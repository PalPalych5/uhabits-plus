/* Copyright (C) 2026 UHabit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import org.isoron.uhabits.activities.settings.SettingsThemePalette

/** Semantic colors supplied by the active UHabit View theme, including AMOLED. */
data class UHabitComposeColors(
    val background: Int,
    val foreground: Int,
    val secondary: Int,
    val accent: Int,
    val surface: Int = background,
    val surfaceVariant: Int = surface,
    val border: Int = secondary,
    val divider: Int = border,
    val danger: Int = 0,
    val warning: Int = 0,
    val isDark: Boolean = false,
    val isPureBlack: Boolean = false
) {
    companion object {
        fun fromPalette(
            palette: SettingsThemePalette,
            danger: Int = 0,
            warning: Int = 0
        ): UHabitComposeColors = UHabitComposeColors(
            background = palette.background,
            foreground = palette.onSurface,
            secondary = palette.onSurfaceVariant,
            accent = palette.accent,
            surface = palette.surface,
            surfaceVariant = palette.surfaceVariant,
            border = palette.border,
            divider = palette.divider,
            danger = danger,
            warning = warning,
            isDark = palette.isDark,
            isPureBlack = palette.isPureBlack
        )
    }
}

val LocalUHabitComposeColors = staticCompositionLocalOf<UHabitComposeColors> {
    error("UHabitComposeTheme is required")
}

@Composable
fun UHabitComposeTheme(colors: UHabitComposeColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUHabitComposeColors provides colors, content = content)
}
