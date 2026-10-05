package com.myaiagent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.MindEngine
import com.myaiagent.automation.MindStore

class AiMindActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (::root.isInitialized) refresh()
            handler.postDelayed(this, 800L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8, 9, 13)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(30))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("NAX MIND", 28f, Color.WHITE, Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(icon("⌂", "Home") {
            startActivity(Intent(this@AiMindActivity, MainActivity::class.java))
        })
        header.addView(icon("⚙", "Settings") {
            startActivity(Intent(this@AiMindActivity, AppSettingsActivity::class.java))
        })
        root.addView(header)
        root.addView(text(
            "Live control room • track → detect → diagnose → recover → verify",
            11f, Color.rgb(145,150,164), Typeface.NORMAL
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        scroll.addView(root)
        return scroll
    }

    private fun refresh() {
        val s = MindStore(this).snapshot()
        while (root.childCount > 2) root.removeViewAt(2)

        val accent = when (s.severity) {
            "ERROR" -> Color.rgb(255,112,112)
            "WARNING" -> Color.rgb(255,181,105)
            "SUCCESS" -> Color.rgb(125,220,164)
            else -> Color.rgb(194,164,255)
        }
        root.addView(card(
            if (s.active) "LIVE FLOW" else "FLOW IDLE",
            s.fileName.ifBlank { "No active automation" },
            s.state + " • retries " + s.retryCount,
            accent
        ))
        root.addView(card(
            "SCREEN TRACKER",
            s.packageName.ifBlank { "No target surface yet" },
            s.lastObservation.ifBlank { "Waiting for UI evidence..." },
            Color.rgb(113,196,255)
        ))
        root.addView(card("DIAGNOSIS", s.diagnosis, "FIX • " + s.fix, accent))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Fix now", true) {
            val msg = MindEngine.safeRecover(this@AiMindActivity)
            android.widget.Toast.makeText(this@AiMindActivity, msg, android.widget.Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("Chat", false) {
            startActivity(Intent(this@AiMindActivity, AiChatActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        })

        root.addView(text("FLOW TIMELINE", 10f, Color.rgb(132,137,151), Typeface.BOLD).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        val timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.events.takeLast(18).reversed().forEach {
            timeline.addView(text("• " + it, 10f, Color.rgb(169,175,191), Typeface.NORMAL).apply {
                setPadding(dp(4), dp(4), 0, dp(4))
            })
        }
        root.addView(timeline)

        val agentObservation = AgentObservationStore(this).last()
        val agentMemory = AgentVerifiedMemoryStore(this).learnedCount()
        val interval = VisionAgentSettings(this).observationIntervalSeconds
        root.addView(card(
            "AGENT CORE",
            "Observe every " + interval + "s • learned " + agentMemory + " verified steps",
            "Last screen: " + agentObservation.aiScreen.ifBlank { "not analyzed yet" } +
                " • action: " + agentObservation.aiAction.ifBlank { "WAIT" } +
                " • confidence: " + String.format(java.util.Locale.US, "%.2f", agentObservation.aiConfidence) +
                " • " + agentObservation.aiReason.ifBlank { "Waiting for the next meaningful screen change." },
            Color.rgb(194,164,255)
        ))

        root.addView(card(
            "RELIABILITY CORE",
            "No fake success",
            "Real state evidence • real upload monitoring • final verification required • security screens stay user-controlled",
            Color.rgb(125,220,164)
        ))
    }

    private fun card(title: String, headline: String, detail: String, accent: Int): View {
        val c = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.argb(55,255,255,255))
            strokeWidth = dp(1)
            strokeColor = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15),dp(14),dp(15),dp(14))
        }
        b.addView(text(title,10f,accent,Typeface.BOLD))
        b.addView(text(headline,15f,Color.WHITE,Typeface.BOLD).apply { setPadding(0,dp(5),0,dp(4)) })
        b.addView(text(detail,11f,Color.rgb(169,175,191),Typeface.NORMAL))
        c.addView(b)
        c.layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) }
        return c
    }

    private fun button(v:String,filled:Boolean,onClick:()->Unit)=MaterialButton(this).apply{
        text=v;isAllCaps=false;cornerRadius=dp(15);minHeight=dp(48);insetTop=0;insetBottom=0;setOnClickListener{onClick()}
        if(filled){backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(194,164,255));setTextColor(Color.rgb(25,20,35))}
        else{backgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(48,255,255,255));strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(Color.argb(90,255,255,255));setTextColor(Color.WHITE)}
    }
    private fun icon(glyph:String,desc:String,onClick:()->Unit)=MaterialButton(this).apply{
        text=glyph;contentDescription=desc;isAllCaps=false;minWidth=dp(46);minHeight=dp(42);cornerRadius=dp(13);insetTop=0;insetBottom=0
        backgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(44,255,255,255));setOnClickListener{onClick()}
    }
    private fun text(v:String,s:Float,c:Int,st:Int)=MaterialTextView(this).apply{text=v;textSize=s;setTextColor(c);typeface=Typeface.create(Typeface.DEFAULT,st)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
