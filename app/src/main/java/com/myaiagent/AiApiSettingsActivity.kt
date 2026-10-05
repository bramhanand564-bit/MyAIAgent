package com.myaiagent

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.myaiagent.automation.VisionAgentSettings

class AiApiSettingsActivity : AppCompatActivity() {
    private lateinit var enabled: CheckBox
    private lateinit var provider: Spinner
    private lateinit var endpoint: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var headers: EditText
    private lateinit var observationInterval: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(NaxBottomNav.wrap(this, buildUi(), "SETTINGS"))
        load()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8,9,13)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("AI SETTINGS", 28f, Color.WHITE, Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(navIcon("⌂", "Home") {
            startActivity(Intent(this@AiApiSettingsActivity, MainActivity::class.java))
        })
        header.addView(navIcon("✦", "NAX Mind") {
            startActivity(Intent(this@AiApiSettingsActivity, AiMindActivity::class.java))
        })
        root.addView(header)
        root.addView(label("Gemini 3.1 Flash • screenshot observer • 15s AI pacing", 13f, Color.rgb(145,150,164), Typeface.NORMAL).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.argb(48,255,255,255))
            strokeWidth = dp(1)
            strokeColor = Color.argb(72,255,255,255)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(16))
        }

        enabled = CheckBox(this).apply {
            text = "Enable AI vision fallback"
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.rgb(194,164,255))
        }
        body.addView(enabled)

        body.addView(label("PROVIDER",10f,Color.rgb(132,137,151),Typeface.BOLD).apply { setPadding(0,dp(10),0,dp(6)) })
        provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Gemini API","Custom OpenAI-compatible API","Local / Open-source API"))
        body.addView(provider)

        body.addView(label("ENDPOINT",10f,Color.rgb(132,137,151),Typeface.BOLD).apply { setPadding(0,dp(14),0,dp(6)) })
        endpoint = field("https://.../v1/chat/completions")
        body.addView(endpoint)

        body.addView(label("API KEY",10f,Color.rgb(132,137,151),Typeface.BOLD).apply { setPadding(0,dp(12),0,dp(6)) })
        apiKey = field("Optional for local / self-hosted endpoints")
        apiKey.inputType = 0x81
        body.addView(apiKey)

        body.addView(label("MODEL",10f,Color.rgb(132,137,151),Typeface.BOLD).apply { setPadding(0,dp(12),0,dp(6)) })
        model = field("Model name")
        body.addView(model)

        body.addView(label("SCREENSHOT OBSERVATION • SECONDS",10f,Color.rgb(132,137,151),Typeface.BOLD).apply { setPadding(0,dp(12),0,dp(6)) })
        observationInterval = field("6")
        observationInterval.inputType = 2
        body.addView(observationInterval)

        body.addView(label("EXTRA HEADERS • JSON",10f,Color.rgb(132,137,151),Typeface.BOLD).apply { setPadding(0,dp(12),0,dp(6)) })
        headers = field("{\"X-Custom-Header\":\"value\"}")
        body.addView(headers)

        body.addView(label(
            "Local / open-source means the model can be hosted anywhere you control or use. " +
            "Paste its OpenAI-compatible API endpoint here. Examples include an externally hosted " +
            "Ollama/LM Studio/vLLM server exposing /v1/chat/completions.",
            11f, Color.rgb(151,157,173), Typeface.NORMAL
        ).apply { setPadding(0,dp(12),0,dp(0)) })

        card.addView(body)
        root.addView(card)

        val save = MaterialButton(this).apply {
            text = "Save API configuration"
            isAllCaps = false
            textSize = 15f
            cornerRadius = dp(17)
            minHeight = dp(54)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(Color.rgb(194,164,255))
            setTextColor(Color.rgb(25,20,35))
            setOnClickListener { save() }
        }
        root.addView(save, LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin=dp(12) })

        root.addView(label(
            "Security: credentials are kept in the app's private settings. Never paste a provider key into source code or GitHub.",
            10f, Color.rgb(105,111,126), Typeface.NORMAL
        ).apply { gravity=Gravity.CENTER; setPadding(0,dp(14),0,0) })

        scroll.addView(root)
        return scroll
    }

    private fun load() {
        val s=VisionAgentSettings(this)
        enabled.isChecked=s.enabled
        provider.setSelection(when(s.provider){
            VisionAgentSettings.PROVIDER_CUSTOM -> 1
            VisionAgentSettings.PROVIDER_LOCAL -> 2
            else -> 0
        })
        endpoint.setText(s.endpoint)
        apiKey.setText(s.apiKey)
        model.setText(s.model)
        observationInterval.setText(s.observationIntervalSeconds.toString())
        headers.setText(s.extraHeaders)
    }

    private fun save() {
        val s=VisionAgentSettings(this)
        s.enabled=enabled.isChecked
        s.provider=when(provider.selectedItemPosition){
            1 -> VisionAgentSettings.PROVIDER_CUSTOM
            2 -> VisionAgentSettings.PROVIDER_LOCAL
            else -> VisionAgentSettings.PROVIDER_GEMINI
        }
        s.endpoint=endpoint.text.toString()
        s.apiKey=apiKey.text.toString()
        s.model=model.text.toString()
        s.observationIntervalSeconds =
            observationInterval.text.toString().toIntOrNull()?.coerceIn(2, 60) ?: 6
        s.extraHeaders=headers.text.toString()
        Toast.makeText(this,"AI API configuration saved",Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun field(hintText:String)=EditText(this).apply{
        hint=hintText
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(105,111,126))
        textSize=13f
        setSingleLine()
        setPadding(dp(12),0,dp(12),0)
        background=rounded(Color.rgb(24,25,32),dp(12))
        minHeight=dp(48)
    }
    private fun label(t:String,s:Float,c:Int,style:Int)=TextView(this).apply{
        text=t;textSize=s;setTextColor(c);typeface=Typeface.create(Typeface.DEFAULT,style)
    }
    private fun navIcon(glyph:String, desc:String, onClick:()->Unit)=MaterialButton(this).apply{
        text=glyph;contentDescription=desc;isAllCaps=false;textSize=17f
        minWidth=dp(44);minHeight=dp(42);cornerRadius=dp(13);insetTop=0;insetBottom=0
        setPadding(0,0,0,0)
        backgroundTintList=ColorStateList.valueOf(Color.argb(44,255,255,255))
        setOnClickListener{onClick()}
    }
    private fun rounded(c:Int,r:Int)=android.graphics.drawable.GradientDrawable().apply{
        setColor(c);cornerRadius=r.toFloat()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
