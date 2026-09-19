package com.tulipskun.droidmcp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var mcpStatus: TextView
    private lateinit var endpoint: TextView
    private lateinit var tunnelStatus: TextView
    private lateinit var tunnelEndpoint: TextView
    private lateinit var tunnelInstallStatus: TextView
    private lateinit var mcpToggle: Button
    private lateinit var tunnelToggle: Button
    private lateinit var shizukuConnect: Button
    private lateinit var keyboardEnable: Button
    private lateinit var keyboardSwitch: Button

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
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                refreshShizuku()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuStatus = findViewById(R.id.shizuku_status)
        mcpStatus = findViewById(R.id.mcp_status)
        endpoint = findViewById(R.id.endpoint)
        tunnelStatus = findViewById(R.id.tunnel_status)
        tunnelEndpoint = findViewById(R.id.tunnel_endpoint)
        tunnelInstallStatus = findViewById(R.id.tunnel_install_status)
        mcpToggle = findViewById(R.id.toggle)
        tunnelToggle = findViewById(R.id.tunnel_toggle)
        shizukuConnect = findViewById(R.id.connect_shizuku)
        keyboardEnable = findViewById(R.id.enable_keyboard)
        keyboardSwitch = findViewById(R.id.switch_keyboard)

        Shizuku.addRequestPermissionResultListener(
            shizukuPermissionListener
        )

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        shizukuConnect.setOnClickListener {
            connectShizuku()
        }

        keyboardEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        keyboardSwitch.setOnClickListener {
            getSystemService(InputMethodManager::class.java)
                .showInputMethodPicker()
        }

        mcpToggle.setOnClickListener {
            toggleMcp()
        }

        tunnelToggle.setOnClickListener {
            toggleTunnel()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(
            shizukuPermissionListener
        )
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

            if (
                Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                shizukuStatus.text = "Shizuku connected"
                return
            }

            Shizuku.requestPermission(
                SHIZUKU_PERMISSION_REQUEST
            )
            shizukuStatus.text = "Waiting for Shizuku permission..."
        } catch (error: Throwable) {
            shizukuStatus.text =
                error.message ?: "Shizuku connection failed"
        }
    }

    private fun refreshShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.text = "Shizuku is not running"
                shizukuConnect.text = "Connect Shizuku"
                return
            }

            val granted =
                Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED

            shizukuStatus.text =
                if (granted) {
                    "Shizuku connected"
                } else {
                    "Shizuku permission required"
                }

            shizukuConnect.text =
                if (granted) {
                    "Reconnect Shizuku"
                } else {
                    "Authorize Shizuku"
                }
        } catch (error: Throwable) {
            shizukuStatus.text =
                error.message ?: "Shizuku unavailable"
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
        mcpStatus.text =
            if (McpService.isRunning) {
                "Running on 0.0.0.0:" + CloudflareTunnelConfig.LOCAL_PORT
            } else {
                "Stopped"
            }

        endpoint.text =
            "http://<ANDROID_IP>:" +
                CloudflareTunnelConfig.LOCAL_PORT +
                "/mcp"

        mcpToggle.text =
            if (McpService.isRunning) {
                "Stop MCP Server"
            } else {
                "Start MCP Server"
            }
    }

    private fun refreshTunnel() {
        val installStatus = preferences.getString("cloudflared_install_status", null)
        val storedRunning = preferences.getBoolean("tunnel_running", false)
        val storedUrl = preferences.getString("tunnel_url", null)
        val storedError = preferences.getString("tunnel_error", null)
        val running = CloudflareTunnelService.isRunning || storedRunning
        val url = CloudflareTunnelService.quickUrl ?: storedUrl
        val error = CloudflareTunnelService.lastError ?: storedError

        tunnelInstallStatus.text = installStatus ?: "Not installed"

        tunnelStatus.text = when {
            error != null -> error
            url != null -> "Quick Tunnel active"
            running -> "Starting Quick Tunnel..."
            else -> "Stopped"
        }

        tunnelEndpoint.text =
            if (url != null) {
                url + "/mcp"
            } else {
                "https://<random>.trycloudflare.com/mcp"
            }

        tunnelToggle.text =
            if (CloudflareTunnelService.isRunning) {
                "Stop Cloudflare Tunnel"
            } else {
                "Start Cloudflare Tunnel"
            }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 2001
    }
}
