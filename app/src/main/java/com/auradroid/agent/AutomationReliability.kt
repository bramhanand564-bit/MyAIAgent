package com.auradroid.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class AuradroidBrainDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "auradroid_brain.db"
        private const val DATABASE_VERSION = 1

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
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SKILLS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RAG")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORIES")
        onCreate(db)
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

    private fun tokenizeText(text: String): String {
        val words = text.lowercase(Locale.US).split(Regex("[^a-z0-9]+"))
        return words.filter { it.length > 2 }.distinct().joinToString(",")
    }
}
