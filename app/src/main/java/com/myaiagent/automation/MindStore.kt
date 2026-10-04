package com.myaiagent.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MindSnapshot(
    val active: Boolean,
    val itemId: String,
    val fileName: String,
    val state: String,
    val stateSince: Long,
    val retryCount: Int,
    val packageName: String,
    val lastObservation: String,
    val diagnosis: String,
    val fix: String,
    val severity: String,
    val recoveryRequested: Boolean,
    val events: List<String>
)

class MindStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nax_mind", Context.MODE_PRIVATE)

    fun start(itemId: String, fileName: String, state: AutomationState) {
        prefs.edit().clear()
            .putBoolean("active", true)
            .putString("item_id", itemId)
            .putString("file_name", fileName)
            .putString("state", state.name)
            .putLong("state_since", System.currentTimeMillis())
            .putInt("retry_count", 0)
            .putString("package", "")
            .putString("last_observation", "")
            .putString("diagnosis", "Starting flow monitor.")
            .putString("fix", "Watch the first state transition.")
            .putString("severity", "INFO")
            .putBoolean("recovery_requested", false)
            .putString("events", JSONArray().toString())
            .apply()
        log("FLOW START • " + fileName + " • " + state.name)
    }

    fun setState(state: AutomationState) {
        prefs.edit()
            .putString("state", state.name)
            .putLong("state_since", System.currentTimeMillis())
            .putInt("retry_count", 0)
            .apply()
        log("STATE • " + state.name)
    }

    fun observe(packageName: String, observation: String = "") {
        val editor = prefs.edit().putString("package", packageName)
        if (observation.isNotBlank()) editor.putString("last_observation", observation)
        editor.apply()
        if (packageName.isNotBlank()) log("SCREEN • " + packageName)
        if (observation.isNotBlank()) log("OBS • " + observation)
    }

    fun retry(attempt: Int) {
        prefs.edit().putInt("retry_count", attempt).apply()
        log("RETRY • attempt " + attempt)
    }

    fun diagnose(diagnosis: String, fix: String, severity: String) {
        prefs.edit()
            .putString("diagnosis", diagnosis)
            .putString("fix", fix)
            .putString("severity", severity)
            .apply()
        log("MIND • " + severity + " • " + diagnosis)
    }

    fun requestRecovery(): Boolean {
        prefs.edit().putBoolean("recovery_requested", true).apply()
        log("RECOVERY REQUESTED • safe retry/reopen only")
        return true
    }

    fun consumeRecovery(): Boolean {
        val value = prefs.getBoolean("recovery_requested", false)
        if (value) prefs.edit().putBoolean("recovery_requested", false).apply()
        return value
    }

    fun clear() {
        prefs.edit()
            .putBoolean("active", false)
            .putBoolean("recovery_requested", false)
            .putString("state", AutomationState.IDLE.name)
            .apply()
        log("FLOW END")
    }

    fun log(message: String) {
        val current = prefs.getString("events", "[]") ?: "[]"
        val events = runCatching { JSONArray(current) }.getOrElse { JSONArray() }
        events.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("message", message)
        })
        val compact = JSONArray()
        val start = (events.length() - 120).coerceAtLeast(0)
        for (i in start until events.length()) compact.put(events.get(i))
        prefs.edit().putString("events", compact.toString()).apply()
    }

    fun snapshot(): MindSnapshot {
        val rawEvents = runCatching {
            JSONArray(prefs.getString("events", "[]") ?: "[]")
        }.getOrElse { JSONArray() }
        val events = buildList {
            for (i in 0 until rawEvents.length()) {
                val o = rawEvents.optJSONObject(i) ?: continue
                add(formatTime(o.optLong("at")) + "  " + o.optString("message"))
            }
        }
        return MindSnapshot(
            active = prefs.getBoolean("active", false),
            itemId = prefs.getString("item_id", "").orEmpty(),
            fileName = prefs.getString("file_name", "").orEmpty(),
            state = prefs.getString("state", AutomationState.IDLE.name).orEmpty(),
            stateSince = prefs.getLong("state_since", 0L),
            retryCount = prefs.getInt("retry_count", 0),
            packageName = prefs.getString("package", "").orEmpty(),
            lastObservation = prefs.getString("last_observation", "").orEmpty(),
            diagnosis = prefs.getString("diagnosis", "No diagnosis yet.").orEmpty(),
            fix = prefs.getString("fix", "No fix required.").orEmpty(),
            severity = prefs.getString("severity", "INFO").orEmpty(),
            recoveryRequested = prefs.getBoolean("recovery_requested", false),
            events = events
        )
    }

    private fun formatTime(value: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(value)
}
