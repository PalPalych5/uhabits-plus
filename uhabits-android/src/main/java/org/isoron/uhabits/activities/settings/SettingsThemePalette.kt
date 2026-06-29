package org.isoron.uhabits.activities.settings

import android.content.Context
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.preferences.Preferences

data class SettingsThemePalette(
    val background: Int,
    val surface: Int,
    val border: Int,
    val divider: Int,
    val isDark: Boolean,
    val isPureBlack: Boolean
)

object SettingsThemePaletteResolver {
    fun resolve(context: Context, prefs: Preferences): SettingsThemePalette {
        val isDark = AndroidThemeSwitcher(context, prefs).isNightMode
        val isPureBlack = isDark && prefs.isPureBlackEnabled

        return when {
            isPureBlack -> SettingsThemePalette(
                background = ContextCompat.getColor(context, R.color.theme_background_amoled),
                surface = ContextCompat.getColor(context, R.color.theme_surface_amoled),
                border = ContextCompat.getColor(context, R.color.theme_border_amoled),
                divider = 0x1AFFFFFF,
                isDark = true,
                isPureBlack = true
            )
            isDark -> SettingsThemePalette(
                background = ContextCompat.getColor(context, R.color.theme_background_dark),
                surface = ContextCompat.getColor(context, R.color.theme_surface_dark),
                border = ContextCompat.getColor(context, R.color.theme_border_dark),
                divider = 0xFF2A2E37.toInt(),
                isDark = true,
                isPureBlack = false
            )
            else -> SettingsThemePalette(
                background = ContextCompat.getColor(context, R.color.theme_background_light),
                surface = ContextCompat.getColor(context, R.color.theme_surface_light),
                border = ContextCompat.getColor(context, R.color.theme_border_light),
                divider = 0xFFEAECEF.toInt(),
                isDark = false,
                isPureBlack = false
            )
        }
    }
}
