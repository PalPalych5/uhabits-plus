package org.isoron.uhabits.activities.common.theme

import android.content.Context
import android.graphics.Color
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver

object MainTabsThemeBridge {
    fun resolve(context: Context): SettingsThemePalette {
        val app = context.applicationContext as? HabitsApplication
        val prefs = app?.component?.preferences
        return if (prefs != null) {
            SettingsThemePaletteResolver.resolve(context, prefs)
        } else {
            SettingsThemePalette(
                background = Color.WHITE,
                surface = Color.WHITE,
                surfaceVariant = 0xFFF3F4F6.toInt(),
                border = 0xFFD9DEE5.toInt(),
                divider = 0xFFE7EBF0.toInt(),
                onSurface = 0xFF191C20.toInt(),
                onSurfaceVariant = 0xFF626872.toInt(),
                accent = 0xFF68C37A.toInt(),
                isDark = false,
                isPureBlack = false
            )
        }
    }

    fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (Color.alpha(color) * alphaFraction).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
