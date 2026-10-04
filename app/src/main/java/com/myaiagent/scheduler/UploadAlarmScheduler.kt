package com.myaiagent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.myaiagent.model.UploadItem

object UploadAlarmScheduler {
    private const val ACTION_UPLOAD_ALARM = "com.myaiagent.action.UPLOAD_ALARM"
    private const val EXTRA_ITEM_ID = "item_id"

    fun schedule(context: Context, item: UploadItem): Boolean =
        item.scheduledAt?.let { scheduleAt(context, item, it) } ?: false

    private fun scheduleAt(context: Context, item: UploadItem, whenAt: Long): Boolean {
        if (whenAt <= System.currentTimeMillis()) return false

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = pendingIntent(context, item.id)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    whenAt,
                    pendingIntent
                )
                true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    whenAt,
                    pendingIntent
                )
                true
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, whenAt, pendingIntent)
                true
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun scheduleSoon(context: Context, item: UploadItem, delayMs: Long = 60_000L): Boolean =
        scheduleAt(context, item, System.currentTimeMillis() + delayMs)

    fun cancel(context: Context, itemId: String) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, itemId))
    }

    fun rescheduleAll(context: Context, items: List<UploadItem>) {
        items.filter { it.status != "UPLOADED" && it.scheduledAt != null }
            .forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, itemId: String): PendingIntent {
        val intent = Intent(context, UploadAlarmReceiver::class.java).apply {
            action = ACTION_UPLOAD_ALARM
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun itemId(intent: Intent): String? = intent.getStringExtra(EXTRA_ITEM_ID)
}