package com.lucasmoraes.fishingpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class CaptureServiceFixed : Service() {
    companion object {
        const val EXTRA_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture_fixed"
        private const val NOTIFICATION_ID = 11
    }

    private val detector = RobustFishingDetector()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastStatus = 0L
    private var lastCaptureDiagnostic = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        stopCapture()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: return START_NOT_STICKY
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (code == 0 || data == null) return START_NOT_STICKY

        val t = HandlerThread("fishing-capture-fixed").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data) ?: return START_NOT_STICKY
        projection = p

        val cb = object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }
        p.registerCallback(cb, handler)

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ onImage(it) }, handler)

        p.createVirtualDisplay(
            "FishingPilotFixed", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )

        detector.reset()
        FishingAccessibilityService.updateStatus("CAPTURA OK • procurando a barra de pesca")
        return START_NOT_STICKY
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val now = SystemClock.elapsedRealtime()

            if (now - lastCaptureDiagnostic >= 750L) {
                lastCaptureDiagnostic = now
                FishingAccessibilityService.updateStatus(
                    diagnoseCapture(bytes, image.width, image.height, plane.pixelStride, plane.rowStride)
                )
            }

            val result = detector.process(
                bytes, image.width, image.height, plane.pixelStride, plane.rowStride, now
            )
            if (result.fire) FishingAccessibilityService.updateStatus(result.message)
            else if (now - lastStatus >= 1200L) {
                lastStatus = now
                FishingAccessibilityService.updateStatus(result.message)
            }
            if (result.fire) triggerAlt()
        } finally {
            image.close()
        }
    }

    private fun diagnoseCapture(
        data: ByteArray, w: Int, h: Int, px: Int, row: Int
    ): String {
        var samples = 0
        var nonBlack = 0
        var varied = 0
        var sum = 0L
        var maxLuma = 0
        val stepX = maxOf(8, w / 80)
        val stepY = maxOf(8, h / 80)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val o = y * row + x * px
                if (o + 2 < data.size) {
                    val r = data[o].toInt() and 255
                    val g = data[o + 1].toInt() and 255
                    val b = data[o + 2].toInt() and 255
                    val luma = (r * 30 + g * 59 + b * 11) / 100
                    samples++
                    sum += luma
                    maxLuma = maxOf(maxLuma, luma)
                    if (luma > 12) nonBlack++
                    if (maxOf(r, g, b) - minOf(r, g, b) > 12) varied++
                }
                x += stepX
            }
            y += stepY
        }

        if (samples == 0) return "CAPTURA SEM DADOS"
        val avg = sum / samples
        val useful = nonBlack * 100 / samples
        val colorVar = varied * 100 / samples

        return when {
            useful < 3 && maxLuma < 25 ->
                "CAPTURA BLOQUEADA/VAZIA • " + w + "x" + h + " • pixels quase pretos"
            useful < 12 && maxLuma < 55 ->
                "CAPTURA MUITO ESCURA • " + w + "x" + h + " • verifique Horizonte"
            else ->
                "CAPTURA RECEBIDA • " + w + "x" + h + " • brilho=" + avg + " • úteis=" + useful + "% • cor=" + colorVar + "%"
        }
    }

    private fun triggerAlt() {
        val prefs = getSharedPreferences("pilot", MODE_PRIVATE)
        val x = prefs.getFloat("alt_x", -1f)
        val y = prefs.getFloat("alt_y", -1f)

        when {
            x < 0f || y < 0f -> {
                FishingAccessibilityService.updateStatus(
                    "ALVO DETECTADO • ALT NÃO CALIBRADO"
                )
            }
            FishingAccessibilityService.instance == null -> {
                FishingAccessibilityService.updateStatus(
                    "ALVO DETECTADO • ACESSIBILIDADE DESCONECTADA"
                )
            }
            else -> {
                FishingAccessibilityService.updateStatus(
                    "ALVO DETECTADO • solicitando ALT..."
                )
                val accepted = FishingAccessibilityService.tap(x, y)
                if (!accepted) {
                    FishingAccessibilityService.updateStatus(
                        "ALT NÃO ENVIADO • verifique Acessibilidade"
                    )
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fishing Pilot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Fishing Pilot")
            .setContentText("Detectando a barra de pesca")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopCapture() {
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onDestroy() {
        stopCapture()
        FishingAccessibilityService.updateStatus("Captura desligada")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
