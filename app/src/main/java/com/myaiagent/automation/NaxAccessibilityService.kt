package com.myaiagent.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class NaxAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Initial foundation only. YouTube/UI state handlers will be added incrementally.
    }

    override fun onInterrupt() = Unit
}
