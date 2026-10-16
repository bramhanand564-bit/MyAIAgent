package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Separate database for story continuity and training assets.
 * It deliberately does not alter AuraDroid's existing brain database or tables.
 */
data class SavedStoryEpisodePlan(
    val seriesName: String,
    val episodeNumber: Int,
    val storyMode: String,
    val characterLock: String,
    val episodeTitle: String,
    val plotSummary: String,
    val videoPrompt: String,
    val durationSeconds: Int
)

class TrainingMemoryStore(context: Context) {
    private val helper = TrainingDatabase(context.applicationContext)

    fun saveStoryPart(series: String, partNumber: Int, storyText: String, dialogue: String): Long {
        require(series.isNotBlank()) { "Story/series name is required." }
        require(partNumber in 1..10000) { "Part number must be between 1 and 10000." }
        require(storyText.isNotBlank()) { "Story text is required." }
        val values = ContentValues().apply {
            put("series_key", series.trim().lowercase())
            put("series_name", series.trim().take(160))
            put("part_number", partNumber)
            put("story_text", storyText.trim().take(20000))
            put("dialogue", dialogue.trim().take(10000))
            put("updated_at", System.currentTimeMillis())
        }
        return helper.writableDatabase.insertWithOnConflict(
            "story_parts", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getStoryContext(series: String, beforePart: Int? = null, limit: Int = 5): String {
        if (series.isBlank()) return ""
        val sql = if (beforePart == null) {
            "SELECT part_number, story_text, dialogue FROM story_parts WHERE series_key = ? ORDER BY part_number DESC LIMIT ?"
        } else {
            "SELECT part_number, story_text, dialogue FROM story_parts WHERE series_key = ? AND part_number < ? ORDER BY part_number DESC LIMIT ?"
        }
        val args = if (beforePart == null) {
            arrayOf(series.trim().lowercase(), limit.coerceIn(1, 10).toString())
        } else {
            arrayOf(series.trim().lowercase(), beforePart.toString(), limit.coerceIn(1, 10).toString())
        }
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    "Part ${cursor.getInt(0)}\nStory: ${cursor.getString(1)}\nHindi dialogue: ${cursor.getString(2) ?: ""}"
                )
            }
        }
        return rows.asReversed().joinToString("\n\n")
    }

    fun listStorySeries(): String {
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT series_name, COUNT(*), MAX(part_number) FROM story_parts GROUP BY series_key ORDER BY MAX(updated_at) DESC LIMIT 20",
            null
        ).use { c ->
            while (c.moveToNext()) rows.add("${c.getString(0)} • ${c.getInt(1)} saved part(s) • latest Part ${c.getInt(2)}")
        }
        return if (rows.isEmpty()) "No story parts saved yet." else rows.joinToString("\n")
    }

    fun saveTaskEpisode(kind: String, summary: String, detail: String = "") {
        if (summary.isBlank()) return
        val cv = ContentValues().apply {
            put("kind", kind.take(80))
            put("summary", summary.take(1000))
            put("detail", detail.take(5000))
            put("created_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insert("task_episodes", null, cv)
    }

    fun recentTaskEpisodes(limit: Int = 10): String {
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT kind, summary, created_at FROM task_episodes ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 50).toString())
        ).use { c ->
            while (c.moveToNext()) rows.add("[${c.getString(0)}] ${c.getString(1)}")
        }
        return if (rows.isEmpty()) "No recorded task episodes yet." else rows.joinToString("\n")
    }

    fun addTrainingAsset(uri: String, displayName: String) {
        val cv = ContentValues().apply {
            put("uri", uri.take(2000))
            put("display_name", displayName.take(300))
            put("created_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insert("training_assets", null, cv)
    }

    fun listTrainingAssets(limit: Int = 10): String {
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT display_name, uri FROM training_assets ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 50).toString())
        ).use { c ->
            while (c.moveToNext()) rows.add("• ${c.getString(0)}")
        }
        return if (rows.isEmpty()) "No screenshots selected." else rows.joinToString("\n")
    }

    /**
     * Save a validated, editable automation skill. Updating a blueprint resets its live
     * verification flag so changed steps are never treated as already calibrated.
     */
    fun saveTrainedSkillBlueprint(skillName: String, jsonBlueprint: String): String {
        val blueprint = AutomationBlueprint.fromJson(jsonBlueprint)
        val cleanName = normalizeSkillName(skillName.ifBlank { blueprint.name })
        require(cleanName.isNotBlank()) { "Skill name is required." }
        require(jsonBlueprint.length <= 1_000_000) { "Blueprint JSON is too large." }
        val cv = ContentValues().apply {
            put("skill_name", cleanName)
            put("json_blueprint", blueprint.toJson())
            put("is_live_verified", 0)
            put("timestamp", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict(
            "trained_skills", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
        saveTaskEpisode("SKILL_SAVED", "Saved skill $cleanName; live verification reset.")
        return cleanName
    }

    fun getTrainedSkillBlueprint(skillName: String): String? {
        val name = normalizeSkillName(skillName)
        if (name.isBlank()) return null
        helper.readableDatabase.rawQuery(
            "SELECT json_blueprint FROM trained_skills WHERE skill_name = ?",
            arrayOf(name)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun isSkillLiveVerified(skillName: String): Boolean {
        val name = normalizeSkillName(skillName)
        if (name.isBlank()) return false
        helper.readableDatabase.rawQuery(
            "SELECT is_live_verified FROM trained_skills WHERE skill_name = ?",
            arrayOf(name)
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) == 1
        }
    }

    /**
     * Verification is recorded only after an explicit human confirmation and at least one
     * visible VERIFY step in the blueprint.
     */
    fun markSkillLiveVerified(skillName: String, verified: Boolean = true): Boolean {
        val name = normalizeSkillName(skillName)
        if (name.isBlank()) return false
        if (verified) {
            val json = getTrainedSkillBlueprint(name) ?: return false
            val blueprint = try { AutomationBlueprint.fromJson(json) } catch (_: Exception) { return false }
            if (blueprint.steps.none { it.action == BlueprintAction.VERIFY }) return false
        }
        val cv = ContentValues().apply {
            put("is_live_verified", if (verified) 1 else 0)
            put("timestamp", System.currentTimeMillis())
        }
        return helper.writableDatabase.update(
            "trained_skills", cv, "skill_name = ?", arrayOf(name)
        ) > 0
    }

    fun getAllTrainedSkillsSummary(): String {
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT skill_name, is_live_verified, timestamp FROM trained_skills ORDER BY timestamp DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    "${c.getString(0)} • ${if (c.getInt(1) == 1) "LIVE-VERIFIED" else "UNVERIFIED"}"
                )
            }
        }
        return if (rows.isEmpty()) "No trained skills saved yet." else rows.joinToString("\n")
    }

    /**
     * Only live-verified skills are sent to the planning model. Match is based on the task
     * description and a normalized skill name (e.g. GOOGLE_FLOW matches Google Flow tasks).
     */
    fun getVerifiedSkillBlueprintForTask(taskDescription: String): String {
        val task = taskDescription.lowercase().replace('_', ' ').replace('-', ' ')
        if (task.isBlank()) return ""
        val candidates = mutableListOf<Pair<String, String>>()
        helper.readableDatabase.rawQuery(
            "SELECT skill_name, json_blueprint FROM trained_skills WHERE is_live_verified = 1 ORDER BY timestamp DESC",
            null
        ).use { c ->
            while (c.moveToNext()) candidates.add(c.getString(0) to c.getString(1))
        }
        val scored = candidates.mapNotNull { (name, json) ->
            val normalized = name.lowercase().replace('_', ' ').replace('-', ' ')
            val tokens = normalized.split(Regex("\\s+")).filter { it.length >= 3 }
            val score = when {
                normalized.isNotBlank() && task.contains(normalized) -> 100 + tokens.size
                else -> tokens.count { token -> Regex("(?<![a-z0-9])" + Regex.escape(token) + "(?![a-z0-9])").containsMatchIn(task) }
            }
            if (score > 0) score to json else null
        }
        return scored.maxByOrNull { it.first }?.second.orEmpty().take(40000)
    }

    private fun normalizeSkillName(value: String): String =
        value.trim().uppercase()
            .replace(Regex("[^A-Z0-9 _-]"), "_")
            .replace(Regex("[\\s-]+"), "_")
            .take(80)
            .trim('_')

    fun saveStoryEpisodePlan(
        seriesName: String,
        episodeNumber: Int,
        storyMode: String,
        characterLock: String,
        episodeTitle: String,
        plotSummary: String,
        videoPrompt: String,
        durationSeconds: Int
    ) {
        require(seriesName.isNotBlank()) { "Story series is required." }
        require(episodeNumber in 1..10000) { "Episode number is invalid." }
        require(durationSeconds in 5..60) { "Duration must be between 5 and 60 seconds." }
        require(videoPrompt.isNotBlank()) { "A video prompt is required." }
        val cv = ContentValues().apply {
            put("series_key", seriesName.trim().lowercase())
            put("series_name", seriesName.trim().take(160))
            put("episode_number", episodeNumber)
            put("story_mode", storyMode.take(32))
            put("character_lock", characterLock.take(1200))
            put("episode_title", episodeTitle.take(300))
            put("plot_summary", plotSummary.take(1000))
            put("video_prompt", videoPrompt.take(12000))
            put("duration_seconds", durationSeconds)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict(
            "story_episode_plans", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getPreviousEpisodePlansContext(
        seriesName: String,
        beforeEpisodeNumber: Int? = null,
        limit: Int = 6
    ): String {
        if (seriesName.isBlank()) return ""
        val sql = if (beforeEpisodeNumber == null) {
            "SELECT episode_number, story_mode, episode_title, plot_summary, duration_seconds FROM story_episode_plans WHERE series_key = ? ORDER BY episode_number DESC LIMIT ?"
        } else {
            "SELECT episode_number, story_mode, episode_title, plot_summary, duration_seconds FROM story_episode_plans WHERE series_key = ? AND episode_number < ? ORDER BY episode_number DESC LIMIT ?"
        }
        val args = if (beforeEpisodeNumber == null) {
            arrayOf(seriesName.trim().lowercase(), limit.coerceIn(1, 10).toString())
        } else {
            arrayOf(seriesName.trim().lowercase(), beforeEpisodeNumber.toString(), limit.coerceIn(1, 10).toString())
        }
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    "Part ${c.getInt(0)} [${c.getString(1)}] • ${c.getString(2)} • ${c.getInt(4)}s\n${c.getString(3)}"
                )
            }
        }
        return rows.asReversed().joinToString("\n\n")
    }

    fun getStoryArcReport(seriesName: String): String {
        if (seriesName.isBlank()) return "Enter a story/series name first."
        val rows = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT episode_number, story_mode, episode_title, plot_summary, duration_seconds, updated_at FROM story_episode_plans WHERE series_key = ? ORDER BY episode_number ASC",
            arrayOf(seriesName.trim().lowercase())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    "Part ${c.getInt(0)} • ${c.getString(1)} • ${c.getInt(4)}s\n${c.getString(2)}\n${c.getString(3)}"
                )
            }
        }
        return if (rows.isEmpty()) "No saved episode prompts for this series yet." else rows.joinToString("\n\n")
    }

    fun getLatestStoryEpisodePlan(): SavedStoryEpisodePlan? {
        helper.readableDatabase.rawQuery(
            "SELECT series_name, episode_number, story_mode, character_lock, episode_title, plot_summary, video_prompt, duration_seconds FROM story_episode_plans ORDER BY updated_at DESC LIMIT 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return SavedStoryEpisodePlan(
                seriesName = c.getString(0),
                episodeNumber = c.getInt(1),
                storyMode = c.getString(2),
                characterLock = c.getString(3),
                episodeTitle = c.getString(4),
                plotSummary = c.getString(5),
                videoPrompt = c.getString(6),
                durationSeconds = c.getInt(7)
            )
        }
    }

    /**
     * Deletes only the selected series' story records, never skills, screenshots or task memory.
     * UI must ask for explicit confirmation before calling this method.
     */
    fun resetStoryArcHistory(seriesName: String): Int {
        require(seriesName.isNotBlank()) { "Series name is required." }
        val key = seriesName.trim().lowercase()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val plans = db.delete("story_episode_plans", "series_key = ?", arrayOf(key))
            db.delete("story_parts", "series_key = ?", arrayOf(key))
            db.setTransactionSuccessful()
            return plans
        } finally {
            db.endTransaction()
        }
    }

    fun integrityCheck(): Boolean {
        helper.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { c ->
            return c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
        }
    }

    private class TrainingDatabase(context: Context) :
        SQLiteOpenHelper(context, "myai_training_memory.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS story_parts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    series_key TEXT NOT NULL,
                    series_name TEXT NOT NULL,
                    part_number INTEGER NOT NULL,
                    story_text TEXT NOT NULL,
                    dialogue TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL,
                    UNIQUE(series_key, part_number) ON CONFLICT REPLACE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_episodes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    detail TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS training_assets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uri TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS story_episode_plans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    series_key TEXT NOT NULL,
                    series_name TEXT NOT NULL,
                    episode_number INTEGER NOT NULL,
                    story_mode TEXT NOT NULL,
                    character_lock TEXT NOT NULL DEFAULT '',
                    episode_title TEXT NOT NULL,
                    plot_summary TEXT NOT NULL,
                    video_prompt TEXT NOT NULL,
                    duration_seconds INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(series_key, episode_number) ON CONFLICT REPLACE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trained_skills (
                    skill_name TEXT PRIMARY KEY,
                    json_blueprint TEXT NOT NULL,
                    is_live_verified INTEGER NOT NULL DEFAULT 0,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // onCreate is idempotent because every table uses IF NOT EXISTS; preserve all old data.
            onCreate(db)
        }
    }
}
