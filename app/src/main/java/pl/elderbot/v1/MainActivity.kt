package pl.elderbot.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Sprawdzanie usługi…"
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "ElderBot V2"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 12)
        }

        val info = TextView(this).apply {
            text = "V2: wykrywanie Metina na podstawie nazwy widocznej na ekranie (OCR) + położenia obiektu.\n\nNa tym etapie bot tylko analizuje obraz. Nie porusza postacią i nie atakuje."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(24, 8, 24, 24)
        }

        val openSettings = Button(this).apply {
            text = "1. Włącz usługę dostępności"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val screenshot = Button(this).apply {
            text = "2. Zrób zrzut ekranu gry"
            setOnClickListener {
                val service = BotAccessibilityService.instance
                if (service == null) {
                    status.text = "Najpierw włącz usługę dostępności."
                } else {
                    service.captureAndSaveScreenshot { _, message -> status.text = message }
                }
            }
        }

        val detect = Button(this).apply {
            text = "3. TEST V2 — WYKRYJ METINA"
            setOnClickListener {
                val service = BotAccessibilityService.instance
                if (service == null) {
                    status.text = "Najpierw włącz usługę dostępności."
                } else {
                    service.captureAndDetectMetin { _, message -> status.text = message }
                }
            }
        }

        val tapTest = Button(this).apply {
            text = "3. Testowy dotyk na środku ekranu"
            setOnClickListener { BotAccessibilityService.instance?.tapScreenCentre() ?: run { status.text = "Najpierw włącz usługę dostępności." } }
        }

        val start = Button(this).apply {
            text = "START"
            setOnClickListener { BotAccessibilityService.instance?.startBot() ?: run { status.text = "Najpierw włącz usługę dostępności." } }
        }

        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener { BotAccessibilityService.instance?.stopBot() ?: run { status.text = "Usługa nie jest aktywna." } }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
            addView(title)
            addView(info)
            addView(openSettings)
            addView(screenshot)
            addView(detect)
            addView(tapTest)
            addView(start)
            addView(stop)
            addView(status)
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        status.text = BotAccessibilityService.instance?.status() ?: "Usługa dostępności jest wyłączona."
    }
}
