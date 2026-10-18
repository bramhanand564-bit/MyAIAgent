package com.auradroid.agent

import org.json.JSONArray
import org.json.JSONObject

enum class BlueprintAction(val wireValue: String) {
    TAP("tap"),
    TYPE("type"),
    SCROLL("scroll"),
    VERIFY("verify"),
    WAIT("wait"),
    BACK("back"),
    SWIPE("swipe");

    companion object {
        fun fromWireValue(value: String): BlueprintAction {
            val normalized = value.trim().lowercase()
            return values().firstOrNull { it.wireValue == normalized || it.name.lowercase() == normalized }
                ?: WAIT
        }
    }
}

data class BlueprintStep(
    val action: BlueprintAction,
    val target: String = "",
    val text: String = "",
    val x: Double = 0.5,
    val y: Double = 0.5,
    val waitMs: Long = 0L,
    val note: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("action", action.wireValue)
        put("target", target.take(1000))
        put("text", text.take(10000))
        put("x", x.coerceIn(0.0, 1.0))
        put("y", y.coerceIn(0.0, 1.0))
        put("waitMs", waitMs.coerceIn(0L, 120000L))
        put("note", note.take(2000))
    }
}

data class AutomationBlueprint(
    val name: String,
    val steps: List<BlueprintStep>,
    val description: String = ""
) {
    fun toJson(): String {
        val array = JSONArray()
        steps.take(MAX_STEPS).forEach { array.put(it.toJsonObject()) }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("name", name.take(200))
            .put("description", description.take(2000))
            .put("steps", array)
            .toString()
    }

    companion object {
        private const val MAX_STEPS = 500

        fun fromJson(raw: String): AutomationBlueprint {
            require(raw.length <= 1_000_000) { "Blueprint JSON is too large." }
            val root = JSONObject(raw)
            val array = root.optJSONArray("steps") ?: JSONArray()
            require(array.length() <= MAX_STEPS) { "Blueprint has too many steps." }
            val steps = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                BlueprintStep(
                    action = BlueprintAction.fromWireValue(item.optString("action", "wait")),
                    target = item.optString("target", "").take(1000),
                    text = item.optString("text", "").take(10000),
                    x = item.optDouble("x", 0.5).coerceIn(0.0, 1.0),
                    y = item.optDouble("y", 0.5).coerceIn(0.0, 1.0),
                    waitMs = item.optLong("waitMs", 0L).coerceIn(0L, 120000L),
                    note = item.optString("note", "").take(2000)
                )
            }
            return AutomationBlueprint(
                name = root.optString("name", "Untitled skill").take(200),
                steps = steps,
                description = root.optString("description", "").take(2000)
            )
        }
    }
}
