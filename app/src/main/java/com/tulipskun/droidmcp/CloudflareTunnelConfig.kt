package com.tulipskun.droidmcp

object CloudflareTunnelConfig {
    const val CLOUDFLARED_VERSION = "2026.9.1"
    const val RELEASE_TAG = "cloudflared-android"
    const val BINARY_NAME = "cloudflared-android-arm64"
    const val BINARY_URL =
        "https://github.com/Tulipskun/Droid-MCP/releases/download/" +
            RELEASE_TAG + "/" + BINARY_NAME
    const val LOCAL_PORT = 8787
    const val LOCAL_ORIGIN = "http://127.0.0.1:8787"
}
