package com.nax.myaiagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        const val ACTION_START = "com.nax.myaiagent.START"
        const val ACTION_STOP = "com.nax.myaiagent.STOP"
        const val ACTION_STATUS = "com.nax.myaiagent.STATUS"
        const val ACTION_TAP_COUNT = "com.nax.myaiagent.TAP_COUNT"
        const val PREFS = "autotapper"
        const val INTERVAL_MIN_MS = 50
    }

    private lateinit var status: TextView
    private lateinit var taps: TextView
    private lateinit var elapsed: TextView
    private lateinit var point: TextView
    private lateinit var target: TargetView
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var interval: SeekBar
    private lateinit var delay: SeekBar
    private lateinit var duration: SeekBar
    private lateinit var maxTaps: SeekBar
    private lateinit var jitter: SeekBar
    private lateinit var pressDuration: SeekBar
    private lateinit var intervalJitter: SeekBar

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TAP_COUNT -> {
                    val count = intent.getIntExtra("count", 0)
                    taps.text = "${count}\nTAPS"
                }
                AutoTapAccessibilityService.ACTION_POINT_CHANGED -> {
                    xInput.setText(intent.getIntExtra("x", 0).toString())
                    yInput.setText(intent.getIntExtra("y", 0).toString())
                    renderPoint()
                }
                ACTION_STATUS -> {
                    val value = intent.getStringExtra("value") ?: "READY"
                    val elapsedMs = intent.getLongExtra("elapsedMs", -1L)
                    status.text = value
                    status.setTextColor(
                        if (value == "RUNNING") Color.rgb(0, 122, 255) else Color.DKGRAY
                    )
                    if (elapsedMs >= 0L) {
                        elapsed.text = "${elapsedMs / 1000L}s\nELAPSED"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        registerReceiverCompat(receiver, IntentFilter().apply {
            addAction(ACTION_TAP_COUNT)
            addAction(ACTION_STATUS)
            addAction(AutoTapAccessibilityService.ACTION_POINT_CHANGED)
        })
        renderPoint()
        status.text = if (isAccessibilityEnabled()) {
            "READY • ACCESSIBILITY ON"
        } else {
            "READY • ENABLE ACCESSIBILITY"
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun buildUi() {
        val bg = Color.rgb(245, 245, 247)
        val blue = Color.rgb(0, 122, 255)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 24, 18, 28)
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Auto Tapper"
            textSize = 30f
            setTextColor(Color.BLACK)
            setTypeface(typeface, 1)
        }

        val subtitle = TextView(this).apply {
            text = "NAX • Simple & precise"
            textSize = 14f
            setTextColor(Color.GRAY)
        }

        titles.addView(title)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        status = TextView(this).apply {
            text = "READY"
            textSize = 12f
            setTypeface(typeface, 1)
            setPadding(14, 8, 14, 8)
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.rgb(232, 232, 237))
        }
        header.addView(status)
        content.addView(header, margin(0, 0, 0, 16))

        target = TargetView(this) { px, py ->
            val dm = resources.displayMetrics
            val x = (px * dm.widthPixels).roundToInt().coerceIn(0, dm.widthPixels - 1)
            val y = (py * dm.heightPixels).roundToInt().coerceIn(0, dm.heightPixels - 1)
            xInput.setText(x.toString())
            yInput.setText(y.toString())
            renderPoint()
        }
        content.addView(card(target, 230))

        val coords = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        xInput = numberField("X", "0")
        yInput = numberField("Y", "0")
        coords.addView(
            xInput,
            LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(0, 0, 5, 0) }
        )
        coords.addView(
            yInput,
            LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(5, 0, 0, 0) }
        )
        content.addView(card(coords, -2))

        interval = slider(content, "Interval", INTERVAL_MIN_MS, 5000, 500) { "${it} ms" }
        delay = slider(content, "Start delay", 0, 30, 1) { "${it} s" }
        duration = slider(content, "Duration", 0, 300, 0) {
            if (it == 0) "0 = Unlimited" else "${it} s"
        }
        maxTaps = slider(content, "Tap count", 0, 10000, 0) {
            if (it == 0) "0 = Unlimited" else it.toString()
        }
        jitter = slider(content, "Random jitter", 0, 30, 0) {
            if (it == 0) "Off" else "±${it} px"
        }
        pressDuration = slider(content, "Press duration", 1, 2000, 1) {
            if (it <= 1) "Tap" else "${it} ms"
        }
        intervalJitter = slider(content, "Random interval", 0, 1000, 0) {
            if (it == 0) "Off" else "±${it} ms"
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = action("START", blue)
        val stop = action("STOP", Color.rgb(230, 230, 235))
        stop.setTextColor(Color.BLACK)
        val reset = action("RESET", Color.rgb(242, 242, 244))
        reset.setTextColor(Color.DKGRAY)

        buttons.addView(
            start,
            LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(0, 0, 5, 0) }
        )
        buttons.addView(
            stop,
            LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(5, 0, 5, 0) }
        )
        buttons.addView(
            reset,
            LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(5, 0, 0, 0) }
        )
        content.addView(card(buttons, -2))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        taps = stat("0", "TAPS")
        elapsed = stat("0s", "ELAPSED")
        point = stat("0,0", "POINT")

        listOf(taps, elapsed, point).forEachIndexed { i, v ->
            stats.addView(
                v,
                LinearLayout.LayoutParams(0, 84, 1f).also {
                    it.setMargins(if (i == 0) 0 else 5, 0, if (i == 2) 0 else 5, 0)
                }
            )
        }
        content.addView(card(stats, -2))

        val markerBtn = action("SHOW FLOATING TARGET", Color.WHITE).apply {
            setTextColor(blue)
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    sendBroadcast(
                        Intent(AutoTapAccessibilityService.ACTION_SHOW_MARKER)
                            .setPackage(packageName)
                    )
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
        content.addView(card(markerBtn, -2))

        val enable = action("OPEN ACCESSIBILITY SETTINGS", Color.WHITE).apply {
            setTextColor(blue)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        content.addView(card(enable, -2))

        val note = TextView(this).apply {
            text = "Real tapping uses the Android AccessibilityService after you enable it. Security screens, CAPTCHA and login verification are never bypassed."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 0)
        }
        content.addView(note)

        start.setOnClickListener { sendConfig(ACTION_START) }
        stop.setOnClickListener {
            sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
        }
        reset.setOnClickListener {
            setDefaults()
            sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
            sendBroadcast(
                Intent(AutoTapAccessibilityService.ACTION_HIDE_MARKER)
                    .setPackage(packageName)
            )
        }

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        setDefaults()
    }

    private fun sendConfig(action: String) {
        val dm = resources.displayMetrics
        val x = xInput.text.toString().toIntOrNull()
            ?.coerceIn(0, dm.widthPixels - 1) ?: 0
        val y = yInput.text.toString().toIntOrNull()
            ?.coerceIn(0, dm.heightPixels - 1) ?: 0
        val intervalMs = (interval.progress + INTERVAL_MIN_MS).coerceIn(
            INTERVAL_MIN_MS,
            5000
        )

        getSharedPreferences(PREFS, 0).edit()
            .putInt("x", x)
            .putInt("y", y)
            .putLong("interval", intervalMs.toLong())
            .putInt("delay", delay.progress)
            .putInt("duration", duration.progress)
            .putInt("maxTaps", maxTaps.progress)
            .putInt("jitter", jitter.progress)
            .putInt("pressDuration", pressDuration.progress + 1)
            .putInt("intervalJitter", intervalJitter.progress)
            .apply()

        sendBroadcast(Intent(action).setPackage(packageName))
        status.text = if (isAccessibilityEnabled()) {
            "STARTING…"
        } else {
            "ENABLE ACCESSIBILITY FIRST"
        }
    }

    private fun setDefaults() {
        val dm = resources.displayMetrics
        xInput.setText((dm.widthPixels / 2).toString())
        yInput.setText((dm.heightPixels / 2).toString())
        interval.progress = 500 - INTERVAL_MIN_MS
        delay.progress = 1
        duration.progress = 0
        maxTaps.progress = 0
        jitter.progress = 0
        pressDuration.progress = 0
        intervalJitter.progress = 0
        taps.text = "0\nTAPS"
        elapsed.text = "0s\nELAPSED"
        renderPoint()
    }

    private fun renderPoint() {
        val dm = resources.displayMetrics
        val x = xInput.text.toString().toIntOrNull() ?: 0
        val y = yInput.text.toString().toIntOrNull() ?: 0
        if (::target.isInitialized && dm.widthPixels > 0 && dm.heightPixels > 0) {
            target.setPoint(
                x.toFloat() / dm.widthPixels,
                y.toFloat() / dm.heightPixels
            )
        }
        if (::point.isInitialized) point.text = "${x},${y}\nPOINT"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun numberField(label: String, value: String) = EditText(this).apply {
        hint = label
        setText(value)
        inputType = 2
        textSize = 16f
        setPadding(14, 10, 14, 10)
    }

    private fun slider(
        container: LinearLayout,
        name: String,
        min: Int,
        max: Int,
        value: Int,
        fmt: (Int) -> String
    ): SeekBar {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(3, 8, 3, 8)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val l = TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(Color.GRAY)
        }
        val v = TextView(this).apply {
            text = fmt(value)
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.END
        }
        row.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))

        val s = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
        }
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                sb: SeekBar,
                p: Int,
                fromUser: Boolean
            ) {
                v.text = fmt(p + min)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        box.addView(row)
        box.addView(s)
        container.addView(card(box, -2), 0)
        return s
    }

    private fun action(text: String, color: Int) = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(
            if (color == Color.rgb(0, 122, 255)) Color.WHITE else Color.BLACK
        )
        setBackgroundColor(color)
        setAllCaps(false)
    }

    private fun stat(value: String, label: String) = TextView(this).apply {
        text = "$value\n$label"
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        setPadding(8, 8, 8, 8)
        setBackgroundColor(Color.rgb(242, 242, 245))
    }

    private fun card(view: View, height: Int): View {
        val frame = FrameLayout(this).apply {
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.WHITE)
        }
        frame.addView(
            view,
            FrameLayout.LayoutParams(-1, if (height < 0) -2 else height)
        )
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 6, 0, 6)
        frame.layoutParams = lp
        return frame
    }

    private fun margin(
        l: Int,
        t: Int,
        r: Int,
        b: Int
    ) = LinearLayout.LayoutParams(-1, -2).also {
        it.setMargins(l, t, r, b)
    }
}

class TargetView(
    context: Context,
    private val listener: (Float, Float) -> Unit
) : View(context) {
    private val p = android.graphics.Paint(1)
    private var px = 0.5f
    private var py = 0.5f

    init {
        setBackgroundColor(Color.rgb(22, 23, 26))
        setOnTouchListener { _, e ->
            if (
                e.action == android.view.MotionEvent.ACTION_DOWN ||
                e.action == android.view.MotionEvent.ACTION_MOVE
            ) {
                px = (e.x / width).coerceIn(0f, 1f)
                py = (e.y / height).coerceIn(0f, 1f)
                invalidate()
                listener(px, py)
            }
            true
        }
    }

    fun setPoint(x: Float, y: Float) {
        px = x.coerceIn(0f, 1f)
        py = y.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = Color.WHITE
        c.drawCircle(px * width, py * height, 14f, p)
        p.style = android.graphics.Paint.Style.FILL
        p.color = Color.rgb(0, 122, 255)
        c.drawCircle(px * width, py * height, 7f, p)
    }
}
