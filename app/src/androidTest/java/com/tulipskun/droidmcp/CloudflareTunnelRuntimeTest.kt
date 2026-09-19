package com.tulipskun.droidmcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CloudflareTunnelRuntimeTest {
    @Test
    fun bundledCloudflaredIsExecutable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

        assertTrue("cloudflared is not extracted: ${binary.absolutePath}", binary.isFile)
        assertTrue("cloudflared is not executable: ${binary.absolutePath}", binary.canExecute())
        assertTrue("cloudflared is too small", binary.length() > 1024 * 1024)

        val process = ProcessBuilder(binary.absolutePath, "version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertEquals("cloudflared version command failed: $output", 0, exitCode)
        assertTrue(
            "unexpected cloudflared output: $output",
            output.contains("cloudflared version", ignoreCase = true)
        )
    }
}
