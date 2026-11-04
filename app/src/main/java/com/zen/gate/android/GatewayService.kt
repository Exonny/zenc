package com.zen.gate.android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Keeps the gateway alive once the user leaves the Activity.
 *
 * Two layers, both required on real hardware:
 *
 *  **Standard Android**
 *   1. foreground service + persistent notification — the only service shape
 *      Android 8+ will not casually reclaim
 *   2. PARTIAL_WAKE_LOCK — the Cached App Freezer skips a locking process;
 *      without it the socket stays open but the process stops being scheduled
 *      and requests hang instead of erroring
 *   3. START_STICKY — rebuilt after a reclaim
 *   4. stopWithTask=false (manifest) — swiping recents does not destroy it
 *
 *  **Vendor ROMs (MIUI / ColorOS / EMUI)**
 *   5. onTaskRemoved re-launches immediately; if the background-start limit
 *      rejects that, a 5s exact alarm picks it up instead
 *   6. a 15-minute AlarmManager watchdog — only setExactAndAllowWhileIdle gets
 *      through Doze. Do not shorten it: more frequent wakes get classified as
 *      abnormal and blocked, which is *less* reliable, not more
 *   7. should_run persists user intent, so "I stopped it" and "the system
 *      killed it" are different states
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "ZenGateway"
        private const val CHANNEL_ID = "zen_gate_gateway"
        private const val NOTIFICATION_ID = 8790
        private const val WAKELOCK_TAG = "zen-gate:gateway-wakelock"

        const val ACTION_STOP = "com.zen.gate.android.STOP"
        const val ACTION_WATCHDOG = "com.zen.gate.android.WATCHDOG"

        /** Do not lower: vendor ROMs block more frequent exact wakes outright. */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L

        /**
         * 10 minutes, shorter than the watchdog interval so it is always renewed
         * before expiry. An indefinite lock gets flagged as an abnormal battery
         * drainer and cleaned up first — the opposite of what we want.
         */
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L

        private const val WATCHDOG_REQUEST = 8791
        private const val TASK_REMOVED_REQUEST = 8792
        private const val SERVICE_REQUEST = 8793

        private const val PREFS = "zen_gate_service"
        private const val KEY_SHOULD_RUN = "should_run"
        private const val KEY_PORT = "port"

        @Volatile
        var running = false
            private set

        fun markShouldRun(ctx: Context, value: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOULD_RUN, value).apply()
        }

        fun shouldRun(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOULD_RUN, false)

        /** Distinguishes "never touched" from "explicitly stopped". */
        fun hasUserIntent(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SHOULD_RUN)

        fun port(ctx: Context): Int =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PORT, Gateway.DEFAULT_PORT)

        fun setPort(ctx: Context, p: Int) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PORT, p).apply()
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GatewayService::class.java).setAction(ACTION_STOP))
        }

        /** True when the user muted our notification channel, which quietly weakens the service. */
        fun isChannelDisabled(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return false
            return mgr.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        // A watchdog alarm already in flight cannot be cancelled; if the user
        // stopped the service in the meantime, refuse to revive it.
        if (intent?.action == ACTION_WATCHDOG && !shouldRun(applicationContext)) {
            Log.i(TAG, "watchdog fired but user stopped the service; not reviving")
            cancelWatchdog()
            return START_NOT_STICKY
        }

        markShouldRun(applicationContext, true)
        // Must reach startForeground within seconds or the system treats it as ANR.
        startForeground(NOTIFICATION_ID, notification())
        acquireWakeLock()

        val url = Gateway.start(applicationContext, port(applicationContext))
        if (url == null) {
            Log.e(TAG, "gateway failed to start: ${Gateway.lastError()}")
            // Keep the service alive but surface the failure in the notification
            // rather than looping a restart storm against a bind error.
            updateNotification("启动失败：${Gateway.lastError() ?: "未知错误"}")
        } else {
            running = true
            updateNotification("运行中 · $url")
        }

        scheduleWatchdog()
        return START_STICKY
    }

    /**
     * Swiped off recents. Vendor ROMs kill the foreground service here even with
     * stopWithTask=false. Android 12+ refuses a background foreground-service
     * start, so fall back to a 5s exact alarm — alarms are system callbacks and
     * are exempt from that restriction.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!shouldRun(applicationContext)) {
            super.onTaskRemoved(rootIntent)
            return
        }
        val ok = runCatching {
            val restart = Intent(applicationContext, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart) else startService(restart)
        }.isSuccess
        if (!ok) scheduleAlarm(TASK_REMOVED_REQUEST, 5_000L, "post-swipe revival")
        scheduleWatchdog()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Gateway.stop()
        running = false
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopNow() {
        running = false
        markShouldRun(applicationContext, false)
        cancelWatchdog()
        Gateway.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "gateway stopped by user")
    }

    // ---------------------------------------------------------------- alarms

    private fun scheduleWatchdog() = scheduleAlarm(WATCHDOG_REQUEST, WATCHDOG_INTERVAL_MS, "watchdog")

    private fun scheduleAlarm(request: Int, delayMs: Long, why: String) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(applicationContext, WatchdogReceiver::class.java)
            .putExtra("reason", why)
        val pi = PendingIntent.getBroadcast(
            applicationContext, request, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            // Permission revoked mid-flight: degrade to inexact rather than crash.
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelWatchdog() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            applicationContext, WATCHDOG_REQUEST,
            Intent(applicationContext, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    // ------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "本地网关", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zen Gate 本地 API 网关的运行状态"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopBtn = PendingIntent.getService(
            applicationContext, 1,
            Intent(applicationContext, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Zen Gate 运行中")
            .setContentText("本地网关正在监听")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 26) {
            b.addAction(Notification.Action.Builder(null, "停止", stopBtn).build())
        }
        return b.build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            val n = notification()
            n.javaClass.getDeclaredField("extras").let { }
            mgr.notify(NOTIFICATION_ID, n)
        }
    }
}
