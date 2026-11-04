package com.zen.gate.android

import android.content.Context
import mobile.Mobile

/**
 * Thin wrapper over the gomobile AAR.
 *
 * Why this exists instead of calling mobile.Mobile directly everywhere:
 *  - the Go side is a singleton by nature (one listener, one store), so every
 *    entry point must agree on whether it is up. One place to ask.
 *  - the API key lives in the same config.json the Go side writes; reading it
 *    back through Status() means Kotlin never has to parse JSON.
 *  - gomobile panics are converted to Java exceptions but *not* caught for you;
 *    a failed Start inside a service must not take the process down.
 */
object Gateway {

    const val DEFAULT_PORT = 8789

    /** Data dir = app-private files/zen-gate. Survives app restart, wiped on uninstall. */
    fun home(ctx: Context): String {
        val dir = java.io.File(ctx.filesDir, "zen-gate")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    @Volatile
    private var lastError: String? = null

    fun lastError(): String? = lastError

    /**
     * Boots the gateway. Idempotent: if it is already up this returns the same
     * URL instead of failing on EADDRINUSE.
     */
    @Synchronized
    fun start(ctx: Context, port: Int = DEFAULT_PORT): String? {
        runCatching { Mobile.status() }.getOrNull()?.let { st ->
            if (st.running) return st.uRL
        }
        return try {
            val url = Mobile.start(home(ctx.applicationContext), port.toLong())
            lastError = null
            url
        } catch (t: Throwable) {
            // gomobile surfaces Go errors as Exception(runtime.Exception)
            lastError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    @Synchronized
    fun stop() {
        runCatching { Mobile.stop() }
    }

    data class Status(val running: Boolean, val url: String, val apiKey: String)

    fun status(): Status = runCatching {
        val s = Mobile.status()
        Status(s.running, s.uRL, s.apiKey)
    }.getOrElse { Status(false, "", "") }

    /** Dashboard lives at the API root, not under /v1. */
    fun dashboardUrl(url: String): String = url.removeSuffix("/v1")
}
