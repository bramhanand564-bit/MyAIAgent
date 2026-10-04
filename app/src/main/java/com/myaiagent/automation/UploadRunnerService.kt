package com.myaiagent.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.myaiagent.YouTubeWorkspaceActivity
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore
import com.myaiagent.scheduler.UploadAlarmScheduler

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
        val item = itemId?.let { id ->
            queueStore.load().firstOrNull { it.id == id }
        }

        startForeground(NOTIFICATION_ID, notification("Automation started"))

        if (item == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val sessionStore = AutomationSessionStore(this)
        if (sessionStore.isActive(item.id)) {
            queueStore.update(
                item.copy(
                    status = "SCHEDULED",
                    resultNote = "Another upload is active; deferred by 60 seconds"
                )
            )
            UploadAlarmScheduler.scheduleSoon(this, item)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        queueStore.update(
            item.copy(
                status = "RUNNING",
                lastRunAt = System.currentTimeMillis(),
                resultNote = "Automation session started"
            )
        )

        val initialState = if (item.automationMode == "EXTERNAL_APP") {
            AutomationState.FILL_DETAILS
        } else {
            AutomationState.WAITING_FOR_APP
        }
        sessionStore.begin(item, initialState)

        val started = if (item.automationMode == "EXTERNAL_APP") {
            launchExternalYouTube(item)
        } else {
            launchEmbeddedYouTube(item)
        }

        if (!started) {
            queueStore.update(
                item.copy(
                    status = "ERROR",
                    lastRunAt = System.currentTimeMillis(),
                    resultNote = "Could not start selected YouTube mode"
                )
            )
            sessionStore.clear()
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun launchEmbeddedYouTube(item: UploadItem): Boolean {
        return runCatching {
            startActivity(Intent(this, YouTubeWorkspaceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("item_id", item.id)
            })
            updateNotification("Embedded YouTube opened for: " + item.fileName)
            true
        }.getOrDefault(false)
    }

    private fun launchExternalYouTube(item: UploadItem): Boolean {
        val youtubePackage = "com.google.android.youtube"
        if (packageManager.getLaunchIntentForPackage(youtubePackage) == null) {
            updateNotification("YouTube app is not installed")
            return false
        }

        return runCatching {
            val uri = Uri.parse(item.uri)
            if (contentResolver.openAssetFileDescriptor(uri, "r") == null) {
                updateNotification("Video file is no longer accessible")
                return false
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage(youtubePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(shareIntent)
            updateNotification("Video handed to YouTube: " + item.fileName)
            true
        }.onFailure {
            if (it is ActivityNotFoundException) {
                updateNotification("YouTube cannot accept this video")
            } else {
                updateNotification("Could not start YouTube automation")
            }
        }.getOrDefault(false)
    }

    private fun notification(text: String): Notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MyAIAgent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("MyAIAgent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MyAIAgent Automation",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(text)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
