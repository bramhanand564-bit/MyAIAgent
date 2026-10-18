package com.auradroid.agent

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class QueuedVideoItem(
    val id: String,
    val displayName: String,
    val localFile: File?,
    val safUri: Uri?,
    val lastModified: Long,
    val sizeBytes: Long
)

data class StoryArcState(
    val currentEpisode: Int,
    val storyMode: String,
    val characterVisualLock: String,
    val arcHistorySummary: String
)

data class YouTubeSeoMetadata(
    val title: String,
    val description: String,
    val tags: List<String>
)

object VideoQueueManager {

    const val PREFS_NAME = "auradroid_video_queue_prefs"
    private const val KEY_CUSTOM_FOLDER_URI = "custom_folder_uri"
    private const val KEY_UPLOAD_NEWEST_FIRST = "upload_newest_first"
    private const val KEY_UPLOADED_IDS = "uploaded_video_ids_json"
    private const val KEY_CURRENT_EPISODE = "story_current_episode"
    private const val KEY_STORY_MODE = "story_continuity_mode"
    private const val KEY_CHARACTER_LOCK = "story_character_visual_lock"
    private const val KEY_ARC_HISTORY = "story_arc_history_json"

    private val FORBIDDEN_POLICY_WORDS = setOf(
        "boy", "boys", "girl", "girls", "child", "children", "kid", "kids",
        "minor", "minors", "baby", "babies", "toddler", "teen", "teenager",
        "youngster", "skin", "nude", "naked", "blood", "gore"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setCustomFolderUri(context: Context, uriString: String?) {
        prefs(context).edit().putString(KEY_CUSTOM_FOLDER_URI, uriString ?: "").apply()
    }

    fun getCustomFolderUri(context: Context): String? {
        val raw = prefs(context).getString(KEY_CUSTOM_FOLDER_URI, "") ?: ""
        return if (raw.isBlank()) null else raw
    }

    fun getFolderDisplayLabel(context: Context): String {
        val customUri = getCustomFolderUri(context)
        if (customUri.isNullOrBlank()) {
            return "Default: /Download/AuraDroid_Flow_Videos"
        }
        return try {
            val parsed = Uri.parse(customUri)
            val doc = DocumentFile.fromTreeUri(context, parsed)
            val folderName = doc?.name ?: parsed.lastPathSegment ?: "Custom Folder"
            "SAF Folder: $folderName ($customUri)"
        } catch (e: Exception) {
            "SAF Folder: $customUri"
        }
    }

    fun setUploadNewestFirst(context: Context, newestFirst: Boolean) {
        prefs(context).edit().putBoolean(KEY_UPLOAD_NEWEST_FIRST, newestFirst).apply()
    }

    fun isUploadNewestFirst(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_UPLOAD_NEWEST_FIRST, true)
    }

