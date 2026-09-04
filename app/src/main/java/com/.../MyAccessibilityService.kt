package com.example.jarvis // ⚠️ अपनी फाइल का पुराना असली पैकेज नाम ही यहाँ रखें!

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

    // 1. स्क्रीन पर सर्च बॉक्स (EditText) ढूंढकर खुद टाइप करना
    fun autoType(textToType: String) {
        val rootNode = rootInActiveWindow ?: return
        val editNodes = findEditTextNodes(rootNode)
        
        if (editNodes.isNotEmpty()) {
            val searchBox = editNodes[0] // पहला डब्बा उठाओ
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    // 2. स्क्रीन पर मौजूद किसी भी बटन को उसके नाम से क्लिक करना!
    fun clickButtonByText(buttonName: String) {
        val rootNode = rootInActiveWindow ?: return
        val clickNodes = rootNode.findAccessibilityNodeInfosByText(buttonName)
        
        for (node in clickNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            } else if (node.parent?.isClickable == true) {
                // अगर टेक्स्ट क्लिक नहीं हो सकता, तो उसके डब्बे (parent) पर क्लिक करो
                node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
        }
    }

    // 3. ग्लोबल कमांड्स (पूरे फोन को कंट्रोल करना)
    fun executeSystemCommand(command: String) {
        when (command.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK) // बैक बटन दबाना
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME) // होम स्क्रीन पर जाना
            "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS) // रीसेंट ऐप्स खोलना
            "notification" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) // ऊपर से शटर गिराना
        }
    }

    // इंटरनल फंक्शन: स्क्रीन में 'टाइप करने वाला डब्बा' खोजने के लिए
    private fun findEditTextNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (child.className?.toString()?.contains("EditText") == true) {
                nodes.add(child)
            }
            nodes.addAll(findEditTextNodes(child)) // अंदर तक सर्च करो
        }
        return nodes
    }
}
