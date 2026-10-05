
package com.myaiagent.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AgentObservation(
    val at: Long,
    val packageName: String,
    val state: String,
    val screenHash: String,
    val aiScreen: String = "",
    val aiAction: String = "",
    val aiConfidence: Float = 0f,
    val aiReason: String = ""
)

class AgentObservationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "nax_agent_observations",
        Context.MODE_PRIVATE
    )

    fun record(
        packageName: String,
        state: String,
        screenHash: String,
        aiScreen: String = "",
        aiAction: String = "",
        aiConfidence: Float = 0f,
        aiReason: String = ""
    ) {
        val events = runCatching {
            JSONArray(prefs.getString("events", "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        events.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("package", packageName)
            put("state", state)
            put("hash", screenHash)
            put("aiScreen", aiScreen)
            put("aiAction", aiAction)
            put("aiConfidence", aiConfidence)
            put("aiReason", aiReason)
        })

        val compact = JSONArray()
        val start = (events.length() - 120).coerceAtLeast(0)
        for (i in start until events.length()) compact.put(events.get(i))

        val editor = prefs.edit()
            .putString("events", compact.toString())
            .putLong("last_at", System.currentTimeMillis())
            .putString("last_package", packageName)
            .putString("last_state", state)
            .putString("last_hash", screenHash)

        if (aiScreen.isNotBlank() || aiAction.isNotBlank() || aiReason.isNotBlank()) {
            editor
                .putString("last_ai_screen", aiScreen)
                .putString("last_ai_action", aiAction)
                .putFloat("last_ai_confidence", aiConfidence)
                .putString("last_ai_reason", aiReason)
        }
        editor.apply()
    }

    fun lastHash(): String = prefs.getString("last_hash", "").orEmpty()

    fun lastAiAt(): Long = prefs.getLong("last_ai_at", 0L)

    fun markAiAt(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_ai_at", at).apply()
    }

    fun last(): AgentObservation = AgentObservation(
        at = prefs.getLong("last_at", 0L),
        packageName = prefs.getString("last_package", "").orEmpty(),
        state = prefs.getString("last_state", AutomationState.IDLE.name).orEmpty(),
        screenHash = prefs.getString("last_hash", "").orEmpty(),
        aiScreen = prefs.getString("last_ai_screen", "").orEmpty(),
        aiAction = prefs.getString("last_ai_action", "").orEmpty(),
        aiConfidence = prefs.getFloat("last_ai_confidence", 0f),
        aiReason = prefs.getString("last_ai_reason", "").orEmpty()
    )

    fun clear() {
        prefs.edit().clear().apply()
    }
}
