package com.myaiapp

import android.os.Bundle
import android.widget.*
import android.view.ViewGroup
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. मेन बैकग्राउंड (हल्का ग्रे)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // 2. जहाँ हमारी बातचीत दिखेगी (Chat History)
        val chatHistory = TextView(this).apply {
            text = "System: Hello Brahamanand! AI Agent Ready.\n"
            textSize = 18f
            setTextColor(Color.BLACK)
        }

        // 3. स्क्रॉल करने की सुविधा
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1.0f // यह टेक्स्ट बॉक्स को नीचे धकेल कर पूरी खाली जगह ले लेगा
            )
            addView(chatHistory)
        }

        // 4. नीचे वाला हिस्सा (Input + Button)
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 5. टेक्स्ट लिखने का डब्बा
        val inputField = EditText(this).apply {
            hint = "Command type karein..."
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
            )
        }

        // 6. RUN बटन
        val runButton = Button(this).apply {
            text = "RUN"
            setBackgroundColor(Color.parseColor("#007BFF")) // नीला रंग
            setTextColor(Color.WHITE)
        }

        // 7. बटन दबाने पर क्या होगा? (लॉजिक)
        runButton.setOnClickListener {
            val userText = inputField.text.toString()
            if (userText.isNotEmpty()) {
                // आपका मैसेज स्क्रीन पर प्रिंट होगा
                chatHistory.append("\nBrahamanand: $userText\n")
                // अभी AI नहीं है, तो बस डमी रिप्लाई
                chatHistory.append("Agent: (Thinking...)\n")
                
                inputField.text.clear() // बॉक्स खाली करें
                
                // स्क्रीन को अपने आप नीचे स्क्रॉल करें
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        // 8. सारे हिस्सों को एक साथ जोड़ना
        inputLayout.addView(inputField)
        inputLayout.addView(runButton)
        
        mainLayout.addView(scrollView)
        mainLayout.addView(inputLayout)

        setContentView(mainLayout)
    }
}
