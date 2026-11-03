package com.localaiagent

import android.net.Uri
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.File
import java.util.concurrent.Executors

class PdfTextModule(private val appContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(appContext) {

    private val worker = Executors.newSingleThreadExecutor()

    override fun getName(): String = "PdfTextExtractor"

    private fun openDocument(uriValue: String): PDDocument {
        PDFBoxResourceLoader.init(appContext)
        val uri = if (uriValue.startsWith("content://") || uriValue.startsWith("file://")) {
            Uri.parse(uriValue)
        } else {
            Uri.fromFile(File(uriValue))
        }
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open selected PDF")
        return try {
            PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly())
        } finally {
            stream.close()
        }
    }

    @ReactMethod
    fun getPageCount(uriValue: String, promise: Promise) {
        worker.execute {
            var document: PDDocument? = null
            try {
                document = openDocument(uriValue)
                promise.resolve(document.numberOfPages)
            } catch (error: Exception) {
                promise.reject("PDF_READ_FAILED", error.message, error)
            } finally {
                try { document?.close() } catch (_: Exception) { }
            }
        }
    }

    @ReactMethod
    fun extractPages(uriValue: String, startPage: Int, pageCount: Int, promise: Promise) {
        worker.execute {
            var document: PDDocument? = null
            try {
                require(startPage >= 1) { "startPage must be at least 1" }
                require(pageCount in 1..10) { "pageCount must be between 1 and 10" }
                document = openDocument(uriValue)
                val endPage = minOf(document.numberOfPages, startPage + pageCount - 1)
                val output = Arguments.createArray()
                for (page in startPage..endPage) {
                    val stripper = PDFTextStripper().apply {
                        this.startPage = page
                        this.endPage = page
                    }
                    val item = Arguments.createMap()
                    item.putInt("page", page)
                    item.putString("text", stripper.getText(document))
                    output.pushMap(item)
                }
                promise.resolve(output)
            } catch (error: Exception) {
                promise.reject("PDF_READ_FAILED", error.message, error)
            } finally {
                try { document?.close() } catch (_: Exception) { }
            }
        }
    }

    override fun invalidate() {
        worker.shutdown()
        super.invalidate()
    }
}
