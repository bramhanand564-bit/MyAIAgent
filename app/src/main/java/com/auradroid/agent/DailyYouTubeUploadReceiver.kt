package com.auradroid.agent

import android.app.AlarmManager
import android.app.BroadcastOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

class DailyYouTubeUploadReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DAILY_REMINDER = "com.auradroid.agent.ACTION_DAILY_YOUTUBE_UPLOAD"
        const val EXTRA_OPEN_NATIVE_UPLOAD = "extra_open_native_youtube_upload"
        private const val PREFS = "auradroid_daily_youtube_reminder"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        private const val REQUEST_CODE = 8167
        private const val NOTIFICATION_ID = 8168
        private const val CHANNEL_ID = "daily_youtube_upload"

        fun isScheduled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun schedule(context: Context, hour: Int, minute: Int) {
            val safeHour = hour.coerceIn(0, 23)
            val safeMinute = minute.coerceIn(0, 59)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_HOUR, safeHour)
                .putInt(KEY_MINUTE, safeMinute)
                .apply()
            scheduleNext(context, safeHour, safeMinute)
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false)
                .apply()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val pendingIntent = pendingIntent(context)
            try { alarmManager?.cancel(pendingIntent) } catch (_: Exception) {}
        }

        fun rescheduleAfterBoot(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            scheduleNext(
                context,
                prefs.getInt(KEY_HOUR, 19),
                prefs.getInt(KEY_MINUTE, 0)
            )
        }

        private fun scheduleNext(context: Context, hour: Int, minute: Int) {
            val now = System.currentTimeMillis()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pendingIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                }
            } catch (_: Exception) {
                try { alarmManager.set(AlarmManager.RTC_WAKEUP, next, pi) } catch (_: Exception) {}
            }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DailyYouTubeUploadReceiver::class.java).apply {
                action = ACTION_DAILY_REMINDER
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                rescheduleAfterBoot(context)
            }
            ACTION_DAILY_REMINDER -> {
                if (!isScheduled(context)) return
                showReminder(context)
                rescheduleAfterBoot(context)
            }
        }
    }

    private fun showReminder(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Daily YouTube upload",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "A daily reminder to review and upload the next queued video." }
            )
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_NATIVE_UPLOAD, true)
        }
        val openPending = PendingIntent.getActivity(
            context,
            REQUEST_CODE + 1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_sys_upload)
            .setContentTitle("Nax Bot • Daily YouTube Upload")
            .setContentText("Your next queued video is ready. Tap to prepare it with vision SEO.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Tap to select the next video using your newest/oldest queue preference, generate title and description, and open the YouTube share flow. Publishing remains under your control."
            ))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission is optional; the daily alarm remains scheduled.
        }
    }
}
