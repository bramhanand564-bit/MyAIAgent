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
    private lateinit var queueContainer: LinearLayout

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
        queueContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }\n        queueText = MaterialTextView(this).apply {
            textSize = 15f
            setPadding(0, 20, 0, 0)
        }
        root.addView(title)
        root.addView(status)
        root.addView(addVideos)
        root.addView(accessibility)
        root.addView(queueText)\n        root.addView(queueContainer)
        setContentView(root)
        refreshQueue()
    }

    override fun onResume() {
        super.onResume()
        if (::queueStore.isInitialized) refreshQueue()
    }

    private fun refreshQueue() {
        val items = queueStore.load()
        queueText.text = "Upload Queue (" + items.size + ")"
        queueContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val time = item.scheduledAt?.let {
                java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(it)
            } ?: "Not scheduled"
            val row = MaterialButton(this).apply {
                text = (index + 1).toString() + ". " + item.fileName +
                    "\\n" + item.title.ifBlank { "Title not set" } +
                    " • " + item.visibility + " • " + time
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, QueueItemActivity::class.java).apply {
                        putExtra("item_id", item.id)
                    })
                }
            }
            queueContainer.addView(row)
        }
    }

