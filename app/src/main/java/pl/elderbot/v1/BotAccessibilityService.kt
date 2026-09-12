package pl.elderbot.v1

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import android.view.accessibility.AccessibilityEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.widget.Button
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: BotAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayButton: Button? = null
    private var windowManager: WindowManager? = null
    @Volatile private var running = false
    @Volatile private var lastStatus = "Usługa gotowa"
    @Volatile private var lastDetectedX = 0
    @Volatile private var lastDetectedY = 0
    private var missCount = 0
    private var searchStep = 0
    private var lastMoveX = 0f
    private var lastMoveY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastStatus = "Usługa dostępności aktywna"
        showScreenshotOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopBot()
    }

    fun status(): String = lastStatus

    /**
     * Adds a small accessibility overlay button so a screenshot can be taken while the game is in front.
     * The overlay is hidden briefly before capture so it does not appear in the saved game screenshot.
     */
    private fun showScreenshotOverlay() {
        if (overlayButton != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = Button(this).apply {
            text = "▶"
            textSize = 18f
            alpha = 0.72f
            setPadding(0, 0, 0, 0)
        }
        val params = WindowManager.LayoutParams(
            105, 105,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 210 }
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX=params.x; startY=params.y; touchX=event.rawX; touchY=event.rawY; moved=false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx=(touchX-event.rawX).toInt(); val dy=(event.rawY-touchY).toInt()
                    if (kotlin.math.abs(dx)>8 || kotlin.math.abs(dy)>8) moved=true
                    params.x=(startX+dx).coerceAtLeast(0); params.y=(startY+dy).coerceAtLeast(0)
                    try { windowManager?.updateViewLayout(button, params) } catch (_: Throwable) {}
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) { if (running) stopBot() else startBot() }
                    true
                }
                else -> false
            }
        }
        try { windowManager?.addView(button, params); overlayButton=button } catch (_: Throwable) { overlayButton=null }
    }

    private fun removeScreenshotOverlay() {
        val button = overlayButton ?: return
        try {
            windowManager?.removeView(button)
        } catch (_: Throwable) {
        }
        overlayButton = null
        windowManager = null
    }

    /**
     * Captures the current screen using the Android Accessibility screenshot API.
     * This is the foundation for visual detection; no game files are modified.
     */
    fun captureAndSaveScreenshot(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lastStatus = "Zrzut ekranu wymaga Androida 11+"
            onDone(false, lastStatus)
            return
        }

        val displayId = android.view.Display.DEFAULT_DISPLAY
        try {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            val buffer: HardwareBuffer = screenshot.hardwareBuffer
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: throw IllegalStateException("Bitmap jest pusty")
                                bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()
                            } finally {
                                buffer.close()
                            }

                            val saved = bitmap?.let { saveBitmap(it) } == true
                            lastStatus = if (saved) {
                                "Zrzut zapisany: Pictures/ElderBot"
                            } else {
                                "Nie udało się zapisać zrzutu"
                            }
                            onDone(saved, lastStatus)
                        } catch (e: Throwable) {
                            lastStatus = "Błąd obsługi zrzutu: ${e.javaClass.simpleName}"
                            onDone(false, lastStatus)
                        } finally {
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastStatus = "Zrzut ekranu nieudany (kod $errorCode)"
                        onDone(false, lastStatus)
                    }
                }
            )
        } catch (e: Throwable) {
            lastStatus = "Nie udało się uruchomić zrzutu: ${e.javaClass.simpleName}"
            onDone(false, lastStatus)
        }
    }

    /**
     * Captures the game screen and performs a conservative visual Metin candidate scan.
     * It looks for a yellow/amber glow in the gameplay area and a nearby red name label.
     * This is only visual screen analysis; it does not inspect or modify game files.
     */
    fun captureAndDetectMetin(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lastStatus = "Wykrywanie wymaga Androida 11+"
            onDone(false, lastStatus)
            return
        }

        val displayId = android.view.Display.DEFAULT_DISPLAY
        try {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            val buffer: HardwareBuffer = screenshot.hardwareBuffer
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: throw IllegalStateException("Bitmap jest pusty")
                                bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()
                            } finally {
                                buffer.close()
                            }

                            val captured = bitmap ?: throw IllegalStateException("Brak obrazu")
                            detectMetinWithOcr(captured) { result ->
                            if (result.found) updateDetectedPosition(result.centerX, result.centerY)
                                try {
                                    val annotated = captured.copy(Bitmap.Config.ARGB_8888, true)
                                    if (result.found) {
                                        val canvas = Canvas(annotated)
                                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            strokeWidth = 6f
                                        }
                                        canvas.drawRect(result.bounds, paint)
                                        paint.style = Paint.Style.FILL
                                        paint.textSize = 30f
                                        canvas.drawText(
                                            "METIN OCR",
                                            result.bounds.left.toFloat(),
                                            (result.bounds.top - 10).coerceAtLeast(35).toFloat(),
                                            paint
                                        )
                                    }
                                    if (!running) saveBitmap(annotated)
                                    annotated.recycle()

                                    if (result.found) { lastDetectedX = result.centerX; lastDetectedY = result.centerY }
                                    lastStatus = if (result.found) {
                                        "Metin znaleziony: X=${result.centerX}, Y=${result.centerY}"
                                    } else {
                                        "Metina nie znaleziono"
                                    }
                                    onDone(result.found, lastStatus)
                                } catch (e: Throwable) {
                                    lastStatus = "Błąd oznaczania wyniku: ${e.javaClass.simpleName}"
                                    onDone(false, lastStatus)
                                } finally {
                                    captured.recycle()
                                }
                            }
                        } catch (e: Throwable) {
                            bitmap?.recycle()
                            lastStatus = "Błąd obsługi zrzutu: ${e.javaClass.simpleName}"
                            onDone(false, lastStatus)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastStatus = "Zrzut ekranu nieudany (kod $errorCode)"
                        onDone(false, lastStatus)
                    }
                }
            )
        } catch (e: Throwable) {
            lastStatus = "Nie udało się uruchomić wykrywania: ${e.javaClass.simpleName}"
            onDone(false, lastStatus)
        }
    }

    private fun detectMetinWithOcr(bitmap: Bitmap, onResult: (MetinDetection) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                val w = bitmap.width
                val h = bitmap.height
                val gameplay = Rect(
                    (w * 0.10f).toInt(),
                    (h * 0.08f).toInt(),
                    (w * 0.90f).toInt(),
                    (h * 0.82f).toInt()
                )

                var bestBox: Rect? = null
                var bestScore = Int.MIN_VALUE

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val raw = line.text.trim()
                        val normalized = raw.lowercase(Locale.getDefault())
                            .replace("ę", "e").replace("ą", "a")
                            .replace("ł", "l").replace("ś", "s")
                            .replace("ć", "c").replace("ń", "n")
                            .replace("ó", "o").replace("ź", "z").replace("ż", "z")
                        val box = line.boundingBox ?: continue
                        if (!normalized.contains("metin") || !box.intersect(gameplay)) continue

                        val cx = (box.left + box.right) / 2
                        val redScore = countRedPixelsNear(bitmap, box)
                        val labelWidth = box.width().coerceAtLeast(1)
                        val labelHeight = box.height().coerceAtLeast(1)
                        val sizeScore = 100 - kotlin.math.abs(labelWidth - 90) - kotlin.math.abs(labelHeight - 18) * 2
                        val score = redScore * 4 + sizeScore

                        if (score > bestScore) {
                            bestScore = score
                            bestBox = Rect(box)
                        }
                    }
                }

                val label = bestBox
                if (label == null) {
                    recognizer.close()
                    onResult(MetinDetection(false, Rect(), 0, 0))
                    return@addOnSuccessListener
                }

                // The label is above the stone. Use a generous, colour-independent target area
                // below the text so different Metin auras do not affect detection.
                val left = (label.centerX() - 65).coerceAtLeast(gameplay.left)
                val right = (label.centerX() + 65).coerceAtMost(gameplay.right)
                val top = (label.bottom + 5).coerceAtMost(gameplay.bottom - 20)
                val bottom = (label.bottom + 125).coerceAtMost(gameplay.bottom)
                val target = Rect(left, top, right, bottom)

                recognizer.close()
                onResult(MetinDetection(true, target, target.centerX(), target.centerY()))
            }
            .addOnFailureListener {
                recognizer.close()
                onResult(MetinDetection(false, Rect(), 0, 0))
            }
    }

    private fun countRedPixelsNear(bitmap: Bitmap, box: Rect): Int {
        val left = (box.left - 20).coerceAtLeast(0)
        val right = (box.right + 20).coerceAtMost(bitmap.width)
        val top = (box.top - 8).coerceAtLeast(0)
        val bottom = (box.bottom + 8).coerceAtMost(bitmap.height)
        var count = 0
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                if (r >= 90 && r > g * 1.25f && r > b * 1.20f) count++
            }
        }
        return count
    }

    private data class MetinDetection(
        val found: Boolean,
        val bounds: Rect,
        val centerX: Int,
        val centerY: Int
    )

    private fun detectMetin(bitmap: Bitmap): MetinDetection {
        val w = bitmap.width
        val h = bitmap.height

        // HUD/minimap/chat are deliberately excluded. We analyse the gameplay field only.
        val left = (w * 0.18f).toInt()
        val right = (w * 0.82f).toInt()
        val top = (h * 0.12f).toInt()
        val bottom = (h * 0.88f).toInt()
        val step = 3
        val cols = ((right - left) + step - 1) / step
        val rows = ((bottom - top) + step - 1) / step
        val visited = BooleanArray(cols * rows)
        val hsv = FloatArray(3)
        val blobs = mutableListOf<Rect>()

        fun isYellowGlow(x: Int, y: Int): Boolean {
            android.graphics.Color.RGBToHSV(
                android.graphics.Color.red(bitmap.getPixel(x, y)),
                android.graphics.Color.green(bitmap.getPixel(x, y)),
                android.graphics.Color.blue(bitmap.getPixel(x, y)),
                hsv
            )
            val hue = hsv[0]
            val sat = hsv[1]
            val value = hsv[2]
            return hue in 12f..48f && sat >= 0.32f && value >= 0.30f
        }

        fun gridIndex(gx: Int, gy: Int) = gy * cols + gx

        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val gi = gridIndex(gx, gy)
                if (visited[gi]) continue
                val px = (left + gx * step).coerceAtMost(right - 1)
                val py = (top + gy * step).coerceAtMost(bottom - 1)
                if (!isYellowGlow(px, py)) continue

                val queue = ArrayDeque<Int>()
                queue.add(gi)
                visited[gi] = true
                var minGX = gx; var maxGX = gx
                var minGY = gy; var maxGY = gy
                var count = 0

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cx = cur % cols
                    val cy = cur / cols
                    count++
                    minGX = minOf(minGX, cx); maxGX = maxOf(maxGX, cx)
                    minGY = minOf(minGY, cy); maxGY = maxOf(maxGY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx; val ny = cy + dy
                            if (nx !in 0 until cols || ny !in 0 until rows) continue
                            val ni = gridIndex(nx, ny)
                            if (visited[ni]) continue
                            val qx = (left + nx * step).coerceAtMost(right - 1)
                            val qy = (top + ny * step).coerceAtMost(bottom - 1)
                            if (isYellowGlow(qx, qy)) {
                                visited[ni] = true
                                queue.add(ni)
                            }
                        }
                    }
                }

                val bw = (maxGX - minGX + 1) * step
                val bh = (maxGY - minGY + 1) * step
                if (count >= 8 && bw in 18..240 && bh in 18..240) {
                    blobs.add(
                        Rect(
                            left + minGX * step - 18,
                            top + minGY * step - 18,
                            (left + (maxGX + 1) * step + 18).coerceAtMost(right),
                            (top + (maxGY + 1) * step + 18).coerceAtMost(bottom)
                        )
                    )
                }
            }
        }

        // A Metin in the supplied screenshots has a red name above the stone.
        // Give candidates with nearby red text a strong preference.
        var best: Rect? = null
        var bestScore = Int.MIN_VALUE
        for (rect in blobs) {
            val cx = (rect.left + rect.right) / 2
            val redBoxLeft = (cx - 130).coerceAtLeast(left)
            val redBoxRight = (cx + 130).coerceAtMost(right - 1)
            val redBoxTop = (rect.top - 90).coerceAtLeast(top)
            val redBoxBottom = (rect.top + 20).coerceAtMost(bottom - 1)
            var redPixels = 0
            for (y in redBoxTop until redBoxBottom step 3) {
                for (x in redBoxLeft until redBoxRight step 3) {
                    val c = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(c)
                    val g = android.graphics.Color.green(c)
                    val b = android.graphics.Color.blue(c)
                    if (r >= 105 && r > g * 1.40f && r > b * 1.30f && g < 115) redPixels++
                }
            }
            val area = rect.width() * rect.height()
            val sizePenalty = kotlin.math.abs(rect.width() - 100) + kotlin.math.abs(rect.height() - 100)
            val score = redPixels * 20 + (10000 - sizePenalty).coerceAtLeast(0) / 20 - area / 1000
            if (score > bestScore) {
                bestScore = score
                best = rect
            }
        }

        val chosen = best ?: return MetinDetection(false, Rect(), 0, 0)
        val found = bestScore >= 80
        return if (found) {
            MetinDetection(true, chosen, (chosen.left + chosen.right) / 2, (chosen.top + chosen.bottom) / 2)
        } else {
            MetinDetection(false, Rect(), 0, 0)
        }
    }

    private fun saveBitmap(bitmap: Bitmap): Boolean {
        return try {
            val name = "elderbot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ElderBot")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            try {
                val stream = contentResolver.openOutputStream(uri) ?: return false
                stream.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException("Nie udało się skompresować obrazu")
                    }
                }
                true
            } catch (e: Throwable) {
                contentResolver.delete(uri, null, null)
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Safe gesture test: a single short tap at the current screen centre. */
    fun tapScreenCentre() {
        if (running) return
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels / 2f
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        lastStatus = "Wysłano testowy dotyk: środek ekranu"
    }

    

    private fun updateDetectedPosition(x: Int, y: Int) {
        lastDetectedX = x
        lastDetectedY = y
    }

    private fun setOverlaySymbol(symbol: String) {
        mainHandler.post {
            try { overlayButton?.text = symbol } catch (_: Throwable) {}
        }
    }

    private fun moveJoystick(dx: Float, dy: Float, duration: Long = 360L) {
        if (!running) return
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val startX = w * 0.13f
        val startY = h * 0.77f
        val radiusX = w * 0.055f
        val radiusY = h * 0.055f
        val endX = startX + radiusX * dx.coerceIn(-1f, 1f)
        val endY = startY + radiusY * dy.coerceIn(-1f, 1f)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun tapAttackButton() {
        if (!running) return
        val m = resources.displayMetrics
        val path = Path().apply {
            moveTo(m.widthPixels * 0.89f, m.heightPixels * 0.76f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 70L)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun farmTick() {
        if (!running) return

        captureAndDetectMetin { found, _ ->
            if (!running) return@captureAndDetectMetin

            val m = resources.displayMetrics
            val w = m.widthPixels.toFloat()
            val h = m.heightPixels.toFloat()

            if (!found) {
                missCount++
                lastStatus = "Szukam Metina..."

                // Po dwóch pustych skanach wykonaj krótki, łagodny ruch poszukiwawczy.
                if (missCount >= 2) {
                    val pattern = arrayOf(
                        0.00f to -0.50f,
                        0.30f to -0.42f,
                        0.00f to -0.50f,
                        -0.30f to -0.42f
                    )
                    val p = pattern[searchStep % pattern.size]
                    searchStep++
                    lastMoveX = p.first
                    lastMoveY = p.second
                    moveJoystick(p.first, p.second, 290L)
                }

                mainHandler.postDelayed({ farmTick() }, 650L)
                return@captureAndDetectMetin
            }

            missCount = 0
            val dx = lastDetectedX - w * 0.50f
            val dy = lastDetectedY - h * 0.48f

            val closeEnough =
                kotlin.math.abs(dx) < w * 0.10f &&
                kotlin.math.abs(dy) < h * 0.17f

            if (closeEnough) {
                lastMoveX = 0f
                lastMoveY = 0f
                lastStatus = "Metin blisko — ATAK"
                tapAttackButton()
                mainHandler.postDelayed({ farmTick() }, 480L)
                return@captureAndDetectMetin
            }

            val targetX = (dx / (w * 0.34f)).coerceIn(-0.90f, 0.90f)
            val targetY = (dy / (h * 0.34f)).coerceIn(-0.90f, 0.90f)

            // Wygładzanie zapobiega gwałtownemu przeskakiwaniu kierunku.
            val moveX = (lastMoveX * 0.35f + targetX * 0.65f).coerceIn(-0.90f, 0.90f)
            val moveY = (lastMoveY * 0.35f + targetY * 0.65f).coerceIn(-0.90f, 0.90f)
            lastMoveX = moveX
            lastMoveY = moveY

            moveJoystick(moveX, moveY, 360L)
            lastStatus = "Podejście do Metina: X=${dx.toInt()} Y=${dy.toInt()}"
            mainHandler.postDelayed({ farmTick() }, 430L)
        }
    }

    fun startBot() {
        if (running) return
        missCount = 0
        searchStep = 0
        lastMoveX = 0f
        lastMoveY = 0f
        running = true
        lastStatus = "ElderBot: SZUKAM METINA..."
        setOverlaySymbol("■")
        farmTick()
    }

    fun stopBot() {
        running = false
        missCount = 0
        lastMoveX = 0f
        lastMoveY = 0f
        mainHandler.removeCallbacksAndMessages(null)
        lastStatus = "Bot zatrzymany"
        setOverlaySymbol("▶")
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        removeScreenshotOverlay()
        instance = null
        super.onDestroy()
    }
}
