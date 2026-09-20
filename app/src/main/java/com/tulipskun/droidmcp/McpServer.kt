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
    private val executor: ShizukuBridge
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return

        val socket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        running = true

        Thread {
            try {
                while (running) {
                    val client = socket.accept()
                    Thread { handle(client) }.start()
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
            val output = BufferedOutputStream(s.getOutputStream())

            try {
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")

                if (parts.size < 2) {
                    writeResponse(output, 400, "application/json", error(null, -32600, "Invalid HTTP request"))
                    return
                }

                val method = parts[0]
                val path = parts[1]
                val headers = readHeaders(input)

                if (path != "/mcp") {
                    writeResponse(output, 404, "application/json", error(null, -32601, "Not found"))
                    return
                }

                if (method == "OPTIONS") {
                    writeResponse(output, 204, null, null, cors = true)
                    return
                }

                if (method != "POST") {
                    writeResponse(
                        output,
                        405,
                        "application/json",
                        error(null, -32601, "Method not allowed"),
                        cors = true
                    )
                    return
                }

                val origin = headers["origin"]
                if (origin != null && origin != "null") {
                    writeResponse(
                        output,
                        403,
                        "application/json",
                        error(null, -32000, "Invalid Origin"),
                        cors = true
                    )
                    return
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0 || contentLength > MAX_BODY) {
                    writeResponse(
                        output,
                        400,
                        "application/json",
                        error(null, -32600, "Invalid Content-Length"),
                        cors = true
                    )
                    return
                }

                val request = JSONObject(
                    String(readExactly(input, contentLength), StandardCharsets.UTF_8)
                )

                val requestMethod = request.optString("method")

                if (requestMethod.startsWith("notifications/")) {
                    writeResponse(output, 202, null, null, cors = true)
                    return
                }

                val response = dispatch(request, headers)

                writeResponse(
                    output,
                    200,
                    "application/json",
                    response.toString(),
                    cors = true
                )
            } catch (e: ProtocolException) {
                writeResponse(
                    output,
                    400,
                    "application/json",
                    error(
                        null,
                        if (e.code != 0) e.code else -32602,
                        e.message ?: "Protocol error",
                        e.data
                    ),
                    cors = true
                )
            } catch (e: Throwable) {
                writeResponse(
                    output,
                    500,
                    "application/json",
                    error(null, -32603, e.message ?: "Internal error"),
                    cors = true
                )
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()

        while (true) {
            val line = readLine(input) ?: throw ProtocolException("Unexpected end of headers")
            if (line.isEmpty()) break

            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }

        return headers
    }

    private fun validateProtocolHeader(headers: Map<String, String>) {
        val headerProtocol = headers["mcp-protocol-version"]
        if (headerProtocol != PROTOCOL_VERSION) {
            throw ProtocolException(
                "Unsupported MCP protocol version: ${headerProtocol ?: "missing"}",
                UNSUPPORTED_PROTOCOL_VERSION,
                JSONObject()
                    .put("supported", JSONArray(SUPPORTED_PROTOCOL_VERSIONS))
                    .put("requested", headerProtocol ?: JSONObject.NULL)
            )
        }
    }

    private fun dispatch(
        request: JSONObject,
        headers: Map<String, String>
    ): JSONObject {
        val id = if (request.has("id")) request.opt("id") else JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "initialize" -> {
                val params = request.optJSONObject("params")
                    ?: throw ProtocolException("Missing params")
                val requested = params.optString("protocolVersion")
                val negotiated = if (requested in SUPPORTED_PROTOCOL_VERSIONS) {
                    requested
                } else {
                    PROTOCOL_VERSION
                }

                result(
                    id,
                    JSONObject()
                        .put("protocolVersion", negotiated)
                        .put(
                            "capabilities",
                            JSONObject().put(
                                "tools",
                                JSONObject().put("listChanged", false)
                            )
                        )
                        .put(
                            "serverInfo",
                            JSONObject()
                                .put("name", "Droid-MCP")
                                .put("version", VERSION)
                        )
                        .put(
                            "instructions",
                            "Droid-MCP exposes Android input and screenshot tools."
                        )
                )
            }

            "tools/list" -> {
                validateProtocolHeader(headers)
                result(
                    id,
                    JSONObject()
                        .put("tools", ToolCatalog.definitions())
                )
            }

            "tools/call" -> {
                validateProtocolHeader(headers)
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                result(id, callTool(name, args))
            }

            "server/discover" -> result(
                id,
                JSONObject()
                    .put("supportedVersions", JSONArray(SUPPORTED_PROTOCOL_VERSIONS))
            )

            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun callTool(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "tap" -> toolWithScreenshot("tap completed") {
                    executor.tap(args.getString("x"), args.getString("y"))
                }

                "swipe" -> toolWithScreenshot("swipe completed") {
                    executor.swipe(
                        args.getString("x1"),
                        args.getString("y1"),
                        args.getString("x2"),
                        args.getString("y2"),
                        args.getLong("duration_ms")
                    )
                }

                "gesture" -> toolWithScreenshot("gesture completed") {
                    executor.gesture(args.getJSONArray("points"))
                }

                "multi_touch" -> toolWithScreenshot("multi_touch completed") {
                    executor.multiTouch(args.getJSONArray("pointers"))
                }

                "key_event" -> toolWithScreenshot("key_event completed") {
                    executor.keyEvent(args.getInt("keycode"))
                }

                "input_text" -> toolWithScreenshot("input_text completed") {
                    executor.inputText(args.getString("text"))
                }

                "screenshot" -> {
                    val png = executor.screenshot()

                    JSONObject()
                        .put("resultType", "complete")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "image")
                                    .put(
                                        "data",
                                        Base64.encodeToString(png, Base64.NO_WRAP)
                                    )
                                    .put("mimeType", "image/png")
                            )
                        )
                        .put("isError", false)
                }

                else -> JSONObject()
                    .put("resultType", "complete")
                    .put(
                        "content",
                        JSONArray().put(toolTextItem("Unknown tool: $name"))
                    )
                    .put("isError", true)
            }
        } catch (e: Throwable) {
            JSONObject()
                .put("resultType", "complete")
                .put(
                    "content",
                    JSONArray().put(
                        toolTextItem(e.message ?: "Tool execution failed")
                    )
                )
                .put("isError", true)
        }
    }

    private fun toolWithScreenshot(
        text: String,
        action: () -> Unit
    ): JSONObject {
        action()
        Thread.sleep(SCREENSHOT_DELAY_MILLIS)
        val png = executor.screenshot()

        return JSONObject()
            .put("resultType", "complete")
            .put(
                "content",
                JSONArray()
                    .put(toolTextItem(text))
                    .put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "data",
                                Base64.encodeToString(png, Base64.NO_WRAP)
                            )
                            .put("mimeType", "image/png")
                    )
            )
            .put("isError", false)
    }

    private fun toolText(text: String): JSONObject =
        JSONObject()
            .put("resultType", "complete")
            .put("content", JSONArray().put(toolTextItem(text)))
            .put("isError", false)

    private fun toolTextItem(text: String): JSONObject =
        JSONObject()
            .put("type", "text")
            .put("text", text)

    private fun result(id: Any?, value: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", value.put("_meta", serverMeta()))

    private fun serverMeta(): JSONObject =
        JSONObject().put(
            "io.modelcontextprotocol/serverInfo",
            JSONObject()
                .put("name", "Droid-MCP")
                .put("version", VERSION)
        )

    private fun error(
        id: Any?,
        code: Int,
        message: String,
        data: JSONObject? = null
    ): JSONObject {
        val error = JSONObject()
            .put("code", code)
            .put("message", message)

        if (data != null) {
            error.put("data", data)
        }

        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", error)
    }

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

            if (bytes.size > 8192) {
                throw IllegalArgumentException("HTTP line too long")
            }
        }

        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun readExactly(
        input: BufferedInputStream,
        length: Int
    ): ByteArray {
        val result = ByteArray(length)
        var offset = 0

        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) {
                throw IllegalArgumentException("Unexpected end of request body")
            }
            offset += count
        }

        return result
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        contentType: String?,
        body: Any?,
        cors: Boolean = false
    ) {
        val bytes = body?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)

        val headers = buildString {
            append("HTTP/1.1 ")
                .append(status)
                .append(' ')
                .append(statusText(status))
                .append("\r\n")
            append("Connection: close\r\n")
            if (cors) {
                append("Access-Control-Allow-Origin: null\r\n")
                append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, MCP-Protocol-Version, Mcp-Method, Mcp-Name\r\n")
            }
            if (contentType != null) {
                append("Content-Type: ").append(contentType).append("\r\n")
            }
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    private class ProtocolException(
        message: String,
        val code: Int = 0,
        val data: JSONObject? = null
    ) : RuntimeException(message)

    companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
        const val VERSION = "0.2.0"
        private val SUPPORTED_PROTOCOL_VERSIONS = listOf(
            "2025-11-25",
            "2025-06-18",
            "2025-03-26"
        )
        private const val UNSUPPORTED_PROTOCOL_VERSION = -32602
        private const val MAX_BODY = 1024 * 1024
        private const val SCREENSHOT_DELAY_MILLIS = 1000L
    }
}
