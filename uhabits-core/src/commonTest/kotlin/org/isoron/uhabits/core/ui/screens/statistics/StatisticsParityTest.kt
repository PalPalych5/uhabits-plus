/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.core.ui.screens.statistics

import kotlinx.coroutines.test.runTest
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitGoal
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshotProvider
import org.isoron.uhabits.core.models.projection.EffectiveReadCalendarContext
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val Habit.ref: HabitRef?
    get() = uuid?.let { HabitRef(it) }

/**
 * Genuine unoptimized baseline oracle reproducing StatisticsReportStateBuilder
 * logic prior to commit 4da9993f.
 */
private object BaselineOracle {

    fun getHabitStartDate(habit: Habit, today: LocalDate, snapshot: EffectiveHabitReadSnapshot? = null): LocalDate {
        val startAttr = habit.effectiveStatisticsStartDate()
        if (startAttr != null) return startAttr
        val firstEntry = habit.originalEntries.getKnown().minByOrNull { it.date }?.date
        val observedFrom = snapshot?.observedFrom
        val earliest = listOfNotNull(firstEntry, observedFrom).minOrNull()
        if (earliest != null) return earliest
        return today
    }

    fun calculateOverallProgressForRange(
        habits: List<Habit>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ): Double? {
        val effectiveEnd = if (end.isNewerThan(today)) today else end
        if (start.isNewerThan(effectiveEnd)) return null
        val progresses = habits.mapNotNull { habit ->
            val snapshot = snapshots?.get(habit.ref)
            val habitStart = getHabitStartDate(habit, today, snapshot)
            if (habitStart > effectiveEnd) null
            else StatisticsReportStateBuilder.evaluateHabitSlice(habit, maxOf(start, habitStart), effectiveEnd, snapshot)?.progress
        }
        return if (progresses.isNotEmpty()) progresses.average() else null
    }

