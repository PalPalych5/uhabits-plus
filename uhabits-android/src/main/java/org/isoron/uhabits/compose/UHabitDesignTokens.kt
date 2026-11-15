/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Candidate visual style presets for future UHabit Next evolution.
 *
 * Phase 0 Note:
 * - Only [MINIMAL_REFINED] is active in production.
 * - No new preference keys or style-switching UI are introduced in Phase 0.
 * - In future migrations, explicit user appearance overrides ([UHabitComposeColors.cardCornerRadiusDp],
 *   [UHabitComposeColors.habitsCardCornerRadiusDp], [UHabitComposeColors.statisticsCardCornerRadiusDp],
 *   [UHabitComposeColors.showCardBorders], brightness mode, and accent/per-habit colors) remain
 *   authoritative over style defaults.
 * - Legacy View/Canvas components (e.g., KMP charts, custom Canvas checkmarks) do not restyle
 *   automatically from Compose tokens alone; they integrate through explicit bridges such as
 *   [HarmonizedChartTheme] and `SettingsThemePalette`.
 */
enum class UHabitVisualStyleCandidate {
    MINIMAL_REFINED,
    M3_EXPRESSIVE,
    NEO_BRUTALISM
}

/** Semantic spacing scale for UHabit Next Compose surfaces. */
@Immutable
data class UHabitSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val cardHorizontalPadding: Dp = 16.dp,
    val cardVerticalPadding: Dp = 14.dp
)

/** Semantic shape scale and user-customizable card corner helpers. */
@Immutable
data class UHabitShapes(
    val xs: Shape = RoundedCornerShape(6.dp),
    val sm: Shape = RoundedCornerShape(8.dp),
    val md: Shape = RoundedCornerShape(12.dp),
    val lg: Shape = RoundedCornerShape(16.dp),
    val xl: Shape = RoundedCornerShape(24.dp)
) {
    /**
     * Resolves a card or container shape from a user-configured corner radius in dp.
     * Preserves 0dp as a crisp [RectangleShape].
     */
    fun cardShape(cornerRadiusDp: Int): Shape =
        if (cornerRadiusDp <= 0) RectangleShape else RoundedCornerShape(cornerRadiusDp.dp)

    fun cardShape(cornerRadius: Dp): Shape =
        if (cornerRadius <= 0.dp) RectangleShape else RoundedCornerShape(cornerRadius)

    fun toMaterialShapes(defaultCardRadiusDp: Int = 12): Shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = if (defaultCardRadiusDp <= 0) RoundedCornerShape(0.dp) else RoundedCornerShape(defaultCardRadiusDp.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp)
    )
}

/** Elevation, border, and surface resolution rules preserving Light, Dark, and AMOLED semantics. */
object UHabitElevationAndBorders {
    const val AMOLED_BORDERED_CARD_SURFACE_ARGB: Int = 0xFF0C0E12.toInt()
    const val AMOLED_CARD_BORDER_ARGB: Int = 0xFF1C2028.toInt()
    const val AMOLED_DIVIDER_ARGB: Int = 0xFF1C1C1C.toInt()
    const val DEFAULT_DANGER_ARGB: Int = 0xFFEF5350.toInt()
    const val DEFAULT_WARNING_ARGB: Int = 0xFFD97706.toInt()

    val flatElevation: Dp = 0.dp
    val lowElevation: Dp = 1.dp
    val modalElevation: Dp = 4.dp

    val hairlineBorderWidth: Dp = 1.dp
    val selectedBorderWidth: Dp = 1.5.dp

