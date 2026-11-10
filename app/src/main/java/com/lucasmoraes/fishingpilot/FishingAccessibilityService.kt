package com.lucasmoraes.fishingpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class FishingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: FishingAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            return service.dispatchTap(x, y)
        }
    }

    private var calibrationOverlay: View? = null

    override fun onServiceConnected() {
        instance = this
    }

    fun startCalibration() {
        if (calibrationOverlay != null) return

        val overlay = object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.argb(45, 0, 0, 0))
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val x = event.rawX
                    val y = event.rawY
                    getSharedPreferences("pilot", MODE_PRIVATE)
                        .edit()
                        .putFloat("alt_x", x)
                        .putFloat("alt_y", y)
                        .apply()
                    removeCalibration()
                    return true
                }
                return true
            }
        }

        overlay.setBackgroundColor(Color.TRANSPARENT)
        overlay.isClickable = true
        overlay.isFocusable = false

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(overlay, params)
        calibrationOverlay = overlay
    }

    private fun removeCalibration() {
        val overlay = calibrationOverlay ?: return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(overlay)
        } catch (_: Throwable) {
        }
        calibrationOverlay = null
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 35)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeCalibration()
        instance = null
        super.onDestroy()
    }
}
