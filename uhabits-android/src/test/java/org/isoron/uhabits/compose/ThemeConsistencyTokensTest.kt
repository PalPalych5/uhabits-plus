package org.isoron.uhabits.compose

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import org.isoron.uhabits.activities.common.views.TargetChart
import org.isoron.uhabits.activities.habits.show.resolveShowHabitSurfaceSpec
import org.isoron.uhabits.activities.habits.show.views.resolveTargetTrackColor
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeConsistencyTokensTest {

    private fun sampleLightPalette(isClassic: Boolean = false, showBorders: Boolean = true) = SettingsThemePalette(
        background = 0xFFF6F7F9.toInt(),
        surface = AndroidColor.WHITE,
        surfaceVariant = 0xFFECEFF3.toInt(),
        border = 0xFFD9DEE5.toInt(),
        divider = 0xFFE7EBF0.toInt(),
        onSurface = 0xFF191C20.toInt(),
        onSurfaceVariant = 0xFF626872.toInt(),
        accent = 0xFF388E3C.toInt(),
        isDark = false,
        isPureBlack = false,
        showHabitCardBorders = showBorders,
        isClassicSectionLayout = isClassic,
        dialogSurface = AndroidColor.WHITE,
        dialogBorder = 0xFFD9DEE5.toInt()
    )

    private fun sampleDarkPalette(isClassic: Boolean = false, showBorders: Boolean = true) = SettingsThemePalette(
        background = 0xFF111318.toInt(),
        surface = 0xFF1A1D23.toInt(),
        surfaceVariant = 0xFF242830.toInt(),
        border = 0x1AFFFFFF,
        divider = 0x1FFFFFFF,
        onSurface = 0xFFF1F2F5.toInt(),
        onSurfaceVariant = 0xFFAEB3BD.toInt(),
        accent = 0xFF68C37A.toInt(),
        isDark = true,
        isPureBlack = false,
        showHabitCardBorders = showBorders,
        isClassicSectionLayout = isClassic,
        dialogSurface = 0xFF1A1D23.toInt(),
        dialogBorder = 0x1AFFFFFF
    )

    private fun sampleAmoledPalette(isClassic: Boolean = false, showBorders: Boolean = true) = SettingsThemePalette(
        background = AndroidColor.BLACK,
        surface = 0xFF121212.toInt(),
        surfaceVariant = 0xFF1A1A1A.toInt(),
        border = 0x29FFFFFF,
        divider = 0x1FFFFFFF,
        onSurface = 0xFFF4F4F5.toInt(),
        onSurfaceVariant = 0xFFA7A9AF.toInt(),
        accent = 0xFF68C37A.toInt(),
        isDark = true,
        isPureBlack = true,
        showHabitCardBorders = showBorders,
        isClassicSectionLayout = isClassic,
        dialogSurface = AndroidColor.BLACK,
        dialogBorder = 0x29FFFFFF
    )

    @Test
    fun `classic detail spec unifies toolbar, status bar, and canvas onto classic section surface`() {
        val lightSpec = resolveShowHabitSurfaceSpec(sampleLightPalette(isClassic = true), isClassicSectionLayout = true)
        assertEquals(AndroidColor.WHITE, lightSpec.canvasColor)
        assertEquals(lightSpec.canvasColor, lightSpec.toolbarColor)
        assertEquals(lightSpec.canvasColor, lightSpec.statusBarColor)
        assertTrue(lightSpec.showToolbarDivider)
        assertTrue(lightSpec.isLightSystemBars)

        val darkSpec = resolveShowHabitSurfaceSpec(sampleDarkPalette(isClassic = true), isClassicSectionLayout = true)
        assertEquals(0xFF1A1D23.toInt(), darkSpec.canvasColor)
        assertEquals(darkSpec.canvasColor, darkSpec.toolbarColor)
        assertEquals(darkSpec.canvasColor, darkSpec.statusBarColor)
        assertTrue(darkSpec.showToolbarDivider)
        assertFalse(darkSpec.isLightSystemBars)

        val amoledSpec = resolveShowHabitSurfaceSpec(sampleAmoledPalette(isClassic = true), isClassicSectionLayout = true)
        assertEquals(AndroidColor.BLACK, amoledSpec.canvasColor)
        assertEquals(AndroidColor.BLACK, amoledSpec.toolbarColor)
        assertEquals(AndroidColor.BLACK, amoledSpec.statusBarColor)
        assertTrue(amoledSpec.showToolbarDivider)
    }

    @Test
    fun `modern detail spec keeps canvas background behind floating cards without toolbar hairline`() {
        val lightSpec = resolveShowHabitSurfaceSpec(sampleLightPalette(isClassic = false), isClassicSectionLayout = false)
        assertEquals(0xFFF6F7F9.toInt(), lightSpec.canvasColor)
        assertEquals(lightSpec.canvasColor, lightSpec.toolbarColor)
        assertFalse(lightSpec.showToolbarDivider)

        val darkSpec = resolveShowHabitSurfaceSpec(sampleDarkPalette(isClassic = false), isClassicSectionLayout = false)
        assertEquals(0xFF111318.toInt(), darkSpec.canvasColor)
        assertFalse(darkSpec.showToolbarDivider)

        val amoledSpec = resolveShowHabitSurfaceSpec(sampleAmoledPalette(isClassic = false), isClassicSectionLayout = false)
        assertEquals(AndroidColor.BLACK, amoledSpec.canvasColor)
        assertFalse(amoledSpec.showToolbarDivider)
    }

    @Test
    fun `amoled dialog surfaces stay true black while dark stays dark gray`() {
        val amoledColors = UHabitComposeColors.fromPalette(sampleAmoledPalette())
        assertEquals(Color.Black, amoledColors.dialogSurfaceColor)
        assertEquals(Color(0x29FFFFFF), amoledColors.dialogBorderColor)

        val darkColors = UHabitComposeColors.fromPalette(sampleDarkPalette())
        assertEquals(Color(0xFF1A1D23), darkColors.dialogSurfaceColor)
        assertNotEquals(Color.Black, darkColors.dialogSurfaceColor)
    }

    @Test
    fun `classic section surfaces stay distinct between amoled and dark`() {
        val classicAmoled = UHabitComposeColors.fromPalette(sampleAmoledPalette(isClassic = true))
        val classicDark = UHabitComposeColors.fromPalette(sampleDarkPalette(isClassic = true))
        val classicLight = UHabitComposeColors.fromPalette(sampleLightPalette(isClassic = true))

        assertEquals(Color.Black, UHabitElevationAndBorders.resolveSectionCardSurface(classicAmoled))
        assertEquals(Color(0xFF1A1D23), UHabitElevationAndBorders.resolveSectionCardSurface(classicDark))
        assertEquals(Color.White, UHabitElevationAndBorders.resolveSectionCardSurface(classicLight))
    }

    @Test
    fun `target bar track color stays clearly visible above amoled and dark card surfaces`() {
        val amoled = UHabitComposeColors.fromPalette(sampleAmoledPalette())
        val dark = UHabitComposeColors.fromPalette(sampleDarkPalette())
        val light = UHabitComposeColors.fromPalette(sampleLightPalette())

        val amoledTrack = resolveTargetTrackColor(amoled)
        val darkTrack = resolveTargetTrackColor(dark)
        val lightTrack = resolveTargetTrackColor(light)

        assertNotEquals(AndroidColor.BLACK, amoledTrack)
        assertNotEquals(0xFF121212.toInt(), amoledTrack)
        assertNotEquals(0xFF1A1D23.toInt(), darkTrack)
        assertNotEquals(AndroidColor.WHITE, lightTrack)
    }

    @Test
    fun `filled target bar text switches to dark ink on bright yellow accents`() {
        assertEquals(0xFF111318.toInt(), TargetChart.resolveFilledBarTextColor(0xFFFDD835.toInt()))
        assertEquals(AndroidColor.WHITE, TargetChart.resolveFilledBarTextColor(0xFF283593.toInt()))
    }
}
