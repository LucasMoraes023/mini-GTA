package com.lucasmoraes.fishingpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class FishingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: FishingAccessibilityService? = null
        fun tap(x: Float, y: Float): Boolean {
            val s = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 40)
            return s.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
}