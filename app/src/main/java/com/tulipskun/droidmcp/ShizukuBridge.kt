package com.tulipskun.droidmcp

import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ShizukuBridge(private val preferences: SharedPreferences) {
    private val lock = Any()

    @Volatile
    private var eventDevice: String? =
        preferences.getString("event_device", null)

    fun checkConnection(): String {
        ensureReady()

        val output = runCommand("echo Droid-MCP").stdout
            .toString(StandardCharsets.UTF_8)
            .trim()

        if (output != "Droid-MCP") {
            throw IllegalStateException(
                "Shizuku shell test returned unexpected output"
            )
        }

        return "Shizuku connected"
    }

    private fun ensureReady() {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku is not running")
        }

        if (
            Shizuku.checkSelfPermission() !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Droid-MCP is not authorized in Shizuku"
            )
        }
    }

    private fun runCommand(command: String): CommandResult {
        ensureReady()

        synchronized(lock) {
            val process = Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
                .invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process

            try {
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroy()
                    throw IllegalStateException(
                        "Shizuku command timed out"
                    )
                }

                val stdout = process.inputStream.use {
                    it.readBytes()
                }

                val stderr = process.errorStream.use {
                    it.readBytes()
                }

                if (process.exitValue() != 0) {
                    val message = String(
                        stderr,
                        StandardCharsets.UTF_8
                    ).trim()

                    throw IllegalStateException(
                        if (message.isNotEmpty()) {
                            message
                        } else {
                            "Shizuku command failed with exit code " +
                                process.exitValue()
                        }
                    )
                }

                return CommandResult(stdout, stderr)
            } finally {
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun tap(x: Int, y: Int) {
        runCommand(
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

        runCommand(
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
            val point = points.getJSONObject(i)
            val x = point.getInt("x")
            val y = point.getInt("y")
            val delay = point.getLong("delay_ms")

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
                    "; sleep " + delay / 1000.0
                )
                command.append(
                    "; input motionevent MOVE " +
                        x + " " + y
                )
            }
        }

        val last = points.getJSONObject(points.length() - 1)

        command.append(
            "; input motionevent UP " +
                last.getInt("x") + " " +
                last.getInt("y")
        )

        runCommand(command.toString())
    }

    fun multiTouch(pointers: JSONArray) {
        require(pointers.length() > 0) {
            "pointers must not be empty"
        }

        val device = resolveEventDevice()
        val commands = ArrayList<String>()

        for (i in 0 until pointers.length()) {
            val pointer = pointers.getJSONObject(i)
            val id = pointer.getInt("id")
            val points = pointer.getJSONArray("points")

            require(points.length() > 0) {
                "pointer points must not be empty"
            }

            val first = points.getJSONObject(0)

            commands +=
                "sendevent " + device + " 3 47 " + id
            commands +=
                "sendevent " + device + " 3 57 " + id
            commands +=
                "sendevent " + device + " 3 53 " +
                    first.getInt("x")
            commands +=
                "sendevent " + device + " 3 54 " +
                    first.getInt("y")
        }

        commands +=
            "sendevent " + device + " 0 0 0"

        val maxPoints =
            (0 until pointers.length()).maxOf {
                pointers
                    .getJSONObject(it)
                    .getJSONArray("points")
                    .length()
            }

        for (step in 1 until maxPoints) {
            for (i in 0 until pointers.length()) {
                val pointer = pointers.getJSONObject(i)
                val points = pointer.getJSONArray("points")

                if (step >= points.length()) {
                    continue
                }

                val point = points.getJSONObject(step)
                val delay = point.getLong("delay_ms")

                require(delay >= 0) {
                    "delay_ms must be >= 0"
                }

                if (delay > 0) {
                    commands +=
                        "sleep " + delay / 1000.0
                }

                commands +=
                    "sendevent " + device + " 3 47 " +
                        pointer.getInt("id")
                commands +=
                    "sendevent " + device + " 3 53 " +
                        point.getInt("x")
                commands +=
                    "sendevent " + device + " 3 54 " +
                        point.getInt("y")
            }

            commands +=
                "sendevent " + device + " 0 0 0"
        }

        for (i in 0 until pointers.length()) {
            val pointer = pointers.getJSONObject(i)

            commands +=
                "sendevent " + device + " 3 47 " +
                    pointer.getInt("id")
            commands +=
                "sendevent " + device + " 3 57 -1"
        }

        commands +=
            "sendevent " + device + " 0 0 0"

        runCommand(commands.joinToString("; "))
    }

    fun keyEvent(keycode: Int) {
        runCommand(
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

        runCommand(
            "input text " + escaped
        )
    }

    fun screenshot(): ByteArray {
        return runCommand("screencap -p").stdout
    }

    private fun resolveEventDevice(): String {
        synchronized(lock) {
            eventDevice?.let { return it }

            val output = String(
                runCommand("getevent -pl").stdout,
                StandardCharsets.UTF_8
            )

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

    fun close() {
    }

    private data class CommandResult(
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 15L
    }
}
