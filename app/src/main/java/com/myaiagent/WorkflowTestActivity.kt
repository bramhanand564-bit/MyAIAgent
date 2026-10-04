package com.myaiagent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.AutomationSessionStore
import com.myaiagent.automation.TestRunSnapshot
import com.myaiagent.automation.TestRunStore
import com.myaiagent.automation.UploadRunnerService
import com.myaiagent.model.UploadItem
import com.myaiagent.workflow.WorkflowStore
import java.util.UUID

class WorkflowTestActivity : AppCompatActivity() {
    private lateinit var queueStore: com.myaiagent.queue.UploadQueueStore
    private lateinit var selectedText: MaterialTextView
    private lateinit var statusText: MaterialTextView
    private lateinit var eventContainer: LinearLayout
    private lateinit var runButton: MaterialButton
    private lateinit var homeButton: MaterialButton
    private val testStore by lazy { TestRunStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var selectedItem: UploadItem? = null
    private var completionHomePosted = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}

        selectedItem = UploadItem(
            id = UUID.randomUUID().toString(),
            uri = uri.toString(),
            fileName = resolveName(uri)
        )
        selectedText.text = selectedItem!!.fileName
        statusText.text = "Ready to test • No schedule will run."
        runButton.isEnabled = true
    }

    private val poll = object : Runnable {
        override fun run() {
            renderSnapshot(testStore.snapshot())
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queueStore = com.myaiagent.queue.UploadQueueStore(this)
        setContentView(buildUi())
        renderSnapshot(testStore.snapshot())
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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 13))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(34))
        }

        root.addView(label("Workflow Test", 30f, Color.WHITE, Typeface.BOLD))
        root.addView(label(
            "One video • one complete end-to-end run",
            14f,
            Color.rgb(145, 150, 164),
            Typeface.NORMAL
        ).apply { setPadding(0, dp(6), 0, dp(18)) })

        val sourceCard = card()
        val sourceBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        sourceBody.addView(label("TEST VIDEO", 10f, Color.rgb(151,157,173), Typeface.BOLD))
        selectedText = label("No video selected", 16f, Color.WHITE, Typeface.BOLD).apply {
            setPadding(0, dp(7), 0, dp(10))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        sourceBody.addView(selectedText)
        sourceBody.addView(MaterialButton(this).apply {
            text = "Choose one video"
            isAllCaps = false
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        })
        sourceCard.addView(sourceBody)
        root.addView(sourceCard)

        val infoCard = card()
        val infoBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        infoBody.addView(label("WHAT THIS TEST DOES", 10f, Color.rgb(151,157,173), Typeface.BOLD))
        infoBody.addView(label(
            "Select → open YouTube → Create → Upload → choose file → fill details → set visibility → publish → verify → return Home.",
            13f, Color.rgb(179,184,198), Typeface.NORMAL
        ).apply { setPadding(0, dp(7), 0, 0) })
        infoCard.addView(infoBody)
        root.addView(infoCard, lp(-1, -2, 0, 10, 0, 0))

        statusText = label(
            "Select a video to begin.",
            12f,
            Color.rgb(151,157,173),
            Typeface.NORMAL
        )
        root.addView(statusText, lp(-1, -2, 0, 12, 0, 8))

        runButton = MaterialButton(this).apply {
            text = "▶  Run test now"
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = dp(18)
            minHeight = dp(58)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(194,164,255))
            setTextColor(Color.rgb(25,20,35))
            isEnabled = false
            setOnClickListener { startTest() }
        }
        root.addView(runButton, lp(-1, 58, 0, 0, 0, 10))

        homeButton = MaterialButton(this).apply {
            text = "Back to Home"
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37,39,48))
            setTextColor(Color.rgb(224,226,234))
            setOnClickListener { goHome() }
        }
        root.addView(homeButton, lp(-1, 50, 0, 0, 0, 12))

        root.addView(label("LIVE PROCESS", 10f, Color.rgb(132,137,151), Typeface.BOLD).apply {
            setPadding(0, dp(14), 0, dp(8))
        })

        eventContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(eventContainer)

        root.addView(label(
            "This is a real test of the same automation engine. It never uses the daily schedule.",
            11f, Color.rgb(105,111,126), Typeface.NORMAL
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun startTest() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Enable MyAIAgent Accessibility Service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val item = selectedItem ?: run {
            Toast.makeText(this, "Choose one video first", Toast.LENGTH_SHORT).show()
            return
        }

        if (AutomationSessionStore(this).isActive()) {
            Toast.makeText(this, "Another automation run is active. Finish it first.", Toast.LENGTH_LONG).show()
            return
        }

        val config = WorkflowStore(this).load()
        val testItem = item.copy(
            status = "QUEUED",
            scheduledAt = null,
            visibility = config.visibility,
            automationMode = config.automationMode,
            title = item.fileName.substringBeforeLast('.')
        )
        queueStore.add(testItem)
        selectedItem = testItem

        testStore.start(testItem)
        runButton.isEnabled = false
        statusText.text = "Test is running • watch every step below."

        val intent = Intent(this, UploadRunnerService::class.java).apply {
            putExtra(UploadRunnerService.EXTRA_ITEM_ID, testItem.id)
            putExtra(UploadRunnerService.EXTRA_TEST_MODE, true)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun renderSnapshot(snapshot: TestRunSnapshot) {
        if (!::eventContainer.isInitialized) return

        if (snapshot.fileName.isNotBlank()) {
            selectedText.text = snapshot.fileName
        }

        statusText.text = when (snapshot.result) {
            "SUCCESS" -> "✓ Test complete • Upload submitted"
            "ERROR" -> "✕ Test complete • Automation stopped"
            "WAITING_USER" -> "⚠ Waiting for user action"
            "RUNNING" -> "● Running • ${snapshot.state.replace('_', ' ')}"
            else -> "Select a video to begin."
        }
        statusText.setTextColor(
            when (snapshot.result) {
                "SUCCESS" -> Color.rgb(125,220,164)
                "ERROR" -> Color.rgb(255,112,112)
                "WAITING_USER" -> Color.rgb(255,181,105)
                else -> Color.rgb(151,157,173)
            }
        )

        eventContainer.removeAllViews()
        snapshot.events.forEach { event ->
            val eventCard = card()
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(11))
            }
            val dot = label("●", 10f, Color.rgb(194,164,255), Typeface.BOLD)
            body.addView(dot, LinearLayout.LayoutParams(dp(18), -2))
            body.addView(label(event.message, 12f, Color.rgb(226,228,236), Typeface.NORMAL),
                LinearLayout.LayoutParams(0, -2, 1f))
            eventCard.addView(body)
            eventContainer.addView(eventCard, lp(-1, -2, 0, 0, 0, 6))
        }

        if (snapshot.result == "SUCCESS" && !completionHomePosted) {
            completionHomePosted = true
            handler.postDelayed({ goHome() }, 2200L)
        }
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.argb(42, 255,255,255))
        strokeWidth = dp(1)
        strokeColor = Color.argb(65,255,255,255)
    }

    private fun label(value: String, size: Float, color: Int, style: Int) =
        MaterialTextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun lp(width: Int, heightDp: Int, left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(width, if (heightDp < 0) -2 else dp(heightDp)).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun resolveName(uri: android.net.Uri): String {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return android.provider.DocumentsContract.getDocumentId(uri).substringAfterLast('/') +
            ".mp4"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = packageName + "/com.myaiagent.automation.NaxAccessibilityService"
        return TextUtils.SimpleStringSplitter(':').let { splitter ->
            splitter.setString(enabled)
            splitter.any { it.equals(expected, ignoreCase = true) }
        }
    }
}
