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
        const val EXTRA_TEST_MODE = "test_mode"
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
        val testMode = intent?.getBooleanExtra(EXTRA_TEST_MODE, false) == true
        val item = itemId?.let { id ->
            queueStore.load().firstOrNull { it.id == id }
        }

        startForeground(NOTIFICATION_ID, notification(if (testMode) "Test automation started" else "Automation started"))

        if (item == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!testMode && item.status == "UPLOADED") {
            updateNotification("Upload already verified: " + item.fileName)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!testMode && item.scheduledAt != null && item.scheduledAt > System.currentTimeMillis()) {
            UploadAlarmScheduler.schedule(this, item)
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
                scheduledAt = if (testMode) null else item.scheduledAt,
                automationMode = "NATIVE_STUDIO",
                lastRunAt = System.currentTimeMillis(),
                resultNote = if (testMode) "TEST • Automation session started" else "Automation session started"
            )
        )

        val initialState = AutomationState.WAITING_FOR_APP
        sessionStore.begin(item, initialState, testMode)

        // All legacy modes now resolve to the native YouTube Studio app.
        // This removes the desktop Studio WebView entirely.
        val started = launchNativeYouTubeStudio(item)

        if (!started) {
            val note = "Could not start YouTube Studio or access the selected video"
            queueStore.update(
                item.copy(
                    status = "ERROR",
                    lastRunAt = System.currentTimeMillis(),
                    resultNote = note
                )
            )
            AutomationLiveStore(this).finish(false, note)
            if (testMode) TestRunStore(this).finish(false, note)
            sessionStore.clear()
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun launchNativeYouTubeStudio(item: UploadItem): Boolean {
        val studioPackage = "com.google.android.apps.youtube.creator"
        val studioIntent = packageManager.getLaunchIntentForPackage(studioPackage)

        if (studioIntent == null) {
            updateNotification("YouTube Studio app is not installed")
            return false
        }

        return runCatching {
            val uri = Uri.parse(item.uri)
            if (contentResolver.openAssetFileDescriptor(uri, "r") == null) {
                updateNotification("Video file is no longer accessible")
                return false
            }

            // Open the actual mobile YouTube Studio app. The accessibility
            // service then drives its visible UI and the system picker.
            studioIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(studioIntent)
            updateNotification("Native YouTube Studio opened: " + item.fileName)
            true
        }.onFailure {
            updateNotification("Could not start YouTube Studio")
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
