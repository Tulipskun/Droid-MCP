package com.tulipskun.droidmcp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        toggle.setOnClickListener {
            val intent = Intent(this, McpService::class.java)
            if (McpService.isRunning) {
                stopService(intent)
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            window.decorView.postDelayed({ refresh() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = if (McpService.isRunning) "Running on 0.0.0.0:8787" else "Stopped"
        toggle.text = if (McpService.isRunning) "Stop MCP Server" else "Start MCP Server"
    }
}
