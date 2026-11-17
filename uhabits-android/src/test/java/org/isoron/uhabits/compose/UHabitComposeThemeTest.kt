/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.PureBlackTheme
import org.junit.Test

class UHabitComposeThemeTest {

    private fun lightPalette(
        habitsRadius: Int = 12,
        statsRadius: Int = 16,
        showBorders: Boolean = true,
        accent: Int = 0xFF1976D2.toInt()
    ) = SettingsThemePalette(
        background = 0xFFF7F8FA.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceVariant = 0xFFEEF1F5.toInt(),
        border = 0xFFD9DEE5.toInt(),
        divider = 0x14000000,
        groupDivider = 0x1F000000,
        onSurface = 0xFF1A1C1E.toInt(),
        onSurfaceVariant = 0xFF5F6368.toInt(),
        accent = accent,
        isDark = false,
        isPureBlack = false,
        habitsCardCornerRadius = habitsRadius,
        statisticsCardCornerRadius = statsRadius,
        showHabitCardBorders = showBorders
    )

    private fun darkPalette(
        habitsRadius: Int = 12,
        statsRadius: Int = 16,
        showBorders: Boolean = true,
        accent: Int = 0xFF64B5F6.toInt()
    ) = SettingsThemePalette(
        background = 0xFF121417.toInt(),
        surface = 0xFF1A1D23.toInt(),
        surfaceVariant = 0xFF232730.toInt(),
        border = 0x1FFFFFFF,
        divider = 0x14FFFFFF,
        groupDivider = 0x1FFFFFFF,
        onSurface = 0xFFE8EAED.toInt(),
        onSurfaceVariant = 0xFF9AA0A6.toInt(),
        accent = accent,
        isDark = true,
        isPureBlack = false,
        habitsCardCornerRadius = habitsRadius,
        statisticsCardCornerRadius = statsRadius,
        showHabitCardBorders = showBorders
    )

    private fun amoledPalette(
        habitsRadius: Int = 12,
        statsRadius: Int = 16,
        showBorders: Boolean = true,
        accent: Int = 0xFF64B5F6.toInt()
    ) = SettingsThemePalette(
        background = 0xFF000000.toInt(),
        surface = 0xFF0A0A0A.toInt(),
        surfaceVariant = 0xFF141414.toInt(),
        border = 0x14FFFFFF,
        divider = 0x14FFFFFF,
        groupDivider = 0x1FFFFFFF,
        onSurface = 0xFFFFFFFF.toInt(),
        onSurfaceVariant = 0xFF9AA0A6.toInt(),
        accent = accent,
        isDark = true,
        isPureBlack = true,
        habitsCardCornerRadius = habitsRadius,
        statisticsCardCornerRadius = statsRadius,
        showHabitCardBorders = showBorders
    )

    @Test
    fun testFromPalettePreservesBothRadiiAndBorderPreference() {
        val palette = lightPalette(habitsRadius = 8, statsRadius = 20, showBorders = false)
        val habitColors = UHabitComposeColors.fromPalette(palette)
        assertThat(habitColors.cardCornerRadiusDp, equalTo(8))
        assertThat(habitColors.habitsCardCornerRadiusDp, equalTo(8))
        assertThat(habitColors.statisticsCardCornerRadiusDp, equalTo(20))
        assertThat(habitColors.showCardBorders, equalTo(false))

        val statsColors = UHabitComposeColors.fromPalette(
            palette,
            cardCornerRadiusDp = palette.statisticsCardCornerRadius
        )
        assertThat(statsColors.cardCornerRadiusDp, equalTo(20))
        assertThat(statsColors.habitsCardCornerRadiusDp, equalTo(8))
        assertThat(statsColors.statisticsCardCornerRadiusDp, equalTo(20))
    }

