package com.auradroid.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
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
    val modelUsed: String,
    val ymin: Float = 0f,
    val xmin: Float = 0f,
    val ymax: Float = 0f,
    val xmax: Float = 0f
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

data class TaskMicroStep(
    val title: String,
    val goal: String,
    val verificationHint: String
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

    private const val MODEL_HEALTH_PREFS = "auradroid_model_health_prefs"
    private const val KEY_DYNAMIC_PERMANENT_BLOCKS = "permanently_blocked_models"
    private const val KEY_PREFERRED_WORKING_MODEL = "preferredWorkingModel"
    private const val KEY_HEALTH_API_KEY_FINGERPRINT = "api_key_fingerprint"
    private const val MODEL_LOG_TAG = "AuraDroidModelRouter"
    private const val QUOTA_COOLDOWN_MS = 30 * 60 * 1000L
    private const val SERVER_COOLDOWN_MS = 60 * 60 * 1000L
    private const val MODEL_HEALTH_ACTIVE = "ACTIVE"
    private const val MODEL_HEALTH_COOLDOWN = "COOLDOWN"
    private const val MODEL_HEALTH_BLOCKED = "BLOCKED"

    private var modelRouterLogListener: ((String) -> Unit)? = null
    private val modelRouterMainHandler by lazy { Handler(Looper.getMainLooper()) }

    // User-requested hard firewall. This check runs before every network request,
    // including user-configured model names and aliases.
    private val PAID_MODELS_BLOCKLIST = setOf(
        "gemini-4-argon",
        "gemini-3.6-flash",
        "gemini-3-flash-preview",
        "deep-research-preview-04-2026",
        "deep-research-max-preview-04-2026",
        "antigravity-preview-09-2026",
        "gemini-nano-banana-2.1",
        "gemini-3.1-flash-image",
        "gemini-3-pro-image",
        "gemini-2.5-flash-image",
        "gemini-omni-1.1-flash",
        "gemini-omni-flash-preview",
        "veo-3.1-generate-preview",
        "veo-3.1-fast-generate-preview",
        "veo-3.1-lite-generate-preview",
        "lyria-3.5",
        "lyria-3-pro-preview",
        "lyria-3-clip-preview"
    )

    // Google's current pricing page lists this image model and Pro TTS preview
    // without a Standard free tier. Keep them unavailable in strict free-only mode.
    private val FREE_TIER_UNAVAILABLE_MODELS = setOf(
        "gemini-3.1-flash-lite-image",
        "gemini-2.5-pro-preview-tts"
    )

    private val FREE_AGENT_MODEL_LADDER = listOf(
        "gemini-2.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-robotics-er-2-preview",
        "gemini-3.1-pro-preview",
        "gemini-pro-latest",
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it"
    )

    private val HIGH_BRAIN_VISION_MODEL_LADDER = listOf(
        "gemini-3.8-flash",
        "gemini-robotics-er-2-preview",
        "gemini-3.1-pro-preview",
        "gemini-3.5-flash-lite",
        "gemini-2.5-flash"
    )

    private val IMAGE_GEN_MODEL_LADDER = listOf(
        "gemini-3.1-flash-lite-image"
    )

    private val TTS_MODEL_LADDER = listOf(
        "gemini-3.8-flash-lite-tts",
        "gemini-3.8-flash-tts",
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts"
    )

    private val AUDIO_TRANSCRIBE_MODEL_LADDER = listOf(
        "gemini-3.5-transcribe",
        "gemini-3.5-transcribe-live"
    )

    private val FREE_MODEL_ALLOWLIST: Set<String> =
        (FREE_AGENT_MODEL_LADDER +
            HIGH_BRAIN_VISION_MODEL_LADDER +
            IMAGE_GEN_MODEL_LADDER +
            TTS_MODEL_LADDER +
            AUDIO_TRANSCRIBE_MODEL_LADDER).toSet()

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

    fun setModelRouterLogListener(listener: ((String) -> Unit)?) {
        modelRouterLogListener = listener
    }

    private fun healthPrefs(context: Context) =
        context.getSharedPreferences(MODEL_HEALTH_PREFS, Context.MODE_PRIVATE)

    private fun modelPreferenceKey(prefix: String, modelId: String): String =
        prefix + modelId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun preferredKey(taskType: String): String {
        val normalized = normalizeTaskType(taskType)
        return if (normalized == "AGENT") {
            KEY_PREFERRED_WORKING_MODEL
        } else {
            "${KEY_PREFERRED_WORKING_MODEL}_${normalized.lowercase(Locale.US)}"
        }
    }

    private fun routerLog(context: Context, message: String) {
        Log.i(MODEL_LOG_TAG, message)
        val listener = modelRouterLogListener
        if (listener != null) {
            modelRouterMainHandler.post {
                try { listener.invoke(message) } catch (_: Exception) {}
            }
        }
    }

    private fun modelKeyFingerprint(apiKey: String): String =
        apiKey.hashCode().toString(16)

    private fun ensureApiKeyHealthScope(context: Context, apiKey: String) {
        if (apiKey.isBlank()) return
        val prefs = healthPrefs(context)
        val fingerprint = modelKeyFingerprint(apiKey)
        val saved = prefs.getString(KEY_HEALTH_API_KEY_FINGERPRINT, null)
        if (saved == null) {
            prefs.edit().putString(KEY_HEALTH_API_KEY_FINGERPRINT, fingerprint).apply()
        } else if (saved != fingerprint) {
            resetBlockedModels(context)
            prefs.edit().putString(KEY_HEALTH_API_KEY_FINGERPRINT, fingerprint).apply()
            routerLog(context, "[ModelBlocker] API key changed. Dynamic model health blocks were reset.")
        }
    }

    fun getApiKey(context: Context): String {
        return (prefs(context).getString(KEY_API_KEY, "") ?: "").trim()
    }

    fun setApiKey(context: Context, apiKey: String) {
        val cleanKey = apiKey.trim()
        val oldKey = getApiKey(context)
        prefs(context).edit().putString(KEY_API_KEY, cleanKey).apply()
        if (oldKey != cleanKey) {
            resetBlockedModels(context)
            healthPrefs(context).edit()
                .putString(KEY_HEALTH_API_KEY_FINGERPRINT, modelKeyFingerprint(cleanKey))
                .apply()
            routerLog(context, "[ModelBlocker] API key changed. Model cooldowns, strikes, preferences, and dynamic blocks were reset.")
        }
    }

    fun getFastModel(context: Context): String {
        val saved = (prefs(context).getString(KEY_FAST_MODEL, "gemini-2.5-flash") ?: "").trim()
        return if (saved.isBlank()) "gemini-2.5-flash" else saved
    }

    fun setFastModel(context: Context, modelName: String) {
        prefs(context).edit().putString(KEY_FAST_MODEL, modelName.trim()).apply()
        if (!isModelEligibleForFreeTier(modelName.trim())) {
            routerLog(context, "[ModelBlocker] Saved fast model is not eligible for free-tier calls and will be skipped: ${modelName.trim()}")
        }
    }

    fun getProModel(context: Context): String {
        val saved = (prefs(context).getString(KEY_PRO_MODEL, "gemini-3.1-pro-preview") ?: "").trim()
        return when {
            saved.isBlank() || saved.equals("gemini-3.8-pro", ignoreCase = true) -> "gemini-3.1-pro-preview"
            else -> saved
        }
    }

    fun setProModel(context: Context, modelName: String) {
        prefs(context).edit().putString(KEY_PRO_MODEL, modelName.trim()).apply()
        if (!isModelEligibleForFreeTier(modelName.trim())) {
            routerLog(context, "[ModelBlocker] Saved Pro model is not eligible for free-tier calls and will be skipped: ${modelName.trim()}")
        }
    }

    private fun normalizeTaskType(taskType: String): String {
        return when (taskType.trim().uppercase(Locale.US).replace(" ", "_")) {
            "HIGH_BRAIN_VISION", "VISION", "STUCK_UI", "COMPLEX_PLANNING" -> "HIGH_BRAIN_VISION"
            "IMAGE_GEN", "IMAGE_GENERATION" -> "IMAGE_GEN"
            "TTS_SPEECH", "TTS", "SPEECH" -> "TTS_SPEECH"
            "AUDIO_TRANSCRIBE", "TRANSCRIBE", "AUDIO_TRANSCRIPTION" -> "AUDIO_TRANSCRIBE"
            else -> "AGENT"
        }
    }

    private fun taskLadder(taskType: String): List<String> {
        return when (normalizeTaskType(taskType)) {
            "HIGH_BRAIN_VISION" -> HIGH_BRAIN_VISION_MODEL_LADDER
            "IMAGE_GEN" -> IMAGE_GEN_MODEL_LADDER
            "TTS_SPEECH" -> TTS_MODEL_LADDER
            "AUDIO_TRANSCRIBE" -> AUDIO_TRANSCRIBE_MODEL_LADDER
            else -> FREE_AGENT_MODEL_LADDER
        }
    }

    private fun dynamicallyBlockedModels(context: Context): Set<String> =
        healthPrefs(context).getStringSet(KEY_DYNAMIC_PERMANENT_BLOCKS, emptySet())?.toSet().orEmpty()

    private fun cooldownUntil(context: Context, modelId: String): Long =
        healthPrefs(context).getLong(modelPreferenceKey("cooldown_", modelId), 0L)

    private fun isModelEligibleForFreeTier(modelId: String): Boolean {
        val normalized = modelId.trim()
        return normalized.isNotBlank() &&
            normalized in FREE_MODEL_ALLOWLIST &&
            normalized !in PAID_MODELS_BLOCKLIST &&
            normalized !in FREE_TIER_UNAVAILABLE_MODELS
    }

    private fun modelStatus(context: Context, modelId: String): String {
        val now = System.currentTimeMillis()
        if (!isModelEligibleForFreeTier(modelId) || modelId in dynamicallyBlockedModels(context)) {
            return MODEL_HEALTH_BLOCKED
        }
        if (cooldownUntil(context, modelId) > now) return MODEL_HEALTH_COOLDOWN
        return MODEL_HEALTH_ACTIVE
    }

    fun getModelHealthSummary(context: Context): String {
        val allModels = (FREE_AGENT_MODEL_LADDER +
            HIGH_BRAIN_VISION_MODEL_LADDER +
            IMAGE_GEN_MODEL_LADDER +
            TTS_MODEL_LADDER +
            AUDIO_TRANSCRIBE_MODEL_LADDER +
            PAID_MODELS_BLOCKLIST).distinct()
        val now = System.currentTimeMillis()
        return buildString {
            append("[ModelBlocker] Model health at ").append(now).append('\n')
            for (modelId in allModels) {
                val status = modelStatus(context, modelId)
                val until = cooldownUntil(context, modelId)
                append(modelId).append(" [").append(status).append(']')
                if (status == MODEL_HEALTH_COOLDOWN) {
                    append(" until ").append(until)
                }
                append('\n')
            }
        }.trim()
    }

    fun logModelHealthSummary(context: Context) {
        getModelHealthSummary(context).lineSequence().forEach { routerLog(context, it) }
    }

    fun resetBlockedModels(context: Context) {
        val hp = healthPrefs(context)
        val editor = hp.edit()
        hp.all.keys.filter {
            it == KEY_DYNAMIC_PERMANENT_BLOCKS ||
                it == KEY_PREFERRED_WORKING_MODEL ||
                it.startsWith("${KEY_PREFERRED_WORKING_MODEL}_") ||
                it.startsWith("cooldown_") ||
                it.startsWith("strike_")
        }.forEach { editor.remove(it) }
        editor.apply()
        routerLog(context, "[ModelBlocker] Dynamic blocked models, cooldowns, strikes, and preferred model were reset. Paid-model firewall remains enabled.")
    }

    private fun storePermanentBlock(context: Context, modelId: String, code: Int) {
        val hp = healthPrefs(context)
        val blocked = hp.getStringSet(KEY_DYNAMIC_PERMANENT_BLOCKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blocked.add(modelId)) {
            hp.edit()
                .putStringSet(KEY_DYNAMIC_PERMANENT_BLOCKS, blocked)
                .remove(modelPreferenceKey("cooldown_", modelId))
                .apply()
        }
        routerLog(context, "[ModelBlocker] Permanently blocked non-working or paid model: $modelId (HTTP $code).")
    }

    private fun storeCooldown(context: Context, modelId: String, durationMs: Long, reason: String) {
        val until = System.currentTimeMillis() + durationMs
        healthPrefs(context).edit()
            .putLong(modelPreferenceKey("cooldown_", modelId), until)
            .apply()
        routerLog(context, "[ModelBlocker] $modelId set to COOLDOWN until $until. Reason: $reason")
    }

    private fun preferredWorkingModel(context: Context, taskType: String): String {
        val hp = healthPrefs(context)
        return hp.getString(preferredKey(taskType), "").orEmpty().trim()
    }

    private fun rememberWorkingModel(context: Context, taskType: String, modelId: String) {
        if (!isModelEligibleForFreeTier(modelId)) return
        healthPrefs(context).edit()
            .putString(preferredKey(taskType), modelId)
            .putInt(modelPreferenceKey("strike_", modelId), 0)
            .remove(modelPreferenceKey("cooldown_", modelId))
            .apply()
        routerLog(context, "[ModelBlocker] Preferred working model for $taskType is now $modelId [ACTIVE].")
    }

    private fun promoteNextWorkingModel(
        context: Context,
        taskType: String,
        models: List<String>,
        failedIndex: Int
    ) {
        val next = models.drop(failedIndex + 1).firstOrNull {
            modelStatus(context, it) == MODEL_HEALTH_ACTIVE
        } ?: return
        healthPrefs(context).edit().putString(preferredKey(taskType), next).apply()
        routerLog(context, "[ModelBlocker] Promoted next working model to preferredWorkingModel: $next [ACTIVE].")
    }

    private fun recordServerStrike(
        context: Context,
        modelId: String,
        taskType: String,
        models: List<String>,
        modelIndex: Int,
        detail: String
    ) {
        val hp = healthPrefs(context)
        val key = modelPreferenceKey("strike_", modelId)
        val strikes = hp.getInt(key, 0) + 1
        hp.edit().putInt(key, strikes).apply()
        routerLog(context, "[ModelBlocker] $modelId server strike $strikes. $detail")
        if (strikes >= 2) {
            storeCooldown(context, modelId, SERVER_COOLDOWN_MS, "Two server errors or timeouts")
            promoteNextWorkingModel(context, taskType, models, modelIndex)
        }
    }

    private fun processHttpFailure(
        context: Context,
        modelId: String,
        taskType: String,
        models: List<String>,
        modelIndex: Int,
        code: Int,
        responseBody: String
    ) {
        val lower = responseBody.lowercase(Locale.US)
        val resourceExhausted = lower.contains("resource_exhausted")
        val paidRequired = lower.contains("billing") ||
            lower.contains("paid") ||
            lower.contains("payment required") ||
            lower.contains("upgrade your plan") ||
            lower.contains("requires a paid tier") ||
            lower.contains("billing account")
        val unsupportedModel = lower.contains("model") && (
            lower.contains("not supported") ||
                lower.contains("unsupported") ||
                lower.contains("not found") ||
                lower.contains("does not exist") ||
                lower.contains("not available for this api")
            )

        when {
            code == 404 -> storePermanentBlock(context, modelId, code)
            code == 403 && paidRequired -> storePermanentBlock(context, modelId, code)
            code == 429 || resourceExhausted -> storeCooldown(context, modelId, QUOTA_COOLDOWN_MS, "HTTP $code or RESOURCE_EXHAUSTED")
            code == 400 && unsupportedModel -> storePermanentBlock(context, modelId, code)
            code == 500 || code == 503 -> recordServerStrike(
                context, modelId, taskType, models, modelIndex, "HTTP $code"
            )
            else -> routerLog(context, "[ModelBlocker] Skipping $modelId after HTTP $code. Response: ${responseBody.take(220)}")
        }
    }

    fun buildModelFallbackChain(
        context: Context,
        preferPro: Boolean,
        taskType: String = if (preferPro) "HIGH_BRAIN_VISION" else "AGENT"
    ): List<String> {
        val apiKey = getApiKey(context)
        ensureApiKeyHealthScope(context, apiKey)
        val normalizedTask = normalizeTaskType(taskType)
        val ladder = taskLadder(normalizedTask)
        // Keep the task's prescribed first-choice order until a working model is learned.
        // A learned preferred model may lead only when it belongs to that task's own ladder.
        val preferred = preferredWorkingModel(context, normalizedTask).takeIf { it in ladder }.orEmpty()
        val ordered = (listOf(preferred).filter { it.isNotBlank() } + ladder).distinct()
        val active = ordered.filter { modelId ->
            if (!isModelEligibleForFreeTier(modelId)) {
                routerLog(context, "[ModelBlocker] Refused model before network call because it is blocked or not on the free-tier allowlist: $modelId [BLOCKED].")
                false
            } else {
                val status = modelStatus(context, modelId)
                if (status != MODEL_HEALTH_ACTIVE) {
                    routerLog(context, "[ModelBlocker] Skipping $modelId [$status].")
                    false
                } else true
            }
        }
        if (active.isEmpty()) {
            routerLog(context, "[ModelBlocker] No eligible free-tier model is currently available for $normalizedTask. No model request will be sent.")
        } else {
            routerLog(context, "[ModelBlocker] Routing $normalizedTask through: ${active.joinToString(", ") { "$it [ACTIVE]" }}")
        }
        return active
    }

    suspend fun callGeminiWithFallback(
        context: Context,
        prompt: String,
        bitmaps: List<Bitmap> = emptyList(),
        preferPro: Boolean = false,
        taskType: String = if (preferPro) "HIGH_BRAIN_VISION" else "AGENT"
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Pair("", "NO_API_KEY")
        }

        ensureApiKeyHealthScope(context, apiKey)
        val normalizedTask = normalizeTaskType(taskType)
        val models = buildModelFallbackChain(context, preferPro, normalizedTask)
        if (models.isEmpty()) {
            return@withContext Pair("", "NO_FREE_MODEL_AVAILABLE")
        }

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

        for ((index, modelName) in models.withIndex()) {
            // Fail closed even if a future caller mutates a model chain or settings value.
            if (!isModelEligibleForFreeTier(modelName) ||
                modelStatus(context, modelName) != MODEL_HEALTH_ACTIVE) {
                routerLog(context, "[ModelBlocker] Final firewall skipped $modelName [BLOCKED].")
                continue
            }
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("key", apiKey)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .post(bodyString.toRequestBody(mediaType))
                    .build()

                val result = httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val parsedText = extractTextFromGeminiJson(rawBody)
                        if (parsedText.isNotBlank()) {
                            rememberWorkingModel(context, normalizedTask, modelName)
                            Pair(parsedText, modelName)
                        } else {
                            routerLog(context, "[ModelBlocker] $modelName returned no text content. Trying the next eligible model.")
                            null
                        }
                    } else {
                        processHttpFailure(
                            context, modelName, normalizedTask, models, index,
                            response.code, rawBody
                        )
                        null
                    }
                }
                if (result != null) return@withContext result
            } catch (e: SocketTimeoutException) {
                recordServerStrike(
                    context, modelName, normalizedTask, models, index,
                    "SocketTimeoutException: ${e.message ?: "timeout"}"
                )
            } catch (e: IOException) {
                routerLog(context, "[ModelBlocker] Network I/O failure for $modelName. Trying next eligible model.")
            } catch (e: Exception) {
                routerLog(context, "[ModelBlocker] Unexpected request failure for $modelName. Trying next eligible model.")
            }
        }

        routerLog(context, "[ModelBlocker] All eligible $normalizedTask model attempts exhausted.")
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
        val (rawResp, modelUsed) = callGeminiWithFallback(context, prompt, images, escalateToPro, taskType = "HIGH_BRAIN_VISION")
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
        escalateToPro: Boolean = false,
        screenWidthPx: Int = context.resources.displayMetrics.widthPixels,
        screenHeightPx: Int = context.resources.displayMetrics.heightPixels
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

