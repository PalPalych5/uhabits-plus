package org.isoron.uhabits.activities.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsDialogsComposeTest {

    @Test
    fun pomodoroDurationSpecValidatesFocusAndBreakRanges() {
        assertEquals(180, PomodoroDurationSpec.maxMinutes(isBreak = false))
        assertEquals(60, PomodoroDurationSpec.maxMinutes(isBreak = true))
        assertEquals(listOf(15, 25, 45, 60, 90), PomodoroDurationSpec.presets(isBreak = false))
        assertEquals(listOf(5, 10, 15, 20, 30), PomodoroDurationSpec.presets(isBreak = true))

        assertEquals(1, PomodoroDurationSpec.parseValidMinutes("1", isBreak = false))
        assertEquals(180, PomodoroDurationSpec.parseValidMinutes("180", isBreak = false))
        assertNull(PomodoroDurationSpec.parseValidMinutes("0", isBreak = false))
        assertNull(PomodoroDurationSpec.parseValidMinutes("181", isBreak = false))
        assertNull(PomodoroDurationSpec.parseValidMinutes("", isBreak = false))

        assertEquals(1, PomodoroDurationSpec.parseValidMinutes("1", isBreak = true))
        assertEquals(60, PomodoroDurationSpec.parseValidMinutes("60", isBreak = true))
        assertNull(PomodoroDurationSpec.parseValidMinutes("61", isBreak = true))
        assertNull(PomodoroDurationSpec.parseValidMinutes("abc", isBreak = true))
    }

    @Test
    fun pomodoroDurationSpecSanitizesInputToThreeDigits() {
        assertEquals("25", PomodoroDurationSpec.sanitizeInput("2a5!"))
        assertEquals("180", PomodoroDurationSpec.sanitizeInput("1809"))
        assertEquals("", PomodoroDurationSpec.sanitizeInput("xyz"))
    }

    @Test
    fun settingsDialogLayoutMathCapsListHeightAcrossOrientations() {
        assertEquals(112, SettingsDialogLayoutMath.maxListHeightDp(300))
        assertEquals(145, SettingsDialogLayoutMath.maxListHeightDp(360))
        assertEquals(146, SettingsDialogLayoutMath.maxListHeightDp(411, fontScale = 2.0f))
        assertEquals(360, SettingsDialogLayoutMath.maxListHeightDp(840))
        assertEquals(360, SettingsDialogLayoutMath.maxListHeightDp(1200))
    }

    @Test
    fun targetSettingsDialogsUseComposeAndPreserveExcludedDialogs() {
        val fragmentSource = readSettingsSource("SettingsFragment.kt")
        val dialogsSource = readSettingsSource("SettingsDialogsCompose.kt")

        assertTrue(fragmentSource.contains("private fun showComposeSingleChoiceDialog("))
        assertTrue(fragmentSource.contains("key = DIALOG_KEY_LANGUAGE,"))
        assertTrue(fragmentSource.contains("key = DIALOG_KEY_WEEKDAY,"))
        assertTrue(fragmentSource.contains("key = DIALOG_KEY_WIDGET_OPACITY,"))
        assertTrue(fragmentSource.contains("key = DIALOG_KEY_SECTION_LAYOUT,"))

        assertTrue(fragmentSource.contains("DIALOG_KEY_POMODORO_FOCUS"))
        assertTrue(fragmentSource.contains("DIALOG_KEY_POMODORO_BREAK"))
        assertTrue(fragmentSource.contains("STATE_DRAFT_POMODORO_MINUTES"))
        assertTrue(fragmentSource.contains("PomodoroDurationDialogContent("))
        assertTrue(fragmentSource.contains("WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM"))

        // Excluded platform/shared/auth/destructive dialogs remain untouched on CustomDialogs/platform hosts.
        assertTrue(fragmentSource.contains("showDatePickerDialog("))
        assertTrue(fragmentSource.contains("CustomDialogs.showCredentialsDialog("))
        assertTrue(fragmentSource.contains("CustomDialogs.showConfirmDialog("))

        assertTrue(dialogsSource.contains("fun SettingsSingleChoiceDialogContent("))
        assertTrue(dialogsSource.contains("fun PomodoroDurationDialogContent("))
        assertTrue(dialogsSource.contains("Role.RadioButton"))
        assertTrue(dialogsSource.contains("selectableGroup()"))
    }

    @Test
    fun statisticsStartSelectionMapsNullTodayMondayAndCustomDate() {
        val monday = org.isoron.platform.time.LocalDate(2026, 11, 9)
        val today = org.isoron.platform.time.LocalDate(2026, 11, 14)
        val custom = org.isoron.platform.time.LocalDate(2026, 10, 1)

        assertEquals(
            -1,
            org.isoron.uhabits.activities.common.dialogs.CustomDialogs.resolveStatisticsStartSelection(null, today, monday),
        )
        assertEquals(
            0,
            org.isoron.uhabits.activities.common.dialogs.CustomDialogs.resolveStatisticsStartSelection(today, today, monday),
        )
        assertEquals(
            1,
            org.isoron.uhabits.activities.common.dialogs.CustomDialogs.resolveStatisticsStartSelection(monday, today, monday),
        )
        assertEquals(
            2,
            org.isoron.uhabits.activities.common.dialogs.CustomDialogs.resolveStatisticsStartSelection(custom, today, monday),
        )
    }

    private fun readSettingsSource(fileName: String): String {
        val sourceDir = listOf(
            Path.of("src/main/java/org/isoron/uhabits/activities/settings"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/settings"),
        ).first { Files.isDirectory(it) }
        return String(Files.readAllBytes(sourceDir.resolve(fileName)), Charsets.UTF_8)
    }
}
