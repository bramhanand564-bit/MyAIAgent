package com.myaiagent.automation

import android.content.Context
import com.myaiagent.model.UploadItem

class AutomationSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs =
        context.getSharedPreferences("automation_session", Context.MODE_PRIVATE)

    fun begin(
        item: UploadItem,
        initialState: AutomationState = AutomationState.WAITING_FOR_APP,
        testMode: Boolean = false
    ) {
        prefs.edit()
            .putString("item_id", item.id)
            .putString("state", initialState.name)
            .putLong("started_at", System.currentTimeMillis())
            .putInt("attempt", 0)
            .putBoolean("test_mode", testMode)
            .putString("waiting_resume_state", null)
            .putString("last_observation", "")
            .putBoolean("transfer_complete", false)
            .apply()

        AutomationLiveStore(appContext).start(item.id, item.fileName)
        AutomationLiveStore(appContext).state(initialState)
        if (testMode) {
            TestRunStore(appContext).setState(initialState)
        }
    }

    fun itemId(): String? = prefs.getString("item_id", null)

    fun startedAt(): Long = prefs.getLong("started_at", 0L)

    fun isActive(
        exceptItemId: String? = null,
        timeoutMs: Long = 10 * 60 * 1000L
    ): Boolean {
        val currentId = itemId() ?: return false
        if (exceptItemId != null && currentId == exceptItemId) return false
        val started = startedAt()
        return started > 0L && System.currentTimeMillis() - started < timeoutMs
    }

    fun state(): AutomationState =
        runCatching {
            AutomationState.valueOf(
                prefs.getString(
                    "state",
                    AutomationState.IDLE.name
                ) ?: AutomationState.IDLE.name
            )
        }.getOrDefault(AutomationState.IDLE)

    fun setState(state: AutomationState) {
        prefs.edit()
            .putString("state", state.name)
            .putInt("attempt", 0)
            .apply()

        AutomationLiveStore(appContext).state(state)
        if (isTestMode()) {
            TestRunStore(appContext).setState(state)
        }
    }

    fun isTestMode(): Boolean = prefs.getBoolean("test_mode", false)

    fun enterWaitingForUser(resumeState: AutomationState) {
        prefs.edit()
            .putString("waiting_resume_state", resumeState.name)
            .putString("state", AutomationState.WAITING_USER.name)
            .putInt("attempt", 0)
            .apply()
        AutomationLiveStore(appContext).state(AutomationState.WAITING_USER)
        if (isTestMode()) {
            TestRunStore(appContext).setState(AutomationState.WAITING_USER)
        }
    }

    fun waitingResumeState(): AutomationState? =
        prefs.getString("waiting_resume_state", null)?.let {
            runCatching { AutomationState.valueOf(it) }.getOrNull()
        }

    fun markTransferComplete() {
        prefs.edit().putBoolean("transfer_complete", true).apply()
    }

    fun isTransferComplete(): Boolean = prefs.getBoolean("transfer_complete", false)

    fun lastObservation(): String = prefs.getString("last_observation", "").orEmpty()

    fun recordObservation(value: String) {
        prefs.edit().putString("last_observation", value).apply()
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