    fun getDefaultDownloadDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val auraDir = File(publicDownloads, "AuraDroid_Flow_Videos")
        if (!auraDir.exists()) auraDir.mkdirs()
        if (auraDir.exists() && auraDir.canWrite()) return auraDir
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AuraDroid_Flow_Videos")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    fun saveVideoBytesToStorage(
        context: Context,
        bytes: ByteArray,
        prefix: String = "AuraDroid_Flow"
    ): File? {
        if (bytes.size < 1024) return null
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${prefix}_${stamp}.mp4"
            val targetDir = getDefaultDownloadDir(context)
            val outFile = File(targetDir, fileName)
            FileOutputStream(outFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            val customUriStr = getCustomFolderUri(context)
            if (!customUriStr.isNullOrBlank()) {
                try {
                    val treeUri = Uri.parse(customUriStr)
                    val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    val newDoc = treeDoc?.createFile("video/mp4", fileName)
                    if (newDoc != null) {
                        context.contentResolver.openOutputStream(newDoc.uri)?.use { os ->
                            os.write(bytes)
                            os.flush()
                        }
                    }
                } catch (_: Exception) {}
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getUploadedIdsSet(context: Context): MutableSet<String> {
        val raw = prefs(context).getString(KEY_UPLOADED_IDS, "[]") ?: "[]"
        val result = mutableSetOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optString(i)
                if (item.isNotBlank()) result.add(item)
            }
        } catch (_: Exception) {}
        return result
    }

    fun markVideoAsUploaded(context: Context, item: QueuedVideoItem) {
        val current = getUploadedIdsSet(context)
        current.add(item.id)
        current.add(item.displayName)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_UPLOADED_IDS, arr.toString()).apply()
    }

    fun listCandidateVideos(context: Context): List<QueuedVideoItem> {
        val uploadedIds = getUploadedIdsSet(context)
        val candidates = mutableListOf<QueuedVideoItem>()
        val seenNames = mutableSetOf<String>()

        val customUriStr = getCustomFolderUri(context)
        if (!customUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null && rootDoc.exists()) {
                    for (doc in rootDoc.listFiles()) {
                        val name = doc.name ?: continue
                        val lower = name.lowercase(Locale.US)
                        val isVideo = lower.endsWith(".mp4") or lower.endsWith(".webm") or lower.endsWith(".mov")
                        if (doc.isFile && isVideo && doc.length() > 50_000L) {
                            val id = doc.uri.toString()
                            if (!uploadedIds.contains(id) && !uploadedIds.contains(name)) {
                                candidates.add(
                                    QueuedVideoItem(id, name, null, doc.uri, doc.lastModified(), doc.length())
                                )
                                seenNames.add(name)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val dirsToScan = listOf(
            getDefaultDownloadDir(context),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        )

        for (dir in dirsToScan) {
            if (dir != null && dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    val lower = f.name.lowercase(Locale.US)
                    val isVideo = lower.endsWith(".mp4") or lower.endsWith(".webm") or lower.endsWith(".mov")
                    if (f.isFile && isVideo && f.length() > 50_000L) {
                        val id = f.absolutePath
                        if (!uploadedIds.contains(id) && !uploadedIds.contains(f.name) && !seenNames.contains(f.name)) {
                            candidates.add(
                                QueuedVideoItem(id, f.name, f, null, f.lastModified(), f.length())
                            )
                            seenNames.add(f.name)
                        }
                    }
                }
            }
        }

        return if (isUploadNewestFirst(context)) {
            candidates.sortedByDescending { it.lastModified }
        } else {
            candidates.sortedBy { it.lastModified }
        }
    }

    fun getNextVideoForUpload(context: Context): QueuedVideoItem? =
        listCandidateVideos(context).firstOrNull()

    fun ensureLocalCacheFile(context: Context, item: QueuedVideoItem): File? {
        if (item.localFile != null && item.localFile.exists() && item.localFile.canRead()) {
            return item.localFile
        }
        val safUri = item.safUri ?: return null
        return try {
            val cacheDir = File(context.cacheDir, "upload_staging")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val safeName = item.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val stagedFile = File(cacheDir, safeName)
            context.contentResolver.openInputStream(safUri)?.use { input ->
                FileOutputStream(stagedFile).use { output -> input.copyTo(output) }
            }
            if (stagedFile.exists() && stagedFile.length() > 10_000L) stagedFile else null
        } catch (e: Exception) {
            null
        }
    }

    fun getDownloadedVideosReport(context: Context): String {
        val list = listCandidateVideos(context)
        if (list.isEmpty()) {
            return "No unuploaded MP4 videos found in queue.\nFolder: ${getFolderDisplayLabel(context)}"
        }
        val sb = StringBuilder()
        sb.append("Queued Videos (${list.size}) • Order: ")
        sb.append(if (isUploadNewestFirst(context)) "Newest First\n\n" else "Oldest First\n\n")
        list.take(15).forEachIndexed { idx, item ->
            val mb = String.format(Locale.US, "%.2f MB", item.sizeBytes.toDouble() / (1024.0 * 1024.0))
            sb.append("${idx + 1}. ${item.displayName} ($mb)\n")
        }
        return sb.toString().trim()
    }

    fun extractRepresentativeFrames(context: Context, videoFile: File): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 8000L
            val firstTimeUs = (durationMs * 200L).coerceAtLeast(500_000L)
            val secondTimeUs = (durationMs * 700L).coerceAtLeast(1_500_000L)
            retriever.getFrameAtTime(firstTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                frames.add(scaleFrameForVision(it))
            }
            retriever.getFrameAtTime(secondTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                frames.add(scaleFrameForVision(it))
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
    }

    private fun scaleFrameForVision(src: Bitmap): Bitmap {
        val maxDim = 640
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = w.toFloat() / h.toFloat()
        val newW: Int
        val newH: Int
        if (w > h) {
            newW = maxDim
            newH = (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            newH = maxDim
            newW = (maxDim * ratio).toInt().coerceAtLeast(1)
        }
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        if (scaled !== src && !src.isRecycled) {
            try { src.recycle() } catch (_: Exception) {}
        }
        return scaled
    }

    fun sanitizePolicySafePrompt(rawPrompt: String, characterLock: String = ""): String {
        val defaultHero = "adult futuristic cybernetic tech explorer with glowing cyan visor"
        val safeHero = if (characterLock.isBlank()) defaultHero else sanitizeWordsOnly(characterLock)
        var cleaned = sanitizeWordsOnly(rawPrompt)
        if (!cleaned.lowercase(Locale.US).contains("adult")) {
            cleaned = "Cinematic 9:16 vertical sci-fi scene featuring an $safeHero. $cleaned"
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeWordsOnly(text: String): String {
        val tokens = text.split(" ")
        return tokens.map { token ->
            val bare = token.lowercase(Locale.US).replace(Regex("[^a-z]"), "")
            if (FORBIDDEN_POLICY_WORDS.contains(bare)) "adult cybernetic explorer" else token
        }.joinToString(" ")
    }

    fun getStoryArcState(context: Context): StoryArcState {
        val p = prefs(context)
        val ep = p.getInt(KEY_CURRENT_EPISODE, 1).coerceAtLeast(1)
        val mode = p.getString(KEY_STORY_MODE, "SERIAL") ?: "SERIAL"
        val charLock = p.getString(
            KEY_CHARACTER_LOCK,
            "Adult futuristic cybernetic tech explorer wearing matte-black nano armor and glowing cyan visor"
        ) ?: "Adult futuristic cybernetic tech explorer"
        val rawArr = p.getString(KEY_ARC_HISTORY, "[]") ?: "[]"
        val sb = StringBuilder()
        try {
            val arr = JSONArray(rawArr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                sb.append("Ep ${obj.optInt("ep")}: ${obj.optString("summary")}\n")
            }
        } catch (_: Exception) {}
        val historyText = if (sb.isEmpty()) {
            "Current Episode: $ep • Ready to start new episodic story arc."
        } else {
            sb.toString().trim()
        }
        return StoryArcState(ep, mode, charLock, historyText)
    }

    fun saveStorySettings(context: Context, storyMode: String, characterVisualLock: String) {
        val safeLock = sanitizeWordsOnly(characterVisualLock)
        prefs(context).edit()
            .putString(KEY_STORY_MODE, storyMode)
            .putString(KEY_CHARACTER_LOCK, safeLock)
            .apply()
    }

    fun recordCompletedEpisode(context: Context, episodeNumber: Int, episodeSummary: String) {
        val p = prefs(context)
        val rawArr = p.getString(KEY_ARC_HISTORY, "[]") ?: "[]"
        val arr = try { JSONArray(rawArr) } catch (_: Exception) { JSONArray() }
        val entry = JSONObject().apply {
            put("ep", episodeNumber)
            put("summary", episodeSummary.take(220))
            put("time", System.currentTimeMillis())
        }
        arr.put(entry)
        p.edit()
            .putInt(KEY_CURRENT_EPISODE, episodeNumber + 1)
            .putString(KEY_ARC_HISTORY, arr.toString())
            .apply()
    }

    fun resetStoryArc(context: Context) {
        prefs(context).edit()
            .putInt(KEY_CURRENT_EPISODE, 1)
            .putString(KEY_ARC_HISTORY, "[]")
            .apply()
    }
}
