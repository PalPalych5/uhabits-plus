/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.uhabits.activities.habits.show.views.HarmonizedTheme
import org.isoron.uhabits.activities.habits.show.views.resolveSectionSurfaceColor
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.PureBlackTheme
import org.junit.Test

class UHabitComposeComponentsContractTest {

    private fun buildColors(
        isDark: Boolean,
        isPureBlack: Boolean,
        showBorders: Boolean,
        radiusDp: Int = 12,
        accent: Int = 0xFF1E88E5.toInt(),
        danger: Int = 0,
        warning: Int = 0
    ): UHabitComposeColors {
        val palette = SettingsThemePalette(
            background = if (isPureBlack) 0xFF000000.toInt() else if (isDark) 0xFF121417.toInt() else 0xFFF7F8FA.toInt(),
            surface = if (isPureBlack) 0xFF0A0A0A.toInt() else if (isDark) 0xFF1A1D23.toInt() else 0xFFFFFFFF.toInt(),
            surfaceVariant = if (isPureBlack) 0xFF141414.toInt() else if (isDark) 0xFF232730.toInt() else 0xFFEEF1F5.toInt(),
            border = if (isDark) 0x1FFFFFFF else 0xFFD9DEE5.toInt(),
            divider = if (isDark) 0x14FFFFFF else 0x14000000,
            onSurface = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1C1E.toInt(),
            onSurfaceVariant = 0xFF9AA0A6.toInt(),
            accent = accent,
            isDark = isDark,
            isPureBlack = isPureBlack,
            habitsCardCornerRadius = radiusDp,
            statisticsCardCornerRadius = radiusDp,
            showHabitCardBorders = showBorders
        )
        return UHabitComposeColors.fromPalette(
            palette = palette,
            danger = danger,
            warning = warning,
            cardCornerRadiusDp = radiusDp,
            showCardBorders = showBorders
        )
    }

    @Test
    fun testHabitDetailsResolveSectionSurfaceColorDelegatesToSharedTokens() {
        for (isDark in listOf(false, true)) {
            for (isPureBlack in listOf(false, true)) {
                if (isPureBlack && !isDark) continue
                for (showBorders in listOf(false, true)) {
                    val colors = buildColors(isDark = isDark, isPureBlack = isPureBlack, showBorders = showBorders)
                    assertThat(
                        resolveSectionSurfaceColor(colors),
                        equalTo(colors.resolveSectionSurfaceColor())
                    )
                }
            }
        }
    }

    @Test
    fun testHarmonizedThemeAliasPreservesDelegatedChartProperties() {
        val base = PureBlackTheme()
        val colors = buildColors(isDark = true, isPureBlack = true, showBorders = true)
        val harmonized: HarmonizedTheme = harmonizeChartTheme(base, colors)

        assertThat(
            harmonized.cardBackgroundColor,
            equalTo(org.isoron.platform.gui.Color(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB))
        )
        assertThat(harmonized.highContrastTextColor, equalTo(base.highContrastTextColor))
        assertThat(harmonized.mediumContrastTextColor, equalTo(base.mediumContrastTextColor))
        assertThat(harmonized.lowContrastTextColor, equalTo(base.lowContrastTextColor))
        assertThat(harmonized.color(5), equalTo(base.color(5)))
    }

    @Test
    fun testDividerDangerAndWarningTokenResolution() {
        val defaultColors = buildColors(isDark = false, isPureBlack = false, showBorders = true)
        assertThat(
            defaultColors.dangerColor,
            equalTo(Color(UHabitElevationAndBorders.DEFAULT_DANGER_ARGB))
        )
        assertThat(
            defaultColors.warningColor,
            equalTo(Color(UHabitElevationAndBorders.DEFAULT_WARNING_ARGB))
        )

        val customColors = buildColors(
            isDark = true,
            isPureBlack = true,
            showBorders = true,
            danger = 0xFFD32F2F.toInt(),
            warning = 0xFFF59E0B.toInt()
        )
        assertThat(customColors.dangerColor, equalTo(Color(0xFFD32F2F.toInt())))
        assertThat(customColors.warningColor, equalTo(Color(0xFFF59E0B.toInt())))
        assertThat(
            customColors.resolveDividerColor(),
            equalTo(Color(UHabitElevationAndBorders.AMOLED_DIVIDER_ARGB))
        )
    }

    @Test
    fun testTypographyAndMaterialShapesFollowActiveColors() {
        val colors = buildColors(isDark = true, isPureBlack = false, showBorders = true, radiusDp = 18)
        val typography = UHabitTypography.fromColors(colors)
        assertThat(typography.titleMedium.color, equalTo(Color(colors.foreground)))
        assertThat(typography.bodySmall.color, equalTo(Color(colors.secondary)))

        val shapes = UHabitShapes().toMaterialShapes(defaultCardRadiusDp = colors.cardCornerRadiusDp)
        assertThat(shapes.medium, equalTo(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)))
    }
}
