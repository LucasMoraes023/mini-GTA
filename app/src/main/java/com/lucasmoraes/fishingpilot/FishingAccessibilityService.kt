package com.lucasmoraes.fishingpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

class FishingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: FishingAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean {
            return instance?.dispatchTap(x, y) == true
        }
    }

    private lateinit var wm: WindowManager
    private var floatingButton: TextView? = null
    private var menu: LinearLayout? = null
    private var calibrationMarker: TextView? = null
    private var calibrationPanel: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
        val b = TextView(this).apply {
            text = "FP"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(28, 150, 92))
                setStroke(2, Color.WHITE)
            }
            setOnClickListener { toggleMenu() }
        }

        val p = overlayParams(dp(52), dp(52), Gravity.TOP or Gravity.START)
        p.x = dp(12)
        p.y = dp(180)
        wm.addView(b, p)
        floatingButton = b
    }

    private fun toggleMenu() {
        if (menu != null) {
            removeMenu()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(235, 15, 20, 24))
                setStroke(dp(1), Color.rgb(70, 85, 92))
            }
        }

        val title = TextView(this).apply {
            text = "FISHING PILOT"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        box.addView(title)

        box.addView(actionButton("CALIBRAR ALT") { startCalibration() })
        box.addView(actionButton("TESTAR ALT") { testAlt() })
        box.addView(actionButton("FECHAR") { removeMenu() })

        val p = overlayParams(dp(190), dp(210), Gravity.TOP or Gravity.START)
        p.x = dp(70)
        p.y = dp(175)
        wm.addView(box, p)
        menu = box
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
        }
    }

    fun startCalibration() {
        removeMenu()
        removeCalibration()

        lateinit var marker: TextView
        marker = TextView(this).apply {
            text = "+"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(100, 220, 40, 40))
                setStroke(dp(3), Color.WHITE)
            }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        marker.setTag(intArrayOf(e.rawX.roundToInt(), e.rawY.roundToInt()))
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val last = marker.getTag() as? IntArray ?: return@setOnTouchListener true
                        val lp = marker.layoutParams as WindowManager.LayoutParams
                        lp.x += e.rawX.roundToInt() - last[0]
                        lp.y += e.rawY.roundToInt() - last[1]
                        marker.setTag(intArrayOf(e.rawX.roundToInt(), e.rawY.roundToInt()))
                        wm.updateViewLayout(marker, lp)
                        true
                    }
                    else -> true
                }
            }
        }

        val mp = overlayParams(dp(64), dp(64), Gravity.TOP or Gravity.START)
        mp.x = resources.displayMetrics.widthPixels / 2 - dp(32)
        mp.y = resources.displayMetrics.heightPixels / 2 - dp(32)
        wm.addView(marker, mp)
        calibrationMarker = marker

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(245, 12, 16, 19))
            }
        }
        panel.addView(TextView(this).apply {
            text = "CALIBRAR ALT\nArraste o alvo vermelho até o centro do botão ALT."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })
        panel.addView(actionButton("SALVAR POSIÇÃO") {
            saveCalibration()
        })
        panel.addView(actionButton("CANCELAR") {
            removeCalibration()
        })

        val pp = overlayParams(dp(260), dp(155), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        pp.y = dp(40)
        wm.addView(panel, pp)
        calibrationPanel = panel
    }

    private fun saveCalibration() {
        val marker = calibrationMarker ?: return
        val lp = marker.layoutParams as WindowManager.LayoutParams
        val x = lp.x + dp(32)
        val y = lp.y + dp(32)

        getSharedPreferences("pilot", MODE_PRIVATE)
            .edit()
            .putFloat("alt_x", x.toFloat())
            .putFloat("alt_y", y.toFloat())
            .apply()

        removeCalibration()
        showToast("ALT calibrado: $x, $y")
    }

    fun testAlt() {
        val prefs = getSharedPreferences("pilot", MODE_PRIVATE)
        val x = prefs.getFloat("alt_x", -1f)
        val y = prefs.getFloat("alt_y", -1f)
        if (x < 0 || y < 0) {
            startCalibration()
            return
        }
        dispatchTap(x, y)
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 45)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun removeMenu() {
        menu?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        menu = null
    }

    private fun removeCalibration() {
        calibrationMarker?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        calibrationMarker = null
        calibrationPanel?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        calibrationPanel = null
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeMenu()
        removeCalibration()
        floatingButton?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        floatingButton = null
        instance = null
        super.onDestroy()
    }
}