Choose the single safest next UI step. If the user asked to open or switch to an app, prefer LAUNCH_APP and put the app name or exact package ID in targetNodeText. If a popup/permission dialog blocks the screen, handle it first.
Coordinate contract: actual device screenshot/screen is ${screenWidthPx} x ${screenHeightPx} pixels. For CLICK/HOLD/SWIPE use absolute pixel coordinates in this exact coordinate space; never assume 540x960 and never return a 0..1000 grid for x/y. Accessibility UI-node coordinates are already in screen pixels. Prefer the center of the visible target: cx=(left+right)/2, cy=(top+bottom)/2. For VISION_BOX_TAP only, return xmin/ymin/xmax/ymax normalized to a 0..1000 grid; the native service converts those to pixel coordinates and taps the box center.
Respond ONLY with a valid JSON object:
{
  "action": "LAUNCH_APP or CLICK or HOLD or VISION_BOX_TAP or TYPE or SWIPE or SCROLL_DOWN or SCROLL_UP or BACK or HOME or WAIT or DONE",
  "x": 0.0,
  "y": 0.0,
  "endX": 0.0,
  "endY": 0.0,
  "xmin": 0.0,
  "ymin": 0.0,
  "xmax": 1000.0,
  "ymax": 1000.0,
  "textToType": "",
  "targetNodeText": "exact visible label or package ID",
  "reason": "why this action moves closer to the goal",
  "isDone": false
}
""".trimIndent()

        val images = if (screenshot != null) listOf(screenshot) else emptyList()
        val (rawResp, modelUsed) = callGeminiWithFallback(context, prompt, images, escalateToPro, taskType = "HIGH_BRAIN_VISION")
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
                modelUsed = modelUsed,
                ymin = obj.optDouble("ymin", 0.0).toFloat().coerceIn(0f, 1000f),
                xmin = obj.optDouble("xmin", 0.0).toFloat().coerceIn(0f, 1000f),
                ymax = obj.optDouble("ymax", 0.0).toFloat().coerceIn(0f, 1000f),
                xmax = obj.optDouble("xmax", 0.0).toFloat().coerceIn(0f, 1000f)
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

    /**
     * Creates a short, observable task plan. If the model is unavailable or returns
     * malformed JSON, keep one complete goal instead of inventing unsafe intermediate actions.
     */
    suspend fun buildMicroStepPlan(
        context: Context,
        goal: String,
        uiTreeSummary: String = "",
        screenshot: Bitmap? = null
    ): List<TaskMicroStep> {
        val normalizedGoal = goal.trim().take(1200)
        if (normalizedGoal.isBlank()) return emptyList()

        val prompt = """
