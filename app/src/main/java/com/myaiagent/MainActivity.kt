package com.myaiagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var queueStore: UploadQueueStore
    private lateinit var queueText: MaterialTextView

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            persistReadPermission(uri)
            queueStore.add(UploadItem(UUID.randomUUID().toString(), uri.toString(), resolveName(uri)))
        }
        refreshQueue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queueStore = UploadQueueStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = MaterialTextView(this).apply {
            text = "MyAIAgent"
            textSize = 28f
        }
        val status = MaterialTextView(this).apply {
            text = "Automation workspace\nEmbedded YouTube + External YouTube fallback"
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        val addVideos = MaterialButton(this).apply {
            text = "Add Videos"
            setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        }
        val accessibility = MaterialButton(this).apply {
            text = "Enable Automation Service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        queueText = MaterialTextView(this).apply {
            textSize = 15f
            setPadding(0, 20, 0, 0)
        }
        root.addView(title); root.addView(status); root.addView(addVideos)
        root.addView(accessibility); root.addView(queueText)
        setContentView(root)
        refreshQueue()
    }

    private fun refreshQueue() {
        val items = queueStore.load()
        queueText.text = if (items.isEmpty()) "Queue is empty." else
            "Upload Queue (" + items.size + ")\n\n" +
            items.mapIndexed { index, item ->
                (index + 1).toString() + ". " + item.fileName + "\n   Status: " + item.status
            }.joinToString("\n\n")
    }

    private fun persistReadPermission(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) {}
    }

    private fun resolveName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "video"
    }
}
