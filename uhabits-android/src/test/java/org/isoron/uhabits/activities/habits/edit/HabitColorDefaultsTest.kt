package org.isoron.uhabits.activities.habits.edit

import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitColorDefaultsTest {
    private val red = PaletteColor(1)
    private val blue = PaletteColor(11)
    private val custom = PaletteColor(8)
    private val blocks = listOf(
        HabitBlock(id = 1, name = "Red", color = red),
        HabitBlock(id = 2, name = "Blue", color = blue)
    )

    @Test
    fun newHabitUsesRedBlockColor() {
        assertEquals(red, HabitColorDefaults.forBlock(1, blocks))
    }

    @Test
    fun newHabitUsesBlueBlockColor() {
        assertEquals(blue, HabitColorDefaults.forBlock(2, blocks))
    }

    @Test
    fun blockChangeUpdatesColorBeforeIndividualSelection() {
        assertEquals(
            blue,
            HabitColorDefaults.afterBlockChange(red, 2, hasIndividualColor = false, blocks)
        )
    }

    @Test
    fun resetUsesCurrentBlockColor() {
        assertEquals(blue, HabitColorDefaults.forBlock(2, blocks))
        assertFalse(HabitColorDefaults.isIndividual(blue, 2, blocks))
    }

    @Test
    fun existingIndividualColorIsPreservedWhenBlockChanges() {
        assertEquals(
            custom,
            HabitColorDefaults.afterBlockChange(custom, 2, hasIndividualColor = true, blocks)
        )
        assertTrue(HabitColorDefaults.isIndividual(custom, 2, blocks))
    }

    @Test
    fun habitWithoutBlockUsesApplicationDefault() {
        assertEquals(HabitColorDefaults.appDefault, HabitColorDefaults.forBlock(null, blocks))
    }
}
