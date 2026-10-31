/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import kotlinx.coroutines.runBlocking
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.setToday
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.EditHabitGoalCommand
import org.isoron.uhabits.core.commands.GoalApplyScope
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.memory.MemoryOrganizationStore
import org.isoron.uhabits.core.containers.statistics.ContainerScopeMode
import org.isoron.uhabits.core.containers.statistics.ContainerStatisticsService
import org.isoron.uhabits.core.containers.statistics.ContainerStatsRange
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitGoalCollisionException
import org.isoron.uhabits.core.database.HabitGoalData
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveHabitValueService
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.isoron.uhabits.core.models.projection.HabitGoalStatus
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.sync.SyncInboxRecord
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.widgets.EffectiveWidgetPresenter
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.toTaskDueDate
import org.isoron.uhabits.core.userTasks.memory.MemoryUserTaskRepository
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent mathematical, historical, calendar, GAP-2, and GAP-3 verification oracle.
 * Never uses production algorithms as both implementation and oracle.
 */
class CalendarAndHistoricalGoalOracleTest : BaseUnitTest() {

    private val oracle = IndependentHistoryAndCalendarOracle()

    /**
     * Independent mathematical oracle for evaluating habit statistics, completion status,
     * calendar squares, streaks, progress ratios, and cycle outcomes.
     */
    class IndependentHistoryAndCalendarOracle {

        fun safeNativeTarget(targetValue: Double, targetType: NumericalHabitType): Long {
            if (!targetValue.isFinite() || targetValue < 0.0) return 0L
            return BigDecimal.valueOf(targetValue).movePointRight(3).setScale(
                0,
                if (targetType == NumericalHabitType.AT_LEAST) RoundingMode.CEILING else RoundingMode.FLOOR
            ).longValueExact()
        }

        fun goalAtDate(goals: List<HabitGoal>, date: LocalDate): HabitGoal {
            val sorted = goals.sortedBy { it.effectiveDate }
            return sorted.lastOrNull { !date.isOlderThan(it.effectiveDate) } ?: sorted.first()
        }

        fun evaluateCadenceWindow(date: LocalDate, cadenceDenominator: Int, firstWeekday: DayOfWeek): Pair<LocalDate, LocalDate> {
            return when (cadenceDenominator) {
                1 -> date to date
                7 -> {
                    val start = date.startOfWeek(firstWeekday)
                    start to start.plus(6)
                }
                30 -> {
                    val start = date.startOfMonth()
                    start to start.plus(start.monthLength - 1)
                }
                else -> date to date
            }
        }

        fun calculateDayStatus(
            habit: Habit,
            date: LocalDate,
            entries: Map<LocalDate, Int>,
            contributions: Map<LocalDate, Long> = emptyMap(),
            firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
            today: LocalDate = try { getToday() } catch (_: Throwable) { date },
        ): HabitGoalStatus {
            val goal = goalAtDate(habit.normalizedGoalHistory(), date)
            val (from, to) = evaluateCadenceWindow(date, goal.frequency.denominator, firstWeekday)

            var d = from
            var sum = 0L
            var totalDays = 0
            var skippedDays = 0
            var unknownDays = 0

            while (d <= to) {
                totalDays++
                val entryVal = entries[d]
                if (entryVal == Entry.SKIP) {
                    skippedDays++
                } else if (entryVal == null || entryVal == Entry.UNKNOWN) {
                    unknownDays++
                } else {
                    sum += entryVal.toLong()
                }
                sum += contributions[d] ?: 0L
                d = d.plus(1)
            }

            if (totalDays > 0 && skippedDays == totalDays) return HabitGoalStatus.SKIPPED

            if (!habit.isNumerical) {
                var manualCount = 0
                var d2 = from
                while (d2 <= to) {
                    if (entries[d2] == Entry.YES_MANUAL) manualCount++
                    d2 = d2.plus(1)
                }
                val quota = goal.frequency.numerator
                return when {
                    manualCount >= quota -> HabitGoalStatus.SATISFIED
                    to > today || unknownDays > 0 -> HabitGoalStatus.UNKNOWN
                    else -> HabitGoalStatus.NOT_SATISFIED
                }
            } else {
                val targetNative = safeNativeTarget(goal.targetValue, goal.targetType)
                val isOngoing = to > today
                return when (goal.targetType) {
                    NumericalHabitType.AT_LEAST -> {
                        when {
                            goal.targetValue <= 0.0 -> HabitGoalStatus.SATISFIED
                            sum >= targetNative -> HabitGoalStatus.SATISFIED
                            isOngoing || unknownDays > 0 -> HabitGoalStatus.UNKNOWN
                            else -> HabitGoalStatus.NOT_SATISFIED
                        }
                    }
                    NumericalHabitType.AT_MOST -> {
                        when {
                            sum > targetNative -> HabitGoalStatus.NOT_SATISFIED
                            isOngoing || unknownDays > 0 -> HabitGoalStatus.UNKNOWN
                            else -> HabitGoalStatus.SATISFIED
                        }
                    }
                }
            }
        }

        fun calculateProgressRatio(
            habit: Habit,
            date: LocalDate,
            entries: Map<LocalDate, Int>,
            contributions: Map<LocalDate, Long> = emptyMap(),
            firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
        ): Double {
            val goal = goalAtDate(habit.normalizedGoalHistory(), date)
            val (from, to) = evaluateCadenceWindow(date, goal.frequency.denominator, firstWeekday)
            var sum = 0L
            var d = from
            while (d <= to) {
                val e = entries[d]
                if (e != null && e >= 0 && e != Entry.SKIP) {
                    sum += e.toLong()
                }
                sum += contributions[d] ?: 0L
                d = d.plus(1)
            }
            val targetNative = safeNativeTarget(goal.targetValue, goal.targetType)
            if (targetNative == 0L) return 1.0
            return sum.toDouble() / targetNative.toDouble()
        }

        fun calculateCalendarSquare(status: HabitGoalStatus): HistoryChart.Square {
            return when (status) {
                HabitGoalStatus.SATISFIED -> HistoryChart.Square.ON
                HabitGoalStatus.NOT_SATISFIED -> HistoryChart.Square.GREY
                HabitGoalStatus.SKIPPED -> HistoryChart.Square.HATCHED
                else -> HistoryChart.Square.OFF
            }
        }

