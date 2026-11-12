/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.isoron.uhabits.core.ui.views.Theme

/**
 * Delegating KMP [Theme] that harmonizes `cardBackgroundColor` with the surrounding Compose
 * container surface so embedded `AndroidDataView` charts (`HistoryChart`, `BarChart`) render
 * without mismatched rectangular cutouts in Light, Dark, or AMOLED modes.
 *
 * Note: Legacy View/Canvas charts do not automatically read Compose `MaterialTheme` tokens;
 * they require this explicit bridge or surface color setter (`ScoreChart.setSurfaceColors`).
 */
open class HarmonizedChartTheme(
    private val delegate: Theme,
    override val cardBackgroundColor: org.isoron.platform.gui.Color
) : Theme() {
    override val appBackgroundColor get() = delegate.appBackgroundColor
    override val headerBackgroundColor get() = delegate.headerBackgroundColor
    override val headerBorderColor get() = delegate.headerBorderColor
    override val headerTextColor get() = delegate.headerTextColor
    override val highContrastTextColor get() = delegate.highContrastTextColor
    override val itemBackgroundColor get() = delegate.itemBackgroundColor
    override val lowContrastTextColor get() = delegate.lowContrastTextColor
    override val mediumContrastTextColor get() = delegate.mediumContrastTextColor
    override val statusBarBackgroundColor get() = delegate.statusBarBackgroundColor
    override val toolbarBackgroundColor get() = delegate.toolbarBackgroundColor
    override val toolbarColor get() = delegate.toolbarColor
    override fun color(paletteIndex: Int): org.isoron.platform.gui.Color = delegate.color(paletteIndex)
}

/**
 * Creates a [HarmonizedChartTheme] whose `cardBackgroundColor` matches the resolved section
 * surface of [colors].
 */
fun harmonizeChartTheme(
    delegate: Theme,
    colors: UHabitComposeColors,
    surfaceColorOverride: Color = colors.resolveSectionSurfaceColor()
): HarmonizedChartTheme = HarmonizedChartTheme(
    delegate = delegate,
    cardBackgroundColor = org.isoron.platform.gui.Color(surfaceColorOverride.toArgb())
)

/**
 * Remembers a [HarmonizedChartTheme] synchronized with [baseTheme] and [surfaceColor].
 */
@Composable
fun rememberHarmonizedChartTheme(
    baseTheme: Theme,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    surfaceColor: Color = colors.resolveSectionSurfaceColor()
): HarmonizedChartTheme = remember(baseTheme, surfaceColor) {
    HarmonizedChartTheme(
        delegate = baseTheme,
        cardBackgroundColor = org.isoron.platform.gui.Color(surfaceColor.toArgb())
    )
}
