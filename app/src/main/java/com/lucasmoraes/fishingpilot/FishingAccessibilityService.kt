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
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

class FishingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: FishingAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean {
            return instance?.dispatchTap(x, y) == true
        }

        fun updateStatus(message: String) {
            instance?.mainHandler?.post { instance?.showStatus(message) }
        }
    }

    private lateinit var wm: WindowManager
    private var floatingButton: TextView? = null
    private var menu: LinearLayout? = null
    private var calibrationMarker: TextView? = null
    private var calibrationPanel: LinearLayout? = null
    private var statusPanel: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureOverlay()
    }

    /** Revalida a permissão quando o usuário retorna das Configurações. */
    fun ensureOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            mainHandler.post { showToast("Libere 'Sobrepor a outros apps' para exibir o botão FP") }
            return
        }
        if (floatingButton == null) showFloatingButton()
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
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
        showStatus("CAPTURA DESLIGADA • abra o app e toque ATIVAR CAPTURA")
    }

    private fun showStatus(message: String) {
        val existing = statusPanel
        if (existing != null) {
            existing.text = message
            return
        }

        val t = TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(220, 8, 12, 15))
                setStroke(dp(1), Color.rgb(45, 210, 125))
            }
        }
        val p = overlayParams(dp(330), dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        p.y = dp(32)
        wm.addView(t, p)
        statusPanel = t
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
            textSize = 22f
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

        val mp = overlayParams(dp(36), dp(36), Gravity.TOP or Gravity.START)
        mp.x = resources.displayMetrics.widthPixels / 2 - dp(18)
        mp.y = resources.displayMetrics.heightPixels / 2 - dp(18)
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
        val marker = calibrationMarker ?: run {
            showStatus("CALIBRAÇÃO • marcador não encontrado")
            showToast("Marcador de calibração não encontrado")
            return
        }
        val lp = marker.layoutParams as WindowManager.LayoutParams
        val x = (lp.x + dp(18)).toFloat()
        val y = (lp.y + dp(18)).toFloat()
        getSharedPreferences("pilot", MODE_PRIVATE).edit()
            .putFloat("alt_x", x)
            .putFloat("alt_y", y)
            .apply()
        val check = getSharedPreferences("pilot", MODE_PRIVATE)
        val savedX = check.getFloat("alt_x", -1f)
        val savedY = check.getFloat("alt_y", -1f)
        removeCalibration()
        if (savedX >= 0f && savedY >= 0f) {
            val msg = "ALT CALIBRADO • X=" + savedX.roundToInt() + " Y=" + savedY.roundToInt()
            showStatus(msg)
            showToast(msg)
        } else {
            showStatus("ERRO AO SALVAR CALIBRAÇÃO")
            showToast("Não foi possível salvar a posição do ALT")
        }
    }

    fun testAlt() {
        if (instance !== this) {
            showStatus("ACESSIBILIDADE • serviço não está ativo")
            showToast("Fishing Pilot não está ativo em Acessibilidade")
            return
        }
        val prefs = getSharedPreferences("pilot", MODE_PRIVATE)
        val x = prefs.getFloat("alt_x", -1f)
        val y = prefs.getFloat("alt_y", -1f)
        if (x < 0f || y < 0f) {
            showStatus("ALT NÃO CALIBRADO • faça a calibração primeiro")
            showToast("Calibre o botão ALT primeiro")
            return
        }
        showStatus("TESTE ALT • enviando toque em " + x.roundToInt() + ", " + y.roundToInt())
        dispatchTap(x, y)
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            updateStatus("TOQUE ALT INDISPONÍVEL • Android antigo")
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 65)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    updateStatus("ALT EXECUTADO ✓ • " + x.roundToInt() + ", " + y.roundToInt())
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    updateStatus("ALT CANCELADO PELO ANDROID • " + x.roundToInt() + ", " + y.roundToInt())
                }
            }, mainHandler)
        } catch (t: Throwable) {
            updateStatus("ERRO NO TOQUE ALT • " + t.javaClass.simpleName)
            false
        }
        if (!accepted) {
            updateStatus("ALT NÃO ENVIADO • Acessibilidade recusou o gesto")
        } else {
            updateStatus("ALT ENVIANDO TOQUE • " + x.roundToInt() + ", " + y.roundToInt())
        }
        return accepted
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
        statusPanel?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        statusPanel = null
        instance = null
        super.onDestroy()
    }
}
