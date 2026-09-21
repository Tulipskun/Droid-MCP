package com.tulipskun.droidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors

class CloudflareTunnelService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing Cloudflare Tunnel"))

        executor.execute {
            try {
                ensureMcpServer()
                setState(false, null, null)
                val binary = TermuxCloudflaredInstaller.ensureInstalled(this)
                startTunnel(binary)
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

    private fun startTunnel(binary: File) {
        val home = File(filesDir, "cloudflared-home").apply { mkdirs() }
        val command = listOf(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--protocol",
            "http2",
            "--metrics",
            "127.0.0.1:20241",
            "--url",
            "http://127.0.0.1:" + CloudflareTunnelConfig.LOCAL_PORT
        )

        val started = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = cacheDir.absolutePath
                environment()["GODEBUG"] = "netdns=cgo"
            }
            .start()

        process = started
        val output = mutableListOf<String>()
        BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    output.add(line)
                    if (output.size > MAX_OUTPUT_LINES) output.removeAt(0)
                    val marker = "https://"
                    val start = line.indexOf(marker)
                    if (start >= 0) {
                        val candidate = line.substring(start).split(" ", "\\t").firstOrNull()
                            ?.trimEnd('.', ',', ')')
                        if (candidate != null && candidate.contains("trycloudflare.com")) {
                            setState(true, candidate.trimEnd('/') + "/mcp", null)
                        }
                    }
                }
                Log.i("DroidMCP-cloudflared", line)
            }
        }

        val exitCode = started.waitFor()
        process = null

        if (isRunning) {
            val detail = output.joinToString(" | ").takeLast(MAX_ERROR_CHARS)
            setState(false, null, if (detail.isBlank()) {
                "cloudflared exited with code $exitCode"
            } else {
                "cloudflared exited with code $exitCode: $detail"
            })
            stopSelf()
        }
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

    private fun setState(running: Boolean, url: String?, error: String?) {
        isRunning = running
        tunnelUrl = url
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
        setState(false, null, null)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "cloudflare_tunnel"
        private const val NOTIFICATION_ID = 8788
        private const val MAX_OUTPUT_LINES = 12
        private const val MAX_ERROR_CHARS = 700

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var tunnelUrl: String? = null

        @Volatile
        var lastError: String? = null
    }
}
