package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class AuradroidBrainDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "auradroid_brain.db"
        private const val DATABASE_VERSION = 2

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
        try {
            db.rawQuery(
                "SELECT goal_query, lesson_fix FROM vector_rag_memory ORDER BY timestamp DESC LIMIT 5",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val q = cursor.getString(0) ?: ""
                    val fix = cursor.getString(1) ?: ""
                    sb.append("• Goal: $q ➔ Fix: $fix\n")
                }
            }
        } catch (_: Exception) {
        }
        val res = sb.toString().trim()
        return if (res.isBlank()) "No prior RAG lessons recorded." else res
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
        if (cleanLabel.contains(sensitive)) return false

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
                    "SELECT center_x, center_y FROM shadow_teach_events WHERE package_name = ? AND event_type = 'VIEW_CLICKED' AND timestamp >= ? ORDER BY timestamp DESC LIMIT 1",
                    arrayOf(packageSafe, (now - 8000L).toString())
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
                        .append(cursor.getString(5)).append(correction).append("\\n")
                }
                if (out.isEmpty()) "No passive teach events yet." else out.toString().trim()
            }
        } catch (_: Exception) {
            "Passive teach history unavailable."
        }
    }

    private fun tokenizeText(text: String): String {
        val words = text.lowercase(Locale.US).split(Regex("[^a-z0-9]+"))
        return words.filter { it.length > 2 }.distinct().joinToString(",")
    }
}
