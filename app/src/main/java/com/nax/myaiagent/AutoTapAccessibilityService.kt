package com.nax.myaiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt
import kotlin.random.Random

class AutoTapAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tapCount = 0
    private var startedAt = 0L
    private var config = Runnable { }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_START -> startTapping()
                MainActivity.ACTION_STOP -> stopTapping()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(MainActivity.ACTION_START); addAction(MainActivity.ACTION_STOP)
        }, RECEIVER_NOT_EXPORTED)
        publish("READY")
    }

    private fun startTapping() {
        if (running) return
        val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val x = p.getInt("x", 0)
        val y = p.getInt("y", 0)
        val interval = p.getLong("interval", 500L).coerceAtLeast(50L)
        val delay = p.getInt("delay", 0).coerceAtLeast(0)
        val duration = p.getInt("duration", 0).coerceAtLeast(0)
        running = true; tapCount = 0; startedAt = System.currentTimeMillis()
        publish("WAITING")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!running) return@postDelayed
            publish("RUNNING")
            scheduleNext(x, y, interval, duration)
        }, delay * 1000L)
    }

    private fun scheduleNext(x: Int, y: Int, interval: Long, duration: Int) {
        if (!running) return
        if (duration > 0 && System.currentTimeMillis() - startedAt >= duration * 1000L) { stopTapping(); return }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 1)
        ).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (!running) return
                tapCount++
                sendBroadcast(Intent(MainActivity.ACTION_TAP_COUNT).setPackage(packageName).putExtra("count",tapCount))
                handler.postDelayed({ scheduleNext(x,y,interval,duration) }, interval)
            }
            override fun onCancelled(g: GestureDescription?) {
                if (running) handler.postDelayed({ scheduleNext(x,y,interval,duration) }, interval)
            }
        }, null)
        if (!ok) {
            publish("GESTURE FAILED")
            handler.postDelayed({ scheduleNext(x,y,interval,duration) }, interval.coerceAtLeast(250L))
        }
    }

    private fun stopTapping() {
        if (!running) { publish("STOPPED"); return }
        running = false
        handler.removeCallbacksAndMessages(null)
        publish("STOPPED")
    }

    private fun publish(value: String) {
        sendBroadcast(Intent(MainActivity.ACTION_STATUS).setPackage(packageName).putExtra("value", value))
    }

    override fun onInterrupt() = stopTapping()

    override fun onDestroy() {
        stopTapping()
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}
