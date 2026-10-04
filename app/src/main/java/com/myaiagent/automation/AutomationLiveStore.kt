package com.myaiagent.automation

import android.content.Context

data class AutomationLiveSnapshot(
    val active: Boolean,
    val itemId: String,
    val fileName: String,
    val state: String,
    val result: String,
    val note: String,
    val events: List<String>
)

class AutomationLiveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("automation_live", Context.MODE_PRIVATE)

    fun start(itemId: String, fileName: String) {
        prefs.edit().clear()
            .putBoolean("active", true)
            .putString("item_id", itemId)
            .putString("file_name", fileName)
            .putString("state", AutomationState.WAITING_FOR_APP.name)
            .putString("result", "RUNNING")
            .putString("note", "Automation started")
            .putLong("started_at", System.currentTimeMillis())
            .putString("events", "").apply()
        log("Video selected • $fileName")
        log("Automation started")
    }

    fun state(state: AutomationState) {
        prefs.edit().putString("state", state.name).apply()
        log("Step • " + stateLabel(state))
    }

    fun log(message: String) {
        val current = prefs.getString("events", "").orEmpty()
        val lines = (current.split("\n".toRegex()).filter { it.isNotBlank() } +
            formatTime(System.currentTimeMillis()) + "  " + message).takeLast(100)
        prefs.edit().putString("events", lines.joinToString("\n")).apply()
    }

    fun finish(success: Boolean, note: String) {
        prefs.edit().putBoolean("active", false)
            .putString("result", if (success) "SUCCESS" else "ERROR")
            .putString("note", note).apply()
        log(if (success) "✓ Completed • $note" else "✕ Stopped • $note")
    }

    fun snapshot(): AutomationLiveSnapshot {
        val storedActive = prefs.getBoolean("active", false)
        val startedAt = prefs.getLong("started_at", 0L)
        val fresh = startedAt > 0L && System.currentTimeMillis() - startedAt < 10 * 60 * 1000L
        val active = storedActive && fresh
        return AutomationLiveSnapshot(
        active,
        prefs.getString("item_id", "").orEmpty(),
        prefs.getString("file_name", "").orEmpty(),
        prefs.getString("state", AutomationState.IDLE.name).orEmpty(),
        prefs.getString("result", "").orEmpty(),
        prefs.getString("note", "").orEmpty(),
        prefs.getString("events", "").orEmpty().split("\n".toRegex()).filter { it.isNotBlank() }
        ).let { snapshot ->
            if (storedActive && !fresh) {
                snapshot.copy(
                    active = false,
                    result = "ERROR",
                    note = "Automation session expired; no live process is running."
                )
            } else snapshot
        }
    }

    private fun stateLabel(state: AutomationState) = when (state) {
        AutomationState.WAITING_FOR_APP -> "Opening YouTube Studio"
        AutomationState.FIND_CREATE -> "Finding Create"
        AutomationState.FIND_UPLOAD -> "Opening Upload"
        AutomationState.WAITING_FOR_PICKER -> "Selecting video"
        AutomationState.FILL_DETAILS -> "Filling title & description"
        AutomationState.SET_VISIBILITY -> "Setting visibility"
        AutomationState.PUBLISH -> "Publishing"
        AutomationState.MONITOR_UPLOAD -> "Monitoring upload progress"
        AutomationState.VERIFY -> "Final verification"
        AutomationState.WAITING_USER -> "Waiting for your action"
        AutomationState.COMPLETE -> "Complete"
        AutomationState.ERROR -> "Error"
        AutomationState.IDLE -> "Ready"
    }

    private fun formatTime(value: Long) =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(value)
}
