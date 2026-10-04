package com.myaiagent.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore

class UploadRunnerService : Service() {
    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 7001
    }

    private lateinit var queueStore: UploadQueueStore

    override fun onCreate() {
        super.onCreate()
        queueStore = UploadQueueStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID)
        val item = itemId?.let { id -> queueStore.load().firstOrNull { it.id == id } }

        startForeground(NOTIFICATION_ID, notification("Automation started"))
        if (item == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        queueStore.update(item.copy(status = "RUNNING"))
        launchYouTube(item)
        return START_NOT_STICKY
    }

    private fun launchYouTube(item: UploadItem) {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            updateNotification("YouTube opened for: " + item.fileName)
        } else {
            updateNotification("YouTube app is not installed")
            queueStore.update(item.copy(status = "ERROR"))
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MyAIAgent Automation",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MyAIAgent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
