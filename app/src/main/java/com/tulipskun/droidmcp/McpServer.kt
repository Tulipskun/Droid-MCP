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
                    writeResponse(
                        output,
                        204,
                        null,
                        null,
                        cors = true
                    )
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

                validateRequestHeaders(headers, request)

                val response = dispatch(request)

                writeResponse(
                    output,
                    200,
                    "application/json",
                    response.toString(),
                    cors = true
                )
            } catch (e: HeaderMismatchException) {
                writeResponse(
                    output,
                    400,
                    "application/json",
                    error(null, HEADER_MISMATCH, e.message ?: "Header mismatch"),
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
                        e.message ?: "Protocol error"
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

    private fun validateRequestHeaders(
        headers: Map<String, String>,
        request: JSONObject
    ) {
        val method = request.optString("method")
        val params = request.optJSONObject("params")
            ?: throw ProtocolException("Missing params")

        val meta = params.optJSONObject("_meta")
            ?: throw ProtocolException("Missing params._meta")

        val bodyProtocol = meta.optString("io.modelcontextprotocol/protocolVersion")
        val headerProtocol = headers["mcp-protocol-version"]

        if (headerProtocol == null || bodyProtocol.isBlank()) {
            throw ProtocolException("Missing MCP protocol version")
        }

        if (headerProtocol != bodyProtocol) {
            throw HeaderMismatchException("MCP-Protocol-Version does not match request metadata")
        }

        if (bodyProtocol != PROTOCOL_VERSION) {
            throw ProtocolException(
                "Unsupported MCP protocol version: $bodyProtocol",
                UNSUPPORTED_PROTOCOL_VERSION
            )
        }

        val capabilities = meta.optJSONObject("io.modelcontextprotocol/clientCapabilities")
            ?: throw ProtocolException("Missing client capabilities")

        if (capabilities.length() < 0) {
            throw ProtocolException("Invalid client capabilities")
        }

        val headerMethod = headers["mcp-method"]
        if (headerMethod == null || headerMethod != method) {
            throw HeaderMismatchException("Mcp-Method does not match request method")
        }

        if (method == "tools/call") {
            val name = params.optString("name")
            val headerName = headers["mcp-name"]

            if (name.isBlank() || headerName == null || headerName != name) {
                throw HeaderMismatchException("Mcp-Name does not match tool name")
            }
        }
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val id = if (request.has("id")) request.opt("id") else JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "server/discover" -> result(
                id,
                JSONObject()
                    .put("resultType", "complete")
                    .put("supportedVersions", JSONArray().put(PROTOCOL_VERSION))
                    .put(
                        "capabilities",
                        JSONObject().put(
                            "tools",
                            JSONObject().put("listChanged", false)
                        )
                    )
                    .put(
                        "instructions",
                        "Droid-MCP exposes Android input and screenshot tools."
                    )
                    .put("ttlMs", 3600000)
                    .put("cacheScope", "public")
            )

            "ping" -> result(
                id,
                JSONObject().put("resultType", "complete")
            )

            "tools/list" -> result(
                id,
                JSONObject()
                    .put("resultType", "complete")
                    .put("tools", ToolCatalog.definitions())
                    .put("ttlMs", 300000)
                    .put("cacheScope", "public")
            )

            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                result(id, callTool(name, args))
            }

            "initialize" -> error(
                id,
                -32601,
                "initialize is not part of MCP 2026-07-28; use server/discover"
            )

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

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )

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
        body: String?,
        cors: Boolean = false
    ) {
        val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)

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
        val code: Int = 0
    ) : RuntimeException(message)

    private class HeaderMismatchException(message: String) : RuntimeException(message)

    companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        const val VERSION = "0.1.0"
        private const val HEADER_MISMATCH = -32020
        private const val UNSUPPORTED_PROTOCOL_VERSION = -32022
        private const val MAX_BODY = 1024 * 1024
    }
}
