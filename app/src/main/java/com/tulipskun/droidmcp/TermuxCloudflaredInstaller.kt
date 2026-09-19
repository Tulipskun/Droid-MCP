package com.tulipskun.droidmcp

import android.content.Context
import android.os.Build
import java.io.File

object TermuxCloudflaredInstaller {
    private const val LIBRARY_NAME = "libcloudflared.so"

    @Synchronized
    fun ensureInstalled(context: Context): File {
        val binary = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)
        if (!binary.isFile) {
            throw IllegalStateException(
                "Bundled cloudflared binary is missing: " + binary.absolutePath
            )
        }
        if (!binary.canRead() || binary.length() <= 1024 * 1024) {
            throw IllegalStateException(
                "Bundled cloudflared binary is invalid: " + binary.absolutePath
            )
        }
        if (!isSupportedAbi()) {
            throw IllegalStateException(
                "Unsupported Android ABI: " + Build.SUPPORTED_ABIS.joinToString()
            )
        }

        context.getSharedPreferences("droid_mcp", Context.MODE_PRIVATE)
            .edit()
            .putString("cloudflared_install_status", "Bundled cloudflared ready")
            .apply()

        return binary
    }

    private fun isSupportedAbi(): Boolean {
        return Build.SUPPORTED_ABIS.any {
            it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64"
        }
    }
}