        fun calculateStreaks(
            statusesByDate: Map<LocalDate, HabitGoalStatus>,
            dateRange: List<LocalDate>,
        ): Pair<Int, Int> {
            var currentStreak = 0
            var longestStreak = 0

            val sortedDates = dateRange.sorted()
            for (date in sortedDates) {
                val status = statusesByDate[date] ?: HabitGoalStatus.UNKNOWN
                when (status) {
                    HabitGoalStatus.SATISFIED -> {
                        currentStreak++
                        if (currentStreak > longestStreak) longestStreak = currentStreak
                    }
                    HabitGoalStatus.SKIPPED -> {
                        // Skips preserve the current streak without advancing or breaking
                    }
                    else -> {
                        currentStreak = 0
                    }
                }
            }
            return currentStreak to longestStreak
        }
    }

    private fun buildDay(
        date: LocalDate,
        value: Int,
        manualValue: Long? = null,
        contributionValue: Long = 0L,
        isSkipped: Boolean = false,
        includedInStatistics: Boolean = true,
    ): EffectiveHabitDay {
        val entryValue = if (isSkipped) Entry.SKIP else value
        val mv = if (isSkipped) null else manualValue ?: (if (value >= 0) value.toLong() else null)
        val tv = mv?.let { it + contributionValue }
        return EffectiveHabitDay(
            manualEntry = Entry(date, entryValue),
            manualValue = mv,
            contributionValue = contributionValue,
            totalValue = tv,
            contributions = emptyList(),
            isNumerical = true,
            includedInStatistics = includedInStatistics,
        )
    }

    private fun createQueryContext(
        from: LocalDate,
        to: LocalDate,
        firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
        timezoneId: String = "UTC",
        habitHourOffset: Int = 0,
        habitMinuteOffset: Int = 0,
        now: Long = 1793232000000L // 2026-10-28 12:00:00 UTC
    ): StatisticsQueryContext {
        return StatisticsQueryContext(
            from = from,
            to = to,
            timezoneId = timezoneId,
            firstWeekday = firstWeekday,
            habitHourOffset = habitHourOffset,
            habitMinuteOffset = habitMinuteOffset,
            datasetId = "test-cal-dataset",
            revision = 1L,
            now = now,
        )
    }

    // =========================================================================
    // SECTION 1: Historical Scenarios
    // =========================================================================

