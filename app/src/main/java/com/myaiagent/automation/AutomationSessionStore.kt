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
        AgentVerifiedMemoryStore(appContext).clearPending()
        AgentObservationStore(appContext).clear()

        prefs.edit()
            .putString("item_id", item.id)
            .putString("state", initialState.name)
            .putLong("started_at", System.currentTimeMillis())
            .putInt("attempt", 0)
            .putBoolean("test_mode", testMode)
            .putString("waiting_resume_state", null)
            .putString("last_observation", "")
            .putBoolean("transfer_complete", false)
            .putBoolean("picker_selection_pending", false)
            .putBoolean("direct_media_handoff", false)
            .apply()

        AutomationLiveStore(appContext).start(item.id, item.fileName)
        AutomationLiveStore(appContext).state(initialState)
        MindEngine.onStart(appContext, item.id, item.fileName, initialState)
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
        val previousState = this.state()
        if (previousState != state) {
            AgentVerifiedMemoryStore(appContext).verifyTransition(previousState, state)
        }

        prefs.edit()
            .putString("state", state.name)
            .putInt("attempt", 0)
            .putBoolean("picker_selection_pending", state == AutomationState.WAITING_FOR_PICKER && prefs.getBoolean("picker_selection_pending", false))
            .apply()

        AutomationLiveStore(appContext).state(state)
        MindEngine.onState(appContext, state)
        if (isTestMode()) {
            TestRunStore(appContext).setState(state)
        }
    }

    fun markDirectMediaHandoffAttempted(value: Boolean = true) {
        prefs.edit().putBoolean("direct_media_handoff", value).apply()
    }

    fun directMediaHandoffAttempted(): Boolean =
        prefs.getBoolean("direct_media_handoff", false)

    fun isTestMode(): Boolean = prefs.getBoolean("test_mode", false)

    fun markPickerSelectionPending() {
        prefs.edit().putBoolean("picker_selection_pending", true).apply()
    }

    fun isPickerSelectionPending(): Boolean =
        prefs.getBoolean("picker_selection_pending", false)


    fun enterWaitingForUser(resumeState: AutomationState) {
        prefs.edit()
            .putString("waiting_resume_state", resumeState.name)
            .putString("state", AutomationState.WAITING_USER.name)
            .putInt("attempt", 0)
            .apply()
        AutomationLiveStore(appContext).state(AutomationState.WAITING_USER)
        MindEngine.onState(appContext, AutomationState.WAITING_USER)
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
        MindEngine.onRetry(appContext, next)
        return next
    }

    fun clear() {
        AgentVerifiedMemoryStore(appContext).clearPending()
        AgentObservationStore(appContext).clear()
        prefs.edit().clear().apply()
        MindStore(appContext).clear()
    }
}
