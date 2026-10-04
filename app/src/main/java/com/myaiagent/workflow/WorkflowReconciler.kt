package com.myaiagent.workflow

import android.content.Context
import com.myaiagent.folder.FolderVideoImporter
import com.myaiagent.queue.UploadQueueStore
import com.myaiagent.scheduler.UploadAlarmScheduler
import java.util.Calendar

object WorkflowReconciler {
    private const val MAX_NEW_SLOTS = 366

    fun sync(context: Context): Int {
        val app = context.applicationContext
        val config = WorkflowStore(app).load()
        if (!config.enabled || config.folderUri.isBlank() || config.times.isEmpty()) return 0

        val queue = UploadQueueStore(app)
        val imported = runCatching {
            FolderVideoImporter.importVideos(app, android.net.Uri.parse(config.folderUri))
        }.getOrDefault(emptyList()).map {
            it.copy(
                visibility = config.visibility,
                automationMode = config.automationMode,
                contentType = config.contentType
            )
        }
        queue.addAllUnique(imported)

        queue.load().filter { it.status == "QUEUED" }.forEach { item ->
            queue.update(
                item.copy(
                    visibility = config.visibility,
                    automationMode = config.automationMode,
                    contentType = config.contentType
                )
            )
        }

        val pending = queue.load().filter { it.status == "QUEUED" && it.scheduledAt == null }
            .sortedBy { it.fileName.lowercase() }
        if (pending.isEmpty()) return 0

        val scheduled = queue.load()
            .filter { it.status == "SCHEDULED" && (it.scheduledAt ?: 0L) > System.currentTimeMillis() }
            .mapNotNull { it.scheduledAt }
            .toMutableSet()

        var cursor = maxOf(
            System.currentTimeMillis(),
            scheduled.maxOrNull() ?: 0L
        )
        var assigned = 0

        for (item in pending) {
            var found = false
            for (day in 0..MAX_NEW_SLOTS) {
                for (time in config.times.take(config.dailyLimit)) {
                    val parts = time.split(":")
                    if (parts.size != 2) continue
                    val slot = Calendar.getInstance().apply {
                        timeInMillis = cursor
                        add(Calendar.DAY_OF_YEAR, if (day == 0) 0 else day)
                        set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
                        set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    if (slot <= System.currentTimeMillis() || scheduled.contains(slot)) continue

                    val updated = item.copy(
                        scheduledAt = slot,
                        status = "SCHEDULED",
                        visibility = config.visibility,
                        automationMode = config.automationMode,
                        contentType = config.contentType
                    )
                    queue.update(updated)
                    UploadAlarmScheduler.schedule(app, updated)
                    scheduled += slot
                    cursor = slot
                    assigned++
                    found = true
                    break
                }
                if (found) break
            }
        }
        return assigned
    }
}
