package com.nax.myaiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt
import kotlin.random.Random

class AutoTapAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tapCount = 0
    private var startedAt = 0L
    private var markerView: MarkerView? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var receiverRegistered = false

    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!running || startedAt <= 0L) return
            publish("RUNNING", System.currentTimeMillis() - startedAt)
            handler.postDelayed(this, 500L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_START -> startTapping()
                MainActivity.ACTION_STOP -> stopTapping()
                ACTION_SHOW_MARKER -> showMarker()
                ACTION_HIDE_MARKER -> hideMarker()
            }
        }
    }

    companion object {
        const val ACTION_SHOW_MARKER = "com.nax.myaiagent.SHOW_MARKER"
        const val ACTION_HIDE_MARKER = "com.nax.myaiagent.HIDE_MARKER"
        const val ACTION_POINT_CHANGED = "com.nax.myaiagent.POINT_CHANGED"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(MainActivity.ACTION_START)
                addAction(MainActivity.ACTION_STOP)
                addAction(ACTION_SHOW_MARKER)
                addAction(ACTION_HIDE_MARKER)
            }

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }

        publish("READY")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Auto Tapper uses the user's configured screen coordinates and does not
        // inspect or modify accessibility node content.
    }

    override fun onInterrupt() {
        stopTapping()
    }

    private fun startTapping() {
        if (running) return

        val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val x = p.getInt("x", 0)
        val y = p.getInt("y", 0)
        val interval = p.getLong("interval", 500L).coerceAtLeast(50L)
        val delay = p.getInt("delay", 0).coerceAtLeast(0)
        val duration = p.getInt("duration", 0).coerceAtLeast(0)
        val maxTaps = p.getInt("maxTaps", 0).coerceAtLeast(0)
        val jitter = p.getInt("jitter", 0).coerceAtLeast(0)
        val pressDuration = p.getInt("pressDuration", 1).coerceIn(1, 10000)
        val intervalJitter = p.getInt("intervalJitter", 0).coerceAtLeast(0)

        running = true
        tapCount = 0
        startedAt = 0L
        publish("WAITING", 0L)

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!running) return@postDelayed

            startedAt = System.currentTimeMillis()
            publish("RUNNING", 0L)
            handler.post(progressRunnable)

            scheduleNext(
                x, y, interval, duration, maxTaps, jitter,
                pressDuration, intervalJitter
            )
        }, delay * 1000L)
    }

    private fun scheduleNext(
        x: Int,
        y: Int,
        interval: Long,
        duration: Int,
        maxTaps: Int,
        jitter: Int,
        pressDuration: Int,
        intervalJitter: Int
    ) {
        if (!running) return

        if (
            duration > 0 &&
            System.currentTimeMillis() - startedAt >= duration * 1000L
        ) {
            stopTapping()
            return
        }

        if (maxTaps > 0 && tapCount >= maxTaps) {
            stopTapping()
            return
        }

        val dm = resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(1)
        val height = dm.heightPixels.coerceAtLeast(1)

        val jx = if (jitter > 0) {
            Random.nextInt(-jitter, jitter + 1)
        } else {
            0
        }
        val jy = if (jitter > 0) {
            Random.nextInt(-jitter, jitter + 1)
        } else {
            0
        }

        val tx = (x + jx).coerceIn(0, width - 1)
        val ty = (y + jy).coerceIn(0, height - 1)

        val path = Path().apply {
            moveTo(tx.toFloat(), ty.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    pressDuration.toLong().coerceAtLeast(1L)
                )
            )
            .build()

        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) {
                    if (!running) return

                    tapCount++
                    sendBroadcast(
                        Intent(MainActivity.ACTION_TAP_COUNT)
                            .setPackage(packageName)
                            .putExtra("count", tapCount)
                    )

                    if (maxTaps > 0 && tapCount >= maxTaps) {
                        stopTapping()
                        return
                    }

                    val randomOffset = if (intervalJitter > 0) {
                        Random.nextLong(
                            -intervalJitter.toLong(),
                            intervalJitter.toLong() + 1
                        )
                    } else {
                        0L
                    }

                    val nextDelay = (interval + randomOffset).coerceAtLeast(50L)
                    handler.postDelayed(
                        {
                            scheduleNext(
                                x, y, interval, duration, maxTaps,
                                jitter, pressDuration, intervalJitter
                            )
                        },
                        nextDelay
                    )
                }

                override fun onCancelled(gesture: GestureDescription?) {
                    if (running) {
                        handler.postDelayed(
                            {
                                scheduleNext(
                                    x, y, interval, duration, maxTaps,
                                    jitter, pressDuration, intervalJitter
                                )
                            },
                            interval.coerceAtLeast(50L)
                        )
                    }
                }
            },
            null
        )

        if (!ok) {
            publish("GESTURE FAILED", System.currentTimeMillis() - startedAt)
            handler.postDelayed(
                {
                    scheduleNext(
                        x, y, interval, duration, maxTaps,
                        jitter, pressDuration, intervalJitter
                    )
                },
                interval.coerceAtLeast(250L)
            )
        }
    }

    private fun stopTapping() {
        val finalElapsed = if (startedAt > 0L) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else {
            0L
        }

        if (!running) {
            publish("STOPPED", finalElapsed)
            return
        }

        running = false
        handler.removeCallbacksAndMessages(null)
        publish("STOPPED", finalElapsed)
    }

    private fun publish(value: String, elapsedMs: Long? = null) {
        val intent = Intent(MainActivity.ACTION_STATUS)
            .setPackage(packageName)
            .putExtra("value", value)

        if (elapsedMs != null) {
            intent.putExtra("elapsedMs", elapsedMs)
        }

        sendBroadcast(intent)
    }

    private fun showMarker() {
        if (markerView != null || !android.provider.Settings.canDrawOverlays(this)) {
            return
        }

        val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val dm = resources.displayMetrics
        val x = p.getInt("x", dm.widthPixels / 2)
        val y = p.getInt("y", dm.heightPixels / 2)
        val size = (44 * dm.density).roundToInt().coerceAtLeast(1)

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
        }

        val view = MarkerView().also { marker ->
            marker.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        marker.downX = e.rawX
                        marker.downY = e.rawY
                        marker.startX = params.x
                        marker.startY = params.y
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = marker.startX + (e.rawX - marker.downX).roundToInt()
                        params.y = marker.startY + (e.rawY - marker.downY).roundToInt()
                        windowManager.updateViewLayout(marker, params)
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val cx = params.x + size / 2
                        val cy = params.y + size / 2
                        p.edit().putInt("x", cx).putInt("y", cy).apply()
                        sendBroadcast(
                            Intent(ACTION_POINT_CHANGED)
                                .setPackage(packageName)
                                .putExtra("x", cx)
                                .putExtra("y", cy)
                        )
                        true
                    }

                    else -> false
                }
            }
        }

        markerView = view
        markerParams = params

        runCatching {
            windowManager.addView(view, params)
        }.onFailure {
            markerView = null
            markerParams = null
            publish("OVERLAY FAILED")
        }
    }

    private fun hideMarker() {
        markerView?.let {
            runCatching { windowManager.removeView(it) }
        }
        markerView = null
        markerParams = null
    }

    override fun onDestroy() {
        stopTapping()
        hideMarker()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private inner class MarkerView : View(this) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        private val paint = android.graphics.Paint(1)

        override fun onDraw(c: android.graphics.Canvas) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.WHITE
            c.drawCircle(width / 2f, height / 2f, 14f, paint)

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = Color.rgb(0, 122, 255)
            c.drawCircle(width / 2f, height / 2f, 7f, paint)
        }
    }
}
