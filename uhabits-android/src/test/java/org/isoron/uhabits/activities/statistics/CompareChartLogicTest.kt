/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import org.isoron.platform.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompareChartLogicTest {

    @Test
    fun buildDateAxisBuildsInclusiveSequence() {
        val axis = CompareChartLogic.buildDateAxis(LocalDate(2026, 5, 1), LocalDate(2026, 5, 4))
        assertEquals(
            listOf(
                LocalDate(2026, 5, 1),
                LocalDate(2026, 5, 2),
                LocalDate(2026, 5, 3),
                LocalDate(2026, 5, 4),
            ),
            axis,
        )
        assertEquals(emptyList<LocalDate>(), CompareChartLogic.buildDateAxis(LocalDate(2026, 5, 5), LocalDate(2026, 5, 4)))
    }

    @Test
    fun scrubIndexForXClampsAndMapsDeterministically() {
        assertNull(CompareChartLogic.scrubIndexForX(50f, 10f, 110f, 1))
        assertNull(CompareChartLogic.scrubIndexForX(50f, 100f, 100f, 5))
        assertEquals(0, CompareChartLogic.scrubIndexForX(0f, 10f, 110f, 5))
        assertEquals(2, CompareChartLogic.scrubIndexForX(60f, 10f, 110f, 5))
        assertEquals(4, CompareChartLogic.scrubIndexForX(200f, 10f, 110f, 5))
    }

    @Test
    fun stepMonthHandlesPositiveAndNegativeYearRollover() {
        assertEquals(LocalDate(2025, 12, 1), CompareChartLogic.stepMonth(LocalDate(2026, 1, 15), -1))
        assertEquals(LocalDate(2027, 2, 1), CompareChartLogic.stepMonth(LocalDate(2026, 11, 1), 3))
        assertEquals(LocalDate(2024, 1, 1), CompareChartLogic.stepMonth(LocalDate(2026, 1, 1), -24))
    }

    @Test
    fun maxFrequencyValueDefaultsToOneAndFindsPeak() {
        assertEquals(1, CompareChartLogic.maxFrequencyValue(emptyMap()))
        val freq = mapOf(
            LocalDate(2026, 1, 1) to arrayOf(0, 2, 1, 0, 0, 0, 0),
            LocalDate(2026, 2, 1) to arrayOf(1, 0, 5, 3, 0, 0, 0),
        )
        assertEquals(5, CompareChartLogic.maxFrequencyValue(freq))
    }
}
