# Droid-MCP

Android APK exposing an MCP endpoint for remote control of an Android device over LAN.

Endpoint:

http://<ANDROID_IP>:8787/mcp

The server binds to 0.0.0.0:8787.

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
- The app implements the ADB client protocol directly and connects to `127.0.0.1:5555`; no external adb executable is required.

ADB authentication:
- The app creates and persists its own 2048-bit RSA ADB host key.
- The first connection may require approving the key in Android's ADB authorization prompt.
- After authorization, reconnects use the saved key.

UI:
- Connect ADB checks that `127.0.0.1:5555` is reachable and an ADB shell command succeeds.
- Enable Droid-MCP Keyboard opens Android's input-method settings.
- Switch Keyboard opens Android's system input-method picker.
- Droid-MCP Keyboard is a real Android InputMethodService.

No authentication or token layer is included for the MCP HTTP server.

The debug APK is published by the GitHub Actions workflow as the `Droid-MCP-debug` artifact. Debug builds use a stable repository signing key so successive CI APKs can be installed as updates instead of conflicting by signature. The first APK from before this stable signing change may still require a one-time uninstall if it was signed by a different key.

MCP transport targets Streamable HTTP and protocol version 2026-07-28.
