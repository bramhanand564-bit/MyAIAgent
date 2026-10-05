package com.myaiagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Native Studio hand-off.
 *
 * The test/queue already owns the exact video URI. We first try to pass that
 * URI directly into the official YouTube Studio app using Android's standard
 * ACTION_SEND media hand-off. If Studio does not expose a compatible receiver,
 * we fall back to the normal Studio launch and let the verified Accessibility
 * flow handle its picker.
 */
class YouTubeWorkspaceActivity : AppCompatActivity() {

    companion object {
        const val YOUTUBE_STUDIO_PACKAGE = "com.google.android.apps.youtube.creator"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        const val EXTRA_VIDEO_URI = "preselected_video_uri"
        const val EXTRA_VIDEO_NAME = "preselected_video_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openNativeStudio()
    }

    private fun openNativeStudio() {
        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        val fileName = intent.getStringExtra(EXTRA_VIDEO_NAME).orEmpty()

        if (!uriString.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            if (uri != null && tryDirectVideoHandoff(uri, fileName)) {
                return
            }
        }

        openStudioNormally()
    }

    private fun tryDirectVideoHandoff(uri: Uri, fileName: String): Boolean {
        val mediaIntent = Intent(Intent.ACTION_SEND).apply {
            setPackage(YOUTUBE_STUDIO_PACKAGE)
            type = contentResolver.getType(uri) ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val receiver = packageManager.resolveActivity(mediaIntent, 0)
        if (receiver == null) {
            return false
        }

        return runCatching {
            startActivity(mediaIntent)
            Toast.makeText(
                this,
                if (fileName.isBlank()) "Sending selected video to YouTube Studio"
                else "Sending $fileName to YouTube Studio",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            true
        }.getOrDefault(false)
    }

    private fun openStudioNormally() {
        val studioIntent = packageManager.getLaunchIntentForPackage(YOUTUBE_STUDIO_PACKAGE)
        if (studioIntent != null) {
            studioIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(studioIntent)
            finish()
            return
        }

        val youtubeIntent = packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
        if (youtubeIntent != null) {
            youtubeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(youtubeIntent)
            Toast.makeText(
                this,
                "YouTube Studio app not installed. Opened YouTube instead.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        Toast.makeText(
            this,
            "Install the official YouTube Studio app to continue.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
