package com.myaiagent

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.RealTapTestStore

/**
 * A deliberately simple end-to-end interaction test:
 * open Chrome, tap the search field, open the real keyboard, tap every key
 * of "I love you" one-by-one, tap Search/Enter, and verify the results screen.
 */
class RealTapTestActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val test = RealTapTestStore(this)

    private lateinit var status: MaterialTextView
    private lateinit var logView: MaterialTextView
    private lateinit var startButton: MaterialButton

    private val poll = object : Runnable {
        override fun run() {
            render()
            if (::status.isInitialized) {
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        render()
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

    override fun onBackPressed() {
        if (test.isActive()) {
            test.finish(false, "Stopped by Back")
            render()
            return
        }
        super.onBackPressed()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            setBackgroundColor(Color.rgb(8, 9, 13))
        }

        root.addView(text("REAL TAP + TYPE TEST", 25f, Color.WHITE).apply {
            gravity = Gravity.CENTER
        })

        root.addView(text(
            "Isolated test. NAX will use real Accessibility taps only — no copy/paste and no text injection.",
            14f,
            Color.rgb(170, 175, 188)
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(20))
        })

        root.addView(text(
            "Flow: Chrome → Search → keyboard → I love you (key-by-key) → Search → verify.",
            13f,
            Color.rgb(145, 150, 164)
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })

        status = text("Ready", 15f, Color.rgb(151, 157, 173))
        root.addView(status, lp(-1, -2, 0, 0, 0, 14))

        startButton = MaterialButton(this).apply {
            text = "▶ START REAL TAP TEST"
            contentDescription = "START REAL TAP TEST"
            isAllCaps = false
            textSize = 16f
            minHeight = dp(58)
            setOnClickListener { startTest() }
        }
        root.addView(startButton, lp(-1, 58, 0, 0, 0, 12))

        val stopButton = MaterialButton(this).apply {
            text = "■ STOP / RESET"
            isAllCaps = false
            textSize = 14f
            minHeight = dp(50)
            setOnClickListener {
                test.finish(false, "Stopped by user")
                render()
            }
        }
        root.addView(stopButton, lp(-1, 50, 0, 0, 0, 18))

        logView = text("", 12f, Color.rgb(220, 223, 232))
        root.addView(logView, lp(-1, -2, 0, 6, 0, 0))

        return root
    }

    private fun startTest() {
        if (!isAccessibilityEnabled()) {
            status.text = "Accessibility service is OFF • enable it first"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val candidates = listOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox"
        )
        val target = candidates.firstNotNullOfOrNull { pkg ->
            runCatching {
                packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    // Keep the test controller in the same task. Chrome/Google is
                    // foreground during the test; no NEW_TASK handoff is needed.
                }
            }.getOrNull()?.let { pkg to it }
        }

        if (target == null) {
            test.finish(false, "Neither Chrome nor Google app is installed")
            render()
            return
        }

        val (packageName, launchIntent) = target
        test.start(packageName)

        runCatching {
            startActivity(launchIntent)
        }.onFailure {
            test.finish(false, "Could not open $packageName • ${it.message ?: it.javaClass.simpleName}")
        }

        render()
    }

    private fun render() {
        if (!::status.isInitialized) return

        val message = test.message()
        status.text = when {
            test.isActive() -> "RUNNING • ${stepLabel(test.step())}"
            test.success() -> "PASS • real tap + keyboard test verified"
            message.isNotBlank() -> "STOPPED • $message"
            else -> "Ready"
        }

        startButton.isEnabled = !test.isActive()
        logView.text = test.events().joinToString("\n") { "• $it" }
    }

    private fun stepLabel(step: Int): String = when (step) {
        RealTapTestStore.STEP_WAIT_APP -> "waiting for Chrome"
        RealTapTestStore.STEP_FIND_SEARCH -> "find search field"
        RealTapTestStore.STEP_WAIT_KEYBOARD -> "wait for keyboard"
        RealTapTestStore.STEP_TYPE -> "typing key-by-key"
        RealTapTestStore.STEP_SUBMIT -> "tap Search/Enter"
        RealTapTestStore.STEP_VERIFY -> "verify search"
        RealTapTestStore.STEP_PASS -> "complete"
        RealTapTestStore.STEP_FAIL -> "failed"
        else -> "step $step"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

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
