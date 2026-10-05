
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
        val pending = runCatching {
            JSONArray(prefs.getString("pending", "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        pending.put(JSONObject().apply {
            put("package", packageName)
            put("state", state)
            put("action", action)
            put("target", target)
            put("value", value)
            put("expectedNextState", expectedNextState)
            put("createdAt", System.currentTimeMillis())
        })

        val compact = JSONArray()
        val start = (pending.length() - 20).coerceAtLeast(0)
        for (i in start until pending.length()) compact.put(pending.get(i))
        prefs.edit().putString("pending", compact.toString()).apply()
    }

    fun verifyTransition(fromState: AutomationState, toState: AutomationState) {
        if (fromState == toState) return

        val pending = runCatching {
            JSONArray(prefs.getString("pending", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        if (pending.length() == 0) return

        val entries = runCatching {
            JSONArray(prefs.getString("entries", "[]") ?: "[]")
        }.getOrDefault(JSONArray())

        val now = System.currentTimeMillis()
        for (i in 0 until pending.length()) {
            val candidate = pending.optJSONObject(i) ?: continue
            if (candidate.optString("state") != fromState.name) continue

            val expected = candidate.optString("expectedNextState")
            if (expected.isNotBlank() && expected != toState.name) continue

            entries.put(JSONObject().apply {
                put("package", candidate.optString("package"))
                put("state", candidate.optString("state"))
                put("action", candidate.optString("action"))
                put("target", candidate.optString("target"))
                put("value", candidate.optString("value"))
                put("expectedNextState", candidate.optString("expectedNextState"))
                put("createdAt", candidate.optLong("createdAt"))
                put("verified", true)
                put("verifiedAt", now)
                put("verifiedNextState", toState.name)
            })
        }

        val compactEntries = JSONArray()
        val entryStart = (entries.length() - 150).coerceAtLeast(0)
        for (i in entryStart until entries.length()) compactEntries.put(entries.get(i))

        prefs.edit()
            .putString("entries", compactEntries.toString())
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
