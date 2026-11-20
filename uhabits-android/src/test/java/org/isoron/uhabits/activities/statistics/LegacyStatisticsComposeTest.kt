/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsDailyProgress
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsGoalTypeFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LegacyStatisticsComposeTest {

    @Before
    fun setUp() {
        setToday(LocalDate(2026, 10, 15))
    }

    @Test
    fun filterDraftResetRestoresDefaults() {
        val modified = LegacyStatisticsFilterDraft(
            sphereId = 42L,
            habitStatus = StatisticsHabitStatusFilter.ARCHIVED,
            goalType = StatisticsGoalTypeFilter.NUMERICAL
        )
        assertEquals(
            LegacyStatisticsFilterDraft(
                sphereId = null,
                habitStatus = StatisticsHabitStatusFilter.ACTIVE,
                goalType = StatisticsGoalTypeFilter.ALL
            ),
            modified.reset()
        )
    }

    @Test
    fun sortSelectableFilterBlocksExcludesNullIdsAndOrdersByPositionThenName() {
        val blocks = listOf(
            HabitBlock(id = null, name = "Unsaved", color = PaletteColor(1), position = 0),
            HabitBlock(id = 3L, name = "Work", color = PaletteColor(2), position = 2),
            HabitBlock(id = 2L, name = "Health", color = PaletteColor(3), position = 1),
            HabitBlock(id = 1L, name = "Career", color = PaletteColor(4), position = 1)
        )
        assertEquals(
            listOf(
                LegacyFilterSphereOption(1L, "Career"),
                LegacyFilterSphereOption(2L, "Health"),
                LegacyFilterSphereOption(3L, "Work")
            ),
            sortSelectableFilterBlocks(blocks)
        )
    }

    @Test
    fun resolveLegacySelectedSphereIdDropsStaleSelection() {
        val options = listOf(
            LegacyFilterSphereOption(1L, "Career"),
            LegacyFilterSphereOption(2L, "Health")
        )
        assertEquals(2L, resolveLegacySelectedSphereId(2L, options))
        assertNull(resolveLegacySelectedSphereId(99L, options))
        assertNull(resolveLegacySelectedSphereId(null, options))
    }

    @Test
    fun activeFilterSummaryCountsAndFormatsLabels() {
        val defaultFilters = StatisticsFilterState()
        assertEquals(
            0 to null,
            buildLegacyActiveFilterSummary(
                filters = defaultFilters,
                blockNamesById = mapOf(10L to "Health"),
                archivedLabel = "Archived",
                allHabitsLabel = "All Habits",
                booleanOnlyLabel = "Yes/No",
                numericalOnlyLabel = "Numerical"
            )
        )

        val activeFilters = StatisticsFilterState(
            sphereId = 10L,
            habitStatus = StatisticsHabitStatusFilter.ARCHIVED,
            goalType = StatisticsGoalTypeFilter.NUMERICAL
        )
        assertEquals(
            3 to "Health · Archived · Numerical",
            buildLegacyActiveFilterSummary(
                filters = activeFilters,
                blockNamesById = mapOf(10L to "Health"),
                archivedLabel = "Archived",
                allHabitsLabel = "All Habits",
                booleanOnlyLabel = "Yes/No",
                numericalOnlyLabel = "Numerical"
            )
        )
    }

    @Test
    fun compareInitialPeriodMatchesReportTab() {
        assertEquals("WEEK", resolveCompareInitialPeriod(StatisticsFragment.ReportTab.DAY))
        assertEquals("WEEK", resolveCompareInitialPeriod(StatisticsFragment.ReportTab.WEEK))
        assertEquals("MONTH", resolveCompareInitialPeriod(StatisticsFragment.ReportTab.MONTH))
        assertEquals("YEAR", resolveCompareInitialPeriod(StatisticsFragment.ReportTab.YEAR))
        assertEquals("ALL", resolveCompareInitialPeriod(StatisticsFragment.ReportTab.ALL))
    }

    @Test
    fun focusDurationFormattingCoversSecondsMinutesAndHours() {
        assertEquals("45 с", formatLegacyFocusDuration(45_000L))
        assertEquals("3 мин 5 с", formatLegacyFocusDuration(185_000L))
        assertEquals("2 ч 15 мин", formatLegacyFocusDuration(8_100_000L))
    }

    @Test
    fun computeNextLegacyAnchorDateHandlesRolloverAndBlocksFuture() {
        val today = LocalDate(2026, 10, 15)
        assertNull(computeNextLegacyAnchorDate(today, StatisticsFragment.ReportTab.DAY, +1, DayOfWeek.MONDAY))
        assertEquals(
            LocalDate(2026, 10, 14),
            computeNextLegacyAnchorDate(today, StatisticsFragment.ReportTab.DAY, -1, DayOfWeek.MONDAY)
        )

        val jan2026 = LocalDate(2026, 1, 15)
        assertEquals(
            LocalDate(2025, 12, 1),
            computeNextLegacyAnchorDate(jan2026, StatisticsFragment.ReportTab.MONTH, -1, DayOfWeek.MONDAY)
        )
        val dec2025 = LocalDate(2025, 12, 1)
        assertEquals(
            LocalDate(2026, 1, 1),
            computeNextLegacyAnchorDate(dec2025, StatisticsFragment.ReportTab.MONTH, +1, DayOfWeek.MONDAY)
        )
    }

    @Test
    fun calendarMetricsAndHitTestingSupportLtrAndRtl() {
        val start = LocalDate(2026, 10, 1) // Thursday
        val end = LocalDate(2026, 10, 31)
        val days = listOf(
            StatisticsDailyProgress(LocalDate(2026, 10, 1), 0.5, true, false, false, false, false),
            StatisticsDailyProgress(LocalDate(2026, 10, 2), 0.9, true, true, false, false, false),
            StatisticsDailyProgress(LocalDate(2026, 10, 20), 1.0, true, true, false, false, true)
        )
        val metrics = computeLegacyMonthCalendarMetrics(
            monthStart = start,
            monthEnd = end,
            firstWeekday = DayOfWeek.MONDAY,
            days = days,
            averageProgress = 0.7,
            shortMonthName = { "Oct" }
        )
        assertEquals("70%", metrics.averageText)
        assertEquals("90%", metrics.bestDayPct)
        assertEquals("2 Oct", metrics.bestDayDate)
        assertEquals(3, metrics.firstDowOffset)
        assertEquals(31, metrics.totalDays)
        assertEquals(5, metrics.numWeeks)

        // LTR: day 1 sits at logical column 3 (Thursday), visual column 3
        val ltrHit = resolveCalendarDayAtPoint(
            touchX = 3f * 20f + 5f,
            touchY = 5f,
            startX = 0f,
            startY = 0f,
            cellSizePx = 18f,
            spacingPx = 2f,
            firstDowOffset = metrics.firstDowOffset,
            totalDays = metrics.totalDays,
            monthStart = start,
            isRtl = false
        )
        assertNotNull(ltrHit)
        assertEquals(LocalDate(2026, 10, 1), ltrHit!!.first)

        // RTL: logical column 3 maps to visual column 3; logical column 4 (day 2) maps to visual column 2
        val rtlHit = resolveCalendarDayAtPoint(
            touchX = 2f * 20f + 5f,
            touchY = 5f,
            startX = 0f,
            startY = 0f,
            cellSizePx = 18f,
            spacingPx = 2f,
            firstDowOffset = metrics.firstDowOffset,
            totalDays = metrics.totalDays,
            monthStart = start,
            isRtl = true
        )
        assertNotNull(rtlHit)
        assertEquals(LocalDate(2026, 10, 2), rtlHit!!.first)

        // Leading empty slot before day 1 returns null
        val emptyHit = resolveCalendarDayAtPoint(
            touchX = 5f,
            touchY = 5f,
            startX = 0f,
            startY = 0f,
            cellSizePx = 18f,
            spacingPx = 2f,
            firstDowOffset = metrics.firstDowOffset,
            totalDays = metrics.totalDays,
            monthStart = start,
            isRtl = false
        )
        assertNull(emptyHit)
    }

    @Test
    fun intensityAlphaAndPercentFormattingMatchLegacyBands() {
        assertEquals("0%", formatLegacyPercent(-0.2))
        assertEquals("50%", formatLegacyPercent(0.5))
        assertEquals("100%", formatLegacyPercent(1.2))
        assertEquals(0f, resolveCalendarIntensityAlpha(0.0), 0.0001f)
        assertEquals(70f / 255f, resolveCalendarIntensityAlpha(0.2), 0.0001f)
        assertEquals(125f / 255f, resolveCalendarIntensityAlpha(0.5), 0.0001f)
        assertEquals(185f / 255f, resolveCalendarIntensityAlpha(0.75), 0.0001f)
        assertEquals(245f / 255f, resolveCalendarIntensityAlpha(1.0), 0.0001f)
    }

    @Test
    fun legacyStatisticsHostsUsePureComposeAndPreserveContracts() {
        val activitySrc = readStatisticsSource("StatisticsActivity.kt")
        val filtersHostSrc = readStatisticsSource("StatisticsFiltersBottomSheet.kt")
        val filtersComposeSrc = readStatisticsSource("LegacyStatisticsFiltersCompose.kt")
        val chartsComposeSrc = readStatisticsSource("LegacyStatisticsChartsCompose.kt")
        val screenComposeSrc = readStatisticsSource("LegacyStatisticsScreenCompose.kt")

        assertTrue(activitySrc.contains("ComposeView(requireContext())"))
        assertTrue(activitySrc.contains("UHabitComposeTheme(colors = colors)"))
        assertTrue(activitySrc.contains("LegacyStatisticsScreen("))
        assertFalse(activitySrc.contains("ActivityStatisticsBinding"))
        assertFalse(activitySrc.contains("AndroidView"))
        assertTrue(activitySrc.contains("lastRenderedReportKey"))
        assertTrue(activitySrc.contains("activeReportGeneration"))
        assertTrue(activitySrc.contains("pendingReportRunnable"))
        assertTrue(activitySrc.contains("currentAnchorDate"))
        assertTrue(activitySrc.contains("isObservingDataState"))

        assertTrue(filtersHostSrc.contains("ComposeView(requireContext())"))
        assertTrue(filtersHostSrc.contains("LegacyStatisticsFiltersSheetContent("))
        assertTrue(filtersHostSrc.contains("const val RESULT_KEY = \"statistics_filters_result\""))
        assertFalse(filtersHostSrc.contains("R.layout.statistics_filters_bottom_sheet"))
        assertFalse(filtersHostSrc.contains("R.layout.statistics_filter_chip"))

        assertTrue(screenComposeSrc.contains("!constraints.hasBoundedWidth || !constraints.hasBoundedHeight"))
        assertTrue(chartsComposeSrc.contains("!constraints.hasBoundedWidth"))
        assertTrue(filtersComposeSrc.contains(".heightIn(max = maxBodyHeight)"))

        val hardcodedColorRegex = Regex("""Color\(\s*0[xX][0-9a-fA-F]+""")
        listOf(filtersComposeSrc, chartsComposeSrc, screenComposeSrc).forEach { src ->
            assertFalse("Must not contain hardcoded Color(0x...) literals", hardcodedColorRegex.containsMatchIn(src))
            assertFalse("Must not inflate XML layouts", src.contains("LayoutInflater.from"))
        }
    }

    private fun readStatisticsSource(fileName: String): String {
        val sourceDir = listOf(
            Path.of("src/main/java/org/isoron/uhabits/activities/statistics"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/statistics")
        ).first { Files.isDirectory(it) }
        return String(Files.readAllBytes(sourceDir.resolve(fileName)), Charsets.UTF_8)
    }
}
