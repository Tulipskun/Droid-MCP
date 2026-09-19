package com.tulipskun.droidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.regex.Pattern

class CloudflareTunnelService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting Cloudflare Tunnel"))

        executor.execute {
            try {
                ensureMcpServer()
                startTunnel()
            } catch (error: Throwable) {
                setState(false, null, error.message ?: "Cloudflare Tunnel failed")
                stopSelf()
            }
        }
    }

    private fun ensureMcpServer() {
        if (McpService.isRunning) return

        startForegroundService(Intent(this, McpService::class.java))

        repeat(50) {
            if (McpService.isRunning) return
            Thread.sleep(100)
        }

        if (!McpService.isRunning) {
            throw IllegalStateException("MCP server could not be started")
        }
    }

    private fun startTunnel() {
        val binary = ensureBundledBinary()
        val builder = ProcessBuilder(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--loglevel",
            "info",
            "--url",
            CloudflareTunnelConfig.LOCAL_ORIGIN
        )

        builder.redirectErrorStream(true)
        builder.environment()["HOME"] = filesDir.absolutePath

        val started = builder.start()
        process = started
        setState(true, null, null)

        val pattern = Pattern.compile(
            "https://[a-z0-9-]+\\.trycloudflare\\.com",
            Pattern.CASE_INSENSITIVE
        )

        BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val match = pattern.matcher(line)
                if (match.find()) {
                    setState(true, match.group(), null)
                }
            }
        }

        val exitCode = started.waitFor()
        process = null

        if (isRunning) {
            setState(false, null, "cloudflared exited with code " + exitCode)
            stopSelf()
        }
    }

    private fun ensureBundledBinary(): File {
        val directory = File(filesDir, "cloudflared")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create cloudflared directory")
        }

        val target = File(directory, CloudflareTunnelConfig.BINARY_NAME)
        if (target.isFile && target.length() > 1024 * 1024) {
            if (!target.setExecutable(true, false)) {
                throw IllegalStateException("Cannot mark bundled cloudflared executable")
            }
            return target
        }

        val partial = File(directory, target.name + ".part")

        applicationContext.assets.open(CloudflareTunnelConfig.BINARY_NAME).use { input ->
            partial.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (partial.length() <= 1024 * 1024) {
            partial.delete()
            throw IllegalStateException("Bundled cloudflared binary is invalid")
        }

        if (!partial.setExecutable(true, false)) {
            partial.delete()
            throw IllegalStateException("Cannot mark bundled cloudflared executable")
        }

        if (!partial.renameTo(target)) {
            target.delete()
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Cannot install bundled cloudflared")
            }
        }

        if (!target.setExecutable(true, false)) {
            throw IllegalStateException("Cannot mark bundled cloudflared executable")
        }

        return target
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Droid-MCP Cloudflare Tunnel",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Droid-MCP")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()

    private fun setState(
        running: Boolean,
        url: String?,
        error: String?
    ) {
        isRunning = running
        quickUrl = url
        lastError = error

        getSharedPreferences("droid_mcp", MODE_PRIVATE)
            .edit()
            .putBoolean("tunnel_running", running)
            .putString("tunnel_url", url)
            .putString("tunnel_error", error)
            .apply()

        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(
                when {
                    error != null -> error
                    url != null -> "Quick Tunnel active"
                    running -> "Starting Quick Tunnel"
                    else -> "Cloudflare Tunnel stopped"
                }
            )
        )
    }

    override fun onDestroy() {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }

        process = null
        isRunning = false
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "droid_mcp_cloudflare"
        private const val NOTIFICATION_ID = 8788

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var quickUrl: String? = null

        @Volatile
        var lastError: String? = null
    }
}
