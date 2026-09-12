package pl.elderbot.v1

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    private fun panelBg(stroke: Int = Color.rgb(65, 73, 90)): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.rgb(22, 25, 32))
            cornerRadius = 22f
            setStroke(2, stroke)
        }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(Color.rgb(145, 174, 255))
        setPadding(14, 18, 14, 6)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 14, 18)
        window.navigationBarColor = Color.rgb(12, 14, 18)
        val prefs = getSharedPreferences("elderbot_settings", MODE_PRIVATE)

        fun option(label: String, key: String, defaultValue: Boolean = true): Switch =
            Switch(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean(key, defaultValue)
                setPadding(18, 8, 18, 8)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    BotAccessibilityService.instance?.refreshOverlay()
                }
            }

        val title = TextView(this).apply {
            text = "ELDERBOT MOBILE"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 24, 18, 2)
        }
        val sub = TextView(this).apply {
            text = "V0.14 • MULTI-MAP NAV • pamięć przeszkód i trasy per mapa"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(18, 2, 18, 18)
        }

        val modules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(8, 4, 8, 10)
            addView(section("AUTOMATYZACJA"))
            addView(option("Farmbot", "farmbot"))
            addView(option("Pickup", "pickup"))
            addView(option("Auto Skills", "auto_skills"))
            addView(option("Auto Potions HP / MP", "auto_potions"))
            addView(option("Auto Revive", "auto_revive"))
            addView(section("CELE"))
            addView(TextView(this@MainActivity).apply {
                text = "Metiny: aktywne\nBossy / Moby: przygotowane do kolejnego etapu"
                textSize = 14f; setTextColor(Color.LTGRAY); setPadding(18, 4, 18, 10)
            })
            addView(section("MODUŁY ROZSZERZEŃ"))
            addView(TextView(this@MainActivity).apply {
                text = "⛏ Auto Mining — przygotowane miejsce w panelu\n🎣 Auto Fishing — przygotowane miejsce w panelu"
                textSize = 14f; setTextColor(Color.GRAY); setPadding(18, 4, 18, 12)
            })
        }

        status = TextView(this).apply {
            text = "Sprawdzanie usługi…"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = panelBg(Color.rgb(70, 100, 165))
            setPadding(18, 14, 18, 14)
        }

        val openSettings = Button(this).apply {
            text = "WŁĄCZ / SPRAWDŹ ACCESSIBILITY"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val overlayInfo = TextView(this).apply {
            text = "Po włączeniu usługi w grze zobaczysz pływający przycisk EB. Dotknij go, aby rozwinąć panel bez wychodzenia z ElderMT2."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(12, 8, 12, 12)
        }
        val start = Button(this).apply {
            text = "START FARMBOT"
            setOnClickListener {
                BotAccessibilityService.instance?.startBot()
                    ?: run { status.text = "Najpierw włącz usługę dostępności." }
                status.postDelayed({ status.text = BotAccessibilityService.instance?.status() ?: status.text }, 300)
            }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                BotAccessibilityService.instance?.stopBot()
                    ?: run { status.text = "Usługa nie jest aktywna." }
                status.postDelayed({ status.text = BotAccessibilityService.instance?.status() ?: status.text }, 200)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 24)
            setBackgroundColor(Color.rgb(12, 14, 18))
            addView(title)
            addView(sub)
            addView(modules)
            addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
            addView(status)
            addView(overlayInfo)
            addView(openSettings)
            addView(start)
            addView(stop)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 14, 18))
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = BotAccessibilityService.instance?.status()
            ?: "Usługa dostępności jest wyłączona."
    }
}
