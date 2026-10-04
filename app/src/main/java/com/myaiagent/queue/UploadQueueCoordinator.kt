package com.myaiagent.queue

import android.content.Context
import com.myaiagent.model.UploadItem
import com.myaiagent.scheduler.UploadAlarmScheduler

object UploadQueueCoordinator {
    fun nextEligible(
        items: List<UploadItem>,
        now: Long = System.currentTimeMillis()
    ): UploadItem? =
        items.asSequence()
            .filter { it.status == "QUEUED" || it.status == "SCHEDULED" || it.status == "ERROR" }
            .filter { it.scheduledAt == null || it.scheduledAt <= now }
            .filter { it.uri.isNotBlank() }
            .minByOrNull { it.scheduledAt ?: Long.MIN_VALUE }

    fun scheduleAll(context: Context, items: List<UploadItem>) {
        items.filter { it.status != "UPLOADED" && it.scheduledAt != null }
            .forEach { UploadAlarmScheduler.schedule(context, it) }
    }

    fun cancel(context: Context, item: UploadItem) {
        UploadAlarmScheduler.cancel(context, item.id)
    }
}