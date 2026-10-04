package com.myaiagent.automation

import android.content.Context
import com.myaiagent.model.UploadItem
import org.json.JSONArray
import org.json.JSONObject

data class TestRunEvent(
    val at: Long,
    val message: String
)

data class TestRunSnapshot(
    val active: Boolean,
    val itemId: String?,
    val fileName: String,
    val startedAt: Long,
    val state: String,
    val result: String,
    val resultNote: String,
    val events: List<TestRunEvent>
)

class TestRunStore(context: Context) {
    private val prefs = context.getSharedPreferences("workflow_test", Context.MODE_PRIVATE)

    fun start(item: UploadItem) {
        prefs.edit()
            .clear()
            .putBoolean("active", true)
            .putString("item_id", item.id)
            .putString("file_name", item.fileName)
            .putLong("started_at", System.currentTimeMillis())
            .putString("state", AutomationState.WAITING_FOR_APP.name)
            .putString("result", "RUNNING")
            .putString("result_note", "")
            .putString("events", JSONArray().toString())
            .apply()

        log("Video selected: ${item.fileName}")
        log("Test run started. Waiting for MyAIAgent + YouTube.")
    }

    fun log(message: String) {
        val events = JSONArray(prefs.getString("events", "[]") ?: "[]")
        events.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("message", message)
        })

        val compact = JSONArray()
        val startIndex = (events.length() - 100).coerceAtLeast(0)
        for (i in startIndex until events.length()) compact.put(events.get(i))
        prefs.edit().putString("events", compact.toString()).apply()
    }

    fun setState(state: AutomationState) {
        prefs.edit().putString("state", state.name).apply()
        log("Step: ${stateLabel(state)}")
    }

    fun finish(success: Boolean, note: String) {
        log(if (success) "TEST COMPLETE • Upload verified successfully." else "TEST COMPLETE • Upload did not complete.")
        log(note)
        prefs.edit()
            .putBoolean("active", false)
            .putString("result", if (success) "SUCCESS" else "ERROR")
            .putString("result_note", note)
            .putString("state", if (success) AutomationState.COMPLETE.name else AutomationState.ERROR.name)
            .apply()
    }

    fun markWaitingForUser(note: String) {
        log("WAITING FOR USER • ${note}")
        prefs.edit()
            .putString("result", "WAITING_USER")
            .putString("result_note", note)
            .putString("state", AutomationState.WAITING_USER.name)
            .apply()
    }

    fun snapshot(): TestRunSnapshot {
        val storedActive = prefs.getBoolean("active", false)
        val started = prefs.getLong("started_at", 0L)
        val stale = storedActive && started > 0L &&
            System.currentTimeMillis() - started >= 10 * 60 * 1000L
        if (stale) {
            prefs.edit()
                .putBoolean("active", false)
                .putString("result", "ERROR")
                .putString("result_note", "Test session expired; no live automation is running.")
                .putString("state", AutomationState.ERROR.name)
                .apply()
        }

        val events = JSONArray(prefs.getString("events", "[]") ?: "[]")
        val parsed = buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                add(
                    TestRunEvent(
                        at = event.optLong("at"),
                        message = event.optString("message")
                    )
                )
            }
        }
        return TestRunSnapshot(
            active = prefs.getBoolean("active", false),
            itemId = prefs.getString("item_id", null),
            fileName = prefs.getString("file_name", "") ?: "",
            startedAt = prefs.getLong("started_at", 0L),
            state = prefs.getString("state", AutomationState.IDLE.name) ?: AutomationState.IDLE.name,
            result = prefs.getString("result", "") ?: "",
            resultNote = prefs.getString("result_note", "") ?: "",
            events = parsed
        )
    }

    private fun stateLabel(state: AutomationState): String = when (state) {
        AutomationState.WAITING_FOR_APP -> "YouTube screen detected / opening"
        AutomationState.FIND_CREATE -> "Finding Create"
        AutomationState.FIND_UPLOAD -> "Finding Upload a video"
        AutomationState.WAITING_FOR_PICKER -> "Selecting the video file"
        AutomationState.FILL_DETAILS -> "Filling title and description"
        AutomationState.SET_VISIBILITY -> "Setting visibility"
        AutomationState.PUBLISH -> "Publishing"
        AutomationState.MONITOR_UPLOAD -> "Checking real upload progress"
        AutomationState.VERIFY -> "Final verification"
        AutomationState.WAITING_USER -> "Waiting for user action"
        AutomationState.COMPLETE -> "Completed"
        AutomationState.ERROR -> "Error"
        AutomationState.IDLE -> "Idle"
    }
}
