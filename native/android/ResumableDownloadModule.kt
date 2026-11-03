package com.localaiagent

import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * HTTP Range downloader. Interrupted transfers retain <destination>.part and resume
 * from its byte length on the next invocation. The final file is exposed only after
 * the transfer passes the configured minimum-size check.
 */
class ResumableDownloadModule(
    private val appContext: ReactApplicationContext
) : ReactContextBaseJavaModule(appContext) {

    private val worker = Executors.newSingleThreadExecutor()

    override fun getName(): String = "ResumableDownload"

    @ReactMethod
    fun download(
        urlValue: String,
        destinationPath: String,
        expectedBytes: Double,
        onProgress: Callback,
        promise: Promise
    ) {
        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                require(urlValue.startsWith("https://")) {
                    "Model downloads must use HTTPS."
                }
                val destination = File(destinationPath)
                val parent = destination.parentFile
                    ?: throw IllegalArgumentException("Destination directory is invalid.")
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IllegalStateException("Could not create model directory.")
                }

                val partial = File(destinationPath + ".part")
                var existingBytes = if (partial.exists()) partial.length() else 0L
                val expected = expectedBytes.toLong().coerceAtLeast(0L)

                connection = (URL(urlValue).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 90_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=" + existingBytes + "-")
                    }
                    connect()
                }

                var status = connection.responseCode
                if (status == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE &&
                    expected > 0L && existingBytes >= expected * 0.9
                ) {
                    if (destination.exists()) destination.delete()
                    if (!partial.renameTo(destination)) {
                        throw IllegalStateException("Could not finalize the completed partial model.")
                    }
                    promise.resolve(destination.absolutePath)
                    return@execute
                }

                if (status == HttpURLConnection.HTTP_OK && existingBytes > 0L) {
                    // Server ignored Range: restart safely rather than corrupting the file.
                    existingBytes = 0L
                } else if (status != HttpURLConnection.HTTP_OK &&
                    status != HttpURLConnection.HTTP_PARTIAL
                ) {
                    throw IllegalStateException("Model server returned HTTP " + status)
                }

                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    val contentRange = connection.getHeaderField("Content-Range") ?: ""
                    val rangeStart = Regex("^bytes (\\d+)-").find(contentRange)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (rangeStart == null || rangeStart != existingBytes) {
                        throw IllegalStateException("Server returned an invalid Content-Range header.")
                    }
                }

                val totalFromRange = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast("/")
                    ?.toLongOrNull()
                val responseLength = connection.contentLengthLong
                val totalBytes = totalFromRange
                    ?: if (expected > 0L) expected else existingBytes + responseLength.coerceAtLeast(0L)

                val append = status == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                var downloaded = if (append) existingBytes else 0L
                if (!append) {
                    if (partial.exists() && !partial.delete()) {
                        throw IllegalStateException("Could not reset incomplete model file.")
                    }
                }

                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val percent = if (totalBytes > 0L) {
                                ((downloaded.toDouble() / totalBytes.toDouble()) * 100.0)
                                    .toInt().coerceIn(0, 99)
                            } else 0
                            onProgress.invoke(percent)
                        }
                        output.fd.sync()
                    }
                }

                val actualBytes = partial.length()
                if (expected > 0L && actualBytes < expected * 0.9) {
                    throw IllegalStateException(
                        "Model download is incomplete (" + actualBytes + " of about " + expected + " bytes)."
                    )
                }
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Could not replace the previous model file.")
                }
                if (!partial.renameTo(destination)) {
                    throw IllegalStateException("Could not rename the downloaded model file.")
                }
                onProgress.invoke(100)
                promise.resolve(destination.absolutePath)
            } catch (error: Exception) {
                // Keep .part intact so the next attempt can resume.
                promise.reject("MODEL_DOWNLOAD_FAILED", error.message, error)
            } finally {
                connection?.disconnect()
            }
        }
    }

    override fun invalidate() {
        worker.shutdown()
        super.invalidate()
    }
}
