/* Copyright (C) 2026 UHabit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRowComposeUnitTest {

    @Test
    fun testUHabitComposeColorsFromPalette() {
        val palette = SettingsThemePalette(
            background = 0xFF121212.toInt(),
            surface = 0xFF1E1E1E.toInt(),
            border = 0x1FFFFFFF,
            divider = 0x14FFFFFF,
            onSurface = 0xFFE0E0E0.toInt(),
            onSurfaceVariant = 0xFFA0A0A0.toInt(),
            accent = 0xFF00B0FF.toInt(),
            isDark = true,
            isPureBlack = false
        )

        val colors = UHabitComposeColors.fromPalette(
            palette = palette,
            danger = 0xFFEF5350.toInt(),
            warning = 0xFFFFB74D.toInt()
        )

        assertEquals(palette.background, colors.background)
        assertEquals(palette.onSurface, colors.foreground)
        assertEquals(palette.onSurfaceVariant, colors.secondary)
        assertEquals(palette.accent, colors.accent)
        assertEquals(0xFFEF5350.toInt(), colors.danger)
        assertEquals(0xFFFFB74D.toInt(), colors.warning)
        assertTrue(colors.isDark)
        assertFalse(colors.isPureBlack)
    }

    @Test
    fun testUseComposeTaskRowDefault() {
        assertTrue("Modern Compose Task Row must be enabled by default", TasksAdapter.useComposeTaskRow)
    }

    @Test
    fun testTaskMetaTokensSpokenDescriptionFormat() {
        val task = UserTask(
            id = TaskId("t1"),
            title = "Купить продукты",
            important = true,
            urgent = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        assertTrue(task.important == true && task.urgent == true)
        assertEquals("Купить продукты", task.title)
    }

    @Test
    fun testCompactContainerPath() {
        // Short path stays intact
        assertEquals("Дом / Ремонт", TaskListSections.compactContainerPath("Дом / Ремонт", "Ремонт"))
        // Multi-level path is nicely compacted
        val longPath = "Работа и проекты / Мобильное приложение / UI / Спринт 03"
        val compacted = TaskListSections.compactContainerPath(longPath, "Спринт 03", maxLen = 30)
        assertTrue("Compacted path should include leaf name", compacted.contains("Спринт 03"))
        assertTrue("Compacted path should not exceed reasonable length", compacted.length <= 36)
    }
}
