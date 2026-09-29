package org.isoron.uhabits.activities.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher

/** Local, readable explanation of the aggregate statistics report. */
class StatisticsHelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = (applicationContext as HabitsApplication).component.preferences
        AndroidThemeSwitcher(this, prefs).apply()
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val palette = SettingsThemePaletteResolver.resolve(this, prefs)
        window.statusBarColor = palette.background
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !palette.isDark
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.background)
            minimumHeight = dp(56)
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(palette.onSurface)
            contentDescription = getString(R.string.statistics_help_back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.statistics_local_help_title)
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.onSurface)
        })
        root.addView(toolbar)
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val sections = listOf(
            R.string.statistics_help_completion_title to R.string.statistics_help_completion_body,
            R.string.statistics_help_fully_closed_title to R.string.statistics_help_fully_closed_body,
            R.string.statistics_help_stability_title to R.string.statistics_help_stability_body
        )
        sections.forEach { (title, description) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    setColor(palette.surface)
                    cornerRadius = dp(16).toFloat()
                }
            }
            card.addView(TextView(this).apply {
                text = getString(title)
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(palette.onSurface)
            })
            card.addView(TextView(this).apply {
                text = getString(description)
                textSize = 15f
                setTextColor(palette.onSurfaceVariant)
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        val cardNote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(16).toFloat()
            }
        }
        cardNote.addView(TextView(this).apply {
            text = getString(R.string.statistics_help_period_goals_note)
            textSize = 14f
            setTextColor(palette.onSurfaceVariant)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        body.addView(cardNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