You are Nax Bot's Android task planner. Break the user's goal into 2-6 small, observable steps.
Only include actions needed for the request. Do not include login, purchases, sending messages,
deleting content, or publishing/posting unless explicitly requested by the user.
Each step must have a clear on-screen completion signal. Return ONLY JSON:
{"steps":[{"title":"short label","goal":"specific next subgoal","verificationHint":"visible evidence of completion"}]}
User goal: ${normalizedGoal}
Current screen accessibility summary (may be empty):
${uiTreeSummary.take(6000)}
""".trimIndent()
        val images = if (screenshot != null) listOf(screenshot) else emptyList()
        val (raw, _) = callGeminiWithFallback(context, prompt, images, preferPro = false, taskType = "HIGH_BRAIN_VISION")
        val obj = extractCleanJsonObject(raw)
        val array = obj?.optJSONArray("steps")
        if (array != null) {
            val parsed = mutableListOf<TaskMicroStep>()
            for (i in 0 until minOf(array.length(), 6)) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim().take(100)
                val stepGoal = item.optString("goal").trim().take(700)
                val hint = item.optString("verificationHint").trim().take(300)
                if (title.isNotBlank() && stepGoal.isNotBlank()) {
                    parsed.add(TaskMicroStep(title, stepGoal, hint))
                }
            }
            if (parsed.isNotEmpty()) return parsed
        }

        return listOf(
            TaskMicroStep(
                title = "Complete requested task",
                goal = normalizedGoal,
                verificationHint = "Visible evidence that the user's requested outcome is complete"
            )
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
        val (rawResp, _) = callGeminiWithFallback(context, prompt, images, preferPro = false, taskType = "HIGH_BRAIN_VISION")
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
        // Video decoding stays off the UI thread; the extracted bitmaps are recycled after use.
        val frames = withContext(Dispatchers.IO) {
            VideoQueueManager.extractRepresentativeFrames(context, videoFile)
        }
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

        try {
            val (rawResp, _) = callGeminiWithFallback(context, prompt, frames, preferPro = false, taskType = "HIGH_BRAIN_VISION")
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
        } finally {
            frames.forEach { bitmap ->
                try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Exception) {}
            }
        }
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

        val minRegex = Regex("(\\d{1,3})\\s*min(?:ute)?\\s*baad")
        val minMatch = minRegex.find(lower)
        if (minMatch != null) {
            val mins = minMatch.groupValues[1].toIntOrNull() ?: 5
            val triggerAt = System.currentTimeMillis() + mins * 60_000L
            return Pair(triggerAt, "In ${mins} minutes")
        }

        val hourRegex = Regex("(\\d{1,2})(?::(\\d{2}))?")
        val matches = hourRegex.findAll(lower).toList()
        for (m in matches) {
            var hour = m.groupValues[1].toIntOrNull() ?: continue
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val suffixText = lower.substring((m.range.last + 1).coerceAtMost(lower.length))
                .take(8).trimStart()
            val suffix = when {
                suffixText.startsWith("baje") -> "baje"
                suffixText.startsWith("am") -> "am"
                suffixText.startsWith("pm") -> "pm"
                else -> ""
            }
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
