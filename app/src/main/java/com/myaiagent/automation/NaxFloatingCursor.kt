package com.myaiagent.automation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import kotlin.math.max

/**
 * Non-touchable accessibility overlay used as NAX's visible "thinking cursor".
 * It floats above the target app without intercepting the user's taps.
 */
class NaxFloatingCursor(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val view = CursorView(context)
    private var attached = false

    init {
        view.visibility = View.GONE
    }

    fun showAt(x: Float, y: Float, message: String) {
        handler.post {
            ensureAttached()
            view.message = message
            view.visibility = View.VISIBLE
            val lp = view.layoutParams as WindowManager.LayoutParams
            lp.x = (x - 72f).toInt()
            lp.y = (y - 72f).toInt()
            windowManager.updateViewLayout(view, lp)
            view.startPulse()
        }
    }

    fun showForNode(node: AccessibilityNodeInfo, message: String) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            showAt(bounds.centerX().toFloat(), bounds.centerY().toFloat(), message)
        }
    }

    fun setMessage(message: String) {
        handler.post {
            if (!attached) return@post
            view.message = message
            view.invalidate()
        }
    }

    fun hide() {
        handler.post {
            view.stopPulse()
            if (attached) {
                runCatching { windowManager.removeView(view) }
                attached = false
            }
        }
    }

    private fun ensureAttached() {
        if (attached) return
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val params = WindowManager.LayoutParams(
            dp(150),
            dp(112),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(110)
        }
        windowManager.addView(view, params)
        attached = true
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class CursorView(context: Context) : View(context) {
        var message: String = "NAX • working"
            set(value) {
                field = value.take(42)
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulse = 0f
        private var animator: ValueAnimator? = null

        fun startPulse() {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1050L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopPulse() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val cx = width * 0.5f
            val cy = 42f * d

            // Soft animated target ring.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * d
            paint.color = Color.argb(
                (120f + 100f * (1f - pulse)).toInt().coerceIn(0, 220),
                185, 155, 255
            )
            canvas.drawCircle(cx, cy, (28f + 9f * pulse) * d, paint)

            // Cute robot body.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(35, 36, 47)
            canvas.drawRoundRect(
                cx - 24f*d, cy - 22f*d, cx + 24f*d, cy + 22f*d,
                16f*d, 16f*d, paint
            )

            paint.color = Color.rgb(194, 164, 255)
            canvas.drawCircle(cx - 9f*d, cy - 2f*d, 4f*d, paint)
            canvas.drawCircle(cx + 9f*d, cy - 2f*d, 4f*d, paint)

            paint.color = Color.WHITE
            canvas.drawCircle(cx - 9f*d, cy - 3f*d, 1.4f*d, paint)
            canvas.drawCircle(cx + 9f*d, cy - 3f*d, 1.4f*d, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f*d
            paint.color = Color.rgb(194, 164, 255)
            val smile = RectF(cx - 9f*d, cy + 2f*d, cx + 9f*d, cy + 13f*d)
            canvas.drawArc(smile, 15f, 150f, false, paint)

            // Antenna.
            canvas.drawLine(cx, cy - 22f*d, cx, cy - 31f*d, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy - 34f*d, 4f*d, paint)

            // Compact status bubble.
            paint.color = Color.argb(235, 24, 25, 33)
            val bubble = RectF(7f*d, 73f*d, width - 7f*d, 108f*d)
            canvas.drawRoundRect(bubble, 12f*d, 12f*d, paint)

            paint.color = Color.WHITE
            paint.textSize = 9.5f*d
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            val clipped = message.take(28)
            canvas.drawText(clipped, width/2f, 95f*d, paint)
        }
    }
}
