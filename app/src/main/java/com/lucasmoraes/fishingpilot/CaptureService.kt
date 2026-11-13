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
    private var previousFrame = ByteArray(0)
    private var previousFrameWidth = 0
    private var previousFrameHeight = 0
    private var framesReceived = 0L
    private var lastDiagnostic = 0L

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
                try {
                    process(r.acquireLatestImage())
                } catch (t: Throwable) {
                    FishingAccessibilityService.updateStatus("ERRO NO PROCESSAMENTO DA CAPTURA")
                    android.util.Log.e("FishingPilot", "Frame processing failed", t)
                } finally {
                    processing = false
                }
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
            framesReceived++
            if (framesReceived % 10L == 0L) {
                FishingAccessibilityService.updateStatus("CAPTURA OK • frames=$framesReceived • estado=$state")
            }

            // IMPORTANT: We do not search the whole screen for green anymore.
            // First locate the black fishing dialog. Only then do we inspect
            // the horizontal white bar inside that dialog.
            val box = findFishingBox(data, w, h, px, row)

            if (box == null) {
                if (framesReceived % 10L == 0L) {
                    FishingAccessibilityService.updateStatus("AGUARDANDO MINIGAME")
                }
                resetRoundAfterMissing()
                previousFrame = data.copyOf()
                previousFrameWidth = w
                previousFrameHeight = h
                return
            }

            if (state == State.IDLE) {
                state = State.BOX
                boxFrames = 1
            } else {
                boxFrames++
            }

            // A single dark region is never enough. Require persistence across
            // consecutive fresh frames before inspecting the panel contents.
            if (boxFrames < 3) {
                if (framesReceived % 5L == 0L) {
                    FishingAccessibilityService.updateStatus("VALIDANDO PAINEL • ${boxFrames}/3")
                }
                previousFrame = data.copyOf()
                previousFrameWidth = w
                previousFrameHeight = h
                return
            }

            // The panel alone is not a detection. The actual white track and
            // a compact green target must both be found inside the same ROI.
            val bar = findWhiteProgressBar(data, w, h, px, row, box)

            if (bar == null) {
                if (framesReceived % 10L == 0L) {
                    FishingAccessibilityService.updateStatus("PAINEL CANDIDATO • procurando trilho branco")
                }
                previousFrame = data.copyOf()
                previousFrameWidth = w
                previousFrameHeight = h
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
                if (framesReceived % 10L == 0L) {
                    FishingAccessibilityService.updateStatus("TRILHO BRANCO DETECTADO • validando faixa verde")
                }
                previousFrame = data.copyOf()
                previousFrameWidth = w
                previousFrameHeight = h
                activeFrames = 0
                missingFrames++
                if (missingFrames >= 6) resetRound()
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
                if (framesReceived % 10L == 0L) {
                    FishingAccessibilityService.updateStatus("FAIXA VERDE VALIDADA • aguardando marcador móvel")
                }
                previousFrame = data.copyOf()
                previousFrameWidth = w
                previousFrameHeight = h
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
                    val sent = triggerAlt()
                    if (sent) {
                        triggeredThisRound = true
                        lastTrigger = now
                        FishingAccessibilityService.updateStatus("ACIONAMENTO ENVIADO • aguardando confirmação do Android")
                    } else {
                        FishingAccessibilityService.updateStatus("ACIONAMENTO NÃO ENVIADO • verifique acessibilidade e calibração")
                    }
                }
            }

            lastWhiteCenter = whiteCenter

            // Keep the previous frame only after all analysis for this frame.
            previousFrame = data.copyOf()
            previousFrameWidth = w
            previousFrameHeight = h
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
        // Detect the dark fishing dialog FIRST. Green pixels anywhere else
        // on the game screen are deliberately ignored.
        val cellW = maxOf(8, w / 90)
        val cellH = maxOf(8, h / 100)
        val cols = (w + cellW - 1) / cellW
        val rows = (h + cellH - 1) / cellH
        val darkCells = BooleanArray(cols * rows)

        // Mark grid cells whose sampled pixels are predominantly dark.
        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val left = gx * cellW
                val top = gy * cellH
                val right = minOf(w, left + cellW)
                val bottom = minOf(h, top + cellH)
                var samples = 0
                var dark = 0
                var y = top
                while (y < bottom) {
                    var x = left
                    while (x < right) {
                        val o = y * row + x * px
                        if (o + 2 < data.size) {
                            val r = data[o].toInt() and 255
                            val g = data[o + 1].toInt() and 255
                            val b = data[o + 2].toInt() and 255
                            val high = maxOf(r, g, b)
                            val low = minOf(r, g, b)
                            // Include near-black and translucent charcoal.
                            if (high <= 105 && high - low <= 45) dark++
                            samples++
                        }
                        x += 2
                    }
                    y += 2
                }
                if (samples >= 4 && dark.toFloat() / samples >= 0.64f) {
                    darkCells[gy * cols + gx] = true
                }
            }
        }

        // Group adjacent dark cells into candidate dialog panels.
        val visited = BooleanArray(darkCells.size)
        var best: RectF2? = null
        var bestScore = 0.0
        for (index in darkCells.indices) {
            if (!darkCells[index] || visited[index]) continue
            val queue = ArrayDeque<Int>()
            queue.add(index)
            visited[index] = true
            var minGX = cols
            var maxGX = -1
            var minGY = rows
            var maxGY = -1
            var componentCells = 0

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val gx = current % cols
                val gy = current / cols
                componentCells++
                minGX = minOf(minGX, gx)
                maxGX = maxOf(maxGX, gx)
                minGY = minOf(minGY, gy)
                maxGY = maxOf(maxGY, gy)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = gx + dx
                        val ny = gy + dy
                        if (nx !in 0 until cols || ny !in 0 until rows) continue
                        val ni = ny * cols + nx
                        if (darkCells[ni] && !visited[ni]) {
                            visited[ni] = true
                            queue.add(ni)
                        }
                    }
                }
            }

            val left = minGX * cellW
            val top = minGY * cellH
            val right = minOf(w - 1, (maxGX + 1) * cellW)
            val bottom = minOf(h - 1, (maxGY + 1) * cellH)
            val boxW = right - left
            val boxH = bottom - top
            if (boxW <= 0 || boxH <= 0) continue
            // The fishing prompt is positioned in the lower gameplay area.
            // Reject generic dark cards, sidebars and full-screen overlays.
            if (top < h * 0.35f) continue
            val aspect = boxW.toFloat() / boxH
            val widthRatio = boxW.toFloat() / w
            val heightRatio = boxH.toFloat() / h
            val fill = componentCells.toFloat() /
                ((maxGX - minGX + 1) * (maxGY - minGY + 1))

            // A fishing panel is wide and shallow. Reject small dark objects,
            // dark scenery and full-screen dark regions.
            if (widthRatio < 0.24f || widthRatio > 0.88f) continue
            if (heightRatio < 0.045f || heightRatio > 0.22f) continue
            if (aspect < 2.6f || aspect > 11f || fill < 0.52f) continue

            val score = componentCells * fill * (1.0 + widthRatio)
            if (score > bestScore) {
                bestScore = score
                best = RectF2(
                    left = (left - cellW).coerceAtLeast(0),
                    top = (top - cellH).coerceAtLeast(0),
                    right = (right + cellW).coerceAtMost(w - 1),
                    bottom = (bottom + cellH).coerceAtMost(h - 1)
                )
            }
        }
        // Never synthesize a panel ROI from green pixels elsewhere on the
        // screen. If the dark panel signature is absent, there is no detection.
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
        // The previous version tried to infer the bar from the longest white
        // horizontal run. That can select unrelated text. We instead use the
        // green-anchor ROI and find the actual horizontal white activity
        // around the target's vertical level.
        var best: Bar? = null
        var bestScore = 0

        val centerY = (box.top + box.bottom) / 2
        val band = maxOf(10, h / 45)

        for (cy in (centerY - band * 2)..(centerY + band * 2) step 2) {
            if (cy < box.top || cy >= box.bottom) continue

            var first = -1
            var last = -1
            var count = 0

            for (x in box.left..box.right step 2) {
                val o = cy * row + x * px
                if (o + 2 >= data.size) continue

                val r = data[o].toInt() and 255
                val g = data[o + 1].toInt() and 255
                val b = data[o + 2].toInt() and 255
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)

                if (hi >= 190 && hi - lo <= 60) {
                    if (first < 0) first = x
                    last = x
                    count++
                }
            }

            if (first >= 0 && last - first >= 12 && count >= 4) {
                val score = count * 3 + (last - first)
                if (score > bestScore) {
                    bestScore = score
                    best = Bar(
                        left = first,
                        right = last,
                        top = maxOf(box.top, cy - band),
                        bottom = minOf(box.bottom, cy + band)
                    )
                }
            }
        }

        // A missing white track is a failed detection, not permission to
        // invent a bar around the center of the panel.
        return best
    }

    private fun findGreenTargetInBar(
        data: ByteArray,
        px: Int,
        row: Int,
        bar: Bar
    ): Pair<Float, Float>? {
        val width = bar.right - bar.left + 1
        val height = bar.bottom - bar.top + 1
        if (width < 24 || height < 4) return null

        // Build a per-column profile. A valid target must occupy several
        // adjacent columns and multiple rows; isolated green HUD pixels fail.
        val columnHits = IntArray(width)
        var totalGreen = 0
        for (y in bar.top..bar.bottom step 2) {
            for (x in bar.left..bar.right step 2) {
                val o = y * row + x * px
                if (o + 2 >= data.size) continue
                val r = data[o].toInt() and 255
                val g = data[o + 1].toInt() and 255
                val b = data[o + 2].toInt() and 255
                val green = g >= 105 &&
                    g - r >= 32 &&
                    g - b >= 24 &&
                    g > r * 1.28f &&
                    g > b * 1.12f
                if (green) {
                    columnHits[x - bar.left]++
                    totalGreen++
                }
            }
        }

        val minColumnHits = maxOf(2, height / 12)
        val active = BooleanArray(width) { columnHits[it] >= minColumnHits }
        var bestStart = -1
        var bestEnd = -1
        var i = 0
        while (i < width) {
            if (!active[i]) {
                i++
                continue
            }
            val runStart = i
            while (i < width && active[i]) i++
            val runEnd = i - 1
            if (bestStart < 0 || runEnd - runStart > bestEnd - bestStart) {
                bestStart = runStart
                bestEnd = runEnd
            }
        }

        if (bestStart < 0 || totalGreen < 8) return null
        val targetWidth = bestEnd - bestStart + 1
        // A target is a compact zone, not a large green background.
        if (targetWidth < 3 || targetWidth > width * 0.30f) return null

        val center = bar.left + (bestStart + bestEnd) / 2f
        val halfWidth = maxOf(targetWidth / 2f, 2f)
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
        // The important signal is not "white exists"; the static white part
        // of the loading bar is always present. We compare consecutive
        // captured frames and look for WHITE pixels that actually changed
        // position. This follows the moving marker instead of the bar.
        if (previousFrame.isEmpty() ||
            previousFrameWidth <= 0 ||
            previousFrameHeight <= 0
        ) {
            return null
        }

        val candidates = ArrayList<Pair<Int, Int>>()
        val exclusion = targetHalfWidth + 4f

        val left = bar.left.coerceAtLeast(0)
        val right = bar.right.coerceAtLeast(left)
        val top = bar.top.coerceAtLeast(0)
        val bottom = bar.bottom.coerceAtLeast(top)

        var x = left
        while (x <= right) {
            if (abs(x - targetCenter) > exclusion) {
                var changedWhite = 0
                var totalWhite = 0

                var y = top
                while (y <= bottom) {
                    val o = y * row + x * px
                    if (o + 2 < data.size && o + 2 < previousFrame.size) {
                        val r = data[o].toInt() and 255
                        val g = data[o + 1].toInt() and 255
                        val b = data[o + 2].toInt() and 255

                        val pr = previousFrame[o].toInt() and 255
                        val pg = previousFrame[o + 1].toInt() and 255
                        val pb = previousFrame[o + 2].toInt() and 255

                        val hi = maxOf(r, g, b)
                        val lo = minOf(r, g, b)
                        val white = hi >= 185 && hi - lo <= 65

                        if (white) {
                            totalWhite++
                            val delta = abs(r - pr) + abs(g - pg) + abs(b - pb)
                            if (delta >= 45) changedWhite++
                        }
                    }
                    y += 2
                }

                // A marker column should contain white pixels AND those white
                // pixels should have changed since the previous frame.
                if (changedWhite >= 2 && totalWhite >= changedWhite) {
                    candidates.add(x to changedWhite)
                }
            }
            x += 2
        }

        if (candidates.isEmpty()) return null

        // Use the strongest contiguous candidate group so isolated UI noise
        // cannot become the marker.
        var bestStart = -1
        var bestEnd = -1
        var bestScore = 0

        var groupStart = candidates[0].first
        var groupEnd = candidates[0].first
        var groupScore = candidates[0].second

        for (i in 1 until candidates.size) {
            val cx = candidates[i].first
            val score = candidates[i].second

            if (cx - groupEnd <= 6) {
                groupEnd = cx
                groupScore += score
            } else {
                if (groupScore > bestScore) {
                    bestScore = groupScore
                    bestStart = groupStart
                    bestEnd = groupEnd
                }
                groupStart = cx
                groupEnd = cx
                groupScore = score
            }
        }

        if (groupScore > bestScore) {
            bestScore = groupScore
            bestStart = groupStart
            bestEnd = groupEnd
        }

        if (bestStart < 0 || bestScore < 4) return null

        return (bestStart + bestEnd) / 2f
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
        previousFrame = ByteArray(0)
        previousFrameWidth = 0
        previousFrameHeight = 0
    }

    private fun triggerAlt(): Boolean {
        val prefs = getSharedPreferences("pilot", MODE_PRIVATE)
        val x = prefs.getFloat("alt_x", -1f)
        val y = prefs.getFloat("alt_y", -1f)

        if (x < 0f || y < 0f) return false
        return FishingAccessibilityService.tap(x, y)
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