    @Test
    fun test60MinDayTo180MinWeekTransition() {
        val transitionDate = LocalDate(2026, 10, 12) // Monday
        val fromDate = LocalDate(2026, 10, 5) // Monday
        val toDate = LocalDate(2026, 10, 25) // Sunday (3 weeks)
        setToday(toDate)

        val habit = modelFactory.buildHabit().apply {
            uuid = "h-trans-60-180"
            name = "Exercise"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 180.0
            frequency = Frequency(1, 7)
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2026, 10, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 60.0, "min"),
                HabitGoal(transitionDate, Frequency(1, 7), NumericalHabitType.AT_LEAST, 180.0, "min")
            )
        }

        val entriesMap = mutableMapOf<LocalDate, Int>()
        val days = mutableListOf<EffectiveHabitDay>()
        var d = fromDate
        while (d <= toDate) {
            // Daily epoch: 70 min/day (exceeds 60m daily)
            // Weekly epoch: 40m on Mon, Wed, Fri -> 120m weekly (< 180m target)
            val mins = if (d < transitionDate) {
                70_000
            } else {
                when (d.dayOfWeek) {
                    DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY -> 40_000
                    else -> 0
                }
            }
            entriesMap[d] = mins
            habit.originalEntries.add(Entry(d, mins))
            days.add(buildDay(d, mins))
            d = d.plus(1)
        }

        val service = EffectiveHabitValueService(MemoryContributionRepository())

        // 1. Verify daily pre-transition statuses and progress ratios against independent oracle
        var dPre = fromDate
        while (dPre < transitionDate) {
            val oracleStatus = oracle.calculateDayStatus(habit, dPre, entriesMap, today = toDate)
            val oracleRatio = oracle.calculateProgressRatio(habit, dPre, entriesMap)
            val prodGoal = service.goalProgressOn(habit, dPre)

            assertEquals(HabitGoalStatus.SATISFIED, oracleStatus)
            assertEquals(oracleStatus, prodGoal.status)
            assertEquals(70.0 / 60.0, oracleRatio, 0.001)
            assertEquals(oracle.calculateCalendarSquare(oracleStatus), HistoryChart.Square.ON)
            dPre = dPre.plus(1)
        }

        // 2. Verify weekly post-transition status and progress ratio against independent oracle
        val postDate = LocalDate(2026, 10, 14) // Wednesday in Week 2
        val oracleWeeklyStatus = oracle.calculateDayStatus(habit, postDate, entriesMap, today = toDate)
        val oracleWeeklyRatio = oracle.calculateProgressRatio(habit, postDate, entriesMap)
        val prodWeeklyGoal = service.goalProgressOn(habit, postDate)

        assertEquals(HabitGoalStatus.NOT_SATISFIED, oracleWeeklyStatus)
        assertEquals(oracleWeeklyStatus, prodWeeklyGoal.status)
        assertEquals(120.0 / 180.0, oracleWeeklyRatio, 0.001)
        assertEquals(oracle.calculateCalendarSquare(oracleWeeklyStatus), HistoryChart.Square.GREY)

        // 3. Cycle aggregates verification
        val context = createQueryContext(fromDate, toDate)
        val aggResult = HabitCycleStatisticsAggregator.aggregate(
            context, listOf(StatisticsHabitInput(habit, days, observationStart = fromDate))
        )
        assertEquals(7, aggResult.daily.satisfiedCount, "7 daily cycles before transition must be satisfied")
        assertEquals(2, aggResult.weekly.notSatisfiedCount, "2 weekly cycles after transition must be not satisfied")
    }

    @Test
    fun testRetroactiveGoalChange() {
        val date1 = LocalDate(2026, 10, 1)
        val date2 = LocalDate(2026, 10, 7)
        setToday(date2)

        val habit = modelFactory.buildHabit().apply {
            uuid = "h-retro"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency.DAILY
            goalHistory = mutableListOf(HabitGoal(date1, Frequency.DAILY, NumericalHabitType.AT_LEAST, 100.0, "min"))
        }

        val entries = mutableMapOf<LocalDate, Int>()
        var d = date1
        while (d <= date2) {
            entries[d] = 80_000 // 80 min daily
            habit.originalEntries.add(Entry(d, 80_000))
            d = d.plus(1)
        }

        val service = EffectiveHabitValueService(MemoryContributionRepository())

        // Initial goal 100m: 80m < 100m -> NOT_SATISFIED
        var checkDate = date1
        while (checkDate <= date2) {
            val statusBefore = oracle.calculateDayStatus(habit, checkDate, entries, today = date2)
            assertEquals(HabitGoalStatus.NOT_SATISFIED, statusBefore)
            assertEquals(statusBefore, service.goalProgressOn(habit, checkDate).status)
            assertEquals(HistoryChart.Square.GREY, oracle.calculateCalendarSquare(statusBefore))
            checkDate = checkDate.plus(1)
        }

        // Retroactively lower goal from date1 to 70m
        habit.targetValue = 70.0
        habit.goalHistory = mutableListOf(HabitGoal(date1, Frequency.DAILY, NumericalHabitType.AT_LEAST, 70.0, "min"))

        // Recalculate: all 7 days flip to SATISFIED
        checkDate = date1
        while (checkDate <= date2) {
            val statusAfter = oracle.calculateDayStatus(habit, checkDate, entries, today = date2)
            assertEquals(HabitGoalStatus.SATISFIED, statusAfter)
            assertEquals(statusAfter, service.goalProgressOn(habit, checkDate).status)
            assertEquals(HistoryChart.Square.ON, oracle.calculateCalendarSquare(statusAfter))
            assertEquals(80.0 / 70.0, oracle.calculateProgressRatio(habit, checkDate, entries), 0.001)
            checkDate = checkDate.plus(1)
        }
    }

    @Test
    fun testMultipleSequentialGoalChangesFourEpochs() {
        val epoch1 = LocalDate(2026, 1, 1) // Daily 30m
        val epoch2 = LocalDate(2026, 4, 1) // Daily 60m
        val epoch3 = LocalDate(2026, 7, 1) // Weekly 180m
        val epoch4 = LocalDate(2026, 10, 1) // Weekly 240m

        val habit = modelFactory.buildHabit().apply {
            uuid = "h-4epochs"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 240.0
            frequency = Frequency(1, 7)
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(epoch1, Frequency.DAILY, NumericalHabitType.AT_LEAST, 30.0, "min"),
                HabitGoal(epoch2, Frequency.DAILY, NumericalHabitType.AT_LEAST, 60.0, "min"),
                HabitGoal(epoch3, Frequency(1, 7), NumericalHabitType.AT_LEAST, 180.0, "min"),
                HabitGoal(epoch4, Frequency(1, 7), NumericalHabitType.AT_LEAST, 240.0, "min")
            )
        }

        val entries = mutableMapOf<LocalDate, Int>()
        // Sample test dates in each epoch
        val testE1 = LocalDate(2026, 2, 10)
        val testE2 = LocalDate(2026, 5, 15)
        val testE3 = LocalDate(2026, 8, 12)
        val testE4 = LocalDate(2026, 10, 14)

        // E1: 35m -> SATISFIED (target 30)
        entries[testE1] = 35_000
        habit.originalEntries.add(Entry(testE1, 35_000))
        // E2: 50m -> NOT_SATISFIED (target 60)
        entries[testE2] = 50_000
        habit.originalEntries.add(Entry(testE2, 50_000))
        // E3: 200m in week -> SATISFIED (target 180)
        val e3WeekStart = testE3.startOfWeek(DayOfWeek.MONDAY)
        entries[e3WeekStart] = 200_000
        habit.originalEntries.add(Entry(e3WeekStart, 200_000))
        for (i in 1..6) {
            entries[e3WeekStart.plus(i)] = 0
            habit.originalEntries.add(Entry(e3WeekStart.plus(i), 0))
        }
        // E4: 210m in week -> NOT_SATISFIED (target 240)
        val e4WeekStart = testE4.startOfWeek(DayOfWeek.MONDAY)
        entries[e4WeekStart] = 210_000
        habit.originalEntries.add(Entry(e4WeekStart, 210_000))
        for (i in 1..6) {
            entries[e4WeekStart.plus(i)] = 0
            habit.originalEntries.add(Entry(e4WeekStart.plus(i), 0))
        }

        setToday(LocalDate(2026, 10, 20))
        val service = EffectiveHabitValueService(MemoryContributionRepository())

        // E1 verification
        val stE1 = oracle.calculateDayStatus(habit, testE1, entries)
        assertEquals(HabitGoalStatus.SATISFIED, stE1)
        assertEquals(stE1, service.goalProgressOn(habit, testE1).status)

        // E2 verification
        val stE2 = oracle.calculateDayStatus(habit, testE2, entries)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, stE2)
        assertEquals(stE2, service.goalProgressOn(habit, testE2).status)

        // E3 verification
        val stE3 = oracle.calculateDayStatus(habit, testE3, entries)
        assertEquals(HabitGoalStatus.SATISFIED, stE3)
        assertEquals(stE3, service.goalProgressOn(habit, testE3).status)

        // E4 verification
        val stE4 = oracle.calculateDayStatus(habit, testE4, entries)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, stE4)
        assertEquals(stE4, service.goalProgressOn(habit, testE4).status)
    }

    @Test
    fun testEditingEntriesBeforeAndAfterGoalEffectiveDate() {
        val boundary = LocalDate(2026, 6, 1)
        val habit = modelFactory.buildHabit().apply {
            uuid = "h-edit-isolated"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 60.0
            frequency = Frequency.DAILY
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2026, 1, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 50.0, "min"),
                HabitGoal(boundary, Frequency.DAILY, NumericalHabitType.AT_LEAST, 60.0, "min")
            )
        }

        val dateBefore = LocalDate(2026, 5, 20)
        val dateAfter = LocalDate(2026, 6, 10)
        setToday(LocalDate(2026, 6, 15))

        val entries = mutableMapOf<LocalDate, Int>()
        entries[dateBefore] = 40_000 // 40m < 50m -> NOT_SATISFIED
        entries[dateAfter] = 70_000 // 70m >= 60m -> SATISFIED
        habit.originalEntries.add(Entry(dateBefore, 40_000))
        habit.originalEntries.add(Entry(dateAfter, 70_000))

        val service = EffectiveHabitValueService(MemoryContributionRepository())

        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(habit, dateBefore).status)
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(habit, dateAfter).status)

        // Edit entry before boundary: 40m -> 55m (satisfies 50m)
        entries[dateBefore] = 55_000
        habit.originalEntries.add(Entry(dateBefore, 55_000))
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, dateBefore, entries))
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(habit, dateBefore).status)
        // Verify entry after boundary remained unchanged
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, dateAfter, entries))
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(habit, dateAfter).status)
        assertEquals(70.0 / 60.0, oracle.calculateProgressRatio(habit, dateAfter, entries), 0.001)
    }

    @Test
    fun testMovingEntryBetweenDatesAndAcrossWeekBoundaries() {
        val week1Sunday = LocalDate(2026, 10, 18)
        val week2Monday = LocalDate(2026, 10, 19)
        setToday(LocalDate(2026, 10, 25))

        val habit = modelFactory.buildHabit().apply {
            uuid = "h-move-entry"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency(1, 7)
            unit = "min"
        }

        val entries = mutableMapOf<LocalDate, Int>()
        // Week 1 has 70m on Friday + 50m on Sunday = 120m (>= 100m -> SATISFIED)
        entries[LocalDate(2026, 10, 16)] = 70_000
        entries[week1Sunday] = 50_000
        for (i in 0..5) entries[LocalDate(2026, 10, 12).plus(i)] = entries[LocalDate(2026, 10, 12).plus(i)] ?: 0

        // Week 2 has 80m on Wednesday = 80m (< 100m -> NOT_SATISFIED)
        entries[LocalDate(2026, 10, 21)] = 80_000
        for (i in 0..6) entries[week2Monday.plus(i)] = entries[week2Monday.plus(i)] ?: 0

        entries.forEach { (date, value) ->
            habit.originalEntries.add(Entry(date, value))
        }

        val service = EffectiveHabitValueService(MemoryContributionRepository())

        // Initial state
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(habit, week1Sunday).status)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(habit, week2Monday).status)

        // Move entry: remove 50m from Sunday, add 50m to Monday
        entries[week1Sunday] = 0
        habit.originalEntries.add(Entry(week1Sunday, 0))
        entries[week2Monday] = 50_000
        habit.originalEntries.add(Entry(week2Monday, 50_000))

        // Week 1 total becomes 70m -> NOT_SATISFIED
        // Week 2 total becomes 80m + 50m = 130m -> SATISFIED
        val oracleW1 = oracle.calculateDayStatus(habit, week1Sunday, entries)
        val oracleW2 = oracle.calculateDayStatus(habit, week2Monday, entries)

        assertEquals(HabitGoalStatus.NOT_SATISFIED, oracleW1)
        assertEquals(HabitGoalStatus.SATISFIED, oracleW2)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, service.goalProgressOn(habit, week1Sunday).status)
        assertEquals(HabitGoalStatus.SATISFIED, service.goalProgressOn(habit, week2Monday).status)
        assertEquals(70.0 / 100.0, oracle.calculateProgressRatio(habit, week1Sunday, entries), 0.001)
        assertEquals(130.0 / 100.0, oracle.calculateProgressRatio(habit, week2Monday, entries), 0.001)
    }

    @Test
    fun testDeletingHistoricalEntry() {
        val startDate = LocalDate(2026, 10, 1)
        val habit = modelFactory.buildHabit().apply {
            uuid = "h-delete-entry"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 50.0
            frequency = Frequency.DAILY
        }

        // 5 consecutive days satisfied
        val statuses = mutableMapOf<LocalDate, HabitGoalStatus>()
        val dates = (0..4).map { startDate.plus(it) }
        dates.forEach { statuses[it] = HabitGoalStatus.SATISFIED }

        val (currentStreak, longestStreak) = oracle.calculateStreaks(statuses, dates)
        assertEquals(5, currentStreak)
        assertEquals(5, longestStreak)

        // Delete entry on day 3 (dates[2])
        statuses[dates[2]] = HabitGoalStatus.NOT_SATISFIED
        val (currentAfter, longestAfter) = oracle.calculateStreaks(statuses, dates)
        // Day 4 & 5 are satisfied -> currentStreak = 2, longestStreak = 2
        assertEquals(2, currentAfter)
        assertEquals(2, longestAfter)
    }

    @Test
    fun testRecalculatingStatisticsForPreviousMonths() {
        val habit = modelFactory.buildHabit().apply {
            uuid = "h-multi-month"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 500.0
            frequency = Frequency(1, 30) // Monthly
        }

        val entries = mutableMapOf<LocalDate, Int>()
        // Initialize all days in August, September, and October to 0 so closed periods have complete coverage
        var cur = LocalDate(2026, 8, 1)
        val end = LocalDate(2026, 10, 31)
        while (cur <= end) {
            entries[cur] = 0
            cur = cur.plus(1)
        }
        // August (31 days): 400m (< 500m -> NOT_SATISFIED)
        entries[LocalDate(2026, 8, 15)] = 400_000
        // September (30 days): 550m (>= 500m -> SATISFIED)
        entries[LocalDate(2026, 9, 15)] = 550_000
        // October (31 days): 600m (>= 500m -> SATISFIED)
        entries[LocalDate(2026, 10, 15)] = 600_000

        setToday(LocalDate(2026, 10, 31))

        assertEquals(HabitGoalStatus.NOT_SATISFIED, oracle.calculateDayStatus(habit, LocalDate(2026, 8, 15), entries))
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, LocalDate(2026, 9, 15), entries))
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, LocalDate(2026, 10, 15), entries))

        // Add 200m to August -> flips August to SATISFIED
        entries[LocalDate(2026, 8, 20)] = 200_000
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, LocalDate(2026, 8, 15), entries))
        assertEquals(600.0 / 500.0, oracle.calculateProgressRatio(habit, LocalDate(2026, 8, 15), entries), 0.001)

        // September and October remain invariant
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, LocalDate(2026, 9, 15), entries))
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit, LocalDate(2026, 10, 15), entries))
    }

    @Test
    fun testUnifiedStatisticsServiceAndContainerStatisticsServiceIndependentVerification() {
        val orgStore = MemoryOrganizationStore()
        val habitRepo = MemoryHabitList()
        val taskRepo = MemoryUserTaskRepository()
        val focusRepo = MemoryFocusSessionRepository()
        val contribRepo = MemoryContributionRepository()

        // Create Container Hierarchy: Root -> Child
        val rootContainer = Container(
            id = ContainerId("c-root"),
            parentId = null,
            name = "Health Sphere",
            color = null, icon = null, siblingOrder = 0, isArchived = false,
            createdAt = 1000L, updatedAt = 1000L, deletedAt = null,
            revision = OrganizationRevision(1)
        )
        val childContainer = Container(
            id = ContainerId("c-child"),
            parentId = ContainerId("c-root"),
            name = "Cardio",
            color = null, icon = null, siblingOrder = 0, isArchived = false,
            createdAt = 1000L, updatedAt = 1000L, deletedAt = null,
            revision = OrganizationRevision(1)
        )
        orgStore.executeInTransaction { tx ->
            tx.saveContainer(rootContainer)
            tx.saveContainer(childContainer)
        }

        // Habit in Child container
        val habitUuid = "habit-cardio"
        val habit = modelFactory.buildHabit().apply {
            uuid = habitUuid
            name = "Morning Run"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 60.0
            frequency = Frequency.DAILY
            unit = "min"
            color = PaletteColor(3)
        }
        habitRepo.add(habit)

        orgStore.executeInTransaction { tx ->
            tx.saveHabitPlacement(
                HabitPlacement(
                    habit = HabitRef(habitUuid),
                    containerId = ContainerId("c-child"),
                    localOrder = 0,
                    origin = PlacementOrigin.USER_CHANGE,
                    revision = OrganizationRevision(1)
                )
            )
        }

        val testDate = LocalDate(2026, 10, 20)
        setToday(testDate)

        // Add 40m manual entry + 30m focus session contribution = 70m total (>= 60m -> SATISFIED)
        habit.originalEntries.add(Entry(testDate, 40_000))

        val focusTs = startOfLocalDay(testDate) + 3600_000L
        val session = FocusSession(
            id = FocusSessionId("fs-1"),
            startedAt = focusTs,
            endedAt = focusTs + 30 * 60 * 1000L,
            durationMs = 30 * 60 * 1000L, // 30 mins
            habit = HabitRef(habitUuid),
            source = FocusSessionSource.MANUAL,
            createdAt = focusTs,
            updatedAt = focusTs
        )
        focusRepo.record(session)

        val contrib = Contribution(
            id = ContributionId("contrib-1"),
            targetHabit = HabitRef(habitUuid),
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = "fs-1",
            contributedValue = 30_000L,
            effectiveTimestamp = focusTs,
            createdAt = focusTs,
            updatedAt = focusTs
        )
        contribRepo.record(contrib)

        // 1. Independent Oracle Calculation
        val entriesMap = mapOf(testDate to 40_000)
        val contribsMap = mapOf(testDate to 30_000L)
        val expectedStatus = oracle.calculateDayStatus(habit, testDate, entriesMap, contribsMap)
        val expectedRatio = oracle.calculateProgressRatio(habit, testDate, entriesMap, contribsMap)
        val expectedSquare = oracle.calculateCalendarSquare(expectedStatus)

        assertEquals(HabitGoalStatus.SATISFIED, expectedStatus)
        assertEquals(70.0 / 60.0, expectedRatio, 0.001)
        assertEquals(HistoryChart.Square.ON, expectedSquare)

        // 2. ContainerStatisticsService verification
        val habitService = EffectiveHabitValueService(contribRepo)
        val containerStats = ContainerStatisticsService(
            containerQueries = orgStore,
            placementQueries = orgStore,
            habitList = habitRepo,
            userTaskRepository = taskRepo,
            focusSessionRepository = focusRepo,
            effectiveHabitValueService = habitService,
            firstWeekday = DayOfWeek.MONDAY,
            clock = { focusTs }
        )

        val report = containerStats.getReport(
            containerId = ContainerId("c-root"),
            range = ContainerStatsRange.day(testDate),
            scopeMode = ContainerScopeMode.SUBTREE
        )

        assertEquals("Health Sphere", report.containerName)
        assertEquals(1, report.habits.totalHabitCount)
        assertEquals(1, report.habits.reachedCount)
        val habitRow = report.habits.habitRows.single()
        assertEquals("Morning Run", habitRow.name)
        assertEquals(expectedStatus, habitRow.goalStatus)
        assertEquals(40_000L, habitRow.manualValue)
        assertEquals(30_000L, habitRow.contributionValue)
        assertEquals(70_000L, habitRow.totalValue)

        // 3. UnifiedStatisticsService verification
        val unifiedService = UnifiedStatisticsService(
            habitList = habitRepo,
            tasks = taskRepo,
            focus = focusRepo,
            contributions = contribRepo,
            placements = orgStore,
            containers = orgStore,
        )
        val queryContext = createQueryContext(
            from = testDate,
            to = testDate,
            timezoneId = TimeZone.getDefault().id,
            now = startOfLocalDay(testDate.plus(1))
        )
        val unifiedReport = unifiedService.read(queryContext, currentIdentity = { StatisticsReadIdentity("test-cal-dataset", 1L) })

        assertNotNull(unifiedReport.habits)
        val cycle = unifiedReport.habits!!.allCycles.single { it.startDate == testDate }
        assertEquals(70_000L, cycle.quantity.lowerBound)
        assertEquals(HabitCycleOutcome.SATISFIED, cycle.outcome)
    }

    // =========================================================================
    // SECTION 2: Calendar Anomalies
    // =========================================================================

    @Test
    fun testLeapYearFeb28And29Anomalies() {
        // Leap year 2024: Feb 29 exists (month length = 29)
        val feb29_2024 = LocalDate(2024, 2, 29)
        assertEquals(29, feb29_2024.monthLength)
        assertEquals(2024, feb29_2024.year)

        val habitMonthly = modelFactory.buildHabit().apply {
            uuid = "h-leap-month"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency(1, 30) // Monthly
        }

        val (from2024, to2024) = oracle.evaluateCadenceWindow(feb29_2024, 30, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2024, 2, 1), from2024)
        assertEquals(LocalDate(2024, 2, 29), to2024)

        // Non-leap year 2025: Feb 28 is month end (month length = 28)
        val feb28_2025 = LocalDate(2025, 2, 28)
        assertEquals(28, feb28_2025.monthLength)
        val (from2025, to2025) = oracle.evaluateCadenceWindow(feb28_2025, 30, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2025, 2, 1), from2025)
        assertEquals(LocalDate(2025, 2, 28), to2025)

        // Leap year 2028: Feb 29 exists
        val feb29_2028 = LocalDate(2028, 2, 29)
        assertEquals(29, feb29_2028.monthLength)

        // Non-leap year 2026: Feb 28 is month end
        val feb28_2026 = LocalDate(2026, 2, 28)
        assertEquals(28, feb28_2026.monthLength)

        // Entry on Feb 29, 2024 counts towards Feb 2024 monthly total
        val entries2024 = mapOf(feb29_2024 to 120_000)
        val status2024 = oracle.calculateDayStatus(habitMonthly, feb29_2024, entries2024, today = feb29_2024)
        assertEquals(HabitGoalStatus.SATISFIED, status2024)
    }

    @Test
    fun testMonthEndTransitions30Vs31Days() {
        val april30 = LocalDate(2026, 4, 30)
        val may31 = LocalDate(2026, 5, 31)
        val aug31 = LocalDate(2026, 8, 31)
        val sept30 = LocalDate(2026, 9, 30)

        assertEquals(30, april30.monthLength)
        assertEquals(31, may31.monthLength)
        assertEquals(31, aug31.monthLength)
        assertEquals(30, sept30.monthLength)

        val habit = modelFactory.buildHabit().apply {
            uuid = "h-month-ends"
            type = HabitType.NUMERICAL
            frequency = Frequency(1, 30)
            targetValue = 100.0
        }

        val (aprStart, aprEnd) = oracle.evaluateCadenceWindow(april30, 30, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 4, 1), aprStart)
        assertEquals(LocalDate(2026, 4, 30), aprEnd)

        val (mayStart, mayEnd) = oracle.evaluateCadenceWindow(may31, 30, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 5, 1), mayStart)
        assertEquals(LocalDate(2026, 5, 31), mayEnd)
    }

    @Test
    fun testYearRolloverDec31ToJan1() {
        val dec31 = LocalDate(2026, 12, 31) // Thursday
        val jan01 = LocalDate(2027, 1, 1) // Friday

        assertEquals(jan01, dec31.plus(1))
        assertEquals(dec31, jan01.minus(1))

        // Weekly cycle spanning across the year rollover (Mon 2026-12-28 to Sun 2027-01-03)
        val (weekStart, weekEnd) = oracle.evaluateCadenceWindow(dec31, 7, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 12, 28), weekStart)
        assertEquals(LocalDate(2027, 1, 3), weekEnd)

        val habit = modelFactory.buildHabit().apply {
            uuid = "h-rollover"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 100.0
            frequency = Frequency(1, 7)
        }

        // Entries on both sides of the rollover sum together into the single weekly cycle
        val entries = mapOf(
            dec31 to 60_000,
            jan01 to 50_000
        )
        val status = oracle.calculateDayStatus(habit, dec31, entries, today = LocalDate(2027, 1, 5))
        assertEquals(HabitGoalStatus.SATISFIED, status)
        assertEquals(110.0 / 100.0, oracle.calculateProgressRatio(habit, dec31, entries), 0.001)
    }

    @Test
    fun testDifferentFirstWeekdaySettings() {
        val testSunday = LocalDate(2026, 10, 18)

        // Under MONDAY: week is Mon 10-12 .. Sun 10-18 (Sunday is end of week)
        val (monStart, monEnd) = oracle.evaluateCadenceWindow(testSunday, 7, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 10, 12), monStart)
        assertEquals(LocalDate(2026, 10, 18), monEnd)

        // Under SUNDAY: week is Sun 10-18 .. Sat 10-24 (Sunday is start of week)
        val (sunStart, sunEnd) = oracle.evaluateCadenceWindow(testSunday, 7, DayOfWeek.SUNDAY)
        assertEquals(LocalDate(2026, 10, 18), sunStart)
        assertEquals(LocalDate(2026, 10, 24), sunEnd)

        // Under SATURDAY: week is Sat 10-17 .. Fri 10-23
        val (satStart, satEnd) = oracle.evaluateCadenceWindow(testSunday, 7, DayOfWeek.SATURDAY)
        assertEquals(LocalDate(2026, 10, 17), satStart)
        assertEquals(LocalDate(2026, 10, 23), satEnd)
    }

    @Test
    fun testTimezoneChangesAndDstTransitions() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            // Spring Forward: 2026-03-08 (23 elapsed hours)
            val springDay = LocalDate(2026, 3, 8)
            val springStart = startOfLocalDay(springDay)
            val springEnd = startOfLocalDay(springDay.plus(1))
            val springHours = (springEnd - springStart) / 3600_000L
            assertEquals(23L, springHours)

            // Fall Back: 2026-11-01 (25 elapsed hours)
            val fallDay = LocalDate(2026, 11, 1)
            val fallStart = startOfLocalDay(fallDay)
            val fallEnd = startOfLocalDay(fallDay.plus(1))
            val fallHours = (fallEnd - fallStart) / 3600_000L
            assertEquals(25L, fallHours)

            // Local date attribution remains stable across DST boundaries
            assertEquals(springDay, localDateAt(springStart + 12 * 3600_000L))
            assertEquals(fallDay, localDateAt(fallStart + 12 * 3600_000L))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun testMidnightRolloverHabitOffsets() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+00:00"))

            val baseDate = LocalDate(2026, 10, 15)
            // Offset 03:30 AM: timestamps between 00:00 and 03:29:59 belong to previous habit day
            val cutoff = startOfLocalDay(baseDate, hourOffset = 3, minuteOffset = 30)

            assertEquals(baseDate.minus(1), localDateAt(cutoff - 1L, hourOffset = 3, minuteOffset = 30))
            assertEquals(baseDate, localDateAt(cutoff, hourOffset = 3, minuteOffset = 30))
            assertEquals(baseDate, localDateAt(cutoff + 1000L, hourOffset = 3, minuteOffset = 30))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun testLongIntervalsAndSparseSchedules() {
        val date = LocalDate(2026, 10, 14)
        // Sparse frequencies: 1/7 (once a week), 2/7 (twice a week), 1/14 (biweekly), 1/30 (monthly)
        val f1_7 = Frequency(1, 7)
        val f2_7 = Frequency(2, 7)
        val f1_14 = Frequency(1, 14)
        val f1_30 = Frequency(1, 30)

        assertEquals(1, f1_7.numerator)
        assertEquals(7, f1_7.denominator)
        assertEquals(14, f1_14.denominator)
        assertEquals(30, f1_30.denominator)

        val habit1_7 = modelFactory.buildHabit().apply {
            uuid = "h-sparse-1-7"
            type = HabitType.YES_NO
            frequency = f1_7
        }
        val habit2_7 = modelFactory.buildHabit().apply {
            uuid = "h-sparse-2-7"
            type = HabitType.YES_NO
            frequency = f2_7
        }

        val weekStart = date.startOfWeek(DayOfWeek.MONDAY)
        val entries1Check = (0..6).associate { i ->
            weekStart.plus(i) to (if (i == 0) Entry.YES_MANUAL else 0)
        }
        val entries2Check = (0..6).associate { i ->
            weekStart.plus(i) to (if (i == 0 || i == 2) Entry.YES_MANUAL else 0)
        }

        // 1/7 with 1 check is SATISFIED
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit1_7, date, entries1Check, today = weekStart.plus(6)))
        // 2/7 with 1 check is NOT_SATISFIED (closed week)
        assertEquals(HabitGoalStatus.NOT_SATISFIED, oracle.calculateDayStatus(habit2_7, date, entries1Check, today = weekStart.plus(6)))
        // 2/7 with 2 checks is SATISFIED
        assertEquals(HabitGoalStatus.SATISFIED, oracle.calculateDayStatus(habit2_7, date, entries2Check, today = weekStart.plus(6)))
    }

    // =========================================================================
    // SECTION 3: GAP-3 Deep Verification
    // =========================================================================

    @Test
    fun testGap3EditHabitGoalCommandTimelinePreservation() = runBlocking {
        val memoryFactory = MemoryModelFactory()
        val list = memoryFactory.buildHabitList()

        val t0 = LocalDate(2026, 1, 1)
        val t1 = LocalDate(2026, 6, 1)
        val t2 = LocalDate(2026, 9, 1)

        val habit = memoryFactory.buildHabit().apply {
            uuid = "gap3-habit"
            name = "Reading"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 30.0
            frequency = Frequency.DAILY
            unit = "min"
            goalHistory = mutableListOf(
                HabitGoal(t0, Frequency.DAILY, NumericalHabitType.AT_LEAST, 30.0, "min"),
                HabitGoal(t1, Frequency.DAILY, NumericalHabitType.AT_LEAST, 60.0, "min"),
                HabitGoal(t2, Frequency(1, 7), NumericalHabitType.AT_LEAST, 180.0, "min")
            )
        }
        list.add(habit)
        val habitId = habit.id!!

        // Add entries across all epochs
        val e0 = LocalDate(2026, 2, 10)
        val e1 = LocalDate(2026, 7, 15)
        val e2 = LocalDate(2026, 9, 20)
        val eFuture = LocalDate(2026, 11, 5)

        habit.originalEntries.add(Entry(e0, 25_000, "note-e0"))
        habit.originalEntries.add(Entry(e1, 65_000, "note-e1"))
        habit.originalEntries.add(Entry(e2, 190_000, "note-e2"))
        habit.originalEntries.add(Entry(eFuture, 50_000, "note-future"))

        // --- SCENARIO A: FROM_DATE Scope at T1 (2026-06-01) with new goal Daily 45m ---
        val modFromDate = memoryFactory.buildHabit().apply {
            copyFrom(habit)
            targetValue = 45.0
            frequency = Frequency.DAILY
            unit = "min"
        }

        commandRunner.run(
            EditHabitGoalCommand(
                habitList = list,
                habitId = habitId,
                modified = modFromDate,
                applyScope = GoalApplyScope.FROM_DATE,
                effectiveDate = t1
            )
        )

        val updatedAfterFromDate = list.getById(habitId)!!
        val goalsAfterFromDate = updatedAfterFromDate.goalHistory

        // Assert 1: Goal prior to T1 (T0 at 2026-01-01) is strictly preserved
        assertEquals(2, goalsAfterFromDate.size, "FROM_DATE should result in exactly 2 goals (prior T0 + new T1)")
        val priorGoal = goalsAfterFromDate.first()
        assertEquals(t0, priorGoal.effectiveDate)
        assertEquals(30.0, priorGoal.targetValue)
        assertEquals(Frequency.DAILY, priorGoal.frequency)

        // Assert 2: Goal at T1 is replaced with the new goal (Daily 45m)
        val newT1Goal = goalsAfterFromDate.last()
        assertEquals(t1, newT1Goal.effectiveDate)
        assertEquals(45.0, newT1Goal.targetValue)
        assertEquals(Frequency.DAILY, newT1Goal.frequency)

        // Assert 3: Future goal T2 (2026-09-01) on/after effectiveDate is replaced
        assertFalse(goalsAfterFromDate.any { it.effectiveDate == t2 }, "Future goal at T2 must be replaced under FROM_DATE")

        // Assert 4: ALL entries before, on, and after T1 are strictly preserved without unexpected deletion
        assertEquals(4, updatedAfterFromDate.originalEntries.getKnown().size)
        assertEquals(Entry(e0, 25_000, "note-e0"), updatedAfterFromDate.originalEntries.get(e0))
        assertEquals(Entry(e1, 65_000, "note-e1"), updatedAfterFromDate.originalEntries.get(e1))
        assertEquals(Entry(e2, 190_000, "note-e2"), updatedAfterFromDate.originalEntries.get(e2))
        assertEquals(Entry(eFuture, 50_000, "note-future"), updatedAfterFromDate.originalEntries.get(eFuture))

        // --- SCENARIO B: ENTIRE_HISTORY Scope with new goal Weekly 210m ---
        val modEntireHistory = memoryFactory.buildHabit().apply {
            copyFrom(updatedAfterFromDate)
            targetValue = 210.0
            frequency = Frequency(1, 7)
            unit = "min"
        }

        commandRunner.run(
            EditHabitGoalCommand(
                habitList = list,
                habitId = habitId,
                modified = modEntireHistory,
                applyScope = GoalApplyScope.ENTIRE_HISTORY,
                effectiveDate = t1
            )
        )

        val updatedAfterEntire = list.getById(habitId)!!
        val goalsAfterEntire = updatedAfterEntire.goalHistory

        // Assert 5: ENTIRE_HISTORY replaces all historical and future goals with single new goal
        assertEquals(1, goalsAfterEntire.size, "ENTIRE_HISTORY must leave exactly 1 goal")
        assertEquals(210.0, goalsAfterEntire.single().targetValue)
        assertEquals(Frequency(1, 7), goalsAfterEntire.single().frequency)

        // Assert 6: ALL entries remain strictly intact without any deletion
        assertEquals(4, updatedAfterEntire.originalEntries.getKnown().size)
        assertEquals(Entry(e0, 25_000, "note-e0"), updatedAfterEntire.originalEntries.get(e0))
        assertEquals(Entry(e1, 65_000, "note-e1"), updatedAfterEntire.originalEntries.get(e1))
        assertEquals(Entry(e2, 190_000, "note-e2"), updatedAfterEntire.originalEntries.get(e2))
        assertEquals(Entry(eFuture, 50_000, "note-future"), updatedAfterEntire.originalEntries.get(eFuture))
    }

    // =========================================================================
    // SECTION 4: GAP-2 Deep Verification
    // =========================================================================

    @Test
    fun testGap2SyntheticDeviceGoalConvergenceAndCollisionIsolation() = runBlocking {
        val dbA = buildMemoryDatabase()
        val dbB = buildMemoryDatabase()

        try {
            val factoryA = SQLModelFactory(dbA)
            val factoryB = SQLModelFactory(dbB)

            val sharedHabitUuid = "habit-shared-uuid"
            val effectiveDate = LocalDate(2026, 10, 1)
            val effectiveTs = effectiveDate.unixTime

            // Device A creates habit (gets local habitId = 1L)
            val idA = factoryA.habitRepository.insert(HabitData(name = "Meditation", uuid = sharedHabitUuid))
            assertEquals(1L, idA)

            // Device B creates 4 dummy habits first, then creates habit with sharedHabitUuid (gets local habitId = 5L)
            for (i in 1..4) {
                factoryB.habitRepository.insert(HabitData(name = "Dummy $i", uuid = "dummy-$i"))
            }
            val idB = factoryB.habitRepository.insert(HabitData(name = "Meditation", uuid = sharedHabitUuid))
            assertEquals(5L, idB)

            // -----------------------------------------------------------------
            // Part A: Clean Convergence with habitUuid-based UUID
            // -----------------------------------------------------------------
            val habitListA = SQLiteHabitList(factoryA)
            val habitA = habitListA.getById(idA)!!
            val modifiedGoalHabitA = factoryA.buildHabit().apply {
                copyFrom(habitA)
                targetValue = 60.0
                frequency = Frequency.DAILY
                unit = "min"
            }

            // Create goal on Device A via EditHabitGoalCommand
            EditHabitGoalCommand(
                habitList = habitListA,
                habitId = idA,
                modified = modifiedGoalHabitA,
                applyScope = GoalApplyScope.FROM_DATE,
                effectiveDate = effectiveDate
            ).run()

            // Verify Device A's goal UUID uses the stable habitUuid
            val goalsA = factoryA.habitGoalRepository.findAllByHabitId(idA)
            val createdGoalA = goalsA.single { it.effectiveTimestamp == effectiveTs }
            val expectedSharedUuid = "$sharedHabitUuid:goal:$effectiveTs"
            assertEquals(expectedSharedUuid, createdGoalA.uuid)

            // Replicate to Device B using the habitUuid-based UUID:
            // Device B upserts the goal mapping to its local habitId (5L)
            val syncedGoalB = createdGoalA.copy(id = null, habitId = idB)
            factoryB.habitGoalRepository.upsert(syncedGoalB)

            val goalsB = factoryB.habitGoalRepository.findAllByHabitId(idB)
            val appliedGoalB = goalsB.single { it.effectiveTimestamp == effectiveTs }
            assertEquals(expectedSharedUuid, appliedGoalB.uuid)
            assertEquals(60.0, appliedGoalB.targetValue)
            assertEquals(idB, appliedGoalB.habitId)

            // -----------------------------------------------------------------
            // Part B: Collision Isolation with Legacy local-habitId-prefixed UUIDs
            // -----------------------------------------------------------------
            // Suppose Device B already had a local goal created under a legacy local habitId prefix ("5:goal:...")
            val collisionDate = LocalDate(2026, 11, 1)
            val collisionTimestamp = collisionDate.unixTime
            val localLegacyGoalB = HabitGoalData(
                habitId = idB,
                uuid = "$idB:goal:$collisionTimestamp", // "5:goal:1793404800"
                effectiveTimestamp = collisionTimestamp,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 45.0,
                unit = "min",
                updatedAt = 100L
            )
            factoryB.habitGoalRepository.upsert(localLegacyGoalB)

            // Remote legacy event from Device A arrives for the same habit & timestamp,
            // but with Device A's local-habitId prefix ("1:goal:1793404800") and different target (90.0)
            val remoteLegacyGoalA = HabitGoalData(
                habitId = idB, // mapped to local habit on Device B
                uuid = "$idA:goal:$collisionTimestamp", // "1:goal:1793404800" - DIVERGENT UUID!
                effectiveTimestamp = collisionTimestamp,
                freqNum = 1,
                freqDen = 1,
                targetType = 0,
                targetValue = 90.0,
                unit = "min",
                updatedAt = 200L
            )

            // Direct upsert on HabitGoalRepository detects collision and throws HabitGoalCollisionException
            val collisionEx = assertFailsWith<HabitGoalCollisionException> {
                factoryB.habitGoalRepository.upsert(remoteLegacyGoalA)
            }
            assertEquals("$idB:goal:$collisionTimestamp", collisionEx.existingUuid)
            assertEquals("$idA:goal:$collisionTimestamp", collisionEx.conflictingUuid)

            // The SyncInbox isolates the colliding event without corrupting the existing goal
            val inboxRecord = SyncInboxRecord(
                logId = 9999L,
                deviceId = "device-A",
                opUuid = "op-legacy-collision-99",
                entityType = "habit_goal",
                entityUuid = "$idA:goal:$collisionTimestamp",
                operationType = "upsert",
                payloadJson = """{"habit_uuid":"$sharedHabitUuid","effective_timestamp":$collisionTimestamp,"target_value":90.0}""",
                createdAt = 200L,
                receivedAt = 250L,
                deferredReason = "GOAL_COLLISION",
                status = "PENDING",
                attempts = 0
            )
            factoryB.syncInboxRepository.insertOrUpdate(inboxRecord)

            // Verify local goal remains untouched on Device B
            val intactGoal = factoryB.habitGoalRepository.findByHabitAndTimestamp(idB, collisionTimestamp)!!
            assertEquals(45.0, intactGoal.targetValue, "Local goal must NOT be corrupted by colliding remote goal")
            assertEquals("$idB:goal:$collisionTimestamp", intactGoal.uuid)

            // Simulate repeated retries (attempts 1..4)
            for (attempt in 1..4) {
                factoryB.syncInboxRepository.recordAttempt(
                    logId = 9999L,
                    now = 300L + attempt * 1000L,
                    newReason = "GOAL_COLLISION",
                    maxAttempts = 5
                )
                val pending = factoryB.syncInboxRepository.findByEntity("habit_goal", "$idA:goal:$collisionTimestamp").single()
                assertEquals(attempt, pending.attempts)
                assertEquals("PENDING", pending.status)
            }

            // 5th attempt reaches maxAttempts (5) and marks status as SUSPENDED
            factoryB.syncInboxRepository.recordAttempt(
                logId = 9999L,
                now = 8000L,
                newReason = "GOAL_COLLISION",
                maxAttempts = 5
            )
            val suspended = factoryB.syncInboxRepository.findByEntity("habit_goal", "$idA:goal:$collisionTimestamp").single()
            assertEquals(5, suspended.attempts)
            assertEquals("SUSPENDED", suspended.status)

            // Suspended record is omitted from active pending queries
            val activePending = factoryB.syncInboxRepository.findPending(maxAttempts = 5)
            assertFalse(activePending.any { it.logId == 9999L }, "Suspended goal collision must be excluded from active pending sync")

            // Local goal remains strictly intact
            val finalGoal = factoryB.habitGoalRepository.findByHabitAndTimestamp(idB, collisionTimestamp)!!
            assertEquals(45.0, finalGoal.targetValue)
            assertEquals("$idB:goal:$collisionTimestamp", finalGoal.uuid)

        } finally {
            dbA.close()
            dbB.close()
        }
    }
}
