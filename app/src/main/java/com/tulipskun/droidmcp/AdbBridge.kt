package com.tulipskun.droidmcp

import android.content.SharedPreferences
import org.json.JSONArray
import java.nio.charset.StandardCharsets

class AdbBridge(private val preferences: SharedPreferences) {
    private val lock = Any()

    @Volatile
    private var shellTransport: AdbTransport? = null

    @Volatile
    private var shellStream: AdbStream? = null

    @Volatile
    private var eventDevice: String? =
        preferences.getString("event_device", null)

    fun checkConnection(): String {
        AdbTransport(preferences).use { transport ->
            transport.connect()

            val output = transport
                .open("shell:echo Droid-MCP")
                .readRawUntilClose()

            val text = String(
                output,
                StandardCharsets.UTF_8
            ).trim()

            if (text != "Droid-MCP") {
                throw IllegalStateException(
                    "ADB shell test returned unexpected output"
                )
            }
        }

        return "Connected to " +
            AdbTransport.ADB_HOST + ":" +
            AdbTransport.ADB_PORT
    }

    private fun ensureShellSession() {
        synchronized(lock) {
            if (
                shellTransport != null &&
                shellStream != null
            ) {
                return
            }

            closeShell()

            val transport = AdbTransport(preferences)
            transport.connect()

            shellTransport = transport
            shellStream = transport.open(
                "shell,v2,raw:"
            )
        }
    }

    private fun sendToShell(command: String): String {
        synchronized(lock) {
            ensureShellSession()

            val marker =
                "__DROID_MCP_" +
                    System.nanoTime() +
                    "__"

            val script =
                command +
                    "; printf '%s\\n' '" +
                    marker +
                    "'"

            shellStream!!.writeShellInput(
                (script + "\n")
                    .toByteArray(StandardCharsets.UTF_8)
            )

            return shellStream!!
                .readUntilMarker(marker)
                .substringBeforeLast(marker)
        }
    }

    fun tap(x: Int, y: Int) {
        sendToShell(
            "input tap " + x + " " + y
        )
    }

    fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long
    ) {
        require(durationMs >= 0) {
            "duration_ms must be >= 0"
        }

        sendToShell(
            "input swipe " +
                x1 + " " +
                y1 + " " +
                x2 + " " +
                y2 + " " +
                durationMs
        )
    }

    fun gesture(points: JSONArray) {
        require(points.length() >= 2) {
            "gesture requires at least 2 points"
        }

        val command = StringBuilder()

        for (i in 0 until points.length()) {
            val point =
                points.getJSONObject(i)

            val x = point.getInt("x")
            val y = point.getInt("y")
            val delay =
                point.getLong("delay_ms")

            require(delay >= 0) {
                "delay_ms must be >= 0"
            }

            if (i == 0) {
                command.append(
                    "input motionevent DOWN " +
                        x + " " + y
                )
            } else {
                command.append(
                    "; sleep " +
                        delay / 1000.0
                )

                command.append(
                    "; input motionevent MOVE " +
                        x + " " + y
                )
            }
        }

        val last =
            points.getJSONObject(
                points.length() - 1
            )

        command.append(
            "; input motionevent UP " +
                last.getInt("x") +
                " " +
                last.getInt("y")
        )

        sendToShell(command.toString())
    }

    fun multiTouch(pointers: JSONArray) {
        require(pointers.length() > 0) {
            "pointers must not be empty"
        }

        val device = resolveEventDevice()
        val commands = ArrayList<String>()

        for (i in 0 until pointers.length()) {
            val pointer =
                pointers.getJSONObject(i)

            val id = pointer.getInt("id")
            val points =
                pointer.getJSONArray("points")

            require(points.length() > 0) {
                "pointer points must not be empty"
            }

            val first =
                points.getJSONObject(0)

            commands +=
                "sendevent " +
                    device +
                    " 3 47 " +
                    id

            commands +=
                "sendevent " +
                    device +
                    " 3 57 " +
                    id

            commands +=
                "sendevent " +
                    device +
                    " 3 53 " +
                    first.getInt("x")

            commands +=
                "sendevent " +
                    device +
                    " 3 54 " +
                    first.getInt("y")
        }

        commands +=
            "sendevent " +
                device +
                " 0 0 0"

        val maxPoints =
            (0 until pointers.length()).maxOf {
                pointers
                    .getJSONObject(it)
                    .getJSONArray("points")
                    .length()
            }

        for (step in 1 until maxPoints) {
            for (i in 0 until pointers.length()) {
                val pointer =
                    pointers.getJSONObject(i)

                val points =
                    pointer.getJSONArray("points")

                if (step >= points.length()) {
                    continue
                }

                val point =
                    points.getJSONObject(step)

                val delay =
                    point.getLong("delay_ms")

                if (delay > 0) {
                    commands +=
                        "sleep " +
                            delay / 1000.0
                }

                commands +=
                    "sendevent " +
                        device +
                        " 3 47 " +
                        pointer.getInt("id")

                commands +=
                    "sendevent " +
                        device +
                        " 3 53 " +
                        point.getInt("x")

                commands +=
                    "sendevent " +
                        device +
                        " 3 54 " +
                        point.getInt("y")
            }

            commands +=
                "sendevent " +
                    device +
                    " 0 0 0"
        }

        for (i in 0 until pointers.length()) {
            val pointer =
                pointers.getJSONObject(i)

            commands +=
                "sendevent " +
                    device +
                    " 3 47 " +
                    pointer.getInt("id")

            commands +=
                "sendevent " +
                    device +
                    " 3 57 -1"
        }

        commands +=
            "sendevent " +
                device +
                " 0 0 0"

        sendToShell(
            commands.joinToString("; ")
        )
    }

    fun keyEvent(keycode: Int) {
        sendToShell(
            "input keyevent " + keycode
        )
    }

    fun inputText(text: String) {
        val escaped =
            "'" +
                text.replace(
                    "'",
                    "'\\''"
                ) +
                "'"

        sendToShell(
            "input text " + escaped
        )
    }

    fun screenshot(): ByteArray {
        AdbTransport(preferences).use { transport ->
            transport.connect()

            return transport
                .open("exec:screencap -p")
                .readRawUntilClose()
        }
    }

    private fun resolveEventDevice(): String {
        synchronized(lock) {
            eventDevice?.let { return it }

            val output =
                sendToShell("getevent -pl")

            val blocks = output.split(
                Regex("(?=add device)")
            )

            for (block in blocks) {
                if (
                    "ABS_MT_POSITION_X" in block &&
                    "ABS_MT_POSITION_Y" in block
                ) {
                    val match = Regex(
                        "/dev/input/event\\d+"
                    ).find(block)

                    if (match != null) {
                        return match.value.also {
                            eventDevice = it

                            preferences.edit()
                                .putString(
                                    "event_device",
                                    it
                                )
                                .apply()
                        }
                    }
                }
            }

            throw IllegalStateException(
                "No multitouch event device found"
            )
        }
    }

    @Synchronized
    fun close() {
        synchronized(lock) {
            closeShell()
        }
    }

    private fun closeShell() {
        try {
            shellStream?.close()
        } catch (_: Throwable) {
        }

        try {
            shellTransport?.close()
        } catch (_: Throwable) {
        }

        shellStream = null
        shellTransport = null
    }
}
