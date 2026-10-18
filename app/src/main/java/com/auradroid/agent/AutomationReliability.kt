package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class AuradroidBrainDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "auradroid_brain.db"
        private const val DATABASE_VERSION = 3

        private const val TABLE_SKILLS = "skill_blueprints"
        private const val TABLE_RAG = "vector_rag_memory"
        private const val TABLE_MEMORIES = "personal_memories"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_SKILLS (
            skill_key TEXT PRIMARY KEY,
            blueprint_json TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )"""
        )

        db.execSQL(
            """CREATE TABLE $TABLE_RAG (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            goal_query TEXT NOT NULL,
            state_signature TEXT NOT NULL,
            lesson_fix TEXT NOT NULL,
            embedding_tokens TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )"""
        )

        db.execSQL(
            """CREATE TABLE $TABLE_MEMORIES (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fact_text TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )"""
        )

        createVisualMemoryTable(db)

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shadow_teach_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT NOT NULL,
            event_type TEXT NOT NULL,
            screen_signature TEXT NOT NULL,
            bounds_text TEXT NOT NULL,
            center_x REAL NOT NULL,
            center_y REAL NOT NULL,
            label_text TEXT NOT NULL,
            is_correction INTEGER NOT NULL DEFAULT 0,
            timestamp INTEGER NOT NULL
        )"""
        )

        db.insert(
            TABLE_SKILLS,
            null,
            ContentValues().apply {
                put("skill_key", "google_flow_master")
                put("blueprint_json", GeminiBrain.DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT)
                put("updated_at", System.currentTimeMillis())
            }
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migration only: never drop existing skill blueprints, RAG lessons, or memories.
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shadow_teach_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT NOT NULL,
            event_type TEXT NOT NULL,
            screen_signature TEXT NOT NULL,
            bounds_text TEXT NOT NULL,
            center_x REAL NOT NULL,
            center_y REAL NOT NULL,
            label_text TEXT NOT NULL,
            is_correction INTEGER NOT NULL DEFAULT 0,
            timestamp INTEGER NOT NULL
        )"""
        )
        createVisualMemoryTable(db)
    }

    private fun createVisualMemoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS visual_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                app_folder TEXT NOT NULL,
                task_goal TEXT NOT NULL,
                actor TEXT NOT NULL,
                stage TEXT NOT NULL,
                image_path TEXT NOT NULL DEFAULT '',
                tap_x REAL NOT NULL DEFAULT -1,
                tap_y REAL NOT NULL DEFAULT -1,
                bounds_text TEXT NOT NULL DEFAULT '',
                button_label TEXT NOT NULL DEFAULT '',
                golden_fix TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visual_memory_package ON visual_memory(package_name, timestamp)")
    }
}

object AutomationReliability {

    private var dbHelper: AuradroidBrainDbHelper? = null

    fun init(context: Context) {
        if (dbHelper == null) {
            dbHelper = AuradroidBrainDbHelper(context.applicationContext)
        }
    }

    fun loadSkillBlueprint(context: Context, skillKey: String): String {
        init(context)
        val db = dbHelper?.readableDatabase ?: return GeminiBrain.DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT
        var result = GeminiBrain.DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT
        try {
            db.rawQuery("SELECT blueprint_json FROM skill_blueprints WHERE skill_key = ?", arrayOf(skillKey)).use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(0) ?: GeminiBrain.DEFAULT_GOOGLE_FLOW_MASTER_BLUEPRINT
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun saveSkillBlueprint(context: Context, skillKey: String, blueprintJson: String) {
        init(context)
        val db = dbHelper?.writableDatabase ?: return
        try {
            val cv = ContentValues().apply {
                put("skill_key", skillKey)
                put("blueprint_json", blueprintJson)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                "skill_blueprints",
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (_: Exception) {
        }
    }

    fun recordSuccessfulLesson(context: Context, goal: String, stateSignature: String, lessonFix: String) {
        init(context)
        val db = dbHelper?.writableDatabase ?: return
        try {
            val tokens = tokenizeText(goal + " " + stateSignature)
            val cv = ContentValues().apply {
                put("goal_query", goal)
                put("state_signature", stateSignature)
                put("lesson_fix", lessonFix)
                put("embedding_tokens", tokens)
                put("timestamp", System.currentTimeMillis())
            }
            db.insert("vector_rag_memory", null, cv)
        } catch (_: Exception) {
        }
    }

    fun retrieveRelevantRagLessons(context: Context, goal: String, stateSignature: String): String {
        init(context)
        val db = dbHelper?.readableDatabase ?: return "No prior RAG lessons recorded."
        val sb = StringBuilder()
        val queryTokens = tokenizeText(goal).split(",").filter { it.isNotBlank() }.toSet()
        val stateTokens = tokenizeText(stateSignature).split(",").filter { it.isNotBlank() }.toSet()
        val lessons = mutableListOf<Triple<String, String, Int>>()
        try {
            db.rawQuery(
                "SELECT goal_query, state_signature, lesson_fix, embedding_tokens FROM vector_rag_memory ORDER BY timestamp DESC LIMIT 100",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val lessonGoal = cursor.getString(0).orEmpty()
                    val lessonState = cursor.getString(1).orEmpty()
                    val fix = cursor.getString(2).orEmpty()
                    val tokens = cursor.getString(3).orEmpty().split(",").filter { it.isNotBlank() }.toSet()
                    val score = queryTokens.intersect(tokens).size * 4 +
                        stateTokens.intersect(tokens).size +
                        if (stateSignature.isNotBlank() && lessonState == stateSignature) 20 else 0
                    lessons.add(Triple(lessonGoal, fix, score))
                }
            }
        } catch (_: Exception) {
        }

        val ranked = if (lessons.any { it.third > 0 }) {
            lessons.sortedByDescending { it.third }.take(5)
        } else {
            lessons.take(5)
        }
        ranked.forEach { (lessonGoal, fix, score) ->
            sb.append("• Goal: ").append(lessonGoal)
                .append(" ➔ Fix: ").append(fix)
                .append(if (score > 0) " [relevance $score]" else "")
                .append('\n')
        }

        // Reuse only events recorded from an identical privacy-safe screen signature.
        // Coordinates are hints, not mandatory clicks; the live UI tree must still agree.
        if (stateSignature.isNotBlank()) {
            try {
                db.rawQuery(
                    "SELECT package_name, event_type, center_x, center_y, label_text, is_correction FROM shadow_teach_events WHERE screen_signature = ? ORDER BY timestamp DESC LIMIT 4",
                    arrayOf(stateSignature)
                ).use { cursor ->
                    var wroteHeader = false
                    while (cursor.moveToNext()) {
                        if (!wroteHeader) {
                            sb.append("Passive Teach examples matching this same screen (verify against current UI before reuse):").append('\n')
                            wroteHeader = true
                        }
                        val retry = if (cursor.getInt(5) == 1) " [repeated target/correction]" else ""
                        sb.append("• ").append(cursor.getString(0)).append(" / ")
                            .append(cursor.getString(1)).append(" @(")
                            .append(cursor.getFloat(2).toInt()).append(",")
                            .append(cursor.getFloat(3).toInt()).append(") label='")
                            .append(cursor.getString(4)).append("'").append(retry).append('\n')
                    }
                }
            } catch (_: Exception) {
            }
        }
        val res = sb.toString().trim()
        return if (res.isBlank()) "No relevant RAG or same-screen teaching lessons recorded." else res
    }

    fun savePersonalMemoryFact(context: Context, factText: String) {
        init(context)
        val db = dbHelper?.writableDatabase ?: return
        try {
            val cv = ContentValues().apply {
                put("fact_text", factText.trim())
                put("timestamp", System.currentTimeMillis())
            }
            db.insert("personal_memories", null, cv)
        } catch (_: Exception) {
        }
    }

    fun getAllMemoryFacts(context: Context): String {
        init(context)
        val db = dbHelper?.readableDatabase ?: return "No personal memory facts saved."
        val sb = StringBuilder()
        try {
            db.rawQuery("SELECT fact_text FROM personal_memories ORDER BY timestamp DESC LIMIT 20", null).use { cursor ->
                while (cursor.moveToNext()) {
                    sb.append("• ${cursor.getString(0)}\n")
                }
            }
        } catch (_: Exception) {
        }
        val res = sb.toString().trim()
        return if (res.isBlank()) "No personal memory facts saved." else res
    }

    private const val TEACH_PREFS = "auradroid_shadow_teach_prefs"
    private const val KEY_TEACH_ENABLED = "passive_teach_enabled"
    private const val MAX_TEACH_EVENTS = 500

    fun setPassiveTeachMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(TEACH_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TEACH_ENABLED, enabled).apply()
    }

    fun isPassiveTeachModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(TEACH_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TEACH_ENABLED, false)

    fun getShadowTeachEventCount(context: Context): Int {
        init(context)
        val db = dbHelper?.readableDatabase ?: return 0
        return try {
            db.rawQuery("SELECT COUNT(*) FROM shadow_teach_events", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Stores structural interaction data only. Screenshots, keystrokes, and typed field contents
     * are never stored. Sensitive labels and own-app events are rejected as a second privacy guard.
     */
    fun recordShadowTeachEvent(
        context: Context,
        packageName: String,
        eventType: String,
        screenSignature: String,
        boundsText: String,
        centerX: Float,
        centerY: Float,
        labelText: String
    ): Boolean {
        if (!isPassiveTeachModeEnabled(context)) return false
        val packageSafe = packageName.trim().take(180)
        if (packageSafe.isBlank() || packageSafe == context.packageName) return false
        val packageLower = packageSafe.lowercase(Locale.US)
        if (packageLower.contains("inputmethod") ||
            packageLower.contains(".ime") ||
            packageLower.contains("keyboard") ||
            packageLower == "com.android.systemui") return false

        val eventSafe = eventType.trim().uppercase(Locale.US)
        if (eventSafe !in setOf("VIEW_CLICKED", "VIEW_SELECTED", "VIEW_SCROLLED", "WINDOW_STATE_CHANGED")) return false
        val cleanLabel = labelText.trim().replace(Regex("\\s+"), " ").take(80)
        val sensitive = Regex(
            "(?i)(password|passcode|pin code|one.?time.?password|\\botp\\b|email|e-mail|phone number|bank|account number|credit card|debit card|cvv|security code|recovery code)"
        )
        if (sensitive.containsMatchIn(cleanLabel)) return false

        val signature = screenSignature.trim().take(64).ifBlank { "unknown" }
        val safeBounds = boundsText.trim().take(100)
        if (!centerX.isFinite() || !centerY.isFinite() || centerX < 0f || centerY < 0f) return false
        init(context)
        val db = dbHelper?.writableDatabase ?: return false
        val now = System.currentTimeMillis()
        return try {
            var correction = false
            if (eventSafe == "VIEW_CLICKED") {
                db.rawQuery(
                    "SELECT center_x, center_y FROM shadow_teach_events WHERE package_name = ? AND screen_signature = ? AND event_type = 'VIEW_CLICKED' AND timestamp >= ? ORDER BY timestamp DESC LIMIT 1",
                    arrayOf(packageSafe, signature, (now - 8000L).toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val oldX = cursor.getFloat(0)
                        val oldY = cursor.getFloat(1)
                        correction = kotlin.math.abs(oldX - centerX) <= 24f &&
                            kotlin.math.abs(oldY - centerY) <= 24f
                    }
                }
            }
            val values = ContentValues().apply {
                put("package_name", packageSafe)
                put("event_type", eventSafe)
                put("screen_signature", signature)
                put("bounds_text", safeBounds)
                put("center_x", centerX)
                put("center_y", centerY)
                put("label_text", cleanLabel)
                put("is_correction", if (correction) 1 else 0)
                put("timestamp", now)
            }
            db.insert("shadow_teach_events", null, values)
            db.execSQL(
                "DELETE FROM shadow_teach_events WHERE id NOT IN (SELECT id FROM shadow_teach_events ORDER BY timestamp DESC LIMIT $MAX_TEACH_EVENTS)"
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getShadowTeachSummary(context: Context, limit: Int = 30): String {
        init(context)
        val db = dbHelper?.readableDatabase ?: return "No passive teach events yet."
        val safeLimit = limit.coerceIn(1, 100)
        val out = StringBuilder()
        return try {
            db.rawQuery(
                "SELECT package_name, event_type, screen_signature, center_x, center_y, label_text, is_correction FROM shadow_teach_events ORDER BY timestamp DESC LIMIT $safeLimit",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val correction = if (cursor.getInt(6) == 1) " • correction/retry" else ""
                    out.append("• ").append(cursor.getString(0)).append(" / ")
                        .append(cursor.getString(1)).append(" @(")
                        .append(cursor.getFloat(3).toInt()).append(",")
                        .append(cursor.getFloat(4).toInt()).append(") ")
                        .append(cursor.getString(5)).append(correction).append('\n')
                }
                if (out.isEmpty()) "No passive teach events yet." else out.toString().trim()
            }
        } catch (_: Exception) {
            "Passive teach history unavailable."
        }
    }


    private data class VisualFrameRecord(
        val id: Long,
        val packageName: String,
        val appFolder: String,
        val taskGoal: String,
        val stage: String,
        val imagePath: String,
        val buttonLabel: String,
        val tapX: Float,
        val tapY: Float,
        val goldenFix: String
    )

    private fun safeFolderName(raw: String): String {
        return raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .take(60)
            .ifBlank { "General" }
    }

    private fun resolveAppFolder(context: Context, packageName: String, description: String = ""): String {
        val lower = (packageName + " " + description).lowercase(Locale.US)
        val mapped = when {
            lower.contains("instagram") -> "Instagram"
            lower.contains("youtube") || lower.contains("yt studio") -> "YouTube"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("telegram") -> "Telegram"
            lower.contains("chrome") || lower.contains("browser") -> "Chrome"
            else -> ""
        }
        if (mapped.isNotBlank()) return mapped
        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.').ifBlank { "General" }
        }
        return safeFolderName(label)
    }

    private fun getAppMemoryDirectory(context: Context, appFolder: String): File {
        val root = File(context.filesDir, "Memory")
        val appDir = File(root, safeFolderName(appFolder))
        if (!appDir.exists()) appDir.mkdirs()
        return appDir
    }

    private fun saveBitmapLossless(bitmap: Bitmap, destination: File): Boolean {
        return try {
            FileOutputStream(destination).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            destination.exists() && destination.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Called only after the VLA loop has detected a stuck screen and a human corrects it.
     * Android accessibility click events do not expose raw touch coordinates, so x/y are the
     * center of the clicked node's screen bounds, not a claim about the exact finger contact.
     */
    fun saveHumanCorrectionEpisode(
        context: Context,
        packageName: String,
        taskGoal: String,
        beforeFrame: Bitmap?,
        afterFrame: Bitmap?,
        tapX: Float,
        tapY: Float,
        boundsText: String,
        buttonLabel: String
    ): Boolean {
        if (packageName.isBlank() || taskGoal.isBlank()) return false
        if (!tapX.isFinite() || !tapY.isFinite() || tapX < 0f || tapY < 0f) return false
        init(context)
        val folder = resolveAppFolder(context, packageName, taskGoal)
        val directory = getAppMemoryDirectory(context, folder)
        val stamp = System.currentTimeMillis()
        val beforeFile = beforeFrame?.let { File(directory, "human_correction_${stamp}_before.png") }
        val afterFile = afterFrame?.let { File(directory, "human_correction_${stamp}_after.png") }
        val beforeSaved = beforeFrame != null && beforeFile != null && saveBitmapLossless(beforeFrame, beforeFile)
        val afterSaved = afterFrame != null && afterFile != null && saveBitmapLossless(afterFrame, afterFile)
        val db = dbHelper?.writableDatabase ?: return false
        return try {
            db.beginTransaction()
            fun insertFrame(stage: String, file: File?) {
                val cv = ContentValues().apply {
                    put("package_name", packageName.take(180))
                    put("app_folder", folder)
                    put("task_goal", taskGoal.take(1000))
                    put("actor", "HUMAN_CORRECTION")
                    put("stage", stage)
                    put("image_path", file?.absolutePath.orEmpty())
                    put("tap_x", tapX)
                    put("tap_y", tapY)
                    put("bounds_text", boundsText.take(160))
                    put("button_label", buttonLabel.take(160))
                    put("timestamp", stamp)
                }
                db.insert("visual_memory", null, cv)
            }
            insertFrame("BEFORE", if (beforeSaved) beforeFile else null)
            insertFrame("AFTER", if (afterSaved) afterFile else null)
            db.setTransactionSuccessful()
            beforeSaved || afterSaved
        } catch (_: Exception) {
            try { beforeFile?.takeIf { !beforeSaved }?.delete() } catch (_: Exception) {}
            try { afterFile?.takeIf { !afterSaved }?.delete() } catch (_: Exception) {}
            false
        } finally {
            db.endTransaction()
        }
    }

    /** Visual memory is intentionally fetched on demand, only when the agent detects a stall. */
    fun retrieveVisualMemoryForStuck(context: Context, packageName: String, taskGoal: String): String {
        init(context)
        val db = dbHelper?.readableDatabase ?: return ""
        val folder = resolveAppFolder(context, packageName, taskGoal)
        val queryTokens = taskGoal.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val rows = mutableListOf<Triple<Int, String, String>>()
        try {
            db.rawQuery(
                "SELECT task_goal, stage, button_label, golden_fix, tap_x, tap_y FROM visual_memory WHERE package_name = ? AND app_folder = ? ORDER BY timestamp DESC LIMIT 40",
                arrayOf(packageName, folder)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val oldGoal = cursor.getString(0).orEmpty()
                    val stage = cursor.getString(1).orEmpty()
                    val label = cursor.getString(2).orEmpty()
                    val fix = cursor.getString(3).orEmpty()
                    val x = cursor.getFloat(4)
                    val y = cursor.getFloat(5)
                    val oldTokens = oldGoal.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
                    val score = queryTokens.intersect(oldTokens).size
                    val text = when {
                        fix.isNotBlank() -> "Golden Fix for '$oldGoal': $fix"
                        stage == "BEFORE" || stage == "AFTER" ->
                            "Prior HUMAN_CORRECTION: task='$oldGoal', stage=$stage, user-selected node='$label', node-center=($x,$y). Inspect saved screenshots before reusing this hint."
                        else -> ""
                    }
                    if (text.isNotBlank()) rows.add(Triple(score, oldGoal, text))
                }
            }
        } catch (_: Exception) {
            return ""
        }
        return rows.sortedByDescending { it.first }.take(5).joinToString("\n").take(5000)
    }

    private fun saveGoldenFixRecord(
        context: Context,
        packageName: String,
        appFolder: String,
        taskGoal: String,
        goldenFix: String,
        imagePaths: List<String> = emptyList()
    ) {
        init(context)
        val db = dbHelper?.writableDatabase ?: return
        val now = System.currentTimeMillis()
        val directory = getAppMemoryDirectory(context, appFolder)
        try {
            File(directory, "golden-fixes.txt").appendText(
                "\n\n[$now] Goal: ${taskGoal.take(500)}\n$goldenFix\n"
            )
        } catch (_: Exception) {
        }
        val cv = ContentValues().apply {
            put("package_name", packageName.take(180))
            put("app_folder", appFolder)
            put("task_goal", taskGoal.take(1000))
            put("actor", "AI_DEEP_ANALYSIS")
            put("stage", "GOLDEN_FIX")
            put("image_path", imagePaths.firstOrNull().orEmpty())
            put("button_label", "")
            put("golden_fix", goldenFix.take(8000))
            put("timestamp", now)
        }
        try { db.insert("visual_memory", null, cv) } catch (_: Exception) {}
    }

    /** High-effort post-task analysis runs in the background after a completed task. */
    suspend fun analyzeHumanCorrectionsAndSaveGoldenFix(
        context: Context,
        packageName: String,
        taskGoal: String
    ): String = withContext(Dispatchers.IO) {
        init(context)
        val db = dbHelper?.readableDatabase ?: return@withContext ""
        val folder = resolveAppFolder(context, packageName, taskGoal)
        val frames = mutableListOf<VisualFrameRecord>()
        try {
            db.rawQuery(
                "SELECT id, package_name, app_folder, task_goal, stage, image_path, button_label, tap_x, tap_y, golden_fix FROM visual_memory WHERE package_name = ? AND app_folder = ? AND actor = 'HUMAN_CORRECTION' ORDER BY timestamp DESC LIMIT 8",
                arrayOf(packageName, folder)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    frames.add(
                        VisualFrameRecord(
                            cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                            cursor.getString(3), cursor.getString(4), cursor.getString(5),
                            cursor.getString(6), cursor.getFloat(7), cursor.getFloat(8),
                            cursor.getString(9)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return@withContext ""
        }
        val ordered = frames.asReversed()
        if (ordered.isEmpty()) return@withContext ""
        val images = mutableListOf<Bitmap>()
        val usedPaths = mutableListOf<String>()
        try {
            ordered.forEach { frame ->
                if (frame.imagePath.isNotBlank()) {
                    val bitmap = BitmapFactory.decodeFile(frame.imagePath)
                    if (bitmap != null) {
                        images.add(bitmap)
                        usedPaths.add(frame.imagePath)
                    }
                }
            }
            val correctionNotes = ordered.joinToString("\n") {
                "stage=${it.stage}, nodeCenter=(${it.tapX.toInt()},${it.tapY.toInt()}), bounds=${it.buttonLabel}, goal=${it.taskGoal}"
            }
            val prompt = """
