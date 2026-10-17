package com.auradroid.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class WebAgentDecision(
    val detectedState: String,
    val actionType: String,
    val targetSelectorOrText: String,
    val inputText: String,
    val x: Float,
    val y: Float,
    val reasoning: String,
    val modelUsed: String,
    val usedProModel: Boolean
)

data class PhoneUiDecision(
    val action: String,
    val x: Float,
    val y: Float,
    val endX: Float,
    val endY: Float,
    val textToType: String,
    val targetNodeText: String,
    val reason: String,
    val isDone: Boolean,
    val modelUsed: String
)

data class PhoneTaskVerification(
    val isCompleted: Boolean,
    val confidence: Int,
    val feedback: String,
    val nextHint: String
)

data class ChatPlanResponse(
    val replyHinglish: String,
    val intentType: String,
    val targetGoal: String,
    val targetUrl: String,
    val scheduledTimeMillis: Long?,
    val scheduleLabel: String,
    val emotion: String
)

data class EpisodicStoryResult(
    val episodeNumber: Int,
    val flowVideoPrompt: String,
    val hindiVoiceDialogue: String,
    val episodeSummary: String,
    val suggestedTitleHint: String
)

object GeminiBrain {

    private const val PREFS_NAME = "auradroid_gemini_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_FAST_MODEL = "gemini_fast_model"
    private const val KEY_PRO_MODEL = "gemini_pro_model"

