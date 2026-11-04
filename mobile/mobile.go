// Package mobile is the Android entry point for zen-gate.
//
// It mirrors cmd/zen-gate/main.go's headless branch: open the store, build the
// lane, wire the gateway, start it. Everything the desktop build does for the
// UI (tray, WebView, single-instance mutex, Windows zone unblock, one-click
// update) is deliberately absent — on Android those are the Kotlin shell's job.
//
// The exported surface is intentionally tiny (Start/Stop/Status) because
// gomobile generates JNI glue from it; every extra exported symbol becomes
// another class in the AAR.
package mobile

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"

	"zen-gate/internal/agents"
	"zen-gate/internal/autotag"
	"zen-gate/internal/gateway"
	"zen-gate/internal/lane"
	"zen-gate/internal/logx"
	"zen-gate/internal/notify"
	"zen-gate/internal/qoderhub"
	"zen-gate/internal/store"
	"zen-gate/internal/subs"
)

func setenv(k, v string) { _ = os.Setenv(k, v) }

var (
	mu     sync.Mutex
	cancel context.CancelFunc
	server *gateway.Server
	store_ *store.Store
	logger *logx.Logger
	port   int
)

// Start boots the gateway on 0.0.0.0:<port> and returns the dashboard URL.
//
// home is the app's private data dir (Kotlin passes getFilesDir()+"...").
// It is exported to the child process via ZEN_GATE_HOME so store.Open() and
// every sidecar resolves the same place.
//
// The listener is widened to the LAN on purpose: on Android, another app on the
// same device (Operit, ChatBox, Cherry Studio) cannot reach our process through
// a loopback-only socket, so 0.0.0.0 is required. The API still demands the
// API key on every call — AllowLan only widens the bind, it does not lower the
// auth bar.
func Start(home string, p int) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		return "", fmt.Errorf("zen-gate already running")
	}
	if home == "" {
		return "", fmt.Errorf("empty data dir")
	}
	if p <= 0 {
		p = 8789
	}

	setenv("ZEN_GATE_HOME", home)
	// Headless is the only mode this binary has; make it explicit so any
	// upstream check that reads the env var agrees with us.
	setenv("ZEN_GATE_HEADLESS", "1")

	st, err := store.Open()
	if err != nil {
		return "", fmt.Errorf("open store: %w", err)
	}

	// Force the two settings this build exists for: the port the user picked,
	// and the routable bind. A stale config.json from a previous version would
	// otherwise keep 8787/loopback and silently make the app unreachable.
	st.Mutate(func(c *store.Config) {
		c.Port = p
		c.AllowLan = true
	})
	if err := st.Save(); err != nil {
		return "", fmt.Errorf("save config: %w", err)
	}

	lg := logx.New(st.Home)
	// On Android stderr goes to logcat; keep file logging quiet on stdout.
	lg.SetStdout(false)

	cfg := st.Config()
	ctx, c := context.WithCancel(context.Background())

	ln := lane.NewLane()
	ln.SetDefaultMaxTokens(cfg.DefaultMaxTokens)
	ln.SetChannelGate(st.ChannelEnabled)
	ln.OnChannelRoster = func(channel string, models []lane.ModelInfo) {
		rows := make([]store.ChannelModel, 0, len(models))
		for _, m := range models {
			rows = append(rows, store.ChannelModel{ID: m.ID, Name: m.Name,
				ContextWindow: m.ContextWindow, MaxOutput: m.MaxOutput,
				Vision: m.Vision, Reasoning: m.Reasoning})
		}
		st.WriteChannelRoster(channel, rows)
	}
	ln.SetExposeRegion(cfg.ExposeRegion)
	ln.SetFailover(cfg.FailoverEnabled, cfg.FailoverMax)
	ln.SetSmartRouting(cfg.SmartRouting)
	ln.SetStrategy(cfg.RoutingStrategy)
	ln.SetWorkBuddyCacheKey(cfg.WorkBuddyCacheKey)
	ln.SetTTFTSource(func(model string) int64 {
		avg, _ := st.TTFTStats(model)
		return avg
	})
	ln.LoadThrottleNotes(quotaNotesFromStore(st.SnapshotQuota()))
	ln.SetThrottleUpdate(func(model string, note lane.ThrottleNote) {
		qn := store.QuotaNote{ThrottledAt: note.ThrottledAt,
			CooldownUntil: note.CooldownUntil, LastOK: note.LastOK}
		for _, e := range note.Episodes {
			qn.Episodes = append(qn.Episodes, store.QuotaEpisode{Start: e.Start, End: e.End})
		}
		st.SetQuotaNote(model, qn)
	})
	ln.Logf = lg.Infof

	// Account channels: same snapshot-in / persist-out wiring as main.go.
	ln.SetWorkBuddyAccounts(cfg.WorkBuddyAccounts)
	ln.SetWorkBuddyPersist(func(a lane.WBAccount) {
		st.UpsertWorkBuddyAccount(a)
		_ = st.Save()
	})
	ln.SetCodeArtsAccount(cfg.CodeArts)
	ln.SetCodeArtsPersist(func(a lane.CAAccount) {
		st.Mutate(func(c *store.Config) { c.CodeArts = a })
		_ = st.Save()
	})
	ln.SetZCodeAccounts(cfg.ZCodeAccounts)
	ln.SetZCodePersist(func(a lane.ZCodeAccount) {
		st.UpsertZCodeAccount(a)
		_ = st.Save()
	})
	ln.SetTraeAccounts(cfg.TraeAccounts)
	ln.SetTraePersist(func(a lane.TraeAccount) {
		st.UpsertTraeAccount(a)
		_ = st.Save()
	})
	ln.SetExtAccounts(cfg.ExtAccounts)
	ln.SetExtPersist(func(a lane.ExtAccount) {
		st.UpsertExtAccount(a)
		_ = st.Save()
	})

	// Proxy: Android has no system proxy to read and no env vars set for us,
	// so anything but an explicit "custom" stays direct. "rotate" is wired by
	// the subscription block below, once the sidecar is actually up — setting
	// it here would clear the rotator the block installs a moment later.
	if cfg.ProxyMode == "custom" {
		lane.SetProxy("custom", cfg.ProxyURL)
	} else if cfg.ProxyMode != "rotate" {
		lane.SetProxy("direct", "")
	}

	// sing-box sidecar. Enabled only when the user configured subscriptions:
	// on Android the download is arm64 Linux (see the patch in subs), and a
	// failed sidecar must not take the gateway down with it.
	mgr := subs.NewManager(st, lg.Infof)
	if cfg.SubsEnabled && len(cfg.Subscriptions) > 0 {
		go func() {
			pullCtx, pullCancel := context.WithTimeout(ctx, 3*time.Minute)
			defer pullCancel()
			if err := mgr.Apply(pullCtx); err != nil {
				lg.Errorf("订阅轮询启动失败: %v", err)
				return
			}
			if st.Config().ProxyMode == "rotate" {
				lane.SetProxy("rotate", "")
				lane.SetRotator(mgr)
			}
		}()
	}

	notify.SetEnabled(cfg.Notifications)

	reg := agents.NewRegistry(st)
	gw := gateway.New(ln, st)
	ln.OnProbeResult = func(r lane.ProbeResult) {
		st.AddTTFTSample(r.Model, r.TTFTMs)
		if r.State == lane.StateAvailable {
			gw.ClearAutoHidden(r.Model)
		}
	}
	gw.SetAgents(reg)
	gw.SetSubs(mgr)
	gw.SetLogger(lg)
	gw.ApplyLogSettings(cfg)

	tagger := autotag.New(ln, st, lg.Infof)
	tagger.SetEnabled(cfg.AutoTagEnabled)
	tagger.Start(ctx)
	gw.SetTagger(tagger)
	gw.SetAutostartState(func() bool { return false })

	syncEndpoints := func() {
		reg.SetEndpoints(gw.BaseURL(), gw.InjectableModels())
		reg.ResyncEnabled()
	}
	ln.OnChange = syncEndpoints
	gw.SetModelsSync(syncEndpoints)
	ln.StartLoops(ctx, time.Duration(cfg.ProbeIntervalMinutes)*time.Minute)

	gw.StartOpenRefLoop(ctx)
	gw.StartCustomProbeLoop(ctx)
	syncEndpoints()

	if err := gw.Start(); err != nil {
		c()
		lg.Close()
		mgr.Stop()
		return "", fmt.Errorf("listen on port %d: %w", p, err)
	}

	// Qoder's embedded gateway rides this process on 127.0.0.1:8095. A busy
	// port is not fatal (an external one keeps serving), same as main.go.
	_, _ = qoderhub.Start()

	cancel = c
	server = gw
	store_ = st
	logger = lg
	port = p

	return gw.BaseURL(), nil
}