    /**
     * Resolves the container surface color for section/statistics/form cards.
     *
     * Rules:
     * - When [UHabitComposeColors.showCardBorders] is false (card decoration disabled),
     *   AMOLED mode stays pure black (`#000000`) and Light/Dark use the screen background —
     *   never forcing a gray elevated surface (`#0C0E12`) when borders are off.
     * - When [UHabitComposeColors.showCardBorders] is true, AMOLED uses `#0C0E12` and
     *   Light/Dark use [UHabitComposeColors.surface].
     */
    fun resolveSectionCardSurface(colors: UHabitComposeColors): Color = when {
        colors.isClassicSectionLayout -> if (colors.isPureBlack) Color.Black else Color(colors.surface)
        colors.isPureBlack -> if (colors.showCardBorders) Color(AMOLED_BORDERED_CARD_SURFACE_ARGB) else Color.Black
        colors.showCardBorders -> Color(colors.surface)
        colors.isDark -> Color(colors.surface)
        else -> Color.White
    }

    /**
     * Resolves the container surface color for interactive list rows (e.g. Habit List cards).
     *
     * Rules:
     * - Interactive selection feedback (`isSelected == true`) always shows a subtle accent tint,
     *   even when persistent card decoration (`showCardBorders`) is disabled.
     * - When `showCardBorders == false` and not selected, returns [Color.Transparent] so rows sit
     *   flush on the window background (including `#000000` in AMOLED).
     * - When `showCardBorders == true`, AMOLED uses `#0C0E12` and Light/Dark use `surface`.
     */
    fun resolveRowCardSurface(
        colors: UHabitComposeColors,
        isSelected: Boolean = false
    ): Color = when {
        isSelected -> Color(colors.accent).copy(alpha = 0.16f)
        !colors.showCardBorders -> Color.Transparent
        colors.isPureBlack -> Color(AMOLED_BORDERED_CARD_SURFACE_ARGB)
        colors.isDark -> Color(colors.surface)
        else -> Color.White
    }

    /**
     * Resolves the border stroke color for section/statistics/form cards when borders are enabled.
     */
    fun resolveSectionCardBorderColor(colors: UHabitComposeColors): Color = when {
        colors.isPureBlack -> Color(AMOLED_CARD_BORDER_ARGB)
        colors.isDark -> Color.White.copy(alpha = 0.08f)
        else -> Color(colors.border).copy(alpha = 0.22f)
    }

    /**
     * Resolves the border stroke color for interactive list rows, distinguishing selection feedback
     * from persistent card outlines.
     */
    fun resolveRowCardBorderColor(
        colors: UHabitComposeColors,
        isSelected: Boolean = false
    ): Color = when {
        isSelected -> Color(colors.accent)
        colors.isPureBlack -> Color(AMOLED_CARD_BORDER_ARGB)
        else -> Color(colors.border)
    }

    /** Resolves intra-card hairline divider color across Light, Dark, and AMOLED. */
    fun resolveDividerColor(colors: UHabitComposeColors): Color =
        if (colors.isPureBlack) {
            Color(AMOLED_DIVIDER_ARGB)
        } else {
            Color(colors.divider).copy(alpha = 0.35f)
        }

    /** Resolves semantic error/danger color with a safe fallback when `colors.danger == 0`. */
    fun resolveDangerColor(colors: UHabitComposeColors): Color =
        Color(if (colors.danger != 0) colors.danger else DEFAULT_DANGER_ARGB)

    /** Resolves semantic warning color with a safe fallback when `colors.warning == 0`. */
    fun resolveWarningColor(colors: UHabitComposeColors): Color =
        Color(if (colors.warning != 0) colors.warning else DEFAULT_WARNING_ARGB)
}

