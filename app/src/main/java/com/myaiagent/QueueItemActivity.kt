package com.myaiagent

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.AdapterView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.model.UploadItem
import com.myaiagent.queue.UploadQueueStore
import com.myaiagent.scheduler.UploadAlarmScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class QueueItemActivity : AppCompatActivity() {
    private lateinit var store: UploadQueueStore
    private lateinit var item: UploadItem
    private lateinit var titleInput: TextInputEditText
    private lateinit var descriptionInput: TextInputEditText
    private lateinit var visibility: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var scheduleText: MaterialTextView
    private var scheduledAt: Long? = null
    private var selectedVisibilityIndex = 0
    private var selectedModeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = UploadQueueStore(this)

        val id = intent.getStringExtra("item_id") ?: run { finish(); return }
        item = store.load().firstOrNull { it.id == id } ?: run { finish(); return }
        scheduledAt = item.scheduledAt

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(32))
            setBackgroundColor(Color.rgb(8, 9, 13))
        }

        root.addView(MaterialTextView(this).apply {
            text = "Upload"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(MaterialTextView(this).apply {
            text = item.fileName
            textSize = 14f
            setTextColor(Color.rgb(145,150,164))
            setPadding(0, dp(6), 0, dp(10))
        })
        root.addView(MaterialTextView(this).apply {
            val run = item.lastRunAt?.let {
                java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(it)
            } ?: "Never"
            text = "Status: " + item.status + "\nLast run: " + run + "\nResult: " + item.resultNote.ifBlank { "—" }
            textSize = 12f
            setTextColor(Color.rgb(132,137,151))
            setPadding(0, 0, 0, dp(18))
        })

        titleInput = TextInputEditText(this).apply { setText(item.title) }
        root.addView(TextInputLayout(this).apply {
            hint = "YouTube Title"
            addView(titleInput)
        })

        descriptionInput = TextInputEditText(this).apply {
            setText(item.description)
            minLines = 5
            gravity = android.view.Gravity.TOP
        }
        root.addView(TextInputLayout(this).apply {
            hint = "Description"
            addView(descriptionInput)
        })

        visibility = Spinner(this)
        val visibilityValues = arrayOf("PRIVATE", "UNLISTED", "PUBLIC")
        visibility.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            visibilityValues
        )
        selectedVisibilityIndex = visibilityValues.indexOf(item.visibility).coerceAtLeast(0)
        visibility.setSelection(selectedVisibilityIndex)
        visibility.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedVisibilityIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(visibility)

        root.addView(MaterialTextView(this).apply {
            text = "YouTube Mode"
            textSize = 15f
            setPadding(0, 18, 0, 6)
        })
        modeSpinner = Spinner(this)
        val modes = listOf("NATIVE_STUDIO")
        selectedModeIndex = modes.indexOf(item.automationMode).coerceAtLeast(0)
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        modeSpinner.setSelection(selectedModeIndex)
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedModeIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(modeSpinner)

        scheduleText = MaterialTextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 12)
        }
        root.addView(scheduleText)
        refreshScheduleLabel()

        root.addView(MaterialButton(this).apply {
            text = "Set Schedule"
            setOnClickListener { chooseDateTime() }
        })

        root.addView(MaterialButton(this).apply {
            text = "Clear Schedule"
            setOnClickListener {
                scheduledAt = null
                refreshScheduleLabel()
            }
        })

        root.addView(MaterialButton(this).apply {
            text = "Save"
            setOnClickListener { saveItem() }
        })

        root.addView(MaterialButton(this).apply {
            text = "▶  Post now"
            isEnabled = item.status != "SUBMITTED" && item.status != "UPLOADED" && item.status != "RUNNING"
            setOnClickListener {
                UploadAlarmScheduler.cancel(this@QueueItemActivity, item.id)
                val updated = item.copy(
                    title = titleInput.text?.toString()?.trim().orEmpty(),
                    description = descriptionInput.text?.toString().orEmpty(),
                    visibility = arrayOf("PRIVATE", "UNLISTED", "PUBLIC")
                        .getOrElse(selectedVisibilityIndex) { "PRIVATE" },
                    automationMode = "NATIVE_STUDIO",
                    status = "QUEUED",
                    scheduledAt = null
                )
                store.update(updated)
                val serviceIntent = android.content.Intent(
                    this@QueueItemActivity,
                    com.myaiagent.automation.UploadRunnerService::class.java
                ).apply {
                    putExtra(com.myaiagent.automation.UploadRunnerService.EXTRA_ITEM_ID, updated.id)
                }
                androidx.core.content.ContextCompat.startForegroundService(
                    this@QueueItemActivity,
                    serviceIntent
                )
                Toast.makeText(
                    this@QueueItemActivity,
                    "Post started • YouTube Studio is opening",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        })

        root.addView(MaterialButton(this).apply {
            text = "Retry Upload"
            setOnClickListener {
                UploadAlarmScheduler.cancel(this@QueueItemActivity, item.id)
                store.resetForRetry(item.id)
                Toast.makeText(this@QueueItemActivity, "Queue item reset to QUEUED", Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        root.addView(MaterialButton(this).apply {
            text = "Remove from Queue"
            setOnClickListener {
                UploadAlarmScheduler.cancel(this@QueueItemActivity, item.id)
                store.remove(item.id)
                finish()
            }
        })

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun chooseDateTime() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                scheduledAt = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                refreshScheduleLabel()
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun refreshScheduleLabel() {
        scheduleText.text = scheduledAt?.let {
            "Scheduled: " + SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(it)
        } ?: "Schedule: Not set"
    }

    private fun saveItem() {
        val selectedVisibility = arrayOf("PRIVATE", "UNLISTED", "PUBLIC")
            .getOrElse(visibility.selectedItemPosition) { "PRIVATE" }
        val selectedMode = "NATIVE_STUDIO"
        val updated = item.copy(
            title = titleInput.text?.toString()?.trim().orEmpty(),
            description = descriptionInput.text?.toString().orEmpty(),
            visibility = selectedVisibility,
            scheduledAt = scheduledAt,
            status = if (scheduledAt != null) "SCHEDULED" else item.status,
            automationMode = selectedMode
        )
        store.update(updated)
        if (scheduledAt != null) {
            UploadAlarmScheduler.schedule(this, updated)
        } else {
            UploadAlarmScheduler.cancel(this, updated.id)
        }
        finish()
    }
}
