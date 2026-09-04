package com.example.jarvis // ध्यान दें: अगर आपकी MainActivity में पैकेज का नाम कुछ और है, तो इसे उसके हिसाब से बदल लें।

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Toast.makeText(this, "J.A.R.V.I.S Eyes Online!", Toast.LENGTH_SHORT).show()
    }

    // यह फंक्शन फोन की हर हलचल (स्क्रीन चेंज) पर नज़र रखेगा
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // स्क्रीन रीडर का एडवांस काम हम यहाँ बाद में जोड़ेंगे
    }

    override fun onInterrupt() {
        // सर्विस क्रैश या बंद होने पर 
    }

    // ⚡ THE MASTER WEAPON: Auto-Typing & Clicking ⚡
    // इस फंक्शन को हम MainActivity से कमांड देंगे कि क्या टाइप करना है
    fun autoTypeAndClick(textToType: String) {
        val rootNode = rootInActiveWindow ?: return

        // 1. स्क्रीन पर टाइपिंग वाला डब्बा (EditText) ढूंढना
        val editableNodes = rootNode.findAccessibilityNodeInfosByText("")
        for (node in editableNodes) {
            if (node.isEditable) {
                // 2. डब्बे में J.A.R.V.I.S खुद टेक्स्ट भर देगा
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                break
            }
        }
    }
}
