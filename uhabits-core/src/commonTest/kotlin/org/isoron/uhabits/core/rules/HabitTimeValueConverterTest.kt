/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.core.rules

import kotlin.test.*

class HabitTimeValueConverterTest {

    @Test
    fun testConvertFocusDurationToHabitValueExactMinutes() {
        // Exactly 0 ms -> 0
        assertEquals(0L, HabitTimeValueConverter.convertFocusDurationToHabitValue(0L))

        // Exactly 1 minute (60_000 ms) -> 1,000
        assertEquals(1_000L, HabitTimeValueConverter.convertFocusDurationToHabitValue(60_000L))

        // Exactly 25 minutes (1,500,000 ms) -> 25,000
        assertEquals(25_000L, HabitTimeValueConverter.convertFocusDurationToHabitValue(1_500_000L))

        // Exactly 42 minutes (2,520,000 ms) -> 42,000
        assertEquals(42_000L, HabitTimeValueConverter.convertFocusDurationToHabitValue(2_520_000L))
    }

    @Test
    fun testConvertFocusDurationToHabitValueRounding() {
        // 59_999 ms with rounding formula: (59999 * 1000 + 30000) / 60000 = 60029000 / 60000 = 1000L
        assertEquals(1_000L, HabitTimeValueConverter.convertFocusDurationToHabitValue(59_999L))

        // 60_001 ms -> (60001000 + 30000) / 60000 = 60031000 / 60000 = 1000L
        assertEquals(1_000L, HabitTimeValueConverter.convertFocusDurationToHabitValue(60_001L))

        // 30_000 ms (half a minute) -> (30000000 + 30000) / 60000 = 500L (0.5 min)
        assertEquals(500L, HabitTimeValueConverter.convertFocusDurationToHabitValue(30_000L))

        // Negative duration rejected
        assertFailsWith<IllegalArgumentException> {
            HabitTimeValueConverter.convertFocusDurationToHabitValue(-1L)
        }
    }

    @Test
    fun testValidateTargetHabitAcceptsMinuteUnits() {
        val validUnits = listOf("min", "mins", "minute", "minutes", "MIN", " min. ", "мин.", "мин", "минута", "минуты", "минут")
        for (unit in validUnits) {
            // Should not throw
            HabitTimeValueConverter.validateTargetHabit(
                isNumerical = true,
                unit = unit,
                name = "Reading",
                uuid = "h-uuid-1",
            )
        }
    }

    @Test
    fun testValidateTargetHabitRejectsNonNumerical() {
        val ex = assertFailsWith<IncompatibleTargetHabitException> {
            HabitTimeValueConverter.validateTargetHabit(
                isNumerical = false,
                unit = "min",
                name = "Boolean Habit",
                uuid = "h-bool",
            )
        }
        assertEquals("h-bool", ex.habitUuid)
        assertTrue(ex.message!!.contains("not numerical"))
    }

    @Test
    fun testValidateTargetHabitRejectsIncompatibleUnits() {
        val invalidUnits = listOf("m", "M", " m ", "m.", " M. ", "метр", "km", "pages", "steps", "hours", "sec", "", "kg")
        for (unit in invalidUnits) {
            val ex = assertFailsWith<IncompatibleTargetHabitException> {
                HabitTimeValueConverter.validateTargetHabit(
                    isNumerical = true,
                    unit = unit,
                    name = "Steps Habit",
                    uuid = "h-unit",
                )
            }
            assertEquals("h-unit", ex.habitUuid)
            assertTrue(ex.message!!.contains("not a minute-based time unit"))
        }
    }
}
