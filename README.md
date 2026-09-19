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
- One persistent ADB shell session for input commands.
- Separate adb exec-out screencap -p process for screenshots.
- One serialized command path through the persistent shell.

The ADB bridge targets the local Wi-Fi debugging endpoint `127.0.0.1:5555` and reconnects it before opening the persistent shell or taking screenshots. The current executor expects an adb executable available to the APK process. The MCP transport and tool layer are isolated from the execution backend so the bridge can be replaced without changing the MCP interface.

No authentication or token layer is included.

The debug APK is published by the GitHub Actions workflow as the `Droid-MCP-debug` artifact.

MCP transport targets Streamable HTTP and protocol version 2026-07-28.
