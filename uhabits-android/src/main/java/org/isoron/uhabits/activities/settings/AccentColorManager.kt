package org.isoron.uhabits.activities.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.ColorPickerUtils
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.utils.toPaletteColor

object AccentColorManager {
    fun getAccentColorString(prefs: Preferences): String {
        return prefs.accentColor
    }

    fun setAccentColorString(prefs: Preferences, value: String) {
        prefs.accentColor = value
    }

    fun getAccentColorName(context: Context, prefs: Preferences): String {
        val color = getAccentColor(context, prefs)
        return ColorPickerUtils.toHex(color)
    }

    fun toPaletteColor(value: String): PaletteColor {
        if (value.startsWith("preset:")) {
            val name = value.removePrefix("preset:")
            val index = when (name) {
                "red" -> 0
                "orange" -> 2
                "green" -> 7
                "teal" -> 8
                "blue" -> 11
                "purple" -> 14
                "pink" -> 15
                else -> 11
            }
            return PaletteColor(index)
        } else if (value.startsWith("custom:")) {
            val hex = value.removePrefix("custom:")
            val parsed = ColorPickerUtils.parseHex(hex) ?: Color.BLUE
            return PaletteColor(parsed)
        }
        return PaletteColor(11)
    }

    fun fromPaletteColor(paletteColor: PaletteColor): String {
        return when (paletteColor.paletteIndex) {
            0 -> "preset:red"
            2 -> "preset:orange"
            7 -> "preset:green"
            8 -> "preset:teal"
            11 -> "preset:blue"
            14 -> "preset:purple"
            15 -> "preset:pink"
            else -> {
                val hex = paletteColor.toCsvColor()
                "custom:$hex"
            }
        }
    }

    fun getAccentColor(context: Context, prefs: Preferences): Int {
        val themeSwitcher = AndroidThemeSwitcher(context, prefs)
        val isNight = themeSwitcher.isNightMode
        val isPureBlack = prefs.isPureBlackEnabled
        val theme = when {
            isNight && isPureBlack -> org.isoron.uhabits.core.ui.views.PureBlackTheme()
            isNight -> org.isoron.uhabits.core.ui.views.DarkTheme()
            else -> org.isoron.uhabits.core.ui.views.LightTheme()
        }
        val valueString = getAccentColorString(prefs)
        
        return if (valueString.startsWith("preset:")) {
            val paletteColor = toPaletteColor(valueString)
            theme.color(paletteColor).toInt()
        } else if (valueString.startsWith("custom:")) {
            val hex = valueString.removePrefix("custom:")
            ColorPickerUtils.parseHex(hex) ?: theme.color(11).toInt()
        } else {
            theme.color(11).toInt()
        }
    }

    fun getAccentColorStateList(context: Context, prefs: Preferences): ColorStateList {
        val accentColor = getAccentColor(context, prefs)
        val themeSwitcher = AndroidThemeSwitcher(context, prefs)
        val normalColor = ContextCompat.getColor(
            context,
            if (themeSwitcher.isNightMode) R.color.grey_500 else R.color.grey_600
        )
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        )
        val colors = intArrayOf(
            accentColor,
            normalColor,
            accentColor,
            normalColor
        )
        return ColorStateList(states, colors)
    }

    fun tintSwitch(switchView: SwitchCompat, context: Context, prefs: Preferences) {
        val accentColor = getAccentColor(context, prefs)
        val themeSwitcher = AndroidThemeSwitcher(context, prefs)
        val isDark = themeSwitcher.isNightMode

        val thumbChecked = accentColor
        val thumbUnchecked = ContextCompat.getColor(context, if (isDark) R.color.grey_400 else R.color.grey_300)

        val thumbStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(thumbChecked, thumbUnchecked)
        )

        val trackChecked = Color.argb(
            76,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        val trackUnchecked = ContextCompat.getColor(context, if (isDark) R.color.grey_700 else R.color.grey_400)

        val trackStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(trackChecked, trackUnchecked)
        )

        switchView.thumbTintList = thumbStateList
        switchView.trackTintList = trackStateList
    }

    fun tintSegment(segmentView: TextView, isSelected: Boolean, context: Context, prefs: Preferences) {
        val themeSwitcher = AndroidThemeSwitcher(context, prefs)
        val isDark = themeSwitcher.isNightMode
        if (isSelected) {
            val accentColor = getAccentColor(context, prefs)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    6f,
                    context.resources.displayMetrics
                )
                setColor(accentColor)
            }
            segmentView.background = drawable
            segmentView.setTextColor(ColorPickerUtils.onColor(accentColor))
        } else {
            segmentView.background = null
            val normalColor = ContextCompat.getColor(context, if (isDark) R.color.grey_400 else R.color.grey_600)
            segmentView.setTextColor(normalColor)
        }
    }
}
