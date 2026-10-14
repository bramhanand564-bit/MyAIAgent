package com.auradroid.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class VideoMetadata(
    val title: String,
    val description: String
)

data class ChatPlanResponse(
    val replyText: String,
    val planGoal: String,
    val planSteps: String,
    val suggestedMode: String, // "WEB" or "PHONE" or "YOUTUBE"
    val targetUrl: String
)

data class AgentDecision(
    val action: String,      // CLICK_NODE, CLICK_XY, TYPE, SCROLL, BACK, WAIT, OPEN_APP, DONE
    val nodeIndex: Int = -1,
    val x: Float = 0f,
    val y: Float = 0f,
    val textToType: String = "",
    val appPackageOrSetting: String = "",
    val reason: String = ""
)

data class WebAgentDecision(
    val action: String,      // CLICK_EL, TYPE_EL, SCROLL, LOAD_URL, WAIT, DOWNLOAD_URL, CHAIN_YOUTUBE, DONE
    val elementIndex: Int = -1,
    val textToType: String = "",
    val url: String = "",
    val reason: String = ""
)

object GeminiBrain {

    private var cachedWorkingModel: String? = null

    private fun fetchAvailableFlashModels(apiKey: String): List<String> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val modelsArr = JSONObject(body).optJSONArray("models") ?: return emptyList()

