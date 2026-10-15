package com.auradroid.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager retry for a saved task. Retries are bounded and preserve the user's
 * selected executor. It never attempts accessibility actions unless that service is active.
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

        if (runAttemptCount >= MAX_RETRIES) {
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
        val useWeb = inputData.getBoolean("use_web", false)
        val url = inputData.getString("url").orEmpty()
        val folder = inputData.getString("folder_path")
            ?: prefs.getString("folder_path", "/storage/emulated/0/Download/AutoVideos").orEmpty()
        if (useWeb) service.startBackgroundWebTask(apiKey, model, url, state.goal, folder)
        else service.startUniversalAiTask(apiKey, model, state.goal)
        return Result.success()
    }

    companion object { const val MAX_RETRIES = 3 }
}
