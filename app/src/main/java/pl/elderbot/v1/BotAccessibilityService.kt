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
        if (overlayButton != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return

        val button = Button(this).apply {
            text = "🔎 METIN"
            textSize = 12f
            setPadding(10, 0, 10, 0)
            setOnClickListener {
                visibility = View.INVISIBLE
                mainHandler.postDelayed({
                    captureAndDetectMetin { _, _ ->
                        visibility = View.VISIBLE
                    }
                }, 200L)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 120
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager?.addView(button, params)
            overlayButton = button
        } catch (_: Throwable) {
            overlayButton = null
            windowManager = null
        }
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

                            val result = detectMetin(bitmap!!)
                            val annotated = bitmap!!.copy(Bitmap.Config.ARGB_8888, true)
                            if (result.found) {
                                val canvas = Canvas(annotated)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    strokeWidth = 6f
                                }
                                canvas.drawRect(result.bounds, paint)
                                paint.style = Paint.Style.FILL
                                paint.textSize = 32f
                                canvas.drawText("METIN?", result.bounds.left.toFloat(),
                                    (result.bounds.top - 10).coerceAtLeast(35).toFloat(), paint)
                            }
                            saveBitmap(annotated)
                            annotated.recycle()

                            lastStatus = if (result.found) {
                                "Metin znaleziony: X=${result.centerX}, Y=${result.centerY}"
                            } else {
                                "Metina nie znaleziono"
                            }
                            onDone(result.found, lastStatus)
                        } catch (e: Throwable) {
                            lastStatus = "Błąd wykrywania: ${e.javaClass.simpleName}"
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
            lastStatus = "Nie udało się uruchomić wykrywania: ${e.javaClass.simpleName}"
            onDone(false, lastStatus)
        }
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

    fun startBot() {
        running = true
        lastStatus = "Tryb pracy włączony — logika farmienia jeszcze nieaktywna"
    }

    fun stopBot() {
        running = false
        lastStatus = "Bot zatrzymany"
    }

    override fun onDestroy() {
        removeScreenshotOverlay()
        instance = null
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