    fun build(
        habits: List<Habit>,
        period: StatisticsPeriod,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        filters: StatisticsFilterState,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ): StatisticsReportState {
        val filteredHabits = habits.filter { habit ->
            val matchSphere = if (filters.allowedHabitUuids != null) {
                filters.allowedHabitUuids.contains(habit.uuid)
            } else {
                !filters.spheresEnabled || filters.sphereId == null || habit.blockId == filters.sphereId
            }
            val matchStatus = when (filters.habitStatus) {
                StatisticsHabitStatusFilter.ACTIVE -> !habit.isArchived
                StatisticsHabitStatusFilter.ARCHIVED -> habit.isArchived
                StatisticsHabitStatusFilter.ALL -> true
            }
            val matchGoalType = when (filters.goalType) {
                StatisticsGoalTypeFilter.YES_NO -> habit.type == HabitType.YES_NO
                StatisticsGoalTypeFilter.NUMERICAL -> habit.type == HabitType.NUMERICAL
                StatisticsGoalTypeFilter.ALL -> true
            }
            matchSphere && matchStatus && matchGoalType
        }

        val scopeHabits = if (!filters.dayTiersEnabled) {
            filteredHabits
        } else {
            filteredHabits.filter { filters.tierScope.includes(it.dayTier) }
        }

        val earliestStart = filteredHabits.map { getHabitStartDate(it, today, snapshots?.get(it.ref)) }.minOrNull() ?: today
        val actualStart = if (period == StatisticsPeriod.ALL) earliestStart else start

        // 1. Per-habit evaluation over period (equal weight macro-average)
        val habitResults = filteredHabits.mapNotNull { habit ->
            val snapshot = snapshots?.get(habit.ref)
            val presentation = snapshot?.presentationHabit() ?: habit
            val relevantPeriods = StatisticsReportStateBuilder.getPeriodsForReport(habit, actualStart, end, today, firstWeekday, snapshot)
            val evaluated = relevantPeriods.map { StatisticsReportStateBuilder.evaluatePeriod(habit, it, today, snapshot) }
            val valid = evaluated.filter { it.progress != null }
            if (valid.isEmpty() && evaluated.none { it.status == StatisticsResultStatus.SKIPPED || it.actual > 0.0 }) {
                null
            } else {
                val actual = evaluated.sumOf { it.actual }
                val target = evaluated.sumOf { it.target }
                val progress = if (valid.isEmpty()) 0.0 else valid.map { it.progress!! }.average()

                val status = when {
                    evaluated.any { it.status == StatisticsResultStatus.VIOLATED } -> StatisticsResultStatus.VIOLATED
                    evaluated.any { it.status == StatisticsResultStatus.MISSED } -> StatisticsResultStatus.MISSED
                    evaluated.any { it.status == StatisticsResultStatus.PENDING } -> StatisticsResultStatus.PENDING
                    evaluated.any { it.status == StatisticsResultStatus.ON_TRACK } -> StatisticsResultStatus.ON_TRACK
                    evaluated.all { it.status == StatisticsResultStatus.SKIPPED } -> StatisticsResultStatus.SKIPPED
                    else -> StatisticsResultStatus.COMPLETED
                }

                StatisticsHabitResult(
                    habit = presentation,
                    progress = progress,
                    actual = actual,
                    target = target,
                    unit = habit.unit,
                    status = status,
                    skippedPeriods = evaluated.count { it.status == StatisticsResultStatus.SKIPPED }
                )
            }
        }

        val countableResults = habitResults.filter { it.status != StatisticsResultStatus.SKIPPED }

        // 2. Tier progress
        val tierProgress = DayTier.entries.associateWith { tier ->
            val tierHabits = filteredHabits.filter { it.dayTier == tier }
            if (tierHabits.isEmpty()) null
            else {
                val tierResults = countableResults.filter { it.habit.dayTier == tier }
                if (tierResults.isNotEmpty()) tierResults.map { it.progress }.average()
                else null
            }
        }
        val tierCounts = DayTier.entries.associateWith { tier ->
            val tierResults = countableResults.filter { it.habit.dayTier == tier }
            val completed = tierResults.count { it.progress >= 1.0 }
            Pair(completed, tierResults.size)
        }
        val minimumProgress = tierProgress[DayTier.MINIMUM]

        val countableScopeResults = countableResults.filter {
            !filters.dayTiersEnabled || filters.tierScope.includes(it.habit.dayTier)
        }
        val overallProgress = if (countableScopeResults.isNotEmpty()) {
            countableScopeResults.map { it.progress }.average()
        } else null

        // 3. Daily progress & fully closed days over active scope
        val dailyList = mutableListOf<StatisticsDailyProgress>()
        var hasAnyDailyMinimumGoals = false
        val dailyEligibleScopeHabits = scopeHabits.filter { habit ->
            val hStart = getHabitStartDate(habit, today, snapshots?.get(habit.ref))
            hStart <= end && (habit.frequency.denominator == 1 || habit.goalHistory.any { it.frequency.denominator == 1 })
        }

        var d = actualStart
        while (d <= end) {
            val isFuture = d > today
            val isToday = d == today
            if (isFuture) {
                dailyList.add(
                    StatisticsDailyProgress(
                        date = d,
                        progress = null,
                        isEligible = false,
                        isFullyClosed = false,
                        isSkipped = false,
                        isToday = false,
                        isFuture = true
                    )
                )
            } else {
                val dailyScopeHabits = scopeHabits.filter { habit ->
                    getHabitStartDate(habit, today, snapshots?.get(habit.ref)) <= d &&
                        habit.goalAt(d).frequency.denominator == 1
                }
                if (dailyScopeHabits.isNotEmpty()) {
                    hasAnyDailyMinimumGoals = true
                }
                val evals = dailyScopeHabits.map { habit ->
                    val snapshot = snapshots?.get(habit.ref)
                    val goal = habit.goalAt(d)
                    val p = GoalPeriod(d, d, goal.frequency, goal.targetType, goal.targetValue, goal.unit)
                    StatisticsReportStateBuilder.evaluatePeriod(habit, p, today, snapshot)
                }
                val nonSkipped = evals.filter { it.status != StatisticsResultStatus.SKIPPED }
                val isSkipped = evals.isNotEmpty() && nonSkipped.isEmpty()

                if (nonSkipped.isEmpty()) {
                    dailyList.add(
                        StatisticsDailyProgress(
                            date = d,
                            progress = null,
                            isEligible = false,
                            isFullyClosed = false,
                            isSkipped = isSkipped,
                            isToday = isToday,
                            isFuture = false
                        )
                    )
                } else {
                    val valid = nonSkipped.mapNotNull { it.progress }
                    val dayProgress = if (valid.isNotEmpty()) valid.average() else null
                    val isEligible = !isToday
                    val isFullyClosed = isEligible && nonSkipped.all {
                        ((it.progress != null && it.progress >= 0.9999) || it.status == StatisticsResultStatus.COMPLETED) &&
                            it.status != StatisticsResultStatus.PENDING
                    }
                    dailyList.add(
                        StatisticsDailyProgress(
                            date = d,
                            progress = dayProgress,
                            isEligible = isEligible,
                            isFullyClosed = isFullyClosed,
                            isSkipped = false,
                            isToday = isToday,
                            isFuture = false
                        )
                    )
                }
            }
            d = d.plus(1)
        }

        val fullyClosedDays = dailyList.count { it.isFullyClosed }
        val eligibleFinishedDays = dailyList.count { it.isEligible }

        val observedDays = dailyList.filter { it.date <= today && !it.isFuture && it.progress != null }
        val averageDailyProgress = if (observedDays.isNotEmpty()) observedDays.mapNotNull { it.progress }.average() else null
        val bestDailyProgress = observedDays
            .sortedWith(compareByDescending<StatisticsDailyProgress> { it.progress }.thenByDescending { it.date })
            .firstOrNull()
            ?.let { it.date to (it.progress ?: 0.0) }

        // 4. Day-specific metrics
        val todayMinimumCount: Pair<Int, Int>?
        val remainingMinimumHabits: List<StatisticsHabitResult>
        if (period == StatisticsPeriod.DAY) {
            val dayScopeHabits = scopeHabits.filter { habit ->
                getHabitStartDate(habit, today, snapshots?.get(habit.ref)) <= start &&
                    habit.goalAt(start).frequency.denominator == 1
            }
            val dayEvals = dayScopeHabits.map { habit ->
                val snapshot = snapshots?.get(habit.ref)
                val presentation = snapshot?.presentationHabit() ?: habit
                val goal = habit.goalAt(start)
                val p = GoalPeriod(start, start, goal.frequency, goal.targetType, goal.targetValue, goal.unit)
                presentation to StatisticsReportStateBuilder.evaluatePeriod(habit, p, today, snapshot)
            }
            val nonSkippedEvals = dayEvals.filter { it.second.status != StatisticsResultStatus.SKIPPED }
            todayMinimumCount = if (nonSkippedEvals.isNotEmpty()) {
                val completed = nonSkippedEvals.count {
                    ((it.second.progress != null && it.second.progress!! >= 0.9999) || it.second.status == StatisticsResultStatus.COMPLETED) &&
                        it.second.status != StatisticsResultStatus.PENDING
                }
                Pair(completed, nonSkippedEvals.size)
            } else null

            remainingMinimumHabits = nonSkippedEvals
                .filter { (it.second.progress == null || it.second.progress!! < 0.9999) && it.second.status != StatisticsResultStatus.COMPLETED }
                .map { (habit, eval) ->
                    StatisticsHabitResult(
                        habit = habit,
                        progress = eval.progress ?: 0.0,
                        actual = eval.actual,
                        target = eval.target,
                        unit = eval.period.unit,
                        status = eval.status
                    )
                }
        } else {
            todayMinimumCount = null
            remainingMinimumHabits = emptyList()
        }

        // 5. Comparison delta & Habit Changes
        val comparisonWindow = StatisticsReportStateBuilder.getComparisonWindow(actualStart, end, period, today)
        val comparisonDelta: Double?
        val habitChanges: List<StatisticsHabitChange>

        if (comparisonWindow == null) {
            comparisonDelta = null
            habitChanges = emptyList()
        } else {
            val sliceDays = comparisonWindow.currentStart.daysUntil(comparisonWindow.currentEnd) + 1
            val currProgresses = scopeHabits.mapNotNull { habit ->
                val snapshot = snapshots?.get(habit.ref)
                val habitStart = getHabitStartDate(habit, today, snapshot)
                if (habitStart > comparisonWindow.currentEnd) null
                else StatisticsReportStateBuilder.evaluateHabitSlice(habit, maxOf(comparisonWindow.currentStart, habitStart), comparisonWindow.currentEnd, snapshot)?.progress
            }
            val prevProgresses = scopeHabits.mapNotNull { habit ->
                val snapshot = snapshots?.get(habit.ref)
                val habitStart = getHabitStartDate(habit, today, snapshot)
                if (habitStart > comparisonWindow.previousEnd) null
                else StatisticsReportStateBuilder.evaluateHabitSlice(habit, maxOf(comparisonWindow.previousStart, habitStart), comparisonWindow.previousEnd, snapshot)?.progress
            }

            comparisonDelta = if (currProgresses.isNotEmpty() && prevProgresses.isNotEmpty()) {
                currProgresses.average() - prevProgresses.average()
            } else null

            habitChanges = scopeHabits.mapNotNull { habit ->
                val snapshot = snapshots?.get(habit.ref)
                val presentation = snapshot?.presentationHabit() ?: habit
                val habitStart = getHabitStartDate(habit, today, snapshot)
                if (habitStart.isNewerThan(comparisonWindow.previousStart) || habit.isArchived) {
                    return@mapNotNull null
                }
                val goal = habit.goalAt(comparisonWindow.currentEnd)
                if (goal.frequency.denominator > 1 && sliceDays < 4) {
                    return@mapNotNull null
                }
                val currEval = StatisticsReportStateBuilder.evaluateHabitSlice(habit, comparisonWindow.currentStart, comparisonWindow.currentEnd, snapshot)
                val prevEval = StatisticsReportStateBuilder.evaluateHabitSlice(habit, comparisonWindow.previousStart, comparisonWindow.previousEnd, snapshot)
                if (currEval?.progress == null || prevEval?.progress == null) {
                    return@mapNotNull null
                }
                if (currEval.observations < 2 || prevEval.observations < 2) {
                    return@mapNotNull null
                }
                if (!currEval.hasEntries && !prevEval.hasEntries) {
                    return@mapNotNull null
                }
                val points = ((currEval.progress - prevEval.progress) * 100).roundToInt()
                if (points == 0) null
                else StatisticsHabitChange(presentation, points, currEval.progress, prevEval.progress)
            }.sortedByDescending { kotlin.math.abs(it.deltaPoints) }.take(3)
        }

        // 6. Trend buckets
        val trendBuckets = when (period) {
            StatisticsPeriod.DAY, StatisticsPeriod.MONTH -> emptyList()
            StatisticsPeriod.WEEK -> {
                val currentStart = actualStart
                (0..6).map { i ->
                    val day = currentStart.plus(i)
                    val prog = dailyList.firstOrNull { it.date == day }?.progress
                    StatisticsBucket(day, day, prog)
                }
            }
            StatisticsPeriod.YEAR -> {
                (1..12).map { month ->
                    val mStart = LocalDate(actualStart.year, month, 1)
                    val mEnd = mStart.plus(mStart.monthLength - 1)
                    val prog = if (mStart <= today) {
                        calculateOverallProgressForRange(scopeHabits, mStart, mEnd, today, firstWeekday, snapshots)
                    } else null
                    StatisticsBucket(mStart, mEnd, prog)
                }
            }
            StatisticsPeriod.ALL -> {
                val totalMonths = (end.year - actualStart.year) * 12 + (end.month - actualStart.month) + 1
                if (totalMonths < 24) {
                    val buckets = mutableListOf<StatisticsBucket>()
                    var currMonth = actualStart.startOfMonth()
                    while (currMonth <= end) {
                        val mEnd = currMonth.plus(currMonth.monthLength - 1)
                        val prog = calculateOverallProgressForRange(scopeHabits, currMonth, mEnd, today, firstWeekday, snapshots)
                        buckets.add(StatisticsBucket(currMonth, mEnd, prog))
                        currMonth = currMonth.plus(currMonth.monthLength)
                    }
                    buckets
                } else {
                    (actualStart.year..end.year).map { y ->
                        val yStart = LocalDate(y, 1, 1)
                        val yEnd = LocalDate(y, 12, 31)
                        val prog = calculateOverallProgressForRange(scopeHabits, yStart, yEnd, today, firstWeekday, snapshots)
                        StatisticsBucket(yStart, yEnd, prog)
                    }
                }
            }
        }

        // 7. Weekday rhythm
        val weekdayRhythm = if (period == StatisticsPeriod.MONTH || period == StatisticsPeriod.YEAR) {
            DayOfWeek.entries.map { dow ->
                val matchingDays = dailyList.filter {
                    it.date.dayOfWeek == dow && it.date <= today && !it.isFuture && it.progress != null
                }
                val observations = matchingDays.size
                val progress = if (observations >= 4) {
                    matchingDays.mapNotNull { it.progress }.average()
                } else null
                StatisticsWeekdayPatternItem(dow, observations, progress)
            }
        } else emptyList()

        // 8. Sphere progress
        val sphereProgress = if (!filters.spheresEnabled) {
            emptyList()
        } else {
            val sphereResults = countableResults.filter { it.habit.blockId != null }
            sphereResults.groupBy { it.habit.blockId }.map { (blockId, items) ->
                val scopedHabits = items.filter { !filters.dayTiersEnabled || filters.tierScope.includes(it.habit.dayTier) }
                val progress = if (scopedHabits.isNotEmpty()) {
                    scopedHabits.map { it.progress }.average()
                } else null
                StatisticsSphereProgress(blockId, progress, items.size)
            }
        }

        // 9. Habit stability
        val habitStability = if (period == StatisticsPeriod.ALL) {
            filteredHabits.mapNotNull { habit ->
                val snapshot = snapshots?.get(habit.ref)
                val presentation = snapshot?.presentationHabit() ?: habit
                val score = habit.scores.get(today).value
                StatisticsHabitStabilityItem(presentation, score)
            }.sortedByDescending { it.score }
        } else emptyList()

        return StatisticsReportState(
            period = period,
            start = actualStart,
            end = end,
            matchingHabits = countableScopeResults.size,
            hasDailyMinimumGoals = hasAnyDailyMinimumGoals,
            overallProgress = overallProgress,
            minimumProgress = minimumProgress,
            tierProgress = tierProgress,
            tierCounts = tierCounts,
            comparisonDelta = comparisonDelta,
            fullyClosedDays = fullyClosedDays,
            eligibleFinishedDays = eligibleFinishedDays,
            dailyProgress = dailyList,
            trendBuckets = trendBuckets,
            weekdayRhythm = weekdayRhythm,
            sphereProgress = sphereProgress,
            remainingMinimumHabits = remainingMinimumHabits,
            habitStability = habitStability,
            todayMinimumCount = todayMinimumCount,
            completedHabits = countableScopeResults.count { it.progress >= 1.0 },
            habits = habitResults,
            habitChanges = habitChanges,
            tierScope = filters.tierScope,
            dayTiersEnabled = filters.dayTiersEnabled,
            spheresEnabled = filters.spheresEnabled,
            averageDailyProgress = averageDailyProgress,
            bestDailyProgress = bestDailyProgress
        )
    }
}

