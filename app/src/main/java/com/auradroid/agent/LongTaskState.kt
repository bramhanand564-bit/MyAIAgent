package com.auradroid.agent

import android.content.Context
import org.json.JSONObject

/**
 * Durable lifecycle record for long-running user tasks.
 * Progress is an explicit 0..100 value; checkpoint is the last safe resume point.
 */
data class LongTaskState(
    val id: String,
    val goal: String,
    val status: Status,
    val progress: Int = 0,
    val currentStep: String = "",
    val checkpoint: String = "",
    val retryCount: Int = 0,
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    enum class Status { QUEUED, RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED }

    fun toJson() = JSONObject().apply {
        put("id", id); put("goal", goal); put("status", status.name)
        put("progress", progress.coerceIn(0, 100)); put("currentStep", currentStep)
        put("checkpoint", checkpoint); put("retryCount", retryCount)
        put("lastError", lastError); put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = LongTaskState(
            id = json.optString("id"),
            goal = json.optString("goal"),
            status = runCatching { Status.valueOf(json.optString("status", "QUEUED")) }.getOrDefault(Status.QUEUED),
            progress = json.optInt("progress").coerceIn(0, 100),
            currentStep = json.optString("currentStep"),
            checkpoint = json.optString("checkpoint"),
            retryCount = json.optInt("retryCount").coerceAtLeast(0),
            lastError = json.optString("lastError"),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}

/** SharedPreferences-backed atomic snapshots; never stores API keys or task secrets. */
object LongTaskStore {
    private const val PREFS = "auradroid_long_tasks"
    private const val KEY_ACTIVE = "active_task"

    fun begin(context: Context, goal: String): LongTaskState {
        val prior = get(context)
        val state = LongTaskState(
            id = "${System.currentTimeMillis()}",
            goal = goal,
            status = LongTaskState.Status.RUNNING,
            progress = 0,
            currentStep = "Starting",
            retryCount = if (prior?.goal == goal) prior.retryCount else 0
        )
        save(context, state)
        return state
    }

    fun get(context: Context): LongTaskState? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)
        if (raw.isNullOrBlank()) null else LongTaskState.fromJson(JSONObject(raw))
    }.getOrNull()

    fun save(context: Context, state: LongTaskState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE, state.copy(
                progress = state.progress.coerceIn(0, 100),
                updatedAt = System.currentTimeMillis()
            ).toJson().toString()).commit()
    }

    fun update(context: Context, transform: (LongTaskState) -> LongTaskState): LongTaskState? {
        val current = get(context) ?: return null
        return transform(current).also { save(context, it) }
    }

    fun pause(context: Context) = update(context) {
        if (it.status == LongTaskState.Status.RUNNING || it.status == LongTaskState.Status.QUEUED)
            it.copy(status = LongTaskState.Status.PAUSED) else it
    }

    fun resume(context: Context) = update(context) {
        if (it.status == LongTaskState.Status.PAUSED || it.status == LongTaskState.Status.FAILED)
            it.copy(status = LongTaskState.Status.QUEUED, lastError = "") else it
    }

    fun cancel(context: Context) = update(context) {
        if (it.status == LongTaskState.Status.COMPLETED) it
        else it.copy(status = LongTaskState.Status.CANCELLED, currentStep = "Cancelled by user")
    }

    fun complete(context: Context) = update(context) {
        it.copy(status = LongTaskState.Status.COMPLETED, progress = 100, currentStep = "Completed")
    }

    fun fail(context: Context, error: String) = update(context) {
        it.copy(status = LongTaskState.Status.FAILED, lastError = error.take(500))
    }

    fun checkpoint(context: Context, step: String, progress: Int, checkpoint: String) = update(context) {
        it.copy(currentStep = step, progress = progress.coerceIn(0, 100), checkpoint = checkpoint)
    }
}
