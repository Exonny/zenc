package com.zen.gate.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The whole UI is a status bar plus a WebView pointed at the gateway's own
 * dashboard. zen-gate already ships a complete single-page panel
 * (internal/gateway/web/index.html), so rewriting it in Kotlin would be pure
 * duplication with a permanent drift cost.
 *
 * The four buttons exist because they are things a WebView cannot do for
 * itself: start/stop the process, ask for the two permissions that decide
 * whether a background listener survives, and copy the API key to hand to
 * another app (Operit et al.).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var portLabel: TextView
    private lateinit var toggle: Button
    private lateinit var detail: TextView

    private val PERM_NOTIF = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        askNotificationPermission()
        // Starting from the Activity is fine: we are in the foreground, so the
        // background-start restriction does not apply.
        if (!GatewayService.hasUserIntent(this) || GatewayService.shouldRun(this)) {
            GatewayService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        // The WebView must release its renderer, but the service keeps running.
        runCatching { web.destroy() }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.zg_bg))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.zg_surface))
        }

        status = TextView(this).apply {
            text = "启动中…"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.zg_text))
            textSize = 15f
        }

        detail = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.zg_muted))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }

        portLabel = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.zg_muted))
            textSize = 12f
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        toggle = Button(this).apply {
            text = "停止"
            setOnClickListener {
                if (GatewayService.shouldRun(this@MainActivity)) {
                    GatewayService.stop(this@MainActivity)
                    Toast.makeText(this@MainActivity, "已停止", Toast.LENGTH_SHORT).show()
                } else {
                    GatewayService.setPort(this@MainActivity, Gateway.DEFAULT_PORT)
                    GatewayService.start(this@MainActivity)
                }
                web.postDelayed({ refresh() }, 600)
            }
        }

        val copyKey = Button(this).apply {
            text = "复制 Key"
            setOnClickListener {
                val st = Gateway.status()
                if (!st.running) {
                    Toast.makeText(this@MainActivity, "网关未运行", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                copyText(st.apiKey)
                Toast.makeText(this@MainActivity, "API Key 已复制", Toast.LENGTH_SHORT).show()
            }
        }

        val copyUrl = Button(this).apply {
            text = "复制地址"
            setOnClickListener {
                val st = Gateway.status()
                if (!st.running) {
                    Toast.makeText(this@MainActivity, "网关未运行", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                copyText(st.url)
                Toast.makeText(this@MainActivity, "已复制 ${st.url}", Toast.LENGTH_SHORT).show()
            }
        }

        val battery = Button(this).apply {
            text = "保活"
            setOnClickListener { openBatteryWhitelist() }
        }

        row.addView(toggle, lp())
        row.addView(copyKey, lp())
        row.addView(copyUrl, lp())
        row.addView(battery, lp())

        bar.addView(status)
        bar.addView(portLabel)
        bar.addView(detail)
        bar.addView(row)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Only our own origin may stay inside the WebView.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val u = request?.url ?: return false
                    val host = u.host ?: return false
                    return if (host == "127.0.0.1" || host == "localhost") false
                    else {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                        true
                    }
                }
            }
            setBackgroundColor(0xFF0B0C10.toInt())
        }

        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    private fun refresh() {
        val st = Gateway.status()
        val svcUp = GatewayService.shouldRun(this)
        if (st.running) {
            status.text = "● 运行中"
            status.setTextColor(ContextCompat.getColor(this, R.color.zg_accent))
            portLabel.text = "API  ${st.url}"
            detail.text = "把上面这个地址 + Key 填进 Operit / ChatBox 等客户端。局域网内其它设备用 http://<手机IP>:${Gateway.DEFAULT_PORT}/v1"
            toggle.text = "停止"
        } else {
            status.text = if (svcUp) "○ 启动中 / 启动失败" else "○ 已停止"
            status.setTextColor(ContextCompat.getColor(this, R.color.zg_muted))
            portLabel.text = "端口 ${Gateway.DEFAULT_PORT}"
            detail.text = Gateway.lastError() ?: "点击「启动」开始"
            toggle.text = "启动"
        }
        if (GatewayService.isChannelDisabled(this)) {
            detail.append("\n提示：通知渠道被关闭，前台服务可能被系统降级。")
        }

        val target = if (st.running) Gateway.dashboardUrl(st.url) else null
        if (target != null && web.url != target) {
            web.loadUrl(target)
        }
    }

    private fun openBatteryWhitelist() {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) != true) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }.onFailure {
                    // Some ROMs hide that dialog; the list page always exists.
                    runCatching {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            } else {
                Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_NOTIF) }
            }
        }
    }

    private fun copyText(s: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("zen-gate", s))
    }

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(2), dp(8), dp(2), 0) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
