package com.auradroid.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Bounded WorkManager retry for a failed task. UI/accessibility actions are retried only
 * when the accessibility service is connected; otherwise WorkManager applies backoff.
 */
class LongTaskRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val state = LongTaskStore.get(applicationContext) ?: return Result.success()
        if (state.status == LongTaskState.Status.CANCELLED ||
            state.status == LongTaskState.Status.COMPLETED ||
            state.status == LongTaskState.Status.PAUSED) return Result.success()

        if (state.retryCount >= MAX_RETRIES) {
            LongTaskStore.fail(applicationContext, state.lastError.ifBlank { "Retry limit reached" })
            return Result.failure()
        }

        val service = AutoTouchService.instance ?: return Result.retry()
        val prefs = applicationContext.getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        if (apiKey.isBlank() || state.goal.isBlank()) {
            LongTaskStore.fail(applicationContext, "Missing saved API key or task goal")
            return Result.failure()
        }

        LongTaskStore.save(applicationContext, state.copy(
            status = LongTaskState.Status.QUEUED,
            retryCount = state.retryCount + 1,
            lastError = ""
        ))
        service.startUniversalAiTask(apiKey, model, state.goal)
        return Result.success()
    }

    companion object { const val MAX_RETRIES = 3 }
}
