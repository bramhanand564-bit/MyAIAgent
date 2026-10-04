package com.myaiagent

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.myaiagent.queue.UploadQueueStore
import java.text.SimpleDateFormat
import java.util.Locale

class UploadHistoryActivity:AppCompatActivity(){
    private lateinit var list:LinearLayout
    private lateinit var store:UploadQueueStore
    override fun onCreate(s:Bundle?){super.onCreate(s);store=UploadQueueStore(this);setContentView(ui())}
    override fun onResume(){super.onResume();if(::list.isInitialized)render()}
    private fun ui():android.view.View{
        val sc=ScrollView(this).apply{setBackgroundColor(Color.rgb(8,9,13))}
        val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(30))}
        r.addView(t("History",29f,Color.WHITE,Typeface.BOLD))
        r.addView(t("Previous workflow results",12f,Color.rgb(145,150,164),Typeface.NORMAL).apply{setPadding(0,dp(5),0,dp(18))})
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};r.addView(list);sc.addView(r);return sc
    }
    private fun render(){
        list.removeAllViews()
        val xs=store.load().filter{it.lastRunAt!=null||it.status=="SUBMITTED"||it.status=="UPLOADED"||it.status=="ERROR"}.sortedByDescending{it.lastRunAt?:0L}
        if(xs.isEmpty()){list.addView(t("No upload history yet.",14f,Color.rgb(151,157,173),Typeface.NORMAL).apply{setPadding(0,dp(45),0,dp(45))});return}
        xs.forEach{it->
            val c=MaterialCardView(this).apply{radius=dp(17).toFloat();cardElevation=0f;setCardBackgroundColor(Color.rgb(24,25,32));strokeWidth=dp(1);strokeColor=Color.rgb(48,50,61)}
            val b=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(13))}
            b.addView(t(it.fileName,14f,Color.WHITE,Typeface.BOLD).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            val whenText=it.lastRunAt?.let{v->SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.getDefault()).format(v)}?:"—"
            b.addView(t(whenText+"  •  "+it.status,11f,statusColor(it.status),Typeface.BOLD).apply{setPadding(0,dp(4),0,dp(3))})
            b.addView(t(it.resultNote.ifBlank{"No result note"},10f,Color.rgb(151,157,173),Typeface.NORMAL).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
            c.addView(b);list.addView(c,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
        }
    }
    private fun statusColor(v:String)=when(v){"SUBMITTED","UPLOADED"->Color.rgb(125,220,164);"ERROR"->Color.rgb(255,112,112);else->Color.rgb(180,185,198)}
    private fun t(v:String,s:Float,c:Int,st:Int)=MaterialTextView(this).apply{text=v;textSize=s;setTextColor(c);typeface=Typeface.create(Typeface.DEFAULT,st)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
