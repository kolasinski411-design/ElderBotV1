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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.ScrollView
import android.widget.SeekBar
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private var overlayPanel: LinearLayout? = null
    private var overlayStatus: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var panelVisible = false
    private var windowManager: WindowManager? = null
    @Volatile private var running = false
    @Volatile private var lastStatus = "Usługa gotowa"
    @Volatile private var lastDetectedX = 0
    @Volatile private var lastDetectedY = 0
    @Volatile private var lastTargetKind = "METIN"
    private var missCount = 0
    private var searchStep = 0
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    @Volatile private var desiredMoveX = 0f
    @Volatile private var desiredMoveY = 0f
    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null
    private var joystickEndX = 0f
    private var joystickEndY = 0f
    private var attackMode = false
    private var attackMisses = 0
    private var attackConfirmed = false
    private var attackSelectAttempts = 0
    private var lastTargetTapAt = 0L
    private var progressAnchorX = 0
    private var progressAnchorY = 0
    private var progressAnchorAt = 0L
    private var lowMotionFrames = 0
    private var lastSceneMotion = 100f
    private var previousSceneSample: IntArray? = null
    private var avoidPhase = 0
    private var avoidPhaseTicks = 0
    private var avoidDirection = 1f
    private var avoidAttempts = 0

    // Route-style navigation inspired by waypoint farming: when there is no reachable
    // target, keep a stable heading for several seconds instead of twitching every OCR scan.
    private var patrolStep = 0
    private var patrolStepUntil = 0L
    private var ignoredTargetX = 0
    private var ignoredTargetY = 0
    private var ignoreTargetUntil = 0L
    private var steeringUpdatedAt = 0L
    private var bestApproachError = Float.MAX_VALUE
    private var lastMeaningfulProgressAt = 0L
    private val skillLastTapAt = LongArray(3)
    private var skillCalibrationOverlay: View? = null
    private var lastHpPotionAt = 0L
    private var lastMpPotionAt = 0L
    private var lastReviveScanAt = 0L
    private var pickupPass = 0

    // EXP target lock. Ordinary mobs can share names, so the lock uses both the OCR name
    // and the last screen position. This keeps one mob selected across consecutive frames
    // instead of jumping between red labels while the character is moving.
    private var expLockActive = false
    private var expLockName = ""
    private var expLockX = 0
    private var expLockY = 0
    private var expLockSeenAt = 0L
    private var expLockMisses = 0
    private var expRangeConfirmFrames = 0
    private var lastDetectedLabelBottom = 0
    private var lastDetectedLabelHeight = 0
    private var lastDetectedName = ""

    // V0.20 core state machine. Target acquisition, navigation, combat and pickup are
    // separate phases so utility modules cannot accidentally hijack the main farming loop.
    private enum class CoreState { STOPPED, SEARCH_ROUTE, TARGET_APPROACH, COMBAT, PICKUP, RECOVER }
    @Volatile private var coreState = CoreState.STOPPED
    private var coreTargetKind = ""
    private var coreTargetName = ""
    private var coreTargetX = 0
    private var coreTargetY = 0
    private var coreTargetSeenAt = 0L
    private var coreTargetMisses = 0
    private var coreTargetLockedAt = 0L
    private var routeTargetObservedAt = 0L
    private var routeBestTargetError = Float.MAX_VALUE
    private var pickupAbsentFrames = 0
    @Volatile private var selectedTargetBarVisible = false

    // Multi-map navigation state. We keep map-specific steering memory outside the game client.
    @Volatile private var currentMapId = "unknown"
    @Volatile private var currentMapLabel = "Nieznana mapa"
    private var lastMapSeenAt = 0L
    private var currentSceneFingerprint = "scene0"
    private var lastSafeHeadingBucket = -1

    private fun prefs() = getSharedPreferences("elderbot_settings", MODE_PRIVATE)
    private fun enabled(key: String, defaultValue: Boolean = true): Boolean =
        prefs().getBoolean(key, defaultValue)

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
    private fun overlayBg(color: Int, stroke: Int = Color.rgb(70, 82, 110)): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f
            setStroke(2, stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Compact in-game control inspired by desktop bot panels.  It is an
     * accessibility overlay, so it remains available while ElderMT2 is open.
     * It does not modify or inject into the game client.
     */
    private fun showScreenshotOverlay() {
        if (overlayButton != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val button = Button(this).apply {
            text = "EB"
            textSize = 14f
            setTextColor(Color.WHITE)
            alpha = 0.90f
            background = overlayBg(Color.rgb(24, 28, 38), Color.rgb(90, 125, 220))
            setPadding(0, 0, 0, 0)
        }
        val params = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(150)
        }
        overlayParams = params

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = overlayBg(Color.argb(242, 18, 21, 28), Color.rgb(72, 88, 130))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "ELDERBOT MOBILE"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minimize = Button(this).apply {
            text = "—"
            textSize = 16f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { setPanelVisible(false) }
        }
        header.addView(title)
        header.addView(minimize, LinearLayout.LayoutParams(dp(46), dp(38)))
        panel.addView(header)

        val stat = TextView(this).apply {
            text = lastStatus
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(5), dp(2), dp(5), dp(8))
        }
        overlayStatus = stat
        panel.addView(stat)

        // START/STOP stays fixed at the top so the bot can always be controlled.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "START"
            textSize = 12f
            setOnClickListener { startBot(); refreshOverlay() }
        }
        val stop = Button(this).apply {
            text = "STOP"
            textSize = 12f
            setOnClickListener { stopBot(); refreshOverlay() }
        }
        controls.addView(start, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(stop, LinearLayout.LayoutParams(0, dp(44), 1f))
        panel.addView(controls)

        // Everything below the main controls is scrollable. This keeps future modules
        // accessible without ever pushing START/STOP outside the screen.
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(scrollContent, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        fun addToggle(label: String, key: String, defaultValue: Boolean = true) {
            val sw = Switch(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                isChecked = enabled(key, defaultValue)
                setPadding(dp(4), 0, dp(4), 0)
                setOnCheckedChangeListener { _, checked ->
                    prefs().edit().putBoolean(key, checked).apply()
                }
            }
            scrollContent.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        }

        addToggle("Farmbot • Metiny", "farmbot")
        addToggle("Bossy / Minibossy M1 + M2", "auto_boss", true)
        addToggle("Auto Łowy • Wszystkie moby", "auto_exp", false)

        val focusLabel = TextView(this).apply {
            val pct = prefs().getInt("auto_hunt_focus", 70)
            text = "Zasięg Auto Łowów: ${pct}%"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(5), dp(2), dp(5), 0)
        }
        scrollContent.addView(focusLabel)
        val focusSeek = SeekBar(this).apply {
            min = 30
            max = 95
            progress = prefs().getInt("auto_hunt_focus", 70).coerceIn(30, 95)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs().edit().putInt("auto_hunt_focus", value).apply()
                        focusLabel.text = "Zasięg Auto Łowów: ${value}%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        scrollContent.addView(focusSeek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        addToggle("Pickup", "pickup")
        addToggle("Auto Skills", "auto_skills")
        addToggle("Auto Potions", "auto_potions")
        addToggle("Auto Revive", "auto_revive")

        val calibrateSkills = Button(this).apply {
            text = "KALIBRUJ 3 SKILLE"
            textSize = 11f
            setOnClickListener { setPanelVisible(false); startSkillCalibration() }
        }
        scrollContent.addView(calibrateSkills, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        val targetInfo = TextView(this).apply {
            text = "PRIORYTET: BOSS > METIN > AUTO ŁOWY   •   najbliższy cel pierwszy"
            textSize = 11f
            setTextColor(Color.rgb(150, 178, 255))
            setPadding(dp(5), dp(6), dp(5), dp(6))
        }
        scrollContent.addView(targetInfo)

        val future = TextView(this).apply {
            text = "⛏ Mining   •   🎣 Fishing  [kolejny etap]"
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        scrollContent.addView(future)

        val panelParams = WindowManager.LayoutParams(
            dp(292), dp(360),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(12)
        }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; moved = false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (touchX - event.rawX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    panelParams.x = params.x; panelParams.y = params.y
                    try { windowManager?.updateViewLayout(button, params) } catch (_: Throwable) {}
                    try { windowManager?.updateViewLayout(panel, panelParams) } catch (_: Throwable) {}
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) setPanelVisible(!panelVisible)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(button, params)
            windowManager?.addView(panel, panelParams)
            overlayButton = button
            overlayPanel = panel
            refreshOverlay()
        } catch (_: Throwable) {
            try { windowManager?.removeView(button) } catch (_: Throwable) {}
            try { windowManager?.removeView(panel) } catch (_: Throwable) {}
            overlayButton = null
            overlayPanel = null
        }
    }

    private fun setPanelVisible(show: Boolean) {
        panelVisible = show
        overlayPanel?.visibility = if (show) View.VISIBLE else View.GONE
        overlayButton?.visibility = if (show) View.GONE else View.VISIBLE
        refreshOverlay()
    }

    fun refreshOverlay() {
        mainHandler.post {
            overlayStatus?.text = lastStatus
            overlayButton?.text = when {
                running && attackMode -> "⚔"
                running -> "■"
                else -> "EB"
            }
        }
    }

    private fun removeScreenshotOverlay() {
        overlayButton?.let { try { windowManager?.removeView(it) } catch (_: Throwable) {} }
        overlayPanel?.let { try { windowManager?.removeView(it) } catch (_: Throwable) {} }
        overlayButton = null
        overlayPanel = null
        overlayStatus = null
        overlayParams = null
        panelVisible = false
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
                            selectedTargetBarVisible = selectedTargetBarLooksVisible(captured)
                            if (running) {
                                lastSceneMotion = updateSceneMotion(captured)
                                checkAutoPotions(captured)
                                checkAutoSkills(captured)
                            }
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

    private fun clearExpLock() {
        expLockActive = false
        expLockName = ""
        expLockX = 0
        expLockY = 0
        expLockSeenAt = 0L
        expLockMisses = 0
        expRangeConfirmFrames = 0
    }

    private data class OcrTargetCandidate(
        val name: String,
        val kind: String,
        val label: Rect,
        val targetX: Int,
        val targetY: Int,
        val redScore: Int,
        val playerDistance: Float
    )

    private fun targetPriority(kind: String): Int = when (kind) {
        "BOSS" -> 3
        "METIN" -> 2
        "EXP" -> 1
        else -> 0
    }

    private fun clearCoreTarget() {
        coreTargetKind = ""
        coreTargetName = ""
        coreTargetX = 0
        coreTargetY = 0
        coreTargetSeenAt = 0L
        coreTargetMisses = 0
        coreTargetLockedAt = 0L
        routeTargetObservedAt = 0L
        routeBestTargetError = Float.MAX_VALUE
    }

    private fun lockCoreTarget(candidate: OcrTargetCandidate, now: Long = System.currentTimeMillis()) {
        val changed = coreTargetKind != candidate.kind || coreTargetName != candidate.name
        coreTargetKind = candidate.kind
        coreTargetName = candidate.name
        coreTargetX = candidate.targetX
        coreTargetY = candidate.targetY
        coreTargetSeenAt = now
        coreTargetMisses = 0
        if (changed || coreTargetLockedAt == 0L) {
            coreTargetLockedAt = now
            routeTargetObservedAt = now
            routeBestTargetError = Float.MAX_VALUE
        }
        if (candidate.kind == "EXP") {
            expLockActive = true
            expLockName = candidate.name
            expLockX = candidate.targetX
            expLockY = candidate.targetY
            expLockSeenAt = now
            expLockMisses = 0
        } else {
            clearExpLock()
        }
    }

    private fun sameLockedTarget(candidate: OcrTargetCandidate): Boolean {
        if (coreTargetKind.isBlank() || candidate.kind != coreTargetKind) return false
        val dx = candidate.targetX - coreTargetX
        val dy = candidate.targetY - coreTargetY
        val maxDistance = if (candidate.kind == "EXP") 300 else 330
        val nearPreviousPosition = dx * dx + dy * dy < maxDistance * maxDistance
        if (!nearPreviousPosition) return false
        // Auto Łowy follows the nearest red world label spatially. Requiring an exact OCR name
        // causes target loss when several mobs overlap or ML Kit reads the same label differently.
        return candidate.kind == "EXP" || coreTargetName.isBlank() || candidate.name == coreTargetName
    }

    private fun isPlausibleExpWorldLabel(raw: String, normalized: String, box: Rect, w: Int, h: Int): Boolean {
        if (normalized.length < 3) return false
        if (!normalized.any { it.isLetter() }) return false
        if (raw.contains("@") || raw.contains("|") || raw.contains("[") || raw.contains("]")) return false
        if (raw.count { it.isDigit() } > raw.length / 2) return false
        if (box.centerY() < h * 0.12f || box.centerY() > h * 0.62f) return false
        if (box.centerX() < w * 0.11f || box.centerX() > w * 0.89f) return false
        if (box.width() > w * 0.25f || box.height() > h * 0.075f) return false

        val compact = normalized
            .replace("-", " ")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val noiseTokens = listOf(
            "elderbot", "szukam", "poziom", "yang", "sell", "sprzedam", "kupie",
            "kupię", "expie", "pw", "pz", "pe", "hp", "lv", "lvl", "cena", "sm"
        )
        return noiseTokens.none { token ->
            compact == token || compact.startsWith("$token ") || compact.contains(" $token ")
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

                val expMode = enabled("auto_exp", false)
                val bossMode = enabled("auto_boss", true)
                val metinMode = enabled("farmbot", true)
                val bossNames = listOf(
                    "lykos", "scrofa", "bera", "tigris",
                    "cung mok", "junghyul", "jug hyul", "mi jung", "se rang", "jin hee",
                    "mahon", "bo", "goo pae", "chuong", "best kapitan", "bestialski kapitan"
                )

                val candidates = mutableListOf<OcrTargetCandidate>()
                val playerX = w * 0.50f
                val playerY = h * 0.48f

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val raw = line.text.trim()
                        val normalized = normalizeGameText(raw)
                        updateMapFromText(normalized)
                        val originalBox = line.boundingBox ?: continue
                        val box = Rect(originalBox)
                        if (!Rect.intersects(box, gameplay)) continue

                        val compact = normalized
                            .replace("-", " ")
                            .replace(".", " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()

                        val redScore = countRedPixelsNear(bitmap, box)
                        val isMetin = metinMode && compact.contains("metin")
                        val isBoss = bossMode && bossNames.any { boss ->
                            compact == boss || compact.startsWith("$boss ") || compact.contains(" $boss ")
                        }
                        val isExpCandidate = expMode &&
                            !isMetin &&
                            !isBoss &&
                            redScore > 12 &&
                            isPlausibleExpWorldLabel(raw, normalized, box, w, h)

                        if (!isBoss && !isMetin && !isExpCandidate) continue

                        val kind = when {
                            isBoss -> "BOSS"
                            isMetin -> "METIN"
                            else -> "EXP"
                        }

                        val bodyOffset = if (kind == "EXP") {
                            (box.height() * 4).coerceIn(58, 92)
                        } else {
                            65
                        }
                        val targetX = box.centerX()
                        val targetY = (box.bottom + bodyOffset).coerceAtMost(gameplay.bottom)

                        val ddx = targetX - playerX
                        val ddy = targetY - playerY
                        val distance = kotlin.math.sqrt(ddx * ddx + ddy * ddy).toFloat()
                        if (kind == "EXP") {
                            val focusPct = prefs().getInt("auto_hunt_focus", 70).coerceIn(30, 95) / 100f
                            val focusRadius = kotlin.math.min(w, h) * (0.86f * focusPct + 0.18f)
                            if (distance > focusRadius) continue
                        }

                        candidates.add(
                            OcrTargetCandidate(
                                name = compact,
                                kind = kind,
                                label = Rect(box),
                                targetX = targetX,
                                targetY = targetY,
                                redScore = redScore,
                                playerDistance = distance
                            )
                        )
                    }
                }

                val now = System.currentTimeMillis()

                fun deliver(candidate: OcrTargetCandidate, refreshLock: Boolean = true) {
                    if (refreshLock) lockCoreTarget(candidate, now)
                    lastTargetKind = candidate.kind
                    lastDetectedName = candidate.name
                    lastDetectedLabelBottom = candidate.label.bottom
                    lastDetectedLabelHeight = candidate.label.height()
                    lastDetectedX = candidate.targetX
                    lastDetectedY = candidate.targetY

                    val left = (candidate.targetX - 65).coerceAtLeast(gameplay.left)
                    val right = (candidate.targetX + 65).coerceAtMost(gameplay.right)
                    val top = (candidate.targetY - 48).coerceAtLeast(gameplay.top)
                    val bottom = (candidate.targetY + 48).coerceAtMost(gameplay.bottom)
                    recognizer.close()
                    onResult(
                        MetinDetection(
                            true,
                            Rect(left, top, right, bottom),
                            candidate.targetX,
                            candidate.targetY
                        )
                    )
                }

                // V0.20 target queue policy: keep the current target stable, but allow a
                // higher-priority target (Boss > Metin > EXP) to pre-empt only before combat.
                if (coreTargetKind.isNotBlank()) {
                    val higher = if (!attackMode) {
                        candidates
                            .filter { targetPriority(it.kind) > targetPriority(coreTargetKind) }
                            .sortedWith(compareByDescending<OcrTargetCandidate> { targetPriority(it.kind) }
                                .thenBy { it.playerDistance })
                            .firstOrNull()
                    } else null

                    if (higher != null) {
                        lockCoreTarget(higher, now)
                        coreState = CoreState.TARGET_APPROACH
                        deliver(higher, refreshLock = false)
                        return@addOnSuccessListener
                    }

                    val lockedPool = candidates.filter { sameLockedTarget(it) }
                    val locked = lockedPool.minByOrNull {
                        val dx = it.targetX - coreTargetX
                        val dy = it.targetY - coreTargetY
                        dx * dx + dy * dy
                    }

                    if (locked != null) {
                        coreTargetX = locked.targetX
                        coreTargetY = locked.targetY
                        coreTargetSeenAt = now
                        coreTargetMisses = 0
                        if (locked.kind == "EXP") {
                            expLockActive = true
                            expLockName = locked.name
                            expLockX = locked.targetX
                            expLockY = locked.targetY
                            expLockSeenAt = now
                            expLockMisses = 0
                        }
                        deliver(locked, refreshLock = false)
                        return@addOnSuccessListener
                    }

                    coreTargetMisses++
                    if (coreTargetKind == "EXP") expLockMisses++
                    if (coreTargetMisses <= 3 && now - coreTargetSeenAt < 2400L) {
                        recognizer.close()
                        onResult(MetinDetection(false, Rect(), 0, 0))
                        return@addOnSuccessListener
                    }

                    clearCoreTarget()
                    clearExpLock()
                    if (attackMode) {
                        recognizer.close()
                        onResult(MetinDetection(false, Rect(), 0, 0))
                        return@addOnSuccessListener
                    }
                }

                // Build a deterministic queue from everything visible in this frame.
                // Same-priority targets are ordered by visual distance to the character.
                val best = candidates
                    .sortedWith(compareByDescending<OcrTargetCandidate> { targetPriority(it.kind) }
                        .thenBy { it.playerDistance })
                    .firstOrNull()

                if (best == null) {
                    recognizer.close()
                    onResult(MetinDetection(false, Rect(), 0, 0))
                    return@addOnSuccessListener
                }

                lockCoreTarget(best, now)
                coreState = CoreState.TARGET_APPROACH
                lastMoveX = 0f
                lastMoveY = 0f
                desiredMoveX = 0f
                desiredMoveY = 0f
                steeringUpdatedAt = 0L
                resetProgressWatch()
                deliver(best, refreshLock = false)
            }
            .addOnFailureListener {
                recognizer.close()
                if (expLockActive) expLockMisses++
                onResult(MetinDetection(false, Rect(), 0, 0))
            }
    }

    private fun selectedTargetBarLooksVisible(bitmap: Bitmap): Boolean {
        // ElderMT2 shows the selected enemy HP bar at the top-centre of the screen.
        // Detecting that bar is much more reliable during combat than repeatedly OCR-tracking
        // a red world label hidden by other mobs. This remains screen-only visual analysis.
        val w = bitmap.width
        val h = bitmap.height
        val left = (w * 0.34f).toInt().coerceIn(0, w - 1)
        val right = (w * 0.69f).toInt().coerceIn(left + 1, w)
        val top = (h * 0.055f).toInt().coerceIn(0, h - 1)
        val bottom = (h * 0.115f).toInt().coerceIn(top + 1, h)
        val requiredRun = (w * 0.025f).toInt().coerceAtLeast(18)
        var matchingRows = 0
        for (y in top until bottom step 2) {
            var run = 0
            var bestRun = 0
            for (x in left until right step 2) {
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                val red = r >= 85 && r > g * 1.32f && r > b * 1.24f
                if (red) {
                    run += 2
                    if (run > bestRun) bestRun = run
                } else run = 0
            }
            if (bestRun >= requiredRun) matchingRows++
            if (matchingRows >= 2) return true
        }
        return false
    }

    private fun updateSceneMotion(bitmap: Bitmap): Float {
        val cols = 18
        val rows = 9
        val sample = IntArray(cols * rows)
        var i = 0
        for (ry in 0 until rows) {
            val y = ((0.16f + 0.58f * (ry.toFloat() / (rows - 1))) * bitmap.height)
                .toInt().coerceIn(0, bitmap.height - 1)
            for (cx in 0 until cols) {
                val x = ((0.14f + 0.72f * (cx.toFloat() / (cols - 1))) * bitmap.width)
                    .toInt().coerceIn(0, bitmap.width - 1)
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                sample[i++] = (r * 30 + g * 59 + b * 11) / 100
            }
        }

        val previous = previousSceneSample
        previousSceneSample = sample
        // Coarse terrain fingerprint: four luminance bands + overall contrast. It is intentionally
        // low resolution so nearby frames map to the same local obstacle memory.
        val band = IntArray(4)
        val perBand = sample.size / 4
        for (b in 0 until 4) {
            var sum = 0
            val from = b * perBand
            val to = if (b == 3) sample.size else (b + 1) * perBand
            for (q in from until to) sum += sample[q]
            band[b] = ((sum / (to - from).coerceAtLeast(1)) / 24).coerceIn(0, 10)
        }
        currentSceneFingerprint = "${band[0]}${band[1]}${band[2]}${band[3]}"
        if (previous == null || previous.size != sample.size) return 100f

        var diff = 0L
        for (k in sample.indices) diff += kotlin.math.abs(sample[k] - previous[k])
        return diff.toFloat() / sample.size.toFloat()
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
                var bestKind = "METIN"
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
            try { overlayStatus?.text = lastStatus } catch (_: Throwable) {}
        }
    }

    /**
     * Continuous virtual joystick engine.  The finger stays down between
     * successive gesture segments, so movement no longer becomes
     * press-release-press-release on every OCR scan.
     */
    private val movementLoop = object : Runnable {
        override fun run() {
            if (!running) {
                finishJoystickGesture()
                return
            }
            if (!attackMode) {
                val x = desiredMoveX.coerceIn(-0.92f, 0.92f)
                val y = desiredMoveY.coerceIn(-0.92f, 0.92f)
                if (kotlin.math.abs(x) > 0.04f || kotlin.math.abs(y) > 0.04f) {
                    continueJoystickGesture(x, y)
                } else {
                    finishJoystickGesture()
                }
            }
            mainHandler.postDelayed(this, 620L)
        }
    }

    private fun continueJoystickGesture(dx: Float, dy: Float) {
        if (!running || attackMode) return
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val centerX = w * 0.13f
        val centerY = h * 0.77f
        val radiusX = w * 0.060f
        val radiusY = h * 0.060f
        val endX = centerX + radiusX * dx
        val endY = centerY + radiusY * dy

        val previous = activeJoystickStroke
        val path = Path()
        val stroke = if (previous == null) {
            path.moveTo(centerX, centerY)
            path.lineTo(endX, endY)
            GestureDescription.StrokeDescription(path, 0L, 760L, true)
        } else {
            path.moveTo(joystickEndX, joystickEndY)
            path.lineTo(endX, endY)
            previous.continueStroke(path, 0L, 760L, true)
        }

        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
        if (ok) {
            activeJoystickStroke = stroke
            joystickEndX = endX
            joystickEndY = endY
        } else {
            activeJoystickStroke = null
        }
    }

    private fun finishJoystickGesture(onFinished: (() -> Unit)? = null) {
        val previous = activeJoystickStroke
        if (previous == null) {
            onFinished?.invoke()
            return
        }
        val path = Path().apply {
            moveTo(joystickEndX, joystickEndY)
            lineTo(joystickEndX, joystickEndY)
        }
        val finalStroke = try {
            previous.continueStroke(path, 0L, 45L, false)
        } catch (_: Throwable) {
            activeJoystickStroke = null
            onFinished?.invoke()
            return
        }
        activeJoystickStroke = null
        dispatchGesture(
            GestureDescription.Builder().addStroke(finalStroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished?.invoke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished?.invoke()
                }
            },
            null
        )
    }

    fun startSkillCalibration() {
        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
        skillCalibrationOverlay?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        var step = 0
        val hint = TextView(this).apply {
            text = "Kalibracja Auto Skills: dotknij SKILL 1"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(70, 0, 0, 0))
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(30), 0, 0)
        }
        hint.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_DOWN) {
                val w = resources.displayMetrics.widthPixels.toFloat()
                val h = resources.displayMetrics.heightPixels.toFloat()
                prefs().edit().putFloat("skill_${step}_x", e.rawX / w).putFloat("skill_${step}_y", e.rawY / h).apply()
                step++
                if (step >= 3) {
                    try { wm.removeView(hint) } catch (_: Throwable) {}
                    skillCalibrationOverlay = null
                    lastStatus = "Skille skalibrowane: 3/3"
                    setPanelVisible(true)
                } else hint.text = "Kalibracja Auto Skills: dotknij SKILL ${step + 1}"
                true
            } else true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, android.graphics.PixelFormat.TRANSLUCENT
        )
        skillCalibrationOverlay = hint
        wm.addView(hint, lp)
    }

    private fun tapAt(x: Float, y: Float, duration: Long = 65L, after: (() -> Unit)? = null) {
        if (!running) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { after?.invoke() }
                override fun onCancelled(gestureDescription: GestureDescription?) { after?.invoke() }
            },
            null
        )
    }

    private fun tapAttackButton(after: (() -> Unit)? = null) {
        val m = resources.displayMetrics
        tapAt(m.widthPixels * 0.90f, m.heightPixels * 0.80f, 85L, after)
    }

    private fun tapDetectedMetin(after: (() -> Unit)? = null) {
        val x = lastDetectedX.toFloat()
        val y = lastDetectedY.toFloat()
        lastTargetTapAt = System.currentTimeMillis()
        tapAt(x, y, 70L, after)
    }

    private val attackLoop = object : Runnable {
        override fun run() {
            if (!running || !attackMode || !attackConfirmed) return
            tapAttackButton()
            mainHandler.postDelayed(this, 310L)
        }
    }

    private fun beginAttackMode() {
        if (!running || attackMode) return
        attackMode = true
        attackConfirmed = false
        attackSelectAttempts = 0
        coreState = CoreState.COMBAT
        attackMisses = 0
        desiredMoveX = 0f
        desiredMoveY = 0f
        lastMoveX = 0f
        lastMoveY = 0f
        selectedTargetBarVisible = false
        lastStatus = when (lastTargetKind) {
            "BOSS" -> "Boss: zaznaczam cel"
            "EXP" -> "Auto Łowy: zaznaczam moba"
            else -> "Metin: zaznaczam cel"
        }
        setOverlaySymbol("◎")

        finishJoystickGesture {
            if (!running || !attackMode) return@finishJoystickGesture
            // Do not start swinging immediately. First tap the world target and wait until
            // ElderMT2 confirms the selection with its own top HP bar. This prevents the
            // characteristic one-hit-in-the-air behaviour from previous builds.
            tapDetectedMetin {
                attackSelectAttempts = 1
                mainHandler.postDelayed({ if (running && attackMode) farmTick() }, 260L)
            }
        }
    }

    private fun finishAttackAndPickup() {
        if (!running) return
        attackMode = false
        attackMisses = 0
        attackConfirmed = false
        attackSelectAttempts = 0
        coreState = CoreState.PICKUP
        mainHandler.removeCallbacks(attackLoop)
        setOverlaySymbol("…")
        val finishedKind = lastTargetKind
        clearCoreTarget()
        if (finishedKind == "EXP") clearExpLock()
        lastStatus = when (finishedKind) {
            "BOSS" -> "Boss pokonany — podnoszę drop"
            "EXP" -> "Mob pokonany — podnoszę drop"
            else -> "Metin zbity — podnoszę drop"
        }
        pickupPass = 0
        if (!enabled("pickup")) {
            missCount = 0
            desiredMoveX = 0f
            desiredMoveY = 0f
            coreState = CoreState.SEARCH_ROUTE
            setOverlaySymbol("■")
            lastStatus = when (finishedKind) {
                "EXP" -> "Pickup wyłączony — szukam następnego moba"
                "BOSS" -> "Pickup wyłączony — szukam kolejnego celu"
                else -> "Pickup wyłączony — szukam następnego Metina"
            }
            mainHandler.postDelayed({ farmTick() }, 400L)
            return
        }
        captureAndPickupLoot {
            if (!running) return@captureAndPickupLoot
            missCount = 0
            desiredMoveX = 0f
            desiredMoveY = 0f
            coreState = CoreState.SEARCH_ROUTE
            setOverlaySymbol("■")
            lastStatus = when (finishedKind) {
                "EXP" -> "Auto Łowy: szukam następnego moba..."
                "BOSS" -> "Szukam kolejnego bossa / Metina..."
                else -> "Szukam następnego Metina..."
            }
            mainHandler.postDelayed({ farmTick() }, 500L)
        }
    }

    /**
     * OCR the area around the last Metin and tap non-red text labels nearby.
     * Red labels are usually enemies, while drop labels in the supplied game
     * screenshots are non-red.  This avoids blindly tapping the whole screen.
     */
    private fun pickupHandLooksVisible(bitmap: Bitmap): Boolean {
        // Calibrated from the two ElderMT2 screenshots supplied by the user:
        // the pickup hand appears around 83% W / 51.5% H as a dark round button
        // with a small bright hand glyph. No game data is read.
        val cx = (bitmap.width * 0.830f).toInt()
        val cy = (bitmap.height * 0.515f).toInt()
        val radius = (bitmap.height * 0.055f).toInt().coerceAtLeast(22)
        var bright = 0
        var dark = 0
        var total = 0
        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(bitmap.height - 1) step 2) {
            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(bitmap.width - 1) step 2) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > radius * radius) continue
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val avg = (r + g + b) / 3
                if (avg < 88) dark++
                if (max > 175 && max - min < 85) bright++
                total++
            }
        }
        if (total == 0) return false
        val brightRatio = bright.toFloat() / total.toFloat()
        val darkRatio = dark.toFloat() / total.toFloat()
        return brightRatio > 0.0045f && darkRatio > 0.14f
    }

    private fun capturePickupHandVisible(onDone: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onDone(false); return }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: throw IllegalStateException("Brak obrazu pickup")
                                bitmap = hw.copy(Bitmap.Config.ARGB_8888, false)
                                hw.recycle()
                            } finally {
                                buffer.close()
                            }
                            onDone(bitmap?.let { pickupHandLooksVisible(it) } == true)
                        } catch (_: Throwable) {
                            onDone(false)
                        } finally {
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) { onDone(false) }
                }
            )
        } catch (_: Throwable) {
            onDone(false)
        }
    }

    private fun captureAndPickupLoot(onDone: () -> Unit) {
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.830f
        val y = m.heightPixels * 0.515f
        pickupPass = 0
        pickupAbsentFrames = 0
        coreState = CoreState.PICKUP

        fun next() {
            if (!running || !enabled("pickup")) { onDone(); return }
            if (pickupPass >= 18) { onDone(); return }
            capturePickupHandVisible { visible ->
                if (!running) return@capturePickupHandVisible
                if (visible) {
                    pickupAbsentFrames = 0
                    pickupPass++
                    lastStatus = "Pickup: ręka widoczna • $pickupPass"
                    safeUtilityTap(x, y)
                    mainHandler.postDelayed({ next() }, 300L)
                } else {
                    pickupAbsentFrames++
                    lastStatus = "Pickup: sprawdzam drop..."
                    if (pickupAbsentFrames >= 2) {
                        onDone()
                    } else {
                        mainHandler.postDelayed({ next() }, 280L)
                    }
                }
            }
        }
        next()
    }

    private fun estimateBarFill(bitmap: Bitmap, x1f: Float, x2f: Float, yf: Float, mode: Int): Float {
        val x1 = (bitmap.width * x1f).toInt().coerceIn(0, bitmap.width - 1)
        val x2 = (bitmap.width * x2f).toInt().coerceIn(x1 + 1, bitmap.width)
        val y0 = (bitmap.height * yf).toInt().coerceIn(1, bitmap.height - 2)
        var good = 0
        var total = 0
        for (x in x1 until x2 step 3) {
            var hit = false
            for (dy in -2..2) {
                val c = bitmap.getPixel(x, (y0 + dy).coerceIn(0, bitmap.height - 1))
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                if (mode == 0) {
                    if (r > 95 && r > g * 1.35f && r > b * 1.35f) hit = true
                } else {
                    if (b > 85 && b > r * 1.20f && b > g * 1.05f) hit = true
                }
            }
            if (hit) good++
            total++
        }
        return if (total == 0) 1f else good.toFloat() / total.toFloat()
    }

    private fun safeUtilityTap(x: Float, y: Float) {
        if (!running) return
        val doTap = { if (running) tapAt(x, y, 70L) }
        if (activeJoystickStroke != null) finishJoystickGesture(doTap) else doTap()
    }

    private fun checkAutoPotions(bitmap: Bitmap) {
        if (!enabled("auto_potions")) return
        val now = System.currentTimeMillis()
        val hp = estimateBarFill(bitmap, 0.060f, 0.220f, 0.052f, 0)
        val mp = estimateBarFill(bitmap, 0.060f, 0.220f, 0.094f, 1)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        if (hp in 0.04f..0.62f && now - lastHpPotionAt > 1200L) {
            lastHpPotionAt = now
            safeUtilityTap(w * 0.831f, h * 0.855f)
            lastStatus = "Auto Potka HP (${(hp * 100).toInt()}%)"
            return
        }
        if (mp in 0.04f..0.36f && now - lastMpPotionAt > 1500L) {
            lastMpPotionAt = now
            // Default MP slot: quick-slot directly above the HP potion slot.
            safeUtilityTap(w * 0.831f, h * 0.760f)
            lastStatus = "Auto Potka MP (${(mp * 100).toInt()}%)"
        }
    }

    private fun skillLooksReady(bitmap: Bitmap, cx: Float, cy: Float): Boolean {
        val x0 = (bitmap.width * cx).toInt()
        val y0 = (bitmap.height * cy).toInt()
        var bright = 0
        var samples = 0
        val radius = (bitmap.height * 0.022f).toInt().coerceAtLeast(5)
        for (dy in -radius..radius step 4) {
            for (dx in -radius..radius step 4) {
                if (dx * dx + dy * dy > radius * radius) continue
                val x = (x0 + dx).coerceIn(0, bitmap.width - 1)
                val y = (y0 + dy).coerceIn(0, bitmap.height - 1)
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max > 85 && (max - min) > 18) bright++
                samples++
            }
        }
        return samples > 0 && bright.toFloat() / samples.toFloat() > 0.16f
    }

    private fun checkAutoSkills(bitmap: Bitmap) {
        if (!enabled("auto_skills")) return
        // Skills are support actions, never navigation actions. In V0.20 they are pressed
        // only during COMBAT, so Auto Skills cannot cancel the joystick while searching
        // or approaching a target.
        if (coreState != CoreState.COMBAT) return
        val slots = Array(3) { i ->
            val defaultSlots = arrayOf(0.870f to 0.590f, 0.922f to 0.595f, 0.831f to 0.640f)
            prefs().getFloat("skill_${i}_x", defaultSlots[i].first) to
                prefs().getFloat("skill_${i}_y", defaultSlots[i].second)
        }
        val now = System.currentTimeMillis()
        for (i in slots.indices) {
            if (now - skillLastTapAt[i] < 2600L) continue
            val (x, y) = slots[i]
            if (skillLooksReady(bitmap, x, y)) {
                skillLastTapAt[i] = now
                safeUtilityTap(bitmap.width * x, bitmap.height * y)
                lastStatus = "Auto Skill ${i + 1}"
                break
            }
        }
    }

    private fun maybeCheckRevive() {
        if (!running || !enabled("auto_revive")) return
        val now = System.currentTimeMillis()
        if (now - lastReviveScanAt < 3500L) return
        lastReviveScanAt = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        try {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return
                                bitmap = hw.copy(Bitmap.Config.ARGB_8888, false)
                                hw.recycle()
                            } finally { buffer.close() }
                            val img = bitmap ?: return
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            recognizer.process(InputImage.fromBitmap(img, 0))
                                .addOnSuccessListener { text ->
                                    var target: Rect? = null
                                    outer@ for (block in text.textBlocks) {
                                        for (line in block.lines) {
                                            val n = line.text.lowercase(Locale.getDefault())
                                                .replace("ę", "e").replace("ą", "a").replace("ł", "l")
                                                .replace("ś", "s").replace("ć", "c").replace("ń", "n")
                                                .replace("ó", "o").replace("ź", "z").replace("ż", "z")
                                            if (n.contains("wskrzes") || n.contains("odrodz") || n.contains("powstan") || n.contains("wstan")) {
                                                target = line.boundingBox
                                                break@outer
                                            }
                                        }
                                    }
                                    recognizer.close()
                                    target?.let { box ->
                                        safeUtilityTap(box.centerX().toFloat(), box.centerY().toFloat())
                                        lastStatus = "Auto Revive"
                                    }
                                    bitmap?.recycle()
                                }
                                .addOnFailureListener { recognizer.close(); bitmap?.recycle() }
                        } catch (_: Throwable) { bitmap?.recycle() }
                    }
                    override fun onFailure(errorCode: Int) = Unit
                }
            )
        } catch (_: Throwable) { }
    }

    private data class MapRouteProfile(
        val id: String,
        val label: String,
        val headings: Array<Pair<Float, Float>>
    )

    private fun normalizeGameText(raw: String): String = raw.lowercase(Locale.getDefault())
        .replace("ę", "e").replace("ą", "a")
        .replace("ł", "l").replace("ś", "s")
        .replace("ć", "c").replace("ń", "n")
        .replace("ó", "o").replace("ź", "z").replace("ż", "z")

    private fun updateMapFromText(text: String) {
        val n = normalizeGameText(text)
        val match = when {
            n.contains("seungryong") || n.contains("dolina") -> "seungryong" to "Dolina Seungryong"
            n.contains("yongbi") || n.contains("pustynia") -> "yongbi" to "Pustynia Yongbi"
            n.contains("sohan") || n.contains("gora sohan") -> "sohan" to "Góra Sohan"
            n.contains("piekielna") || n.contains("ognista ziemia") -> "fireland" to "Piekielna Ziemia"
            n.contains("czerwony las") -> "red_forest" to "Czerwony Las"
            n.contains("las duchow") -> "ghost_forest" to "Las Duchów"
            n.contains("wezowe pole") || n.contains("wezowe") -> "snakefield" to "Wężowe Pole"
            n.contains("swiatynia hwang") || n.contains("hwang") -> "hwang" to "Świątynia Hwang"
            n.contains("yongan") -> "yongan" to "Yongan • M1 Shinsoo"
            n.contains("jayang") -> "jayang" to "Jayang • M2 Shinsoo"
            n.contains("pyungmoo") -> "pyungmoo" to "Pyungmoo • M1 Jinno"
            n.contains("bakra") -> "bakra" to "Bakra • M2 Jinno"
            n.contains("joan") -> "joan" to "Joan • M1 Chunjo"
            n.contains("bokjung") -> "bokjung" to "Bokjung • M2 Chunjo"
            else -> null
        }
        if (match != null) {
            currentMapId = match.first
            currentMapLabel = match.second
            lastMapSeenAt = System.currentTimeMillis()
            prefs().edit().putString("last_map_id", currentMapId).putString("last_map_label", currentMapLabel).apply()
        }
    }

    private fun currentRouteProfile(): MapRouteProfile {
        fun a(vararg p: Pair<Float, Float>) = arrayOf(*p)
        return when (currentMapId) {
            "seungryong" -> MapRouteProfile("seungryong", "Dolina Seungryong", a(0f to -0.72f, 0.34f to -0.62f, 0.58f to -0.38f, -0.22f to -0.70f, -0.52f to -0.42f))
            "yongbi" -> MapRouteProfile("yongbi", "Pustynia Yongbi", a(0f to -0.78f, 0.44f to -0.55f, -0.44f to -0.55f, 0.66f to -0.28f, -0.66f to -0.28f))
            "sohan" -> MapRouteProfile("sohan", "Góra Sohan", a(0f to -0.62f, 0.30f to -0.58f, -0.30f to -0.58f, 0.60f to -0.24f, -0.60f to -0.24f))
            "fireland" -> MapRouteProfile("fireland", "Piekielna Ziemia", a(0f to -0.66f, 0.40f to -0.52f, -0.40f to -0.52f, 0.62f to -0.22f, -0.62f to -0.22f))
            "ghost_forest", "red_forest" -> MapRouteProfile(currentMapId, currentMapLabel, a(0f to -0.58f, 0.28f to -0.55f, -0.28f to -0.55f, 0.54f to -0.24f, -0.54f to -0.24f))
            "snakefield", "hwang" -> MapRouteProfile(currentMapId, currentMapLabel, a(0f to -0.64f, 0.34f to -0.54f, -0.34f to -0.54f, 0.58f to -0.20f, -0.58f to -0.20f))
            "yongan" -> MapRouteProfile("yongan", "Yongan • M1 Shinsoo", a(0f to -0.68f, 0.28f to -0.62f, 0.52f to -0.36f, 0.18f to -0.70f, -0.30f to -0.62f, -0.54f to -0.34f))
            "jayang" -> MapRouteProfile("jayang", "Jayang • M2 Shinsoo", a(0f to -0.62f, 0.24f to -0.58f, 0.42f to -0.42f, -0.22f to -0.60f, -0.42f to -0.42f))
            "pyungmoo" -> MapRouteProfile("pyungmoo", "Pyungmoo • M1 Jinno", a(0f to -0.64f, 0.30f to -0.56f, 0.50f to -0.34f, -0.28f to -0.58f, -0.50f to -0.34f))
            "bakra" -> MapRouteProfile("bakra", "Bakra • M2 Jinno", a(0f to -0.60f, 0.22f to -0.58f, 0.38f to -0.44f, -0.22f to -0.58f, -0.38f to -0.44f))
            "joan" -> MapRouteProfile("joan", "Joan • M1 Chunjo", a(0f to -0.66f, 0.26f to -0.60f, 0.48f to -0.38f, -0.26f to -0.60f, -0.48f to -0.38f))
            "bokjung" -> MapRouteProfile("bokjung", "Bokjung • M2 Chunjo", a(0f to -0.60f, 0.20f to -0.58f, 0.40f to -0.42f, -0.20f to -0.58f, -0.40f to -0.42f))
            else -> MapRouteProfile("unknown", "Nieznana mapa", a(0f to -0.68f, 0.30f to -0.60f, -0.30f to -0.60f, 0.52f to -0.36f, -0.52f to -0.36f))
        }
    }

    private fun headingBucket(x: Float, y: Float): Int {
        val angle = Math.toDegrees(kotlin.math.atan2(x.toDouble(), (-y).toDouble()))
        val normalized = ((angle + 360.0) % 360.0)
        return ((normalized + 22.5) / 45.0).toInt() % 8
    }

    private fun blockKey(local: Boolean, bucket: Int): String = if (local) {
        "nav_block_${currentMapId}_${currentSceneFingerprint}_$bucket"
    } else {
        "nav_block_${currentMapId}_global_$bucket"
    }

    private fun blockedScore(bucket: Int): Int {
        val p = prefs()
        return p.getInt(blockKey(false, bucket), 0) + p.getInt(blockKey(true, bucket), 0) * 2
    }

    private fun rememberBlockedDirection() {
        val bucket = headingBucket(desiredMoveX, desiredMoveY)
        val p = prefs()
        val gk = blockKey(false, bucket)
        val lk = blockKey(true, bucket)
        val global = (p.getInt(gk, 0) + 1).coerceAtMost(8)
        val local = (p.getInt(lk, 0) + 2).coerceAtMost(12)
        p.edit().putInt(gk, global).putInt(lk, local).apply()
    }

    private fun rewardCurrentDirection() {
        val bucket = headingBucket(desiredMoveX, desiredMoveY)
        if (bucket == lastSafeHeadingBucket) return
        lastSafeHeadingBucket = bucket
        val p = prefs()
        val gk = blockKey(false, bucket)
        val lk = blockKey(true, bucket)
        val global = (p.getInt(gk, 0) - 1).coerceAtLeast(0)
        val local = (p.getInt(lk, 0) - 1).coerceAtLeast(0)
        p.edit().putInt(gk, global).putInt(lk, local).apply()
    }

    private fun resetProgressWatch() {
        progressAnchorAt = 0L
        progressAnchorX = 0
        progressAnchorY = 0
        lowMotionFrames = 0
        bestApproachError = Float.MAX_VALUE
        lastMeaningfulProgressAt = 0L
    }

    private fun isIgnoredTarget(w: Float, h: Float): Boolean {
        if (System.currentTimeMillis() >= ignoreTargetUntil) return false
        val dx = kotlin.math.abs(lastDetectedX - ignoredTargetX)
        val dy = kotlin.math.abs(lastDetectedY - ignoredTargetY)
        return dx < w * 0.16f && dy < h * 0.22f
    }

    private fun ignoreCurrentTargetAndResumeRoute() {
        ignoredTargetX = lastDetectedX
        ignoredTargetY = lastDetectedY
        ignoreTargetUntil = System.currentTimeMillis() + 11000L
        avoidPhase = 0
        avoidPhaseTicks = 0
        avoidAttempts = 0
        resetProgressWatch()
        patrolStepUntil = 0L
        clearCoreTarget()
        clearExpLock()
        coreState = CoreState.SEARCH_ROUTE
        lastStatus = "Cel niedostępny — wracam na trasę"
        setOverlaySymbol("↻")
    }

    private fun applyPatrolRoute(now: Long = System.currentTimeMillis()) {
        if (now >= patrolStepUntil) {
            patrolStep++
            patrolStepUntil = now + 6500L
        }
        val profile = currentRouteProfile()
        // Pick a route leg that has worked on this map/local terrain before. A blocked
        // direction gets a persistent penalty, so the bot stops repeating the same mistake.
        var best = profile.headings[patrolStep % profile.headings.size]
        var bestScore = Int.MAX_VALUE
        for (offset in profile.headings.indices) {
            val candidate = profile.headings[(patrolStep + offset) % profile.headings.size]
            val bucket = headingBucket(candidate.first, candidate.second)
            val score = blockedScore(bucket) + offset
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        desiredMoveX = (lastMoveX * 0.72f + best.first * 0.28f).coerceIn(-0.86f, 0.86f)
        desiredMoveY = (lastMoveY * 0.72f + best.second * 0.28f).coerceIn(-0.90f, 0.90f)
        lastMoveX = desiredMoveX
        lastMoveY = desiredMoveY
        lastStatus = "${profile.label}: bezpieczna trasa • pamięć przeszkód=$bestScore"
        setOverlaySymbol("⌕")
    }

    private fun beginAvoidance(dx: Float) {
        rememberBlockedDirection()
        avoidAttempts++
        avoidDirection = if (avoidAttempts % 2 == 1) {
            if (dx >= 0f) -1f else 1f
        } else {
            -avoidDirection
        }
        avoidPhase = 1
        avoidPhaseTicks = 3
        resetProgressWatch()
        lastStatus = "UTKNIĘCIE — cofam i obchodzę przeszkodę"
        setOverlaySymbol("↪")
    }

    private fun applyAvoidanceStep(): Boolean {
        if (avoidPhase == 0) return false
        when (avoidPhase) {
            1 -> {
                desiredMoveX = avoidDirection * 0.35f
                desiredMoveY = 0.82f
                avoidPhaseTicks--
                if (avoidPhaseTicks <= 0) {
                    avoidPhase = 2
                    avoidPhaseTicks = if (avoidAttempts >= 3) 7 else 5
                }
            }
            2 -> {
                desiredMoveX = avoidDirection * 0.92f
                desiredMoveY = -0.18f
                avoidPhaseTicks--
                if (avoidPhaseTicks <= 0) {
                    avoidPhase = 3
                    avoidPhaseTicks = if (avoidAttempts >= 3) 7 else 5
                }
            }
            3 -> {
                desiredMoveX = avoidDirection * 0.76f
                desiredMoveY = -0.82f
                avoidPhaseTicks--
                if (avoidPhaseTicks <= 0) {
                    avoidPhase = 0
                    avoidPhaseTicks = 0
                    resetProgressWatch()
                }
            }
        }
        lastMoveX = desiredMoveX
        lastMoveY = desiredMoveY
        lastStatus = when (avoidPhase) {
            1 -> "Omijanie: odsuwam się od przeszkody"
            2 -> "Omijanie: idę bokiem"
            3 -> "Omijanie: wracam łukiem do celu"
            else -> "Ponownie namierzam Metina..."
        }
        setOverlaySymbol("↪")
        return true
    }

    private fun farmTick() {
        if (!running) return
        maybeCheckRevive()

        captureAndDetectMetin { found, _ ->
            if (!running) return@captureAndDetectMetin

            val m = resources.displayMetrics
            val w = m.widthPixels.toFloat()
            val h = m.heightPixels.toFloat()
            val now = System.currentTimeMillis()

            // COMBAT is isolated from target acquisition. While fighting, OCR may refresh the
            // locked target, but the bot never jumps to a different mob just because it appeared.
            if (attackMode) {
                coreState = CoreState.COMBAT
                desiredMoveX = 0f
                desiredMoveY = 0f
                lastMoveX = 0f
                lastMoveY = 0f

                if (selectedTargetBarVisible) {
                    // ElderMT2 itself confirmed a selected target. Only now may the bot start
                    // the attack loop. From this point world-label OCR no longer decides whether
                    // the fight is alive; the selected-target HP bar does.
                    attackMisses = 0
                    attackSelectAttempts = 0
                    if (!attackConfirmed) {
                        attackConfirmed = true
                        mainHandler.removeCallbacks(attackLoop)
                        tapAttackButton()
                        mainHandler.postDelayed(attackLoop, 260L)
                    }
                    lastStatus = when (lastTargetKind) {
                        "BOSS" -> "Walka: boss / miniboss • HP LOCK"
                        "EXP" -> "Auto Łowy: walka • HP LOCK"
                        else -> "Walka: Metin • HP LOCK"
                    }
                    mainHandler.postDelayed({ farmTick() }, 420L)
                } else if (!attackConfirmed) {
                    // Selection has not been confirmed yet: never swing into the air. Re-tap the
                    // candidate a few times, then abandon it cleanly if the game never shows HP.
                    attackSelectAttempts++
                    if (found && now - lastTargetTapAt > 650L) tapDetectedMetin()
                    lastStatus = "Zaznaczam cel • czekam na pasek HP ($attackSelectAttempts/6)"
                    if (attackSelectAttempts >= 6) {
                        attackMode = false
                        attackConfirmed = false
                        mainHandler.removeCallbacks(attackLoop)
                        if (lastTargetKind == "EXP") clearExpLock()
                        clearCoreTarget()
                        coreState = CoreState.SEARCH_ROUTE
                        setOverlaySymbol("⌕")
                        lastStatus = "Nie udało się zaznaczyć celu • szukam następnego"
                        mainHandler.postDelayed({ farmTick() }, 320L)
                    } else {
                        mainHandler.postDelayed({ farmTick() }, 300L)
                    }
                } else {
                    // A confirmed fight ended only after the game HP bar vanished repeatedly.
                    attackMisses++
                    lastStatus = "Walka: pasek celu zniknął ($attackMisses/3)"
                    if (attackMisses >= 3) finishAttackAndPickup()
                    else mainHandler.postDelayed({ farmTick() }, 360L)
                }
                return@captureAndDetectMetin
            }

            if (!found) {
                // A short OCR dropout must not immediately destroy the target lock. Stop movement
                // for a moment, reacquire, and only then fall back to the route.
                if (coreTargetKind.isNotBlank() && coreTargetMisses in 1..3) {
                    coreState = CoreState.TARGET_APPROACH
                    desiredMoveX = 0f
                    desiredMoveY = 0f
                    lastMoveX = 0f
                    lastMoveY = 0f
                    expRangeConfirmFrames = 0
                    lastStatus = "Cel chwilowo zgubiony • ponawiam namierzanie"
                    setOverlaySymbol("◎")
                    mainHandler.postDelayed({ farmTick() }, 260L)
                    return@captureAndDetectMetin
                }

                clearCoreTarget()
                clearExpLock()
                resetProgressWatch()
                missCount++
                coreState = CoreState.SEARCH_ROUTE

                if (applyAvoidanceStep()) {
                    coreState = CoreState.RECOVER
                    mainHandler.postDelayed({ farmTick() }, 450L)
                    return@captureAndDetectMetin
                }

                val onlyAutoHunt = enabled("auto_exp", false) && !enabled("farmbot") && !enabled("auto_boss", true)
                if (onlyAutoHunt) {
                    // Official-style local Auto-Hunt: do not wander across the whole map when no
                    // monster is inside the configured focus area. Wait and rescan locally.
                    desiredMoveX = 0f
                    desiredMoveY = 0f
                    lastMoveX = 0f
                    lastMoveY = 0f
                } else if (missCount >= 2) {
                    applyPatrolRoute()
                } else {
                    desiredMoveX = lastMoveX * 0.90f
                    desiredMoveY = lastMoveY * 0.90f
                }
                lastStatus = when {
                    enabled("auto_boss", true) && enabled("farmbot") && enabled("auto_exp", false) ->
                        "Trasa • skan: BOSS > METIN > AUTO ŁOWY"
                    enabled("auto_boss", true) && enabled("farmbot") ->
                        "Trasa • skan: BOSS > METIN"
                    enabled("auto_exp", false) -> "Auto Łowy: brak mobów w zasięgu • czekam"
                    else -> "Trasa • szukam celu"
                }
                mainHandler.postDelayed({ farmTick() }, 540L)
                return@captureAndDetectMetin
            }

            missCount = 0
            coreState = CoreState.TARGET_APPROACH
            val dx = lastDetectedX - w * 0.50f
            val dy = lastDetectedY - h * 0.48f
            val isExpTarget = lastTargetKind == "EXP"
            val approachError = kotlin.math.sqrt((dx / w) * (dx / w) + (dy / h) * (dy / h)).toFloat()

            if (isIgnoredTarget(w, h)) {
                clearCoreTarget()
                clearExpLock()
                coreState = CoreState.SEARCH_ROUTE
                applyPatrolRoute()
                mainHandler.postDelayed({ farmTick() }, 540L)
                return@captureAndDetectMetin
            }

            if (applyAvoidanceStep()) {
                coreState = CoreState.RECOVER
                mainHandler.postDelayed({ farmTick() }, 450L)
                return@captureAndDetectMetin
            }

            // Bosses and Metins are allowed to influence steering only after the normal route
            // has brought them into a conservative capture corridor. A distant target behind
            // terrain therefore cannot drag the character straight into a wall.
            val inCaptureCorridor = kotlin.math.abs(dx) < w * 0.24f &&
                dy > -h * 0.20f && dy < h * 0.28f

            if (!isExpTarget && !inCaptureCorridor) {
                if (routeTargetObservedAt == 0L) routeTargetObservedAt = now
                if (approachError < routeBestTargetError - 0.012f) {
                    routeBestTargetError = approachError
                    lastMeaningfulProgressAt = now
                }
                if (lastMeaningfulProgressAt == 0L) lastMeaningfulProgressAt = now

                val stalled = now - lastMeaningfulProgressAt > 6500L
                val heldTooLong = coreTargetLockedAt > 0L && now - coreTargetLockedAt > 14000L
                if (stalled || heldTooLong) {
                    ignoredTargetX = lastDetectedX
                    ignoredTargetY = lastDetectedY
                    ignoreTargetUntil = now + 9000L
                    clearCoreTarget()
                    clearExpLock()
                    coreState = CoreState.SEARCH_ROUTE
                    resetProgressWatch()
                    applyPatrolRoute()
                    lastStatus = "$lastTargetKind poza bezpiecznym dojściem • wracam na trasę"
                    mainHandler.postDelayed({ farmTick() }, 520L)
                    return@captureAndDetectMetin
                }

                applyPatrolRoute(now)
                lastStatus = "$lastTargetKind zablokowany • trasa zbliża do celu"
                setOverlaySymbol("◇")
                mainHandler.postDelayed({ farmTick() }, 520L)
                return@captureAndDetectMetin
            }

            setOverlaySymbol("■")

            // Attack is a separate state and requires a confirmed close-range position.
            val aligned = kotlin.math.abs(dx) < w * (if (isExpTarget) 0.075f else 0.105f)
            val attackRangeOnScreen = if (isExpTarget) {
                dy > -h * 0.075f && dy < h * 0.12f
            } else {
                dy > -h * 0.13f && dy < h * 0.20f
            }

            if (aligned && attackRangeOnScreen) {
                desiredMoveX = 0f
                desiredMoveY = 0f
                lastMoveX = 0f
                lastMoveY = 0f

                if (isExpTarget) {
                    expRangeConfirmFrames++
                    if (expRangeConfirmFrames < 2) {
                        lastStatus = "Auto Łowy: potwierdzam zasięg"
                        mainHandler.postDelayed({ farmTick() }, 230L)
                        return@captureAndDetectMetin
                    }
                }

                resetProgressWatch()
                avoidPhase = 0
                avoidPhaseTicks = 0
                beginAttackMode()
                mainHandler.postDelayed({ farmTick() }, 500L)
                return@captureAndDetectMetin
            }
            if (isExpTarget) expRangeConfirmFrames = 0

            // During TARGET_APPROACH, monitor actual progress toward the attack zone. If the
            // target is not getting closer, run a bounded detour and finally abandon it.
            val commanded = kotlin.math.sqrt(desiredMoveX * desiredMoveX + desiredMoveY * desiredMoveY)
            if (progressAnchorAt == 0L) {
                progressAnchorAt = now
                progressAnchorX = lastDetectedX
                progressAnchorY = lastDetectedY
                bestApproachError = approachError
                lastMeaningfulProgressAt = now
            } else if (approachError < bestApproachError - 0.018f) {
                bestApproachError = approachError
                lastMeaningfulProgressAt = now
                progressAnchorX = lastDetectedX
                progressAnchorY = lastDetectedY
                avoidAttempts = 0
                rewardCurrentDirection()
            }

            if (commanded > 0.26f) {
                val noProgressLimit = if (isExpTarget) 1750L else 2300L
                val hardLimit = if (isExpTarget) 4200L else 5200L
                val noRealProgressFor = now - lastMeaningfulProgressAt
                val hardTimeout = now - progressAnchorAt
                if (noRealProgressFor >= noProgressLimit || hardTimeout >= hardLimit) {
                    val maxAvoidAttempts = if (isExpTarget) 2 else 3
                    if (avoidAttempts >= maxAvoidAttempts) {
                        ignoredTargetX = lastDetectedX
                        ignoredTargetY = lastDetectedY
                        ignoreTargetUntil = now + if (isExpTarget) 5500L else 10000L
                        clearCoreTarget()
                        clearExpLock()
                        coreState = CoreState.SEARCH_ROUTE
                        desiredMoveX = 0f
                        desiredMoveY = 0f
                        resetProgressWatch()
                        lastStatus = "Cel niedostępny • pomijam i wracam na trasę"
                        mainHandler.postDelayed({ farmTick() }, 340L)
                    } else {
                        coreState = CoreState.RECOVER
                        beginAvoidance(dx)
                        applyAvoidanceStep()
                        mainHandler.postDelayed({ farmTick() }, 480L)
                    }
                    return@captureAndDetectMetin
                }
            }

            // Final local approach. EXP receives faster steering because nearby mobs move;
            // Metins/Bosses are smoothed more strongly to avoid oscillation.
            val targetX = (dx / (w * 0.30f)).coerceIn(-0.86f, 0.86f)
            val targetY = (dy / (h * 0.31f)).coerceIn(-0.90f, 0.90f)
            val steerInterval = if (isExpTarget) 300L else 760L
            val oldWeight = if (isExpTarget) 0.36f else 0.78f
            val newWeight = 1f - oldWeight
            if (now - steeringUpdatedAt >= steerInterval) {
                val moveX = (lastMoveX * oldWeight + targetX * newWeight).coerceIn(-0.86f, 0.86f)
                val moveY = (lastMoveY * oldWeight + targetY * newWeight).coerceIn(-0.90f, 0.90f)
                lastMoveX = if (kotlin.math.abs(moveX) < 0.035f) 0f else moveX
                lastMoveY = if (kotlin.math.abs(moveY) < 0.035f) 0f else moveY
                desiredMoveX = lastMoveX
                desiredMoveY = lastMoveY
                steeringUpdatedAt = now
            }

            lastStatus = when (lastTargetKind) {
                "BOSS" -> "Podejście: BOSS • ${if (lastDetectedName.isBlank()) "cel" else lastDetectedName}"
                "METIN" -> "Podejście: METIN • ${if (lastDetectedName.isBlank()) "cel" else lastDetectedName}"
                else -> "Auto Łowy: najbliższy mob • ${if (lastDetectedName.isBlank()) "cel" else lastDetectedName}"
            }
            mainHandler.postDelayed({ farmTick() }, 500L)
        }
    }

    fun startBot() {
        if (running) return
        if (!enabled("farmbot") && !enabled("auto_boss", true) && !enabled("auto_exp", false)) {
            lastStatus = "Włącz Metiny, Bossy lub Auto EXP"
            return
        }
        missCount = 0
        searchStep = 0
        lastMoveX = 0f
        lastMoveY = 0f
        desiredMoveX = 0f
        desiredMoveY = 0f
        attackMode = false
        attackMisses = 0
        attackConfirmed = false
        attackSelectAttempts = 0
        selectedTargetBarVisible = false
        activeJoystickStroke = null
        resetProgressWatch()
        previousSceneSample = null
        lastSceneMotion = 100f
        avoidPhase = 0
        avoidPhaseTicks = 0
        avoidDirection = 1f
        avoidAttempts = 0
        patrolStep = 0
        patrolStepUntil = 0L
        ignoredTargetX = 0
        ignoredTargetY = 0
        ignoreTargetUntil = 0L
        steeringUpdatedAt = 0L
        bestApproachError = Float.MAX_VALUE
        lastMeaningfulProgressAt = 0L
        skillLastTapAt.fill(0L)
        lastHpPotionAt = 0L
        lastMpPotionAt = 0L
        lastReviveScanAt = 0L
        currentMapId = prefs().getString("last_map_id", currentMapId) ?: currentMapId
        currentMapLabel = prefs().getString("last_map_label", currentMapLabel) ?: currentMapLabel
        lastSafeHeadingBucket = -1
        clearCoreTarget()
        clearExpLock()
        coreState = CoreState.SEARCH_ROUTE
        lastDetectedName = ""
        lastDetectedLabelBottom = 0
        lastDetectedLabelHeight = 0
        running = true
        // Keep the control panel from covering world labels or the pickup hand button while
        // automation is active. The compact EB button remains available to reopen it instantly.
        setPanelVisible(false)
        lastStatus = if (enabled("auto_exp", false)) "ElderBot: AUTO ŁOWY — szukam najbliższych mobów..." else "ElderBot: SZUKAM METINA..."
        setOverlaySymbol("■")
        mainHandler.post(movementLoop)
        farmTick()
    }

    fun stopBot() {
        running = false
        attackMode = false
        attackMisses = 0
        attackConfirmed = false
        attackSelectAttempts = 0
        selectedTargetBarVisible = false
        missCount = 0
        lastMoveX = 0f
        lastMoveY = 0f
        desiredMoveX = 0f
        desiredMoveY = 0f
        resetProgressWatch()
        previousSceneSample = null
        avoidPhase = 0
        avoidPhaseTicks = 0
        avoidAttempts = 0
        patrolStepUntil = 0L
        ignoreTargetUntil = 0L
        steeringUpdatedAt = 0L
        clearCoreTarget()
        clearExpLock()
        coreState = CoreState.STOPPED
        lastDetectedName = ""
        lastDetectedLabelBottom = 0
        lastDetectedLabelHeight = 0
        mainHandler.removeCallbacks(attackLoop)
        mainHandler.removeCallbacks(movementLoop)
        mainHandler.removeCallbacksAndMessages(null)
        finishJoystickGesture()
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
