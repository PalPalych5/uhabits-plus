/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.content.Context
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.models.PaletteColor

object TaskThemeUtils {
    fun resolvePaletteColor(context: Context, color: PaletteColor?, defaultColor: Int): Int {
        if (color == null) return defaultColor
        val app = context.applicationContext as HabitsApplication
        val switcher = AndroidThemeSwitcher(context, app.component.preferences)
        switcher.apply()
        return switcher.currentTheme.color(color).toInt()
    }

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    fun dpInt(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
