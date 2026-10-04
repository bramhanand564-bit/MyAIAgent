package com.myaiagent

import android.app.AlarmManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView

class AppSettingsActivity:AppCompatActivity(){
    private lateinit var access:MaterialTextView
    private lateinit var alarm:MaterialTextView
    override fun onCreate(s:Bundle?){super.onCreate(s);setContentView(NaxBottomNav.wrap(this,ui(),"SETTINGS"));refresh()}
    override fun onResume(){super.onResume();if(::access.isInitialized)refresh()}
    private fun ui():android.view.View{
        val sc=ScrollView(this).apply{setBackgroundColor(Color.rgb(8,9,13))}
        val r=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(30))}
        r.addView(t("Settings",29f,Color.WHITE,Typeface.BOLD))
        r.addView(t("System access and automation controls",12f,Color.rgb(145,150,164),Typeface.NORMAL).apply{setPadding(0,dp(5),0,dp(18))})
        access=t("Checking…",11f,Color.rgb(151,157,173),Typeface.NORMAL)
        r.addView(card("Accessibility automation","Allows MyAIAgent to control the visible Studio UI",access,"Open settings"){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))})
        alarm=t("Checking…",11f,Color.rgb(151,157,173),Typeface.NORMAL)
        r.addView(card("Exact scheduling","For precise daily upload times where Android permits it",alarm,"Configure"){
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply{data=Uri.parse("package:"+packageName)})
        })
        r.addView(card("Workflow","Folder, frequency, daily times and privacy",t("Configure workflow",11f,Color.rgb(169,175,191),Typeface.NORMAL),"Open"){startActivity(Intent(this,WorkflowSetupActivity::class.java))})
        r.addView(card("AI vision","Provider, endpoint, API key and model",t("Configure AI",11f,Color.rgb(169,175,191),Typeface.NORMAL),"Open"){startActivity(Intent(this,AiApiSettingsActivity::class.java))})
        r.addView(card("YouTube Studio","Official native Studio app",t("Native Studio",11f,Color.rgb(169,175,191),Typeface.NORMAL),"Open"){startActivity(Intent(this,YouTubeWorkspaceActivity::class.java))})
        r.addView(t("Google login, CAPTCHA and security verification remain user-controlled. MyAIAgent does not bypass them.",10f,Color.rgb(132,137,151),Typeface.NORMAL).apply{setPadding(0,dp(18),0,0)})
        sc.addView(r);return sc
    }
    private fun refresh(){
        val e=Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val expected=packageName+"/com.myaiagent.automation.NaxAccessibilityService"
        val on=TextUtils.SimpleStringSplitter(':').let{s->s.setString(e);s.any{it.equals(expected,true)}}
        access.text=if(on)"● Enabled" else "○ Not enabled"
        access.setTextColor(if(on)Color.rgb(125,220,164)else Color.rgb(255,181,105))
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
            val ok=getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            alarm.text=if(ok)"● Enabled" else "○ Not enabled"
            alarm.setTextColor(if(ok)Color.rgb(125,220,164)else Color.rgb(255,181,105))
        }else{alarm.text="● System scheduler";alarm.setTextColor(Color.rgb(125,220,164))}
    }
    private fun card(title:String,sub:String,status:MaterialTextView,action:String,onClick:()->Unit)=MaterialCardView(this).apply{
        radius=dp(18).toFloat();cardElevation=0f;setCardBackgroundColor(Color.rgb(24,25,32));strokeWidth=dp(1);strokeColor=Color.rgb(48,50,61)
        val b=LinearLayout(this@AppSettingsActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(14),dp(15),dp(13))}
        b.addView(t(title,14f,Color.WHITE,Typeface.BOLD))
        b.addView(t(sub,10f,Color.rgb(132,137,151),Typeface.NORMAL).apply{setPadding(0,dp(4),0,dp(7))})
        b.addView(status)
        b.addView(MaterialButton(this@AppSettingsActivity).apply{text=action;isAllCaps=false;cornerRadius=dp(13);minHeight=dp(42);insetTop=0;insetBottom=0;setOnClickListener{onClick()}})
        addView(b);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)}
    }
    private fun t(v:String,s:Float,c:Int,st:Int)=MaterialTextView(this).apply{text=v;textSize=s;setTextColor(c);typeface=Typeface.create(Typeface.DEFAULT,st)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