You are reviewing a human correction for an Android screen-agent task after the task finished.
Compare the attached BEFORE and AFTER screenshots in chronological order. The accessibility API only provides the clicked node's center, not raw finger coordinates.
Task goal: ${taskGoal.take(1000)}
App package: $packageName
Correction event notes:
$correctionNotes
Create a concise Golden Fix with: screen/app context, what the human corrected, the safe action to repeat next time, visible state cue, and a caution for ambiguous screens. Do not infer credentials or private text. Never suggest re-toggling a control that is already in the desired state.
Return plain text only.
""".trimIndent()
            val (analysis, modelUsed) = GeminiBrain.callGeminiWithFallback(
                context = context,
                prompt = prompt,
                bitmaps = images,
                preferPro = true,
                taskType = "HIGH_BRAIN_VISION"
            )
            val fix = analysis.trim().takeIf { it.isNotBlank() }.orEmpty()
            if (fix.isNotBlank()) {
                saveGoldenFixRecord(context, packageName, folder, taskGoal, "Model=$modelUsed\n$fix", usedPaths)
            }
            fix
        } finally {
            images.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
    }

    /** Extract a bounded set of keyframes from a selected MP4 and learn its visible workflow. */
    suspend fun trainMemoryFromVideo(
        context: Context,
        videoUri: Uri,
        description: String
    ): String = withContext(Dispatchers.IO) {
        val note = description.trim()
        if (note.isBlank()) return@withContext "Write a short description of what the recording demonstrates first."
        val appFolder = resolveAppFolder(context, "", note)
        val packageName = when (appFolder) {
            "Instagram" -> "com.instagram.android"
            "YouTube" -> "com.google.android.youtube"
            "WhatsApp" -> "com.whatsapp"
            "Telegram" -> "org.telegram.messenger"
            "Chrome" -> "com.android.chrome"
            else -> "user-trained-${safeFolderName(appFolder).lowercase(Locale.US)}"
        }
        val directory = getAppMemoryDirectory(context, appFolder)
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        val paths = mutableListOf<String>()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
            val count = minOf(8, maxOf(1, (durationMs / 2000L).toInt()))
            val spacingMs = if (count <= 1) 0L else (durationMs / count).coerceAtLeast(250L)
            val stamp = System.currentTimeMillis()
            for (index in 0 until count) {
                val timestampMs = (index * spacingMs).coerceAtMost(durationMs - 1L)
                val frame = try {
                    retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) { null } ?: continue
                val scaled = if (frame.width > 1280) {
                    val targetHeight = (frame.height * (1280f / frame.width)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(frame, 1280, targetHeight, true).also {
                        if (it !== frame) frame.recycle()
                    }
                } else frame
                val output = File(directory, "video_training_${stamp}_${index + 1}.png")
                if (saveBitmapLossless(scaled, output)) paths.add(output.absolutePath)
                frames.add(scaled)
            }
            if (frames.isEmpty()) return@withContext "Unable to extract readable frames from this video."
            val prompt = """
