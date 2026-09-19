# Droid-MCP

Android APK exposing an MCP endpoint for remote control of an Android device.

Local endpoint:

http://<ANDROID_IP>:8787/mcp

Quick Tunnel:

https://<random>.trycloudflare.com/mcp

The MCP server binds to 0.0.0.0:8787.

Cloudflare Quick Tunnel is not bundled in the APK. When the tunnel is started, the app reads the current cloudflared package metadata from the official Termux main repository, downloads the matching package for the device ABI, verifies its SHA-256, extracts the cloudflared executable into app-private storage, and runs it locally.

Supported Android ABIs map to the corresponding Termux package architectures:
- arm64-v8a -> aarch64
- armeabi-v7a -> arm
- x86_64 -> x86_64
- x86 -> i686

Tools:
- tap
- swipe
- gesture
- multi_touch
- key_event
- input_text
- screenshot

Architecture:
- One persistent in-app ADB shell session for input commands.
- Separate ADB transport for screenshot exec-out.
- One serialized command path through the persistent shell.
- The app implements the ADB client protocol directly and connects to 127.0.0.1:5555; no external adb executable is required.

ADB authentication:
- The app creates and persists its own 2048-bit RSA ADB host key.
- The first connection may require approving the key in Android's ADB authorization prompt.
- After authorization, reconnects use the saved key.

UI:
- Connect ADB checks that 127.0.0.1:5555 is reachable and an ADB shell command succeeds.
- Switch Keyboard opens Android's system input-method picker.

No authentication or token layer is included for the MCP HTTP server.

The debug APK is published by the GitHub Actions workflow as the Droid-MCP-debug artifact.

MCP transport targets Streamable HTTP and protocol version 2026-07-28.
