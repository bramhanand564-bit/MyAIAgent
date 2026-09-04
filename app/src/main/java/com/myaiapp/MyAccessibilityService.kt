package com.myaiapp

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Toast.makeText(this, "J.A.R.V.I.S Super-Hands Online!", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // 1. खुद टाइप करने की ताकत
    fun autoType(textToType: String) {
        val rootNode = rootInActiveWindow ?: return
        val editNodes = findEditTextNodes(rootNode)
        
        if (editNodes.isNotEmpty()) {
            val searchBox = editNodes[0]
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    // 2. खुद बटन क्लिक करने की ताकत
    fun clickButtonByText(buttonName: String) {
        val rootNode = rootInActiveWindow ?: return
        val clickNodes = rootNode.findAccessibilityNodeInfosByText(buttonName)
        
        for (node in clickNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            } else if (node.parent?.isClickable == true) {
                node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
        }
    }

    // 3. सिस्टम को कंट्रोल करने की ताकत (Home, Back, Notification)
    fun executeSystemCommand(command: String) {
        when (command.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notification" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    // डब्बा खोजने वाला इंटरनल फंक्शन
    private fun findEditTextNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (child.className?.toString()?.contains("EditText") == true) {
                nodes.add(child)
            }
            nodes.addAll(findEditTextNodes(child))
        }
        return nodes
    }
}
