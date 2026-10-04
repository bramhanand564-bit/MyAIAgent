package com.myaiagent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.automation.AiChatClient
import com.myaiagent.automation.MindEngine
import com.myaiagent.automation.MindStore
import com.myaiagent.automation.VisionAgentSettings

class AiChatActivity : AppCompatActivity() {
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        addMessage("NAX", "I am connected to the current flow monitor. Ask where it is stuck, why it failed, or how to repair it safely.")
    }

    private fun buildUi(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8,9,13))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16),dp(10),dp(16),dp(8))
        }
        header.addView(
            text("NAX CHAT",27f,Color.WHITE,Typeface.BOLD),
            LinearLayout.LayoutParams(0,-2,1f)
        )
        header.addView(icon("⌂","Home"){
            startActivity(Intent(this@AiChatActivity,MainActivity::class.java))
        })
        header.addView(icon("✦","Mind"){
            startActivity(Intent(this@AiChatActivity,AiMindActivity::class.java))
        })
        root.addView(header)
        root.addView(text(
            "Diagnose • explain • fix • plan",
            11f, Color.rgb(145,150,164), Typeface.NORMAL
        ).apply { setPadding(dp(16),0,dp(16),dp(10)) })

        val scroll = ScrollView(this).apply { isFillViewport=true }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16),dp(6),dp(16),dp(14))
        }
        scroll.addView(messages)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        val composer = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(12))
        }
        input = EditText(this).apply {
            hint="Ask NAX about the current flow…"
            setHintTextColor(Color.rgb(105,111,126))
            setTextColor(Color.WHITE)
            textSize=13f
            setSingleLine(false)
            minLines=1
            maxLines=4
            setPadding(dp(12),dp(9),dp(12),dp(9))
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(24,25,32))
                cornerRadius=dp(14).toFloat()
            }
        }
        send = MaterialButton(this).apply {
            text="Send"
            isAllCaps=false
            cornerRadius=dp(14)
            minHeight=dp(48)
            insetTop=0
            insetBottom=0
            setOnClickListener{sendMessage()}
        }
        composer.addView(input,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(7)})
        composer.addView(send,LinearLayout.LayoutParams(dp(78),dp(48)))
        root.addView(composer)
        return root
    }

    private fun sendMessage() {
        val q=input.text.toString().trim()
        if(q.isBlank())return
        input.setText("")
        addMessage("YOU",q)
        send.isEnabled=false
        val thinkingIndex=messages.childCount
        addMessage("NAX","Thinking from the live flow…")
        val client=AiChatClient(VisionAgentSettings(this))
        val snap=MindStore(this).snapshot()
        val context=MindEngine.diagnosisForChat(this)+"\nRecent events:\n"+snap.events.takeLast(12).joinToString("\n")
        Thread{
            val answer=client.ask(q,context)
            runOnUiThread{
                if(messages.childCount>=thinkingIndex+2){
                    messages.removeViewAt(messages.childCount-1)
                    messages.removeViewAt(messages.childCount-1)
                }
                addMessage("NAX",answer)
                send.isEnabled=true
            }
        }.start()
    }

    private fun addMessage(who:String,message:String){
        val accent=if(who=="NAX")Color.rgb(194,164,255)else Color.rgb(113,196,255)
        messages.addView(text(who,10f,accent,Typeface.BOLD).apply{setPadding(0,dp(7),0,dp(2))})
        messages.addView(text(message,13f,Color.WHITE,Typeface.NORMAL).apply{setPadding(dp(2),0,0,dp(7))})
    }

    private fun icon(glyph:String,desc:String,onClick:()->Unit)=MaterialButton(this).apply{
        text=glyph;contentDescription=desc;isAllCaps=false;minWidth=dp(46);minHeight=dp(42);cornerRadius=dp(13);insetTop=0;insetBottom=0
        backgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(44,255,255,255));setOnClickListener{onClick()}
    }
    private fun text(v:String,s:Float,c:Int,st:Int)=MaterialTextView(this).apply{
        text=v;textSize=s;setTextColor(c);typeface=Typeface.create(Typeface.DEFAULT,st)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
