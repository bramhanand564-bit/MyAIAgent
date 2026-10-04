package com.myaiagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = MaterialTextView(this).apply {
            text = "MyAIAgent"
            textSize = 28f
        }

        val status = MaterialTextView(this).apply {
            text = "Automation workspace ready\n\nModes:\n• Embedded YouTube workspace\n• External YouTube app fallback"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }

        val accessibility = MaterialButton(this).apply {
            text = "Enable Automation Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(accessibility)
        setContentView(root)
    }
}
