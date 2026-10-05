package com.myaiagent

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.AutomationLiveStore

class AutomationLiveActivity : AppCompatActivity() {
    private lateinit var titleText: MaterialTextView
    private lateinit var stateText: MaterialTextView
    private lateinit var eventContainer: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); if (!isFinishing) handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun buildUi(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(Color.rgb(8, 9, 13))
        }
        titleText = text("Publishing", 27f, Color.WHITE, Typeface.BOLD)
        root.addView(titleText)
        stateText = text("Starting…", 13f, Color.rgb(125, 220, 164), Typeface.BOLD)
        stateText.setPadding(0, dp(5), 0, dp(16))
        root.addView(stateText)
        scroll = ScrollView(this)
        eventContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(eventContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(MaterialButton(this).apply {
            text = "Done"
            isAllCaps = false
            cornerRadius = dp(16)
            minHeight = dp(52)
            setOnClickListener { finish() }
        })
        return root
    }

    private fun render() {
        val s = AutomationLiveStore(this).snapshot()
        if (s.fileName.isNotBlank()) {
            titleText.text = s.fileName
            titleText.maxLines = 1
            titleText.ellipsize = TextUtils.TruncateAt.END
        }
        stateText.text = when {
            s.result == "SUCCESS" -> "✓ Upload completed"
            s.result == "ERROR" -> "✕ Upload stopped"
            s.state == "WAITING_USER" -> "⚠ Action needed from you"
            else -> stateLabel(s.state)
        }
        eventContainer.removeAllViews()
        s.events.forEach { event ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.rgb(24, 25, 32))
                strokeWidth = dp(1)
                strokeColor = Color.rgb(48, 50, 61)
            }
            card.addView(text(event, 12f, Color.rgb(205, 208, 218), Typeface.NORMAL).apply {
                setPadding(dp(13), dp(11), dp(13), dp(11))
            })
            eventContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun stateLabel(v: String) = when (v) {
        "WAITING_FOR_APP" -> "Opening YouTube Studio…"
        "FIND_CREATE" -> "Finding Create…"
        "FIND_UPLOAD" -> "Opening Upload…"
        "WAITING_FOR_PICKER" -> "Selecting video…"
        "FILL_DETAILS" -> "Filling details…"
        "SET_VISIBILITY" -> "Setting visibility…"
        "PUBLISH" -> "Publishing…"
        "MONITOR_UPLOAD" -> "Monitoring upload progress…"
        "VERIFY" -> "Final verification…"
        "WAITING_USER" -> "Waiting for your action…"
        else -> "Working…"
    }

    private fun text(v: String, size: Float, color: Int, style: Int) =
        MaterialTextView(this).apply {
            text = v
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
