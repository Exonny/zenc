# Zen Gate · Android

把 [zen-gate](https://github.com/LAGcomcom/zen-gate) 的本地网关打包成 Android APK。

启动后在手机上跑一个 OpenAI / Anthropic 兼容网关，**Operit、ChatBox、Cherry Studio、任意 SDK** 都能填地址连上来用。

```
手机上的 Agent (Operit / ChatBox / …)
        │  http://127.0.0.1:8789/v1  +  API Key
        ▼
┌────────────────────────────────┐
│  Zen Gate (本 App)             │
│  Go 网关 (gomobile AAR)        │
│  ├─ OpenCode Zen 免费车道      │
│  ├─ 指纹对齐 / 限流自动切换    │
│  ├─ sing-box 出口轮换（可选）  │
│  └─ 自带管理面板 (WebView)     │
└────────────────────────────────┘
```

## 端口

默认 **8789**（避开 traework2api 的 8787）。`app/src/main/java/.../Gateway.kt` 里的 `DEFAULT_PORT` 是唯一一处。

## 构建

推上去就行，GitHub Actions 自动出 APK：

- `build-go` —— 先只用 Go 编一遍 arm64，证明上游代码能编过（快，不下载 NDK）
- `build-apk` —— 装 NDK + gomobile bind + gradle，产出 `app-debug.apk`

产物在 Actions 的 **Artifacts → zen-gate-android-debug-apk**。

手动触发时可以指定上游 ref：
```
Actions → build-apk → Run workflow → upstream_ref: v1.10.0
```

## 结构

```
mobile/mobile.go            Go 侧入口（Start/Stop/Status），gomobile 导出
patches/subs-android.patch  sing-box 平台名的单行修改
app/src/main/java/.../      Kotlin 壳
  Gateway.kt                包住 AAR 的门面
  GatewayService.kt         前台服务 + 保活（WakeLock / AlarmManager 看门狗）
  WatchdogReceiver.kt       闹钟回调，被强杀后拉回
  MainActivity.kt           状态条 + WebView 加载自带面板
.github/workflows/android.yml
```

## 改了什么、为什么

| 改动 | 原因 |
|---|---|
| `AllowLan = true`，监听 `0.0.0.0` | 同机的另一个 App 连不到只绑 loopback 的 socket |
| 端口 8789 | 你的 traework2api 已经占了 8787 |
| `singBoxPlatform()` 在 android 下返回 linux | SagerNet 没有 android 版发行物，Android 用户态就是 Linux |
| `mobile/` 复制进 `goroot/` 再 bind | gomobile 要求包在被 import 的 module 内 |
| 不 fork 上游 | CI 拉 tarball + 打补丁，上游更新零合并成本 |

## 已知边界

- **只有 arm64-v8a / x86_64**：`armeabi-v7a` 需要第二次 gomobile bind，体积翻倍，这个项目不做。
- **没有开机自启**：manifest 刻意不含 `RECEIVE_BOOT_COMPLETED`。开机自动监听端口是用户按下了 App 才该发生的事。
- **国产 ROM 保活看运气**：前台服务 + WakeLock + 15 分钟精确闹钟 + 电池白名单，四层都做了；但 MIUI/ColorOS 仍然可能杀。UI 上有「保活」按钮引导加白名单 —— 那一层比其它所有技术手段都管用。
- **sing-box 侧车未验证**：patch 让它能下载到正确的 arm64 发行物，但 Android 上执行子进程属于首次验证范围。不用订阅轮换的话不碰这条路径。
- **首次启动要联网**：模型目录是从上游拉的，没有预置缓存。

## 免责声明

仅供个人研究。走的是 OpenCode Zen 的非公开免费车道（靠客户端指纹对齐），上游随时可能改；上游条款由你自己评估。