    const val DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT = """{
  "skill_name": "google_flow_master",
  "target_url": "https://labs.google/fx/tools/flow",
  "viewport_space": "540x960",
  "credit_lock_policy": "STRICT_VIDEO_9_16_8S_OR_10S_X1_ONLY",
  "states": {
    "STATE_POPUP_OBSTACLE": {
      "detect": ["Got it", "Accept all", "I agree", "Continue", "Dismiss", "Close"],
      "action": "CLICK_TEXT_OR_DISMISS",
      "verify_next": "STATE_LANDING_OR_COMPOSER"
    },
    "STATE_LANDING": {
      "detect": ["Create with Flow", "Get started", "Launch Flow", "New project", "Sign in"],
      "action": "CLICK_CTA_ENTER_WORKSPACE",
      "fallback_xy": [270, 520],
      "verify_next": "STATE_COMPOSER_READY"
    },
    "STATE_MODE_MISMATCH": {
      "detect": ["Nano Banana", "Image", "16:9", "x2", "x4"],
      "action": "ENFORCE_VIDEO_9_16_X1",
      "verify_next": "STATE_COMPOSER_READY"
    },
    "STATE_COMPOSER_READY": {
      "detect": ["textarea", "Generate", "Describe your video", "prompt"],
      "composer_rule": "top >= 175px",
      "action": "TYPE_PROMPT_ONCE_AND_SUBMIT",
      "fallback_xy": [270, 845],
      "verify_next": "STATE_RENDERING"
    },
    "STATE_RENDERING": {
      "detect": ["%", "Generating", "Rendering", "Queued"],
      "action": "WAIT_PATIENTLY_DO_NOT_RETYPE",
      "verify_next": "STATE_VIDEO_COMPLETED"
    },
    "STATE_VIDEO_COMPLETED": {
      "detect": ["100%", "video.mp4", "Download", "Play"],
      "play_card_xy": [92, 286],
      "action": "SNIFF_MP4_OR_4SENSE_DOWNLOAD"
    }
  }
}"""

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(35, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String {
        return (prefs(context).getString(KEY_API_KEY, "") ?: "").trim()
    }

    fun setApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getFastModel(context: Context): String {
        val saved = (prefs(context).getString(KEY_FAST_MODEL, "gemini-3.8-flash") ?: "").trim()
        return if (saved.isBlank()) "gemini-3.8-flash" else saved
    }

    fun setFastModel(context: Context, modelName: String) {
        prefs(context).edit().putString(KEY_FAST_MODEL, modelName.trim()).apply()
    }

    fun getProModel(context: Context): String {
        val saved = (prefs(context).getString(KEY_PRO_MODEL, "gemini-3.8-pro") ?: "").trim()
        return if (saved.isBlank()) "gemini-3.8-pro" else saved
    }

    fun setProModel(context: Context, modelName: String) {
        prefs(context).edit().putString(KEY_PRO_MODEL, modelName.trim()).apply()
    }

    fun buildModelFallbackChain(context: Context, preferPro: Boolean): List<String> {
        val primary = if (preferPro) getProModel(context) else getFastModel(context)
        val rotation = if (preferPro) {
            listOf(
                primary,
                "gemini-3.8-pro",
                "gemini-3.7-pro",
                "gemini-3.5-pro",
                "gemini-3.1-pro",
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "gemini-2.0-flash"
            )
        } else {
            listOf(
                primary,
                "gemini-3.8-flash",
                "gemini-3.7-flash",
                "gemini-3.5-flash",
                "gemini-3.1-flash",
                "gemini-2.5-flash",
                "gemini-2.0-flash"
            )
        }
        return rotation.distinct()
    }

    suspend fun callGeminiWithFallback(
        context: Context,
        prompt: String,
        bitmaps: List<Bitmap> = emptyList(),
        preferPro: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Pair("", "NO_API_KEY")
        }

        val models = buildModelFallbackChain(context, preferPro)
        val partsArray = JSONArray()
        partsArray.put(JSONObject().put("text", prompt))

        for (bmp in bitmaps) {
            val b64 = bitmapToBase64Jpeg(bmp)
            if (b64.isNotBlank()) {
                val inlineData = JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", b64)
                partsArray.put(JSONObject().put("inline_data", inlineData))
            }
        }

        val payloadObj = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.25)
                    .put("maxOutputTokens", 1400)
            )
        }
        val bodyString = payloadObj.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (modelName in models) {
            try {
                val url =
                    "https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent?key=${apiKey}"
                val request = Request.Builder()
                    .url(url)
                    .post(bodyString.toRequestBody(mediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val rawBody = response.body?.string() ?: ""
                        val parsedText = extractTextFromGeminiJson(rawBody)
                        if (parsedText.isNotBlank()) {
                            return@withContext Pair(parsedText, modelName)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        Pair("", "FALLBACK_EXHAUSTED")
    }

    private fun extractTextFromGeminiJson(rawResponse: String): String {
        return try {
            val root = JSONObject(rawResponse)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            }
            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractCleanJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        val startIdx = trimmed.indexOf('{')
        val endIdx = trimmed.lastIndexOf('}')
        if (startIdx >= 0 && endIdx > startIdx) {
            return try {
                JSONObject(trimmed.substring(startIdx, endIdx + 1))
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 76, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun decideNextWebAction(
        context: Context,
        goal: String,
        currentUrl: String,
        domStateSummary: String,
        detectedStateHint: String,
        blueprintJson: String,
        ragLessons: String,
        screenshot: Bitmap?,
        escalateToPro: Boolean = false
    ): WebAgentDecision {
        val prompt = """
You are Nax Bot, the cognitive web state-machine controller for AuraDroid OS.
Viewport coordinate space is 540x960.
Current Goal: ${goal}
Current URL: ${currentUrl}
Heuristic State Hint: ${detectedStateHint}

State-Machine Blueprint Guide (Guide only, verify live DOM):
${blueprintJson}

Relevant Past Self-Healing Lessons (Vector RAG):
${ragLessons}

Live DOM Snapshot:
${domStateSummary}

STRICT GOOGLE FLOW RULES:
1. If popup/cookie banner ("Got it", "Accept all", "I agree", "Continue") is visible -> state = STATE_POPUP_OBSTACLE, actionType = CLICK_TEXT, target = exact button text.
2. If mode shows "Nano Banana", "Image", "16:9", or "x2" -> state = STATE_MODE_MISMATCH, actionType = ENFORCE_FLOW_SETTINGS.
3. If composer textarea (top >= 175px) is ready and prompt not yet submitted -> state = STATE_COMPOSER_READY, actionType = TYPE_AND_SUBMIT, inputText = prompt to type.
4. If rendering percentage (0%..99%) is active -> state = STATE_RENDERING, actionType = WAIT.
5. If video is 100% completed or ready -> state = STATE_VIDEO_COMPLETED, actionType = EXTRACT_OR_PLAY_MP4, x = 92, y = 286.

Respond ONLY with a valid JSON object:
{
  "detectedState": "STATE_POPUP_OBSTACLE or STATE_LANDING or STATE_MODE_MISMATCH or STATE_COMPOSER_READY or STATE_RENDERING or STATE_VIDEO_COMPLETED",
  "actionType": "CLICK_TEXT or CLICK_XY or TYPE_AND_SUBMIT or ENFORCE_FLOW_SETTINGS or WAIT or EXTRACT_OR_PLAY_MP4 or DONE",
  "targetSelectorOrText": "button label or CSS selector",
  "inputText": "text to type if needed",
  "x": 270.0,
  "y": 540.0,
  "reasoning": "short 1-line explanation"
}
""".trimIndent()

        val images = if (screenshot != null) listOf(screenshot) else emptyList()
        val (rawResp, modelUsed) = callGeminiWithFallback(context, prompt, images, escalateToPro)
        val obj = extractCleanJsonObject(rawResp)

        if (obj != null) {
            return WebAgentDecision(
                detectedState = obj.optString("detectedState", detectedStateHint),
                actionType = obj.optString("actionType", "WAIT"),
                targetSelectorOrText = obj.optString("targetSelectorOrText", ""),
                inputText = obj.optString("inputText", ""),
                x = obj.optDouble("x", 270.0).toFloat(),
                y = obj.optDouble("y", 520.0).toFloat(),
                reasoning = obj.optString("reasoning", "AI live state verified"),
                modelUsed = modelUsed,
                usedProModel = escalateToPro
            )
        }

        return WebAgentDecision(
            detectedState = detectedStateHint,
            actionType = when (detectedStateHint) {
                "STATE_POPUP_OBSTACLE" -> "CLICK_TEXT"
                "STATE_LANDING" -> "CLICK_TEXT"
                "STATE_MODE_MISMATCH" -> "ENFORCE_FLOW_SETTINGS"
                "STATE_COMPOSER_READY" -> "TYPE_AND_SUBMIT"
                "STATE_VIDEO_COMPLETED" -> "EXTRACT_OR_PLAY_MP4"
                else -> "WAIT"
            },
            targetSelectorOrText = if (detectedStateHint == "STATE_LANDING") "Create with Flow" else "Got it",
            inputText = goal,
            x = if (detectedStateHint == "STATE_VIDEO_COMPLETED") 92f else 270f,
            y = if (detectedStateHint == "STATE_VIDEO_COMPLETED") 286f else 540f,
            reasoning = "Heuristic state-machine fallback executed (${detectedStateHint})",
            modelUsed = "heuristic-state-engine",
            usedProModel = escalateToPro
        )
    }

    suspend fun decideNextUiAction(
        context: Context,
        goal: String,
        uiTreeSummary: String,
        screenshot: Bitmap?,
        historySummary: String,
        ragLessons: String,
        escalateToPro: Boolean = false
    ): PhoneUiDecision {
        val prompt = """
You are Nax Bot controlling an Android phone via Accessibility UI Tree + Vision.
Goal: ${goal}

Recent Action History:
${historySummary}

Relevant Past RAG Fixes:
${ragLessons}

Live Accessibility UI Nodes (with screen coordinates):
${uiTreeSummary}

Choose the single safest next UI step. If a popup/permission dialog blocks the screen, dismiss or allow it first.
Respond ONLY with a valid JSON object:
{
  "action": "CLICK or TYPE or SWIPE or BACK or HOME or WAIT or DONE",
  "x": 540.0,
  "y": 1200.0,
  "endX": 540.0,
  "endY": 400.0,
  "textToType": "",
  "targetNodeText": "exact label of button/field",
  "reason": "why this action moves closer to goal",
  "isDone": false
}
""".trimIndent()

        val images = if (screenshot != null) listOf(screenshot) else emptyList()
        val (rawResp, modelUsed) = callGeminiWithFallback(context, prompt, images, escalateToPro)
        val obj = extractCleanJsonObject(rawResp)

        if (obj != null) {
            return PhoneUiDecision(
                action = obj.optString("action", "WAIT").uppercase(Locale.US),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1100.0).toFloat(),
                endX = obj.optDouble("endX", 540.0).toFloat(),
                endY = obj.optDouble("endY", 450.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                targetNodeText = obj.optString("targetNodeText", ""),
                reason = obj.optString("reason", "Cognitive UI decision"),
                isDone = obj.optBoolean("isDone", false),
                modelUsed = modelUsed
            )
        }

        return PhoneUiDecision(
            action = "WAIT",
            x = 540f,
            y = 960f,
            endX = 540f,
            endY = 400f,
            textToType = "",
            targetNodeText = "",
            reason = "Waiting for UI tree stabilization",
            isDone = false,
            modelUsed = "fallback-guard"
        )
    }

    suspend fun verifyPhoneTaskCompletion(
        context: Context,
        goal: String,
        uiTreeSummary: String,
        screenshot: Bitmap?
    ): PhoneTaskVerification {
        val prompt = """
Verify if the user's Android goal is 100% completed on the current screen.
Goal: ${goal}
Current UI Tree:
${uiTreeSummary}

Respond ONLY with JSON:
{
  "isCompleted": true,
  "confidence": 95,
  "feedback": "what was verified on screen",
  "nextHint": "if false, what step remains"
}
""".trimIndent()

        val images = if (screenshot != null) listOf(screenshot) else emptyList()
        val (rawResp, _) = callGeminiWithFallback(context, prompt, images, preferPro = false)
        val obj = extractCleanJsonObject(rawResp)
        if (obj != null) {
            return PhoneTaskVerification(
                isCompleted = obj.optBoolean("isCompleted", false),
                confidence = obj.optInt("confidence", 50),
                feedback = obj.optString("feedback", "Verified screen state"),
                nextHint = obj.optString("nextHint", "")
            )
        }
        return PhoneTaskVerification(
            isCompleted = false,
            confidence = 40,
            feedback = "Verification inconclusive, continuing observation",
            nextHint = "Verify target element state"
        )
    }

    suspend fun generateEpisodicStoryPrompt(
        context: Context,
        topic: String,
        storyMode: String,
        characterVisualLock: String,
        customTwist: String,
        durationSeconds: Int,
        episodeNumber: Int,
        previousArcSummary: String
    ): EpisodicStoryResult {
        val safeHero = VideoQueueManager.sanitizePolicySafePrompt(characterVisualLock, characterVisualLock)
        val safeTopic = VideoQueueManager.sanitizePolicySafePrompt(topic, safeHero)
        val safeTwist = if (customTwist.isBlank()) "None" else VideoQueueManager.sanitizePolicySafePrompt(customTwist, safeHero)
        val midpointSec = (durationSeconds / 2).coerceAtLeast(4)

        val prompt = """
You are an elite viral sci-fi showrunner creating Episode ${episodeNumber} (${storyMode} mode) for Google Flow 9:16 vertical video generation.
Target Duration: ${durationSeconds}s.
Character Visual Lock (Keep 100% consistent): ${safeHero}
Story Concept: ${safeTopic}
Plot Twist to Include: ${safeTwist}
Previous Episodes Memory:
${previousArcSummary}

STRICT POLICY RULES:
- Never use words: boy, girl, child, kid, minor, skin, baby, teen.
- Always describe an adult futuristic cybernetic tech explorer.
- Include timed Hindi voice dialogue matching the ${durationSeconds}s duration (0s-${midpointSec}s and ${midpointSec}s-${durationSeconds}s).

Respond ONLY with JSON:
{
  "flowVideoPrompt": "Full cinematic 9:16 vertical video prompt in English with camera movement, lighting, character lock, and timed Hindi voiceover lines",
  "hindiVoiceDialogue": "0s-${midpointSec}s: [Hindi line 1] • ${midpointSec}s-${durationSeconds}s: [Hindi cliffhanger line 2]",
  "episodeSummary": "1-sentence summary of what happened in Episode ${episodeNumber}",
  "suggestedTitleHint": "Curiosity-driven viral hook"
}
""".trimIndent()

        val (rawResp, _) = callGeminiWithFallback(context, prompt, emptyList(), preferPro = false)
        val obj = extractCleanJsonObject(rawResp)
        if (obj != null) {
            val rawFlowPrompt = obj.optString("flowVideoPrompt", "")
            val sanitizedFlowPrompt = VideoQueueManager.sanitizePolicySafePrompt(rawFlowPrompt, safeHero)
            return EpisodicStoryResult(
                episodeNumber = episodeNumber,
                flowVideoPrompt = "${sanitizedFlowPrompt} [Aspect Ratio: 9:16 Vertical, Duration: ${durationSeconds}s, Outputs: x1]",
                hindiVoiceDialogue = obj.optString(
                    "hindiVoiceDialogue",
                    "0s-${midpointSec}s: Yeh signal kisi insaan ka nahi hai! • ${midpointSec}s-${durationSeconds}s: Agle darwaze ke peeche sach chhupa hai!"
                ),
                episodeSummary = obj.optString("episodeSummary", "Episode ${episodeNumber} completed on ${safeTopic}"),
                suggestedTitleHint = obj.optString("suggestedTitleHint", "Part ${episodeNumber}: Cybernetic Mystery")
            )
        }

        val fallbackPrompt = VideoQueueManager.sanitizePolicySafePrompt(
            "Vertical 9:16 cinematic Unreal Engine 5 sci-fi scene, ${safeHero} exploring ${safeTopic}, dramatic neon volumetric lighting, smooth gimbal camera, timed Hindi voiceover 0s-${midpointSec}s and ${midpointSec}s-${durationSeconds}s, Duration ${durationSeconds}s, x1",
            safeHero
        )
        return EpisodicStoryResult(
            episodeNumber = episodeNumber,
            flowVideoPrompt = fallbackPrompt,
            hindiVoiceDialogue = "0s-${midpointSec}s: Is rahasya ka raaz ab khulega! • ${midpointSec}s-${durationSeconds}s: Part ${episodeNumber + 1} mein dekhiye aage kya hua!",
            episodeSummary = "Episode ${episodeNumber}: ${safeHero} uncovered a new clue in ${safeTopic}.",
            suggestedTitleHint = "Rahasya Part ${episodeNumber} #shorts"
        )
    }

    suspend fun generateYouTubeMetadataWithVision(
        context: Context,
        videoFile: File,
        storyTopicHint: String,
        episodeNumber: Int
    ): YouTubeSeoMetadata {
        val frames = VideoQueueManager.extractRepresentativeFrames(context, videoFile)
        val prompt = """
You are a Viral YouTube Shorts SEO Strategist.
Analyze these 2 actual HD frames extracted from the video (at 20% and 70% duration).
Story Context Hint: ${storyTopicHint} (Episode ${episodeNumber})

STRICT SEO RULES:
1. NEVER mention filenames, ".mp4", "Google Flow", or "AI generated".
2. Write a high-CTR curiosity-driven Hinglish/English viral Shorts title (max 68 chars) ending with #shorts #viral.
3. Write an engaging description with a comment hook and trending tags.

Respond ONLY with JSON:
{
  "title": "Viral hook title #shorts #viral",
  "description": "Engaging 3-line Hinglish + English description with hashtags",
  "tags": ["shorts", "viral", "scifi", "kahani", "mystery"]
}
""".trimIndent()

        val (rawResp, _) = callGeminiWithFallback(context, prompt, frames, preferPro = false)
        val obj = extractCleanJsonObject(rawResp)
        if (obj != null) {
            val title = obj.optString("title", "Aakhri Darwaze Ka Rahasya 😱 Part ${episodeNumber} #shorts #viral")
            val desc = obj.optString(
                "description",
                "Kya aapne notice kiya 5th second par kya dikha? Comment mein batayein! 🔥\n\n#shorts #viral #trending #scifi #story"
            )
            val tagsList = mutableListOf<String>()
            val arr = obj.optJSONArray("tags")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i)
                    if (t.isNotBlank()) tagsList.add(t)
                }
            }
            if (tagsList.isEmpty()) {
                tagsList.addAll(listOf("shorts", "viral", "trending", "scifistory", "hindishorts"))
            }
            return YouTubeSeoMetadata(title = title.take(95), description = desc, tags = tagsList)
        }

        return YouTubeSeoMetadata(
            title = "Is Rahasya Ne Sabko Hila Diya 😱 Part ${episodeNumber} #shorts #viral",
            description = "Part ${episodeNumber + 1} ke liye Subscribe karein aur comment mein 'PART ${episodeNumber + 1}' likhein! ⚡\n\n#shorts #viral #kahani #scifi #trending",
            tags = listOf("shorts", "viral", "trending", "scifi", "hindikahani")
        )
    }

    suspend fun chatAndBuildPlan(
        context: Context,
        userMessage: String,
        memoryFacts: String
    ): ChatPlanResponse {
        val parsedTimer = parseNaturalLanguageTimerMillis(userMessage)
        val prompt = """
You are Nax Bot (●—●), the cute Baymax-inspired autonomous AI companion inside AuraDroid OS.
Speak in friendly, confident Hinglish.
User Message: "${userMessage}"
Saved Personal Memory Facts:
${memoryFacts}

Classify intentType into one of:
- "SCHEDULE_TASK" (if user mentions a time like 'raat 11 baje', '10 min baad', 'subah 8 baje')
- "FLOW_PIPELINE" (if user wants to make a Google Flow story video or upload to YouTube)
- "WEB_TASK" (if user wants to open a website or run a browser task)
- "PHONE_TASK" (if user wants to control an Android app on the phone)
- "CHAT_ONLY" (general conversation or question)

Choose emotion from: IDLE, THINKING, WORKING, RENDERING, HAPPY, EXCITED, CONFUSED, HIGH_BRAIN, SEARCHING, DOWNLOADING, UPLOADING, SURPRISED.

Respond ONLY with JSON:
{
  "replyHinglish": "Your warm Hinglish response to Boss",
  "intentType": "CHAT_ONLY or FLOW_PIPELINE or WEB_TASK or PHONE_TASK or SCHEDULE_TASK",
  "targetGoal": "Clear actionable goal if task requested",
  "targetUrl": "https://labs.google/fx/tools/flow",
  "emotion": "HAPPY"
}
""".trimIndent()

        val (rawResp, _) = callGeminiWithFallback(context, prompt, emptyList(), preferPro = false)
        val obj = extractCleanJsonObject(rawResp)

        if (parsedTimer != null) {
            val reply = obj?.optString("replyHinglish", "")?.takeIf { it.isNotBlank() }
                ?: "Done Boss! Maine ${parsedTimer.second} ka पक्का Alarm Timer lock kar diya hai. Waqt hote hi main autonomous pipeline chala dunga!"
            val goal = obj?.optString("targetGoal", "")?.takeIf { it.isNotBlank() } ?: userMessage
            return ChatPlanResponse(
                replyHinglish = reply,
                intentType = "SCHEDULE_TASK",
                targetGoal = goal,
                targetUrl = "https://labs.google/fx/tools/flow",
                scheduledTimeMillis = parsedTimer.first,
                scheduleLabel = parsedTimer.second,
                emotion = "EXCITED"
            )
        }

        if (obj != null) {
            return ChatPlanResponse(
                replyHinglish = obj.optString("replyHinglish", "Boss, main taiyar hoon!"),
                intentType = obj.optString("intentType", "CHAT_ONLY"),
                targetGoal = obj.optString("targetGoal", userMessage),
                targetUrl = obj.optString("targetUrl", "https://labs.google/fx/tools/flow"),
                scheduledTimeMillis = null,
                scheduleLabel = "",
                emotion = obj.optString("emotion", "HAPPY")
            )
        }

        return ChatPlanResponse(
            replyHinglish = "Boss, aapka message mil gaya! Agar API key सेट hai toh main turant autonomous mode mein kaam shuru kar sakta hoon.",
            intentType = "CHAT_ONLY",
            targetGoal = userMessage,
            targetUrl = "https://labs.google/fx/tools/flow",
            scheduledTimeMillis = null,
            scheduleLabel = "",
            emotion = "IDLE"
        )
    }

    fun parseNaturalLanguageTimerMillis(text: String): Pair<Long, String>? {
        val lower = text.lowercase(Locale.US)
        val hasTimeTrigger = lower.contains("baje") or
            lower.contains("minute baad") or
            lower.contains("min baad") or
            lower.contains("timer") or
            lower.contains("schedule") or
            lower.contains("alarm")

        if (!hasTimeTrigger) return null

        val minRegex = Regex("(\\d{1,3})\\s*(minute|min)\\s*baad")
        val minMatch = minRegex.find(lower)
        if (minMatch != null) {
            val mins = minMatch.groupValues[1].toIntOrNull() ?: 5
            val triggerAt = System.currentTimeMillis() + mins * 60_000L
            return Pair(triggerAt, "In ${mins} minutes")
        }

        val hourRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(baje|am|pm)?")
        val matches = hourRegex.findAll(lower).toList()
        for (m in matches) {
            var hour = m.groupValues[1].toIntOrNull() ?: continue
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val suffix = m.groupValues[3]
            if (hour in 1..24) {
                val isNightOrEvening = lower.contains("raat") or
                    lower.contains("shaam") or
                    lower.contains("dopahar") or
                    (suffix == "pm")
                val isMorning = lower.contains("subah") or (suffix == "am")
                if (isNightOrEvening && hour < 12) {
                    hour += 12
                } else if (isMorning && hour == 12) {
                    hour = 0
                }
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                    set(Calendar.MINUTE, minute.coerceIn(0, 59))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                val label = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                return Pair(cal.timeInMillis, "Scheduled at ${label}")
            }
        }
        return null
    }

    suspend fun trainSkillFromScreenshots(
        context: Context,
        skillName: String,
        screenshots: List<Bitmap>
    ): String {
        if (screenshots.isEmpty()) return DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT
        val prompt = """
Analyze these sequential UI screenshots for skill "$skillName".
Build a cognitive State-Machine JSON Blueprint that maps each visual state, obstacle popups, target buttons, 540x960 fallback coordinates, and verification conditions.
Return ONLY valid formatted JSON.
""".trimIndent()

        val (rawResp, _) = callGeminiWithFallback(context, prompt, screenshots.take(4), preferPro = true)
        val obj = extractCleanJsonObject(rawResp)
        return obj?.toString(2) ?: DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT
    }
}
