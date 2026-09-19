package com.tulipskun.droidmcp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CloudflareTunnelRuntimeTest {
    @Test
    fun cloudflaredAndQuickTunnelWorkEndToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val binary = File(
            context.applicationInfo.nativeLibraryDir,
            CloudflareTunnelConfig.BINARY_NAME
        )

        assertTrue("cloudflared is not extracted: ${binary.absolutePath}", binary.isFile)
        assertTrue("cloudflared is not executable: ${binary.absolutePath}", binary.canExecute())
        assertTrue("cloudflared is too small", binary.length() > 1024 * 1024)

        val versionProcess = ProcessBuilder(binary.absolutePath, "version")
            .redirectErrorStream(true)
            .start()
        val versionOutput = versionProcess.inputStream.bufferedReader().use { it.readText() }
        val versionExitCode = versionProcess.waitFor()

        assertEquals("cloudflared version command failed: ${versionOutput}", 0, versionExitCode)
        assertTrue(
            "unexpected cloudflared output: ${versionOutput}",
            versionOutput.contains("cloudflared version", ignoreCase = true)
        )

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val serviceIntent = Intent(context, CloudflareTunnelService::class.java)

        try {
            context.startForegroundService(serviceIntent)

            val preferences = context.getSharedPreferences("droid_mcp", 0)
            val deadline = System.currentTimeMillis() + 120_000
            var quickUrl: String? = null

            while (System.currentTimeMillis() < deadline) {
                val error = preferences.getString("tunnel_error", null)
                quickUrl = preferences.getString("tunnel_url", null)

                if (error != null) {
                    throw AssertionError("Cloudflare Tunnel failed: ${error}")
                }

                if (!quickUrl.isNullOrBlank()) {
                    break
                }

                Thread.sleep(250)
            }

            assertNotNull("Quick Tunnel URL was not produced", quickUrl)
            assertTrue(
                "Unexpected Quick Tunnel URL: ${quickUrl}",
                quickUrl!!.startsWith("https://") &&
                    quickUrl.endsWith(".trycloudflare.com")
            )

            var responseBody: String? = null
            var responseCode = -1
            var lastError: Throwable? = null

            repeat(10) {
                try {
                    val connection = URL("${quickUrl}/mcp").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty(
                        "MCP-Protocol-Version",
                        McpServer.PROTOCOL_VERSION
                    )
                    connection.setRequestProperty("Mcp-Method", "server/discover")
                    connection.doOutput = true

                    val body =
                        """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"${McpServer.PROTOCOL_VERSION}","io.modelcontextprotocol/clientCapabilities":{}}}}"""

                    connection.outputStream.use {
                        it.write(body.toByteArray(Charsets.UTF_8))
                    }

                    responseCode = connection.responseCode
                    responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    if (responseCode == 200) {
                        break
                    }
                } catch (error: Throwable) {
                    lastError = error
                }

                Thread.sleep(2_000)
            }

            assertEquals(
                "Quick Tunnel MCP request failed: code=${responseCode} error=${lastError} body=${responseBody}",
                200,
                responseCode
            )
            assertTrue(
                "Unexpected MCP response: ${responseBody}",
                responseBody?.contains("Droid-MCP") == true
            )
        } finally {
            context.stopService(serviceIntent)
            instrumentation.runOnMainSync {
                activity.finishAndRemoveTask()
            }
        }
    }
}
