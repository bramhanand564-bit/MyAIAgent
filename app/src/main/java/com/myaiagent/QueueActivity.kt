package com.myaiagent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.queue.UploadQueueStore
import java.text.SimpleDateFormat
import java.util.Locale

class QueueActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var store: UploadQueueStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = UploadQueueStore(this)
        setContentView(NaxBottomNav.wrap(this, buildUi(), "QUEUE"))
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }

    private fun buildUi(): android.view.View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8,9,13)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(14),dp(18),dp(30))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("Queue",29f,Color.WHITE,Typeface.BOLD), LinearLayout.LayoutParams(0,-2,1f))
        header.addView(MaterialButton(this).apply {
            text = "Home"
            isAllCaps = false
            cornerRadius = dp(14)
            minHeight = dp(44)
            setOnClickListener { finish() }
        })
        root.addView(header)
        root.addView(text("All videos • schedule, run and inspect",12f,Color.rgb(145,150,164),Typeface.NORMAL).apply {
            setPadding(0,dp(5),0,dp(16))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        scroll.addView(root)
        return scroll
    }

    private fun render() {
        list.removeAllViews()
        val items = store.load()
        if (items.isEmpty()) {
            list.addView(text("Queue is empty. Add videos from Home.",14f,Color.rgb(151,157,173),Typeface.NORMAL).apply {
                setPadding(dp(10),dp(45),0,dp(45))
            })
            return
        }
        items.forEachIndexed { index, item ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.rgb(24,25,32))
                strokeWidth = dp(1)
                strokeColor = Color.rgb(48,50,61)
                setOnClickListener {
                    startActivity(Intent(this@QueueActivity,QueueItemActivity::class.java).apply {
                        putExtra("item_id",item.id)
                    })
                }
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15),dp(13),dp(15),dp(13))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(text(String.format(Locale.getDefault(),"%02d",index+1),11f,Color.rgb(204,180,255),Typeface.BOLD))
            top.addView(text(item.fileName,14f,Color.WHITE,Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10),0,dp(5),0)
            },LinearLayout.LayoutParams(0,-2,1f))
            top.addView(text(item.status,9f,statusColor(item.status),Typeface.BOLD))
            body.addView(top)
            body.addView(text(
                item.scheduledAt?.let { "Scheduled • "+SimpleDateFormat("dd MMM, hh:mm a",Locale.getDefault()).format(it) } ?: "Ready to run now",
                11f,Color.rgb(140,146,160),Typeface.NORMAL
            ).apply { setPadding(dp(39),dp(8),0,0) })
            if (item.resultNote.isNotBlank()) body.addView(text(item.resultNote,10f,Color.rgb(156,162,177),Typeface.NORMAL).apply {
                maxLines=2
                ellipsize=TextUtils.TruncateAt.END
                setPadding(dp(39),dp(4),0,0)
            })
            card.addView(body)
            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
        }
    }

    private fun statusColor(v:String)=when(v) {
        "RUNNING"->Color.rgb(115,190,255)
        "SCHEDULED"->Color.rgb(204,180,255)
        "SUBMITTED","UPLOADED"->Color.rgb(125,220,164)
        "ERROR"->Color.rgb(255,112,112)
        else->Color.rgb(180,185,198)
    }

    private fun text(v:String,s:Float,c:Int,t:Int)=MaterialTextView(this).apply {
        text=v;textSize=s;setTextColor(c);typeface=Typeface.create(Typeface.DEFAULT,t)
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
