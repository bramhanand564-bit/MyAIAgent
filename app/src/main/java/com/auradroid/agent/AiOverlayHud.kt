package com.auradroid.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AiOverlayHud(private val context: Context) {

    companion object {
        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    WebView.enableSlowWholeDocumentDraw()
                } catch (_: Exception) {}
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pointerCanvasView: CuteBaymaxHandCursorCanvas? = null
    private var podRootLayout: LinearLayout? = null
    private var podLayoutParams: WindowManager.LayoutParams? = null

    private var compactRow: LinearLayout? = null
    private var sideInfoContainer: LinearLayout? = null
    private var tvPodTitle: TextView? = null
    private var tvPodSubtitle: TextView? = null
    private var btnQuickMiniBrowser: TextView? = null
    private var stopBadge: TextView? = null
    private var botAvatarView: ExpressiveCompanionAvatar? = null

    private var expandedContainer: LinearLayout? = null
    private var tvExpandedStepInfo: TextView? = null
    private var btnExpandFullToggle: TextView? = null
    private var podWebFrame: FrameLayout? = null

    private var sharedWebView: WebView? = null
    private var isDesktopMode = false
    private var isAdded = false
    private var isPodVisible = true
    private var isSideBarExpanded = false
    private var currentStage = 1

    private val autoCollapseSideBarRunnable = Runnable {
        if (!AutoTouchService.isJobRunning && currentStage == 1) {
            setSideBarExpanded(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateSharedWebView(): WebView {
        if (sharedWebView == null) {
            val wv = object : WebView(context.applicationContext) {
                override fun onCheckIsTextEditor(): Boolean = true
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun saveBase64Video(base64Data: String, fileName: String) {
                        try {
                            val cleanB64 = base64Data.substringAfter(",")
                            val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                            val dir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "AutoVideos"
                            )
                            if (!dir.exists()) dir.mkdirs()
                            val outFile = File(dir, fileName)
                            FileOutputStream(outFile).use { it.write(bytes) }
                            VideoQueueManager.appendLog(context, "✅ Saved Google Flow 8s Video: ${outFile.name}")
                        } catch (e: Exception) {
                            VideoQueueManager.appendLog(context, "⚠️ Blob Video Save Error: ${e.message}")
                        }
                    }
                }, "AuraBlobBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        url?.let { MainActivity.onBrowserUrlChanged?.invoke(it) }
                    }
                }
                webChromeClient = WebChromeClient()

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    try {
                        if (url.startsWith("blob:")) {
                            val js = """
                                (function() {
                                    fetch('$url').then(r => r.blob()).then(b => {
                                        var reader = new FileReader();
                                        reader.onloadend = function() {
                                            AuraBlobBridge.saveBase64Video(reader.result, 'flow_video_' + Date.now() + '.mp4');
                                        };
                                        reader.readAsDataURL(b);
                                    });
                                })();
                            """.trimIndent()
                            evaluateJavascript(js, null)
                            return@setDownloadListener
                        }

                        val rawName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        val fileName = if (rawName.endsWith(".bin", true) || !rawName.contains(".")) {
                            "flow_video_${System.currentTimeMillis()}.mp4"
                        } else rawName

                        val request = DownloadManager.Request(Uri.parse(url)).apply {
                            setMimeType(mimeType.ifBlank { "video/mp4" })
                            addRequestHeader("User-Agent", userAgent)
                            CookieManager.getInstance().getCookie(url)?.let {
                                addRequestHeader("Cookie", it)
                            }
                            setTitle(fileName)
                            setDescription("AuraDroid AI Downloading Video...")
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AutoVideos/$fileName")
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)
                        VideoQueueManager.appendLog(context, "⬇️ Downloading Video: $fileName")
                    } catch (e: Exception) {
                        VideoQueueManager.appendLog(context, "⚠️ Download error: ${e.message}")
                    }
                }

                measure(
                    View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 540, 960)
                loadUrl(VideoQueueManager.getPinnedFlowUrl(context))
            }
            sharedWebView = wv
        }
        return sharedWebView!!
    }

    suspend fun captureWebViewScreenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val wv = getOrCreateSharedWebView()
                if (wv.width <= 10 || wv.height <= 10) {
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 540, 960)
                }

                val captureW = wv.width.coerceAtLeast(540)
                val captureH = wv.height.coerceAtLeast(960)

                val bmp = Bitmap.createBitmap(captureW, captureH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.parseColor("#121316"))
                wv.draw(canvas)

                val scaled = Bitmap.createScaledBitmap(bmp, 540, 960, true)
                if (scaled != bmp) bmp.recycle()

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 68, baos)
                scaled.recycle()

                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                if (cont.isActive) cont.resume(b64)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun toggleDesktopMode(): Boolean {
        isDesktopMode = !isDesktopMode
        val wv = getOrCreateSharedWebView()
        wv.settings.userAgentString = if (isDesktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        wv.reload()
        return isDesktopMode
    }

    fun attachWebViewToContainer(targetContainer: FrameLayout) {
        mainHandler.post {
            val wv = getOrCreateSharedWebView()
            (wv.parent as? ViewGroup)?.removeView(wv)
            targetContainer.removeAllViews()
            targetContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            wv.requestFocus()
        }
    }

    /**
     * Immediately collapses any open Mini Window / Full Screen browser back to the compact floating bot icon
     * so native apps like YouTube or WhatsApp are never covered!
     */
    fun collapseToCompactStage() {
        setPodStage(1)
    }

    fun show() {
        mainHandler.post {
            if (!isPodVisible) return@post
            if (isAdded && podRootLayout?.windowToken != null) return@post

            try { pointerCanvasView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            try { podRootLayout?.let { windowManager.removeView(it) } } catch (_: Exception) {}

            try {
                val canvasParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                pointerCanvasView = CuteBaymaxHandCursorCanvas(context).apply { visibility = View.GONE }
                windowManager.addView(pointerCanvasView, canvasParams)

                podLayoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = dpToPx(16)
                    y = dpToPx(48)
                }

                podRootLayout = buildThreeStagePodUi()
                windowManager.addView(podRootLayout, podLayoutParams)
                isAdded = true
                currentStage = 1
                setSideBarExpanded(AutoTouchService.isJobRunning)
            } catch (e: Exception) {
                isAdded = false
            }
        }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseSideBarRunnable)
            try { pointerCanvasView?.stopAnimations(); pointerCanvasView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            try { podWebFrame?.removeAllViews(); podRootLayout?.let { windowManager.removeView(it) } } catch (_: Exception) {}
            pointerCanvasView = null
            podRootLayout = null
            isAdded = false
            currentStage = 1
        }
    }

    fun togglePodVisibility(): Boolean {
        isPodVisible = !isPodVisible
        if (isPodVisible) { isAdded = false; show() } else { hide() }
        return isPodVisible
    }

    fun setCursorOverlayVisible(visible: Boolean) {
        mainHandler.post {
            pointerCanvasView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun setSideBarExpanded(expanded: Boolean, autoHideAfterMs: Long = 0L) {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseSideBarRunnable)
            isSideBarExpanded = expanded
            val root = podRootLayout ?: return@post
            val params = podLayoutParams ?: return@post

            if (expanded || currentStage > 1) {
                sideInfoContainer?.visibility = View.VISIBLE
                root.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F2181A20"))
                    cornerRadius = dpToPx(32).toFloat()
                    setStroke(dpToPx(1), Color.parseColor("#334155"))
                }
                root.setPadding(dpToPx(4), dpToPx(3), dpToPx(5), dpToPx(3))

                if (autoHideAfterMs > 0L && !AutoTouchService.isJobRunning && currentStage == 1) {
                    mainHandler.postDelayed(autoCollapseSideBarRunnable, autoHideAfterMs)
                }
            } else {
                sideInfoContainer?.visibility = View.GONE
                root.background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                root.setPadding(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1))
            }

            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
        }
    }

    fun updateStatus(stepBadge: String, detailMessage: String, mood: String = "WORKING") {
        mainHandler.post {
            if (isPodVisible && (!isAdded || podRootLayout?.windowToken == null)) show()
            tvPodTitle?.text = "Nax Bot"
            tvPodSubtitle?.text = "$stepBadge • $detailMessage"
            tvExpandedStepInfo?.text = "$stepBadge • $detailMessage"
            botAvatarView?.setMood(mood)
            pointerCanvasView?.setHandMood(mood)

            val upperMood = mood.uppercase()
            val isFinishedOrIdle = upperMood in listOf("DONE", "IDLE", "STOPPED", "ERROR", "SAD") && !AutoTouchService.isJobRunning

            if (AutoTouchService.isJobRunning) {
                stopBadge?.visibility = View.VISIBLE
                setSideBarExpanded(true, 0L)
            } else if (isFinishedOrIdle) {
                stopBadge?.visibility = View.GONE
                setSideBarExpanded(true, 2200L)
            }
        }
    }

    fun moveBotAndShowTap(targetX: Float, targetY: Float, actionLabel: String) {
        mainHandler.post {
            if (isPodVisible && (!isAdded || podRootLayout?.windowToken == null)) show()
            pointerCanvasView?.visibility = View.VISIBLE
            pointerCanvasView?.animateHandToTap(targetX, targetY, actionLabel)
        }
    }

    private fun setPodStage(stage: Int) {
        mainHandler.post {
            currentStage = stage
            val params = podLayoutParams ?: return@post
            val root = podRootLayout ?: return@post
            val dm = context.resources.displayMetrics

            when (stage) {
                1 -> {
                    expandedContainer?.visibility = View.GONE
                    podWebFrame?.removeAllViews()
                    sharedWebView?.let { wv ->
                        wv.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
                        wv.layout(0, 0, 540, 960)
                    }
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    setSideBarExpanded(AutoTouchService.isJobRunning, if (AutoTouchService.isJobRunning) 0L else 2200L)
                }
                2 -> {
                    setSideBarExpanded(true, 0L)
                    expandedContainer?.visibility = View.VISIBLE
                    btnExpandFullToggle?.text = "⛶ Full Screen"
                    params.width = (dm.widthPixels * 0.88f).toInt()
                    params.height = (dm.heightPixels * 0.46f).toInt()
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    podWebFrame?.let { attachWebViewToContainer(it) }
                }
                3 -> {
                    setSideBarExpanded(true, 0L)
                    expandedContainer?.visibility = View.VISIBLE
                    btnExpandFullToggle?.text = "↙ Mini Window"
                    params.x = 0; params.y = dpToPx(28)
                    params.width = dm.widthPixels
                    params.height = (dm.heightPixels * 0.88f).toInt()
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    podWebFrame?.let { attachWebViewToContainer(it) }
                }
            }
            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildThreeStagePodUi(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1))
        }

        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        botAvatarView = ExpressiveCompanionAvatar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(58))
        }
        compactRow?.addView(botAvatarView)

        sideInfoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpToPx(3)
            }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dpToPx(104), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvPodTitle = TextView(context).apply {
            text = "Nax Bot"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        tvPodSubtitle = TextView(context).apply {
            text = "Ready • Tap 🌐"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        textCol.addView(tvPodTitle)
        textCol.addView(tvPodSubtitle)
        sideInfoContainer?.addView(textCol)

        btnQuickMiniBrowser = TextView(context).apply {
            text = "🌐"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3))
            background = GradientDrawable().apply { setColor(Color.parseColor("#2563EB")); cornerRadius = dpToPx(9).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpToPx(3) }
            setOnClickListener { if (currentStage == 1) setPodStage(2) else setPodStage(1) }
        }
        sideInfoContainer?.addView(btnQuickMiniBrowser)

        stopBadge = TextView(context).apply {
            text = "■"
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3))
            background = GradientDrawable().apply { setColor(Color.parseColor("#EF4444")); cornerRadius = dpToPx(9).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpToPx(3) }
            setOnClickListener { AutoTouchService.instance?.stopCurrentJob() }
        }
        sideInfoContainer?.addView(stopBadge)
        compactRow?.addView(sideInfoContainer)
        root.addView(compactRow)

        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { topMargin = dpToPx(8) }
        }

        val controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }

        tvExpandedStepInfo = TextView(context).apply {
            text = "Live Browser Workspace"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        controlBar.addView(tvExpandedStepInfo)

        btnExpandFullToggle = TextView(context).apply {
            text = "⛶ Full Screen"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            background = GradientDrawable().apply { setColor(Color.parseColor("#3B82F6")); cornerRadius = dpToPx(12).toFloat() }
            setOnClickListener { if (currentStage == 2) setPodStage(3) else setPodStage(2) }
        }
        controlBar.addView(btnExpandFullToggle)

        val btnMinimizePod = TextView(context).apply {
            text = "━ Hide"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpToPx(6) }
            background = GradientDrawable().apply { setColor(Color.parseColor("#262932")); cornerRadius = dpToPx(12).toFloat() }
            setOnClickListener { setPodStage(1) }
        }
        controlBar.addView(btnMinimizePod)
        expandedContainer?.addView(controlBar)

        podWebFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#0D0E11")); cornerRadius = dpToPx(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        expandedContainer?.addView(podWebFrame)
        root.addView(expandedContainer)

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var isDragging = false

        compactRow?.setOnTouchListener { _, event ->
            val params = podLayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        if (currentStage != 3) {
                            params.x = (startX + dx).coerceIn(0, 960)
                            params.y = (startY + dy).coerceIn(20, 2150)
                            try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (currentStage > 1) {
                            setPodStage(1)
                        } else if (!isSideBarExpanded) {
                            setSideBarExpanded(true, 3000L)
                        } else {
                            setPodStage(2)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        return root
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }

    /**
     * Nax Bot: compact rounded white companion. Solid eye discs cycle color while idle and
     * map the agent's task states to 18 expression moods. The parent keeps the original 64x58dp size.
     */
    private class ExpressiveCompanionAvatar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var mood = "IDLE"

        private val colorCycle = intArrayOf(
            Color.parseColor("#00D7FF"),
            Color.parseColor("#4F8CFF"),
            Color.parseColor("#B56BFF"),
            Color.parseColor("#FF5BBE"),
            Color.parseColor("#28D98B"),
            Color.parseColor("#FFC247")
        )

        // Exactly 15 canonical moods. Runtime task states map to these rather than adding more.
        private val supportedEmotions = setOf(
            "NORMAL", "IDLE", "HAPPY", "THINKING", "FOCUSED",
            "SURPRISED", "SAD", "CONFUSED", "WINK", "SLEEPY",
            "LISTENING", "WORKING", "ERROR", "SUCCESS", "WAITING"
        )

        private val animator = object : Runnable {
            override fun run() {
                phase += 0.17f
                invalidate()
                postDelayed(this, 40)
            }
        }

        init {
            contentDescription = "Nax Bot companion"
            post(animator)
        }

        fun setMood(newMood: String) {
            val requested = newMood.trim().uppercase()
            mood = when (requested) {
                "DONE", "COMPLETE", "COMPLETED" -> "SUCCESS"
                "PROCESSING", "ANALYZING" -> "THINKING"
                "FAILED" -> "ERROR"
                "WORRIED", "DISCONNECTED" -> "SAD"
                "READY", "START", "STOPPED" -> "IDLE"
                "BUSY", "EXECUTING" -> "WORKING"
                "WAIT", "PAUSED" -> "WAITING"
                "HAPPY" -> "HAPPY"
                "CURIOUS" -> "SURPRISED"
                "SLEEPING" -> "SLEEPY"
                else -> if (requested in supportedEmotions) requested else "NORMAL"
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(animator)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val bobY = sin(phase * 0.65f) * 1.3f
            val cx = w * 0.5f

            val dynamicColor = colorCycle[((phase / 5f).toInt() % colorCycle.size).coerceAtLeast(0)]
            val eyeColor = when (mood) {
                "ERROR", "SAD" -> Color.parseColor("#FF4F68")
                "THINKING", "FOCUSED", "LISTENING", "WORKING" -> Color.parseColor("#00CFE8")
                "SUCCESS" -> Color.parseColor("#20CF88")
                "HAPPY" -> Color.parseColor("#FF5BBE")
                "WAITING", "SLEEPY" -> Color.parseColor("#FFB12E")
                "SURPRISED", "CONFUSED" -> Color.parseColor("#8E7BFF")
                else -> dynamicColor
            }
            val outlineColor = Color.parseColor("#CFD8E3")
            val faceRect = RectF(w * 0.055f, h * 0.025f + bobY, w * 0.945f, h * 0.965f + bobY)

            // Soft single capsule silhouette (no dark visor), preserving the existing avatar bounds.
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F9FBFD")
            canvas.drawRoundRect(faceRect, h * 0.47f, h * 0.47f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.2f, w * 0.018f)
            paint.color = outlineColor
            canvas.drawRoundRect(faceRect, h * 0.47f, h * 0.47f, paint)

            // Large solid color-changing eyes: the old thin rings are replaced by filled discs.
            val leftEyeX = w * 0.305f
            val rightEyeX = w * 0.695f
            val eyeY = h * 0.425f + bobY
            val eyeRadius = minOf(w * 0.158f, h * 0.172f)
            val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#526174")
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1.4f, w * 0.022f)
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(leftEyeX + eyeRadius * 0.66f, eyeY, rightEyeX - eyeRadius * 0.66f, eyeY, bridgePaint)

            fun drawSolidEye(x: Float, radius: Float, color: Int) {
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawCircle(x, eyeY, radius, paint)
                paint.color = Color.argb(225, 255, 255, 255)
                canvas.drawCircle(x - radius * 0.28f, eyeY - radius * 0.30f, radius * 0.19f, paint)
            }

            when (mood) {
                "WINK" -> {
                    drawSolidEye(leftEyeX, eyeRadius, eyeColor)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(2f, w * 0.04f)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = eyeColor
                    canvas.drawLine(rightEyeX - eyeRadius * 0.75f, eyeY, rightEyeX + eyeRadius * 0.75f, eyeY, paint)
                }
                "SLEEPY", "SLEEPING" -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(2f, w * 0.045f)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = eyeColor
                    canvas.drawArc(RectF(leftEyeX - eyeRadius, eyeY - eyeRadius * 0.35f, leftEyeX + eyeRadius, eyeY + eyeRadius * 0.30f), 190f, 160f, false, paint)
                    canvas.drawArc(RectF(rightEyeX - eyeRadius, eyeY - eyeRadius * 0.35f, rightEyeX + eyeRadius, eyeY + eyeRadius * 0.30f), 190f, 160f, false, paint)
                }
                else -> {
                    val occasionalBlink = sin(phase * 0.82f) > 0.993f && mood !in setOf("SURPRISED", "ERROR")
                    if (occasionalBlink) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = maxOf(2f, w * 0.045f)
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.color = eyeColor
                        canvas.drawLine(leftEyeX - eyeRadius * 0.8f, eyeY, leftEyeX + eyeRadius * 0.8f, eyeY, paint)
                        canvas.drawLine(rightEyeX - eyeRadius * 0.8f, eyeY, rightEyeX + eyeRadius * 0.8f, eyeY, paint)
                    } else {
                        drawSolidEye(leftEyeX, eyeRadius, eyeColor)
                        drawSolidEye(rightEyeX, eyeRadius, eyeColor)
                    }
                }
            }

            val mouthY = h * 0.645f + bobY
            val mouthWidth = w * 0.125f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.8f, w * 0.032f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.parseColor("#455366")
            when (mood) {
                "SAD", "ERROR" -> canvas.drawArc(
                    RectF(cx - mouthWidth, mouthY, cx + mouthWidth, mouthY + h * 0.14f),
                    205f, 130f, false, paint
                )
                "HAPPY", "SUCCESS" -> canvas.drawArc(
                    RectF(cx - mouthWidth, mouthY - h * 0.015f, cx + mouthWidth, mouthY + h * 0.12f),
                    15f, 150f, false, paint
                )
                "SURPRISED" -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, mouthY + h * 0.045f, h * 0.045f, paint)
                }
                "CONFUSED", "THINKING" -> canvas.drawLine(cx - mouthWidth, mouthY + h * 0.035f, cx + mouthWidth * 0.7f, mouthY, paint)
                "LISTENING", "FOCUSED" -> canvas.drawLine(cx - mouthWidth * 0.7f, mouthY, cx + mouthWidth * 0.7f, mouthY, paint)
                else -> canvas.drawArc(
                    RectF(cx - mouthWidth, mouthY - h * 0.015f, cx + mouthWidth, mouthY + h * 0.095f),
                    18f, 145f, false, paint
                )
            }
        }
    }

    private class CuteBaymaxHandCursorCanvas(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC"); textSize = 27f; typeface = Typeface.DEFAULT_BOLD }
        private val screenOffset = IntArray(2)

        private var handX = 540f; private var handY = 400f; private var startX = 540f; private var startY = 400f
        private var targetX = 540f; private var targetY = 400f; private var rippleProgress = 0f; private var pressScale = 1f
        private var phase = 0f; private var currentLabel = ""; private var handMood = "WORKING"

        private var moveAnimator: ValueAnimator? = null
        private var rippleAnimator: ValueAnimator? = null

        private val ticker = object : Runnable {
            override fun run() { phase += 0.2f; invalidate(); postDelayed(this, 40) }
        }

        init { post(ticker) }

        fun setHandMood(mood: String) { handMood = mood.uppercase(); invalidate() }

        fun animateHandToTap(destX: Float, destY: Float, label: String) {
            startX = handX; startY = handY; targetX = destX; targetY = destY; currentLabel = label
            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340; interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    handX = startX + (targetX - startX) * f; handY = startY + (targetY - startY) * f
                    invalidate()
                }
                start()
            }
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 260; duration = 620
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float; rippleProgress = p
                    pressScale = if (p < 0.35f) 1f - (p / 0.35f) * 0.16f else 0.84f + ((p - 0.35f) / 0.65f) * 0.16f
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimations() { removeCallbacks(ticker); moveAnimator?.cancel(); rippleAnimator?.cancel() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (currentLabel.isEmpty()) return
            getLocationOnScreen(screenOffset)
            val ox = screenOffset[0].toFloat(); val oy = screenOffset[1].toFloat()
            val tipX = handX - ox; val tipY = (handY - oy) + sin(phase * 0.8f) * 2.5f

            val accentColor = when (handMood) { "ERROR", "SAD" -> Color.parseColor("#EF4444"); "DONE", "HAPPY" -> Color.parseColor("#10B981"); else -> Color.parseColor("#00E5FF") }

            if (rippleProgress > 0f && rippleProgress < 1f) {
                val alpha = ((1f - rippleProgress) * 210).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4.5f; paint.color = Color.argb(alpha, 0, 229, 255)
                canvas.drawCircle(targetX - ox, targetY - oy, 12f + rippleProgress * 62f, paint)
            }

            canvas.save(); canvas.translate(tipX, tipY); canvas.scale(pressScale, pressScale)
            paint.style = Paint.Style.FILL; paint.color = Color.argb(55, 0, 0, 0)
            canvas.drawRoundRect(RectF(-22f, 20f, 34f, 74f), 26f, 26f, paint)

            val handFillColor = Color.parseColor("#F8FAFC"); val handStrokeColor = Color.parseColor("#334155")
            val thumbRect = RectF(-34f, 28f, -6f, 52f)
            paint.style = Paint.Style.FILL; paint.color = handFillColor
            canvas.rotate(-22f, -20f, 40f); canvas.drawRoundRect(thumbRect, 14f, 14f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f; paint.color = handStrokeColor
            canvas.drawRoundRect(thumbRect, 14f, 14f, paint); canvas.rotate(22f, -20f, 40f)

            val midFingerRect = RectF(6f, 10f, 22f, 38f); val ringFingerRect = RectF(18f, 16f, 33f, 42f)
            paint.style = Paint.Style.FILL; paint.color = handFillColor
            canvas.drawRoundRect(ringFingerRect, 10f, 10f, paint); paint.style = Paint.Style.STROKE; paint.color = handStrokeColor; canvas.drawRoundRect(ringFingerRect, 10f, 10f, paint)
            paint.style = Paint.Style.FILL; paint.color = handFillColor
            canvas.drawRoundRect(midFingerRect, 10f, 10f, paint); paint.style = Paint.Style.STROKE; paint.color = handStrokeColor; canvas.drawRoundRect(midFingerRect, 10f, 10f, paint)

            val indexFingerRect = RectF(-9f, -2f, 10f, 36f)
            paint.style = Paint.Style.FILL; paint.color = handFillColor
            canvas.drawRoundRect(indexFingerRect, 11f, 11f, paint); paint.style = Paint.Style.STROKE; paint.color = handStrokeColor; canvas.drawRoundRect(indexFingerRect, 11f, 11f, paint)

            val palmRect = RectF(-20f, 22f, 32f, 70f)
            paint.style = Paint.Style.FILL; paint.color = handFillColor; canvas.drawRoundRect(palmRect, 24f, 24f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f; paint.color = handStrokeColor; canvas.drawRoundRect(palmRect, 24f, 24f, paint)

            val cuffRect = RectF(-12f, 64f, 24f, 75f)
            paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#E2E8F0"); canvas.drawRoundRect(cuffRect, 8f, 8f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = handStrokeColor; canvas.drawRoundRect(cuffRect, 8f, 8f, paint)

            paint.style = Paint.Style.FILL; paint.color = accentColor
            canvas.drawCircle(6f, 46f, 4.5f, paint); canvas.drawCircle(0f, 0f, 4f, paint)
            canvas.restore()

            val textWidth = textPaint.measureText(currentLabel)
            val tagLeft = (tipX + 42f).coerceAtMost(width - textWidth - 34f).coerceAtLeast(16f)
            val tagTop = (tipY + 18f).coerceAtMost(height - 70f).coerceAtLeast(50f)
            val tagRect = RectF(tagLeft, tagTop, tagLeft + textWidth + 24f, tagTop + 40f)

            paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#E6181A20"); canvas.drawRoundRect(tagRect, 14f, 14f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f; paint.color = accentColor; canvas.drawRoundRect(tagRect, 14f, 14f, paint)
            canvas.drawText(currentLabel, tagLeft + 12f, tagTop + 28f, textPaint)
        }
    }
}
