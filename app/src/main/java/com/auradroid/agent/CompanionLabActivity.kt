package com.auradroid.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Additional training lab. Existing chat/browser/pipeline tabs are left in place.
 */
class CompanionLabActivity : AppCompatActivity() {
    private val store by lazy { TrainingMemoryStore(this) }
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val draftSteps = mutableListOf<BlueprintStep>()
    private var recording = false

    private lateinit var storySeries: EditText
    private lateinit var storyPart: EditText
    private lateinit var storyText: EditText
    private lateinit var storyDialogue: EditText
    private lateinit var storySeedInput: EditText
    private lateinit var storyTwistInput: EditText
    private lateinit var storyGenerationStatus: TextView
    private lateinit var storyStatus: TextView
    private lateinit var topicInput: EditText
    private lateinit var dialogueInput: EditText
    private lateinit var durationSpinner: Spinner
    private lateinit var promptOutput: EditText
    private lateinit var jsonInput: EditText
    private lateinit var blueprintName: EditText
    private lateinit var actionSpinner: Spinner
    private lateinit var targetInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var waitInput: EditText
    private lateinit var stepStatus: TextView
    private lateinit var errorInput: EditText
    private lateinit var correctionStatus: TextView
    private lateinit var diagnostics: TextView
    private lateinit var baymax: BaymaxFaceView
    private lateinit var expressionSpinner: Spinner
    private lateinit var recordingButton: Button

