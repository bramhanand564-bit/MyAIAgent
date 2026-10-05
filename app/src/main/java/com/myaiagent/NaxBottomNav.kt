package com.myaiagent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton

/**
 * Shared bottom navigation for the main product surfaces.
 *
 * Home / Queue / Workflow / Settings stay consistent across setup screens.
 */
object NaxBottomNav {
    private const val HOME = "HOME"
    private const val QUEUE = "QUEUE"
    private const val WORKFLOW = "WORKFLOW"
    private const val SETTINGS = "SETTINGS"

    fun wrap(activity: Activity, content: View, active: String): View {
        if (content is ScrollView) {
            content.clipToPadding = false
            content.setPadding(
                content.paddingLeft,
                content.paddingTop,
                content.paddingRight,
                activity.dp(92)
            )
        }

        val frame = FrameLayout(activity).apply {
            setBackgroundColor(Color.rgb(8, 9, 13))
        }
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))

        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(10))
            setBackgroundColor(Color.rgb(17, 18, 24))
            elevation = activity.dp(8).toFloat()
        }

        fun tab(icon: String, title: String, key: String, wide: Boolean = false): MaterialButton =
            MaterialButton(activity).apply {
                text = "$icon\n$title"
                contentDescription = title
                isAllCaps = false
                textSize = if (wide) 12f else 11f
                gravity = Gravity.CENTER
                cornerRadius = activity.dp(if (wide) 20 else 16)
                minWidth = 0
                minHeight = activity.dp(if (wide) 58 else 54)
                insetTop = 0
                insetBottom = 0
                setPadding(activity.dp(2), 0, activity.dp(2), 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (active == key) Color.rgb(194, 164, 255) else Color.rgb(28, 29, 37)
                )
                setTextColor(
                    if (active == key) Color.rgb(25, 20, 35) else Color.rgb(198, 202, 214)
                )
                setOnClickListener {
                    when (key) {
                        HOME -> activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        QUEUE -> activity.startActivity(Intent(activity, QueueActivity::class.java))
                        WORKFLOW -> activity.startActivity(Intent(activity, WorkflowSetupActivity::class.java))
                        SETTINGS -> activity.startActivity(Intent(activity, AppSettingsActivity::class.java))
                    }
                }
            }

        nav.addView(tab("⌂", "Home", HOME),
            LinearLayout.LayoutParams(0, activity.dp(54), 1f).apply { marginEnd = activity.dp(4) })
        nav.addView(tab("▣", "Queue", QUEUE),
            LinearLayout.LayoutParams(0, activity.dp(54), 1f).apply { marginEnd = activity.dp(4) })
        nav.addView(tab("⚙", "Workflow", WORKFLOW, true),
            LinearLayout.LayoutParams(0, activity.dp(58), 1.08f).apply { marginEnd = activity.dp(4) })
        nav.addView(tab("◌", "Settings", SETTINGS),
            LinearLayout.LayoutParams(0, activity.dp(54), 1f))

        frame.addView(nav, FrameLayout.LayoutParams(-1, activity.dp(76)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = activity.dp(8)
            rightMargin = activity.dp(8)
            bottomMargin = activity.dp(8)
        })
        return frame
    }

    private fun Activity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
