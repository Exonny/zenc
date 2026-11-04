package com.zen.gate.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Alarm callback. Runs as a system callback, which is why it can start the
 * foreground service even when the normal background-start limit would refuse.
 *
 * Deliberately dumb: it re-launches the service and lets its onStartCommand
 * decide everything. All policy lives in one place.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (!GatewayService.shouldRun(app)) {
            Log.i("ZenGateway", "watchdog (${intent.getStringExtra("reason")}): user stopped, skip")
            return
        }
        Log.i("ZenGateway", "watchdog (${intent.getStringExtra("reason")}): reviving gateway")
        runCatching { GatewayService.start(app) }
    }
}