/** Motion timing and spring specifications for UHabit Next Compose interactions. */
@Immutable
data class UHabitMotion(
    val fastDurationMs: Int = 150,
    val standardDurationMs: Int = 180,
    val emphasizedDurationMs: Int = 240,
    val standardEasing: Easing = FastOutSlowInEasing
) {
    fun <T> fastTween(): TweenSpec<T> = tween(durationMillis = fastDurationMs, easing = standardEasing)
    fun <T> standardTween(): TweenSpec<T> = tween(durationMillis = standardDurationMs, easing = standardEasing)
    fun <T> defaultSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

/**
 * Calibrated Material 3 [Typography] for UHabit Next (Minimal & Refined).
 * Preserves existing dense, readable mobile sizing rather than stock oversized M3 headers.
 */
object UHabitTypography {
    fun fromColors(colors: UHabitComposeColors): Typography {
        val primaryText = Color(colors.foreground)
        val secondaryText = Color(colors.secondary)
        return Typography(
            displayMedium = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 42.sp,
                letterSpacing = (-0.5).sp,
                color = primaryText
            ),
            headlineSmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
                color = primaryText
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
                color = primaryText
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.15.sp,
                color = primaryText
            ),
            titleSmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                letterSpacing = 0.15.sp,
                color = primaryText
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                letterSpacing = 0.1.sp,
                color = primaryText
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
                color = primaryText
            ),
            bodySmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                letterSpacing = 0.15.sp,
                color = secondaryText
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.1.sp,
                color = primaryText
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.2.sp,
                color = secondaryText
            ),
            labelSmall = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.4.sp,
                color = secondaryText
            )
        )
    }
}

/**
 * Aggregated design tokens exposed through [LocalUHabitDesignTokens] inside [UHabitComposeTheme].
 */
@Immutable
data class UHabitDesignTokens(
    val styleCandidate: UHabitVisualStyleCandidate = UHabitVisualStyleCandidate.MINIMAL_REFINED,
    val spacing: UHabitSpacing = UHabitSpacing(),
    val shapes: UHabitShapes = UHabitShapes(),
    val motion: UHabitMotion = UHabitMotion()
)

val LocalUHabitDesignTokens = staticCompositionLocalOf { UHabitDesignTokens() }

/**
 * Maps [UHabitComposeColors] to an authoritative Material 3 [ColorScheme] while preserving
 * Light, Dark, and pure-black AMOLED surface semantics.
 */
fun UHabitComposeColors.toMaterialColorScheme(): ColorScheme {
    val bg = Color(background)
    val fg = Color(foreground)
    val sec = Color(secondary)
    val acc = Color(accent)
    val surf = Color(surface)
    val surfVar = Color(surfaceVariant)
    val brd = Color(border)
    val div = Color(divider)
    val err = UHabitElevationAndBorders.resolveDangerColor(this)
    val onAcc = if ((1.05f / (acc.luminance() + 0.05f)) >= 4.5f) Color.White else Color(0xFF191C20)
    val accContainer = acc.copy(alpha = if (isDark) 0.20f else 0.14f)

    return if (isDark) {
        darkColorScheme(
            primary = acc,
            onPrimary = onAcc,
            primaryContainer = accContainer,
            onPrimaryContainer = acc,
            secondary = sec,
            onSecondary = bg,
            secondaryContainer = surfVar,
            onSecondaryContainer = fg,
            background = bg,
            onBackground = fg,
            surface = surf,
            onSurface = fg,
            surfaceVariant = surfVar,
            onSurfaceVariant = sec,
            surfaceContainerLowest = if (isPureBlack) Color.Black else bg,
            surfaceContainerLow = resolveSectionSurfaceColor(),
            surfaceContainer = surf,
            surfaceContainerHigh = surfVar,
            surfaceContainerHighest = surfVar,
            outline = brd,
            outlineVariant = div,
            error = err,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = acc,
            onPrimary = onAcc,
            primaryContainer = accContainer,
            onPrimaryContainer = acc,
            secondary = sec,
            onSecondary = Color.White,
            secondaryContainer = surfVar,
            onSecondaryContainer = fg,
            background = bg,
            onBackground = fg,
            surface = surf,
            onSurface = fg,
            surfaceVariant = surfVar,
            onSurfaceVariant = sec,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = resolveSectionSurfaceColor(),
            surfaceContainer = surf,
            surfaceContainerHigh = surfVar,
            surfaceContainerHighest = surfVar,
            outline = brd,
            outlineVariant = div,
            error = err,
            onError = Color.White
        )
    }
}
