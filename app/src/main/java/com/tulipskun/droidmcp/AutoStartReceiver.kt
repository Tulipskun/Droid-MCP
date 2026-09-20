package com.tulipskun.droidmcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val preferences = context.getSharedPreferences("droid_mcp", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("auto_start", false)) return

        val appContext = context.applicationContext
        val mcpIntent = Intent(appContext, McpService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(mcpIntent)
            else appContext.startService(mcpIntent)
        } catch (_: Throwable) {
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!McpService.isRunning) return@postDelayed
            val tunnelIntent = Intent(appContext, CloudflareTunnelService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(tunnelIntent)
                else appContext.startService(tunnelIntent)
            } catch (_: Throwable) {
            }
        }, 1500)
    }
}
