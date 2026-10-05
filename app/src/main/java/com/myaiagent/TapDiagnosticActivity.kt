package com.myaiagent

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.TapDiagnosticStore

class TapDiagnosticActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: MaterialTextView
    private lateinit var logView: MaterialTextView
    private val diagnostic = TapDiagnosticStore(this)

    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun buildUi(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            setBackgroundColor(Color.rgb(8, 9, 13))
        }
        root.addView(text("TAP DIAGNOSTIC", 26f, Color.WHITE))
        root.addView(text("YouTube is not used here. This isolates the Accessibility tap engine.", 14f, Color.rgb(170,175,188)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })
        status = text("Ready", 15f, Color.rgb(151,157,173))
        root.addView(status, lp(-1, -2, 0, 0, 0, 18))

        root.addView(MaterialButton(this).apply {
            text = "TAP TARGET"
            contentDescription = "TAP TARGET"
            isAllCaps = false
            textSize = 18f
            minHeight = dp(70)
            setOnClickListener {
                text = "NODE TAP SUCCESS"
                contentDescription = "NODE TAP SUCCESS"
            }
        }, lp(-1, 70, 0, 0, 0, 16))

        root.addView(MaterialButton(this).apply {
            text = "GESTURE TARGET"
            contentDescription = "GESTURE TARGET"
            isAllCaps = false
            textSize = 18f
            minHeight = dp(70)
            setOnClickListener {
                text = "GESTURE TAP SUCCESS"
                contentDescription = "GESTURE TAP SUCCESS"
            }
        }, lp(-1, 70, 0, 0, 0, 16))

        root.addView(MaterialButton(this).apply {
            text = "▶ START TAP TEST"
            isAllCaps = false
            textSize = 16f
            minHeight = dp(58)
            setOnClickListener {
                if (!isAccessibilityEnabled()) {
                    startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                diagnostic.start()
            }
        }, lp(-1, 58, 0, 0, 0, 12))

        logView = text("", 12f, Color.rgb(220,223,232))
        root.addView(logView, lp(-1, -2, 0, 12, 0, 0))
        return root
    }

    private fun render() {
        if (!::status.isInitialized) return
        status.text = when {
            diagnostic.isActive() -> "RUNNING • step " + diagnostic.step()
            diagnostic.success() -> "PASS • both tap methods verified"
            diagnostic.message().isNotBlank() -> "FAIL • " + diagnostic.message()
            else -> "Ready"
        }
        logView.text = diagnostic.events().joinToString("\n") { "• " + it }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val expected = packageName + "/com.myaiagent.automation.NaxAccessibilityService"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    private fun text(value: String, size: Float, color: Int) =
        MaterialTextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun lp(width: Int, heightDp: Int, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(width, if (heightDp < 0) -2 else dp(heightDp)).apply {
            setMargins(dp(l), dp(t), dp(r), dp(b))
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}