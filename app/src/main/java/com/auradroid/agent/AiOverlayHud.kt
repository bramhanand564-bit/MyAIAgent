package com.auradroid.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class BotEmotion(val eyeColorHex: String, val label: String) {
    IDLE("#38BDF8", "Idle"),
    THINKING("#A855F7", "Thinking"),
    WORKING("#2DD4BF", "Working"),
    RENDERING("#F59E0B", "Rendering"),
    HAPPY("#22C55E", "Happy"),
    EXCITED("#10B981", "Excited"),
    CONFUSED("#FB923C", "Confused"),
    HIGH_BRAIN("#EC4899", "High-Brain Pro"),
    SEARCHING("#60A5FA", "Searching"),
    DOWNLOADING("#06B6D4", "Downloading"),
    UPLOADING("#8B5CF6", "Uploading"),
    SURPRISED("#FACC15", "Surprised"),
    ERROR("#EF4444", "Error"),
    SAD("#64748B", "Sad"),
    SLEEPY("#94A3B8", "Sleepy")
}

object AiOverlayHud {

    const val STAGE_1_COMPACT_POD = 1
    const val STAGE_2_MINI_BROWSER = 2
    const val STAGE_3_FULL_BROWSER = 3
    const val STAGE_4_STEALTH_1X1 = 4

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    private var rootPodLayout: LinearLayout? = null
    private var podLayoutParams: WindowManager.LayoutParams? = null
    private var faceView: NaxBotFaceView? = null
    private var sideNotchCard: LinearLayout? = null
    private var tvNotchStatus: TextView? = null
    private var floatingBrowserCard: FrameLayout? = null
    private var floatingWebHost: FrameLayout? = null

    private var cursorOverlayView: TapRippleOverlayView? = null
    private var cursorLayoutParams: WindowManager.LayoutParams? = null

    private var sharedWebView: WebView? = null
    private var homeWebContainer: FrameLayout? = null

    private var currentStage: Int = STAGE_1_COMPACT_POD
    private var currentEmotion: BotEmotion = BotEmotion.IDLE
    private var isHudAttached: Boolean = false
    private var isCursorAttached: Boolean = false
    private var isTemporarilyHiddenForInput: Boolean = false
    private var stageBeforeInputHide: Int = STAGE_1_COMPACT_POD

    private var onLiveTeachClicked: (() -> Unit)? = null
    private var onStopAllClicked: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (windowManager == null) {
            windowManager = appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
    }

    fun setCallbacks(
        onLiveTeachToggle: () -> Unit,
        onStopClicked: () -> Unit
    ) {
        onLiveTeachClicked = onLiveTeachToggle
        onStopAllClicked = onStopClicked
    }