// Stop shuts the gateway down in the reverse order of Start. Safe to call when
// nothing is running (the Kotlin shell calls it unconditionally from
// onDestroy).
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	if server != nil {
		server.Stop()
	}
	if cancel != nil {
		cancel()
	}
	if logger != nil {
		logger.Close()
	}
	server, cancel, logger, store_ = nil, nil, nil, nil
}

// Status is a tiny health report for the shell's status bar. It never touches
// the network: it answers "is our listener up", not "is the upstream healthy".
func Status() (running bool, url string, apiKey string) {
	mu.Lock()
	defer mu.Unlock()
	if server == nil || store_ == nil {
		return false, "", ""
	}
	cfg := store_.Config()
	return true, server.BaseURL(), cfg.MainKey
}

// APIVersion lets the Kotlin side verify the AAR it linked against is the one
// it thinks it is, without reparsing a version string.
func APIVersion() string { return gateway.Version }

// quotaNotesFromStore converts persisted quota notes back into the lane's shape
// for boot-time seeding. main.go keeps its own copy (package main cannot export
// to us); without this the lane forgets every cooldown across a restart.
func quotaNotesFromStore(in map[string]store.QuotaNote) map[string]lane.ThrottleNote {
	out := map[string]lane.ThrottleNote{}
	for m, n := range in {
		note := lane.ThrottleNote{ThrottledAt: n.ThrottledAt, CooldownUntil: n.CooldownUntil, LastOK: n.LastOK}
		for _, e := range n.Episodes {
			note.Episodes = append(note.Episodes, lane.ThrottleEpisode{Start: e.Start, End: e.End})
		}
		out[m] = note
	}
	return out
}
