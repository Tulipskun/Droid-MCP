package com.tulipskun.droidmcp

import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuBridge(private val preferences: SharedPreferences) {
    private val lock = Any()

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

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val streamsClosed = CountDownLatch(2)

            val stdoutThread = Thread {
                try {
                    process.inputStream.use { it.copyTo(stdout) }
                } finally {
                    streamsClosed.countDown()
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream.use { it.copyTo(stderr) }
                } finally {
                    streamsClosed.countDown()
                }
            }

            stdoutThread.start()
            stderrThread.start()

            try {
                if (
                    !streamsClosed.await(
                        COMMAND_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                ) {
                    process.destroy()
                    throw IllegalStateException(
                        "Shizuku command timed out"
                    )
                }

                val exitCode = try {
                    process.waitFor()
                    process.exitValue()
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        error.message ?: "Unable to get Shizuku process exit code",
                        error
                    )
                }

                val stdoutBytes = stdout.toByteArray()
                val stderrBytes = stderr.toByteArray()

                if (exitCode != 0) {
                    val message = String(
                        stderrBytes,
                        StandardCharsets.UTF_8
                    ).trim()

                    throw IllegalStateException(
                        if (message.isNotEmpty()) {
                            message
                        } else {
                            "Shizuku command failed with exit code " + exitCode
                        }
                    )
                }

                return CommandResult(stdoutBytes, stderrBytes)
            } finally {
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
                try {
                    stdoutThread.join(COMMAND_STREAM_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                try {
                    stderrThread.join(COMMAND_STREAM_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
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

        synchronized(lock) {
            ensureReady()

            val count = pointers.length()
            val pointerIds = IntArray(count)
            val pointArrays = arrayOfNulls<JSONArray>(count)

            for (i in 0 until count) {
                val pointer = pointers.getJSONObject(i)
                val id = pointer.getInt("id")
                require(id >= 0) {
                    "pointer id must be >= 0"
                }

                val points = pointer.getJSONArray("points")
                require(points.length() > 0) {
                    "pointer points must not be empty"
                }

                pointerIds[i] = id
                pointArrays[i] = points
            }

            val positions = Array(count) { IntArray(2) }

            for (i in 0 until count) {
                val point = pointArrays[i]!!.getJSONObject(0)
                positions[i][0] = point.getInt("x")
                positions[i][1] = point.getInt("y")
            }

            val downTime = SystemClock.uptimeMillis()

            injectMotionEvent(
                createMotionEvent(
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    pointerIds.copyOfRange(0, 1),
                    positions.copyOfRange(0, 1)
                )
            )

            for (i in 1 until count) {
                injectMotionEvent(
                    createMotionEvent(
                        downTime,
                        MotionEvent.ACTION_POINTER_DOWN or
                            (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        pointerIds.copyOfRange(0, i + 1),
                        positions.copyOfRange(0, i + 1)
                    )
                )
            }

            val maxPoints = (0 until count).maxOf {
                pointArrays[it]!!.length()
            }

            for (step in 1 until maxPoints) {
                var delayMs = 0L

                for (i in 0 until count) {
                    val points = pointArrays[i]!!
                    if (step >= points.length()) {
                        continue
                    }

                    val point = points.getJSONObject(step)
                    val delay = point.getLong("delay_ms")
                    require(delay >= 0) {
                        "delay_ms must be >= 0"
                    }

                    delayMs = maxOf(delayMs, delay)
                    positions[i][0] = point.getInt("x")
                    positions[i][1] = point.getInt("y")
                }

                if (delayMs > 0) {
                    Thread.sleep(delayMs)
                }

                injectMotionEvent(
                    createMotionEvent(
                        downTime,
                        MotionEvent.ACTION_MOVE,
                        pointerIds,
                        positions
                    )
                )
            }

            for (i in count - 1 downTo 1) {
                injectMotionEvent(
                    createMotionEvent(
                        downTime,
                        MotionEvent.ACTION_POINTER_UP or
                            (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        pointerIds,
                        positions
                    )
                )
            }

            injectMotionEvent(
                createMotionEvent(
                    downTime,
                    MotionEvent.ACTION_UP,
                    pointerIds.copyOfRange(0, 1),
                    positions.copyOfRange(0, 1)
                )
            )
        }
    }

    private fun createMotionEvent(
        downTime: Long,
        action: Int,
        pointerIds: IntArray,
        positions: Array<IntArray>
    ): MotionEvent {
        val properties = Array(pointerIds.size) {
            PointerProperties().apply {
                id = pointerIds[it]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val coords = Array(pointerIds.size) {
            PointerCoords().apply {
                x = positions[it][0].toFloat()
                y = positions[it][1].toFloat()
                pressure = 1f
                size = 1f
            }
        }

        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            pointerIds.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        ).apply {
            setDisplayId(0)
        }
    }

    private fun injectMotionEvent(event: MotionEvent) {
        var recycled = false
        try {
            val service = SystemServiceHelper.getSystemService("input")
                ?: throw IllegalStateException(
                    "Android input service is unavailable"
                )

            val transactionCode =
                SystemServiceHelper.getTransactionCode(
                    "android.hardware.input.IInputManager$Stub",
                    "injectInputEvent"
                ) ?: throw IllegalStateException(
                    "IInputManager.injectInputEvent transaction not found"
                )

            val binder = ShizukuBinderWrapper(service)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeInterfaceToken(
                    "android.hardware.input.IInputManager"
                )
                data.writeTypedObject(event, 0)
                data.writeInt(INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)

                if (!binder.transact(transactionCode, data, reply, 0)) {
                    throw IllegalStateException(
                        "IInputManager transaction failed"
                    )
                }

                reply.readException()

                if (!reply.readBoolean()) {
                    throw IllegalStateException(
                        "Android rejected input event injection"
                    )
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            if (!recycled) {
                event.recycle()
                recycled = true
            }
        }
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

    fun close() {
    }

    private data class CommandResult(
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 15L
        private const val COMMAND_STREAM_JOIN_MILLIS = 1000L
    }
}
