package com.auradroid.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object WebPipelineManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val httpClient = OkHttpClient()

    private var pipelineJob: Job? = null
    @Volatile private var isRunning: Boolean = false
    @Volatile private var isFlowCaptureActive: Boolean = false
    @Volatile private var captureEpoch: Long = 0L
    @Volatile private var lastInterceptedMp4Bytes: ByteArray? = null
    private val captureDownloadInProgress = AtomicBoolean(false)

    private var onLogAppended: ((String) -> Unit)? = null
    private var onStatusUpdated: ((String, String) -> Unit)? = null

    fun setLogListener(listener: (String) -> Unit) {
        onLogAppended = listener
    }

    fun setStatusListener(listener: (String, String) -> Unit) {
        onStatusUpdated = listener
    }

    private fun log(line: String) {
        val timestamped = "[${System.currentTimeMillis() % 100000}] $line"
        mainHandler.post {
            onLogAppended?.invoke(timestamped)
        }
    }

    private fun updateStatus(status: String, emotion: String) {
        mainHandler.post {
            onStatusUpdated?.invoke(status, emotion)
            AiOverlayHud.updateStatus(status, emotion)
        }
    }

    private fun beginFlowVideoCapture() {
        captureEpoch += 1L
        lastInterceptedMp4Bytes = null
        isFlowCaptureActive = true
    }

    private fun endFlowVideoCapture() {
        isFlowCaptureActive = false
        captureEpoch += 1L
        lastInterceptedMp4Bytes = null
    }

    fun stopCurrentJob() {
        endFlowVideoCapture()
        pipelineJob?.cancel()
        pipelineJob = null
        isRunning = false
        updateStatus("Pipeline Stopped", "IDLE")
        log("[Pipeline] Stopped by user or task finish.")
    }

    fun stopEverything(context: Context?) {
        stopCurrentJob()
        AiOverlayHud.hide()
    }

    fun interceptNetworkRequestForMp4(request: WebResourceRequest): WebResourceResponse? {
        if (!isFlowCaptureActive) return null
        val urlStr = request.url.toString()
        val lower = urlStr.lowercase(Locale.US)
        val isVideoUrl = lower.contains(".mp4") or
            lower.contains("googlevideo") or
            lower.contains("storage.googleapis.com/generate") or
            lower.contains("media/video")
        if (!isVideoUrl || !(lower.startsWith("http://") || lower.startsWith("https://"))) return null

        // Only one stream may be buffered at a time on a low-memory mobile device.
        if (!captureDownloadInProgress.compareAndSet(false, true)) return null
        val requestEpoch = captureEpoch
        scope.launch(Dispatchers.IO) {
            try {
                val okReq = Request.Builder().url(urlStr).get().build()
                httpClient.newCall(okReq).execute().use { resp ->
                    if (!resp.isSuccessful || !isFlowCaptureActive || requestEpoch != captureEpoch) return@use
                    val body = resp.body ?: return@use
                    // Protect low-memory devices from unexpectedly large or endless media responses.
                    val declaredLength = body.contentLength()
                    val maxCaptureBytes = 180L * 1024L * 1024L
                    if (declaredLength > maxCaptureBytes) {
                        log("[StreamSniffer] Skipped oversized stream (${declaredLength / (1024L * 1024L)} MB); waiting for the direct video URL.")
                        return@use
                    }
                    val initialCapacity = if (declaredLength > 0L) {
                        minOf(declaredLength, 1024L * 1024L).toInt()
                    } else {
                        64 * 1024
                    }
                    val buffer = java.io.ByteArrayOutputStream(initialCapacity)
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            if (!isFlowCaptureActive || requestEpoch != captureEpoch) return@launch
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > maxCaptureBytes) {
                                log("[StreamSniffer] Aborted stream above 180 MB memory limit.")
                                return@launch
                            }
                            buffer.write(chunk, 0, read)
                        }
                    }
                    val bytes = buffer.toByteArray()
                    if (bytes.size > 50_000 && isFlowCaptureActive && requestEpoch == captureEpoch) {
                        lastInterceptedMp4Bytes = bytes
                        log("[StreamSniffer] Captured MP4 stream (${bytes.size} bytes) for the active episode.")
                    }
                }
            } catch (_: Exception) {
                // The DOM download path remains available when a signed URL cannot be fetched here.
            } finally {
                captureDownloadInProgress.set(false)
            }
        }
        return null
    }

    fun startAutonomousWebAgent(
        context: Context,
        webView: WebView,
        goal: String,
        targetUrl: String
    ) {
        endFlowVideoCapture()
        if (isRunning) {
            log("[Agent] Pipeline already running. Stop first.")
            return
        }
        isRunning = true
        updateStatus("Agent • Running", "WORKING")
        log("[Agent] Starting Autonomous Web Agent for goal: $goal")

        pipelineJob = scope.launch(Dispatchers.Main) {
            try {
                if (webView.url != targetUrl) {
                    webView.loadUrl(targetUrl)
                    delay(5000)
                }

                val skillBlueprint = AutomationReliability.loadSkillBlueprint(context, "google_flow_master")
                var currentStep = 0
                val maxSteps = 18

                while (isRunning && currentStep < maxSteps) {
                    currentStep++
                    val snap = WebActionVerifier.inspectLiveDomState(webView)
                    val ragLessons = AutomationReliability.retrieveRelevantRagLessons(context, goal, snap.heuristicState)

                    updateStatus("State: ${snap.heuristicState}", "THINKING")

                    if (snap.heuristicState == "STATE_POPUP_OBSTACLE" || snap.popupObstacleText.isNotBlank()) {
                        log("[WebAgent] Popup obstacle detected: '${snap.popupObstacleText}'. Auto-killing...")
                        WebActionVerifier.autoKillPopupObstacles(webView)
                        delay(1200)
                        continue
                    }

                    if (snap.heuristicState == "STATE_MODE_MISMATCH") {
                        log("[WebAgent] Mode mismatch detected. Enforcing Video 9:16 x1 credit-lock...")
                        WebActionVerifier.enforceGoogleFlowCreditLock(webView, 8)
                        delay(1500)
                        continue
                    }

                    val decision = GeminiBrain.decideNextWebAction(
                        context = context,
                        goal = goal,
                        currentUrl = snap.url,
                        domStateSummary = snap.interactiveSummary,
                        detectedStateHint = snap.heuristicState,
                        blueprintJson = skillBlueprint,
                        ragLessons = ragLessons,
                        screenshot = WebActionVerifier.captureWebViewBitmap(webView),
                        escalateToPro = false
                    )

                    log("[Decision] ${decision.detectedState} -> Action: ${decision.actionType} (${decision.reasoning})")

                    when (decision.actionType) {
                        "CLICK_TEXT", "CLICK_CTA_ENTER_WORKSPACE" -> {
                            val report = WebActionVerifier.execute4SenseSmartClick(
                                webView,
                                decision.targetSelectorOrText.ifBlank { "Create with Flow" },
                                decision.x,
                                decision.y
                            )
                            log("[Click] ${report.senseUsed} at (${report.targetX}, ${report.targetY}) - ${report.details}")
                            if (report.needsProModelEscalation) {
                                log("[ProEscalation] Escalating next step to High-Reasoning Pro Model.")
                            }
                        }
                        "TYPE_AND_SUBMIT", "ENFORCE_FLOW_SETTINGS" -> {
                            WebActionVerifier.enforceGoogleFlowCreditLock(webView, 8)
                            delay(800)
                            WebActionVerifier.typePromptIntoBottomComposerOnce(webView, decision.inputText.ifBlank { goal })
                            log("[Composer] Prompt typed and submitted once.")
                        }
                        "WAIT" -> {
                            updateStatus("Rendering...", "RENDERING")
                            delay(3500)
                        }
                        "EXTRACT_OR_PLAY_MP4", "SNIFF_MP4_OR_4SENSE_DOWNLOAD" -> {
                            updateStatus("Downloading MP4...", "DOWNLOADING")
                            val domMp4 = WebActionVerifier.extractMp4UrlFromDomOrReactFiber(webView)
                            var mp4Bytes = lastInterceptedMp4Bytes

                            if (mp4Bytes == null && domMp4.isNotBlank()) {
                                mp4Bytes = downloadBytesFromUrl(domMp4)
                            }

                            if (mp4Bytes == null) {
                                WebActionVerifier.execute4SenseSmartClick(webView, "Play", WebActionVerifier.FLOW_PLAY_CARD_X, WebActionVerifier.FLOW_PLAY_CARD_Y)
                                delay(2500)
                                mp4Bytes = lastInterceptedMp4Bytes ?: downloadBytesFromUrl(WebActionVerifier.extractMp4UrlFromDomOrReactFiber(webView))
                            }

                            if (mp4Bytes != null && mp4Bytes.size > 50_000L) {
                                val savedFile = VideoQueueManager.saveVideoBytesToStorage(context, mp4Bytes, "AuraDroid_Flow")
                                if (savedFile != null) {
                                    log("[Success] MP4 saved to ${savedFile.absolutePath} (${savedFile.length()} bytes)")
                                    updateStatus("MP4 Saved Successfully!", "HAPPY")
                                    AutomationReliability.recordSuccessfulLesson(context, goal, snap.heuristicState, "Successfully extracted and saved MP4 video")
                                    isRunning = false
                                    break
                                }
                            }
                            delay(2000)
                        }
                        else -> {
                            delay(2000)
                        }
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                log("[Error] Autonomous Web Agent exception: ${e.message}")
                updateStatus("Agent Error", "ERROR")
            } finally {
                isRunning = false
            }
        }
    }

    fun startFlowToYouTubePipeline(
        context: Context,
        webView: WebView,
        topic: String,
        storyMode: String,
        characterVisualLock: String,
        customTwist: String,
        durationSeconds: Int,
        episodeCount: Int,
        autoUpload: Boolean
    ) {
        if (isRunning) {
            log("[Pipeline] Pipeline already active.")
            return
        }
        endFlowVideoCapture()
        isRunning = true
        updateStatus("Flow ➔ YT Pipeline", "WORKING")
        log("[Pipeline] Starting Episodic Story Showrunner pipeline for $episodeCount episodes.")

        pipelineJob = scope.launch(Dispatchers.Main) {
            try {
                val arcState = VideoQueueManager.getStoryArcState(context)
                var currentEp = arcState.currentEpisode

                for (epIndex in 1..episodeCount) {
                    if (!isRunning) break
                    log("[Showrunner] Generating Episode $currentEp prompt via Gemini...")
                    updateStatus("Writing Ep $currentEp Prompt", "THINKING")

                    val storyResult = GeminiBrain.generateEpisodicStoryPrompt(
                        context = context,
                        topic = topic,
                        storyMode = storyMode,
                        characterVisualLock = characterVisualLock,
                        customTwist = customTwist,
                        durationSeconds = durationSeconds,
                        episodeNumber = currentEp,
                        previousArcSummary = arcState.arcHistorySummary
                    )

                    log("[Prompt] ${storyResult.flowVideoPrompt}")
                    log("[Voiceover] ${storyResult.hindiVoiceDialogue}")

                    beginFlowVideoCapture()
                    webView.loadUrl("https://labs.google/fx/tools/flow")
                    delay(6000)

                    // Execute web steps to generate video
                    var generatedOk = false
                    for (attempt in 1..12) {
                        if (!isRunning) break
                        val snap = WebActionVerifier.inspectLiveDomState(webView)
                        if (snap.heuristicState == "STATE_POPUP_OBSTACLE") {
                            WebActionVerifier.autoKillPopupObstacles(webView)
                            delay(1000)
                            continue
                        }
                        if (snap.heuristicState == "STATE_MODE_MISMATCH") {
                            WebActionVerifier.enforceGoogleFlowCreditLock(webView, durationSeconds)
                            delay(1000)
                            continue
                        }
                        if (snap.heuristicState == "STATE_COMPOSER_READY") {
                            WebActionVerifier.enforceGoogleFlowCreditLock(webView, durationSeconds)
                            delay(800)
                            WebActionVerifier.typePromptIntoBottomComposerOnce(webView, storyResult.flowVideoPrompt)
                            log("[Flow] Prompt submitted for Episode $currentEp. Waiting for render (0%..100%)...")
                            updateStatus("Rendering Ep $currentEp", "RENDERING")
                            delay(15000)
                            generatedOk = true
                            break
                        }
                        if (snap.heuristicState == "STATE_LANDING") {
                            WebActionVerifier.execute4SenseSmartClick(webView, "Create with Flow", 270f, 520f)
                            delay(3000)
                        } else {
                            delay(3000)
                        }
                    }

                    if (generatedOk) {
                        // Wait for render completion and extract MP4
                        var savedFile: File? = null
                        for (waitAttempt in 1..25) {
                            if (!isRunning) break
                            val domMp4 = WebActionVerifier.extractMp4UrlFromDomOrReactFiber(webView)
                            var mp4Bytes = lastInterceptedMp4Bytes
                            if (mp4Bytes == null && domMp4.isNotBlank()) {
                                mp4Bytes = downloadBytesFromUrl(domMp4)
                            }
                            if (mp4Bytes == null) {
                                WebActionVerifier.execute4SenseSmartClick(webView, "Play", WebActionVerifier.FLOW_PLAY_CARD_X, WebActionVerifier.FLOW_PLAY_CARD_Y)
                                delay(3000)
                                mp4Bytes = lastInterceptedMp4Bytes ?: downloadBytesFromUrl(WebActionVerifier.extractMp4UrlFromDomOrReactFiber(webView))
                            }
                            if (mp4Bytes != null && mp4Bytes.size > 50_000L) {
                                savedFile = VideoQueueManager.saveVideoBytesToStorage(context, mp4Bytes, "Ep_${currentEp}_Flow")
                                if (savedFile != null) break
                            }
                            delay(4000)
                        }

                        if (savedFile != null) {
                            log("[Success] Episode $currentEp video saved: ${savedFile.name}")
                            VideoQueueManager.recordCompletedEpisode(context, currentEp, storyResult.episodeSummary)

                        }
                    }
                    endFlowVideoCapture()
                    currentEp++
                    delay(3000)
                }
                if (isRunning && autoUpload) {
                    log("[Upload] Generated videos are queued. Opening one YouTube share/review flow; publishing is completed in YouTube by the user.")
                    runYouTubeAutoUpload(context, webView, skipFlowRouting = true)
                    updateStatus("Generation Done • YouTube Review", "WORKING")
                    log("[Pipeline] Generation finished. Review and publish the selected queued video in YouTube; confirm upload in AuraDroid afterwards.")
                } else {
                    updateStatus("Pipeline Complete!", "HAPPY")
                    log("[Pipeline] Video generation loop completed. Review the queue for saved videos.")
                }
            } catch (e: Exception) {
                log("[Error] Pipeline exception: ${e.message}")
                updateStatus("Pipeline Error", "ERROR")
            } finally {
                endFlowVideoCapture()
                isRunning = false
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun runYouTubeAutoUpload(
        context: Context,
        webView: WebView,
        skipFlowRouting: Boolean = false
    ): Unit = withContext(Dispatchers.Main) {
        // Do not mark queued videos uploaded simply because metadata was generated.
        // Native Android share flow hands the video and Vision SEO metadata to YouTube;
        // the user must publish there and confirm completion in AuraDroid.
        updateStatus("Preparing YouTube Upload", "WORKING")
        val result = try {
            NativeYouTubeUploader.startNextUpload(context)
        } catch (e: Exception) {
            NativeUploadStartResult(
                started = false,
                message = "YouTube upload handoff failed: ${e.message ?: "unknown error"}"
            )
        }
        log("[Upload] ${result.message}")
        updateStatus(
            if (result.started) "YouTube Review Required" else "Upload Not Started",
            if (result.started) "WORKING" else "ERROR"
        )
    }

    private suspend fun downloadBytesFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return@withContext resp.body?.bytes()
                }
            }
        } catch (_: Exception) {
        }
        return@withContext null
    }
}