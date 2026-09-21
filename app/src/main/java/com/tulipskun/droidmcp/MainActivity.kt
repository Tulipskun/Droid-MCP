package com.tulipskun.droidmcp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var mcpStatus: TextView
    private lateinit var endpoint: TextView
    private lateinit var tunnelStatus: TextView
    private lateinit var tunnelEndpoint: TextView
    private lateinit var copyTunnelUrl: Button
    private lateinit var tunnelToggle: Button
    private lateinit var mcpToggle: Button
    private lateinit var shizukuConnect: Button
    private lateinit var keyboardEnable: Button
    private lateinit var keyboardSwitch: Button
    private lateinit var mainScroll: ScrollView
    private lateinit var autoStart: MaterialSwitch
    private lateinit var webhookUrl: EditText
    private lateinit var webhookStatus: TextView
    private lateinit var sendWebhook: Button
    private lateinit var copyCurl: Button

    private var mcpActionPending = false
    private var tunnelActionPending = false
    private var mcpDesiredRunning = false
    private var tunnelDesiredRunning = false
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private val preferences by lazy {
        getSharedPreferences("droid_mcp", MODE_PRIVATE)
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val servicePoll = object : Runnable {
        override fun run() {
            refreshMcp()
            refreshTunnel()
            uiHandler.postDelayed(this, 250)
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST) refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mainScroll = findViewById(R.id.main_scroll)
        autoStart = findViewById(R.id.auto_start)

        shizukuStatus = findViewById(R.id.shizuku_status)
        mcpStatus = findViewById(R.id.mcp_status)
        endpoint = findViewById(R.id.endpoint)
        tunnelStatus = findViewById(R.id.tunnel_status)
        tunnelEndpoint = findViewById(R.id.tunnel_endpoint)
        copyTunnelUrl = findViewById(R.id.copy_tunnel_url)
        tunnelToggle = findViewById(R.id.tunnel_toggle)
        mcpToggle = findViewById(R.id.toggle)
        shizukuConnect = findViewById(R.id.connect_shizuku)
        keyboardEnable = findViewById(R.id.enable_keyboard)
        keyboardSwitch = findViewById(R.id.switch_keyboard)
        webhookUrl = findViewById(R.id.webhook_url)
        webhookStatus = findViewById(R.id.webhook_status)
        sendWebhook = findViewById(R.id.send_webhook)
        copyCurl = findViewById(R.id.copy_curl)
        installKeyboardInsetsHandling()

        webhookUrl.setText(preferences.getString("webhook_url", ""))
        updateWebhookButtons()
        webhookUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveWebhookUrl()
        }
        sendWebhook.setOnClickListener { sendMcpUrlToWebhook() }
        copyCurl.setOnClickListener { copyCurlCommand() }

        autoStart.isChecked = preferences.getBoolean("auto_start", false)
        autoStart.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("auto_start", checked).apply()
            if (checked) {
                startMcpIfNeeded()
                uiHandler.postDelayed({ startTunnelIfNeeded() }, 1200)
            }
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        shizukuConnect.setOnClickListener { connectShizuku() }
        keyboardEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        keyboardSwitch.setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        mcpToggle.setOnClickListener { toggleMcp() }
        tunnelToggle.setOnClickListener { toggleTunnel() }
        copyTunnelUrl.setOnClickListener { copyTunnelUrl() }
        if (preferences.getBoolean("auto_start", false)) {
            startMcpIfNeeded()
            uiHandler.postDelayed({ startTunnelIfNeeded() }, 1200)
        }
    }

    private fun installKeyboardInsetsHandling() {
        val baseBottomPadding = mainScroll.paddingBottom
        mainScroll.setOnApplyWindowInsetsListener { view, insets ->
            val systemBottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else 0
            val imeBottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else 0
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottomPadding + maxOf(systemBottom, imeBottom))
            insets
        }
        mainScroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (currentFocus is EditText) {
                val focused = currentFocus
                focused?.post {
                    val rect = android.graphics.Rect()
                    focused.getDrawingRect(rect)
                    mainScroll.offsetDescendantRectToMyCoords(focused, rect)
                    val visibleBottom = mainScroll.height - mainScroll.paddingBottom
                    if (rect.bottom > visibleBottom) mainScroll.smoothScrollBy(0, rect.bottom - visibleBottom + 32)
                }
            }
        }
        mainScroll.requestApplyInsets()
    }

    private fun saveWebhookUrl(): String {
        val value = webhookUrl.text.toString().trim()
        preferences.edit().putString("webhook_url", value).apply()
        return value
    }

    private fun updateWebhookButtons() {
        val configured = webhookUrl.text.toString().trim().isNotBlank()
        val hasMcpUrl = normalizeMcpUrl(CloudflareTunnelService.tunnelUrl ?: preferences.getString("tunnel_url", "") ?: "").isNotBlank()
        sendWebhook.isEnabled = configured && hasMcpUrl
        copyCurl.isEnabled = configured && hasMcpUrl
    }

    private fun sendMcpUrlToWebhook() {
        val webhook = saveWebhookUrl()
        val mcpUrl = normalizeMcpUrl(CloudflareTunnelService.tunnelUrl ?: preferences.getString("tunnel_url", "") ?: "")
        if (!webhook.startsWith("https://", ignoreCase = true)) {
            webhookStatus.text = "Webhook must use HTTPS"
            updateWebhookButtons()
            return
        }
        if (mcpUrl.isBlank()) {
            webhookStatus.text = "Start Cloudflare Tunnel first"
            updateWebhookButtons()
            return
        }

        sendWebhook.isEnabled = false
        copyCurl.isEnabled = false
        webhookStatus.text = "Sending MCP URL..."
        val payload = JSONObject().put("url", mcpUrl).toString()
        networkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(webhook).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                val text = if (code in 200..299) "Webhook sent (HTTP $code)" else "Webhook failed (HTTP $code)"
                uiHandler.post {
                    webhookStatus.text = text
                    updateWebhookButtons()
                }
            } catch (error: Throwable) {
                uiHandler.post {
                    webhookStatus.text = "Webhook failed: ${error.message ?: "network error"}"
                    updateWebhookButtons()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun copyCurlCommand() {
        val webhook = saveWebhookUrl()
        val mcpUrl = normalizeMcpUrl(CloudflareTunnelService.tunnelUrl ?: preferences.getString("tunnel_url", "") ?: "")
        if (webhook.isBlank() || mcpUrl.isBlank()) {
            webhookStatus.text = "Webhook and MCP URL are required"
            updateWebhookButtons()
            return
        }
        val payload = JSONObject().put("url", mcpUrl).toString()
        val command = "curl -X POST ${shellQuote(webhook)} -H ${shellQuote("Content-Type: application/json")} --data ${shellQuote(payload)}"
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Droid-MCP cURL", command))
        webhookStatus.text = "cURL command copied"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    override fun onDestroy() {
        uiHandler.removeCallbacks(servicePoll)
        networkExecutor.shutdownNow()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        refreshMcp()
        refreshTunnel()
        uiHandler.post(servicePoll)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(servicePoll)
        super.onPause()
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.text = "Shizuku is not running"
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuStatus.text = "Shizuku connected"
                return
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            shizukuStatus.text = "Waiting for Shizuku permission..."
        } catch (error: Throwable) {
            shizukuStatus.text = error.message ?: "Shizuku connection failed"
        }
    }

    private fun refreshShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.text = "Shizuku is not running"
                shizukuConnect.text = "Connect Shizuku"
                return
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            shizukuStatus.text = if (granted) "Shizuku connected" else "Shizuku permission required"
            shizukuConnect.text = if (granted) "Reconnect Shizuku" else "Authorize Shizuku"
        } catch (error: Throwable) {
            shizukuStatus.text = error.message ?: "Shizuku unavailable"
        }
    }

    private fun startMcpIfNeeded() {
        if (McpService.isRunning) return
        mcpDesiredRunning = true
        mcpActionPending = true
        refreshMcp()
        val intent = Intent(this, McpService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            mcpActionPending = false
            mcpDesiredRunning = false
            mcpStatus.text = error.message ?: "Unable to start MCP Server"
        }
    }

    private fun startTunnelIfNeeded() {
        if (CloudflareTunnelService.isRunning || !McpService.isRunning) return
        tunnelDesiredRunning = true
        tunnelActionPending = true
        refreshTunnel()
        val intent = Intent(this, CloudflareTunnelService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            tunnelActionPending = false
            tunnelDesiredRunning = false
            tunnelStatus.text = error.message ?: "Unable to start Cloudflare Tunnel"
        }
    }

    private fun toggleMcp() {
        if (mcpActionPending) return
        val intent = Intent(this, McpService::class.java)
        if (McpService.isRunning) {
            if (CloudflareTunnelService.isRunning) {
                tunnelDesiredRunning = false
                tunnelActionPending = true
                stopService(Intent(this, CloudflareTunnelService::class.java))
            }
            mcpDesiredRunning = false
            mcpActionPending = true
            refreshMcp()
            uiHandler.postDelayed({ stopService(intent) }, 250)
        } else {
            startMcpIfNeeded()
        }
    }

    private fun toggleTunnel() {
        if (tunnelActionPending) return
        if (!McpService.isRunning) {
            Toast.makeText(this, "Start MCP Server first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CloudflareTunnelService::class.java)
        if (CloudflareTunnelService.isRunning) {
            tunnelDesiredRunning = false
            tunnelActionPending = true
            refreshTunnel()
            stopService(intent)
        } else {
            startTunnelIfNeeded()
        }
    }

    private fun refreshMcp() {
        val running = McpService.isRunning
        if (mcpActionPending) {
            if (running == mcpDesiredRunning) mcpActionPending = false
        }
        mcpStatus.text = when {
            mcpActionPending && mcpDesiredRunning -> "Starting MCP Server..."
            mcpActionPending && !mcpDesiredRunning -> "Stopping MCP Server..."
            running -> "Running on 0.0.0.0:" + CloudflareTunnelConfig.LOCAL_PORT
            else -> "Stopped"
        }
        endpoint.text = "http://<ANDROID_IP>:" + CloudflareTunnelConfig.LOCAL_PORT + "/mcp"
        mcpToggle.text = when {
            mcpActionPending && mcpDesiredRunning -> "Starting..."
            mcpActionPending -> "Stopping..."
            running -> "Stop MCP Server"
            else -> "Start MCP Server"
        }
        mcpToggle.isEnabled = !mcpActionPending
    }

    private fun copyTunnelUrl() {
        val url = CloudflareTunnelService.tunnelUrl ?: preferences.getString("tunnel_url", null) ?: ""
        val endpoint = normalizeMcpUrl(url)
        if (endpoint.isBlank()) {
            Toast.makeText(this, "Cloudflare Tunnel URL is not configured", Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Droid-MCP URL", endpoint))
        Toast.makeText(this, "MCP URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun normalizeMcpUrl(value: String): String {
        var url = value.trim()
        if (url.isBlank()) return ""
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            url = "https://$url"
        }
        url = url.trimEnd('/')
        while (url.endsWith("/mcp", ignoreCase = true)) url = url.dropLast(4).trimEnd('/')
        return if (url.isBlank()) "" else "$url/mcp"
    }

    private fun refreshTunnel() {
        val storedUrl = preferences.getString("tunnel_url", null)
        val storedError = preferences.getString("tunnel_error", null)
        val running = CloudflareTunnelService.isRunning
        val url = CloudflareTunnelService.tunnelUrl ?: if (running) storedUrl else null
        val error = CloudflareTunnelService.lastError ?: if (running) storedError else null

        if (tunnelActionPending && running == tunnelDesiredRunning) tunnelActionPending = false
        tunnelStatus.text = when {
            tunnelActionPending && tunnelDesiredRunning -> "Starting Quick Tunnel..."
            tunnelActionPending -> "Stopping Quick Tunnel..."
            error != null -> error
            url != null -> "Quick Tunnel active"
            else -> "Stopped"
        }
        val endpointUrl = normalizeMcpUrl(url ?: "")
        tunnelEndpoint.text = if (endpointUrl.isBlank()) "Not configured" else endpointUrl
        tunnelToggle.text = when {
            tunnelActionPending && tunnelDesiredRunning -> "Starting..."
            tunnelActionPending -> "Stopping..."
            running -> "Stop Cloudflare Tunnel"
            else -> "Start Cloudflare Tunnel"
        }
        tunnelToggle.isEnabled = !tunnelActionPending && (McpService.isRunning || running)
        copyTunnelUrl.isEnabled = !tunnelActionPending && endpointUrl.isNotBlank()
        updateWebhookButtons()
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 2001
    }
}
