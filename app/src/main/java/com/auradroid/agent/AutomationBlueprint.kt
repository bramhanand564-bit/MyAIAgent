package com.auradroid.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated format for saving/importing user-approved automation steps.
 * Importing a blueprint never executes it; callers should preview it and obtain consent.
 */
data class AutomationBlueprint(
    val name: String,
    val version: Int = CURRENT_VERSION,
    val steps: List<BlueprintStep>,
    val description: String = ""
) {
    companion object {
        const val CURRENT_VERSION = 1
        private const val MAX_STEPS = 500
        private const val MAX_TEXT_LENGTH = 4000

        fun fromJson(json: String): AutomationBlueprint {
            require(json.length <= 1_000_000) { "Blueprint JSON is too large." }
            val root = JSONObject(json)
            val version = root.optInt("version", CURRENT_VERSION)
            require(version == CURRENT_VERSION) { "Unsupported blueprint version: " + version }

            val name = root.optString("name", "").trim()
            require(name.isNotEmpty()) { "Blueprint name is required." }
            require(name.length <= 120) { "Blueprint name must be 120 characters or less." }

            val rawSteps = root.optJSONArray("steps")
                ?: throw IllegalArgumentException("Blueprint must contain a steps array.")
            require(rawSteps.length() in 1..MAX_STEPS) {
                "Blueprint must contain between 1 and " + MAX_STEPS + " steps."
            }

            val steps = (0 until rawSteps.length()).map { index ->
                val item = rawSteps.optJSONObject(index)
                    ?: throw IllegalArgumentException("Step " + (index + 1) + " must be an object.")
                val action = BlueprintAction.fromWireValue(item.optString("action", ""))
                val text = item.optString("text", "")
                val target = item.optString("target", "")
                val waitMs = item.optLong("waitMs", 0L)
                require(text.length <= MAX_TEXT_LENGTH) {
                    "Step " + (index + 1) + " text is too long."
                }
                require(target.length <= 500) {
                    "Step " + (index + 1) + " target is too long."
                }
                require(waitMs in 0L..60_000L) {
                    "Step " + (index + 1) + " waitMs must be between 0 and 60000."
                }
                val x = item.optDouble("x", 0.0)
                val y = item.optDouble("y", 0.0)
                require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) {
                    "Step " + (index + 1) + " coordinates must be normalized between 0 and 1."
                }
                BlueprintStep(
                    action = action,
                    target = target,
                    text = text,
                    x = x,
                    y = y,
                    waitMs = waitMs,
                    note = item.optString("note", "").take(500)
                )
            }

            return AutomationBlueprint(
                name = name,
                version = version,
                steps = steps,
                description = root.optString("description", "").take(1000)
            )
        }
    }

    fun toJson(): String {
        val root = JSONObject()
            .put("format", "myai-automation-blueprint")
            .put("version", version)
            .put("name", name)
            .put("description", description)
        val array = JSONArray()
        steps.forEach { step ->
            array.put(
                JSONObject()
                    .put("action", step.action.wireValue)
                    .put("target", step.target)
                    .put("text", step.text)
                    .put("x", step.x)
                    .put("y", step.y)
                    .put("waitMs", step.waitMs)
                    .put("note", step.note)
            )
        }
        root.put("steps", array)
        return root.toString(2)
    }
}

enum class BlueprintAction(val wireValue: String) {
    TAP("tap"),
    TYPE("type"),
    SCROLL("scroll"),
    WAIT("wait"),
    BACK("back"),
    OPEN_URL("open_url"),
    VERIFY("verify"),
    ASK_USER("ask_user");

    companion object {
        fun fromWireValue(value: String): BlueprintAction =
            values().firstOrNull { it.wireValue == value.trim().lowercase() }
                ?: throw IllegalArgumentException("Unsupported blueprint action: " + value)
    }
}

data class BlueprintStep(
    val action: BlueprintAction,
    val target: String = "",
    val text: String = "",
    /** Normalized horizontal coordinate in the range 0.0..1.0. */
    val x: Double = 0.0,
    /** Normalized vertical coordinate in the range 0.0..1.0. */
    val y: Double = 0.0,
    val waitMs: Long = 0L,
    val note: String = ""
)
