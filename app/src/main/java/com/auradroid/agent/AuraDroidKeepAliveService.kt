package com.auradroid.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Visible best-effort process keep-alive for AuraDroid OS.
 *
 * Android and device vendors may still stop this service or the accessibility service. This
 * service never hides its notification and uses a timed partial wake lock to bound accidental
 * indefinite wake-lock ownership. The system can restart it with START_STICKY when permitted.
 */
class AuraDroidKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val renewWakeLock = object : Runnable {
        override fun run() {
            try {
                val lock = wakeLock
                if (lock != null && !lock.isHeld) {
                    lock.acquire(WAKELOCK_TIMEOUT_MS)
                } else if (lock != null) {
                    lock.acquire(WAKELOCK_TIMEOUT_MS)
                }
            } catch (_: Exception) {
                // Vendor power-management policy may reject the request.
            }
            handler.postDelayed(this, WAKELOCK_RENEW_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireTimedWakeLock()
        handler.postDelayed(renewWakeLock, WAKELOCK_RENEW_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopKeepAlive()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        acquireTimedWakeLock()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopKeepAlive()
        super.onDestroy()
    }

    private fun acquireTimedWakeLock() {
        try {
            if (wakeLock == null) {
                val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:AuraDroidEngine"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            val lock = wakeLock ?: return
            if (!lock.isHeld) lock.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (_: Exception) {
            // Do not crash the foreground service if a device rejects the wake lock.
        }
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(renewWakeLock)
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AuraDroid Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the AuraDroid background engine is active."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AuraDroidKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("AuraDroid OS • Nax Bot")
            .setContentText("Background engine active. Tap Stop to disable it.")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop engine", stopPendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "auradroid_engine_keepalive"
        private const val NOTIFICATION_ID = 4701
        private const val ACTION_STOP = "com.auradroid.agent.action.STOP_KEEPALIVE"
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val WAKELOCK_RENEW_INTERVAL_MS = 5L * 60L * 60L * 1000L

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AuraDroidKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {
                // Foreground-service launch can be restricted by OS policy or battery settings.
            }
        }
    }
}
