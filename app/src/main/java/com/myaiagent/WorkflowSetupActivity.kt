package com.myaiagent

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.view.View
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.folder.FolderVideoImporter
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore
import com.myaiagent.scheduler.UploadAlarmScheduler
import com.myaiagent.workflow.WorkflowConfig
import com.myaiagent.workflow.WorkflowStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class WorkflowSetupActivity : AppCompatActivity() {
    private lateinit var workflowStore: WorkflowStore
    private lateinit var queueStore: UploadQueueStore
    private lateinit var folderText: MaterialTextView
    private lateinit var frequency: Spinner
    private lateinit var visibilitySpinner: Spinner
    private lateinit var contentTypeSpinner: Spinner
    private lateinit var mode: Spinner
    private val timeButtons = mutableListOf<MaterialButton>()
    private var times = mutableListOf("07:00", "13:00", "19:00")
    private var folderUri = ""

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        folderUri = uri.toString()
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
        folderText.text = "Folder selected\n" + (uri.lastPathSegment ?: "Selected folder")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workflowStore = WorkflowStore(this)
        queueStore = UploadQueueStore(this)
        val config = workflowStore.load()
        folderUri = config.folderUri
        times = config.times.toMutableList().ifEmpty { mutableListOf("07:00") }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(32))
            setBackgroundColor(Color.rgb(8, 9, 13))
        }

        root.addView(label("Workflow", 30f, Color.WHITE, Typeface.BOLD))
        root.addView(label("Set once. Run automatically.", 14f, Color.rgb(145, 150, 164), Typeface.NORMAL).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        val folderCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Color.argb(48, 255, 255, 255))
            strokeWidth = dp(1); strokeColor = Color.argb(72, 255, 255, 255)
        }
        val folderBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        folderBody.addView(label("VIDEO SOURCE", 10f, Color.rgb(151,157,173), Typeface.BOLD))
        folderText = label(if (folderUri.isBlank()) "No folder selected" else "Folder configured", 15f, Color.WHITE, Typeface.BOLD)
        folderText.setPadding(0, dp(6), 0, dp(10))
        folderBody.addView(folderText)
        folderBody.addView(MaterialButton(this).apply {
            text = "Choose video folder"; isAllCaps = false; cornerRadius = dp(14); insetTop = 0; insetBottom = 0
            setOnClickListener { pickFolder.launch(null) }
        })
        folderCard.addView(folderBody); root.addView(folderCard)

        root.addView(section("UPLOAD FREQUENCY"))
        frequency = spinner(arrayOf("1 upload / day", "2 uploads / day", "3 uploads / day"))
        frequency.setSelection((config.dailyLimit - 1).coerceIn(0, 2))
        root.addView(frequency)

        root.addView(section("DAILY TIMES"))
        val timeHint = label("Choose one, two or three times. Each time gets the next video in the folder queue.", 12f, Color.rgb(126,132,148), Typeface.NORMAL)
        timeHint.setPadding(0, dp(5), 0, dp(8)); root.addView(timeHint)
        repeat(3) { index ->
            val button = MaterialButton(this).apply {
                isAllCaps = false; cornerRadius = dp(14); insetTop = 0; insetBottom = 0
                text = if (index < times.size) "Upload ${index + 1}: ${times[index]}" else "Upload ${index + 1}: Not used"
                setOnClickListener { chooseTime(index) }
            }
            timeButtons.add(button); root.addView(button, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
        }

        root.addView(section("YOUTUBE SETTINGS"))
        visibilitySpinner = spinner(arrayOf("PRIVATE", "UNLISTED", "PUBLIC"))
        visibilitySpinner.setSelection(arrayOf("PRIVATE","UNLISTED","PUBLIC").indexOf(config.visibility).coerceAtLeast(0))
        root.addView(visibilitySpinner)

        root.addView(section("CONTENT TYPE"))
        contentTypeSpinner = spinner(arrayOf("VIDEO", "SHORT"))
        contentTypeSpinner.setSelection(if (config.contentType == "SHORT") 1 else 0)
        root.addView(contentTypeSpinner)

        mode = spinner(arrayOf("NATIVE_STUDIO"))
        mode.setSelection(0)
        root.addView(mode, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(section("WORKFLOW"))

        val demoCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.argb(42, 194, 164, 255))
            strokeWidth = dp(1)
            strokeColor = Color.argb(85, 194, 164, 255)
        }
        val demoBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        demoBody.addView(label("AUTO-TAPPER • ACTION → VERIFY", 10f, Color.rgb(204, 180, 255), Typeface.BOLD))
        demoBody.addView(label(
            "1  Open YouTube Studio  →  verify Studio is active\n" +
                "2  Create → Upload      →  verify upload screen\n" +
                "3  Select video         →  verify correct file\n" +
                "4  Fill details         →  verify title/description\n" +
                "5  Set visibility       →  verify selected privacy\n" +
                "6  Publish              →  start progress monitoring\n" +
                "7  Preparing/Sending    →  keep checking, do not finish\n" +
                "8  100% / Published     →  final verification\n" +
                "9  Video visible        →  mark SUCCESS",
            12f,
            Color.WHITE,
            Typeface.NORMAL
        ).apply { setPadding(0, dp(9), 0, dp(5)) })
        demoBody.addView(label(
            "A failed UI match retries. Security/login screens pause for user action. SUCCESS is written only after a real upload verification signal.",
            10f,
            Color.rgb(165, 170, 186),
            Typeface.NORMAL
        ))
        demoCard.addView(demoBody)
        root.addView(demoCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        root.addView(MaterialButton(this).apply {
            text = "▶  Test one video before scheduling"
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(17)
            minHeight = dp(54)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37,39,48))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.argb(95,255,255,255))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val current = workflowStore.load()
                val currentVisibility = arrayOf("PRIVATE","UNLISTED","PUBLIC")[visibilitySpinner.selectedItemPosition.coerceIn(0,2)]
                val currentMode = "NATIVE_STUDIO"
                workflowStore.save(
                    current.copy(
                        folderUri = folderUri,
                        visibility = currentVisibility,
                        automationMode = currentMode,
                        contentType = contentTypeSpinner.selectedItem.toString()
                    )
                )
                startActivity(Intent(this@WorkflowSetupActivity, WorkflowTestActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })

        val start = MaterialButton(this).apply {
            text = if (config.enabled) "✓  Workflow is ON" else "Start workflow"
            textSize = 16f; isAllCaps = false; cornerRadius = dp(18); minHeight = dp(58); insetTop = 0; insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(194,164,255))
            setTextColor(Color.rgb(25,20,35))
            setOnClickListener { startWorkflow() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })

        root.addView(MaterialButton(this).apply {
            text = "Stop workflow"; textSize = 14f; isAllCaps = false; cornerRadius = dp(16); insetTop = 0; insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37,39,48))
            setTextColor(Color.rgb(255,112,112))
            setOnClickListener { stopWorkflow() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        setContentView(NaxBottomNav.wrap(this, root, "WORKFLOW"))
        refreshTimeButtons()
    }

    private fun startWorkflow() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Enable MyAIAgent Accessibility Service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (folderUri.isBlank()) { Toast.makeText(this, "Choose a video folder first", Toast.LENGTH_SHORT).show(); return }
        val count = frequency.selectedItemPosition + 1
        val selectedTimes = times.take(count)
        if (selectedTimes.size < count) { Toast.makeText(this, "Set all selected daily times", Toast.LENGTH_SHORT).show(); return }

        val visibilityValue = arrayOf("PRIVATE","UNLISTED","PUBLIC")[visibilitySpinner.selectedItemPosition.coerceIn(0,2)]
        val modeValue = "NATIVE_STUDIO"
        val contentTypeValue = contentTypeSpinner.selectedItem.toString()

        val imported = FolderVideoImporter.importVideos(this, Uri.parse(folderUri))
            .map { it.copy(visibility = visibilityValue, automationMode = modeValue, contentType = contentTypeValue) }
        queueStore.addAllUnique(imported)

        // Apply the active workflow profile to every pending item so the Auto-Tapper
        // knows whether this run is for a normal video or a Short.
        queueStore.load()
            .filter { it.status == "QUEUED" || it.status == "SCHEDULED" }
            .forEach { item ->
                queueStore.update(
                    item.copy(
                        visibility = visibilityValue,
                        automationMode = modeValue,
                        contentType = contentTypeValue
                    )
                )
            }

        val config = WorkflowConfig(
            enabled = true,
            folderUri = folderUri,
            times = selectedTimes,
            dailyLimit = count,
            visibility = visibilityValue,
            automationMode = modeValue,
            contentType = contentTypeValue
        )
        workflowStore.save(config)
        scheduleQueue(selectedTimes, visibilityValue, modeValue)
        Toast.makeText(this, "Workflow ON • ${count} upload(s) per day", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun scheduleQueue(selectedTimes: List<String>, visibilityValue: String, modeValue: String) {
        val now = Calendar.getInstance()
        val items = queueStore.load().filter { it.status == "QUEUED" || it.status == "SCHEDULED" }.toMutableList()
        var slot = 0
        var dayOffset = 0
        while (slot < items.size && dayOffset < 366) {
            for (time in selectedTimes) {
                if (slot >= items.size) break
                val parts = time.split(":")
                if (parts.size != 2) continue
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now.timeInMillis
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
                    set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= now.timeInMillis) continue
                val old = items[slot]
                val updated = old.copy(
                    scheduledAt = cal.timeInMillis,
                    status = "SCHEDULED",
                    visibility = visibilityValue,
                    automationMode = modeValue
                )
                queueStore.update(updated)
                UploadAlarmScheduler.schedule(this, updated)
                slot++
            }
            dayOffset++
        }
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
    private fun stopWorkflow() {
        workflowStore.setEnabled(false)
        queueStore.load().filter { it.status == "SCHEDULED" }.forEach { item ->
            UploadAlarmScheduler.cancel(this, item.id)
            queueStore.update(item.copy(status = "QUEUED", scheduledAt = null, resultNote = "Workflow stopped"))
        }
        Toast.makeText(this, "Workflow stopped", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun chooseTime(index: Int) {
        val current = times.getOrNull(index)?.split(":")
        val hour = current?.getOrNull(0)?.toIntOrNull() ?: 7
        val minute = current?.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(this, { _, h, m ->
            val value = String.format(Locale.getDefault(), "%02d:%02d", h, m)
            while (times.size <= index) times.add("07:00")
            times[index] = value
            refreshTimeButtons()
        }, hour, minute, true).show()
    }

    private fun refreshTimeButtons() {
        timeButtons.forEachIndexed { index, button ->
            button.text = if (index < times.size) "Upload ${index + 1}: ${times[index]}" else "Upload ${index + 1}: Not used"
        }
    }

    private fun spinner(values: Array<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@WorkflowSetupActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun section(value: String): MaterialTextView = label(value, 10f, Color.rgb(132,137,151), Typeface.BOLD).apply {
        setPadding(0, dp(20), 0, dp(7))
    }

    private fun label(value: String, size: Float, color: Int, style: Int) = MaterialTextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = Typeface.create(Typeface.DEFAULT, style)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}