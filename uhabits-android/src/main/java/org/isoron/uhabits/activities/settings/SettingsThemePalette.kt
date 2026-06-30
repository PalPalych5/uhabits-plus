package org.isoron.uhabits.activities.settings

import android.content.Context
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.preferences.Preferences

/**
 * Shared palette data class containing theme colors resolved from user preferences.
 * This class is shared by the Settings screen (custom list and cards) and the app-wide CustomDialogs system.
 */
data class SettingsThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int = surface,
    val border: Int,
    val divider: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val accent: Int = onSurface,
    val isDark: Boolean,
    val isPureBlack: Boolean
)

object SettingsThemePaletteResolver {
    /**
     * Resolves the palette colors from the current preference context.
     * This resolver is shared by Settings and CustomDialogs to maintain style uniformity.
     */
    fun resolve(context: Context, prefs: Preferences): SettingsThemePalette {
        val isDark = AndroidThemeSwitcher(context, prefs).isNightMode
        val isPureBlack = isDark && prefs.isPureBlackEnabled

        return when {
            isPureBlack -> SettingsThemePalette(
                background = ContextCompat.getColor(context, R.color.theme_background_amoled),
                surface = ContextCompat.getColor(context, R.color.theme_surface_amoled),
                surfaceVariant = ContextCompat.getColor(context, R.color.theme_surface_variant_amoled),
                border = 0x14FFFFFF,
                divider = 0x14FFFFFF,
                onSurface = ContextCompat.getColor(context, R.color.theme_on_surface_amoled),
                onSurfaceVariant = ContextCompat.getColor(context, R.color.theme_on_surface_variant_amoled),
                accent = AccentColorManager.getAccentColor(context, prefs),
                isDark = true,
                isPureBlack = true
            )
            isDark -> SettingsThemePalette(
                background = ContextCompat.getColor(context, R.color.theme_background_dark),
                surface = ContextCompat.getColor(context, R.color.theme_surface_dark),
                surfaceVariant = ContextCompat.getColor(context, R.color.theme_surface_variant_dark),
                border = 0x1FFFFFFF,
                divider = 0xFF303540.toInt(),
                onSurface = ContextCompat.getColor(context, R.color.theme_on_surface_dark),
                onSurfaceVariant = ContextCompat.getColor(context, R.color.theme_on_surface_variant_dark),
                accent = AccentColorManager.getAccentColor(context, prefs),
                isDark = true,
                isPureBlack = false
            )
            else -> SettingsThemePalette(
                background = ContextCompat.getColor(context, R.color.theme_background_light),
                surface = ContextCompat.getColor(context, R.color.theme_surface_light),
                surfaceVariant = ContextCompat.getColor(context, R.color.theme_surface_variant_light),
                border = 0xFFD9DEE5.toInt(),
                divider = 0xFFE7EBF0.toInt(),
                onSurface = ContextCompat.getColor(context, R.color.theme_on_surface_light),
                onSurfaceVariant = ContextCompat.getColor(context, R.color.theme_on_surface_variant_light),
                accent = AccentColorManager.getAccentColor(context, prefs),
                isDark = false,
                isPureBlack = false
            )
        }
    }
}
