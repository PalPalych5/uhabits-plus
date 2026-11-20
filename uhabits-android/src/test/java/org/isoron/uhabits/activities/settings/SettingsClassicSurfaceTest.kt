package org.isoron.uhabits.activities.settings

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsClassicSurfaceTest {

    private fun lightPalette(classic: Boolean) = SettingsThemePalette(
        background = 0xFFF6F7F9.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        border = 0xFFD9DEE5.toInt(),
        divider = 0x14000000,
        onSurface = 0xFF111318.toInt(),
        onSurfaceVariant = 0xFF5A6472.toInt(),
        isDark = false,
        isPureBlack = false,
        habitsCardCornerRadius = 8,
        showHabitCardBorders = true,
        isClassicSectionLayout = classic,
    )

    private fun darkPalette(classic: Boolean) = SettingsThemePalette(
        background = 0xFF111318.toInt(),
        surface = 0xFF1A1D23.toInt(),
        border = 0x1FFFFFFF,
        divider = 0x14FFFFFF,
        onSurface = 0xFFFFFFFF.toInt(),
        onSurfaceVariant = 0xFFB0B7C3.toInt(),
        isDark = true,
        isPureBlack = false,
        habitsCardCornerRadius = 8,
        showHabitCardBorders = true,
        isClassicSectionLayout = classic,
    )

    private fun amoledPalette(classic: Boolean) = SettingsThemePalette(
        background = 0xFF000000.toInt(),
        surface = 0xFF121212.toInt(),
        border = 0x14FFFFFF,
        divider = 0x14FFFFFF,
        onSurface = 0xFFFFFFFF.toInt(),
        onSurfaceVariant = 0xFF9EA6B2.toInt(),
        isDark = true,
        isPureBlack = true,
        habitsCardCornerRadius = 8,
        showHabitCardBorders = true,
        isClassicSectionLayout = classic,
    )

    @Test
    fun classicRowSurfaceMatchesScreenBackgroundWithoutCardChromeAcrossAllThemes() {
        listOf(lightPalette(true), darkPalette(true), amoledPalette(true)).forEach { palette ->
            val style = SettingsRowSurfaceSpec.resolve(
                palette = palette,
                density = 2.625f,
                cardMarginHorizontalPx = 42,
                cardHalfGapPx = 14,
                isFirst = true,
                isLast = true,
            )
            assertEquals(palette.background, style.backgroundColor)
            assertEquals(0f, style.cornerRadiusPx, 0f)
            assertEquals(0, style.strokeWidthPx)
            assertEquals(Color.TRANSPARENT, style.strokeColor)
            assertFalse(style.clipToOutline)
            assertEquals(0, style.marginHorizontalPx)
            assertEquals(0, style.marginTopPx)
            assertEquals(0, style.marginBottomPx)
        }
    }

    @Test
    fun cardsRowSurfacePreservesCardSurfaceBordersRadiiAndMargins() {
        listOf(lightPalette(false), darkPalette(false), amoledPalette(false)).forEach { palette ->
            val style = SettingsRowSurfaceSpec.resolve(
                palette = palette,
                density = 2f,
                cardMarginHorizontalPx = 32,
                cardHalfGapPx = 10,
                isFirst = true,
                isLast = false,
            )
            assertEquals(palette.surface, style.backgroundColor)
            assertEquals(16f, style.cornerRadiusPx, 0f)
            assertEquals(2, style.strokeWidthPx)
            assertEquals(palette.border, style.strokeColor)
            assertTrue(style.clipToOutline)
            assertEquals(32, style.marginHorizontalPx)
            assertEquals(10, style.marginTopPx)
            assertEquals(0, style.marginBottomPx)
        }
    }

    @Test
    fun morphSurfaceSpecDisablesCardChromeAndShadowInClassicOnly() {
        val amoledClassic = SettingsMorphSurfaceSpec.fromPalette(amoledPalette(true), cardCornerRadiusPx = 30f)
        assertEquals(0xFF000000.toInt(), amoledClassic.sourceColor)
        assertEquals(0xFF000000.toInt(), amoledClassic.destinationColor)
        assertEquals(Color.TRANSPARENT, amoledClassic.boundaryColor)
        assertEquals(0f, amoledClassic.sourceCornerRadius, 0f)
        assertFalse(amoledClassic.drawShadow)

        val amoledCards = SettingsMorphSurfaceSpec.fromPalette(amoledPalette(false), cardCornerRadiusPx = 30f)
        assertEquals(0xFF121212.toInt(), amoledCards.sourceColor)
        assertEquals(0xFF000000.toInt(), amoledCards.destinationColor)
        assertEquals(0x14FFFFFF, amoledCards.boundaryColor)
        assertEquals(30f, amoledCards.sourceCornerRadius, 0f)
        assertTrue(amoledCards.drawShadow)
    }

    @Test
    fun homeSectionPresentationUpdatesIsLastDynamicallyWhenDeveloperToggles() {
        val base = listOf(
            SettingsSectionModel(SettingsSectionId.APPEARANCE, 1, "Appearance", "AMOLED", emptyList()),
            SettingsSectionModel(SettingsSectionId.HELP, 2, "Help", "Version", emptyList()),
        )
        val withoutDev = SettingsListComposer.composeHome(base, { it.title }, {})
        assertEquals(2, withoutDev.size)
        assertTrue((withoutDev[0] as SettingItem.Section).presentation.isFirst)
        assertFalse((withoutDev[0] as SettingItem.Section).presentation.isLast)
        assertFalse((withoutDev[1] as SettingItem.Section).presentation.isFirst)
        assertTrue((withoutDev[1] as SettingItem.Section).presentation.isLast)

        val withDev = SettingsListComposer.composeHome(
            base + SettingsSectionModel(SettingsSectionId.DEVELOPER, 3, "Developer", "Dev", emptyList()),
            { it.title },
            {},
        )
        assertEquals(3, withDev.size)
        assertFalse((withDev[1] as SettingItem.Section).presentation.isLast)
        assertTrue((withDev[2] as SettingItem.Section).presentation.isLast)
        assertFalse(withoutDev[1].presentation == withDev[1].presentation)
    }

    @Test
    fun sourceContractsOwnContinuousBackgroundAndClassicInsetDividers() {
        val homeSource = readSettingsSource("SettingsHomeCompose.kt")
        val fragmentSource = readSettingsSource("SettingsFragment.kt")
        val morphLayerSource = readSettingsSource("SettingsMorphLayer.kt")

        assertTrue(homeSource.contains("colors.isClassicSectionLayout && !item.presentation.isLast"))
        assertTrue(fragmentSource.contains("composeView.setBackgroundColor(palette.background)"))
        assertTrue(morphLayerSource.contains("if (liftPx > 0f && drawCardShadow)"))
    }

    private fun readSettingsSource(fileName: String): String {
        val sourceDir = listOf(
            Path.of("src/main/java/org/isoron/uhabits/activities/settings"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/settings"),
        ).first { Files.isDirectory(it) }
        return String(Files.readAllBytes(sourceDir.resolve(fileName)), Charsets.UTF_8)
    }
}