You are the visual-workflow teacher for AuraDroid OS.
The user-uploaded screen recording was described as:
$note
The attached frames are selected in chronological order from the same recording. Infer the app/workflow only from visible evidence. Describe the steps sequentially, identify visible buttons and state changes, and provide a safe reusable Golden Fix for ambiguous popups. Never infer or preserve passwords, one-time codes, payment details, or private message contents.
Return plain text with a short workflow title, numbered visible steps, cues for when each step applies, and warnings about uncertain transitions.
""".trimIndent()
            val (analysis, modelUsed) = GeminiBrain.callGeminiWithFallback(
                context = context,
                prompt = prompt,
                bitmaps = frames,
                preferPro = true,
                taskType = "HIGH_BRAIN_VISION"
            )
            val result = analysis.trim()
            if (result.isBlank()) return@withContext "The video frames were extracted, but the free-tier model ladder returned no analysis."
            saveGoldenFixRecord(context, packageName, appFolder, note, "Video-trained workflow (model=$modelUsed)\n$result", paths)
            "Learned ${frames.size} keyframes for $appFolder.\n$result"
        } catch (error: Exception) {
            "Video training failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            frames.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
    }

    /**
     * Resolve app-specific memory beneath private app storage. No normal VLA step calls this;
     * the phone agent asks for hints only after repeated failure or a human correction.
     */
    fun appMemoryFolder(context: Context, appNameOrPackage: String): File {
        val raw = appNameOrPackage.trim().lowercase(Locale.US)
        val appKey = when {
            raw.contains("instagram") -> "Instagram"
            raw.contains("youtube") -> "YouTube"
            raw.contains("whatsapp") -> "WhatsApp"
            raw.contains("chrome") -> "Chrome"
            raw.contains("telegram") -> "Telegram"
            else -> raw.substringAfterLast('.').ifBlank { "UnknownApp" }
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .take(60)
                .replaceFirstChar { it.uppercase(Locale.US) }
        }
        return File(File(context.applicationContext.filesDir, "Memory"), appKey).apply { mkdirs() }
    }

    private fun saveBitmapJpeg(bitmap: Bitmap?, destination: File): Boolean {
        if (bitmap == null || bitmap.isRecycled) return false
        return try {
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                output.flush()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Save the before/after screen and accessibility target data only when the caller has
     * established that the agent is stuck and a human correction likely occurred.
     */
    fun saveHumanCorrection(
        context: Context,
        packageName: String,
        beforeScreenshot: Bitmap?,
        targetCenterX: Float,
        targetCenterY: Float,
        targetLabel: String,
        targetBounds: String,
        afterScreenshot: Bitmap?
    ): Boolean {
        if (beforeScreenshot == null || afterScreenshot == null) return false
        val folder = appMemoryFolder(context, packageName)
        val stamp = System.currentTimeMillis()
        val beforeFile = File(folder, "correction_${stamp}_before.jpg")
        val afterFile = File(folder, "correction_${stamp}_after.jpg")
        if (!saveBitmapJpeg(beforeScreenshot, beforeFile)) return false
        if (!saveBitmapJpeg(afterScreenshot, afterFile)) {
            beforeFile.delete()
            return false
        }
        return try {
            val manifest = JSONObject()
                .put("actor", "HUMAN_CORRECTION")
                .put("packageName", packageName.take(200))
                .put("timestamp", stamp)
                .put("targetCenterX", targetCenterX)
                .put("targetCenterY", targetCenterY)
                .put("targetLabel", targetLabel.replace(Regex("\\s+"), " ").take(120))
                .put("targetBounds", targetBounds.take(120))
                .put("beforeScreenshot", beforeFile.name)
                .put("afterScreenshot", afterFile.name)
                .put("coordinateSource", "Accessibility node center; raw finger coordinates are not exposed by TYPE_VIEW_CLICKED")
            File(folder, "correction_${stamp}.json").writeText(manifest.toString(2), Charsets.UTF_8)
            true
        } catch (_: Exception) {
            beforeFile.delete()
            afterFile.delete()
            false
        }
    }

    fun getAppSpecificMemoryHints(context: Context, appNameOrPackage: String, goal: String): String {
        val folder = appMemoryFolder(context, appNameOrPackage)
        if (!folder.exists()) return ""
        return try {
            val files = folder.listFiles()
                ?.filter { it.isFile && (it.extension.equals("json", true) || it.extension.equals("txt")) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5)
                .orEmpty()
            if (files.isEmpty()) return ""
            buildString {
                append("App-specific learned memory for ")
                    .append(appNameOrPackage.take(100))
                    .append(". Verify every hint against the current screenshot. Goal: ")
                    .append(goal.take(220))
                    .append('\n')
                files.forEach { file ->
                    val content = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                    if (content.isNotBlank()) {
                        append("\n")
                        append(content.take(1300))
                        append('\n')
                    }
                }
            }.take(6000)
        } catch (_: Exception) {
            ""
        }
    }

    fun saveGoldenFix(
        context: Context,
        appNameOrPackage: String,
        goal: String,
        analysis: String
    ): Boolean {
        val cleanAnalysis = analysis.trim().take(12000)
        if (cleanAnalysis.isBlank()) return false
        val folder = appMemoryFolder(context, appNameOrPackage)
        val stamp = System.currentTimeMillis()
        return try {
            val record = JSONObject()
                .put("actor", "GOLDEN_FIX")
                .put("app", appNameOrPackage.take(200))
                .put("goal", goal.take(800))
                .put("createdAt", stamp)
                .put("analysis", cleanAnalysis)
            File(folder, "golden_fix_${stamp}.json").writeText(record.toString(2), Charsets.UTF_8)
            recordSuccessfulLesson(context, goal, appNameOrPackage, cleanAnalysis)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveLearningKeyframe(
        context: Context,
        appNameOrPackage: String,
        bitmap: Bitmap,
        frameIndex: Int
    ): String? {
        val folder = appMemoryFolder(context, appNameOrPackage)
        val destination = File(folder, "training_frame_${System.currentTimeMillis()}_${frameIndex.coerceAtLeast(0)}.jpg")
        return if (saveBitmapJpeg(bitmap, destination)) destination.absolutePath else null
    }

    fun saveVideoTrainingRecord(
        context: Context,
        appNameOrPackage: String,
        userDescription: String,
        analysis: String,
        keyframePaths: List<String>
    ): Boolean {
        val folder = appMemoryFolder(context, appNameOrPackage)
        val stamp = System.currentTimeMillis()
        return try {
            val paths = JSONArray()
            keyframePaths.take(12).forEach { paths.put(File(it).name) }
            val record = JSONObject()
                .put("actor", "VIDEO_TRAINING")
                .put("app", appNameOrPackage.take(200))
                .put("userDescription", userDescription.take(1200))
                .put("analysis", analysis.take(12000))
                .put("keyframes", paths)
                .put("createdAt", stamp)
            File(folder, "video_training_${stamp}.json").writeText(record.toString(2), Charsets.UTF_8)
            recordSuccessfulLesson(context, userDescription, appNameOrPackage, analysis)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Loads a small set of old correction frames only when a caller detects a stall.
     * Callers own and must recycle returned bitmaps.
     */
    fun getAppMemoryScreenshots(
        context: Context,
        appNameOrPackage: String,
        limit: Int = 3
    ): List<Bitmap> {
        val folder = appMemoryFolder(context, appNameOrPackage)
        if (!folder.exists()) return emptyList()
        return try {
            folder.listFiles()
                ?.filter {
                    it.isFile &&
                        (it.name.contains("_before.") || it.name.contains("_after.")) &&
                        (it.extension.equals("jpg", true) || it.extension.equals("png", true))
                }
                ?.sortedByDescending { it.lastModified() }
                ?.take(limit.coerceIn(1, 5))
                ?.mapNotNull { file ->
                    try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
                }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getVisualMemorySummary(context: Context, limit: Int = 10): String {
        val root = File(context.applicationContext.filesDir, "Memory")
        if (!root.exists()) return "No app-specific visual memories saved yet."
        return try {
            val safeLimit = limit.coerceIn(1, 30)
            val rows = root.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?.flatMap { folder ->
                    folder.listFiles()
                        ?.filter { it.isFile && (it.name.startsWith("golden_fix") || it.name == "golden-fixes.txt" || it.name.startsWith("video_training") || it.name.startsWith("human_correction")) }
                        ?.sortedByDescending { it.lastModified() }
                        ?.take(safeLimit)
                        ?.map { file -> "${folder.name}/${file.name} (${file.length()} bytes)" }
                        .orEmpty()
                }
                ?.take(safeLimit)
                .orEmpty()
            if (rows.isEmpty()) "No app-specific visual memories saved yet."
            else rows.joinToString("\n")
        } catch (_: Exception) {
            "Visual memory index is unavailable."
        }
    }

    private fun tokenizeText(text: String): String {
        val words = text.lowercase(Locale.US).split(Regex("[^a-z0-9]+"))
        return words.filter { it.length > 2 }.distinct().joinToString(",")
    }
}
