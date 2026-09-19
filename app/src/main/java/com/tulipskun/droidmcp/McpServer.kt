package com.tulipskun.droidmcp

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class McpServer(
    private val port: Int,
    private val executor: AdbBridge
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true

        Thread {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handle(socket) }.start()
                }
            } catch (_: Throwable) {
            } finally {
                running = false
            }
        }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        executor.close()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")

                if (parts.size < 2) {
                    writeResponse(output, 400, "application/json", error(null, -32600, "Invalid HTTP request"))
                    return
                }

                val method = parts[0]
                val path = parts[1]
                val headers = linkedMapOf<String, String>()

                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val index = line.indexOf(':')
                    if (index > 0) {
                        headers[line.substring(0, index).trim().lowercase()] =
                            line.substring(index + 1).trim()
                    }
                }

                if (path != "/mcp") {
                    writeResponse(output, 404, "application/json", error(null, -32601, "Not found"))
                    return
                }

                if (method != "POST") {
                    writeResponse(output, 405, "application/json", error(null, -32601, "Method not allowed"))
                    return
                }

                val origin = headers["origin"]
                if (origin != null && origin != "null") {
                    writeResponse(output, 403, "application/json", error(null, -32000, "Invalid Origin"))
                    return
                }

                val protocol = headers["mcp-protocol-version"]
                if (protocol != null && protocol != PROTOCOL_VERSION) {
                    writeResponse(
                        output,
                        400,
                        "application/json",
                        error(null, -32602, "Unsupported MCP protocol version: $protocol")
                    )
                    return
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0 || contentLength > MAX_BODY) {
                    writeResponse(output, 400, "application/json", error(null, -32600, "Invalid Content-Length"))
                    return
                }

                val request = JSONObject(
                    String(readExactly(input, contentLength), StandardCharsets.UTF_8)
                )

                if (request.optString("method").startsWith("notifications/")) {
                    writeResponse(output, 202, null, null)
                    return
                }

                writeResponse(
                    output,
                    200,
                    "application/json",
                    dispatch(request).toString()
                )
            } catch (e: Throwable) {
                writeResponseSafe(s, 500, error(null, -32603, e.message ?: "Internal error"))
            }
        }
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val id = if (request.has("id")) request.opt("id") else JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "initialize" -> result(
                id,
                JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put(
                        "serverInfo",
                        JSONObject()
                            .put("name", "Droid-MCP")
                            .put("version", "0.1.0")
                    )
            )
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", ToolCatalog.definitions()))
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                result(id, callTool(name, args))
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun callTool(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "tap" -> {
                    executor.tap(args.getInt("x"), args.getInt("y"))
                    toolText("tap completed")
                }
                "swipe" -> {
                    executor.swipe(
                        args.getInt("x1"),
                        args.getInt("y1"),
                        args.getInt("x2"),
                        args.getInt("y2"),
                        args.getLong("duration_ms")
                    )
                    toolText("swipe completed")
                }
                "gesture" -> {
                    executor.gesture(args.getJSONArray("points"))
                    toolText("gesture completed")
                }
                "multi_touch" -> {
                    executor.multiTouch(args.getJSONArray("pointers"))
                    toolText("multi_touch completed")
                }
                "key_event" -> {
                    executor.keyEvent(args.getInt("keycode"))
                    toolText("key_event completed")
                }
                "input_text" -> {
                    executor.inputText(args.getString("text"))
                    toolText("input_text completed")
                }
                "screenshot" -> {
                    val png = executor.screenshot()
                    JSONObject()
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "image")
                                    .put("data", Base64.encodeToString(png, Base64.NO_WRAP))
                                    .put("mimeType", "image/png")
                            )
                        )
                        .put("isError", false)
                }
                else -> JSONObject()
                    .put("content", JSONArray().put(toolTextItem("Unknown tool: $name")))
                    .put("isError", true)
            }
        } catch (e: Throwable) {
            JSONObject()
                .put("content", JSONArray().put(toolTextItem(e.message ?: "Tool execution failed")))
                .put("isError", true)
        }
    }

    private fun toolText(text: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(toolTextItem(text)))
            .put("isError", false)

    private fun toolTextItem(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    private fun result(id: Any?, value: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value)

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()

        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (bytes.isEmpty()) null else {
                    String(bytes.toByteArray(), StandardCharsets.UTF_8)
                }
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
            if (bytes.size > 8192) throw IllegalArgumentException("HTTP line too long")
        }

        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw IllegalArgumentException("Unexpected end of request body")
            offset += count
        }

        return result
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        contentType: String?,
        body: String?
    ) {
        val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Connection: close\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun writeResponseSafe(socket: Socket, status: Int, body: JSONObject) {
        try {
            writeResponse(
                BufferedOutputStream(socket.getOutputStream()),
                status,
                "application/json",
                body.toString()
            )
        } catch (_: Throwable) {
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        private const val MAX_BODY = 1024 * 1024
    }
}
