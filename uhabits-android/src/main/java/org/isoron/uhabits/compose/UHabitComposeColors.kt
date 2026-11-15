/* Copyright (C) 2026 UHabit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import org.isoron.uhabits.activities.settings.SettingsThemePalette

/**
 * Semantic colors and appearance settings supplied by the active UHabit View theme, including AMOLED.
 *
 * Serves as the single authoritative bridge between `Preferences` / [SettingsThemePalette] and
 * Jetpack Compose (`MaterialTheme` + [LocalUHabitComposeColors]).
 */
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
    val isPureBlack: Boolean = false,
    val cardCornerRadiusDp: Int = 8,
    val showCardBorders: Boolean = true,
    val habitsCardCornerRadiusDp: Int = cardCornerRadiusDp,
    val statisticsCardCornerRadiusDp: Int = cardCornerRadiusDp,
    val isClassicSectionLayout: Boolean = false,
    val dialogSurface: Int = surface,
    val dialogBorder: Int = border
) {
    val backgroundColor: Color get() = Color(background)
    val foregroundColor: Color get() = Color(foreground)
    val secondaryColor: Color get() = Color(secondary)
    val accentColor: Color get() = Color(accent)
    val surfaceColor: Color get() = Color(surface)
    val surfaceVariantColor: Color get() = Color(surfaceVariant)
    val borderColor: Color get() = Color(border)
    val dividerColor: Color get() = Color(divider)
    val dialogSurfaceColor: Color get() = Color(dialogSurface)
    val dialogBorderColor: Color get() = Color(dialogBorder)
    val dangerColor: Color get() = UHabitElevationAndBorders.resolveDangerColor(this)
    val warningColor: Color get() = UHabitElevationAndBorders.resolveWarningColor(this)

    /** Resolves the surface color for section/statistics/form cards, respecting `showCardBorders` and AMOLED. */
    fun resolveSectionSurfaceColor(): Color =
        UHabitElevationAndBorders.resolveSectionCardSurface(this)

    /** Resolves the surface color for interactive list rows, distinguishing `isSelected` from `showCardBorders`. */
    fun resolveRowSurfaceColor(isSelected: Boolean = false): Color =
        UHabitElevationAndBorders.resolveRowCardSurface(this, isSelected = isSelected)

    /** Resolves the border color for section/statistics/form cards. */
    fun resolveSectionBorderColor(): Color =
        UHabitElevationAndBorders.resolveSectionCardBorderColor(this)

    /** Resolves the border color for interactive list rows. */
    fun resolveRowBorderColor(isSelected: Boolean = false): Color =
        UHabitElevationAndBorders.resolveRowCardBorderColor(this, isSelected = isSelected)

    /** Resolves the intra-card hairline divider color. */
    fun resolveDividerColor(): Color =
        UHabitElevationAndBorders.resolveDividerColor(this)

    /** Resolves the active card [Shape] from [cardCornerRadiusDp] (0dp -> RectangleShape). */
    fun resolveCardShape(radiusDp: Int = cardCornerRadiusDp): Shape =
        UHabitShapes().cardShape(radiusDp)

    companion object {
        fun fromPalette(
            palette: SettingsThemePalette,
            danger: Int = 0,
            warning: Int = 0,
            cardCornerRadiusDp: Int = palette.habitsCardCornerRadius,
            showCardBorders: Boolean = palette.showHabitCardBorders
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
            isPureBlack = palette.isPureBlack,
            cardCornerRadiusDp = cardCornerRadiusDp,
            showCardBorders = showCardBorders,
            habitsCardCornerRadiusDp = palette.habitsCardCornerRadius,
            statisticsCardCornerRadiusDp = palette.statisticsCardCornerRadius,
            isClassicSectionLayout = palette.isClassicSectionLayout,
            dialogSurface = palette.dialogSurface,
            dialogBorder = palette.dialogBorder
        )
    }
}

val LocalUHabitComposeColors = staticCompositionLocalOf<UHabitComposeColors> {
    error("UHabitComposeTheme is required")
}

/**
 * Authoritative Compose theme wrapper for UHabit Next.
 *
 * Bridges [UHabitComposeColors] and [UHabitDesignTokens] into Material 3 [MaterialTheme]
 * (`colorScheme`, `shapes`, `typography`) while maintaining full backward compatibility with
 * [LocalUHabitComposeColors].
 */
@Composable
fun UHabitComposeTheme(
    colors: UHabitComposeColors,
    tokens: UHabitDesignTokens = remember { UHabitDesignTokens() },
    content: @Composable () -> Unit
) {
    val colorScheme = remember(colors) { colors.toMaterialColorScheme() }
    val typography = remember(colors.foreground, colors.secondary) {
        UHabitTypography.fromColors(colors)
    }
    val shapes = remember(colors.cardCornerRadiusDp, tokens.shapes) {
        tokens.shapes.toMaterialShapes(defaultCardRadiusDp = colors.cardCornerRadiusDp)
    }

    CompositionLocalProvider(
        LocalUHabitComposeColors provides colors,
        LocalUHabitDesignTokens provides tokens
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography,
            content = content
        )
    }
}
