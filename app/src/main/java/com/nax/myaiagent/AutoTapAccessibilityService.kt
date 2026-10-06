package com.nax.myaiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.graphics.Path
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
    private var markerView: MarkerView? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, IntentFilter().apply {
                addAction(MainActivity.ACTION_START); addAction(MainActivity.ACTION_STOP)
                addAction(ACTION_SHOW_MARKER); addAction(ACTION_HIDE_MARKER)
            }, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter().apply {
                addAction(MainActivity.ACTION_START); addAction(MainActivity.ACTION_STOP)
                addAction(ACTION_SHOW_MARKER); addAction(ACTION_HIDE_MARKER)
            })
        }
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
        val maxTaps = p.getInt("maxTaps", 0).coerceAtLeast(0)
        val jitter = p.getInt("jitter", 0).coerceAtLeast(0)
        running = true; tapCount = 0; startedAt = 0L
        publish("WAITING")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!running) return@postDelayed
            startedAt = System.currentTimeMillis()
            publish("RUNNING")
            scheduleNext(x, y, interval, duration, maxTaps, jitter)
        }, delay * 1000L)
    }

    private fun scheduleNext(x: Int, y: Int, interval: Long, duration: Int, maxTaps: Int, jitter: Int) {
        if (!running) return
        if (duration > 0 && System.currentTimeMillis() - startedAt >= duration * 1000L) { stopTapping(); return }
        if (maxTaps > 0 && tapCount >= maxTaps) { stopTapping(); return }
        val dm = resources.displayMetrics
        val jx = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
        val jy = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
        val tx = (x + jx).coerceIn(0, dm.widthPixels - 1)
        val ty = (y + jy).coerceIn(0, dm.heightPixels - 1)
        val path = Path().apply { moveTo(tx.toFloat(), ty.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 1)
        ).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (!running) return
                tapCount++
                sendBroadcast(Intent(MainActivity.ACTION_TAP_COUNT).setPackage(packageName).putExtra("count",tapCount))
                if (maxTaps > 0 && tapCount >= maxTaps) { stopTapping(); return }
                handler.postDelayed({ scheduleNext(x,y,interval,duration,maxTaps,jitter) }, interval)
            }
            override fun onCancelled(g: GestureDescription?) {
                if (running) handler.postDelayed({ scheduleNext(x,y,interval,duration,maxTaps,jitter) }, interval)
            }
        }, null)
        if (!ok) {
            publish("GESTURE FAILED")
            handler.postDelayed({ scheduleNext(x,y,interval,duration,maxTaps,jitter) }, interval.coerceAtLeast(250L))
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

    private fun showMarker() {
        if (markerView != null || !android.provider.Settings.canDrawOverlays(this)) return
        val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val dm = resources.displayMetrics
        val x = p.getInt("x", dm.widthPixels / 2)
        val y = p.getInt("y", dm.heightPixels / 2)
        val size = (44 * dm.density).roundToInt()
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x - size / 2; this.y = y - size / 2 }
        val view = MarkerView().also { it.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { it.downX=e.rawX; it.downY=e.rawY; it.startX=params.x; it.startY=params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x=it.startX+(e.rawX-it.downX).roundToInt(); params.y=it.startY+(e.rawY-it.downY).roundToInt(); windowManager.updateViewLayout(it,params); true }
                MotionEvent.ACTION_UP -> { val cx=params.x+size/2; val cy=params.y+size/2; p.edit().putInt("x",cx).putInt("y",cy).apply(); sendBroadcast(Intent(ACTION_POINT_CHANGED).setPackage(packageName).putExtra("x",cx).putExtra("y",cy)); true }
                else -> false
            }
        }}
        markerView=view; markerParams=params
        runCatching { windowManager.addView(view,params) }
            .onFailure { markerView=null; markerParams=null; publish("OVERLAY FAILED") }
    }

    private fun hideMarker() { markerView?.let { runCatching { windowManager.removeView(it) } }; markerView=null; markerParams=null }

    override fun onDestroy() {
        stopTapping(); hideMarker(); runCatching { unregisterReceiver(receiver) }; super.onDestroy()
    }

    private inner class MarkerView : View(this) {
        var downX=0f; var downY=0f; var startX=0; var startY=0
        private val paint=android.graphics.Paint(1)
        override fun onDraw(c:android.graphics.Canvas){ paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=4f;paint.color=Color.WHITE;c.drawCircle(width/2f,height/2f,14f,paint);paint.style=android.graphics.Paint.Style.FILL;paint.color=Color.rgb(0,122,255);c.drawCircle(width/2f,height/2f,7f,paint) }
    }
}
