package com.auradroid.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated full-body companion for the Home page.
 * This is intentionally separate from the small face-only floating launcher.
 */
class FutureCompanionHomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val frame = object : Runnable {
        override fun run() {
            phase += 0.035f
            invalidate()
            postDelayed(this, 32L)
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        post(frame)
        contentDescription = "Animated futuristic AI companion robot"
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val scale = minOf(w / 340f, h / 390f)
        canvas.save()
        canvas.translate((w - 340f * scale) / 2f, (h - 390f * scale) / 2f)
        canvas.scale(scale, scale)

        val cyan = Color.rgb(34, 211, 238)
        val ice = Color.rgb(165, 243, 252)
        val shell = Color.rgb(239, 250, 255)
        val shadow = Color.rgb(7, 24, 39)
        val bob = sin(phase) * 4.2f
        val centerX = 170f

        // Deep aqua halo and soft floating particles.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(centerX, 174f + bob, 150f,
            intArrayOf(Color.argb(90, 34, 211, 238), Color.argb(30, 34, 211, 238), Color.TRANSPARENT),
            floatArrayOf(0f, 0.64f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(centerX, 174f + bob, 150f, paint)
        paint.shader = null

        for (i in 0..8) {
            val a = phase * 0.55f + i * 0.698f
            val px = centerX + cos(a) * (112f + (i % 3) * 11f)
            val py = 165f + bob + sin(a * 1.2f) * (102f + (i % 2) * 14f)
            paint.color = Color.argb(100 + (i % 3) * 35, 103, 232, 249)
            canvas.drawCircle(px, py, 1.8f + (i % 3) * 0.7f, paint)
        }

        // Transparent crystal-water platform, with three gently breathing rings.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(centerX, 327f, 100f,
            intArrayOf(Color.argb(65, 34, 211, 238), Color.argb(18, 34, 211, 238), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(45f, 294f, 295f, 354f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        for (i in 0..2) {
            val pulse = 1f + sin(phase * 1.3f + i) * 0.035f
            paint.strokeWidth = if (i == 0) 2.1f else 1.1f
            paint.color = Color.argb(145 - i * 35, 103, 232, 249)
            canvas.drawOval(RectF(
                centerX - (118f + i * 12f) * pulse,
                322f - (19f + i * 2f) * pulse,
                centerX + (118f + i * 12f) * pulse,
                322f + (19f + i * 2f) * pulse
            ), paint)
        }

        // Legs and cyan-lit feet.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawOval(RectF(106f, 291f, 234f, 335f), paint)
        paint.shader = RadialGradient(170f, 316f, 95f,
            intArrayOf(Color.argb(65, 34, 211, 238), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(62f, 297f, 278f, 342f), paint)
        paint.shader = null

        drawCeramicLimb(canvas, RectF(116f, 260f + bob, 158f, 321f + bob), 20f)
        drawCeramicLimb(canvas, RectF(182f, 260f + bob, 224f, 321f + bob), 20f)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(137f, 313f + bob, 32f,
            intArrayOf(Color.WHITE, Color.rgb(199, 239, 248), Color.rgb(86, 190, 212)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(101f, 300f + bob, 160f, 326f + bob), 13f, 13f, paint)
        canvas.drawRoundRect(RectF(180f, 300f + bob, 239f, 326f + bob), 13f, 13f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = cyan
        canvas.drawRoundRect(RectF(101f, 300f + bob, 160f, 326f + bob), 13f, 13f, paint)
        canvas.drawRoundRect(RectF(180f, 300f + bob, 239f, 326f + bob), 13f, 13f, paint)

        // Rounded torso with glossy ceramic gradient.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(75, 0, 0, 0)
        canvas.drawRoundRect(RectF(100f, 169f + bob, 240f, 286f + bob), 48f, 48f, paint)
        paint.shader = LinearGradient(95f, 160f + bob, 244f, 290f + bob,
            intArrayOf(Color.WHITE, shell, Color.rgb(169, 220, 235)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(98f, 164f + bob, 242f, 282f + bob), 48f, 48f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        paint.color = Color.rgb(177, 235, 247)
        canvas.drawRoundRect(RectF(98f, 164f + bob, 242f, 282f + bob), 48f, 48f, paint)

        // Short rounded arms and visible hands, gently swinging from shoulders.
        val swing = sin(phase * 0.8f) * 4f
        drawCeramicLimb(canvas, RectF(67f, 187f + bob + swing, 111f, 239f + bob + swing), 22f)
        drawCeramicLimb(canvas, RectF(229f, 187f + bob - swing, 273f, 239f + bob - swing), 22f)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(221, 247, 253)
        canvas.drawCircle(83f, 232f + bob + swing, 17f, paint)
        canvas.drawCircle(257f, 232f + bob - swing, 17f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = cyan
        canvas.drawCircle(83f, 232f + bob + swing, 17f, paint)
        canvas.drawCircle(257f, 232f + bob - swing, 17f, paint)

        // Shoulder rings, blue glass joints.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(18, 64, 85)
        canvas.drawCircle(104f, 194f + bob + swing, 18f, paint)
        canvas.drawCircle(236f, 194f + bob - swing, 18f, paint)
        paint.color = cyan
        canvas.drawCircle(104f, 194f + bob + swing, 7f, paint)
        canvas.drawCircle(236f, 194f + bob - swing, 7f, paint)

        // Head: rounded transparent helmet with side audio/assistant modules.
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawRoundRect(RectF(83f, 47f + bob, 257f, 185f + bob), 58f, 58f, paint)
        paint.shader = LinearGradient(82f, 44f + bob, 260f, 181f + bob,
            intArrayOf(Color.WHITE, Color.rgb(242, 252, 255), Color.rgb(164, 218, 232)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(80f, 43f + bob, 260f, 180f + bob), 58f, 58f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = ice
        canvas.drawRoundRect(RectF(80f, 43f + bob, 260f, 180f + bob), 58f, 58f, paint)

        // Circular illuminated side modules, distinct from the face-only launcher icon.
        for (x in listOf(84f, 256f)) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(17, 75, 98)
            canvas.drawRoundRect(RectF(x - 13f, 91f + bob, x + 13f, 139f + bob), 12f, 12f, paint)
            paint.color = cyan
            canvas.drawCircle(x, 115f + bob, 8f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.WHITE
            canvas.drawCircle(x, 115f + bob, 5f, paint)
        }

        // Dark glass face plate, bright friendly eyes and a small smile.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(102f, 78f + bob, 238f, 158f + bob,
            intArrayOf(Color.rgb(4, 17, 31), Color.rgb(10, 37, 56)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(101f, 75f + bob, 239f, 151f + bob), 31f, 31f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.rgb(22, 110, 139)
        canvas.drawRoundRect(RectF(101f, 75f + bob, 239f, 151f + bob), 31f, 31f, paint)

        val blink = sin(phase * 0.47f) > 0.985f
        for (eyeX in listOf(139f, 201f)) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(65, 34, 211, 238)
            canvas.drawCircle(eyeX, 111f + bob, 21f, paint)
            paint.color = cyan
            canvas.drawCircle(eyeX, 111f + bob, 14f, paint)
            paint.shader = RadialGradient(eyeX - 4f, 106f + bob, 14f,
                intArrayOf(Color.WHITE, Color.rgb(103, 232, 249), cyan), null, Shader.TileMode.CLAMP)
            if (blink) {
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawLine(eyeX - 10f, 111f + bob, eyeX + 10f, 111f + bob, paint)
            } else {
                canvas.drawCircle(eyeX, 111f + bob, 13f, paint)
            }
            paint.shader = null
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 3f
        paint.color = ice
        canvas.drawArc(RectF(161f, 122f + bob, 179f, 139f + bob), 20f, 140f, false, paint)

        // Chest reactor: softly pulsing aqua core.
        val corePulse = 9f + sin(phase * 1.6f) * 1.4f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(centerX, 222f + bob, 24f,
            intArrayOf(Color.WHITE, cyan, Color.argb(10, 34, 211, 238)),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(centerX, 222f + bob, corePulse + 5f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawCircle(centerX, 222f + bob, corePulse, paint)
        paint.style = Paint.Style.FILL
        paint.color = cyan
        canvas.drawCircle(centerX, 222f + bob, 4f, paint)

        // Fine glass highlights.
        paint.strokeWidth = 2f
        paint.color = Color.argb(170, 255, 255, 255)
        canvas.drawArc(RectF(103f, 50f + bob, 237f, 156f + bob), 205f, 75f, false, paint)
        canvas.restore()
    }

    private fun drawCeramicLimb(canvas: Canvas, rect: RectF, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.WHITE, Color.rgb(236, 250, 255), Color.rgb(155, 212, 229)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.rgb(177, 235, 247)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