class StatisticsParityTest : BaseUnitTest() {

    private fun assertReportsEqual(expected: StatisticsReportState, actual: StatisticsReportState) {
        assertEquals(expected.period, actual.period, "period mismatch")
        assertEquals(expected.start, actual.start, "start date mismatch")
        assertEquals(expected.end, actual.end, "end date mismatch")
        assertEquals(expected.matchingHabits, actual.matchingHabits, "matchingHabits mismatch")
        assertEquals(expected.hasDailyMinimumGoals, actual.hasDailyMinimumGoals, "hasDailyMinimumGoals mismatch")
        assertEquals(expected.fullyClosedDays, actual.fullyClosedDays, "fullyClosedDays mismatch")
        assertEquals(expected.eligibleFinishedDays, actual.eligibleFinishedDays, "eligibleFinishedDays mismatch")
        assertEquals(expected.completedHabits, actual.completedHabits, "completedHabits mismatch")

        assertDoubleNullableEquals(expected.overallProgress, actual.overallProgress, "overallProgress")
        assertDoubleNullableEquals(expected.minimumProgress, actual.minimumProgress, "minimumProgress")
        assertDoubleNullableEquals(expected.comparisonDelta, actual.comparisonDelta, "comparisonDelta")
        assertDoubleNullableEquals(expected.averageDailyProgress, actual.averageDailyProgress, "averageDailyProgress")

        if (expected.bestDailyProgress == null) {
            assertNull(actual.bestDailyProgress, "bestDailyProgress should be null")
        } else {
            assertNotNull(actual.bestDailyProgress, "bestDailyProgress should not be null")
            assertEquals(expected.bestDailyProgress!!.first, actual.bestDailyProgress!!.first, "bestDailyProgress date")
            assertEquals(expected.bestDailyProgress!!.second, actual.bestDailyProgress!!.second, 1e-9, "bestDailyProgress progress")
        }

        assertEquals(expected.tierCounts, actual.tierCounts, "tierCounts")
        for (tier in DayTier.entries) {
            assertDoubleNullableEquals(expected.tierProgress[tier], actual.tierProgress[tier], "tierProgress[$tier]")
        }

        assertEquals(expected.dailyProgress.size, actual.dailyProgress.size, "dailyProgress.size mismatch")
        expected.dailyProgress.zip(actual.dailyProgress).forEachIndexed { i, (exp, act) ->
            assertEquals(exp.date, act.date, "dailyProgress[$i].date")
            assertDoubleNullableEquals(exp.progress, act.progress, "dailyProgress[$i].progress on ${exp.date}")
            assertEquals(exp.isEligible, act.isEligible, "dailyProgress[$i].isEligible on ${exp.date}")
            assertEquals(exp.isFullyClosed, act.isFullyClosed, "dailyProgress[$i].isFullyClosed on ${exp.date}")
            assertEquals(exp.isSkipped, act.isSkipped, "dailyProgress[$i].isSkipped on ${exp.date}")
            assertEquals(exp.isToday, act.isToday, "dailyProgress[$i].isToday on ${exp.date}")
            assertEquals(exp.isFuture, act.isFuture, "dailyProgress[$i].isFuture on ${exp.date}")
        }

        assertEquals(expected.trendBuckets.size, actual.trendBuckets.size, "trendBuckets.size mismatch")
        expected.trendBuckets.zip(actual.trendBuckets).forEachIndexed { i, (exp, act) ->
            assertEquals(exp.start, act.start, "trendBuckets[$i].start")
            assertEquals(exp.end, act.end, "trendBuckets[$i].end")
            assertDoubleNullableEquals(exp.progress, act.progress, "trendBuckets[$i].progress")
        }

        assertEquals(expected.weekdayRhythm.size, actual.weekdayRhythm.size, "weekdayRhythm.size mismatch")
        expected.weekdayRhythm.zip(actual.weekdayRhythm).forEachIndexed { i, (exp, act) ->
            assertEquals(exp.dayOfWeek, act.dayOfWeek, "weekdayRhythm[$i].dayOfWeek")
            assertEquals(exp.observations, act.observations, "weekdayRhythm[$i].observations")
            assertDoubleNullableEquals(exp.progress, act.progress, "weekdayRhythm[$i].progress")
        }

        assertEquals(expected.habits.size, actual.habits.size, "habits.size mismatch")
        expected.habits.zip(actual.habits).forEachIndexed { i, (exp, act) ->
            assertEquals(exp.habit.uuid, act.habit.uuid, "habits[$i].habit.uuid")
            assertEquals(exp.status, act.status, "habits[$i].status")
            assertEquals(exp.skippedPeriods, act.skippedPeriods, "habits[$i].skippedPeriods")
            assertEquals(exp.actual, act.actual, 1e-9, "habits[$i].actual")
            assertEquals(exp.target, act.target, 1e-9, "habits[$i].target")
            assertDoubleNullableEquals(exp.progress, act.progress, "habits[$i].progress")
        }

        assertEquals(expected.habitChanges.size, actual.habitChanges.size, "habitChanges.size mismatch")
        expected.habitChanges.zip(actual.habitChanges).forEachIndexed { i, (exp, act) ->
            assertEquals(exp.habit.uuid, act.habit.uuid, "habitChanges[$i].habit.uuid")
            assertEquals(exp.deltaPoints, act.deltaPoints, "habitChanges[$i].deltaPoints")
            assertEquals(exp.currentProgress, act.currentProgress, 1e-9, "habitChanges[$i].currentProgress")
            assertEquals(exp.previousProgress, act.previousProgress, 1e-9, "habitChanges[$i].previousProgress")
        }

        assertEquals(expected.todayMinimumCount, actual.todayMinimumCount, "todayMinimumCount mismatch")
    }

