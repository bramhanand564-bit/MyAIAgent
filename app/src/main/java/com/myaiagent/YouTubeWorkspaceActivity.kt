package com.myaiagent

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.myaiagent.queue.UploadQueueStore

class YouTubeWorkspaceActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val pickWebUploadFile = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(
            if (uris.isNotEmpty()) uris.toTypedArray() else null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemId = intent.getStringExtra("item_id")
        val queuedItem = itemId?.let { id ->
            UploadQueueStore(this).load().firstOrNull { it.id == id }
        }

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val queuedUri = queuedItem?.uri?.let(Uri::parse)
                val queuedType = queuedUri?.let {
                    contentResolver.getType(it)
                }

                if (queuedUri != null &&
                    (queuedType?.startsWith("video/") == true || params?.acceptTypes?.any { it.startsWith("video/") } == true)
                ) {
                    filePathCallback?.onReceiveValue(arrayOf(queuedUri))
                    filePathCallback = null
                    return true
                }

                val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() }
                val mimeTypes = if (acceptTypes.isNullOrEmpty()) {
                    arrayOf("video/*")
                } else {
                    acceptTypes.toTypedArray()
                }
                pickWebUploadFile.launch(mimeTypes)
                return true
            }
        }

        webView.loadUrl("https://studio.youtube.com/")
        setContentView(webView)
    }

    @Deprecated("Use OnBackInvokedDispatcher on newer Android versions.")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
