/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.containers.statistics

import org.isoron.platform.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ContainerStatisticsLogicTest {

    @Test
    fun stepAnchorHandlesMonthYearBoundaries() {
        assertEquals(
            LocalDate(2025, 12, 1),
            ContainerStatisticsLogic.stepAnchor(ContainerRangeType.MONTH, LocalDate(2026, 1, 15), -1),
        )
        assertEquals(
            LocalDate(2027, 1, 1),
            ContainerStatisticsLogic.stepAnchor(ContainerRangeType.MONTH, LocalDate(2026, 12, 31), 1),
        )
        assertEquals(
            LocalDate(2026, 5, 10),
            ContainerStatisticsLogic.stepAnchor(ContainerRangeType.DAY, LocalDate(2026, 5, 11), -1),
        )
        assertEquals(
            LocalDate(2026, 5, 18),
            ContainerStatisticsLogic.stepAnchor(ContainerRangeType.WEEK, LocalDate(2026, 5, 11), 1),
        )
    }

    @Test
    fun computeBarHeightRatioPreservesZeroAndMinVisibleFloor() {
        assertEquals(0f, ContainerStatisticsLogic.computeBarHeightRatio(0L, 100L), 0.0001f)
        assertEquals(0.05f, ContainerStatisticsLogic.computeBarHeightRatio(1L, 10_000L), 0.0001f)
        assertEquals(0.5f, ContainerStatisticsLogic.computeBarHeightRatio(50L, 100L), 0.0001f)
        assertEquals(1f, ContainerStatisticsLogic.computeBarHeightRatio(100L, 100L), 0.0001f)
    }

    @Test
    fun formatDurationAndDayLabelsMatchRussianAndEnglishLocales() {
        assertEquals("0 m", ContainerStatisticsLogic.formatDuration(0L, isRussian = false))
        assertEquals("0 мин", ContainerStatisticsLogic.formatDuration(0L, isRussian = true))
        assertEquals("1 h 30 m", ContainerStatisticsLogic.formatDuration(90 * 60_000L, isRussian = false))
        assertEquals("2 ч", ContainerStatisticsLogic.formatDuration(120 * 60_000L, isRussian = true))
        assertEquals("15", ContainerStatisticsLogic.formatDayLabel(LocalDate(2026, 5, 15), bucketCount = 31, isRussian = false))
        assertTrue(ContainerStatisticsLogic.formatDayLabel(LocalDate(2026, 5, 11), bucketCount = 7, isRussian = true) == "Пн")
        assertEquals("5", ContainerStatisticsLogic.formatNumber(5.0, Locale.US))
        assertEquals("5.5", ContainerStatisticsLogic.formatNumber(5.5, Locale.US))
    }

    @Test
    fun formatDateRangeCoversSingleSameYearAndCrossYearRanges() {
        val dayRange = ContainerStatisticsLogic.computeRange(ContainerRangeType.DAY, LocalDate(2026, 10, 5))
        assertEquals("5 Oct 2026", ContainerStatisticsLogic.formatDateRange(dayRange, isRussian = false))
        assertEquals("5 окт 2026", ContainerStatisticsLogic.formatDateRange(dayRange, isRussian = true))

        val weekRange = ContainerStatisticsLogic.computeRange(ContainerRangeType.WEEK, LocalDate(2026, 10, 7))
        assertEquals("5 Oct — 11 Oct 2026", ContainerStatisticsLogic.formatDateRange(weekRange, isRussian = false))
    }
}
