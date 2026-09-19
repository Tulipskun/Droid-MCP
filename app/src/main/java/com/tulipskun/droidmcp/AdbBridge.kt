package com.tulipskun.droidmcp

import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class AdbBridge {
    private var shell: Process? = null
    private var shellInput: java.io.BufferedWriter? = null
    private var shellOutput: BufferedReader? = null

    @Synchronized
    private fun ensureShellSession() {
        if (shell?.isAlive == true && shellInput != null && shellOutput != null) return

        closeShell()

        shell = ProcessBuilder("adb", "shell")
            .redirectErrorStream(true)
            .start()

        shellInput = shell!!.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        shellOutput = BufferedReader(
            InputStreamReader(shell!!.inputStream, StandardCharsets.UTF_8)
        )
    }

    @Synchronized
    private fun sendToShell(command: String): String {
        ensureShellSession()

        val marker = "__DROID_MCP_" + System.nanoTime() + "__"
        shellInput!!.write(command)
        shellInput!!.write("; printf '%s\\n' " + marker)
        shellInput!!.newLine()
        shellInput!!.flush()

        val output = StringBuilder()

        while (true) {
            val line = shellOutput!!.readLine() ?: run {
                closeShell()
                throw IllegalStateException("ADB shell disconnected")
            }

            if (line == marker) break
            output.append(line).append('\\n')
        }

        return output.toString()
    }

    fun tap(x: Int, y: Int) {
        sendToShell("input tap $x $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        require(durationMs >= 0) { "duration_ms must be >= 0" }
        sendToShell("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    fun gesture(points: JSONArray) {
        require(points.length() >= 2) { "gesture requires at least 2 points" }

        val command = StringBuilder()

        for (i in 0 until points.length()) {
            val point = points.getJSONObject(i)
            val x = point.getInt("x")
            val y = point.getInt("y")
            val delay = point.getLong("delay_ms")

            require(delay >= 0) { "delay_ms must be >= 0" }

            if (i == 0) {
                command.append("input motionevent DOWN $x $y")
            } else {
                command.append("; sleep ").append(delay / 1000.0)
                command.append("; input motionevent MOVE $x $y")
            }
        }

        val last = points.getJSONObject(points.length() - 1)
        command.append("; input motionevent UP ")
            .append(last.getInt("x"))
            .append(' ')
            .append(last.getInt("y"))

        sendToShell(command.toString())
    }

    fun multiTouch(pointers: JSONArray) {
        val device = resolveEventDevice()
        require(pointers.length() > 0) { "pointers must not be empty" }

        val commands = ArrayList<String>()

        for (i in 0 until pointers.length()) {
            val pointer = pointers.getJSONObject(i)
            val id = pointer.getInt("id")
            val points = pointer.getJSONArray("points")
            require(points.length() > 0) { "pointer points must not be empty" }

            val first = points.getJSONObject(0)
            commands += "sendevent $device 3 47 $id"
            commands += "sendevent $device 3 57 $id"
            commands += "sendevent $device 3 53 ${first.getInt("x")}"
            commands += "sendevent $device 3 54 ${first.getInt("y")}"
        }

        commands += "sendevent $device 0 0 0"

        val maxPoints = (0 until pointers.length())
            .maxOf { pointers.getJSONObject(it).getJSONArray("points").length() }

        for (step in 1 until maxPoints) {
            for (i in 0 until pointers.length()) {
                val pointer = pointers.getJSONObject(i)
                val points = pointer.getJSONArray("points")
                if (step >= points.length()) continue

                val point = points.getJSONObject(step)
                val delay = point.getLong("delay_ms")

                if (delay > 0) {
                    commands += "sleep ${delay / 1000.0}"
                }

                commands += "sendevent $device 3 47 ${pointer.getInt("id")}"
                commands += "sendevent $device 3 53 ${point.getInt("x")}"
                commands += "sendevent $device 3 54 ${point.getInt("y")}"
            }

            commands += "sendevent $device 0 0 0"
        }

        for (i in 0 until pointers.length()) {
            val pointer = pointers.getJSONObject(i)
            commands += "sendevent $device 3 47 ${pointer.getInt("id")}"
            commands += "sendevent $device 3 57 -1"
        }

        commands += "sendevent $device 0 0 0"
        sendToShell(commands.joinToString("; "))
    }

    fun keyEvent(keycode: Int) {
        sendToShell("input keyevent $keycode")
    }

    fun inputText(text: String) {
        val escaped = "'" + text.replace("'", "'\\''") + "'"
        sendToShell("input text $escaped")
    }

    fun screenshot(): ByteArray {
        val process = ProcessBuilder("adb", "exec-out", "screencap", "-p")
            .redirectErrorStream(false)
            .start()

        val bytes = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(bytes) }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = process.errorStream.bufferedReader().readText()
            throw IllegalStateException("screenshot failed: $error")
        }

        return bytes.toByteArray()
    }

    private fun resolveEventDevice(): String {
        val output = runOneShot("adb", "shell", "getevent", "-pl")
        val blocks = output.split(Regex("(?=add device)"))

        for (block in blocks) {
            if ("ABS_MT_POSITION_X" in block && "ABS_MT_POSITION_Y" in block) {
                val match = Regex("/dev/input/event\\d+").find(block)
                if (match != null) return match.value
            }
        }

        throw IllegalStateException("No multitouch event device found")
    }

    private fun runOneShot(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(output.ifBlank { "command failed" })
        }

        return output
    }

    @Synchronized
    fun close() {
        closeShell()
    }

    private fun closeShell() {
        try {
            shellInput?.write("exit")
            shellInput?.newLine()
            shellInput?.flush()
        } catch (_: Throwable) {
        }

        try {
            shell?.destroy()
        } catch (_: Throwable) {
        }

        shell = null
        shellInput = null
        shellOutput = null
    }
}
