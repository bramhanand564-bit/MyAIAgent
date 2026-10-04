package com.myaiagent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.AutomationSessionStore
import com.myaiagent.automation.TestRunStore
import com.myaiagent.folder.FolderVideoImporter
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueCoordinator
import com.myaiagent.queue.UploadQueueStore
import com.myaiagent.scheduler.UploadAlarmScheduler
import com.myaiagent.workflow.WorkflowStore
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var queueStore: UploadQueueStore
    private lateinit var queueContainer: LinearLayout
    private lateinit var queueCountText: MaterialTextView
    private lateinit var serviceStatusText: MaterialTextView
    private lateinit var scheduleStatusText: MaterialTextView
    private lateinit var lastTestContainer: LinearLayout
    private lateinit var liveAutomationContainer: LinearLayout
    private lateinit var kpiContainer: LinearLayout
    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveTicker = object : Runnable {
        override fun run() {
            if (::liveAutomationContainer.isInitialized) refreshLiveAutomation()
            liveHandler.postDelayed(this, 700L)
        }
    }

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            persistReadPermission(uri)
            queueStore.add(
                UploadItem(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    fileName = resolveName(uri)
                )
            )
        }
        refreshQueue()
        autoStartQueueIfPossible()
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistTreePermission(uri)
        queueStore.addAllUnique(FolderVideoImporter.importVideos(this, uri))
        refreshQueue()
        autoStartQueueIfPossible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queueStore = UploadQueueStore(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(8, 9, 13)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        setContentView(buildDashboard())
        refreshQueue()
        rescheduleUploads()
        autoStartQueueIfPossible()
    }

    override fun onResume() {
        super.onResume()
        liveHandler.removeCallbacks(liveTicker)
        liveHandler.post(liveTicker)
        if (::queueStore.isInitialized) {
            refreshQueue()
            rescheduleUploads()
            updateServiceStatus()
            refreshLastTest()
        }
    }

    override fun onPause() {
        super.onPause()
        liveHandler.removeCallbacks(liveTicker)
    }

    private fun buildDashboard(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 13))
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(34))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        brand.addView(label("MyAIAgent", 30f, Color.WHITE, Typeface.BOLD))
        brand.addView(label("Automation", 12f, Color.rgb(142, 147, 160), Typeface.NORMAL).apply {
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(brand)

        val workflowOn = WorkflowStore(this).load().enabled
        val live = MaterialTextView(this).apply {
            text = if (workflowOn) "●  WORKFLOW ON" else "○  SETUP"
            textSize = 10f
            setTextColor(if (workflowOn) Color.rgb(125, 220, 164) else Color.rgb(255, 181, 105))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(if (workflowOn) Color.rgb(25, 43, 34) else Color.rgb(55, 39, 25), dp(20))
        }
        header.addView(live)

        root.addView(header)

        root.addView(label(
            "Your uploads, scheduled and automated.",
            14f,
            Color.rgb(145, 150, 164),
            Typeface.NORMAL
        ).apply {
            setPadding(0, dp(5), 0, dp(16))
        })

        root.addView(buildStatusCard())

        lastTestContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(lastTestContainer, lp(-1, -2, 0, 10, 0, 0))
        refreshLastTest()

        liveAutomationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(liveAutomationContainer, lp(-1, -2, 0, 8, 0, 0))
        refreshLiveAutomation()

        root.addView(MaterialButton(this).apply {
            text = "✦  AI API & Model Settings"
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(16)
            minHeight = dp(50)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(40, 194, 164, 255))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.argb(80, 194, 164, 255))
            setTextColor(Color.rgb(220, 210, 245))
            setOnClickListener { startActivity(Intent(this@MainActivity, AiApiSettingsActivity::class.java)) }
        }, lp(-1, 50, 0, 10, 0, 0))
        root.addView(MaterialButton(this).apply {
            text = "⚙  Setup daily workflow"
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = dp(16)
            minHeight = dp(52)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(48, 255,255,255))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.argb(90,255,255,255))
            setTextColor(Color.rgb(224,226,234))
            setOnClickListener { startActivity(Intent(this@MainActivity, WorkflowSetupActivity::class.java)) }
        }, lp(-1, 52, 0, 10, 0, 0))

        root.addView(MaterialButton(this).apply {
            text = "▶  Test one video now"
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(16)
            minHeight = dp(52)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(38, 194,164,255))
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.argb(90,194,164,255))
            setTextColor(Color.rgb(220,210,245))
            setOnClickListener { startActivity(Intent(this@MainActivity, WorkflowTestActivity::class.java)) }
        }, lp(-1, 52, 0, 0, 0, 0))

        root.addView(sectionTitle("PAGES").apply {
            setPadding(0, dp(22), 0, dp(9))
        })
        val pages = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        pages.addView(actionButton("Queue", false) {
            startActivity(Intent(this@MainActivity, QueueActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
        pages.addView(actionButton("History", false) {
            startActivity(Intent(this@MainActivity, UploadHistoryActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
        root.addView(pages)
        root.addView(actionButton("App Settings", false) {
            startActivity(Intent(this@MainActivity, AppSettingsActivity::class.java))
        }, lp(-1, 50, 0, 10, 0, 0))

        kpiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(kpiContainer)
        refreshKpiRow()
        root.addView(sectionTitle("CREATE").apply {
            setPadding(0, dp(22), 0, dp(9))
        })

        val primary = MaterialButton(this).apply {
            text = "＋  Add videos"
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 20, 35))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(194, 164, 255))
            cornerRadius = dp(18)
            minHeight = dp(58)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        }
        root.addView(primary, lp(-1, 58, 0, 0, 0, 10))

        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        secondaryRow.addView(actionButton("Add folder", false) {
            pickFolder.launch(null)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })

        secondaryRow.addView(actionButton("YouTube Studio", false) {
            startActivity(Intent(this@MainActivity, YouTubeWorkspaceActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })

        root.addView(secondaryRow)

        root.addView(sectionTitle("SYSTEM").apply {
            setPadding(0, dp(22), 0, dp(8))
        })

        serviceStatusText = label("Checking…", 11f, Color.rgb(151, 157, 173), Typeface.NORMAL)
        scheduleStatusText = label("Checking…", 11f, Color.rgb(151, 157, 173), Typeface.NORMAL)
        updateServiceStatus()

        val automationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        automationRow.addView(settingCard(
            "Accessibility",
            serviceStatusText,
            "Enable"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(6) })

        automationRow.addView(settingCard(
            "Scheduling",
            scheduleStatusText,
            "Configure"
        ) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(android.app.AlarmManager::class.java)
                if (!alarmManager.canScheduleExactAlarms()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:" + packageName)
                    })
                }
            }
        }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(6) })

        root.addView(automationRow)

        val queueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(26), 0, dp(10))
        }

        queueHeader.addView(sectionTitle("UPLOAD QUEUE"), LinearLayout.LayoutParams(0, -2, 1f))
        queueCountText = label("0 items", 12f, Color.rgb(151, 157, 173), Typeface.NORMAL)
        queueCountText.gravity = Gravity.END
        queueHeader.addView(queueCountText)
        root.addView(queueHeader)

        queueContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(queueContainer)

        val footer = label(
            "User-authorized automation • YouTube UI workflow",
            11f,
            Color.rgb(105, 111, 126),
            Typeface.NORMAL
        )
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(24), 0, 0)
        root.addView(footer)

        scroll.addView(root)
        return scroll
    }

    private fun refreshLiveAutomation() {
        if (!::liveAutomationContainer.isInitialized) return
        val snapshot = com.myaiagent.automation.AutomationLiveStore(this).snapshot()
        liveAutomationContainer.removeAllViews()
        if (snapshot.fileName.isBlank()) return

        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                when {
                    snapshot.active -> Color.argb(62, 194, 164, 255)
                    snapshot.result == "SUCCESS" -> Color.argb(52, 74, 190, 126)
                    snapshot.result == "ERROR" -> Color.argb(52, 190, 74, 86)
                    else -> Color.argb(42, 255, 255, 255)
                }
            )
            strokeWidth = dp(1)
            strokeColor = Color.argb(85, 194, 164, 255)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }

        body.addView(label(
            if (snapshot.active) "●  LIVE AUTOMATION" else "LAST AUTOMATION",
            10f,
            if (snapshot.active) Color.rgb(125,220,164) else Color.rgb(151,157,173),
            Typeface.BOLD
        ))
        body.addView(label(
            snapshot.fileName,
            14f,
            Color.WHITE,
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, dp(3))
        })

        val humanState = when (snapshot.state) {
            "WAITING_FOR_APP" -> "Opening YouTube Studio"
            "FIND_CREATE" -> "Finding Create"
            "FIND_UPLOAD" -> "Opening Upload"
            "WAITING_FOR_PICKER" -> "Selecting video"
            "FILL_DETAILS" -> "Filling title & description"
            "SET_VISIBILITY" -> "Setting visibility"
            "PUBLISH" -> "Publishing"
            "MONITOR_UPLOAD" -> "Monitoring upload progress"
            "VERIFY" -> "Final verification"
            "WAITING_USER" -> "Waiting for your action"
            "COMPLETE" -> "Complete"
            "ERROR" -> "Error"
            else -> "Working"
        }

        body.addView(label(
            when {
                snapshot.result == "SUCCESS" -> "✓ Upload completed"
                snapshot.result == "ERROR" -> "✕ Upload stopped"
                snapshot.state == "WAITING_USER" -> "⚠ $humanState"
                else -> humanState
            },
            13f,
            when {
                snapshot.result == "SUCCESS" -> Color.rgb(125,220,164)
                snapshot.result == "ERROR" -> Color.rgb(255,112,112)
                snapshot.state == "WAITING_USER" -> Color.rgb(255,181,105)
                else -> Color.WHITE
            },
            Typeface.BOLD
        ).apply { setPadding(0, dp(2), 0, dp(3)) })

        if (snapshot.note.isNotBlank()) {
            body.addView(label(
                snapshot.note,
                11f,
                Color.rgb(169,175,191),
                Typeface.NORMAL
            ).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        snapshot.events.takeLast(3).forEach { event ->
            body.addView(label(
                "• $event",
                10f,
                Color.rgb(145,150,164),
                Typeface.NORMAL
            ).apply { setPadding(0, dp(3), 0, 0) })
        }

        if (snapshot.active) {
            val hint = label(
                "Process is running. You can keep this screen open and watch each step.",
                10f,
                Color.rgb(132,137,151),
                Typeface.NORMAL
            )
            hint.setPadding(0, dp(7), 0, 0)
            body.addView(hint)
        }

        card.addView(body)
        liveAutomationContainer.addView(card)
    }

    private fun refreshLastTest() {
        if (!::lastTestContainer.isInitialized) return
        val snapshot = TestRunStore(this).snapshot()
        lastTestContainer.removeAllViews()
        if (snapshot.fileName.isBlank() || snapshot.result.isBlank()) return

        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                when (snapshot.result) {
                    "SUCCESS" -> Color.argb(58, 74, 190, 126)
                    "ERROR" -> Color.argb(58, 190, 74, 86)
                    "WAITING_USER" -> Color.argb(58, 190, 140, 74)
                    else -> Color.argb(45, 255,255,255)
                }
            )
            strokeWidth = dp(1)
            strokeColor = when (snapshot.result) {
                "SUCCESS" -> Color.argb(100, 108,220,157)
                "ERROR" -> Color.argb(100, 255,112,112)
                "WAITING_USER" -> Color.argb(100, 255,181,105)
                else -> Color.argb(70,255,255,255)
            }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        body.addView(label("LAST WORKFLOW TEST", 10f, Color.rgb(151,157,173), Typeface.BOLD))
        body.addView(label(
            snapshot.fileName,
            15f,
            Color.WHITE,
            Typeface.BOLD
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, dp(4))
        })
        val resultText = when (snapshot.result) {
            "SUCCESS" -> "✓ Upload verified successfully"
            "ERROR" -> "✕ Test failed / stopped"
            "WAITING_USER" -> "⚠ User action required"
            else -> snapshot.result
        }
        body.addView(label(
            resultText,
            12f,
            when (snapshot.result) {
                "SUCCESS" -> Color.rgb(125,220,164)
                "ERROR" -> Color.rgb(255,112,112)
                "WAITING_USER" -> Color.rgb(255,181,105)
                else -> Color.rgb(180,185,198)
            },
            Typeface.BOLD
        ))
        if (snapshot.resultNote.isNotBlank()) {
            body.addView(label(
                snapshot.resultNote,
                11f,
                Color.rgb(169,175,191),
                Typeface.NORMAL
            ).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
        }
        card.addView(body)
        lastTestContainer.addView(card)
    }

    private fun refreshKpiRow() {
        if (!::kpiContainer.isInitialized) return
        kpiContainer.removeAllViews()

        fun kpi(title: String, value: String, accent: Int): View {
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.argb(44, 255, 255, 255))
                strokeWidth = dp(1)
                strokeColor = Color.argb(68, 255, 255, 255)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
            }
            body.addView(label(value, 20f, Color.WHITE, Typeface.BOLD))
            body.addView(label(title, 10f, accent, Typeface.BOLD).apply {
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(body)
            return card
        }

        val items = queueStore.load()
        val queued = items.count { it.status == "QUEUED" }
        val scheduled = items.count { it.status == "SCHEDULED" && (it.scheduledAt ?: Long.MAX_VALUE) > System.currentTimeMillis() }
        val running = items.count { it.status == "RUNNING" || it.status == "NEEDS_USER_ACTION" }
        kpiContainer.addView(kpi("IN QUEUE", queued.toString(), Color.rgb(176,151,255)),
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(4) })
        kpiContainer.addView(kpi("SCHEDULED", scheduled.toString(), Color.rgb(113,196,255)),
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(4),0,dp(4),0) })
        kpiContainer.addView(kpi("ACTIVE", running.toString(), Color.rgb(111,224,164)),
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(4) })
    }

    private fun buildStatusCard(): View {
        val config = WorkflowStore(this).load()
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(if (config.enabled) Color.argb(72, 74, 190, 126) else Color.argb(55, 255, 255, 255))
            strokeWidth = dp(1)
            strokeColor = if (config.enabled) Color.argb(110, 108, 220, 157) else Color.argb(70, 255,255,255)
            setOnClickListener { startActivity(Intent(this@MainActivity, WorkflowSetupActivity::class.java)) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val title = if (config.enabled) "●  WORKFLOW ON" else "WORKFLOW NOT SET"
        body.addView(label(title, 11f, if (config.enabled) Color.rgb(125,220,164) else Color.rgb(255,181,105), Typeface.BOLD))
        body.addView(label(
            if (config.enabled) "Daily • ${config.dailyLimit} upload(s) / day" else "Set your folder and daily schedule once.",
            18f, Color.WHITE, Typeface.BOLD
        ).apply { setPadding(0, dp(5), 0, dp(3)) })
        val details = if (config.enabled) {
            config.times.take(config.dailyLimit).joinToString("  •  ") { formatWorkflowTime(it) } +
                "\nNext videos will be taken automatically from your configured folder."
        } else {
            "Folder → frequency → times → Start workflow"
        }
        body.addView(label(details, 12f, Color.rgb(169,175,191), Typeface.NORMAL))
        card.addView(body)
        return card
    }

    private fun formatWorkflowTime(value: String): String {
        val p = value.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return value
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val hour = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return String.format(Locale.getDefault(), "%d:%02d %s", hour, m, if (h >= 12) "PM" else "AM")
    }
    private fun settingCard(
        title: String,
        status: MaterialTextView,
        action: String,
        onClick: () -> Unit
    ): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.argb(48, 255, 255, 255))
            strokeWidth = dp(1)
            strokeColor = Color.argb(72, 255, 255, 255)
            setOnClickListener { onClick() }
            isClickable = true
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        body.addView(label(title, 13f, Color.WHITE, Typeface.BOLD))
        status.textSize = 11f
        status.setPadding(0, dp(5), 0, dp(4))
        body.addView(status)
        body.addView(label(action, 11f, Color.rgb(184, 157, 245), Typeface.BOLD))

        card.addView(body)
        return card
    }

    private fun actionButton(textValue: String, filled: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = textValue
            textSize = 13f
            isAllCaps = false
            cornerRadius = dp(16)
            minHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
            if (filled) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(194, 164, 255))
                setTextColor(Color.rgb(25, 20, 35))
            } else {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(48, 255,255,255))
                strokeWidth = dp(1)
                strokeColor = android.content.res.ColorStateList.valueOf(Color.argb(90,255,255,255))
                setTextColor(Color.rgb(224, 226, 234))
            }
        }

    private fun sectionTitle(textValue: String): MaterialTextView =
        label(textValue, 10f, Color.rgb(132, 137, 151), Typeface.BOLD)

    private fun refreshQueue() {
        val items = queueStore.load()
        if (!::queueContainer.isInitialized) return

        refreshKpiRow()

        queueCountText.text = when (items.size) {
            0 -> "No items"
            1 -> "1 item"
            else -> items.size.toString() + " items"
        }

        queueContainer.removeAllViews()

        if (items.isEmpty()) {
            val empty = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.argb(42, 255, 255, 255))
                strokeWidth = dp(1)
                strokeColor = Color.argb(65, 255, 255, 255)
            }
            val text = label(
                "Your upload queue is empty.\nAdd a video to get started.",
                14f,
                Color.rgb(151, 157, 173),
                Typeface.NORMAL
            )
            text.gravity = Gravity.CENTER
            text.setPadding(dp(20), dp(28), dp(20), dp(28))
            empty.addView(text)
            queueContainer.addView(empty)
            return
        }

        items.forEachIndexed { index, item ->
            queueContainer.addView(buildQueueCard(index + 1, item))
        }
    }

    private fun buildQueueCard(number: Int, item: UploadItem): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.rgb(24, 25, 32))
            strokeWidth = dp(1)
            strokeColor = Color.rgb(48, 50, 61)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QueueItemActivity::class.java).apply {
                    putExtra("item_id", item.id)
                })
            }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = label(
            String.format(Locale.getDefault(), "%02d", number),
            11f,
            Color.rgb(204, 180, 255),
            Typeface.BOLD
        )
        badge.gravity = Gravity.CENTER
        badge.background = rounded(Color.rgb(43, 35, 57), dp(10))
        badge.setPadding(dp(9), dp(7), dp(9), dp(7))
        top.addView(badge)

        val file = label(
            item.fileName,
            14f,
            Color.WHITE,
            Typeface.BOLD
        )
        file.maxLines = 1
        file.ellipsize = android.text.TextUtils.TruncateAt.END
        file.setPadding(dp(10), 0, dp(8), 0)
        top.addView(file, LinearLayout.LayoutParams(0, -2, 1f))

        val status = item.status.uppercase(Locale.getDefault())
        val statusView = label(
            status,
            9f,
            statusColor(status),
            Typeface.BOLD
        )
        statusView.gravity = Gravity.CENTER
        statusView.background = rounded(statusBackground(status), dp(9))
        statusView.setPadding(dp(8), dp(6), dp(8), dp(6))
        top.addView(statusView)

        body.addView(top)

        val title = item.title.ifBlank { "Title not configured" }
        body.addView(label(
            title,
            13f,
            Color.rgb(177, 182, 196),
            Typeface.NORMAL
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(42), dp(8), 0, dp(6))
        })

        val metadata = label(
            (item.visibility + "  •  " + item.automationMode.replace("_", " ")),
            11f,
            Color.rgb(126, 132, 148),
            Typeface.NORMAL
        )
        metadata.setPadding(dp(42), 0, 0, 0)
        body.addView(metadata)

        val schedule = item.scheduledAt?.let {
            java.text.SimpleDateFormat(
                "dd MMM, hh:mm a",
                Locale.getDefault()
            ).format(it)
        } ?: "Automatic / ready"

        body.addView(label(
            schedule,
            11f,
            Color.rgb(126, 132, 148),
            Typeface.NORMAL
        ).apply {
            setPadding(dp(42), dp(4), 0, 0)
        })

        if (item.resultNote.isNotBlank()) {
            body.addView(label(
                item.resultNote,
                11f,
                Color.rgb(151, 157, 173),
                Typeface.NORMAL
            ).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(42), dp(7), 0, 0)
            })
        }

        if (item.status != "SUBMITTED" && item.status != "UPLOADED" && item.status != "RUNNING") {
            val runNow = MaterialButton(this).apply {
                text = "▶  Post now"
                textSize = 12f
                isAllCaps = false
                cornerRadius = dp(14)
                minHeight = dp(44)
                insetTop = 0
                insetBottom = 0
                setOnClickListener { runQueueItemNow(item) }
            }
            body.addView(runNow, LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(10)
            })
        }



        card.addView(body)
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun runQueueItemNow(item: UploadItem) {
        UploadAlarmScheduler.cancel(this, item.id)
        val updated = item.copy(
            status = "QUEUED",
            scheduledAt = null,
            lastRunAt = System.currentTimeMillis(),
            resultNote = "Manual post started"
        )
        queueStore.update(updated)
        val intent = Intent(this, com.myaiagent.automation.UploadRunnerService::class.java).apply {
            putExtra(com.myaiagent.automation.UploadRunnerService.EXTRA_ITEM_ID, updated.id)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateServiceStatus() {
        if (!::serviceStatusText.isInitialized) return
        if (isAutomationServiceEnabled()) {
            serviceStatusText.text = "● Enabled"
            serviceStatusText.setTextColor(Color.rgb(125, 220, 164))
        } else {
            serviceStatusText.text = "○ Not enabled"
            serviceStatusText.setTextColor(Color.rgb(255, 181, 105))
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(android.app.AlarmManager::class.java)
            scheduleStatusText.text = if (alarmManager.canScheduleExactAlarms()) {
                "● Exact alarms on"
            } else {
                "○ Tap to enable"
            }
        } else {
            scheduleStatusText.text = "● System scheduler"
        }
    }

    private fun rescheduleUploads() {
        UploadAlarmScheduler.rescheduleAll(this, queueStore.load())
    }

    private fun autoStartQueueIfPossible() {
        if (!isAutomationServiceEnabled()) return
        if (AutomationSessionStore(this).isActive()) return

        val workflow = WorkflowStore(this).load()
        // A manually added video must never auto-upload just because the app opens.
        // Automatic execution is reserved for an explicitly enabled workflow.
        if (!workflow.enabled) return
        val candidates = queueStore.load().filter {
            (it.status == "QUEUED" || it.status == "SCHEDULED") && it.scheduledAt != null
        }
        val next = UploadQueueCoordinator.nextEligible(candidates) ?: return
        if (next.status == "RUNNING") return

        val intent = Intent(
            this,
            com.myaiagent.automation.UploadRunnerService::class.java
        ).apply {
            putExtra(
                com.myaiagent.automation.UploadRunnerService.EXTRA_ITEM_ID,
                next.id
            )
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isAutomationServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected =
            packageName + "/com.myaiagent.automation.NaxAccessibilityService"

        return TextUtils.SimpleStringSplitter(':').let { splitter ->
            splitter.setString(enabled)
            splitter.any { it.equals(expected, ignoreCase = true) }
        }
    }

    private fun label(
        textValue: String,
        size: Float,
        color: Int,
        style: Int
    ): MaterialTextView =
        MaterialTextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun statusColor(status: String): Int = when (status) {
        "RUNNING" -> Color.rgb(115, 190, 255)
        "SUBMITTED", "UPLOADED" -> Color.rgb(125, 220, 164)
        "ERROR" -> Color.rgb(255, 112, 112)
        "NEEDS_USER_ACTION" -> Color.rgb(255, 181, 105)
        "SCHEDULED" -> Color.rgb(204, 180, 255)
        else -> Color.rgb(180, 185, 198)
    }

    private fun statusBackground(status: String): Int = when (status) {
        "RUNNING" -> Color.rgb(27, 45, 59)
        "SUBMITTED", "UPLOADED" -> Color.rgb(25, 43, 34)
        "ERROR" -> Color.rgb(55, 27, 31)
        "NEEDS_USER_ACTION" -> Color.rgb(55, 39, 25)
        "SCHEDULED" -> Color.rgb(43, 35, 57)
        else -> Color.rgb(37, 39, 48)
    }

    private fun lp(
        width: Int,
        heightDp: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, dp(heightDp)).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun persistTreePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun resolveName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }

        return DocumentFile.fromSingleUri(this, uri)?.name
            ?: uri.lastPathSegment
            ?: "video"
    }
}
