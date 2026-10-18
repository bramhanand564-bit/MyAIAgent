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

    suspend fun startNextUpload(context: Context): NativeUploadStartResult {
        val appContext = context.applicationContext
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
            YouTubeSeoMetadata(
                title = "Aakhri Frame Ka Rahasya 😱 #shorts #viral",
                description = "End tak dekho—what detail did you notice? Comment below! #shorts #viral",
                tags = listOf("shorts", "viral", "mystery")
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
                .apply()
            NativeUploadStartResult(
                started = true,
                message = "YouTube share flow opened. Review the title/description in YouTube, then publish manually.",
                displayName = item.displayName,
                title = metadata.title
            )
        } catch (e: Exception) {
            NativeUploadStartResult(
                started = false,
                message = "No compatible video-share app found: ${e.message ?: "unknown error"}"
            )
        }
    }

    fun markPendingUploadComplete(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_PENDING_ID, "").orEmpty()
        val name = prefs.getString(KEY_PENDING_NAME, "").orEmpty()
        if (id.isBlank() || name.isBlank()) return "No pending video to confirm. Start an upload first."

        val queued = VideoQueueManager.listCandidateVideos(context)
            .firstOrNull { it.id == id || it.displayName == name }
        if (queued == null) {
            prefs.edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_NAME).remove(KEY_PENDING_TITLE).apply()
            return "The pending item is no longer in the queue; refresh the queue before continuing."
        }

        VideoQueueManager.markVideoAsUploaded(context, queued)
        prefs.edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_NAME).remove(KEY_PENDING_TITLE).apply()
        return "Marked '${queued.displayName}' as uploaded because you confirmed completion in YouTube."
    }

    fun getPendingTitle(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_TITLE, "").orEmpty()
}
