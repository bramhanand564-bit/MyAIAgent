package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Separate database for story continuity and training assets.
 * It deliberately does not alter AuraDroid's existing brain database or tables.
 */
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

    fun integrityCheck(): Boolean {
        helper.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { c ->
            return c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
        }
    }

    private class TrainingDatabase(context: Context) :
        SQLiteOpenHelper(context, "myai_training_memory.db", null, 1) {
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Additive migration point. Never drop existing user data.
        }
    }
}
