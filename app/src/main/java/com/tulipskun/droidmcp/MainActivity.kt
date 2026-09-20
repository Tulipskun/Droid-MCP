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
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var mcpStatus: TextView
    private lateinit var endpoint: TextView
    private lateinit var tunnelStatus: TextView
    private lateinit var tunnelEndpoint: TextView
    private lateinit var copyTunnelUrl: Button
    private lateinit var tunnelInstallStatus: TextView
    private lateinit var tunnelToggle: Button
    private lateinit var tunnelId: EditText
    private lateinit var tunnelToken: EditText
    private lateinit var tunnelHostname: EditText
    private lateinit var mcpAccessToken: EditText
    private lateinit var copyMcpToken: Button
    private lateinit var mcpToggle: Button
    private lateinit var shizukuConnect: Button
    private lateinit var keyboardEnable: Button
    private lateinit var keyboardSwitch: Button
    private lateinit var mainScroll: ScrollView

    private val preferences by lazy {
        getSharedPreferences("droid_mcp", MODE_PRIVATE)
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tunnelPoll = object : Runnable {
        override fun run() {
            refreshTunnel()
            uiHandler.postDelayed(this, 500)
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

        shizukuStatus = findViewById(R.id.shizuku_status)
        mcpStatus = findViewById(R.id.mcp_status)
        endpoint = findViewById(R.id.endpoint)
        tunnelStatus = findViewById(R.id.tunnel_status)
        tunnelEndpoint = findViewById(R.id.tunnel_endpoint)
        copyTunnelUrl = findViewById(R.id.copy_tunnel_url)
        tunnelInstallStatus = findViewById(R.id.tunnel_install_status)
        tunnelToggle = findViewById(R.id.tunnel_toggle)
        tunnelId = findViewById(R.id.tunnel_id)
        tunnelToken = findViewById(R.id.tunnel_token)
        tunnelHostname = findViewById(R.id.tunnel_hostname)
        mcpAccessToken = findViewById(R.id.mcp_access_token)
        copyMcpToken = findViewById(R.id.copy_mcp_token)
        mcpToggle = findViewById(R.id.toggle)
        shizukuConnect = findViewById(R.id.connect_shizuku)
        keyboardEnable = findViewById(R.id.enable_keyboard)
        keyboardSwitch = findViewById(R.id.switch_keyboard)
        installKeyboardInsetsHandling()

        loadTunnelSettings()

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
        copyMcpToken.setOnClickListener { copyMcpAccessToken() }

        val save = {
            preferences.edit()
                .putString("cloudflare_tunnel_id", tunnelId.text.toString().trim())
                .putString("cloudflare_tunnel_token", tunnelToken.text.toString().trim())
                .putString("cloudflare_tunnel_hostname", tunnelHostname.text.toString().trim())
                .putString("mcp_access_token", mcpAccessToken.text.toString().trim())
                .apply()
            Toast.makeText(this, "Cloudflare Tunnel settings saved", Toast.LENGTH_SHORT).show()
        }
        tunnelId.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) save() else view.postDelayed({ mainScroll.smoothScrollTo(0, view.bottom) }, 180)
        }
        tunnelToken.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) save() else view.postDelayed({ mainScroll.smoothScrollTo(0, view.bottom) }, 180)
        }
        mcpAccessToken.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) save() else view.postDelayed({ mainScroll.smoothScrollTo(0, view.bottom) }, 180)
        }
        tunnelHostname.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) save() else view.postDelayed({ mainScroll.smoothScrollTo(0, view.bottom) }, 180)
        }
    }

    private fun installKeyboardInsetsHandling() {
        val baseBottomPadding = mainScroll.paddingBottom
        mainScroll.setOnApplyWindowInsetsListener { view, insets ->
            val imeBottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                0
            }
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                baseBottomPadding + imeBottom
            )
            insets
        }

        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.postDelayed({
                    mainScroll.smoothScrollTo(0, view.bottom.coerceAtLeast(0))
                }, 180)
            }
        }
        tunnelId.onFocusChangeListener = focusListener
        tunnelToken.onFocusChangeListener = focusListener
        tunnelHostname.onFocusChangeListener = focusListener
        mcpAccessToken.onFocusChangeListener = focusListener

        mainScroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (currentFocus is EditText) {
                val focused = currentFocus
                focused?.post {
                    val rect = android.graphics.Rect()
                    focused.getDrawingRect(rect)
                    mainScroll.offsetDescendantRectToMyCoords(focused, rect)
                    val visibleBottom = mainScroll.height - mainScroll.paddingBottom
                    if (rect.bottom > visibleBottom) {
                        mainScroll.smoothScrollBy(0, rect.bottom - visibleBottom + 32)
                    }
                }
            }
        }
        mainScroll.requestApplyInsets()
    }

    private fun loadTunnelSettings() {
        tunnelId.setText(preferences.getString("cloudflare_tunnel_id", ""))
        tunnelToken.setText(preferences.getString("cloudflare_tunnel_token", ""))
        tunnelHostname.setText(
            preferences.getString("cloudflare_tunnel_hostname", "")
        )
        var accessToken = preferences.getString("mcp_access_token", "")?.trim().orEmpty()
        if (accessToken.isBlank()) {
            accessToken = java.util.UUID.randomUUID().toString().replace("-", "") +
                java.util.UUID.randomUUID().toString().replace("-", "")
            preferences.edit().putString("mcp_access_token", accessToken).apply()
        }
        mcpAccessToken.setText(accessToken)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        refreshMcp()
        refreshTunnel()
        uiHandler.post(tunnelPoll)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(tunnelPoll)
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

    private fun toggleMcp() {
        val intent = Intent(this, McpService::class.java)
        if (McpService.isRunning) {
            stopService(intent)
        } else if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        uiHandler.postDelayed({ refreshMcp() }, 300)
    }

    private fun toggleTunnel() {
        if (!McpService.isRunning) {
            Toast.makeText(this, "Start MCP Server first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CloudflareTunnelService::class.java)
        if (CloudflareTunnelService.isRunning) {
            stopService(intent)
        } else if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        uiHandler.postDelayed({ refreshTunnel() }, 300)
    }

    private fun refreshMcp() {
        mcpStatus.text = if (McpService.isRunning) {
            "Running on 0.0.0.0:" + CloudflareTunnelConfig.LOCAL_PORT
        } else "Stopped"
        endpoint.text = "http://<ANDROID_IP>:" + CloudflareTunnelConfig.LOCAL_PORT + "/mcp"
        mcpToggle.text = if (McpService.isRunning) "Stop MCP Server" else "Start MCP Server"
    }

    private fun copyMcpAccessToken() {
        val token = mcpAccessToken.text.toString().trim()
        if (token.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Droid-MCP Access Token", token))
        Toast.makeText(this, "MCP access token copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyTunnelUrl() {
        val url = CloudflareTunnelService.tunnelUrl
            ?: preferences.getString("tunnel_url", null)
            ?: buildHostnameUrl()
        if (url.isBlank()) {
            Toast.makeText(this, "Cloudflare Tunnel URL is not configured", Toast.LENGTH_SHORT).show()
            return
        }
        val endpoint = normalizeMcpUrl(url)
        if (endpoint.isBlank()) {
            Toast.makeText(this, "Cloudflare Tunnel URL is not configured", Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Droid-MCP URL", endpoint))
        Toast.makeText(this, "MCP URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun buildHostnameUrl(): String {
        return normalizeMcpUrl(tunnelHostname.text.toString())
    }

    private fun normalizeMcpUrl(value: String): String {
        var url = value.trim()
        if (url.isBlank()) return ""
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) {
            url = "https://$url"
        }
        url = url.trimEnd('/')
        while (url.endsWith("/mcp", ignoreCase = true)) {
            url = url.dropLast(4).trimEnd('/')
        }
        return if (url.isBlank()) "" else "$url/mcp"
    }

    private fun refreshTunnel() {
        val installStatus = preferences.getString("cloudflared_install_status", null)
        val storedRunning = preferences.getBoolean("tunnel_running", false)
        val storedUrl = preferences.getString("tunnel_url", null)
        val storedError = preferences.getString("tunnel_error", null)
        val running = CloudflareTunnelService.isRunning || storedRunning
        val url = CloudflareTunnelService.tunnelUrl ?: storedUrl
        val error = CloudflareTunnelService.lastError ?: storedError

        tunnelInstallStatus.text = installStatus ?: "Not installed"
        tunnelStatus.text = when {
            error != null -> error
            url != null -> "Named Tunnel active"
            running -> "Starting Named Tunnel..."
            else -> "Stopped"
        }
        val endpointUrl = normalizeMcpUrl(url ?: tunnelHostname.text.toString())
        tunnelEndpoint.text = if (endpointUrl.isBlank()) "Not configured" else endpointUrl
        tunnelToggle.text = if (CloudflareTunnelService.isRunning) {
            "Stop Cloudflare Tunnel"
        } else "Start Cloudflare Tunnel"
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 2001
    }
}