            val discovered = mutableListOf<String>()
            for (i in 0 until modelsArr.length()) {
                val item = modelsArr.getJSONObject(i)
                val rawName = item.optString("name", "").removePrefix("models/")
                val methods = item.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent") && rawName.contains("gemini", true)) {
                    discovered.add(rawName)
                }
            }
            discovered.sortedByDescending { it.contains("flash", true) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun callGeminiApi(
        apiKey: String,
        userModel: String,
        prompt: String,
        base64ImageJpeg: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw Exception("Gemini API Key missing! Settings tab mein jaakar API Key daalein.")
        val requested = userModel.trim().removePrefix("models/").ifEmpty { "gemini-2.5-flash" }

        val candidateSet = linkedSetOf<String>()
        cachedWorkingModel?.let { candidateSet.add(it) }
        candidateSet.add(requested)
        candidateSet.add("gemini-2.5-flash")
        candidateSet.add("gemini-flash-latest")
        candidateSet.add("gemini-3.1-flash-lite-preview")
        candidateSet.add("gemini-2.5-flash-lite")
        candidateSet.add("gemini-2.0-flash")

        var lastError = ""

        suspend fun tryModels(models: Collection<String>): String? {
            for (modelName in models) {
                for (attempt in 1..2) {
                    try {
                        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${apiKey.trim()}"
                        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 25000
                            readTimeout = 25000
                            doOutput = true
                        }

                        val partsArray = JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                            if (!base64ImageJpeg.isNullOrBlank()) {
                                put(JSONObject().put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64ImageJpeg)
                                }))
                            }
                        }

                        val payload = JSONObject().apply {
                            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                            put("generationConfig", JSONObject().apply {
                                put("temperature", 0.25)
                                put("responseMimeType", "application/json")
                            })
                        }

                        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                        val code = conn.responseCode
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

                        if (code in 200..299) {
                            val root = JSONObject(responseText)
                            val text = root.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                            cachedWorkingModel = modelName
                            return text.trim()
                        } else {
                            lastError = "HTTP $code ($modelName)"
                            if (code == 503 || code == 429) {
                                delay(2000L * attempt)
                                continue
                            } else {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "Network error"
                        delay(1000L)
                    }
                }
            }
            return null
        }

        tryModels(candidateSet)?.let { return@withContext it }

        val liveModels = fetchAvailableFlashModels(apiKey)
        if (liveModels.isNotEmpty()) {
            tryModels(liveModels)?.let { return@withContext it }
        }

        throw Exception("Gemini API Busy/Error ($lastError)")
    }

    /**
     * TAB 1: Interactive AI Chat & Step-by-Step Task Planner
     */
    suspend fun chatAndBuildPlan(
        apiKey: String,
        model: String,
        userMessage: String,
        chatHistory: String,
        memoryVault: String
    ): ChatPlanResponse {
        val prompt = """
            You are AuraDroid Companion Bot, a friendly, smart Hindi/Hinglish speaking Autonomous Android & Web AI Assistant.
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT CHAT CONVERSATION:
            ${chatHistory.takeLast(2500)}
            
            NEW USER MESSAGE:
            "$userMessage"
            
            Your job:
            1. Talk naturally to the user in clear Hindi/Hinglish. Answer questions or clarify any confusion.
            2. Create a numbered execution plan ONLY when the task is sufficiently specific to act safely.
            3. IMPORTANT: Never invent missing user choices or content. Before changing a profile picture/DP, ask which exact image to use if it has not been selected or clearly identified. Before sending a message, ask for the exact message text when it is missing; do not invent a message or infer one from unrelated chat history. If a recipient/contact is ambiguous, ask which one. Apply the same rule to unspecified settings, files, uploads, posts, deletion, purchases, and other external side effects.
            4. If clarification is required, ask one concise Hindi/Hinglish question in replyText, set planGoal to an EMPTY STRING, and set planSteps to a clear note that execution is paused until the user answers. Do not produce executable steps for a clarification-needed request. This blank planGoal intentionally prevents the Execute button from running an assumed task.
            5. Do not claim the task is completed or that an action occurred unless the available information supports it.
            6. Decide the best execution mode:
               - "WEB": If the task involves websites, filling web forms, checking daily free video credit limits, generating/downloading AI videos from a website, or GitHub web actions.
               - "YOUTUBE": If the task is uploading pending folder videos to YouTube.
               - "PHONE": If the task involves fixing phone settings (Battery, Wi-Fi, Display, Storage) or controlling native Android apps.
            7. If a website is mentioned or needed, provide its full URL in targetUrl (or "https://www.google.com" if searching).

            Respond strictly in valid JSON:
            {
              "replyText": "Friendly Hindi/Hinglish reply explaining how you understood the task",
              "planGoal": "Complete concise instruction of the overall goal for the execution engine",
              "planSteps": "1. Step one...\n2. Step two...\n3. Step three...",
              "suggestedMode": "WEB",
              "targetUrl": "https://..."
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            ChatPlanResponse(
                replyText = obj.optString("replyText", "Maine aapka kaam samajh liya hai aur plan taiyar kar diya hai."),
                planGoal = obj.optString("planGoal", userMessage),
                planSteps = obj.optString("planSteps", "1. Task analyze karein\n2. Action execute karein\n3. Report dein"),
                suggestedMode = obj.optString("suggestedMode", "WEB").uppercase(),
                targetUrl = obj.optString("targetUrl", "")
            )
        } catch (e: Exception) {
            ChatPlanResponse(
                replyText = "⚠️ AI Plan banane mein dikkat aayi: ${e.message}",
                planGoal = userMessage,
                planSteps = "1. Execute: $userMessage",
                suggestedMode = "WEB",
                targetUrl = ""
            )
        }
    }

    /**
     * TAB 3: YouTube Viral Title & Description Generator
     */
    suspend fun generateYouTubeMetadata(
        apiKey: String,
        model: String,
        fileName: String,
        userInstruction: String
    ): VideoMetadata {
        val prompt = """
            You are an expert YouTube Shorts & Video SEO Manager.
            Video file name: "$fileName"
            User instruction: "$userInstruction"
            
            Generate a catchy viral Title/Caption (max 85 chars, include 2 hashtags like #shorts #viral) and a full SEO Description with hashtags.
            Respond strictly in valid JSON:
            {
              "title": "Viral Title #shorts #viral",
              "description": "Full SEO description with #hashtags"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            VideoMetadata(
                title = obj.optString("title", fileName.substringBeforeLast(".")).take(95),
                description = obj.optString("description", "#shorts #viral #trending")
            )
        } catch (e: Exception) {
            val baseName = fileName.substringBeforeLast(".").replace("_", " ").replace("-", " ")
            VideoMetadata(
                title = "$baseName #shorts #viral",
                description = "$baseName\n\n#shorts #trending #viral"
            )
        }
    }

    /**
     * TAB 2: Background Web Studio Agent Brain (Forms, Free Video Limits, AI Video Download, GitHub)
     */
    suspend fun decideNextWebAction(
        apiKey: String,
        model: String,
        goal: String,
        pageUrl: String,
        pageTextAndElements: String,
        history: List<String>,
        memoryVault: String,
        screenshotBase64: String? = null
    ): WebAgentDecision {
        val prompt = """
            You are an Autonomous Background Web Browser Agent running inside AuraDroid OS.
            OVERALL GOAL: $goal
            CURRENT URL: $pageUrl
            
            USER'S PERSONAL MEMORY VAULT (Use this data for filling forms, emails, topics, GitHub urls):
            $memoryVault
            
            RECENT ACTIONS TAKEN:
            ${history.takeLast(8).joinToString("\n")}
            
            CURRENT WEBPAGE INTERACTIVE ELEMENTS & VISIBLE TEXT:
            $pageTextAndElements
            
            Rules:
            - If checking daily free video credit/limit, inspect the page text and proceed if credits exist.
            - If generating a story/film video, type a detailed prompt into the prompt input/textarea and click Generate/Create.
            - If the webpage says "Generating...", "Processing...", or "Loading...", choose action "WAIT" so we wait patiently until the video is ready!
            - If a video or file is ready to download (or you see a .mp4 link or Download button), click the Download button or return "DOWNLOAD_URL".
            - If the goal also asks to upload the downloaded video to YouTube after downloading, return "CHAIN_YOUTUBE" once download is triggered.
            
            Available actions:
            - "CLICK_EL": Click interactive webpage element by its elementIndex.
            - "TYPE_EL": Type/fill text into input/textarea at elementIndex (provide textToType).
            - "SCROLL": Scroll down the webpage to see more fields/buttons.
            - "LOAD_URL": Navigate directly to a specific URL (provide url).
            - "DOWNLOAD_URL": Direct download of a video/file URL found on page (provide url).
            - "WAIT": Wait 5 seconds (use when AI video is generating or page is loading).
            - "CHAIN_YOUTUBE": Video is downloaded, now launch YouTube Auto-Upload pipeline!
            - "DONE": Goal is 100% completed.
            
            Respond strictly in valid JSON:
            {
              "action": "CLICK_EL",
              "elementIndex": 2,
              "textToType": "",
              "url": "",
              "reason": "Short clear status message for the user"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt, screenshotBase64)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            WebAgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                elementIndex = obj.optInt("elementIndex", -1),
                textToType = obj.optString("textToType", ""),
                url = obj.optString("url", ""),
                reason = obj.optString("reason", "Analyzing webpage...")
            )
        } catch (e: Exception) {
            WebAgentDecision(action = "WAIT", reason = "Retrying after network pause...")
        }
    }

    /**
     * TAB 4 & UNIVERSAL: Phone Screen & Settings Doctor Brain (Supports Screenshot Vision!)
     */
    suspend fun decideNextUiAction(
        apiKey: String,
        model: String,
        goal: String,
        screenSummary: String,
        history: List<String>,
        memoryVault: String = "",
        screenshotBase64: String? = null
    ): AgentDecision {
        val prompt = """
            You are an Autonomous Android UI & Phone Doctor Agent controlling an Android phone.
            OVERALL GOAL: $goal
            
            USER'S SAVED MEMORY VAULT:
            $memoryVault
            
            RECENT ACTIONS HISTORY:
            ${history.takeLast(8).joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS (Index | Text | ContentDesc | ViewId | Editable | Clickable | CenterXY):
            $screenSummary
            
            CRITICAL TAP SAFETY RULES:
            - Prefer CLICK_NODE for all buttons and controls. The screen summary contains node indices and their exact centers; select the node that semantically matches the intended action.
            - COORDINATE CONTRACT: when a screenshot is attached, it is resized to exactly 480x960 pixels. For CLICK_XY, return coordinates in THIS image only: x must be 0..479, y must be 0..959, with (0,0) at the image's top-left. Do NOT return original phone pixels, dp values, or coordinates such as x=540/y=1200. The app converts valid image coordinates to phone screen coordinates.
            - If the target is visible in the screenshot but no accessibility node matches it, estimate the target's CENTER within the 480x960 image. If the screenshot is missing or the target cannot be located confidently, return WAIT, not invented coordinates.
            - Never guess coordinates, never reuse coordinates from a previous screen, and NEVER use the default center of the screen as a tap target.
            - For WhatsApp messaging: after typing/pasting the requested message, inspect the CURRENT screen again. Find the actual Send button/icon using its text, content description, viewId and screenshot. Tap only that current Send control. Do NOT tap a previous chat message, video thumbnail, attachment, or any content in the conversation.
            - If the Send control cannot be confidently identified, return WAIT and explain that the Send button is unclear; do not tap.
            - Do not repeat a tap unless the next screen confirms the previous action did not occur.
            - If required information is missing, ask for clarification rather than acting.
            
            Available actions:
            - "CLICK_NODE": Click a specific UI node by its nodeIndex.
            - "CLICK_XY": Tap coordinates ONLY when the intended target is visibly identified in the current screenshot AND there is no matching accessibility node. Never guess.
            - "TYPE": Type text into an editable field (specify nodeIndex and textToType).
            - "SCROLL": Scroll down to see more settings or options.
            - "BACK": Press Android Back button.
            - "OPEN_APP": Open an Android System Setting or App directly. Set appPackageOrSetting to one of:
                "SETTINGS", "BATTERY", "WIFI", "DISPLAY", "STORAGE", "SOUND", "BLUETOOTH", "APPS", or a package name like "com.whatsapp", "com.android.chrome", "com.google.android.youtube".
            - "WAIT": Wait 3 seconds if screen is loading or the correct target cannot be identified safely.
            - "DONE": Goal is completely finished.
            
            Return strictly valid JSON:
            {
              "action": "CLICK_NODE",
              "nodeIndex": 3,
              "x": 320,
              "y": 280,
              "textToType": "",
              "appPackageOrSetting": "",
              "reason": "Brief explanation of this step"
            }
        """.trimIndent()

        return try {
            val rawJson = callGeminiApi(apiKey, model, prompt, screenshotBase64)
            val clean = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            AgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                nodeIndex = obj.optInt("nodeIndex", -1),
                x = obj.optDouble("x", -1.0).toFloat(),
                y = obj.optDouble("y", -1.0).toFloat(),
                textToType = obj.optString("textToType", ""),
                appPackageOrSetting = obj.optString("appPackageOrSetting", ""),
                reason = obj.optString("reason", "Proceeding")
            )
        } catch (e: Exception) {
            AgentDecision(action = "WAIT", reason = "Waiting for response...")
        }
    }
}
