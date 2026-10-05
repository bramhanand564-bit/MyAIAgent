
package com.myaiagent.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authoritative agent learning memory.
 *
 * An action is first stored as a pending candidate. It is promoted to learned
 * memory only after the automation state machine reaches the next verified state.
 * This prevents failed clicks/taps from becoming future instructions.
 */
class AgentVerifiedMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "nax_agent_verified_memory",
        Context.MODE_PRIVATE
    )

    fun rememberCandidate(
        packageName: String,
        state: String,
        action: String,
        target: String,
        value: String = "",
        expectedNextState: String = ""
    ) {
        prefs.edit().putString(
            "pending",
            JSONObject().apply {
                put("package", packageName)
                put("state", state)
                put("action", action)
                put("target", target)
                put("value", value)
                put("expectedNextState", expectedNextState)
                put("createdAt", System.currentTimeMillis())
            }.toString()
        ).apply()
    }

    fun verifyTransition(fromState: AutomationState, toState: AutomationState) {
        if (fromState == toState) return
        val pending = runCatching {
            JSONObject(prefs.getString("pending", "") ?: "")
        }.getOrNull() ?: return

        if (pending.optString("state") != fromState.name) return

        val expected = pending.optString("expectedNextState")
        if (expected.isNotBlank() && expected != toState.name) return

        val entries = runCatching {
            JSONArray(prefs.getString("entries", "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        entries.put(JSONObject(pending).apply {
            put("verified", true)
            put("verifiedAt", System.currentTimeMillis())
            put("verifiedNextState", toState.name)
        })

        val compact = JSONArray()
        val start = (entries.length() - 150).coerceAtLeast(0)
        for (i in start until entries.length()) compact.put(entries.get(i))

        prefs.edit()
            .putString("entries", compact.toString())
            .remove("pending")
            .apply()
    }

    fun promptContext(packageName: String, state: String = ""): String {
        val entries = runCatching {
            JSONArray(prefs.getString("entries", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val lines = mutableListOf<String>()

        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("package") != packageName) continue
            if (state.isNotBlank() && entry.optString("state") != state) continue

            val action = entry.optString("action").ifBlank { "ACTION" }
            val target = entry.optString("target").ifBlank { "unknown target" }
            val nextState = entry.optString("verifiedNextState").ifBlank { "unknown" }
            lines += "- VERIFIED " + entry.optString("state") + " -> " +
                action + " -> " + target + " -> " + nextState
        }

        return lines.takeLast(30).joinToString("\n")
    }

    fun learnedCount(): Int =
        runCatching {
            JSONArray(prefs.getString("entries", "[]") ?: "[]").length()
        }.getOrDefault(0)

    fun clearPending() {
        prefs.edit().remove("pending").apply()
    }
}
