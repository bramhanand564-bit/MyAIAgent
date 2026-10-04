package com.myaiagent.queue

import android.content.Context
import com.myaiagent.model.UploadItem
import org.json.JSONArray
import org.json.JSONObject

class UploadQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("upload_queue", Context.MODE_PRIVATE)

    fun load(): MutableList<UploadItem> {
        val array = JSONArray(prefs.getString("items", "[]") ?: "[]")
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
                status = o.optString("status", "QUEUED"),
                automationMode = o.optString("automationMode", "EMBEDDED_WEB")
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
                put("automationMode", item.automationMode)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun add(item: UploadItem): Boolean {
        val items = load()
        if (items.any { it.uri == item.uri }) return false
        items.add(item)
        save(items)
        return true
    }

    fun addAllUnique(itemsToAdd: List<UploadItem>): Int {
        val items = load()
        val knownUris = items.mapTo(mutableSetOf()) { it.uri }
        var added = 0
        itemsToAdd.forEach { item ->
            if (knownUris.add(item.uri)) {
                items.add(item)
                added++
            }
        }
        if (added > 0) save(items)
        return added
    }

    fun update(item: UploadItem) {
        val items = load()
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = item
            save(items)
        }
    }

    fun remove(itemId: String): Boolean {
        val items = load()
        val removed = items.removeAll { it.id == itemId }
        if (removed) save(items)
        return removed
    }

    fun resetForRetry(itemId: String) {
        val item = load().firstOrNull { it.id == itemId } ?: return
        update(item.copy(status = "QUEUED"))
    }
}
