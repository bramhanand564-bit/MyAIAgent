package com.auradroid.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * User-visible lifecycle shell for an explicitly started long task.
 * This service tracks task state; the accessibility executor remains responsible for UI actions.
 */
class LongTaskForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "AuraDroid Long Tasks", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = LongTaskStore.get(this)
        if (state == null || state.status in listOf(
                LongTaskState.Status.CANCELLED, LongTaskState.Status.COMPLETED,
                LongTaskState.Status.FAILED, LongTaskState.Status.PAUSED
            )) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(state))
        return START_NOT_STICKY
    }

    private fun buildNotification(state: LongTaskState): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AuraDroid task running")
            .setContentText("${state.progress}% • ${state.currentStep.ifBlank { state.goal }}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, state.progress, false)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "auradroid_long_task_channel"
        private const val NOTIFICATION_ID = 2002

        fun start(context: Context) {
            val intent = Intent(context, LongTaskForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LongTaskForegroundService::class.java))
        }
    }
}
