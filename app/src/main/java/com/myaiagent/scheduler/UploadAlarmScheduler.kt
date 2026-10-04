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

    fun schedule(context: Context, item: UploadItem): Boolean {
        val whenAt = item.scheduledAt ?: return false
        if (whenAt <= System.currentTimeMillis()) return false

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = pendingIntent(context, item.id)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                false
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

    fun cancel(context: Context, itemId: String) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, itemId))
    }

    fun rescheduleAll(context: Context, items: List<UploadItem>) {
        items.filter { it.status != "UPLOADED" }.forEach { schedule(context, it) }
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
