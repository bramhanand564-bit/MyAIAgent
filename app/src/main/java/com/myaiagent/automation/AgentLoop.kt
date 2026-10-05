
package com.myaiagent.automation

import android.os.Handler
import android.os.Looper

/**
 * Small observation scheduler for the NAX agent.
 *
 * The loop is intentionally independent from the upload state machine:
 * - one observation tick every configured interval (6s by default)
 * - screenshot/analysis work is supplied by the owner
 * - no coordinate macro or security bypass lives here
 */
class AgentLoop(
    intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = intervalMs.coerceAtLeast(1000L)
    private var running = false
    private var tickAction: (() -> Unit)? = null

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            tickAction?.invoke()
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start(onTick: () -> Unit) {
        stop()
        tickAction = onTick
        running = true
        handler.post(runnable)
    }

    fun updateInterval(newIntervalMs: Long) {
        intervalMs = newIntervalMs.coerceAtLeast(1000L)
        if (running) {
            handler.removeCallbacks(runnable)
            handler.post(runnable)
        }
    }


    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
        tickAction = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 6_000L
    }
}
