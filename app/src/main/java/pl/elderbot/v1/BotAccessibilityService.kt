package pl.elderbot.v1

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: BotAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var lastStatus = "Usługa gotowa"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastStatus = "Usługa dostępności aktywna"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopBot()
    }

    fun status(): String = lastStatus

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
        instance = null
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
