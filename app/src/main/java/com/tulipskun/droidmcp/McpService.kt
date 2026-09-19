package com.tulipskun.droidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class McpService : Service() {
    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Droid-MCP",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Droid-MCP")
            .setContentText("MCP server listening on port 8787")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val preferences = getSharedPreferences("droid_mcp", MODE_PRIVATE)
        try {
            server = McpServer(8787, ShizukuBridge(preferences))
            server!!.start()
            isRunning = true
        } catch (_: Throwable) {
            isRunning = false
            server = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "droid_mcp"
        private const val NOTIFICATION_ID = 8787

        @Volatile
        var isRunning: Boolean = false
    }
}
