package com.auradroid.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager retry hook for deferred/retryable work.
 * The worker deliberately does not replay UI gestures: only the task executor that
 * understands a saved checkpoint may safely resume a task.
 */
class LongTaskRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val state = LongTaskStore.get(applicationContext) ?: return Result.success()
        return when (state.status) {
            LongTaskState.Status.CANCELLED,
            LongTaskState.Status.COMPLETED -> Result.success()
            LongTaskState.Status.PAUSED -> Result.success()
            LongTaskState.Status.FAILED -> {
                if (state.retryCount >= MAX_RETRIES) Result.failure()
                else {
                    LongTaskStore.save(applicationContext, state.copy(
                        status = LongTaskState.Status.QUEUED,
                        retryCount = state.retryCount + 1,
                        lastError = ""
                    ))
                    // Signal a retryable state; task-specific execution is resumed by the app/service.
                    Result.success()
                }
            }
            else -> Result.success()
        }
    }

    companion object { const val MAX_RETRIES = 3 }
}
