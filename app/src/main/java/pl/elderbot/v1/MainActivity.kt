package pl.elderbot.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "ElderBot V0.8"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 12)
        }

        val info = TextView(this).apply {
            text = "Rdzeń farmienia: OCR Metina → podejście → atak → ponowne szukanie. W grze użyj małego przycisku ▶/■."
            textSize = 15f
            setPadding(24, 8, 24, 24)
        }

        status = TextView(this).apply {
            text = "Sprawdzanie usługi…"
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        val openSettings = Button(this).apply {
            text = "Włącz usługę dostępności"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val start = Button(this).apply {
            text = "START"
            setOnClickListener {
                BotAccessibilityService.instance?.startBot()
                    ?: run { status.text = "Najpierw włącz usługę dostępności." }
            }
        }

        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                BotAccessibilityService.instance?.stopBot()
                    ?: run { status.text = "Usługa nie jest aktywna." }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
            addView(title)
            addView(info)
            addView(openSettings)
            addView(start)
            addView(stop)
            addView(status)
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        status.text = BotAccessibilityService.instance?.status()
            ?: "Usługa dostępności jest wyłączona."
    }
}
