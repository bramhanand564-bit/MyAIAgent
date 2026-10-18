package com.auradroid.agent

import android.Manifest
import android.app.TimePickerDialog
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Top Header
    private lateinit var tvHeaderStatus: TextView
    private lateinit var btnToggleFloatingHud: Button
    private lateinit var btnEmergencyStopAll: Button

    // 5 Tab Containers
    private lateinit var tabChatContainer: ScrollView
    private lateinit var tabBrowserContainer: LinearLayout
    private lateinit var tabStoryContainer: ScrollView
    private lateinit var tabTrainContainer: ScrollView
    private lateinit var tabSettingsContainer: ScrollView

    // Bottom Navigation Buttons
    private lateinit var btnNavTab1: Button
    private lateinit var btnNavTab2: Button
    private lateinit var btnNavTab3: Button
    private lateinit var btnNavTab4: Button
    private lateinit var btnNavTab5: Button

    // Tab 1: Chat & Timer
    private lateinit var rgExecutionMode: RadioGroup
    private lateinit var rbModeStealthWeb: RadioButton
    private lateinit var rbModePhoneVision: RadioButton
    private lateinit var svChatHistory: ScrollView
    private lateinit var tvChatHistory: TextView
    private lateinit var etChatMessage: EditText
    private lateinit var btnVoiceMic: Button
    private lateinit var btnSendChat: Button
    private lateinit var btnClearTimers: Button
    private lateinit var tvActiveTimers: TextView

    // Tab 2: Browser
    private lateinit var btnBrowserBack: Button
    private lateinit var btnBrowserForward: Button
    private lateinit var btnBrowserReload: Button
    private lateinit var etBrowserUrl: EditText
    private lateinit var btnBrowserGo: Button
    private lateinit var btnShortcutFlow: Button
    private lateinit var btnShortcutYouTubeStudio: Button
    private lateinit var btnShortcutGoogleLogin: Button
    private lateinit var btnToggleDesktopMode: Button
    private lateinit var btnPinCurrentUrl: Button
    private lateinit var btnViewDownloads: Button
    private lateinit var etCustomWebGoal: EditText
    private lateinit var btnRunWebGoal: Button
    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView

    // Tab 3: Story Showrunner
    private lateinit var etStoryTopic: EditText
    private lateinit var rgStoryMode: RadioGroup
    private lateinit var rbStorySerial: RadioButton
    private lateinit var rbStorySideStory: RadioButton
    private lateinit var rbStoryStandalone: RadioButton
    private lateinit var etCharacterVisualLock: EditText
    private lateinit var etCustomTwist: EditText
    private lateinit var rgVideoDuration: RadioGroup
    private lateinit var rbDur8s: RadioButton
    private lateinit var rbDur10s: RadioButton
    private lateinit var rbDur12s: RadioButton
    private lateinit var rbDur15s: RadioButton
    private lateinit var etCustomDurationSec: EditText
    private lateinit var etEpisodeCount: EditText
    private lateinit var cbAutoUploadYouTube: CheckBox
    private lateinit var btnStartFlowPipeline: Button
    private lateinit var btnUploadExistingVideo: Button
    private lateinit var btnConfirmVideoUploaded: Button
    private lateinit var btnScheduleDailyYouTube: Button
    private lateinit var btnCancelDailyYouTube: Button
    private lateinit var tvSelectedFolderUri: TextView
    private lateinit var btnPickStorageFolder: Button
    private lateinit var rgUploadOrder: RadioGroup
    private lateinit var rbOrderNewestFirst: RadioButton
    private lateinit var rbOrderOldestFirst: RadioButton
    private lateinit var btnResetStoryArc: Button
    private lateinit var tvStoryArcHistory: TextView

    // Tab 4: Train Studio & RAG Vault
    private lateinit var etSkillName: EditText
    private lateinit var btnPickTrainScreenshots: Button
    private lateinit var btnStartLiveTeach: Button
    private lateinit var btnLoadFlowMasterBlueprint: Button
    private lateinit var etSkillJsonBlueprint: EditText
    private lateinit var btnSaveSkillBlueprint: Button
    private lateinit var btnCopySkillBlueprint: Button
    private lateinit var etMemoryFact: EditText
    private lateinit var btnSaveMemoryFact: Button
    private lateinit var tvRagVaultViewer: TextView

    // Tab 5: Engine Settings & Logs
    private lateinit var etGeminiApiKey: EditText
    private lateinit var cbUseNativeExecution: CheckBox
    private lateinit var cbPassiveTeachMode: CheckBox
    private var syncingExecutionMode = false
    private lateinit var etFastModelName: EditText
    private lateinit var etProModelName: EditText
    private lateinit var btnSaveAiSettings: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnGrantOverlayPermission: Button
    private lateinit var btnGrantStoragePermission: Button
    private lateinit var btnGrantBatteryUnrestricted: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var tvConsoleLogs: TextView

    private var isDesktopMode: Boolean = false
    private var isLiveTeachRecording: Boolean = false
    private val liveTeachStepsArray = JSONArray()

    // Activity Result Launchers
    private val voiceMicLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                etChatMessage.setText(spoken)
                handleSendChatMessage()
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }
            VideoQueueManager.setCustomFolderUri(this, uri.toString())
            tvSelectedFolderUri.text = VideoQueueManager.getFolderDisplayLabel(this)
            appendConsoleLog("[Storage] Custom SAF folder locked: $uri")
        }
    }

    private val screenshotTrainerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val skillKey = etSkillName.text.toString().trim().ifBlank { "custom_vision_skill" }
            appendConsoleLog("[Trainer] Analyzing ${uris.size} sequential screenshots via Gemini Vision OCR...")
            lifecycleScope.launch {
                val bitmaps = uris.take(4).mapNotNull { u ->
                    try {
                        contentResolver.openInputStream(u)?.use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                val blueprint = GeminiBrain.trainSkillFromScreenshots(this@MainActivity, skillKey, bitmaps)
                etSkillJsonBlueprint.setText(blueprint)
                AutomationReliability.saveSkillBlueprint(this@MainActivity, skillKey, blueprint)
                appendConsoleLog("[Trainer] Vision OCR State-Machine Blueprint saved for '$skillKey'.")
                refreshRagVaultViewer()
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        Toast.makeText(this, "Storage permissions updated!", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AutomationReliability.init(this)
        AiOverlayHud.init(this)

        bindAllViews()
        GeminiBrain.setModelRouterLogListener { line -> appendConsoleLog(line) }
        setupWebView()
        setupTabNavigation()
        setupListeners()
        loadInitialState()

        handleIncomingScheduledIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingScheduledIntent(intent)
    }

    private fun bindAllViews() {
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        btnToggleFloatingHud = findViewById(R.id.btnToggleFloatingHud)
        btnEmergencyStopAll = findViewById(R.id.btnEmergencyStopAll)

        tabChatContainer = findViewById(R.id.tabChatContainer)
        tabBrowserContainer = findViewById(R.id.tabBrowserContainer)
        tabStoryContainer = findViewById(R.id.tabStoryContainer)
        tabTrainContainer = findViewById(R.id.tabTrainContainer)
        tabSettingsContainer = findViewById(R.id.tabSettingsContainer)

        btnNavTab1 = findViewById(R.id.btnNavTab1)
        btnNavTab2 = findViewById(R.id.btnNavTab2)
        btnNavTab3 = findViewById(R.id.btnNavTab3)
        btnNavTab4 = findViewById(R.id.btnNavTab4)
        btnNavTab5 = findViewById(R.id.btnNavTab5)

        rgExecutionMode = findViewById(R.id.rgExecutionMode)
        rbModeStealthWeb = findViewById(R.id.rbModeStealthWeb)
        rbModePhoneVision = findViewById(R.id.rbModePhoneVision)
        svChatHistory = findViewById(R.id.svChatHistory)
        tvChatHistory = findViewById(R.id.tvChatHistory)
        etChatMessage = findViewById(R.id.etChatMessage)
        btnVoiceMic = findViewById(R.id.btnVoiceMic)
        btnSendChat = findViewById(R.id.btnSendChat)
        btnClearTimers = findViewById(R.id.btnClearTimers)
        tvActiveTimers = findViewById(R.id.tvActiveTimers)

        btnBrowserBack = findViewById(R.id.btnBrowserBack)
        btnBrowserForward = findViewById(R.id.btnBrowserForward)
        btnBrowserReload = findViewById(R.id.btnBrowserReload)
        etBrowserUrl = findViewById(R.id.etBrowserUrl)
        btnBrowserGo = findViewById(R.id.btnBrowserGo)
        btnShortcutFlow = findViewById(R.id.btnShortcutFlow)
        btnShortcutYouTubeStudio = findViewById(R.id.btnShortcutYouTubeStudio)
        btnShortcutGoogleLogin = findViewById(R.id.btnShortcutGoogleLogin)
        btnToggleDesktopMode = findViewById(R.id.btnToggleDesktopMode)
        btnPinCurrentUrl = findViewById(R.id.btnPinCurrentUrl)
        btnViewDownloads = findViewById(R.id.btnViewDownloads)
        etCustomWebGoal = findViewById(R.id.etCustomWebGoal)
        btnRunWebGoal = findViewById(R.id.btnRunWebGoal)
        webViewContainer = findViewById(R.id.webViewContainer)
        mainWebView = findViewById(R.id.mainWebView)

        etStoryTopic = findViewById(R.id.etStoryTopic)
        rgStoryMode = findViewById(R.id.rgStoryMode)
        rbStorySerial = findViewById(R.id.rbStorySerial)
        rbStorySideStory = findViewById(R.id.rbStorySideStory)
        rbStoryStandalone = findViewById(R.id.rbStoryStandalone)
        etCharacterVisualLock = findViewById(R.id.etCharacterVisualLock)
        etCustomTwist = findViewById(R.id.etCustomTwist)
        rgVideoDuration = findViewById(R.id.rgVideoDuration)
        rbDur8s = findViewById(R.id.rbDur8s)
        rbDur10s = findViewById(R.id.rbDur10s)
        rbDur12s = findViewById(R.id.rbDur12s)
        rbDur15s = findViewById(R.id.rbDur15s)
        etCustomDurationSec = findViewById(R.id.etCustomDurationSec)
        etEpisodeCount = findViewById(R.id.etEpisodeCount)
        cbAutoUploadYouTube = findViewById(R.id.cbAutoUploadYouTube)
        btnStartFlowPipeline = findViewById(R.id.btnStartFlowPipeline)
        btnUploadExistingVideo = findViewById(R.id.btnUploadExistingVideo)
        btnConfirmVideoUploaded = findViewById(R.id.btnConfirmVideoUploaded)
        btnScheduleDailyYouTube = findViewById(R.id.btnScheduleDailyYouTube)
        btnCancelDailyYouTube = findViewById(R.id.btnCancelDailyYouTube)
        tvSelectedFolderUri = findViewById(R.id.tvSelectedFolderUri)
        btnPickStorageFolder = findViewById(R.id.btnPickStorageFolder)
        rgUploadOrder = findViewById(R.id.rgUploadOrder)
        rbOrderNewestFirst = findViewById(R.id.rbOrderNewestFirst)
        rbOrderOldestFirst = findViewById(R.id.rbOrderOldestFirst)
        btnResetStoryArc = findViewById(R.id.btnResetStoryArc)
        tvStoryArcHistory = findViewById(R.id.tvStoryArcHistory)

        etSkillName = findViewById(R.id.etSkillName)
        btnPickTrainScreenshots = findViewById(R.id.btnPickTrainScreenshots)
        btnStartLiveTeach = findViewById(R.id.btnStartLiveTeach)
        btnLoadFlowMasterBlueprint = findViewById(R.id.btnLoadFlowMasterBlueprint)
        etSkillJsonBlueprint = findViewById(R.id.etSkillJsonBlueprint)
        btnSaveSkillBlueprint = findViewById(R.id.btnSaveSkillBlueprint)
        btnCopySkillBlueprint = findViewById(R.id.btnCopySkillBlueprint)
        etMemoryFact = findViewById(R.id.etMemoryFact)
        btnSaveMemoryFact = findViewById(R.id.btnSaveMemoryFact)
        tvRagVaultViewer = findViewById(R.id.tvRagVaultViewer)

        etGeminiApiKey = findViewById(R.id.etGeminiApiKey)
        cbUseNativeExecution = findViewById(R.id.cbUseNativeExecution)
        cbPassiveTeachMode = findViewById(R.id.cbPassiveTeachMode)
        etFastModelName = findViewById(R.id.etFastModelName)
        etProModelName = findViewById(R.id.etProModelName)
        btnSaveAiSettings = findViewById(R.id.btnSaveAiSettings)
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnGrantOverlayPermission = findViewById(R.id.btnGrantOverlayPermission)
        btnGrantStoragePermission = findViewById(R.id.btnGrantStoragePermission)
        btnGrantBatteryUnrestricted = findViewById(R.id.btnGrantBatteryUnrestricted)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(mainWebView, true)

        mainWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        mainWebView.webChromeClient = WebChromeClient()
        mainWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) {
                    etBrowserUrl.setText(url)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    WebPipelineManager.interceptNetworkRequestForMp4(request)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        // Live Teach Touch Recorder on WebView (normalizes into 540x960 space)
        mainWebView.setOnTouchListener { v, event ->
            if (isLiveTeachRecording && event.actionMasked == MotionEvent.ACTION_UP) {
                val vw = v.width.coerceAtLeast(1).toFloat()
                val vh = v.height.coerceAtLeast(1).toFloat()
                val normX = ((event.x / vw) * 540f).toInt()
                val normY = ((event.y / vh) * 960f).toInt()

                val stepObj = JSONObject().apply {
                    put("step", liveTeachStepsArray.length() + 1)
                    put("action", "CLICK_540x960")
                    put("x", normX)
                    put("y", normY)
                    put("url", mainWebView.url ?: "")
                }
                liveTeachStepsArray.put(stepObj)
                AiOverlayHud.moveBotAndShowTap(normX.toFloat(), normY.toFloat(), "Teach #${liveTeachStepsArray.length()}")
                appendConsoleLog("[LiveTeach] Recorded touch #${liveTeachStepsArray.length()} @($normX, $normY)")
            }
            false
        }

        AiOverlayHud.bindWebView(mainWebView, webViewContainer)
        AiOverlayHud.setCallbacks(
            onLiveTeachToggle = { toggleLiveTeachMode() },
            onStopClicked = { stopAllAutonomousOperations() }
        )

        mainWebView.loadUrl("https://labs.google/fx/tools/flow")
    }

    private fun setupTabNavigation() {
        fun selectTab(tabIndex: Int) {
            tabChatContainer.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
            tabBrowserContainer.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
            tabStoryContainer.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
            tabTrainContainer.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE
            tabSettingsContainer.visibility = if (tabIndex == 5) View.VISIBLE else View.GONE

            val buttons = listOf(btnNavTab1, btnNavTab2, btnNavTab3, btnNavTab4, btnNavTab5)
            buttons.forEachIndexed { idx, btn ->
                val active = (idx + 1) == tabIndex
                btn.setBackgroundColor(Color.parseColor(if (active) "#0284C7" else "#1E293B"))
                btn.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#94A3B8"))
            }

            if (tabIndex == 2) {
                AiOverlayHud.returnWebViewToHomeContainer()
            }
        }

        btnNavTab1.setOnClickListener { selectTab(1) }
        btnNavTab2.setOnClickListener { selectTab(2) }
        btnNavTab3.setOnClickListener {
            refreshStoryArcUi()
            selectTab(3)
        }
        btnNavTab4.setOnClickListener {
            refreshRagVaultViewer()
            selectTab(4)
        }
        btnNavTab5.setOnClickListener { selectTab(5) }
    }

    private fun setupListeners() {
        WebPipelineManager.setLogListener { line -> appendConsoleLog(line) }
        WebPipelineManager.setStatusListener { status, emotion ->
            tvHeaderStatus.text = "Nax Bot • $status ($emotion)"
        }

        PhoneAgentManager.setListeners(
            logListener = { line ->
                appendConsoleLog(line)
                if (line.contains("[Plan]") || line.contains("[Checklist]") || line.contains("[Verify]")) {
                    appendChatLine("🧭 $line")
                }
            },
            statusListener = { status, emotion ->
                tvHeaderStatus.text = "Nax Bot • $status ($emotion)"
            }
        )

        rgExecutionMode.setOnCheckedChangeListener { _, checkedId ->
            if (syncingExecutionMode) return@setOnCheckedChangeListener
            val useNative = checkedId == R.id.rbModePhoneVision
            getSharedPreferences("auradroid_execution_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("use_native_execution", useNative).apply()
            if (cbUseNativeExecution.isChecked != useNative) {
                syncingExecutionMode = true
                cbUseNativeExecution.isChecked = useNative
                syncingExecutionMode = false
            }
        }

        cbUseNativeExecution.setOnCheckedChangeListener { _, useNative ->
            if (syncingExecutionMode) return@setOnCheckedChangeListener
            syncingExecutionMode = true
            if (useNative) {
                rbModePhoneVision.isChecked = true
            } else {
                rbModeStealthWeb.isChecked = true
            }
            syncingExecutionMode = false
            getSharedPreferences("auradroid_execution_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("use_native_execution", useNative).apply()
            appendConsoleLog("[Engine] Execution engine: ${if (useNative) "Native Android Accessibility" else "Stealth WebView"}.")
        }

        cbPassiveTeachMode.setOnCheckedChangeListener { _, enabled ->
            AutomationReliability.setPassiveTeachMode(this, enabled)
            appendConsoleLog("[ShadowTeach] Passive teaching ${if (enabled) "ENABLED" else "DISABLED"}. No screenshots or typed contents are stored.")
            appendChatLine("🧠 Passive Shadow Teach Mode ${if (enabled) "ON" else "OFF"}")
            refreshRagVaultViewer()
        }

        btnToggleFloatingHud.setOnClickListener {
            if (!AiOverlayHud.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                AiOverlayHud.toggle(this)
            }
        }

        btnEmergencyStopAll.setOnClickListener {
            stopAllAutonomousOperations()
        }

        // TAB 1: Chat & Smart Timer
        btnSendChat.setOnClickListener {
            handleSendChatMessage()
        }

        btnVoiceMic.setOnClickListener {
            try {
                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Nax Bot se Hinglish mein boliye...")
                }
                voiceMicLauncher.launch(speechIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Voice input not supported on this device", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearTimers.setOnClickListener {
            TaskSchedulerReceiver.clearAllTimers(this)
            tvActiveTimers.text = TaskSchedulerReceiver.getFormattedActiveTimers(this)
            Toast.makeText(this, "All scheduled timers cleared", Toast.LENGTH_SHORT).show()
        }

        // TAB 2: Browser Controls
        btnBrowserBack.setOnClickListener {
            if (mainWebView.canGoBack()) mainWebView.goBack()
        }
        btnBrowserForward.setOnClickListener {
            if (mainWebView.canGoForward()) mainWebView.goForward()
        }
        btnBrowserReload.setOnClickListener {
            mainWebView.reload()
        }
        btnBrowserGo.setOnClickListener {
            var rawUrl = etBrowserUrl.text.toString().trim()
            if (rawUrl.isNotBlank()) {
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    rawUrl = "https://$rawUrl"
                }
                mainWebView.loadUrl(rawUrl)
            }
        }
        btnShortcutFlow.setOnClickListener {
            mainWebView.loadUrl("https://labs.google/fx/tools/flow")
        }
        btnShortcutYouTubeStudio.setOnClickListener {
            mainWebView.loadUrl("https://studio.youtube.com")
        }
        btnShortcutGoogleLogin.setOnClickListener {
            mainWebView.loadUrl("https://accounts.google.com")
        }
        btnToggleDesktopMode.setOnClickListener {
            isDesktopMode = !isDesktopMode
            mainWebView.settings.userAgentString = if (isDesktopMode) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            } else {
                null
            }
            btnToggleDesktopMode.text = if (isDesktopMode) "🖥 Desktop: ON" else "🖥 Desktop: OFF"
            mainWebView.reload()
        }
        btnPinCurrentUrl.setOnClickListener {
            val current = mainWebView.url ?: "https://labs.google/fx/tools/flow"
            AutomationReliability.savePersonalMemoryFact(this, "Pinned URL: $current")
            Toast.makeText(this, "Pinned URL to Memory Vault!", Toast.LENGTH_SHORT).show()
        }
        btnViewDownloads.setOnClickListener {
            val report = VideoQueueManager.getDownloadedVideosReport(this)
            AlertDialog.Builder(this)
                .setTitle("⬇ Queued & Downloaded MP4 Videos")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }
        btnRunWebGoal.setOnClickListener {
            val goal = etCustomWebGoal.text.toString().trim().ifBlank {
                "Generate 9:16 vertical sci-fi video on Google Flow"
            }
            val url = mainWebView.url ?: "https://labs.google/fx/tools/flow"
            WebPipelineManager.startAutonomousWebAgent(this, mainWebView, goal, url)
        }

        // TAB 3: AI Story Showrunner & YouTube Pipeline
        btnStartFlowPipeline.setOnClickListener {
            val topic = etStoryTopic.text.toString().trim().ifBlank {
                "Adult futuristic cybernetic explorer discovers an ancient glowing AI core in Neo-Tokyo"
            }
            val storyMode = when (rgStoryMode.checkedRadioButtonId) {
                R.id.rbStorySideStory -> "SIDE_STORY"
                R.id.rbStoryStandalone -> "STANDALONE"
                else -> "SERIAL"
            }
            val charLock = etCharacterVisualLock.text.toString().trim().ifBlank {
                "Adult futuristic cybernetic tech explorer with glowing cyan visor"
            }
            val customTwist = etCustomTwist.text.toString().trim()
            val durationSec = resolveSelectedDurationSeconds()
            val epCount = etEpisodeCount.text.toString().trim().toIntOrNull()?.coerceIn(1, 10) ?: 1
            val autoUpload = cbAutoUploadYouTube.isChecked

            VideoQueueManager.saveStorySettings(this, storyMode, charLock)
            if (rbModeStealthWeb.isChecked && AiOverlayHud.canDrawOverlays(this)) {
                AiOverlayHud.enableInvisible1x1StealthMode()
            }

            WebPipelineManager.startFlowToYouTubePipeline(
                context = this,
                webView = mainWebView,
                topic = topic,
                storyMode = storyMode,
                characterVisualLock = charLock,
                customTwist = customTwist,
                durationSeconds = durationSec,
                episodeCount = epCount,
                autoUpload = autoUpload
            )
        }

        btnUploadExistingVideo.setOnClickListener {
            lifecycleScope.launch {
                val result = NativeYouTubeUploader.startNextUpload(this@MainActivity)
                appendConsoleLog("[YouTube Native] ${result.message}")
                appendChatLine(if (result.started) "📤 ${result.message}" else "⚠️ ${result.message}")
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                if (result.started) tvHeaderStatus.text = "Nax Bot • Preparing upload"
            }
        }

        btnConfirmVideoUploaded.setOnClickListener {
            val message = NativeYouTubeUploader.markPendingUploadComplete(this)
            appendConsoleLog("[YouTube Native] $message")
            appendChatLine("✅ $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        btnScheduleDailyYouTube.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    DailyYouTubeUploadReceiver.schedule(this, hourOfDay, minute)
                    val timeLabel = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
                    btnCancelDailyYouTube.isEnabled = true
                    appendConsoleLog("[YouTube Schedule] Daily reminder enabled for $timeLabel.")
                    appendChatLine("🗓 Daily YouTube upload reminder set for $timeLabel.")
                    Toast.makeText(this, "Daily upload reminder set for $timeLabel", Toast.LENGTH_LONG).show()
                },
                19,
                0,
                android.text.format.DateFormat.is24HourFormat(this)
            ).apply {
                setTitle("Choose daily YouTube upload reminder time")
            }.show()
        }

        btnCancelDailyYouTube.setOnClickListener {
            if (DailyYouTubeUploadReceiver.isScheduled(this)) {
                DailyYouTubeUploadReceiver.cancel(this)
                btnCancelDailyYouTube.isEnabled = false
                appendConsoleLog("[YouTube Schedule] Daily reminder cancelled.")
                Toast.makeText(this, "Daily YouTube reminder cancelled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No daily reminder is currently scheduled", Toast.LENGTH_SHORT).show()
            }
        }

        btnPickStorageFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        rgUploadOrder.setOnCheckedChangeListener { _, checkedId ->
            val newestFirst = checkedId == R.id.rbOrderNewestFirst
            VideoQueueManager.setUploadNewestFirst(this, newestFirst)
        }

        btnResetStoryArc.setOnClickListener {
            VideoQueueManager.resetStoryArc(this)
            refreshStoryArcUi()
            Toast.makeText(this, "Story Arc reset to Episode 1", Toast.LENGTH_SHORT).show()
        }

        // TAB 4: Train Studio & Vector RAG Vault
        btnPickTrainScreenshots.setOnClickListener {
            screenshotTrainerLauncher.launch("image/*")
        }

        btnStartLiveTeach.setOnClickListener {
            toggleLiveTeachMode()
        }

        btnLoadFlowMasterBlueprint.setOnClickListener {
            etSkillName.setText("google_flow_master")
            etSkillJsonBlueprint.setText(GeminiBrain.DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT)
            Toast.makeText(this, "Loaded Google Flow Master State-Machine Blueprint", Toast.LENGTH_SHORT).show()
        }

        btnSaveSkillBlueprint.setOnClickListener {
            val key = etSkillName.text.toString().trim().ifBlank { "google_flow_master" }
            val json = etSkillJsonBlueprint.text.toString().trim().ifBlank {
                GeminiBrain.DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT
            }
            AutomationReliability.saveSkillBlueprint(this, key, json)
            refreshRagVaultViewer()
            Toast.makeText(this, "Saved '$key' blueprint to SQLite!", Toast.LENGTH_SHORT).show()
        }

        btnCopySkillBlueprint.setOnClickListener {
            copyToClipboard("SkillBlueprint", etSkillJsonBlueprint.text.toString())
        }

        btnSaveMemoryFact.setOnClickListener {
            val fact = etMemoryFact.text.toString().trim()
            if (fact.isNotBlank()) {
                AutomationReliability.savePersonalMemoryFact(this, fact)
                etMemoryFact.setText("")
                refreshRagVaultViewer()
                Toast.makeText(this, "Rule added to Memory Vault!", Toast.LENGTH_SHORT).show()
            }
        }

        // TAB 5: AI Engine Settings & Permissions
        btnSaveAiSettings.setOnClickListener {
            GeminiBrain.setApiKey(this, etGeminiApiKey.text.toString())
            GeminiBrain.setFastModel(this, etFastModelName.text.toString())
            GeminiBrain.setProModel(this, etProModelName.text.toString())
            appendConsoleLog("[Engine] Saved Gemini API Key & Models (Fast=${GeminiBrain.getFastModel(this)}, Pro=${GeminiBrain.getProModel(this)})")
            GeminiBrain.logModelHealthSummary(this)
            Toast.makeText(this, "AI Engine Settings Saved!", Toast.LENGTH_SHORT).show()
        }

        btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }

        btnGrantStoragePermission.setOnClickListener {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            storagePermissionLauncher.launch(perms)
        }

        btnGrantBatteryUnrestricted.setOnClickListener {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Battery already Unrestricted!", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        btnCopyLogs.setOnClickListener {
            copyToClipboard("AuraDroidLogs", tvConsoleLogs.text.toString())
        }

        btnClearLogs.setOnClickListener {
            tvConsoleLogs.text = "[System] Console logs cleared.\n"
        }
    }

    private fun loadInitialState() {
        etGeminiApiKey.setText(GeminiBrain.getApiKey(this))
        etFastModelName.setText(GeminiBrain.getFastModel(this))
        etProModelName.setText(GeminiBrain.getProModel(this))

        val useNativeExecution = getSharedPreferences("auradroid_execution_settings", Context.MODE_PRIVATE)
            .getBoolean("use_native_execution", false)
        syncingExecutionMode = true
        rbModeStealthWeb.isChecked = !useNativeExecution
        rbModePhoneVision.isChecked = useNativeExecution
        cbUseNativeExecution.isChecked = useNativeExecution
        syncingExecutionMode = false

        cbPassiveTeachMode.isChecked = AutomationReliability.isPassiveTeachModeEnabled(this)
        btnCancelDailyYouTube.isEnabled = DailyYouTubeUploadReceiver.isScheduled(this)
        tvSelectedFolderUri.text = VideoQueueManager.getFolderDisplayLabel(this)
        val newestFirst = VideoQueueManager.isUploadNewestFirst(this)
        rbOrderNewestFirst.isChecked = newestFirst
        rbOrderOldestFirst.isChecked = !newestFirst

        val loadedBlueprint = AutomationReliability.loadSkillBlueprint(this, "google_flow_master")
        etSkillJsonBlueprint.setText(loadedBlueprint)

        tvActiveTimers.text = TaskSchedulerReceiver.getFormattedActiveTimers(this)
        refreshStoryArcUi()
        refreshRagVaultViewer()
    }

    private fun handleSendChatMessage() {
        val msg = etChatMessage.text.toString().trim()
        if (msg.isBlank()) return
        etChatMessage.setText("")

        appendChatLine("👤 Boss: $msg")
        AiOverlayHud.setEmotion(BotEmotion.THINKING)

        lifecycleScope.launch {
            val memories = AutomationReliability.getAllMemoryFacts(this@MainActivity)
            val plan = GeminiBrain.chatAndBuildPlan(this@MainActivity, msg, memories)

            AiOverlayHud.setEmotionByName(plan.emotion)
            appendChatLine("🤖 Nax Bot (●—●): ${plan.replyHinglish}")

            when (plan.intentType) {
                "SCHEDULE_TASK" -> {
                    val triggerAt = plan.scheduledTimeMillis
                        ?: (System.currentTimeMillis() + 5 * 60_000L)
                    val mode = if (rbModePhoneVision.isChecked) "PHONE_VISION" else "STEALTH_WEB"
                    TaskSchedulerReceiver.scheduleExactTask(
                        context = this@MainActivity,
                        triggerAtMillis = triggerAt,
                        goal = plan.targetGoal,
                        scheduleLabel = plan.scheduleLabel,
                        executionMode = mode
                    )
                    tvActiveTimers.text = TaskSchedulerReceiver.getFormattedActiveTimers(this@MainActivity)
                    appendConsoleLog("[Timer] Scheduled '${plan.targetGoal}' (${plan.scheduleLabel}) in $mode mode.")
                }

                "FLOW_PIPELINE" -> {
                    if (rbModeStealthWeb.isChecked && AiOverlayHud.canDrawOverlays(this@MainActivity)) {
                        AiOverlayHud.enableInvisible1x1StealthMode()
                    }
                    WebPipelineManager.startFlowToYouTubePipeline(
                        context = this@MainActivity,
                        webView = mainWebView,
                        topic = plan.targetGoal,
                        storyMode = "SERIAL",
                        characterVisualLock = etCharacterVisualLock.text.toString().ifBlank {
                            "Adult futuristic cybernetic tech explorer"
                        },
                        customTwist = "",
                        durationSeconds = resolveSelectedDurationSeconds(),
                        episodeCount = 1,
                        autoUpload = cbAutoUploadYouTube.isChecked
                    )
                }

                "WEB_TASK" -> {
                    if (rbModePhoneVision.isChecked) {
                        appendChatLine("📱 Using native Android accessibility for this task.")
                        PhoneAgentManager.startAutonomousPhoneTask(
                            context = this@MainActivity,
                            goal = plan.targetGoal
                        )
                    } else {
                        WebPipelineManager.startAutonomousWebAgent(
                            context = this@MainActivity,
                            webView = mainWebView,
                            goal = plan.targetGoal,
                            targetUrl = plan.targetUrl
                        )
                    }
                }

                "PHONE_TASK" -> {
                    PhoneAgentManager.startAutonomousPhoneTask(
                        context = this@MainActivity,
                        goal = plan.targetGoal
                    )
                }
            }
        }
    }

    private fun handleIncomingScheduledIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(DailyYouTubeUploadReceiver.EXTRA_OPEN_NATIVE_UPLOAD, false) == true) {
            intent.removeExtra(DailyYouTubeUploadReceiver.EXTRA_OPEN_NATIVE_UPLOAD)
            appendChatLine("⏰ Daily YouTube reminder opened. Preparing the next queued video…")
            lifecycleScope.launch {
                val result = NativeYouTubeUploader.startNextUpload(this@MainActivity)
                appendConsoleLog("[YouTube Native] ${result.message}")
                appendChatLine(if (result.started) "📤 ${result.message}" else "⚠️ ${result.message}")
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            }
            return
        }
        val autoGoal = intent?.getStringExtra(TaskSchedulerReceiver.EXTRA_AUTO_START_GOAL)
        if (autoGoal.isNullOrBlank()) return
        val mode = intent.getStringExtra(TaskSchedulerReceiver.EXTRA_AUTO_START_MODE) ?: "STEALTH_WEB"
        intent.removeExtra(TaskSchedulerReceiver.EXTRA_AUTO_START_GOAL)

        appendConsoleLog("[AlarmTriggered] Running scheduled autonomous task: $autoGoal ($mode)")
        appendChatLine("⏰ Scheduled Timer Triggered: $autoGoal")
        tvActiveTimers.text = TaskSchedulerReceiver.getFormattedActiveTimers(this)

        if (mode == "PHONE_VISION") {
            PhoneAgentManager.startAutonomousPhoneTask(this, autoGoal)
        } else {
            if (AiOverlayHud.canDrawOverlays(this)) {
                AiOverlayHud.enableInvisible1x1StealthMode()
            }
            WebPipelineManager.startFlowToYouTubePipeline(
                context = this,
                webView = mainWebView,
                topic = autoGoal,
                storyMode = "SERIAL",
                characterVisualLock = "Adult futuristic cybernetic tech explorer",
                customTwist = "",
                durationSeconds = resolveSelectedDurationSeconds(),
                episodeCount = 1,
                autoUpload = true
            )
        }
    }

    private fun toggleLiveTeachMode() {
        isLiveTeachRecording = !isLiveTeachRecording
        if (isLiveTeachRecording) {
            liveTeachStepsArray.let {
                while (it.length() > 0) it.remove(0)
            }
            btnStartLiveTeach.text = "⏹ Stop Teach"
            Toast.makeText(this, "Live Teach ON: Tap elements in WebView to record coordinates", Toast.LENGTH_SHORT).show()
        } else {
            btnStartLiveTeach.text = "👆 2. Live Teach"
            val skillKey = etSkillName.text.toString().trim().ifBlank { "live_taught_skill" }
            val blueprintObj = JSONObject().apply {
                put("skill_name", skillKey)
                put("viewport_space", "540x960")
                put("recorded_steps", liveTeachStepsArray)
            }
            val formatted = blueprintObj.toString(2)
            etSkillJsonBlueprint.setText(formatted)
            AutomationReliability.saveSkillBlueprint(this, skillKey, formatted)
            appendConsoleLog("[LiveTeach] Saved ${liveTeachStepsArray.length()} recorded touch steps to '$skillKey'.")
        }
    }

    private fun stopAllAutonomousOperations() {
        WebPipelineManager.stopCurrentJob()
        PhoneAgentManager.stopPhoneAgent()
        AiOverlayHud.setEmotion(BotEmotion.IDLE)
        tvHeaderStatus.text = "Nax Bot Ready • Stopped (●—●)"
        Toast.makeText(this, "Stopped all autonomous tasks", Toast.LENGTH_SHORT).show()
    }

    private fun resolveSelectedDurationSeconds(): Int {
        val customVal = etCustomDurationSec.text.toString().trim().toIntOrNull()
        if (customVal != null && customVal in 4..60) {
            return customVal
        }
        return when (rgVideoDuration.checkedRadioButtonId) {
            R.id.rbDur10s -> 10
            R.id.rbDur12s -> 12
            R.id.rbDur15s -> 15
            else -> 8
        }
    }

    private fun refreshStoryArcUi() {
        val state = VideoQueueManager.getStoryArcState(this)
        etCharacterVisualLock.setText(state.characterVisualLock)
        tvStoryArcHistory.text = state.arcHistorySummary
    }

    private fun refreshRagVaultViewer() {
        val memories = AutomationReliability.getAllMemoryFacts(this)
        val ragLessons = AutomationReliability.retrieveRelevantRagLessons(this, "google_flow", "")
        val teachCount = AutomationReliability.getShadowTeachEventCount(this)
        val teachSummary = AutomationReliability.getShadowTeachSummary(this, 25)
        val teachState = if (AutomationReliability.isPassiveTeachModeEnabled(this)) "ON" else "OFF"
        tvRagVaultViewer.text =
            "=== Personal Rules & Pinned URLs ===\n$memories" +
            "\n\n=== Vector RAG Self-Healing Lessons ===\n$ragLessons" +
            "\n\n=== Passive Shadow Teach ($teachState; $teachCount/500 events) ===\n$teachSummary"
    }

    private fun appendChatLine(line: String) {
        val current = tvChatHistory.text.toString()
        tvChatHistory.text = "$current\n\n$line"
        svChatHistory.post {
            svChatHistory.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendConsoleLog(line: String) {
        val current = tvConsoleLogs.text.toString()
        val updated = "$current$line\n"
        tvConsoleLogs.text = if (updated.length > 14000) updated.takeLast(14000) else updated
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            AiOverlayHud.show(this)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied $label to clipboard!", Toast.LENGTH_SHORT).show()
    }
}
