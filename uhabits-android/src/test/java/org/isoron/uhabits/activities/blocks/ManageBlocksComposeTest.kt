/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.blocks

import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ManageBlocksComposeTest {

    @Test
    fun testCanDeleteBlock_preservesDefaultProtectedIds() {
        assertFalse(canDeleteBlock(null))
        assertFalse(canDeleteBlock(1L))
        assertFalse(canDeleteBlock(7L))
        assertTrue(canDeleteBlock(8L))
        assertTrue(canDeleteBlock(42L))
    }

    @Test
    fun testValidateBlockName_trimsAndRejectsBlank() {
        assertNull(validateBlockName(""))
        assertNull(validateBlockName("   \t\n "))
        assertEquals("Health", validateBlockName("  Health  "))
    }

    @Test
    fun testComputeNextBlockPosition() {
        assertEquals(0, computeNextBlockPosition(emptyList()))
        val blocks = listOf(
            HabitBlock(id = 1L, name = "A", color = PaletteColor(1), position = 0),
            HabitBlock(id = 2L, name = "B", color = PaletteColor(2), position = 4),
        )
        assertEquals(5, computeNextBlockPosition(blocks))
    }

    @Test
    fun testBuildManageBlockItems_countsHabitsAndResolvesColor() {
        val factory = MemoryModelFactory()
        val h1 = factory.buildHabit().apply { blockId = 2L }
        val h2 = factory.buildHabit().apply { blockId = 2L }
        val h3 = factory.buildHabit().apply { blockId = 9L }

        val blocks = listOf(
            HabitBlock(id = 1L, name = "Limits", color = PaletteColor(3), position = 0),
            HabitBlock(id = 2L, name = "Routine", color = PaletteColor(5), position = 1),
        )

        val items = buildManageBlockItems(
            blocks = blocks,
            allHabits = listOf(h1, h2, h3),
            resolveColor = { color -> 0x100000 + color.paletteIndex },
        )

        assertEquals(2, items.size)
        assertEquals(0, items[0].habitCount)
        assertEquals(0x100003, items[0].resolvedColorArgb)
        assertEquals(2, items[1].habitCount)
        assertEquals(0x100005, items[1].resolvedColorArgb)
    }

    @Test
    fun testManageBlocksActivityRoutesToPureComposeAndPreservesPersistenceContracts() {
        val activitySrc = readBlocksSource("ManageBlocksActivity.kt")
        val composeSrc = readBlocksSource("ManageBlocksComposeScreen.kt")

        assertTrue(activitySrc.contains("ComposeView(this)"))
        assertTrue(activitySrc.contains("ManageBlocksComposeScreen("))
        assertTrue(activitySrc.contains("syncManager.enqueueBlockChange"))
        assertTrue(activitySrc.contains("repository.softDelete"))
        assertTrue(activitySrc.contains("SQLiteHabitList"))
        assertTrue(activitySrc.contains("KEY_EDITOR_MODE"))
        assertTrue(activitySrc.contains("KEY_SHOW_COLOR_PICKER"))
        assertFalse(activitySrc.contains("ActivityManageBlocksBinding"))
        assertFalse(activitySrc.contains("CustomDialogs"))
        assertFalse(activitySrc.contains("UHabitColorPickerBottomSheet"))
        assertFalse(composeSrc.contains("AndroidView"))
        assertTrue(composeSrc.contains("UHabitComposeTheme(colors)"))
        assertTrue(composeSrc.contains("UHabitSectionCard("))
        assertTrue(composeSrc.contains("UHabitDialogShell("))
        assertTrue(composeSrc.contains("UHabitTextField("))
        assertTrue(composeSrc.contains("UHabitColorPickerContent("))
    }

    private fun readBlocksSource(fileName: String): String {
        val dir = listOf(
            Path.of("src/main/java/org/isoron/uhabits/activities/blocks"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/blocks"),
        ).first { Files.isDirectory(it) }
        return String(Files.readAllBytes(dir.resolve(fileName)), Charsets.UTF_8)
    }
}
