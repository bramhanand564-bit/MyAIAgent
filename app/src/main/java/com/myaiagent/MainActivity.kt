package com.myaiagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.folder.FolderVideoImporter
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore
import com.myaiagent.scheduler.UploadAlarmScheduler
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
            queueStore.add(
                UploadItem(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    fileName = resolveName(uri)
                )
            )
        }
        refreshQueue()
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        persistTreePermission(uri)
        val imported = FolderVideoImporter.importVideos(this, uri)
        if (imported.isNotEmpty()) {
            val current = queueStore.load().toMutableList()
            current.addAll(imported)
            queueStore.save(current)
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
        root.addView(MaterialTextView(this).apply {
            text = "MyAIAgent"
            textSize = 28f
        })
        root.addView(MaterialTextView(this).apply {
            text = "Automation workspace
Embedded YouTube + External YouTube fallback"
            textSize = 16f
            setPadding(0, 20, 0, 20)
        })
        root.addView(MaterialButton(this).apply {
            text = "Open Embedded YouTube"
            setOnClickListener { startActivity(Intent(this@MainActivity, YouTubeWorkspaceActivity::class.java)) }
        })
        root.addView(MaterialButton(this).apply {
            text = "Add Videos"
            setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        })
        root.addView(MaterialButton(this).apply {
            text = "Add Folder"
            setOnClickListener { pickFolder.launch(null) }
        })
        root.addView(MaterialButton(this).apply {
            text = "Enable Automation Service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(MaterialButton(this).apply {
            text = "Enable Exact Scheduling"
            setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val alarmManager = getSystemService(android.app.AlarmManager::class.java)
                    if (!alarmManager.canScheduleExactAlarms()) {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:" + packageName)
                        })
                    }
                }
            }
        })
        queueText = MaterialTextView(this).apply {
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }
        queueContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(queueText)
        root.addView(queueContainer)
        setContentView(root)
        refreshQueue()
        UploadAlarmScheduler.rescheduleAll(this, queueStore.load())
    }

    override fun onResume() {
        super.onResume()
        if (::queueStore.isInitialized) {
            refreshQueue()
            UploadAlarmScheduler.rescheduleAll(this, queueStore.load())
        }
    }

    private fun refreshQueue() {
        val items = queueStore.load()
        queueText.text = "Upload Queue (" + items.size + ")"
        queueContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val time = item.scheduledAt?.let {
                java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(it)
            } ?: "Not scheduled"
            queueContainer.addView(MaterialButton(this).apply {
                text = (index + 1).toString() + ". " + item.fileName +
                    "
" + item.title.ifBlank { "Title not set" } +
                    " • " + item.visibility +
                    " • " + time +
                    "
Mode: " + item.automationMode
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, QueueItemActivity::class.java).apply {
                        putExtra("item_id", item.id)
                    })
                }
            })
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
    }

    private fun persistTreePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
    }

    private fun resolveName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: "video"
    }
}
