package com.myaiagent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Native Studio hand-off.
 *
 * We deliberately do not load studio.youtube.com here. The previous WebView
 * rendered the desktop Studio surface inside a phone viewport, which is not a
 * good mobile experience. The official Android YouTube Studio app is the
 * mobile surface and is the target for accessibility automation.
 */
class YouTubeWorkspaceActivity : AppCompatActivity() {

    companion object {
        const val YOUTUBE_STUDIO_PACKAGE = "com.google.android.apps.youtube.creator"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openNativeStudio()
    }

    private fun openNativeStudio() {
        val studioIntent = packageManager.getLaunchIntentForPackage(YOUTUBE_STUDIO_PACKAGE)
        if (studioIntent != null) {
            studioIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(studioIntent)
            finish()
            return
        }

        // Keep a graceful fallback for devices that have only the main YouTube app.
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