    @Test
    fun testMaterialColorSchemeMappingLightDarkAndAmoled() {
        val light = UHabitComposeColors.fromPalette(lightPalette(accent = 0xFF2E7D32.toInt()))
        val lightScheme = light.toMaterialColorScheme()
        assertThat(lightScheme.primary.toArgb(), equalTo(0xFF2E7D32.toInt()))
        assertThat(lightScheme.background.toArgb(), equalTo(0xFFF7F8FA.toInt()))
        assertThat(lightScheme.surface.toArgb(), equalTo(0xFFFFFFFF.toInt()))
        assertThat(lightScheme.onSurface.toArgb(), equalTo(0xFF1A1C1E.toInt()))

        val dark = UHabitComposeColors.fromPalette(darkPalette(accent = 0xFF81C784.toInt()))
        val darkScheme = dark.toMaterialColorScheme()
        assertThat(darkScheme.primary.toArgb(), equalTo(0xFF81C784.toInt()))
        assertThat(darkScheme.background.toArgb(), equalTo(0xFF121417.toInt()))
        assertThat(darkScheme.surface.toArgb(), equalTo(0xFF1A1D23.toInt()))

        val amoledBordered = UHabitComposeColors.fromPalette(amoledPalette(showBorders = true))
        val amoledBorderedScheme = amoledBordered.toMaterialColorScheme()
        assertThat(amoledBorderedScheme.background.toArgb(), equalTo(0xFF000000.toInt()))
        assertThat(amoledBorderedScheme.surfaceContainerLowest.toArgb(), equalTo(0xFF000000.toInt()))
        assertThat(
            amoledBorderedScheme.surfaceContainerLow.toArgb(),
            equalTo(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB)
        )

        val amoledFlat = UHabitComposeColors.fromPalette(amoledPalette(showBorders = false))
        val amoledFlatScheme = amoledFlat.toMaterialColorScheme()
        assertThat(amoledFlatScheme.surfaceContainerLow.toArgb(), equalTo(0xFF000000.toInt()))
    }

    @Test
    fun testAmoledDisablingCardBordersDoesNotForceElevatedGraySurface() {
        val amoledBordered = UHabitComposeColors.fromPalette(amoledPalette(showBorders = true))
        assertThat(
            amoledBordered.resolveSectionSurfaceColor(),
            equalTo(Color(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB))
        )
        assertThat(
            amoledBordered.resolveRowSurfaceColor(isSelected = false),
            equalTo(Color(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB))
        )

        val amoledUnbordered = UHabitComposeColors.fromPalette(amoledPalette(showBorders = false))
        assertThat(amoledUnbordered.resolveSectionSurfaceColor(), equalTo(Color.Black))
        assertThat(amoledUnbordered.resolveRowSurfaceColor(isSelected = false), equalTo(Color.Transparent))
    }

    @Test
    fun testSelectionFeedbackRemainsDistinctWhenBordersAreDisabled() {
        val accent = 0xFF42A5F5.toInt()
        val unbordered = UHabitComposeColors.fromPalette(amoledPalette(showBorders = false, accent = accent))

        val normalRowBg = unbordered.resolveRowSurfaceColor(isSelected = false)
        val selectedRowBg = unbordered.resolveRowSurfaceColor(isSelected = true)
        val selectedBorder = unbordered.resolveRowBorderColor(isSelected = true)

        assertThat(normalRowBg, equalTo(Color.Transparent))
        assertThat(selectedRowBg, equalTo(Color(accent).copy(alpha = 0.16f)))
        assertThat(selectedBorder, equalTo(Color(accent)))
    }

    @Test
    fun testCardShapeRespectsZeroAndPositiveRadii() {
        val zeroColors = UHabitComposeColors.fromPalette(lightPalette(habitsRadius = 0))
        assertThat(zeroColors.resolveCardShape(), equalTo(RectangleShape))

        val roundedColors = UHabitComposeColors.fromPalette(lightPalette(habitsRadius = 24))
        assertThat(roundedColors.resolveCardShape(), instanceOf(RoundedCornerShape::class.java))
    }

