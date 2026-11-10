package com.lucasmoraes.fishingpilot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCode = 7001
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0xFF0B0F12.toInt())
        }

        val title = TextView(this).apply {
            text = "FISHING PILOT 1.2"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val sub = TextView(this).apply {
            text = "Detecção local • acionamento por acessibilidade • baixa latência"
            textSize = 14f
            setTextColor(0xFF9AA5AD.toInt())
            setPadding(0, 8, 0, 28)
        }

        status = TextView(this).apply {
            text = "Pronto"
            textSize = 16f
            setTextColor(0xFF26D07C.toInt())
            setPadding(0, 0, 0, 20)
        }

        val accessibility = Button(this).apply {
            text = "ATIVAR CONTROLE"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val overlay = Button(this).apply {
            text = "LIBERAR BOTÃO FLUTUANTE"
            setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    status.text = "Permissão de janela flutuante já liberada"
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }

        val calibrate = Button(this).apply {
            text = "CALIBRAR BOTÃO ALT"
            setOnClickListener {
                if (FishingAccessibilityService.instance != null) {
                    FishingAccessibilityService.instance?.startCalibration()
                    status.text = "Toque no botão ALT do jogo"
                } else {
                    status.text = "Ative o serviço de acessibilidade primeiro"
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }

        val start = Button(this).apply {
            text = "ATIVAR CAPTURA"
            setOnClickListener { requestProjection() }
        }

        val stop = Button(this).apply {
            text = "PARAR"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureServiceFixed::class.java))
                status.text = "Parado"
            }
        }

        val info = TextView(this).apply {
            text = "1) Ative Fishing Pilot em Acessibilidade. 2) Calibre o botão ALT tocando nele dentro do jogo. 3) Ative a captura. O alvo verde é recalculado a cada rodada e o toque é disparado automaticamente."
            setTextColor(0xFFB8C1C8.toInt())
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }

        root.addView(title)
        root.addView(sub)
        root.addView(status)
        root.addView(accessibility)
        root.addView(overlay)
        root.addView(calibrate)
        root.addView(start)
        root.addView(stop)
        root.addView(info)
        setContentView(root)
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            status.text = "Captura ativa • monitorando pesca"
            startForegroundService(
                Intent(this, CaptureServiceFixed::class.java)
                    .putExtra("resultCode", resultCode)
                    .putExtra("data", data)
            )
        }
    }
}
