package com.myaiagent.queue

import android.content.Context
import com.myaiagent.model.UploadItem
import org.json.JSONArray
import org.json.JSONObject

class UploadQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("upload_queue", Context.MODE_PRIVATE)

    fun load(): MutableList<UploadItem> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val array = JSONArray(raw)
        return MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            UploadItem(
                id = o.getString("id"),
                uri = o.getString("uri"),
                fileName = o.getString("fileName"),
                title = o.optString("title"),
                description = o.optString("description"),
                thumbnailUri = o.optString("thumbnailUri").ifBlank { null },
                visibility = o.optString("visibility", "PRIVATE"),
                scheduledAt = if (o.has("scheduledAt") && !o.isNull("scheduledAt")) o.getLong("scheduledAt") else null,
                status = o.optString("status", "QUEUED")
            )
        }
    }

    fun save(items: List<UploadItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("uri", item.uri)
                put("fileName", item.fileName)
                put("title", item.title)
                put("description", item.description)
                put("thumbnailUri", item.thumbnailUri)
                put("visibility", item.visibility)
                if (item.scheduledAt == null) put("scheduledAt", JSONObject.NULL) else put("scheduledAt", item.scheduledAt)
                put("status", item.status)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun add(item: UploadItem) {
        val items = load()
        items.add(item)
        save(items)
    }
}
