package com.tulipskun.droidmcp

import org.json.JSONArray
import org.json.JSONObject

object ToolCatalog {
    fun definitions(): JSONArray = JSONArray().apply {
        put(tool("tap", "Tap a screen coordinate using percentages of the current screen.", schema(
            "x" to percentage("horizontal position, e.g. 50%"),
            "y" to percentage("vertical position, e.g. 25%"),
            required = listOf("x", "y")
        )))
        put(tool("long_press", "Hold a screen coordinate for a duration. Defaults to 3 seconds.", schema(
            "x" to percentage("horizontal position, e.g. 50%"),
            "y" to percentage("vertical position, e.g. 25%"),
            "duration_ms" to integer("hold duration in milliseconds, default 3000, minimum 200"),
            required = listOf("x", "y")
        )))
        put(tool("swipe", "Swipe between percentage screen coordinates.", schema(
            "x1" to percentage("start horizontal position, e.g. 10%"),
            "y1" to percentage("start vertical position, e.g. 80%"),
            "x2" to percentage("end horizontal position, e.g. 90%"),
            "y2" to percentage("end vertical position, e.g. 20%"),
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
                            .put("x", percentage("horizontal position, e.g. 50%"))
                            .put("y", percentage("vertical position, e.g. 25%"))
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
    private fun percentage(description: String): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("pattern", "^ *([0-9]+([.][0-9]+)?)% *$")
            .put("description", description)

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
