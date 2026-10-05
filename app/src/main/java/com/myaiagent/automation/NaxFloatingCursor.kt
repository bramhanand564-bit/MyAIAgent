package com.myaiagent.automation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Non-touchable Accessibility overlay used as NAX's live action cursor.
 *
 * It never performs automation itself. It only visualizes the exact target/action
 * that the AccessibilityService is already executing.
 */
class NaxFloatingCursor(private val context: Context) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val view = CursorView(context)

    private var attached = false
    private var moveAnimator: ValueAnimator? = null

    fun showAt(x: Float, y: Float, message: String) {
        handler.post {
            ensureAttached()
            view.message = message
            view.visibility = View.VISIBLE

            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            val target = targetLayout(x, y)

            if (params.x == target.first && params.y == target.second) {
                windowManager.updateViewLayout(view, params)
                view.startPulse()
                return@post
            }

            animateTo(target.first, target.second)
            view.startPulse()
        }
    }

    fun showStatus(message: String = "NAX • WORKING") {
        handler.post {
            ensureAttached()
            view.visibility = View.VISIBLE
            view.message = message
            if (!view.hasTarget) {
                val target = targetLayout(dp(86).toFloat(), dp(138).toFloat())
                moveNow(target.first, target.second)
            }
            view.startPulse()
        }
    }

    /**
     * Update the visible automation step without moving the cursor away from its last target.
     */
    fun setState(stateName: String, detail: String = "") {
        handler.post {
            val label = stateLabel(stateName)
            val suffix = detail.trim().takeIf { it.isNotEmpty() }
            view.message = if (suffix != null) "$label • $suffix" else label

            if (!attached || view.visibility != View.VISIBLE) {
                ensureAttached()
                view.visibility = View.VISIBLE
                if (!view.hasTarget) {
                    val target = targetLayout(dp(86).toFloat(), dp(138).toFloat())
                    moveNow(target.first, target.second)
                }
            }
            view.invalidate()
        }
    }

    fun showForNode(node: AccessibilityNodeInfo, message: String) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            showAt(bounds.centerX().toFloat(), bounds.centerY().toFloat(), message)
        }
    }

    fun moveTo(x: Float, y: Float) {
        handler.post {
            if (!attached) return@post
            val target = targetLayout(x, y)
            animateTo(target.first, target.second)
        }
    }

    fun tapFeedback() {
        handler.post {
            if (!attached || view.visibility != View.VISIBLE) return@post
            view.startTapPulse()
        }
    }

    fun setMessage(message: String) {
        handler.post {
            if (!attached) return@post
            view.message = message
            view.invalidate()
        }
    }

    fun isShown(): Boolean = attached && view.visibility == View.VISIBLE

    fun hide() {
        handler.post {
            moveAnimator?.cancel()
            moveAnimator = null
            view.stopAnimations()
            if (attached) {
                runCatching { windowManager.removeView(view) }
                attached = false
            }
            view.hasTarget = false
        }
    }

    private fun targetLayout(x: Float, y: Float): Pair<Int, Int> {
        val width = dp(176)
        val height = dp(128)
        val maxX = (context.resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (context.resources.displayMetrics.heightPixels - height).coerceAtLeast(0)

        // Keep the robot slightly above the real target when possible.
        val desiredX = (x - width / 2f).toInt()
        val desiredY = (y - height * 0.58f).toInt()

        return desiredX.coerceIn(0, maxX) to desiredY.coerceIn(0, maxY)
    }

    private fun animateTo(targetX: Int, targetY: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val startX = params.x
        val startY = params.y

        moveAnimator?.cancel()
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            addUpdateListener {
                if (!attached) return@addUpdateListener
                val t = it.animatedValue as Float
                val eased = 1f - (1f - t) * (1f - t)
                params.x = (startX + (targetX - startX) * eased).toInt()
                params.y = (startY + (targetY - startY) * eased).toInt()
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (attached) {
                        params.x = targetX
                        params.y = targetY
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    view.hasTarget = true
                    moveAnimator = null
                }
            })
            start()
        }
    }

    private fun moveNow(x: Int, y: Int) {
        moveAnimator?.cancel()
        moveAnimator = null
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
        view.hasTarget = true
    }

    private fun ensureAttached() {
        if (attached) return

        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val params = WindowManager.LayoutParams(
            dp(176),
            dp(128),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(110)
        }

        runCatching {
            windowManager.addView(view, params)
            attached = true
        }
    }

    private fun stateLabel(stateName: String): String = when (stateName) {
        "WAITING_FOR_APP" -> "WAITING • YouTube Studio"
        "FIND_CREATE" -> "STEP 1 • Find Create"
        "VERIFY_CREATE_MENU" -> "STEP 2 • Verify Create"
        "FIND_UPLOAD" -> "STEP 3 • Find Upload"
        "VERIFY_UPLOAD_PICKER" -> "STEP 4 • Verify Picker"
        "WAITING_FOR_PICKER" -> "STEP 5 • Select exact video"
        "FILL_DETAILS" -> "STEP 6 • Enter details"
        "SET_VISIBILITY" -> "STEP 7 • Set visibility"
        "PUBLISH" -> "STEP 8 • Publish"
        "MONITOR_UPLOAD" -> "STEP 9 • Monitor upload"
        "VERIFY" -> "STEP 10 • Final verify"
        "WAITING_USER" -> "PAUSED • Your action needed"
        "COMPLETE" -> "COMPLETE • Verified"
        "ERROR" -> "STOPPED • Check NAX Mind"
        else -> "NAX • \${stateName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}"
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class CursorView(context: Context) : View(context) {
        var message: String = "NAX • WORKING"
            set(value) {
                field = value.take(36)
                invalidate()
            }

        var hasTarget: Boolean = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
        }

        private var pulse = 0f
        private var tapPulse = 1f
        private var pulseAnimator: ValueAnimator? = null
        private var tapAnimator: ValueAnimator? = null

        fun startPulse() {
            if (pulseAnimator?.isRunning == true) return
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun startTapPulse() {
            tapAnimator?.cancel()
            tapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
                addUpdateListener {
                    tapPulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimations() {
            pulseAnimator?.cancel()
            tapAnimator?.cancel()
            pulseAnimator = null
            tapAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val d = resources.displayMetrics.density
            val cx = width * 0.5f
            val robotY = 42f * d

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.8f * d
            paint.color = Color.argb(
                (125f + 95f * (1f - pulse)).toInt().coerceIn(0, 220),
                195, 165, 255
            )
            canvas.drawCircle(cx, robotY, (28f + 8f * pulse) * d, paint)

            if (tapPulse < 1f) {
                paint.strokeWidth = 3f * d
                paint.color = Color.argb(
                    ((1f - tapPulse) * 180f).toInt().coerceIn(0, 180),
                    255, 255, 255
                )
                canvas.drawCircle(cx, robotY, (16f + 40f * tapPulse) * d, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(31, 33, 43)
            canvas.drawRoundRect(
                cx - 27f * d,
                robotY - 23f * d,
                cx + 27f * d,
                robotY + 24f * d,
                17f * d,
                17f * d,
                paint
            )

            paint.color = Color.rgb(77, 78, 94)
            canvas.drawRoundRect(
                cx - 31f * d,
                robotY - 9f * d,
                cx - 25f * d,
                robotY + 9f * d,
                3f * d,
                3f * d,
                paint
            )
            canvas.drawRoundRect(
                cx + 25f * d,
                robotY - 9f * d,
                cx + 31f * d,
                robotY + 9f * d,
                3f * d,
                3f * d,
                paint
            )

            paint.color = Color.rgb(202, 173, 255)
            canvas.drawCircle(cx - 10f * d, robotY - 2f * d, 4.4f * d, paint)
            canvas.drawCircle(cx + 10f * d, robotY - 2f * d, 4.4f * d, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx - 11.2f * d, robotY - 3.2f * d, 1.35f * d, paint)
            canvas.drawCircle(cx + 8.8f * d, robotY - 3.2f * d, 1.35f * d, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(202, 173, 255)
            canvas.drawArc(
                RectF(
                    cx - 10f * d,
                    robotY + 1f * d,
                    cx + 10f * d,
                    robotY + 14f * d
                ),
                15f,
                150f,
                false,
                paint
            )

            canvas.drawLine(cx, robotY - 23f * d, cx, robotY - 32f * d, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, robotY - 35f * d, 4.3f * d, paint)

            paint.color = Color.argb(238, 19, 21, 29)
            canvas.drawRoundRect(
                RectF(6f * d, 74f * d, width - 6f * d, 123f * d),
                14f * d,
                14f * d,
                paint
            )

            paint.color = Color.argb(55, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            canvas.drawRoundRect(
                RectF(6.5f * d, 74.5f * d, width - 6.5f * d, 122.5f * d),
                14f * d,
                14f * d,
                paint
            )

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 9.5f * d
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(message.take(34), width / 2f, 103f * d, paint)

            paint.textSize = 7.2f * d
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.color = Color.argb(175, 220, 220, 230)
            canvas.drawText("ACCESSIBILITY • VERIFIED FLOW", width / 2f, 117f * d, paint)
        }
    }
}
