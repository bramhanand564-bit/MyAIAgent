package com.myaiapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "Hello Brahamanand! AI Agent Ready."
            textSize = 24f
            setPadding(50, 50, 50, 50)
        }
        
        setContentView(textView)
    }
}
