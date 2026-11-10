package com.lucasmoraes.fishingpilot

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.content.pm.ServiceInfo
import kotlin.math.abs

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var processing = false
    private var lastTrigger = 0L

    // Fishing UI state:
    // IDLE = nothing useful on screen
    // BOX = the black "PUSH O PEIXE / ABORT" box is visible
    // ACTIVE = the white progress bar + green target are visible
    private enum class State { IDLE, BOX, ACTIVE }
    private var state = State.IDLE

    private var boxFrames = 0
    private var activeFrames = 0
    private var missingFrames = 0
    private var triggeredThisRound = false

    private var lastTargetCenter = Float.NaN
    private var lastWhiteCenter = Float.NaN
    private var whiteDirection = 0
    private var whiteMovingFrames = 0
    private var whiteInsideFrames = 0
    private var whiteOutsideFrames = 0
    private var previousWhiteProfile = IntArray(0)

    private data class RectF2(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private data class Bar(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int
    )

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }

        val code = i?.getIntExtra("resultCode", 0) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra("data")
        }
        if (data == null) return START_NOT_STICKY

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection?.stop()
        projection = mgr.getMediaProjection(code, data)

        val m = resources.displayMetrics
        reader?.close()
        reader = ImageReader.newInstance(
            m.widthPixels,
            m.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        projection?.createVirtualDisplay(
            "FishingPilot",
            m.widthPixels,
            m.heightPixels,
            m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            null
        )

        reader?.setOnImageAvailableListener(
            { r ->
                if (processing) {
                    r.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                processing = true
                process(r.acquireLatestImage())
                processing = false
            },
            Handler(Looper.getMainLooper())
        )

        return START_STICKY
    }

    private fun process(image: Image?) {
        image ?: return

        try {
            val p = image.planes[0]
            val b = p.buffer
            val w = image.width
            val h = image.height
            val px = p.pixelStride
            val row = p.rowStride
            val data = ByteArray(b.remaining())
            b.get(data)

            // IMPORTANT: We do not search the whole screen for green anymore.
            // First locate the black fishing dialog. Only then do we inspect
            // the horizontal white bar inside that dialog.
            val box = findFishingBox(data, w, h, px, row)

            if (box == null) {
                resetRoundAfterMissing()
                return
            }

            if (state == State.IDLE) {
                state = State.BOX
                boxFrames = 1
            } else {
                boxFrames++
            }

            // The black dialog itself is NOT enough to click ALT.
            // We wait until a real white horizontal bar and its green target
            // are both detected inside the same dialog.
            val bar = findWhiteProgressBar(data, w, h, px, row, box)

            if (bar == null) {
                activeFrames = 0
                if (state == State.ACTIVE) {
                    // Keep the round latched for a few frames in case the
                    // capture skips one frame.
                    missingFrames++
                    if (missingFrames >= 6) resetRound()
                }
                return
            }

            val target = findGreenTargetInBar(data, px, row, bar)

            if (target == null) {
                activeFrames = 0
                return
            }

            // Only now is the fishing minigame considered ACTIVE.
            missingFrames = 0
            if (state != State.ACTIVE) {
                state = State.ACTIVE
                activeFrames = 1
                triggeredThisRound = false
                lastTargetCenter = Float.NaN
                lastWhiteCenter = Float.NaN
                whiteDirection = 0
                whiteMovingFrames = 0
                whiteInsideFrames = 0
                whiteOutsideFrames = 0
            } else {
                activeFrames++
            }

            val targetCenter = target.first
            val targetHalfWidth = target.second

            // Find the moving WHITE section/marker on the bar, but exclude
            // the green target itself. We use temporal movement, not simply
            // "there is something white", so the static white background of
            // the bar cannot trigger ALT.
            val whiteCenter = findMovingWhiteCenter(
                data, px, row, bar, targetCenter, targetHalfWidth
            )

            if (whiteCenter == null || activeFrames < 3) {
                whiteOutsideFrames = 0
                whiteInsideFrames = 0
                return
            }

            if (!lastTargetCenter.isNaN() && abs(targetCenter - lastTargetCenter) > 10f) {
                // New target position = new round/attempt. Never click just
                // because the green point moved.
                triggeredThisRound = false
                whiteMovingFrames = 0
                whiteInsideFrames = 0
                whiteOutsideFrames = 0
                lastWhiteCenter = Float.NaN
            }
            lastTargetCenter = targetCenter

            if (!lastWhiteCenter.isNaN()) {
                val dx = whiteCenter - lastWhiteCenter
                val newDirection = when {
                    dx > 1.2f -> 1
                    dx < -1.2f -> -1
                    else -> 0
                }

                if (newDirection != 0) {
                    if (whiteDirection == 0 || newDirection == whiteDirection) {
                        whiteMovingFrames++
                    } else {
                        // Direction changed: still movement, but do not
                        // immediately fire on a noisy reversal.
                        whiteMovingFrames = 1
                    }
                    whiteDirection = newDirection
                }
            }

            val inside = whiteCenter >= targetCenter - targetHalfWidth &&
                    whiteCenter <= targetCenter + targetHalfWidth

            if (inside) {
                whiteInsideFrames++
                whiteOutsideFrames = 0
            } else {
                whiteOutsideFrames++
                whiteInsideFrames = 0
            }

            // The marker must have genuinely moved before we allow a click.
            // Then it must enter the green target for two consecutive frames.
            if (!triggeredThisRound &&
                whiteMovingFrames >= 3 &&
                whiteInsideFrames >= 2
            ) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTrigger >= 500L) {
                    triggeredThisRound = true
                    lastTrigger = now
                    triggerAlt()
                }
            }

            lastWhiteCenter = whiteCenter
        } finally {
            image.close()
        }
    }

    private fun findFishingBox(
        data: ByteArray,
        w: Int,
        h: Int,
        px: Int,
        row: Int
    ): RectF2? {
        // The fishing dialog is black, but its exact size can vary with
        // scaling. Instead of looking for one perfect dark rectangle, find
        // the horizontal white/green fishing track first and use the dark
        // area around it as the dialog ROI.
        val step = 4
        var best: RectF2? = null
        var bestScore = 0

        for (y in (h * 0.18f).toInt() until (h * 0.85f).toInt() step step) {
            var whiteRun = 0
            var greenCount = 0
            var firstX = -1
            var lastX = -1

            for (x in (w * 0.05f).toInt() until (w * 0.95f).toInt() step step) {
                val o = y * row + x * px
                if (o + 2 >= data.size) continue

                val r = data[o].toInt() and 255
                val g = data[o + 1].toInt() and 255
                val b = data[o + 2].toInt() and 255
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)

                val white = hi >= 205 && hi - lo <= 50
                val green = g >= 105 && g > r * 1.25f && g > b * 1.15f

                if (white) {
                    if (firstX < 0) firstX = x
                    lastX = x
                    whiteRun++
                } else if (!green) {
                    whiteRun = 0
                }

                if (green) greenCount++
            }

            // A real fishing track has a substantial horizontal white
            // component and a green target in the same neighborhood.
            if (whiteRun >= 12 && greenCount >= 1 && firstX >= 0) {
                val left = (firstX - w * 0.10f).toInt().coerceAtLeast(0)
                val right = (lastX + w * 0.10f).toInt().coerceAtMost(w - 1)
                val top = (y - h * 0.12f).toInt().coerceAtLeast(0)
                val bottom = (y + h * 0.18f).toInt().coerceAtMost(h - 1)
                val score = whiteRun * 3 + greenCount * 10

                if (score > bestScore) {
                    bestScore = score
                    best = RectF2(left, top, right, bottom)
                }
            }
        }

        return best
    }

    private fun findWhiteProgressBar(
        data: ByteArray,
        w: Int,
        h: Int,
        px: Int,
        row: Int,
        box: RectF2
    ): Bar? {
        // The moving indicator is white on a dark dialog. We therefore do
        // NOT require the entire bar to be white. Find a horizontal band
        // containing enough white pixels to represent the fishing track.
        var best: Bar? = null
        var bestScore = 0

        val minWidth = maxOf((box.width * 0.08f).toInt(), 18)

        for (y in box.top until box.bottom step 2) {
            var first = -1
            var last = -1
            var count = 0

            for (x in box.left until box.right step 2) {
                val o = y * row + x * px
                if (o + 2 >= data.size) continue

                val r = data[o].toInt() and 255
                val g = data[o + 1].toInt() and 255
                val b = data[o + 2].toInt() and 255
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)

                if (hi >= 205 && hi - lo <= 50) {
                    if (first < 0) first = x
                    last = x
                    count++
                }
            }

            if (first >= 0 && last - first >= minWidth && count >= 4) {
                val score = count + (last - first) / 2
                if (score > bestScore) {
                    bestScore = score
                    best = Bar(
                        left = first,
                        right = last,
                        top = maxOf(box.top, y - 6),
                        bottom = minOf(box.bottom, y + 6)
                    )
                }
            }
        }

        return best
    }

    private fun findGreenTargetInBar(
        data: ByteArray,
        px: Int,
        row: Int,
        bar: Bar
    ): Pair<Float, Float>? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var count = 0

        for (y in bar.top..bar.bottom step 2) {
            for (x in bar.left..bar.right step 2) {
                val o = y * row + x * px
                if (o + 2 >= data.size) continue

                val r = data[o].toInt() and 255
                val g = data[o + 1].toInt() and 255
                val b = data[o + 2].toInt() and 255

                // Small green target: strong G dominance, but keep this
                // confined to the actual white bar.
                if (g >= 105 && g > r * 1.25f && g > b * 1.15f) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    count++
                }
            }
        }

        if (count < 3 || minX == Int.MAX_VALUE) return null

        val width = maxX - minX
        if (width < 2 || width > bar.right - bar.left) return null

        val center = (minX + maxX) / 2f
        val halfWidth = maxOf(width / 2f, 4f)
        return center to halfWidth
    }

    private fun findMovingWhiteCenter(
        data: ByteArray,
        px: Int,
        row: Int,
        bar: Bar,
        targetCenter: Float,
        targetHalfWidth: Float
    ): Float? {
        val width = ((bar.right - bar.left) / 2) + 1
        if (width <= 0) return null
        if (previousWhiteProfile.size != width) {
            previousWhiteProfile = IntArray(width)
        }

        var bestX = -1
        var bestScore = 0
        val current = IntArray(width)
        val exclusion = targetHalfWidth + 7f

        for (i in 0 until width) {
            val x = bar.left + i * 2
            if (x > bar.right || abs(x - targetCenter) <= exclusion) continue

            var bright = 0
            var dark = 0
            for (y in bar.top..bar.bottom) {
                val o = y * row + x * px
                if (o + 2 >= data.size) continue
                val r = data[o].toInt() and 255
                val g = data[o + 1].toInt() and 255
                val b = data[o + 2].toInt() and 255
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)
                if (hi >= 220 && hi - lo <= 45) bright++
                if (hi <= 100) dark++
            }

            current[i] = bright
            val delta = abs(bright - previousWhiteProfile[i])
            val score = bright * 2 + dark + delta * 5
            if (score > bestScore) {
                bestScore = score
                bestX = x
            }
        }

        previousWhiteProfile = current
        return if (bestX >= 0 && bestScore >= 10) bestX.toFloat() else null
    }

    private fun isDark(
        data: ByteArray,
        x: Int,
        y: Int,
        px: Int,
        row: Int
    ): Boolean {
        val o = y * row + x * px
        if (o + 2 >= data.size) return false
        val r = data[o].toInt() and 255
        val g = data[o + 1].toInt() and 255
        val b = data[o + 2].toInt() and 255
        return r < 55 && g < 55 && b < 55
    }

    private fun isWhite(
        data: ByteArray,
        x: Int,
        y: Int,
        px: Int,
        row: Int
    ): Boolean {
        val o = y * row + x * px
        if (o + 2 >= data.size) return false
        val r = data[o].toInt() and 255
        val g = data[o + 1].toInt() and 255
        val b = data[o + 2].toInt() and 255
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        return hi >= 205 && hi - lo <= 45
    }

    private fun resetRoundAfterMissing() {
        missingFrames++
        if (missingFrames >= 8) {
            resetRound()
        }
    }

    private fun resetRound() {
        state = State.IDLE
        boxFrames = 0
        activeFrames = 0
        missingFrames = 0
        triggeredThisRound = false
        lastTargetCenter = Float.NaN
        lastWhiteCenter = Float.NaN
        whiteDirection = 0
        whiteMovingFrames = 0
        whiteInsideFrames = 0
        whiteOutsideFrames = 0
        previousWhiteProfile = IntArray(0)
    }

    private fun triggerAlt() {
        val prefs = getSharedPreferences("pilot", MODE_PRIVATE)
        val x = prefs.getFloat("alt_x", -1f)
        val y = prefs.getFloat("alt_y", -1f)

        if (x >= 0f && y >= 0f) {
            FishingAccessibilityService.tap(x, y)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    "capture",
                    "Fishing Pilot",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "capture")
                .setContentTitle("Fishing Pilot")
                .setContentText("Monitorando pesca e acionando ALT")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Fishing Pilot")
                .setContentText("Monitorando pesca e acionando ALT")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        }

    override fun onDestroy() {
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
