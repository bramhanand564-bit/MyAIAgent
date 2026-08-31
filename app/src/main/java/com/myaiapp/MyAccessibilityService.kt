package com.myaiapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

class MyAccessibilityService : AccessibilityService() {
    
    companion object {
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null; return super.onUnbind(intent)
    }

    fun clickButtonByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                current = current.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    // "Enter" या "Search" दबाने की ताकत!
    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val searchKeywords = listOf("Search", "Go", "Enter", "Submit", "खोजें")
        for (keyword in searchKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                var current: AccessibilityNodeInfo? = nodes[0]
                while (current != null) {
                    if (current.isClickable) {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    current = current.parent
                }
            }
        }
        return false
    }

    fun performGlobal(action: Int) { performGlobalAction(action) }
}