    private fun assertDoubleNullableEquals(expected: Double?, actual: Double?, message: String) {
        if (expected == null) {
            assertNull(actual, "$message should be null")
        } else {
            assertNotNull(actual, "$message should not be null")
            assertEquals(expected, actual, 1e-9, message)
        }
    }

    @Test
    fun testParityDailyAndMixedHabitsAcrossAllPeriods() {
        val today = LocalDate(2026, 9, 29)
        val h1 = fixtures.createEmptyHabit().apply {
            name = "Daily Minimal"
            dayTier = DayTier.MINIMUM
            frequency = Frequency.DAILY
            for (i in 0..60) {
                val d = today.minus(i)
                when (i % 5) {
                    0, 1 -> originalEntries.add(Entry(d, Entry.YES_MANUAL))
                    2 -> originalEntries.add(Entry(d, Entry.SKIP))
                    3 -> originalEntries.add(Entry(d, Entry.NO))
                    // 4 is UNKNOWN / absent
                }
            }
            recompute()
        }

        val h2 = fixtures.createEmptyHabit().apply {
            name = "Weekly 3x"
            dayTier = DayTier.NORMAL
            frequency = Frequency(3, 7)
            for (i in 0..60) {
                val d = today.minus(i)
                if (i % 2 == 0) originalEntries.add(Entry(d, Entry.YES_MANUAL))
            }
            recompute()
        }

        val habits = listOf(h1, h2)
        val periods = listOf(
            Triple(StatisticsPeriod.DAY, today, today),
            Triple(StatisticsPeriod.WEEK, today.minus(6), today),
            Triple(StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)),
            Triple(StatisticsPeriod.YEAR, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31)),
            Triple(StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today)
        )

        for ((period, start, end) in periods) {
            val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
            val baseline = BaselineOracle.build(habits, period, start, end, today, DayOfWeek.MONDAY, filters)
            val candidate = StatisticsReportStateBuilder.build(habits, period, start, end, today, DayOfWeek.MONDAY, filters)
            assertReportsEqual(baseline, candidate)
        }
    }

    @Test
    fun testParityNumericalAtLeastAndAtMost() {
        val today = LocalDate(2026, 9, 29)
        val hAtLeast = fixtures.createEmptyHabit().apply {
            name = "At Least Steps"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 10000.0
            unit = "steps"
            dayTier = DayTier.MINIMUM
            frequency = Frequency.DAILY
            for (i in 0..45) {
                val d = today.minus(i)
                val steps = (8000 + (i * 250) % 5000) * 1000 // multiplied by 1000 for precision storage
                originalEntries.add(Entry(d, steps))
            }
            recompute()
        }

        val hAtMost = fixtures.createEmptyHabit().apply {
            name = "At Most Coffee"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 2.0
            unit = "cups"
            dayTier = DayTier.OPTIONAL
            frequency = Frequency.DAILY
            for (i in 0..45) {
                val d = today.minus(i)
                val cups = (i % 4) * 1000
                originalEntries.add(Entry(d, cups))
            }
            recompute()
        }

        val habits = listOf(hAtLeast, hAtMost)
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)

        for (period in listOf(StatisticsPeriod.WEEK, StatisticsPeriod.MONTH)) {
            val start = if (period == StatisticsPeriod.WEEK) today.minus(6) else LocalDate(2026, 9, 1)
            val end = if (period == StatisticsPeriod.WEEK) today else LocalDate(2026, 9, 30)
            val baseline = BaselineOracle.build(habits, period, start, end, today, DayOfWeek.MONDAY, filters)
            val candidate = StatisticsReportStateBuilder.build(habits, period, start, end, today, DayOfWeek.MONDAY, filters)
            assertReportsEqual(baseline, candidate)
        }
    }

    @Test
    fun testParityHistoricalGoalsWithDuplicateAndDescendingDates() {
        val today = LocalDate(2026, 9, 29)
        val h = fixtures.createEmptyHabit().apply {
            name = "Complex Historical Goals"
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 20.0
            unit = "km"
            frequency = Frequency.DAILY
            dayTier = DayTier.MINIMUM
            // Historical goals with duplicate dates and unsorted dates
            goalHistory = mutableListOf(
                HabitGoal(LocalDate(2026, 9, 15), Frequency.DAILY, NumericalHabitType.AT_LEAST, 20.0, "km"),
                HabitGoal(LocalDate(2026, 8, 1), Frequency.DAILY, NumericalHabitType.AT_LEAST, 10.0, "km"),
                HabitGoal(LocalDate(2026, 8, 15), Frequency.DAILY, NumericalHabitType.AT_LEAST, 12.0, "km"),
                HabitGoal(LocalDate(2026, 8, 15), Frequency.DAILY, NumericalHabitType.AT_LEAST, 15.0, "km"), // Duplicate date overrides previous
                HabitGoal(LocalDate(2026, 7, 1), Frequency(3, 7), NumericalHabitType.AT_LEAST, 5.0, "km")
            )
            for (i in 0..90) {
                val d = today.minus(i)
                originalEntries.add(Entry(d, ((i % 25) + 5) * 1000))
            }
            recompute()
        }

        val habits = listOf(h)
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)

        val baselineMonth = BaselineOracle.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters)
        val candidateMonth = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(baselineMonth, candidateMonth)

        val baselineAll = BaselineOracle.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters)
        val candidateAll = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(baselineAll, candidateAll)
    }

    @Test
    fun testParityConfiguredStatisticsStartDates() {
        val today = LocalDate(2026, 9, 29)
        val h = fixtures.createEmptyHabit().apply {
            name = "Custom Start Dates"
            dayTier = DayTier.MINIMUM
            statisticsStartDate = LocalDate(2026, 9, 10)
            globalStatisticsStartDate = LocalDate(2026, 9, 5)
            // Entries before and after statistics start
            for (i in 0..30) {
                val d = today.minus(i)
                originalEntries.add(Entry(d, Entry.YES_MANUAL))
            }
            recompute()
        }

        val habits = listOf(h)
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)

        val baselineMonth = BaselineOracle.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters)
        val candidateMonth = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(baselineMonth, candidateMonth)

        val baselineAll = BaselineOracle.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters)
        val candidateAll = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(baselineAll, candidateAll)
    }

    @Test
    fun testParitySQLiteBackedHabits() = runTest {
        val db = buildMemoryDatabase()
        val sqlFactory = SQLModelFactory(db)
        val habitList = sqlFactory.buildHabitList()

        val h1 = sqlFactory.buildHabit().apply {
            name = "SQLite Habit 1"
            dayTier = DayTier.MINIMUM
            frequency = Frequency.DAILY
        }
        habitList.add(h1)

        val h2 = sqlFactory.buildHabit().apply {
            name = "SQLite Habit 2"
            dayTier = DayTier.NORMAL
            frequency = Frequency.DAILY
        }
        habitList.add(h2)

        val today = LocalDate(2026, 9, 29)
        val habitId1 = h1.id!!
        val habitId2 = h2.id!!

        // Insert directly into DB without populating in-memory entries cache
        for (i in 0..30) {
            val d = today.minus(i)
            sqlFactory.entryRepository.insert(
                EntryData(
                    habitId = habitId1,
                    timestamp = d.unixTime,
                    value = if (i % 2 == 0) Entry.YES_MANUAL else Entry.NO
                )
            )
            sqlFactory.entryRepository.insert(
                EntryData(
                    habitId = habitId2,
                    timestamp = d.unixTime,
                    value = if (i % 3 == 0) Entry.YES_MANUAL else Entry.SKIP
                )
            )
        }

        // Invalidate to ensure entry lists are not loaded
        (h1.originalEntries as SQLiteEntryList).invalidate()
        (h2.originalEntries as SQLiteEntryList).invalidate()
        assertEquals(false, (h1.originalEntries as SQLiteEntryList).isLoaded)
        assertEquals(false, (h2.originalEntries as SQLiteEntryList).isLoaded)

        val habits = listOf(h1, h2)
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)

        val baselineMonth = BaselineOracle.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters)
        val candidateMonth = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(baselineMonth, candidateMonth)

        // After build, entries should be loaded
        assertEquals(true, (h1.originalEntries as SQLiteEntryList).isLoaded)
        assertEquals(true, (h2.originalEntries as SQLiteEntryList).isLoaded)

        val baselineAll = BaselineOracle.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters)
        val candidateAll = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(baselineAll, candidateAll)
    }

    @Test
    fun testDistinctHabitsWithMissingOrCollidingExternalIdentities() {
        val today = LocalDate(2026, 9, 29)
        val first = fixtures.createEmptyHabit().apply {
            name = "same name"
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today.minus(20)
            for (i in 0..20) originalEntries.add(Entry(today.minus(i), Entry.YES_MANUAL))
            recompute()
        }
        val second = fixtures.createEmptyHabit().apply {
            name = "same name"
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today.minus(5)
            for (i in 0..5) originalEntries.add(Entry(today.minus(i), Entry.NO))
            recompute()
        }
        val habits = listOf(first, second)
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        fun assertParity() {
            for (period in listOf(StatisticsPeriod.MONTH, StatisticsPeriod.ALL)) {
                val expected = BaselineOracle.build(habits, period, LocalDate(2026, 9, 1), today,
                    today, DayOfWeek.MONDAY, filters)
                val actual = StatisticsReportStateBuilder.build(habits, period, LocalDate(2026, 9, 1), today,
                    today, DayOfWeek.MONDAY, filters)
                assertReportsEqual(expected, actual)
                if (period == StatisticsPeriod.ALL) assertEquals(today.minus(20), actual.start)
            }
        }
        // Public model setters can clear identifiers after construction.
        first.uuid = null
        first.id = null
        second.uuid = null
        second.id = null
        assertParity()
        // A synthetic local-ID key must not alias a valid UUID string.
        first.uuid = "id:42"
        second.id = 42
        assertParity()
        // Copies may share entry lists but remain distinct models with different goals.
        val copy = first.copy(targetValue = 2.0, type = HabitType.NUMERICAL)
        val expected = BaselineOracle.build(listOf(first, copy), StatisticsPeriod.MONTH,
            LocalDate(2026, 9, 1), today, today, DayOfWeek.MONDAY, filters)
        val actual = StatisticsReportStateBuilder.build(listOf(first, copy), StatisticsPeriod.MONTH,
            LocalDate(2026, 9, 1), today, today, DayOfWeek.MONDAY, filters)
        assertReportsEqual(expected, actual)
    }
    @Test
    fun testCombinedHydrationGroupedMutationsAndImmediateStatistics() = runTest {
        val today = LocalDate(2026, 9, 29)
        val db = buildMemoryDatabase()
        val factory = SQLModelFactory(db)
        val persisted = factory.buildHabitList()
        val boolean = factory.buildHabit().apply {
            name = "Boolean"
            dayTier = DayTier.MINIMUM
            statisticsStartDate = today.minus(20)
        }
        val numeric = factory.buildHabit().apply {
            name = "Numeric"
            dayTier = DayTier.MINIMUM
            type = HabitType.NUMERICAL
            targetValue = 10.0
            statisticsStartDate = today.minus(20)
            goalHistory = mutableListOf(
                HabitGoal(today.minus(20), Frequency.DAILY, NumericalHabitType.AT_LEAST, 5.0, ""),
                HabitGoal(today.minus(7), Frequency.DAILY, NumericalHabitType.AT_LEAST, 10.0, "")
            )
        }
        for (habit in listOf(boolean, numeric)) {
            persisted.add(habit)
            for (i in 1..20) habit.originalEntries.add(Entry(today.minus(i), if (habit.isNumerical) 5000 else Entry.YES_MANUAL))
        }
        val reloaded = SQLiteHabitList(factory)
        reloaded.primaryOrder = HabitList.Order.BY_DAY_TIER
        reloaded.secondaryOrder = HabitList.Order.BY_STATUS_ASC
        val descendant = reloaded.getFiltered(HabitMatcher())
        val hydratedBoolean = assertNotNull(reloaded.getById(boolean.id!!))
        val hydratedNumeric = assertNotNull(reloaded.getById(numeric.id!!))
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)
        val operations = listOf(hydratedBoolean to Entry.YES_MANUAL, hydratedBoolean to Entry.UNKNOWN,
            hydratedBoolean to Entry.SKIP, hydratedNumeric to 10000, hydratedNumeric to 0)
        for ((habit, value) in operations) {
            CreateRepetitionCommand(reloaded, habit, today, value, "integration").run()
            assertEquals(value, habit.originalEntries.get(today).value)
            for (source in listOf(reloaded.toList(), descendant.toList())) {
                for (period in listOf(StatisticsPeriod.YEAR, StatisticsPeriod.ALL)) {
                    val expected = BaselineOracle.build(source, period, LocalDate(2026, 1, 1), today,
                        today, DayOfWeek.MONDAY, filters)
                    val actual = StatisticsReportStateBuilder.build(source, period, LocalDate(2026, 1, 1), today,
                        today, DayOfWeek.MONDAY, filters)
                    assertReportsEqual(expected, actual)
                }
            }
            val anotherSession = SQLiteHabitList(factory)
            assertEquals(value, anotherSession.getById(habit.id!!)!!.originalEntries.get(today).value)
        }
        assertEquals(today.minus(20), StatisticsReportStateBuilder.getHabitStartDate(hydratedNumeric, today))
    }

    @Test
    fun testParitySnapshotAndDatasetIsolation() {
        val today = LocalDate(2026, 9, 29)
        val h = fixtures.createEmptyHabit().apply {
            name = "Snapshot Isolated Habit"
            dayTier = DayTier.MINIMUM
            frequency = Frequency.DAILY
            for (i in 0..20) {
                originalEntries.add(Entry(today.minus(i), Entry.YES_MANUAL))
            }
            recompute()
        }

        val calendar = EffectiveReadCalendarContext("local", DayOfWeek.MONDAY, 0, 0)
        val repository = MemoryContributionRepository()
        val provider = EffectiveHabitReadSnapshotProvider(repository)
        val snapshots = provider.prepare(listOf(h), today.minus(20), today, "test", 1L, calendar)

        val habits = listOf(h)
        val filters = StatisticsFilterState(habitStatus = StatisticsHabitStatusFilter.ALL)

        val baselineMonth = BaselineOracle.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters, snapshots)
        val candidateMonth = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.MONTH, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today, DayOfWeek.MONDAY, filters, snapshots)
        assertReportsEqual(baselineMonth, candidateMonth)

        val baselineAll = BaselineOracle.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters, snapshots)
        val candidateAll = StatisticsReportStateBuilder.build(habits, StatisticsPeriod.ALL, LocalDate(2026, 1, 1), today, today, DayOfWeek.MONDAY, filters, snapshots)
        assertReportsEqual(baselineAll, candidateAll)
    }
}
