package com.tulipskun.droidmcp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var adbStatus: TextView
    private lateinit var mcpStatus: TextView
    private lateinit var mcpToggle: Button
    private lateinit var adbConnect: Button

    private val preferences by lazy {
        getSharedPreferences("droid_mcp", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adbStatus = findViewById(R.id.adb_status)
        mcpStatus = findViewById(R.id.mcp_status)
        mcpToggle = findViewById(R.id.toggle)
        adbConnect = findViewById(R.id.connect_adb)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        adbConnect.setOnClickListener {
            checkAdbConnection()
        }

        findViewById<Button>(R.id.enable_keyboard)
            .setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_INPUT_METHOD_SETTINGS
                    )
                )
            }

        findViewById<Button>(R.id.switch_keyboard)
            .setOnClickListener {
                getSystemService(
                    InputMethodManager::class.java
                ).showInputMethodPicker()
            }

        mcpToggle.setOnClickListener {
            val intent = Intent(
                this,
                McpService::class.java
            )

            if (McpService.isRunning) {
                stopService(intent)
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            window.decorView.postDelayed(
                { refreshMcp() },
                300
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMcp()
    }

    private fun checkAdbConnection() {
        adbConnect.isEnabled = false
        adbStatus.text = "Connecting to 127.0.0.1:5555..."

        Thread {
            try {
                val result = AdbBridge(
                    preferences
                ).checkConnection()

                runOnUiThread {
                    adbStatus.text = result
                    adbConnect.text = "Reconnect ADB"
                    adbConnect.isEnabled = true
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    adbStatus.text =
                        "Disconnected: " +
                            (error.message ?: "ADB connection failed")

                    adbConnect.isEnabled = true
                }
            }
        }.start()
    }

    private fun refreshMcp() {
        mcpStatus.text =
            if (McpService.isRunning) {
                "Running on 0.0.0.0:8787"
            } else {
                "Stopped"
            }

        mcpToggle.text =
            if (McpService.isRunning) {
                "Stop MCP Server"
            } else {
                "Start MCP Server"
            }
    }
}
