package com.auradroid.agent

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class NativeUploadStartResult(
    val started: Boolean,
    val message: String,
    val displayName: String = "",
    val title: String = ""
)

/**
 * Starts the real YouTube app's video-share/upload flow. Android/YouTube still owns the
 * final review and publish action; this class never claims a video uploaded until the
 * user confirms that YouTube finished successfully.
 */
object NativeYouTubeUploader {
    private const val PREFS = "auradroid_native_youtube_upload"
    private const val KEY_PENDING_ID = "pending_video_id"
    private const val KEY_PENDING_NAME = "pending_video_name"
    private const val KEY_PENDING_TITLE = "pending_video_title"
    private const val KEY_PENDING_LAST_MODIFIED = "pending_video_last_modified"
    private const val KEY_PENDING_SIZE_BYTES = "pending_video_size_bytes"
    private const val KEY_AUTO_PUBLISH_PUBLIC = "auto_publish_public"

    fun isAutoPublishPublicEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PUBLISH_PUBLIC, false)

    fun setAutoPublishPublicEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_PUBLISH_PUBLIC, enabled)
            .apply()
    }

    fun isYouTubeAutomationGoal(goal: String): Boolean =
        goal.startsWith("[NAXBOT_YOUTUBE_UPLOAD]")

    /**
     * Only mark a queue item as uploaded when the active UI tree contains an explicit
     * publication confirmation. Vision-model text alone is not treated as proof.
     */
    fun handleAgentCompletion(
        context: Context,
        goal: String,
        reason: String,
        uiTree: String
    ): String? {
        if (!isYouTubeAutomationGoal(goal)) return null

        val visibleText = uiTree.lowercase()
        val publishEvidence = listOf(
            "video published",
            "your video has been published",
            "published successfully"
        ).any { visibleText.contains(it) }

        if (publishEvidence) {
            val result = markPendingUploadComplete(context)
            return if (result.startsWith("Marked", ignoreCase = true)) {
                "✅ Verified YouTube publication from visible screen text. $result"
            } else {
                "⚠️ Publication text was visible, but the local queue could not be updated: $result"
            }
        }

        val autoPublic = goal.contains("AUTO_PUBLISH_PUBLIC: TRUE", ignoreCase = true)
        return if (autoPublic) {
            "⚠️ NEEDS_CONFIRMATION: Nax Bot stopped but no explicit YouTube publication confirmation was detected. Check YouTube Studio before retrying to avoid a duplicate. Agent note: ${reason.take(180)}"
        } else {
            "🟡 Review mode: Nax Bot stopped without publishing confirmation. The video remains pending. Check YouTube; use Confirm Last Video Uploaded only if it really published. Agent note: ${reason.take(180)}"
        }
    }

    suspend fun startNextUpload(context: Context): NativeUploadStartResult {
        val appContext = context.applicationContext
        if (PhoneAgentManager.isTaskRunning()) {
            return NativeUploadStartResult(
                started = false,
                message = "Another phone task is running. Stop or finish it before starting a YouTube upload."
            )
        }
        if (AutoTouchService.instance == null) {
            return NativeUploadStartResult(
                started = false,
                message = "Enable AuraDroid Accessibility Service in Settings first. The YouTube workflow needs screen interaction."
            )
        }
        if (GeminiBrain.getApiKey(appContext).isBlank()) {
            return NativeUploadStartResult(
                started = false,
                message = "Gemini API key is missing. Add it in Settings before preparing upload metadata."
            )
        }
        val item = withContext(Dispatchers.IO) {
            VideoQueueManager.getNextVideoForUpload(appContext)
        } ?: return NativeUploadStartResult(
            started = false,
            message = "Upload queue is empty. Choose the video folder and scan again."
        )

        val localFile = withContext(Dispatchers.IO) {
            VideoQueueManager.ensureLocalCacheFile(appContext, item)
        } ?: return NativeUploadStartResult(
            started = false,
            message = "Could not read '${item.displayName}'. Check the folder permission."
        )

        val metadata = try {
            GeminiBrain.generateYouTubeMetadataWithVision(
                context = appContext,
                videoFile = localFile,
                storyTopicHint = "Infer the topic from the actual video frames only; never reuse the source filename.",
                episodeNumber = 1
            )
        } catch (e: Exception) {
            return NativeUploadStartResult(
                started = false,
                message = "Gemini metadata generation failed. Nothing was uploaded. Check API key/quota. ${e.message ?: e.javaClass.simpleName}"
            )
        }

        if (metadata.title.isBlank() || metadata.description.isBlank()) {
            return NativeUploadStartResult(
                started = false,
                message = "Gemini returned incomplete metadata. Nothing was uploaded; retry after checking API/model availability."
            )
        }

        val sharedUri = try {
            item.safUri ?: FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                localFile
            )
        } catch (e: Exception) {
            return NativeUploadStartResult(
                started = false,
                message = "Secure video sharing failed: ${e.message ?: "FileProvider error"}"
            )
        }

        val textMetadata = buildString {
            append(metadata.title)
            append("\n\n")
            append(metadata.description)
            if (metadata.tags.isNotEmpty()) {
                append("\n\n")
                append(metadata.tags.distinct().take(15).joinToString(" ") {
                    if (it.startsWith("#")) it else "#${it.replace(Regex("[^A-Za-z0-9_]"), "")}"
                })
            }
        }.take(5000)

        val autoPublic = isAutoPublishPublicEnabled(appContext)
        val visibilityInstruction = if (autoPublic) {
            "Set visibility to Public. Verify that Public is visibly selected before pressing Publish."
        } else {
            "REVIEW MODE: prepare the upload, fill and verify the fields, then stop before the final Publish button. Do not publish."
        }
        val goal = buildString {
            appendLine("[NAXBOT_YOUTUBE_UPLOAD]")
            appendLine("Use only the visible official YouTube app / YouTube Studio UI. Do not use any YouTube Data API.")
            appendLine("Selected video file: ${item.displayName}")
            appendLine("Required title, exactly: ${metadata.title}")
            appendLine("Required description, exactly: ${metadata.description}")
            appendLine("Suggested tags: ${metadata.tags.distinct().take(15).joinToString(", ")}")
            appendLine("AUTO_PUBLISH_PUBLIC: ${if (autoPublic) "TRUE" else "FALSE"}")
            appendLine("If the Android share chooser appears, select the official YouTube app. The selected video is already attached.")
            appendLine("Fill the title and description fields manually, then verify the selected video and every visible metadata field.")
            appendLine(visibilityInstruction)
            appendLine("If a login, CAPTCHA, 2FA, permission prompt, unclear screen, or unexpected account appears, stop and ask the user. Never bypass it.")
            appendLine("Report DONE only at the final review step or after a visible publication confirmation. Never guess that it succeeded.")
        }.trim()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = when (localFile.extension.lowercase()) {
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                else -> "video/mp4"
            }
            putExtra(Intent.EXTRA_STREAM, sharedUri)
            putExtra(Intent.EXTRA_SUBJECT, metadata.title.take(100))
            putExtra(Intent.EXTRA_TEXT, textMetadata)
            clipData = ClipData.newUri(appContext.contentResolver, item.displayName, sharedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Choose YouTube to upload ${item.displayName}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(chooser)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_ID, item.id)
                .putString(KEY_PENDING_NAME, item.displayName)
                .putString(KEY_PENDING_TITLE, metadata.title)
                .putLong(KEY_PENDING_LAST_MODIFIED, item.lastModified)
                .putLong(KEY_PENDING_SIZE_BYTES, item.sizeBytes)
                .apply()

            PhoneAgentManager.startAutonomousPhoneTask(context, goal)

            NativeUploadStartResult(
                started = true,
                message = if (autoPublic) {
                    "Nax Bot started the YouTube UI workflow for '${item.displayName}'. Auto-Public is enabled; it will stop if the UI or publish result cannot be verified."
                } else {
                    "Nax Bot started the YouTube UI workflow for '${item.displayName}' in Review Mode. It should stop before the final Publish action."
                },
                displayName = item.displayName,
                title = metadata.title
            )
        } catch (e: Exception) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_NAME)
                .remove(KEY_PENDING_TITLE)
                .remove(KEY_PENDING_LAST_MODIFIED)
                .remove(KEY_PENDING_SIZE_BYTES)
                .apply()
            NativeUploadStartResult(
                started = false,
                message = "Could not start the YouTube UI workflow: ${e.message ?: "unknown error"}"
            )
        }
    }

    fun markPendingUploadComplete(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_PENDING_ID, "").orEmpty()
        val name = prefs.getString(KEY_PENDING_NAME, "").orEmpty()
        if (id.isBlank() || name.isBlank()) return "No pending video to confirm. Start an upload first."

        val queued = QueuedVideoItem(
            id = id,
            displayName = name,
            localFile = null,
            safUri = null,
            lastModified = prefs.getLong(KEY_PENDING_LAST_MODIFIED, 0L),
            sizeBytes = prefs.getLong(KEY_PENDING_SIZE_BYTES, 0L)
        )

        VideoQueueManager.markVideoAsUploaded(context, queued)
        prefs.edit()
            .remove(KEY_PENDING_ID)
            .remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_TITLE)
            .remove(KEY_PENDING_LAST_MODIFIED)
            .remove(KEY_PENDING_SIZE_BYTES)
            .apply()
        return "Marked '${queued.displayName}' as uploaded after completion was confirmed."
    }

    fun getPendingTitle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_TITLE, "").orEmpty()
}
