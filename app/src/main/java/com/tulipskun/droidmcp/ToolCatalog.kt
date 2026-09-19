package com.tulipskun.droidmcp

import org.json.JSONArray
import org.json.JSONObject

object ToolCatalog {
    fun definitions(): JSONArray = JSONArray().apply {
        put(tool("tap", "Tap a screen coordinate.", schema(
            "x" to integer("screen x"),
            "y" to integer("screen y"),
            required = listOf("x", "y")
        )))
        put(tool("swipe", "Swipe between two coordinates.", schema(
            "x1" to integer("start x"),
            "y1" to integer("start y"),
            "x2" to integer("end x"),
            "y2" to integer("end y"),
            "duration_ms" to integer("duration in milliseconds"),
            required = listOf("x1", "y1", "x2", "y2", "duration_ms")
        )))
        put(tool("gesture", "Execute a custom touch path.", schema(
            "points" to pointArray(),
            required = listOf("points")
        )))
        put(tool("multi_touch", "Execute multiple concurrent touch paths.", schema(
            "pointers" to pointerArray(),
            required = listOf("pointers")
        )))
        put(tool("key_event", "Send an Android key event.", schema(
            "keycode" to integer("Android keycode"),
            required = listOf("keycode")
        )))
        put(tool("input_text", "Type text into the focused field.", schema(
            "text" to JSONObject().put("type", "string"),
            required = listOf("text")
        )))
        put(tool("screenshot", "Capture the current screen as PNG.", schema()))
    }

    private fun pointArray(): JSONObject =
        JSONObject()
            .put("type", "array")
            .put(
                "items",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("x", integer("x"))
                            .put("y", integer("y"))
                            .put("delay_ms", integer("delay after point"))
                    )
                    .put("required", JSONArray(listOf("x", "y", "delay_ms")))
            )

    private fun pointerArray(): JSONObject =
        JSONObject()
            .put("type", "array")
            .put(
                "items",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("id", integer("pointer id"))
                            .put("points", pointArray())
                    )
                    .put("required", JSONArray(listOf("id", "points")))
            )

    private fun integer(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun schema(
        vararg properties: Pair<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        val result = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())

        val objectProperties = result.getJSONObject("properties")
        properties.forEach { objectProperties.put(it.first, it.second) }

        if (required.isNotEmpty()) {
            result.put("required", JSONArray(required))
        }

        return result
    }

    private fun tool(
        name: String,
        description: String,
        inputSchema: JSONObject
    ): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", inputSchema)
}