    fun bindWebView(webView: WebView, homeContainer: FrameLayout) {
        sharedWebView = webView
        homeWebContainer = homeContainer
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun toggle(context: Context) {
        init(context)
        if (isHudAttached) {
            hide()
        } else {
            show(context)
        }
    }

    fun show(context: Context) {
        init(context)
        if (!canDrawOverlays(context)) return
        mainHandler.post {
            ensureHudCreated(context)
            ensureCursorOverlayCreated(context)
            applyStageLayout(STAGE_1_COMPACT_POD)
        }
    }

    fun hide() {
        mainHandler.post {
            returnWebViewToHomeContainer()
            try {
                if (isHudAttached && rootPodLayout != null) {
                    windowManager?.removeViewImmediate(rootPodLayout)
                }
            } catch (_: Exception) {
            }
            isHudAttached = false

            try {
                if (isCursorAttached && cursorOverlayView != null) {
                    windowManager?.removeViewImmediate(cursorOverlayView)
                }
            } catch (_: Exception) {
            }
            isCursorAttached = false
        }
    }

    fun setEmotion(emotion: BotEmotion) {
        currentEmotion = emotion
        mainHandler.post {
            faceView?.setBotEmotion(emotion)
        }
    }

    fun setEmotionByName(emotionName: String) {
        val clean = emotionName.trim().uppercase(Locale.US)
        val matched = BotEmotion.values().firstOrNull { it.name == clean } ?: BotEmotion.IDLE
        setEmotion(matched)
    }

    fun updateStatus(statusText: String, emotionName: String = "") {
        if (emotionName.isNotBlank()) {
            setEmotionByName(emotionName)
        }
        mainHandler.post {
            tvNotchStatus?.text = statusText.take(36)
        }
    }

    fun setCursorOverlayVisible(visible: Boolean) {
        mainHandler.post {
            val ctx = appContext ?: return@post
            if (!canDrawOverlays(ctx)) return@post
            if (visible) {
                ensureCursorOverlayCreated(ctx)
                cursorOverlayView?.visibility = View.VISIBLE
            } else {
                cursorOverlayView?.clearRipples()
                cursorOverlayView?.visibility = View.GONE
            }
        }
    }

    fun hidePodForFocusedTextInput() {
        mainHandler.post {
            if (!isHudAttached) return@post
            if (!isTemporarilyHiddenForInput) {
                stageBeforeInputHide = currentStage
                isTemporarilyHiddenForInput = true
                rootPodLayout?.alpha = 0f
                rootPodLayout?.visibility = View.GONE
            }
        }
    }

    fun restorePodAfterTextInput() {
        mainHandler.post {
            if (!isHudAttached) return@post
            if (isTemporarilyHiddenForInput) {
                isTemporarilyHiddenForInput = false
                rootPodLayout?.visibility = View.VISIBLE
                rootPodLayout?.alpha = 1f
                applyStageLayout(stageBeforeInputHide)
            }
        }
    }

    fun moveBotAndShowTap(x: Float, y: Float, label: String = "") {
        mainHandler.post {
            val ctx = appContext ?: return@post
            if (canDrawOverlays(ctx)) {
                ensureCursorOverlayCreated(ctx)
                cursorOverlayView?.visibility = View.VISIBLE
                cursorOverlayView?.triggerTapRipple(x, y, label, currentEmotion.eyeColorHex)
            }
            if (isHudAttached && currentStage == STAGE_1_COMPACT_POD && podLayoutParams != null && rootPodLayout != null) {
                val targetX = max(12, (x.toInt() - dpToPx(ctx, 28f)))
                val targetY = max(40, (y.toInt() - dpToPx(ctx, 76f)))
                podLayoutParams?.x = targetX
                podLayoutParams?.y = targetY
                try {
                    windowManager?.updateViewLayout(rootPodLayout, podLayoutParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun enableInvisible1x1StealthMode() {
        mainHandler.post {
            val ctx = appContext ?: return@post
            if (!canDrawOverlays(ctx)) return@post
            ensureHudCreated(ctx)
            applyStageLayout(STAGE_4_STEALTH_1X1)
        }
    }

    fun openForFirstRunLiveCalibration() {
        mainHandler.post {
            val ctx = appContext ?: return@post
            if (!canDrawOverlays(ctx)) return@post
            ensureHudCreated(ctx)
            applyStageLayout(STAGE_2_MINI_BROWSER)
        }
    }

    fun collapseToCompactStage() {
        mainHandler.post {
            if (!isHudAttached) return@post
            applyStageLayout(STAGE_1_COMPACT_POD)
        }
    }

    fun cycleBrowserStages() {
        val nextStage = when (currentStage) {
            STAGE_1_COMPACT_POD -> STAGE_2_MINI_BROWSER
            STAGE_2_MINI_BROWSER -> STAGE_3_FULL_BROWSER
            STAGE_3_FULL_BROWSER -> STAGE_1_COMPACT_POD
            else -> STAGE_2_MINI_BROWSER
        }
        applyStageLayout(nextStage)
    }

    fun returnWebViewToHomeContainer() {
        mainHandler.post {
            val webView = sharedWebView ?: return@post
            val home = homeWebContainer ?: return@post
            val currentParent = webView.parent as? ViewGroup
            if (currentParent != null && currentParent !== home) {
                currentParent.removeView(webView)
            }
            if (webView.parent == null) {
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                webView.layoutParams = lp
                home.addView(webView)
            } else {
                webView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                webView.requestLayout()
            }
        }
    }

    private fun moveWebViewIntoFloatingHost() {
        val webView = sharedWebView ?: return
        val host = floatingWebHost ?: return
        val currentParent = webView.parent as? ViewGroup
        if (currentParent != null && currentParent !== host) {
            currentParent.removeView(webView)
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.layoutParams = lp
        if (webView.parent == null) {
            host.addView(webView)
        }
    }

    private fun applyStageLayout(stage: Int) {
        val ctx = appContext ?: return
        val root = rootPodLayout ?: return
        val params = podLayoutParams ?: return
        currentStage = stage

        when (stage) {
            STAGE_1_COMPACT_POD -> {
                returnWebViewToHomeContainer()
                floatingBrowserCard?.visibility = View.GONE
                faceView?.visibility = View.VISIBLE
                sideNotchCard?.visibility = View.VISIBLE
                root.alpha = 1f
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            STAGE_2_MINI_BROWSER -> {
                moveWebViewIntoFloatingHost()
                faceView?.visibility = View.VISIBLE
                sideNotchCard?.visibility = View.VISIBLE
                floatingBrowserCard?.visibility = View.VISIBLE
                floatingBrowserCard?.layoutParams = LinearLayout.LayoutParams(
                    dpToPx(ctx, 300f),
                    dpToPx(ctx, 470f)
                ).apply {
                    topMargin = dpToPx(ctx, 6f)
                }
                sharedWebView?.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                root.alpha = 1f
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            STAGE_3_FULL_BROWSER -> {
                moveWebViewIntoFloatingHost()
                faceView?.visibility = View.VISIBLE
                sideNotchCard?.visibility = View.VISIBLE
                floatingBrowserCard?.visibility = View.VISIBLE
                floatingBrowserCard?.layoutParams = LinearLayout.LayoutParams(
                     LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = dpToPx(ctx, 6f)
                }
                sharedWebView?.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                root.alpha = 1f
                params.x = 0
                params.y = 0
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            STAGE_4_STEALTH_1X1 -> {
                moveWebViewIntoFloatingHost()
                faceView?.visibility = View.GONE
                sideNotchCard?.visibility = View.GONE
                floatingBrowserCard?.visibility = View.VISIBLE
                floatingBrowserCard?.layoutParams = LinearLayout.LayoutParams(1, 1)
                sharedWebView?.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                root.alpha = 0.02f
                params.x = 0
                params.y = 0
                params.width = 1
                params.height = 1
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
        }

        if (isHudAttached) {
            try {
                windowManager?.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureHudCreated(context: Context) {
        if (isHudAttached && rootPodLayout != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val botFace = NaxBotFaceView(context).apply {
            val sizePx = dpToPx(context, 46f)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setBotEmotion(currentEmotion)
        }
        faceView = botFace

        val notch = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val maxNotchWidth = dpToPx(context, 112f)
            layoutParams = LinearLayout.LayoutParams(maxNotchWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpToPx(context, 6f)
            }
            setPadding(dpToPx(context, 8f), dpToPx(context, 6f), dpToPx(context, 8f), dpToPx(context, 6f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6131B2E"))
                cornerRadius = dpToPx(context, 16f).toFloat()
                setStroke(dpToPx(context, 1f), Color.parseColor("#38BDF8"))
            }
        }

        val statusTv = TextView(context).apply {
            text = "Nax • Ready"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
        }
        tvNotchStatus = statusTv
        notch.addView(statusTv)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 4f)
            }
        }

        fun makeQuickButton(symbol: String, bgHex: String, onClick: () -> Unit): Button {
            return Button(context).apply {
                text = symbol
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    cornerRadius = dpToPx(context, 8f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, 24f), 1f).apply {
                    marginEnd = dpToPx(context, 2f)
                }
                setOnClickListener { onClick() }
            }
        }

        val btnStage = makeQuickButton("🌐", "#0284C7") {
            cycleBrowserStages()
        }
        val btnTeach = makeQuickButton("🎓", "#0D9488") {
            onLiveTeachClicked?.invoke()
        }
        val btnStop = makeQuickButton("■", "#DC2626") {
            onStopAllClicked?.invoke()
        }

        btnRow.addView(btnStage)
        btnRow.addView(btnTeach)
        btnRow.addView(btnStop)
        notch.addView(btnRow)
        sideNotchCard = notch

        topRow.addView(botFace)
        topRow.addView(notch)
        container.addView(topRow)

        val browserFrame = FrameLayout(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1325"))
                cornerRadius = dpToPx(context, 18f).toFloat()
                setStroke(dpToPx(context, 1.5f), Color.parseColor("#2DD4BF"))
            }
            setPadding(dpToPx(context, 3f), dpToPx(context, 3f), dpToPx(context, 3f), dpToPx(context, 3f))
        }

        val innerHost = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        floatingWebHost = innerHost
        browserFrame.addView(innerHost)
        floatingBrowserCard = browserFrame
        container.addView(browserFrame)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(context, 12f)
            y = dpToPx(context, 140f)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var movedSignificantly = false

        botFace.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    movedSignificantly = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || (kotlin.math.abs(dy) > 8)) {
                        movedSignificantly = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager?.updateViewLayout(container, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!movedSignificantly) {
                        notch.visibility = if (notch.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        rootPodLayout = container
        podLayoutParams = params

        try {
            windowManager?.addView(container, params)
            isHudAttached = true
        } catch (_: Exception) {
            isHudAttached = false
        }
    }

    private fun ensureCursorOverlayCreated(context: Context) {
        if (isCursorAttached && cursorOverlayView != null) return

        val rippleView = TapRippleOverlayView(context)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        cursorOverlayView = rippleView
        cursorLayoutParams = params

        try {
            windowManager?.addView(rippleView, params)
            isCursorAttached = true
        } catch (_: Exception) {
            isCursorAttached = false
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}

class NaxBotFaceView(context: Context) : View(context) {

    private var emotion: BotEmotion = BotEmotion.IDLE
    private var breathScale: Float = 1.0f
    private var blinkFactor: Float = 1.0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val eyeOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
    }

    private val eyeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }

    private val eyeRectLeft = RectF()
    private val eyeRectRight = RectF()

    private val breathAnimator = ValueAnimator.ofFloat(0.95f, 1.04f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            breathScale = it.animatedValue as Float
            invalidate()
        }
    }

    private val blinkAnimator = ValueAnimator.ofFloat(1f, 1f, 1f, 0.12f, 1f).apply {
        duration = 3200L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            blinkFactor = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        breathAnimator.start()
        blinkAnimator.start()
    }

    override fun onDetachedFromWindow() {
        breathAnimator.cancel()
        blinkAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setBotEmotion(newEmotion: BotEmotion) {
        emotion = newEmotion
        val parsedColor = try {
            Color.parseColor(newEmotion.eyeColorHex)
        } catch (_: Exception) {
            Color.parseColor("#38BDF8")
        }
        borderPaint.color = parsedColor
        eyeCorePaint.color = parsedColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (min(width, height) / 2f) - 4f

        canvas.save()
        canvas.scale(breathScale, breathScale, cx, cy)

        canvas.drawCircle(cx, cy, baseRadius, bodyPaint)
        canvas.drawCircle(cx, cy, baseRadius, borderPaint)

        val eyeOffsetX = baseRadius * 0.38f
        val leftEyeX = cx - eyeOffsetX
        val rightEyeX = cx + eyeOffsetX
        val eyeY = cy

        canvas.drawLine(leftEyeX, eyeY, rightEyeX, eyeY, bridgePaint)

        val eyeRadiusX = baseRadius * 0.24f
        val emotionHeightMultiplier = when (emotion) {
            BotEmotion.SLEEPY -> 0.45f
            BotEmotion.HAPPY, BotEmotion.EXCITED -> 0.82f
            BotEmotion.SURPRISED, BotEmotion.HIGH_BRAIN -> 1.18f
            else -> 1.0f
        }
        val eyeRadiusY = max(2f, eyeRadiusX * blinkFactor * emotionHeightMultiplier)

        eyeRectLeft.set(
            leftEyeX - eyeRadiusX,
            eyeY - eyeRadiusY,
            leftEyeX + eyeRadiusX,
            eyeY + eyeRadiusY
        )
        eyeRectRight.set(
            rightEyeX - eyeRadiusX,
            eyeY - eyeRadiusY,
            rightEyeX + eyeRadiusX,
            eyeY + eyeRadiusY
        )

        canvas.drawOval(eyeRectLeft, eyeOuterPaint)
        canvas.drawOval(eyeRectRight, eyeOuterPaint)

        val innerRx = eyeRadiusX * 0.68f
        val innerRy = max(1.5f, eyeRadiusY * 0.68f)

        eyeRectLeft.set(
            leftEyeX - innerRx,
            eyeY - innerRy,
            leftEyeX + innerRx,
            eyeY + innerRy
        )
        eyeRectRight.set(
            rightEyeX - innerRx,
            eyeY - innerRy,
            rightEyeX + innerRx,
            eyeY + innerRy
        )

        canvas.drawOval(eyeRectLeft, eyeCorePaint)
        canvas.drawOval(eyeRectRight, eyeCorePaint)

        canvas.restore()
    }
}

class TapRippleOverlayView(context: Context) : View(context) {

    private val tapPoint = PointF(-100f, -100f)
    private var rippleProgress: Float = 0f
    private var tapLabel: String = ""
    private var activeAnimator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2DD4BF")
        style = Paint.Style.FILL
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D90F172A")
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }

    fun triggerTapRipple(x: Float, y: Float, label: String, colorHex: String) {
        tapPoint.set(x, y)
        tapLabel = label.take(28)
        try {
            ringPaint.color = Color.parseColor(colorHex)
        } catch (_: Exception) {
            ringPaint.color = Color.parseColor("#38BDF8")
        }

        activeAnimator?.cancel()
        activeAnimator = ValueAnimator.ofFloat(0.1f, 1.0f).apply {
            duration = 650L
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun clearRipples() {
        activeAnimator?.cancel()
        rippleProgress = 0f
        tapPoint.set(-100f, -100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rippleProgress <= 0.01f || (tapPoint.x < 0f)) return

        val alpha = ((1.0f - rippleProgress) * 255).toInt().coerceIn(0, 255)
        ringPaint.alpha = alpha
        centerDotPaint.alpha = alpha

        val radius = 18f + (rippleProgress * 64f)
        canvas.drawCircle(tapPoint.x, tapPoint.y, radius, ringPaint)
        canvas.drawCircle(tapPoint.x, tapPoint.y, 10f, centerDotPaint)

        if (tapLabel.isNotBlank()) {
            val textWidth = labelTextPaint.measureText(tapLabel)
            val left = (tapPoint.x - textWidth / 2f - 14f).coerceAtLeast(10f)
            val top = (tapPoint.y - radius - 48f).coerceAtLeast(20f)
            val rect = RectF(left, top, left + textWidth + 28f, top + 40f)
            canvas.drawRoundRect(rect, 12f, 12f, labelBgPaint)
            canvas.drawText(tapLabel, left + 14f, top + 29f, labelTextPaint)
        }
    }
}