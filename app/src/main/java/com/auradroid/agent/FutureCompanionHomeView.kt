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

        val mood = CompanionVisualState.currentMood
        val cyan = Color.rgb(34, 211, 238)
        val ice = Color.rgb(165, 243, 252)
        val shell = Color.rgb(249, 253, 255)
        val accent = when (mood) {
            "ERROR", "SAD", "FAILED" -> Color.rgb(251, 85, 120)
            "THINKING", "PROCESSING", "WORKING" -> Color.rgb(79, 156, 255)
            "DONE", "HAPPY", "SUCCESS", "COMPLETED" -> Color.rgb(52, 211, 153)
            "WAITING", "NEEDS_PERMISSION", "APPROVAL_REQUIRED" -> Color.rgb(251, 191, 36)
            "STOP", "STOPPED", "CANCELLED", "PAUSED" -> Color.rgb(183, 166, 255)
            else -> cyan
        }
        val stopped = mood in listOf("STOP", "STOPPED", "CANCELLED", "PAUSED")
        val sad = mood in listOf("ERROR", "SAD", "FAILED")
        val thinking = mood in listOf("THINKING", "PROCESSING", "WORKING")
        val happy = mood in listOf("DONE", "HAPPY", "SUCCESS", "COMPLETED")
        val bob = sin(phase) * 4.2f
        val centerX = 170f

        // Deep aqua halo and soft floating particles.
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
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
        paint.color = Color.WHITE
        paint.shader = RadialGradient(centerX, 327f, 100f,
            intArrayOf(Color.argb(65, 34, 211, 238), Color.argb(18, 34, 211, 238), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(45f, 294f, 295f, 354f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        for (i in 0..2) {
            val pulse = 1f + sin(phase * 1.3f + i) * 0.035f
            paint.strokeWidth = if (i == 0) 2.1f else 1.1f
            paint.color = Color.argb(145 - i * 35, Color.red(accent), Color.green(accent), Color.blue(accent))
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
        paint.color = Color.WHITE
        paint.shader = RadialGradient(170f, 316f, 95f,
            intArrayOf(Color.argb(65, 34, 211, 238), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawOval(RectF(62f, 297f, 278f, 342f), paint)
        paint.shader = null

        drawCeramicLimb(canvas, RectF(116f, 260f + bob, 158f, 321f + bob), 20f)
        drawCeramicLimb(canvas, RectF(182f, 260f + bob, 224f, 321f + bob), 20f)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
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
        paint.color = Color.WHITE
        paint.shader = LinearGradient(95f, 160f + bob, 244f, 290f + bob,
            intArrayOf(Color.WHITE, shell, Color.rgb(169, 220, 235)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(98f, 164f + bob, 242f, 282f + bob), 48f, 48f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        paint.color = Color.rgb(177, 235, 247)
        canvas.drawRoundRect(RectF(98f, 164f + bob, 242f, 282f + bob), 48f, 48f, paint)

        // Specular highlight and bevel line give the ceramic shell more convincing depth.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(104f, 171f + bob, 153f, 277f + bob,
            intArrayOf(Color.argb(180, 255, 255, 255), Color.argb(35, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(104f, 171f + bob, 151f, 267f + bob), 24f, 24f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawArc(RectF(109f, 172f + bob, 231f, 270f + bob), 205f, 90f, false, paint)

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
        paint.color = accent
        canvas.drawCircle(104f, 194f + bob + swing, 7f, paint)
        canvas.drawCircle(236f, 194f + bob - swing, 7f, paint)

        // Head: rounded transparent helmet with side audio/assistant modules.
        paint.color = Color.argb(75, 0, 0, 0)
        canvas.drawRoundRect(RectF(83f, 47f + bob, 257f, 185f + bob), 58f, 58f, paint)
        paint.color = Color.WHITE
        paint.shader = LinearGradient(80f, 43f + bob, 260f, 180f + bob,
            intArrayOf(Color.WHITE, Color.rgb(249, 254, 255), Color.rgb(154, 215, 232)),
            floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(80f, 43f + bob, 260f, 180f + bob), 58f, 58f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = ice
        canvas.drawRoundRect(RectF(80f, 43f + bob, 260f, 180f + bob), 58f, 58f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawOval(RectF(111f, 47f + bob, 222f, 61f + bob), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = Color.argb(190, 255, 255, 255)
        canvas.drawArc(RectF(97f, 49f + bob, 242f, 166f + bob), 205f, 72f, false, paint)

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
        paint.color = Color.WHITE
        paint.shader = LinearGradient(94f, 72f + bob, 246f, 159f + bob,
            intArrayOf(Color.rgb(2, 9, 21), Color.rgb(5, 27, 47), Color.rgb(11, 53, 78)),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(94f, 71f + bob, 246f, 156f + bob), 34f, 34f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = accent
        canvas.drawRoundRect(RectF(94f, 71f + bob, 246f, 156f + bob), 34f, 34f, paint)

        // Oversized LED eyes with a bright rim, dark lens depth and glass catchlights.
        val blink = !sad && !stopped && sin(phase * 0.47f) > 0.985f
        for (eyeX in listOf(132f, 208f)) {
            val eyeY = 111f + bob
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(eyeX, eyeY, 25f, paint)
            paint.color = accent
            canvas.drawCircle(eyeX, eyeY, 19f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f
            paint.color = ice
            canvas.drawCircle(eyeX, eyeY, 16.8f, paint)

            if (stopped) {
                paint.strokeWidth = 3.8f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = ice
                canvas.drawLine(eyeX - 10f, eyeY, eyeX + 10f, eyeY, paint)
            } else if (blink) {
                paint.strokeWidth = 3.5f
                paint.color = Color.WHITE
                canvas.drawLine(eyeX - 11f, eyeY, eyeX + 11f, eyeY, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(1, 18, 33)
                canvas.drawCircle(eyeX, eyeY, 13.8f, paint)
                paint.color = accent
                paint.shader = RadialGradient(eyeX - 4.5f, eyeY - 5f, 15f,
                    intArrayOf(Color.WHITE, ice, accent), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(eyeX, eyeY, 12f, paint)
                paint.shader = null
                paint.color = Color.WHITE
                canvas.drawCircle(eyeX - 4f, eyeY - 5f, 3f, paint)
                paint.color = Color.argb(150, 255, 255, 255)
                canvas.drawCircle(eyeX + 5f, eyeY + 5f, 1.6f, paint)
            }
        }

        // Expression mouth reacts to task status, with visibly different shapes.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 4f
        paint.color = accent
        val mouth = RectF(157f, 124f + bob, 183f, 145f + bob)
        when {
            sad -> canvas.drawArc(mouth, 205f, 130f, false, paint)
            stopped -> canvas.drawLine(161f, 137f + bob, 179f, 137f + bob, paint)
            thinking -> {
                val dot = 3f + (sin(phase * 2.3f) + 1f) * 1.3f
                canvas.drawCircle(170f, 136f + bob, dot, paint)
            }
            mood in listOf("WAITING", "NEEDS_PERMISSION", "APPROVAL_REQUIRED") -> canvas.drawCircle(170f, 136f + bob, 4f, paint)
            happy -> canvas.drawArc(mouth, 10f, 160f, false, paint)
            else -> canvas.drawArc(mouth, 10f, 160f, false, paint)
        }

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
        paint.color = Color.WHITE
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


/** Shared live mood lets the Home hero and floating face react to the same task status. */
object CompanionVisualState {
    @Volatile
    var currentMood: String = "IDLE"
        private set

    fun update(value: String) {
        currentMood = value.uppercase()
    }
}
