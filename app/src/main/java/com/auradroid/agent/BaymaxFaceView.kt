package com.auradroid.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Friendly rounded robot companion with configurable facial expressions. */
class BaymaxFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        val EXPRESSIONS = listOf(
            "Normal", "Happy", "Very Happy", "Thinking", "Focused", "Surprised",
            "Sad", "Worried", "Confused", "Wink", "Sleepy", "Sleeping",
            "Listening", "Working", "Error", "Success", "Curious", "Proud"
        )
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7FAFC.toInt(); style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFD8E3.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF17212B.toInt(); style = Paint.Style.FILL }
    private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF17212B.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    var expression: String = "Normal"
        private set

    fun setExpression(value: String) {
        expression = if (EXPRESSIONS.contains(value)) value else "Normal"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val headW = w * 0.55f
        val headH = h * 0.43f
        val head = RectF(centerX - headW / 2f, h * 0.08f, centerX + headW / 2f, h * 0.08f + headH)
        canvas.drawRoundRect(head, headH * 0.45f, headH * 0.45f, fill)
        canvas.drawRoundRect(head, headH * 0.45f, headH * 0.45f, outline)

        val eyeY = head.top + headH * 0.48f
        val eyeGap = headW * 0.22f
        val eyeRadius = headH * if (expression == "Sleeping") 0.012f else 0.035f
        if (expression == "Wink") {
            canvas.drawCircle(centerX - eyeGap, eyeY, eyeRadius, eye)
            canvas.drawLine(centerX + eyeGap - eyeRadius * 2, eyeY, centerX + eyeGap + eyeRadius * 2, eyeY, mouth)
        } else if (expression == "Sleeping") {
            canvas.drawLine(centerX - eyeGap - eyeRadius * 2, eyeY, centerX - eyeGap + eyeRadius * 2, eyeY, mouth)
            canvas.drawLine(centerX + eyeGap - eyeRadius * 2, eyeY, centerX + eyeGap + eyeRadius * 2, eyeY, mouth)
        } else {
            canvas.drawCircle(centerX - eyeGap, eyeY, eyeRadius, eye)
            canvas.drawCircle(centerX + eyeGap, eyeY, eyeRadius, eye)
        }

        val bodyTop = head.bottom - headH * 0.02f
        val body = RectF(w * 0.29f, bodyTop, w * 0.71f, h * 0.93f)
        canvas.drawRoundRect(body, w * 0.14f, w * 0.14f, fill)
        canvas.drawRoundRect(body, w * 0.14f, w * 0.14f, outline)

        val mouthY = eyeY + headH * 0.22f
        val half = headW * 0.10f
        when (expression) {
            "Happy", "Very Happy", "Success", "Proud" -> {
                val p = Path().apply {
                    moveTo(centerX - half, mouthY)
                    quadTo(centerX, mouthY + headH * if (expression == "Very Happy") 0.16f else 0.10f, centerX + half, mouthY)
                }
                canvas.drawPath(p, mouth)
            }
            "Sad", "Worried", "Error" -> {
                val p = Path().apply {
                    moveTo(centerX - half, mouthY + headH * 0.07f)
                    quadTo(centerX, mouthY - headH * 0.02f, centerX + half, mouthY + headH * 0.07f)
                }
                canvas.drawPath(p, mouth)
            }
            "Surprised", "Curious" -> canvas.drawCircle(centerX, mouthY + headH * 0.03f, headH * 0.045f, eye)
            "Confused", "Thinking" -> canvas.drawLine(centerX - half, mouthY, centerX + half * 0.6f, mouthY + headH * 0.025f, mouth)
            "Sleepy" -> canvas.drawLine(centerX - half, mouthY, centerX + half, mouthY, mouth)
            else -> canvas.drawLine(centerX - half, mouthY, centerX + half, mouthY, mouth)
        }
    }
}
