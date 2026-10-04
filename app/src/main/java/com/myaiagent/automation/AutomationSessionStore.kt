package com.myaiagent.automation

import android.content.Context
import com.myaiagent.model.UploadItem

class AutomationSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("automation_session", Context.MODE_PRIVATE)

    fun begin(item: UploadItem) {
        prefs.edit()
            .putString("item_id", item.id)
            .putString("state", AutomationState.WAITING_FOR_APP.name)
            .putLong("started_at", System.currentTimeMillis())
            .putInt("attempt", 0)
            .apply()
    }

    fun itemId(): String? = prefs.getString("item_id", null)

    fun state(): AutomationState =
        runCatching {
            AutomationState.valueOf(
                prefs.getString("state", AutomationState.IDLE.name) ?: AutomationState.IDLE.name
            )
        }.getOrDefault(AutomationState.IDLE)

    fun setState(state: AutomationState) {
        prefs.edit()
            .putString("state", state.name)
            .putInt("attempt", 0)
            .apply()
    }

    fun incrementAttempt(): Int {
        val next = prefs.getInt("attempt", 0) + 1
        prefs.edit().putInt("attempt", next).apply()
        return next
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