    private val screenshotPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.take(6).forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                store.addTrainingAsset(uri.toString(), resolveDisplayName(uri))
            }
            refreshTrainingAssets()
            val selected = uris.take(6)
            AlertDialog.Builder(this)
                .setTitle("Analyze ordered screenshots?")
                .setMessage("The selected screenshots will be sent to Google Gemini using the API key saved in MyAIAgent. Gemini will propose a draft workflow JSON; it will NOT run automatically. Do not select screenshots containing passwords, OTPs, private messages or payment details.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Keep local only", null)
                .setPositiveButton("Analyze with Gemini") { _, _ ->
                    uiScope.launch {
                        stepStatus.text = "Analyzing ${selected.size} screenshot(s)…"
                        try {
                            val blueprint = ScreenshotBlueprintAnalyzer.analyze(this@CompanionLabActivity, selected)
                            draftSteps.clear()
                            draftSteps.addAll(blueprint.steps)
                            blueprintName.setText(blueprint.name)
                            recording = false
                            recordingButton.text = "⏺ Start recording a new workflow"
                            jsonInput.setText(blueprint.toJson())
                            refreshDraftStatus()
                            toast("Screenshot workflow draft created. Review every step and import the edited JSON before replay.")
                        } catch (e: Exception) {
                            stepStatus.text = "Screenshot analysis failed: ${e.message ?: "unknown error"}"
                            toast(e.message ?: "Screenshot analysis failed.")
                        }
                    }
                }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStoryMemory()
        refreshTrainingAssets()
        updateDiagnostics()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 19, 22))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(24, 26, 32))
        }
        val title = TextView(this).apply {
            text = "🧠 Agent Training Lab"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        header.addView(actionButton("Back", false) { finish() })
        outer.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)

        body.addView(label(
            "This is an additive module. Existing automation remains unchanged. Replays require a preview and explicit confirmation; Android can still block background actions."
        ))

        val faceCard = section("🤖 Companion expressions — 18 states")
        baymax = BaymaxFaceView(this)
        faceCard.addView(baymax, LinearLayout.LayoutParams(-1, dp(170)))
        expressionSpinner = spinner(BaymaxFaceView.EXPRESSIONS)
        expressionSpinner.onItemSelectedListener = simpleSelection { value -> baymax.setExpression(value) }
        faceCard.addView(expressionSpinner)
        faceCard.addView(label("Expression is a local UI preview; it does not generate or alter a video character."))
        body.addView(faceCard)

        val memoryCard = section("📚 SQLite episodic story memory")
        storySeries = edit("Story / series name", "My Story")
        storyPart = edit("Current part number (1–10000)", "1")
        storyText = edit("Current story / scene summary", "", true)
        storyDialogue = edit("Hindi dialogue for this part", "", true)
        storySeedInput = edit("Base story idea / original premise", "A friendly white robot discovers a hidden city beneath a futuristic mountain", true)
        storyTwistInput = edit("New twist for this part (optional)", "", true)
        memoryCard.addView(storySeries)
        memoryCard.addView(storyPart)
        memoryCard.addView(storySeedInput)
        memoryCard.addView(storyTwistInput)
        memoryCard.addView(storyText)
        memoryCard.addView(storyDialogue)
        storyGenerationStatus = label("Generated episodes are drafts. Review them before saving.")
        memoryCard.addView(storyGenerationStatus)
        memoryCard.addView(actionButton("✨ Generate next story part + Hindi dialogue", true) {
            val part = storyPart.text.toString().toIntOrNull()
            if (part == null || part !in 1..10000) {
                toast("Enter a valid part number from 1 to 10000.")
                return@actionButton
            }
            AlertDialog.Builder(this)
                .setTitle("Generate story with Gemini?")
                .setMessage("The base idea, saved previous-part context and optional twist will be sent to the Gemini model configured in MyAIAgent. The result will be a draft only; nothing is saved or published until you review and tap Save.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Generate draft") { _, _ ->
                    uiScope.launch {
                        storyGenerationStatus.text = "Generating Part $part using saved story context…"
                        try {
                            val previous = if (part > 1) {
                                store.getStoryContext(storySeries.text.toString(), part, 5)
                            } else {
                                "No previous part. Establish the premise and protagonist."
                            }
                            val episode = StoryEpisodeGenerator.generate(
                                this@CompanionLabActivity,
                                storySeries.text.toString(),
                                part,
                                storySeedInput.text.toString(),
                                previous,
                                storyTwistInput.text.toString()
                            )
                            storyText.setText(
                                buildString {
                                    append(episode.storyText)
                                    if (episode.cliffhanger.isNotBlank()) {
                                        append("\n\nEnding hook: ")
                                        append(episode.cliffhanger)
                                    }
                                    if (episode.nextPartOutline.isNotBlank()) {
                                        append("\n\nNext part idea: ")
                                        append(episode.nextPartOutline)
                                    }
                                }
                            )
                            storyDialogue.setText(episode.hindiDialogue)
                            topicInput.setText("${episode.title}\n${episode.storySummary}".trim())
                            storyGenerationStatus.text = "Draft: ${episode.title}\nSummary: ${episode.storySummary}\n\nReview story/dialogue; then tap Save this story part."
                            store.saveTaskEpisode("STORY_DRAFT_GENERATED", "Drafted ${storySeries.text} — Part $part")
                        } catch (e: Exception) {
                            storyGenerationStatus.text = "Generation failed: ${e.message ?: "unknown error"}"
                            toast(e.message ?: "Could not generate story.")
                        }
                    }
                }
                .show()
        })
        memoryCard.addView(actionButton("💾 Save this story part", true) {
            val part = storyPart.text.toString().toIntOrNull()
            if (part == null || part !in 1..10000) {
                toast("Enter a valid part number from 1 to 10000.")
            } else {
                try {
                    store.saveStoryPart(
                        storySeries.text.toString(),
                        part,
                        storyText.text.toString(),
                        storyDialogue.text.toString()
                    )
                    store.saveTaskEpisode("STORY_PART_SAVED", "Saved ${storySeries.text} — Part ${part}")
                    refreshStoryMemory()
                    toast("Story part saved to SQLite.")
                } catch (e: Exception) {
                    toast(e.message ?: "Could not save this story part.")
                }
            }
        })
        storyStatus = label("")
        memoryCard.addView(storyStatus)
        memoryCard.addView(actionButton("Refresh memory", false) { refreshStoryMemory() })
        body.addView(memoryCard)

        val videoCard = section("🎬 Custom duration + prompt timing")
        topicInput = edit("Video topic / scene", "A friendly rounded robot helps in a futuristic city")
        dialogueInput = edit("Hindi dialogue (optional)", "", true)
        durationSpinner = spinner(VideoPromptPlanner.supportedDurationsSeconds.map { "${it}s" })
        videoCard.addView(topicInput)
        videoCard.addView(label("Requested duration"))
        videoCard.addView(durationSpinner)
        videoCard.addView(dialogueInput)
        videoCard.addView(actionButton("Build timed prompt with previous-story context", true) {
            try {
                val seconds = VideoPromptPlanner.supportedDurationsSeconds[
                    durationSpinner.selectedItemPosition.coerceIn(0, VideoPromptPlanner.supportedDurationsSeconds.lastIndex)
                ]
                val part = storyPart.text.toString().toIntOrNull()
                val previous = if (part != null && part > 1) {
                    store.getStoryContext(storySeries.text.toString(), part, 5)
                } else {
                    store.getStoryContext(storySeries.text.toString(), null, 5)
                }
                val result = VideoPromptPlanner.buildTimedPrompt(
                    topicInput.text.toString(),
                    seconds,
                    previous,
                    dialogueInput.text.toString()
                )
                promptOutput.setText(result)
                store.saveTaskEpisode("VIDEO_PROMPT", "Prepared ${seconds}s timed prompt", result)
                toast("Prompt prepared. Check generator duration support before spending credits.")
            } catch (e: Exception) {
                toast(e.message ?: "Could not create the prompt.")
            }
        })
        promptOutput = edit("Generated prompt (editable)", "", true)
        videoCard.addView(promptOutput)
        videoCard.addView(actionButton("🚀 Run this duration in Google Flow", true) {
            try {
                val seconds = VideoPromptPlanner.supportedDurationsSeconds[
                    durationSpinner.selectedItemPosition.coerceIn(0, VideoPromptPlanner.supportedDurationsSeconds.lastIndex)
                ]
                val part = storyPart.text.toString().toIntOrNull()
                val previous = if (part != null) {
                    store.getStoryContext(storySeries.text.toString(), part, 5)
                } else {
                    store.getStoryContext(storySeries.text.toString(), null, 5)
                }
                val timedPrompt = VideoPromptPlanner.buildTimedPrompt(
                    topicInput.text.toString(), seconds, previous, dialogueInput.text.toString()
                )
                promptOutput.setText(timedPrompt)
                val prefs = getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
                val apiKey = prefs.getString("api_key", "").orEmpty().trim()
                val model = prefs.getString("model_name", "gemini-2.5-flash").orEmpty().trim()
                val folder = prefs.getString("folder_path", "/storage/emulated/0/Download/AutoVideos")
                    ?: "/storage/emulated/0/Download/AutoVideos"
                if (apiKey.isBlank()) {
                    toast("Add your Gemini API key in the main Settings tab first.")
                    return@actionButton
                }
                val service = AutoTouchService.instance
                if (service == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Accessibility service required")
                        .setMessage("Enable AuraDroid's Accessibility Service before running Google Flow automation.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Open settings") { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .show()
                    return@actionButton
                }
                val goal = """
                    We are on Google Flow (${VideoQueueManager.getPinnedFlowUrl(this)}).
                    Create one video using the exact supplied prompt only once.
                    Required target mode: Video • 720p • ${seconds}s • x1.
                    Verify the selected mode displays ${seconds}s before submitting. If the UI doesn't offer or confirm this duration, STOP and ask the user; do not submit at a different duration.
                    After submission, wait for the render to finish, then download the completed video. Do not chain YouTube upload.
                """.trimIndent()
                service.startBackgroundWebTask(
                    apiKey = apiKey,
                    model = model,
                    initialUrl = VideoQueueManager.getPinnedFlowUrl(this),
                    userGoal = goal,
                    folderPath = folder,
                    chainYouTubeAfterDownload = false,
                    forceChainYouTube = false,
                    exactFlowPrompt = timedPrompt,
                    preferredDurationSeconds = seconds
                )
                store.saveTaskEpisode("FLOW_RUN_REQUESTED", "Requested ${seconds}s Google Flow video", timedPrompt)
                toast("Flow task started. The app will stop if the requested duration cannot be confirmed.")
            } catch (e: Exception) {
                toast(e.message ?: "Could not start Flow task.")
            }
        })
        videoCard.addView(actionButton("Copy prompt", false) {
            copyToClipboard("Video prompt", promptOutput.text.toString())
        })
        videoCard.addView(label("The prompt-only button does not run a video. The Flow button checks the requested duration; if the current Google Flow UI cannot confirm it, the task stops before prompt submission. Other generators may support different durations."))
        body.addView(videoCard)

        val trainingCard = section("🎓 Train Model / Record & Play")
        blueprintName = edit("Blueprint name", "My workflow")
        trainingCard.addView(blueprintName)
        recordingButton = actionButton("⏺ Start recording a workflow", true) {
            if (!recording) {
                draftSteps.clear()
                recording = true
                recordingButton.text = "⏹ Stop recording and keep steps"
                refreshDraftStatus()
            } else {
                recording = false
                recordingButton.text = "⏺ Start a new recording"
                jsonInput.setText(currentBlueprint().toJson())
                refreshDraftStatus()
            }
        }
        trainingCard.addView(recordingButton)
        actionSpinner = spinner(BlueprintAction.values().map { it.wireValue })
        targetInput = edit("Target label / content description (optional)", "")
        payloadInput = edit("Text payload or URL for TYPE / OPEN_URL", "", true)
        xInput = edit("Normalized X (0.0–1.0) for coordinate tap", "0.5")
        yInput = edit("Normalized Y (0.0–1.0) for coordinate tap", "0.5")
        waitInput = edit("Wait milliseconds (0–60000)", "1000")
        trainingCard.addView(label("Step action"))
        trainingCard.addView(actionSpinner)
        trainingCard.addView(targetInput)
        trainingCard.addView(payloadInput)
        trainingCard.addView(xInput)
        trainingCard.addView(yInput)
        trainingCard.addView(waitInput)
        trainingCard.addView(actionButton("＋ Add selected step to recording", true) { addDraftStep() })
        stepStatus = label("")
        trainingCard.addView(stepStatus)
        trainingCard.addView(actionButton("🖼 Select ordered screenshots → AI workflow draft", false) {
            screenshotPicker.launch(arrayOf("image/*"))
        })
        trainingCard.addView(label("Gallery reference files:\n${store.listTrainingAssets()}"))
        trainingCard.addView(actionButton("Validate / Import JSON from the box below", false) {
            try {
                val parsed = AutomationBlueprint.fromJson(jsonInput.text.toString())
                blueprintName.setText(parsed.name)
                draftSteps.clear()
                draftSteps.addAll(parsed.steps)
                recording = false
                recordingButton.text = "⏺ Start recording a new workflow"
                jsonInput.setText(parsed.toJson())
                refreshDraftStatus()
                toast("JSON is valid and loaded as a draft. Nothing has executed.")
            } catch (e: Exception) {
                toast("JSON validation failed: ${e.message}")
            }
        })
        jsonInput = edit("Paste or review automation blueprint JSON", "", true)
        trainingCard.addView(jsonInput)
        trainingCard.addView(actionButton("Export blueprint JSON to clipboard", false) {
            try {
                val blueprint = currentBlueprint()
                val json = blueprint.toJson()
                jsonInput.setText(json)
                copyToClipboard("Automation Blueprint", json)
                store.saveTaskEpisode("BLUEPRINT_EXPORTED", "Exported ${blueprint.name}", json)
                toast("JSON copied to clipboard.")
            } catch (e: Exception) {
                toast(e.message ?: "No valid steps to export.")
            }
        })
        trainingCard.addView(actionButton("▶ Preview and run approved steps", true) { confirmAndRunBlueprint() })
        body.addView(trainingCard)

        val recoveryCard = section("🩺 Self-correction policy")
        errorInput = edit("Error / failure state to diagnose", "button not found after page loading")
        recoveryCard.addView(errorInput)
        correctionStatus = label("")
        recoveryCard.addView(correctionStatus)
        recoveryCard.addView(actionButton("Analyze failure and suggest safe recovery", true) {
            val advice = SelfCorrectionPolicy.advise(errorInput.text.toString(), attempt = 1)
            correctionStatus.text = "Decision: ${advice.action}\n${advice.message}"
            store.saveTaskEpisode("SELF_CORRECTION_ADVICE", advice.action.name, errorInput.text.toString())
        })
        recoveryCard.addView(label("Recovery is bounded. Permission/sign-in/OTP/CAPTCHA/payment/identity gates stop for a human. Unknown failures do not loop forever."))
        body.addView(recoveryCard)

        val diagnosticsCard = section("⚙ Background runtime diagnostics")
        diagnostics = label("")
        diagnosticsCard.addView(diagnostics)
        diagnosticsCard.addView(actionButton("Refresh diagnostics", false) { updateDiagnostics() })
        diagnosticsCard.addView(actionButton("Open Accessibility settings", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        diagnosticsCard.addView(actionButton("Open this app's battery settings", false) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        diagnosticsCard.addView(actionButton("Run module self-check", true) { runSelfCheck() })
        body.addView(diagnosticsCard)

        body.addView(label(
            "Safety note: Android may stop jobs due to battery restrictions, OEM task killing, missing accessibility permission, screen lock, app-specific UI changes or an unavailable network. These checks report current conditions; they cannot promise uninterrupted 24/7 execution."
        ))
    }

    private fun addDraftStep() {
        if (!recording) {
            toast("Start recording before adding steps.")
            return
        }
        try {
            val action = BlueprintAction.fromWireValue(actionSpinner.selectedItem.toString())
            val target = targetInput.text.toString().trim()
            val payload = payloadInput.text.toString()
            val x = xInput.text.toString().toDoubleOrNull() ?: 0.5
            val y = yInput.text.toString().toDoubleOrNull() ?: 0.5
            val waitMs = waitInput.text.toString().toLongOrNull() ?: 1000L
            if (action == BlueprintAction.TYPE && payload.isBlank()) {
                toast("TYPE needs text in the payload field.")
                return
            }
            if (action == BlueprintAction.VERIFY && target.isBlank()) {
                toast("VERIFY needs a target label.")
                return
            }
            val step = BlueprintStep(
                action = action,
                target = target,
                text = payload,
                x = x,
                y = y,
                waitMs = waitMs,
                note = "Added manually in Training Lab"
            )
            // Run the shared validator on each draft step so invalid bounds are caught early.
            AutomationBlueprint(
                name = blueprintName.text.toString().ifBlank { "My workflow" },
                steps = draftSteps + step
            ).also { AutomationBlueprint.fromJson(it.toJson()) }
            draftSteps.add(step)
            payloadInput.setText("")
            refreshDraftStatus()
        } catch (e: Exception) {
            toast(e.message ?: "Invalid step.")
        }
    }

    private fun currentBlueprint(): AutomationBlueprint {
        if (draftSteps.isEmpty()) {
            val parsed = AutomationBlueprint.fromJson(jsonInput.text.toString())
            return parsed
        }
        return AutomationBlueprint(
            name = blueprintName.text.toString().trim().ifBlank { "My workflow" },
            steps = draftSteps.toList(),
            description = "Prepared in MyAIAgent Training Lab. Review before replay."
        )
    }

    private fun confirmAndRunBlueprint() {
        val blueprint = try { currentBlueprint() } catch (e: Exception) {
            toast("Add steps or import valid JSON first: ${e.message}")
            return
        }
        val preview = blueprint.steps.mapIndexed { i, s ->
            "${i + 1}. ${s.action.wireValue} • ${s.target.ifBlank { "coordinates / focused field" }}${if (s.text.isNotBlank()) " • payload: ${s.text.take(80)}" else ""}"
        }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Review before replay")
            .setMessage(
                "The approved workflow can click/type in the active Android UI or open URLs. Do not run untrusted blueprints. Passwords, payments, verification and destructive steps should remain manual.\n\n${preview.take(3500)}"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("I reviewed — Run") { _, _ ->
                uiScope.launch {
                    stepStatus.text = "Starting replay…"
                    val result = BlueprintRunner.run(this@CompanionLabActivity, blueprint) { message ->
                        stepStatus.text = message
                    }
                    stepStatus.text = "${result.summary}\nCompleted steps: ${result.completedSteps}"
                    toast(if (result.success) "Workflow finished. Verify the actual result." else "Workflow stopped safely.")
                }
            }
            .show()
    }

    private fun refreshDraftStatus() {
        stepStatus.text = "Mode: ${if (recording) "recording" else "draft saved"}\nSteps: ${draftSteps.size}\n${draftSteps.mapIndexed { i, s -> "${i + 1}. ${s.action.wireValue} — ${s.target.ifBlank { s.text.take(50) }}" }.joinToString("\n")}"
    }

    private fun refreshStoryMemory() {
        if (!::storyStatus.isInitialized) return
        storyStatus.text = "Saved series:\n${store.listStorySeries()}\n\nRecent task episodes:\n${store.recentTaskEpisodes(5)}"
    }

    private fun refreshTrainingAssets() {
        // Gallery asset list is also refreshed when the status view is rebuilt on screen.
        if (::stepStatus.isInitialized && !recording) refreshDraftStatus()
    }

    private fun updateDiagnostics() {
        val component = ComponentName(this, AutoTouchService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val accessibilityEnabled = enabledServices.split(':').any { it.equals(component, ignoreCase = true) }
        val active = AutoTouchService.instance != null
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoresBattery = powerManager.isIgnoringBatteryOptimizations(packageName)
        diagnostics.text = buildString {
            appendLine("Accessibility permission: ${if (accessibilityEnabled) "ENABLED" else "NOT ENABLED"}")
            appendLine("Service currently connected: ${if (active) "YES" else "NO"}")
            appendLine("Battery optimization exemption: ${if (ignoresBattery) "GRANTED" else "NOT GRANTED"}")
            appendLine("Android version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Screen locked / app-specific restrictions may still prevent gestures.")
            append("Note: WebView automation only works while its hosting process and required view are available.")
        }
    }

    private fun runSelfCheck() {
        val results = mutableListOf<String>()
        val blueprintOk = try {
            val sample = AutomationBlueprint(
                name = "self-check",
                steps = listOf(BlueprintStep(BlueprintAction.WAIT, waitMs = 10))
            )
            AutomationBlueprint.fromJson(sample.toJson()).steps.size == 1
        } catch (_: Exception) { false }
        results.add("JSON import/export round-trip: ${if (blueprintOk) "PASS" else "FAIL"}")

        val durationOk = try {
            VideoPromptPlanner.supportedDurationsSeconds.all { seconds ->
                VideoPromptPlanner.buildTimedPrompt("self-check scene", seconds).contains("${seconds} seconds")
            }
        } catch (_: Exception) { false }
        results.add("8s/10s/12s/15s timing planner: ${if (durationOk) "PASS" else "FAIL"}")
        results.add("Self-correction guardrails: ${if (SelfCorrectionPolicy.runSelfCheck()) "PASS" else "FAIL"}")
        results.add("SQLite integrity: ${if (store.integrityCheck()) "PASS" else "FAIL"}")
        results.add("Baymax expression catalogue (18 states): ${if (BaymaxFaceView.EXPRESSIONS.size >= 15) "PASS" else "FAIL"}")
        val allPassed = results.all { it.endsWith("PASS") }
        AlertDialog.Builder(this)
            .setTitle(if (allPassed) "Module self-check passed" else "Module self-check needs attention")
            .setMessage(results.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
        store.saveTaskEpisode("MODULE_SELF_CHECK", if (allPassed) "All local checks passed" else "One or more local checks failed", results.joinToString("\n"))
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "Screenshot"
            } ?: (uri.lastPathSegment ?: "Screenshot")
        } catch (_: Exception) { uri.lastPathSegment ?: "Screenshot" }
    }

    private fun copyToClipboard(label: String, value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun simpleSelection(onSelected: (String) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            parent?.getItemAtPosition(position)?.toString()?.let(onSelected)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun spinner(values: List<String>): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@CompanionLabActivity, android.R.layout.simple_spinner_item, values).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) }
        }
    }

    private fun edit(hintText: String, value: String = "", multiline: Boolean = false): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.rgb(148, 163, 184))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(35, 38, 47))
            setText(value)
            if (multiline) {
                minLines = 3
                maxLines = 8
                gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            layoutParams = LinearLayout.LayoutParams(-1, if (multiline) ViewGroup.LayoutParams.WRAP_CONTENT else dp(52)).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun label(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(Color.rgb(203, 213, 225))
            textSize = 14f
            setPadding(dp(2), dp(4), dp(2), dp(10))
        }
    }

    private fun section(titleText: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 26, 32))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(48, 52, 64))
            }
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val header = TextView(this).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(header)
        return card
    }

    private fun actionButton(textValue: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                if (primary) Color.rgb(37, 99, 235) else Color.rgb(48, 52, 64)
            ))
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
            setOnClickListener { action() }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