    @Test
    fun testHarmonizedChartThemeMatchesSectionCardSurfaceAcrossModes() {
        val lightColors = UHabitComposeColors.fromPalette(lightPalette(showBorders = true))
        val lightChartTheme = harmonizeChartTheme(LightTheme(), lightColors)
        assertThat(
            lightChartTheme.cardBackgroundColor,
            equalTo(org.isoron.platform.gui.Color(0xFFFFFFFF.toInt()))
        )

        val darkColors = UHabitComposeColors.fromPalette(darkPalette(showBorders = true))
        val darkChartTheme = harmonizeChartTheme(DarkTheme(), darkColors)
        assertThat(
            darkChartTheme.cardBackgroundColor,
            equalTo(org.isoron.platform.gui.Color(0xFF1A1D23.toInt()))
        )

        val amoledBordered = UHabitComposeColors.fromPalette(amoledPalette(showBorders = true))
        val amoledBorderedChartTheme = harmonizeChartTheme(PureBlackTheme(), amoledBordered)
        assertThat(
            amoledBorderedChartTheme.cardBackgroundColor,
            equalTo(org.isoron.platform.gui.Color(0xFF0C0E12.toInt()))
        )

        val amoledFlat = UHabitComposeColors.fromPalette(amoledPalette(showBorders = false))
        val amoledFlatChartTheme = harmonizeChartTheme(PureBlackTheme(), amoledFlat)
        assertThat(
            amoledFlatChartTheme.cardBackgroundColor,
            equalTo(org.isoron.platform.gui.Color(0xFF000000.toInt()))
        )
    }

    @Test
    fun testHabitCardsFollowHabitsCardCornerRadiusByDefault() {
        val palette = lightPalette(habitsRadius = 14, statsRadius = 24, showBorders = true)
        val defaultColors = UHabitComposeColors.fromPalette(palette)
        assertThat(defaultColors.cardCornerRadiusDp, equalTo(14))
        assertThat(defaultColors.habitsCardCornerRadiusDp, equalTo(14))
        assertThat(defaultColors.statisticsCardCornerRadiusDp, equalTo(24))

        // When user modifies habits corner radius to 0 (crisp rectangle / Classic)
        val flatPalette = lightPalette(habitsRadius = 0, statsRadius = 24, showBorders = true)
        val flatColors = UHabitComposeColors.fromPalette(flatPalette)
        assertThat(flatColors.cardCornerRadiusDp, equalTo(0))
        assertThat(flatColors.resolveCardShape(), equalTo(RectangleShape))
    }

    @Test
    fun testClassicSectionLayoutCharacteristics() {
        val amoledClassicPalette = amoledPalette(showBorders = true).copy(isClassicSectionLayout = true)
        val amoledClassicColors = UHabitComposeColors.fromPalette(amoledClassicPalette)
        assertThat(amoledClassicColors.isClassicSectionLayout, equalTo(true))
        assertThat(amoledClassicColors.resolveSectionSurfaceColor(), equalTo(androidx.compose.ui.graphics.Color.Black))

        val lightClassicPalette = lightPalette().copy(isClassicSectionLayout = true)
        val lightClassicColors = UHabitComposeColors.fromPalette(lightClassicPalette)
        assertThat(lightClassicColors.isClassicSectionLayout, equalTo(true))
        assertThat(lightClassicColors.resolveSectionSurfaceColor(), equalTo(androidx.compose.ui.graphics.Color(lightClassicPalette.surface)))
    }

    @Test
    fun testShowHabitSurfaceSpecCardsVsClassic() {
        val amoledPalette = amoledPalette(showBorders = true)
        val amoledCardsSpec = org.isoron.uhabits.activities.habits.show.resolveShowHabitSurfaceSpec(
            amoledPalette,
            isClassicSectionLayout = false
        )
        assertThat(amoledCardsSpec.showToolbarDivider, equalTo(false))
        assertThat(amoledCardsSpec.canvasColor, equalTo(amoledPalette.background))

        val amoledClassicSpec = org.isoron.uhabits.activities.habits.show.resolveShowHabitSurfaceSpec(
            amoledPalette,
            isClassicSectionLayout = true
        )
        assertThat(amoledClassicSpec.showToolbarDivider, equalTo(true))
        assertThat(amoledClassicSpec.canvasColor, equalTo(android.graphics.Color.BLACK))
        assertThat(amoledClassicSpec.toolbarColor, equalTo(android.graphics.Color.BLACK))
        assertThat(amoledClassicSpec.statusBarColor, equalTo(android.graphics.Color.BLACK))
        assertThat(amoledClassicSpec.navigationBarColor, equalTo(android.graphics.Color.BLACK))
    }
}
