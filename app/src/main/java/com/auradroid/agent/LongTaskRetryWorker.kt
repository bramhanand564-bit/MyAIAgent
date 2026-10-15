package com.auradroid.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager guard for deferred retry requests. UI tasks are resumed only when the
 * accessibility service is connected; otherwise bounded WorkManager backoff applies.
 * The user's explicit Retry button chooses the execution mode and starts the task.
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
        // Accessibility-driven actions must not be replayed blindly by a background worker.
        // Return retry so Android backs off until the user-visible service is available.
        return if (AutoTouchService.instance == null) Result.retry() else Result.success()
    }

    companion object { const val MAX_RETRIES = 3 }
}
