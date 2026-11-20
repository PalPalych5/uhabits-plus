/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.views

import kotlinx.coroutines.runBlocking
import org.isoron.platform.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class HabitChartsComposeTest {

    @Test
    fun horizontalScrollStateDragRightIncreasesDataOffsetAndClamps() = runBlocking {
        val state = ChartHorizontalScrollState(maxDataOffset = 10)
        state.updateBucketSize(50)
        assertEquals(0, state.dataOffset)

        // Drag right by 125px -> 2 buckets (125 / 50 = 2)
        state.snapBy(125f)
        assertEquals(2, state.dataOffset)

        // Drag right past max (10 * 50 = 500px) -> clamped to 10
        state.snapBy(1000f)
        assertEquals(10, state.dataOffset)

        // Drag left past 0 -> clamped to 0
        state.snapBy(-2000f)
        assertEquals(0, state.dataOffset)

        // Reset restores 0
        state.snapBy(150f)
        assertEquals(3, state.dataOffset)
        state.reset()
        assertEquals(0, state.dataOffset)
    }

    @Test
    fun frequencyChartStepMonthHandlesYearBoundaries() {
        val jan = LocalDate(2026, 1, 1)
        assertEquals(LocalDate(2025, 12, 1), FrequencyChartRenderer.stepMonth(jan, -1))
        assertEquals(LocalDate(2024, 11, 1), FrequencyChartRenderer.stepMonth(jan, -14))
        assertEquals(LocalDate(2027, 2, 1), FrequencyChartRenderer.stepMonth(jan, 13))
    }

    @Test
    fun frequencyChartComputeMaxFreqHasFloorOfOne() {
        assertEquals(1, FrequencyChartRenderer.computeMaxFreq(emptyMap()))
        val data = mapOf(
            LocalDate(2026, 10, 1) to arrayOf(0, 3, 5, 1, 0, 2, 0),
            LocalDate(2026, 9, 1) to arrayOf(4, 2, 1, 0, 0, 0, 0)
        )
        assertEquals(5, FrequencyChartRenderer.computeMaxFreq(data))
    }

    @Test
    fun habitDetailsUsesComposeCanvasForTargetSectionsAndPreservesViewFacades() {
        val mainRoot = listOf(
            Path.of("src/main/java/org/isoron/uhabits"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits")
        ).first { Files.isDirectory(it) }

        val detailsSrc = String(
            Files.readAllBytes(mainRoot.resolve("activities/habits/show/views/HabitDetailsCompose.kt")),
            Charsets.UTF_8
        )
        val statsSection = detailsSrc.substringAfter("fun HabitStatisticsSection(")
            .substringBefore("fun HabitBarHistorySection(")
        val streakSection = detailsSrc.substringAfter("fun HabitStreakSummary(")
            .substringBefore("fun HabitFrequencySection(")
        val freqSection = detailsSrc.substringAfter("fun HabitFrequencySection(")
            .substringBefore("fun HabitEffectiveReadNotice(")

        assertTrue(statsSection.contains("ScoreChartCanvas("))
        assertFalse(statsSection.contains("AndroidView("))
        assertTrue(streakSection.contains("StreakChartCanvas("))
        assertFalse(streakSection.contains("AndroidView("))
        assertTrue(freqSection.contains("FrequencyChartCanvas("))
        assertFalse(freqSection.contains("AndroidView("))

        // History, Bar, Target remain intact
        assertTrue(detailsSrc.contains("fun HabitTargetProgress("))
        assertTrue(detailsSrc.contains("fun HabitHistoryCalendar("))
        assertTrue(detailsSrc.contains("fun HabitBarHistorySection("))

        // View façades remain available for widgets and base view tests
        val scoreView = String(
            Files.readAllBytes(mainRoot.resolve("activities/common/views/ScoreChart.kt")),
            Charsets.UTF_8
        )
        val freqView = String(
            Files.readAllBytes(mainRoot.resolve("activities/common/views/FrequencyChart.kt")),
            Charsets.UTF_8
        )
        val streakView = String(
            Files.readAllBytes(mainRoot.resolve("activities/common/views/StreakChart.kt")),
            Charsets.UTF_8
        )
        assertTrue(scoreView.contains("class ScoreChart : ScrollableChart"))
        assertTrue(freqView.contains("class FrequencyChart : ScrollableChart"))
        assertTrue(streakView.contains("class StreakChart : View"))
    }
}
