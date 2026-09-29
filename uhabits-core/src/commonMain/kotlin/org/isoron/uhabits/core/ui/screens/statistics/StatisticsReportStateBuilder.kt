package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.DayTierScope
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import kotlin.math.roundToInt

enum class StatisticsPeriod {
    DAY, WEEK, MONTH, YEAR, ALL
}

enum class StatisticsHabitStatusFilter {
    ACTIVE, ARCHIVED, ALL
}

enum class StatisticsGoalTypeFilter {
    YES_NO, NUMERICAL, ALL
}

data class StatisticsFilterState(
    val sphereId: Long? = null,
    val habitStatus: StatisticsHabitStatusFilter = StatisticsHabitStatusFilter.ACTIVE,
    val goalType: StatisticsGoalTypeFilter = StatisticsGoalTypeFilter.ALL,
    val tierScope: DayTierScope = DayTierScope.MINIMUM,
    val dayTiersEnabled: Boolean = true,
    val spheresEnabled: Boolean = true
)

enum class StatisticsResultStatus {
    PENDING, ON_TRACK, VIOLATED, MISSED, SKIPPED, COMPLETED
}

data class StatisticsHabitResult(
    val habit: Habit,
    val progress: Double,
    val actual: Double,
    val target: Double,
    val unit: String,
    val status: StatisticsResultStatus,
    val skippedPeriods: Int = 0
)

data class StatisticsBucket(
    val start: LocalDate,
    val end: LocalDate,
    val progress: Double?
)

data class StatisticsReportTierProgress(
    val tier: DayTier,
    val progress: Double?,
    val completedCount: Int = 0,
    val totalCount: Int = 0
)

data class StatisticsSphereProgress(
    val blockId: Long?,
    val progress: Double?,
    val habitCount: Int
)

data class StatisticsWeekdayPatternItem(
    val dayOfWeek: DayOfWeek,
    val observations: Int,
    val progress: Double?
)

data class StatisticsDailyProgress(
    val date: LocalDate,
    val progress: Double?,
    val isEligible: Boolean,
    val isFullyClosed: Boolean,
    val isSkipped: Boolean,
    val isToday: Boolean,
    val isFuture: Boolean
)

data class StatisticsHabitStabilityItem(
    val habit: Habit,
    val score: Double
)

data class StatisticsReportState(
    val period: StatisticsPeriod,
    val start: LocalDate,
    val end: LocalDate,
    val matchingHabits: Int,
    val hasDailyMinimumGoals: Boolean,
    val overallProgress: Double?,
    val minimumProgress: Double?,
    val tierProgress: Map<DayTier, Double?>,
    val tierCounts: Map<DayTier, Pair<Int, Int>> = emptyMap(),
    val comparisonDelta: Double?,
    val fullyClosedDays: Int,
    val eligibleFinishedDays: Int,
    val dailyProgress: List<StatisticsDailyProgress>,
    val trendBuckets: List<StatisticsBucket>,
    val weekdayRhythm: List<StatisticsWeekdayPatternItem>,
    val sphereProgress: List<StatisticsSphereProgress>,
    val remainingMinimumHabits: List<StatisticsHabitResult>,
    val habitStability: List<StatisticsHabitStabilityItem>,
    val todayMinimumCount: Pair<Int, Int>? = null,
    val completedHabits: Int = 0,
    val habits: List<StatisticsHabitResult> = emptyList(),
    val habitChanges: List<StatisticsHabitChange> = emptyList(),
    val tierScope: DayTierScope = DayTierScope.MINIMUM,
    val dayTiersEnabled: Boolean = true,
    val spheresEnabled: Boolean = true,
    val averageDailyProgress: Double? = null,
    val bestDailyProgress: Pair<LocalDate, Double>? = null
) {
    val tiers: List<StatisticsReportTierProgress>
        get() = DayTier.entries.map {
            val counts = tierCounts[it] ?: Pair(0, 0)
            StatisticsReportTierProgress(it, tierProgress[it], counts.first, counts.second)
        }
}

data class StatisticsHabitChange(
    val habit: Habit,
    val deltaPoints: Int,
    val currentProgress: Double,
    val previousProgress: Double
)

data class GoalPeriod(
    val start: LocalDate,
    val end: LocalDate,
    val frequency: Frequency,
    val targetType: NumericalHabitType,
    val targetValue: Double,
    val unit: String
)

data class EvaluatedPeriod(
    val period: GoalPeriod,
    val actual: Double,
    val target: Double,
    val progress: Double?,
    val status: StatisticsResultStatus,
    val skippedDays: Int,
    val eligibleDays: Int
)

object StatisticsReportStateBuilder {

    fun build(
        habits: List<Habit>,
        period: StatisticsPeriod,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        filters: StatisticsFilterState,
        shouldCancel: () -> Boolean = { false }
    ): StatisticsReportState {
        val filteredHabits = habits.filter { habit ->
            val matchSphere = !filters.spheresEnabled || filters.sphereId == null || habit.blockId == filters.sphereId
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

        val earliestStart = filteredHabits.map { getHabitStartDate(it, today) }.minOrNull() ?: today
        val actualStart = if (period == StatisticsPeriod.ALL) earliestStart else start

        // 1. Per-habit evaluation over period (equal weight macro-average)
        val habitResults = filteredHabits.mapNotNull { habit ->
            if (shouldCancel()) return@mapNotNull null
            val relevantPeriods = getPeriodsForReport(habit, actualStart, end, today, firstWeekday)
            val evaluated = relevantPeriods.map { evaluatePeriod(habit, it, today) }
            val valid = evaluated.filter { it.progress != null }
            if (valid.isEmpty() && evaluated.none { it.status == StatisticsResultStatus.SKIPPED }) {
                null
            } else {
                val actual = valid.sumOf { it.actual }
                val target = valid.sumOf { it.target }
                val progress = if (valid.isEmpty()) 0.0 else valid.map { it.progress!! }.average()

                val status = when {
                    valid.any { it.status == StatisticsResultStatus.VIOLATED } -> StatisticsResultStatus.VIOLATED
                    valid.any { it.status == StatisticsResultStatus.MISSED } -> StatisticsResultStatus.MISSED
                    valid.any { it.status == StatisticsResultStatus.PENDING } -> StatisticsResultStatus.PENDING
                    valid.any { it.status == StatisticsResultStatus.ON_TRACK } -> StatisticsResultStatus.ON_TRACK
                    valid.all { it.status == StatisticsResultStatus.SKIPPED } -> StatisticsResultStatus.SKIPPED
                    else -> StatisticsResultStatus.COMPLETED
                }

                StatisticsHabitResult(
                    habit = habit,
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

        // 2. Tier progress (independent macro-average per tier across all matching habits)
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

        // Single aggregate headline progress: macro-average over active scope
        val countableScopeResults = countableResults.filter {
            !filters.dayTiersEnabled || filters.tierScope.includes(it.habit.dayTier)
        }
        val overallProgress = if (countableScopeResults.isNotEmpty()) {
            countableScopeResults.map { it.progress }.average()
        } else null

        // 3. Daily progress & fully closed days over active scope
        val dailyList = mutableListOf<StatisticsDailyProgress>()
        var hasAnyDailyMinimumGoals = false
        var d = actualStart
        while (d <= end && !shouldCancel()) {
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
                    getHabitStartDate(habit, today) <= d &&
                        habit.goalAt(d).frequency.denominator == 1
                }
                if (dailyScopeHabits.isNotEmpty()) {
                    hasAnyDailyMinimumGoals = true
                }
                val evals = dailyScopeHabits.map { habit ->
                    val goal = habit.goalAt(d)
                    val p = GoalPeriod(d, d, goal.frequency, goal.targetType, goal.targetValue, goal.unit)
                    evaluatePeriod(habit, p, today)
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
                        it.progress == 1.0 && it.status != StatisticsResultStatus.PENDING
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

        // 4. Day-specific metrics (remaining scope habits and completed count)
        val todayMinimumCount: Pair<Int, Int>?
        val remainingMinimumHabits: List<StatisticsHabitResult>
        if (period == StatisticsPeriod.DAY) {
            val dayScopeHabits = scopeHabits.filter { habit ->
                getHabitStartDate(habit, today) <= start &&
                    habit.goalAt(start).frequency.denominator == 1
            }
            val dayEvals = dayScopeHabits.map { habit ->
                val goal = habit.goalAt(start)
                val p = GoalPeriod(start, start, goal.frequency, goal.targetType, goal.targetValue, goal.unit)
                habit to evaluatePeriod(habit, p, today)
            }
            val nonSkippedEvals = dayEvals.filter { it.second.status != StatisticsResultStatus.SKIPPED }
            todayMinimumCount = if (nonSkippedEvals.isNotEmpty()) {
                val completed = nonSkippedEvals.count { it.second.progress == 1.0 && it.second.status != StatisticsResultStatus.PENDING }
                Pair(completed, nonSkippedEvals.size)
            } else null

            remainingMinimumHabits = nonSkippedEvals
                .filter { it.second.progress == null || it.second.progress!! < 1.0 }
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

        // 5. Comparison delta (percentage points) & Habit Changes over scope
        val comparisonWindow = getComparisonWindow(actualStart, end, period, today)
        val comparisonDelta = if (comparisonWindow == null) {
            null
        } else {
            val currProg = calculateOverallProgressForRange(scopeHabits, comparisonWindow.currentStart, comparisonWindow.currentEnd, today, firstWeekday)
            val prevProg = calculateOverallProgressForRange(scopeHabits, comparisonWindow.previousStart, comparisonWindow.previousEnd, today, firstWeekday)
            if (currProg != null && prevProg != null) {
                currProg - prevProg
            } else null
        }

        val habitChanges = if (comparisonWindow == null) {
            emptyList()
        } else {
            val sliceDays = comparisonWindow.currentStart.daysUntil(comparisonWindow.currentEnd) + 1
            scopeHabits.mapNotNull { habit ->
                val habitStart = getHabitStartDate(habit, today)
                if (habitStart.isNewerThan(comparisonWindow.previousStart) || habit.isArchived) {
                    return@mapNotNull null
                }
                val goal = habit.goalAt(comparisonWindow.currentEnd)
                if (goal.frequency.denominator > 1 && sliceDays < 4) {
                    return@mapNotNull null
                }
                val currEval = evaluateHabitSlice(habit, comparisonWindow.currentStart, comparisonWindow.currentEnd)
                val prevEval = evaluateHabitSlice(habit, comparisonWindow.previousStart, comparisonWindow.previousEnd)
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
                else StatisticsHabitChange(habit, points, currEval.progress, prevEval.progress)
            }.sortedByDescending { kotlin.math.abs(it.deltaPoints) }.take(3)
        }

        // 6. Trend / Month / History buckets
        val trendBuckets = when (period) {
            StatisticsPeriod.DAY, StatisticsPeriod.MONTH -> emptyList()
            StatisticsPeriod.WEEK -> {
                // 7 daily buckets for the week
                var curr = actualStart
                val buckets = mutableListOf<StatisticsBucket>()
                while (curr <= end) {
                    val dayProg = dailyList.firstOrNull { it.date == curr }?.progress
                    buckets.add(StatisticsBucket(curr, curr, dayProg))
                    curr = curr.plus(1)
                }
                buckets
            }
            StatisticsPeriod.YEAR -> {
                // 12 monthly buckets for the year over scopeHabits
                (1..12).map { month ->
                    val mStart = LocalDate(actualStart.year, month, 1)
                    val mEnd = mStart.plus(mStart.monthLength - 1)
                    val prog = if (mStart <= today) {
                        calculateOverallProgressForRange(scopeHabits, mStart, mEnd, today, firstWeekday)
                    } else null
                    StatisticsBucket(mStart, mEnd, prog)
                }
            }
            StatisticsPeriod.ALL -> {
                // Adaptive: monthly if span < 24 months, yearly if >= 24 months over scopeHabits
                val totalMonths = (end.year - actualStart.year) * 12 + (end.month - actualStart.month) + 1
                if (totalMonths < 24) {
                    val buckets = mutableListOf<StatisticsBucket>()
                    var currMonth = actualStart.startOfMonth()
                    while (currMonth <= end) {
                        val mEnd = currMonth.plus(currMonth.monthLength - 1)
                        val prog = calculateOverallProgressForRange(scopeHabits, currMonth, mEnd, today, firstWeekday)
                        buckets.add(StatisticsBucket(currMonth, mEnd, prog))
                        currMonth = currMonth.plus(currMonth.monthLength)
                    }
                    buckets
                } else {
                    (actualStart.year..end.year).map { y ->
                        val yStart = LocalDate(y, 1, 1)
                        val yEnd = LocalDate(y, 12, 31)
                        val prog = calculateOverallProgressForRange(scopeHabits, yStart, yEnd, today, firstWeekday)
                        StatisticsBucket(yStart, yEnd, prog)
                    }
                }
            }
        }

        // 7. Weekday rhythm (for Month and Year)
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

        // 8. Spheres progress (meaningful spheres only, blockId != null, scoped progress with total habit count)
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

        // 9. Habit stability (belongs ONLY to All Time)
        val habitStability = if (period == StatisticsPeriod.ALL) {
            filteredHabits.map {
                StatisticsHabitStabilityItem(it, it.scores[today].value)
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

    fun getHabitStartDate(habit: Habit, today: LocalDate): LocalDate {
        val startAttr = habit.effectiveStatisticsStartDate()
        if (startAttr != null) return startAttr
        val firstEntry = habit.originalEntries.getKnown().minByOrNull { it.date }?.date
        if (firstEntry != null) return firstEntry
        return today
    }

    fun getPeriodsForReport(
        habit: Habit,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek
    ): List<GoalPeriod> {
        val habitStart = getHabitStartDate(habit, today)
        if (end < habitStart) return emptyList()

        val limitDate = if (end.isNewerThan(today)) end else today
        val allPeriods = generateAllPeriods(habit, habitStart, limitDate.plus(31), firstWeekday)

        val activeGoal = habit.goalAt(end)
        val freqLen = when (activeGoal.frequency.denominator) {
            1 -> 1
            7 -> 7
            30 -> end.monthLength
            else -> activeGoal.frequency.denominator
        }
        val length = start.daysUntil(end) + 1

        return if (length < freqLen) {
            val containing = allPeriods.firstOrNull { it.start <= end && end <= it.end }
            if (containing != null) listOf(containing) else emptyList()
        } else {
            allPeriods.filter { it.start >= start && it.start <= end }
        }
    }

    fun generateAllPeriods(
        habit: Habit,
        habitStart: LocalDate,
        limitDate: LocalDate,
        firstWeekday: DayOfWeek
    ): List<GoalPeriod> {
        val periods = mutableListOf<GoalPeriod>()
        val goals = habit.normalizedGoalHistory()
        val changeDates = goals.map { it.effectiveDate }.filter { it.isNewerThan(habitStart) }.sorted().distinct()

        var currentSegStart = habitStart
        for (changeDate in changeDates) {
            if (currentSegStart > limitDate) break
            val segEnd = changeDate.minus(1)
            if (segEnd >= currentSegStart) {
                generatePeriodsForSegment(habit, currentSegStart, segEnd, periods, firstWeekday)
            }
            currentSegStart = changeDate
        }
        if (currentSegStart <= limitDate) {
            generatePeriodsForSegment(habit, currentSegStart, limitDate, periods, firstWeekday)
        }
        return periods
    }

    private fun generatePeriodsForSegment(
        habit: Habit,
        segStart: LocalDate,
        segEnd: LocalDate,
        outPeriods: MutableList<GoalPeriod>,
        firstWeekday: DayOfWeek
    ) {
        val goal = habit.goalAt(segStart)
        val denom = goal.frequency.denominator
        val targetVal = goal.targetValue
        val targetType = goal.targetType
        val unit = goal.unit

        when (denom) {
            1 -> {
                var curr = segStart
                while (curr <= segEnd) {
                    outPeriods.add(GoalPeriod(curr, curr, goal.frequency, targetType, targetVal, unit))
                    curr = curr.plus(1)
                }
            }
            7 -> {
                var weekStart = segStart.startOfWeek(firstWeekday)
                while (weekStart <= segEnd) {
                    val weekEnd = weekStart.plus(6)
                    val pStart = if (weekStart.isOlderThan(segStart)) segStart else weekStart
                    val pEnd = if (weekEnd.isNewerThan(segEnd)) segEnd else weekEnd
                    if (pStart <= pEnd) {
                        outPeriods.add(GoalPeriod(pStart, pEnd, goal.frequency, targetType, targetVal, unit))
                    }
                    weekStart = weekStart.plus(7)
                }
            }
            30 -> {
                var monthStart = segStart.startOfMonth()
                while (monthStart <= segEnd) {
                    val monthEnd = monthStart.plus(monthStart.monthLength - 1)
                    val pStart = if (monthStart.isOlderThan(segStart)) segStart else monthStart
                    val pEnd = if (monthEnd.isNewerThan(segEnd)) segEnd else monthEnd
                    if (pStart <= pEnd) {
                        outPeriods.add(GoalPeriod(pStart, pEnd, goal.frequency, targetType, targetVal, unit))
                    }
                    monthStart = monthStart.plus(monthStart.monthLength)
                }
            }
            else -> {
                var curr = segStart
                while (curr <= segEnd) {
                    val pEnd = curr.plus(denom - 1)
                    val clippedEnd = if (pEnd.isNewerThan(segEnd)) segEnd else pEnd
                    outPeriods.add(GoalPeriod(curr, clippedEnd, goal.frequency, targetType, targetVal, unit))
                    curr = curr.plus(denom)
                }
            }
        }
    }

    fun evaluatePeriod(
        habit: Habit,
        p: GoalPeriod,
        today: LocalDate
    ): EvaluatedPeriod {
        var actual = 0.0
        var skippedDays = 0
        val effectiveEnd = if (p.end.isNewerThan(today)) today else p.end
        if (p.start.isNewerThan(today)) {
            return EvaluatedPeriod(p, 0.0, 0.0, null, StatisticsResultStatus.PENDING, 0, 0)
        }
        val totalDays = p.start.daysUntil(effectiveEnd) + 1
        var hasEntries = false

        var curr = p.start
        while (curr <= effectiveEnd) {
            val entry = habit.computedEntries.get(curr)
            val v = entry.value

            if (v == Entry.SKIP) {
                skippedDays++
            } else {
                if (v != Entry.UNKNOWN) {
                    hasEntries = true
                }
                if (habit.isNumerical) {
                    if (v != Entry.UNKNOWN && v > 0) {
                        actual += v / 1000.0
                    }
                } else {
                    if (v == Entry.YES_MANUAL || v == Entry.YES_AUTO) {
                        actual += 1.0
                    }
                }
            }
            curr = curr.plus(1)
        }

        val eligibleDays = totalDays - skippedDays
        if (eligibleDays == 0) {
            return EvaluatedPeriod(
                period = p,
                actual = 0.0,
                target = 0.0,
                progress = null,
                status = StatisticsResultStatus.SKIPPED,
                skippedDays = skippedDays,
                eligibleDays = 0
            )
        }

        val baseTarget = if (habit.isNumerical) p.targetValue else p.frequency.numerator.toDouble()
        val target = baseTarget * (eligibleDays.toDouble() / totalDays.toDouble())

        val progress: Double?
        val status: StatisticsResultStatus

        if (habit.isNumerical && p.targetType == NumericalHabitType.AT_MOST) {
            if (!hasEntries) {
                progress = null
                status = StatisticsResultStatus.PENDING
            } else if (actual > target) {
                progress = 0.0
                status = StatisticsResultStatus.VIOLATED
            } else {
                progress = 1.0
                status = if (p.end < today) {
                    StatisticsResultStatus.COMPLETED
                } else {
                    StatisticsResultStatus.ON_TRACK
                }
            }
        } else {
            if (!hasEntries && p.end >= today) {
                progress = 0.0
                status = StatisticsResultStatus.PENDING
            } else {
                progress = if (target > 0.0) minOf(actual / target, 1.0) else 1.0
                status = if (actual >= target) {
                    if (p.end < today || p.start == p.end) {
                        StatisticsResultStatus.COMPLETED
                    } else {
                        StatisticsResultStatus.ON_TRACK
                    }
                } else if (p.end < today) {
                    StatisticsResultStatus.MISSED
                } else {
                    StatisticsResultStatus.PENDING
                }
            }
        }

        return EvaluatedPeriod(
            period = p,
            actual = actual,
            target = target,
            progress = progress,
            status = status,
            skippedDays = skippedDays,
            eligibleDays = eligibleDays
        )
    }

    data class ComparisonWindow(
        val currentStart: LocalDate,
        val currentEnd: LocalDate,
        val previousStart: LocalDate,
        val previousEnd: LocalDate,
        val isPartial: Boolean
    )

    data class HabitSliceEvaluation(
        val progress: Double?,
        val eligibleDays: Int,
        val observations: Int,
        val hasEntries: Boolean,
        val actual: Double,
        val target: Double
    )

    fun getComparisonWindow(
        start: LocalDate,
        end: LocalDate,
        period: StatisticsPeriod,
        today: LocalDate
    ): ComparisonWindow? {
        if (period == StatisticsPeriod.ALL || start.isNewerThan(today)) return null

        val isPartial = when (period) {
            StatisticsPeriod.WEEK -> start.daysUntil(end) + 1 < 7 || end >= today
            StatisticsPeriod.MONTH -> (start.daysUntil(end) + 1 < start.monthLength) || end >= today
            StatisticsPeriod.YEAR -> (start.daysUntil(end) + 1 < start.yearLength) || end >= today
            StatisticsPeriod.DAY -> false
            StatisticsPeriod.ALL -> false
        }
        val currentStart = start
        val currentEnd = if (isPartial) minOf(end, today) else end
        val elapsedDays = currentStart.daysUntil(currentEnd) + 1
        if (elapsedDays <= 0) return null

        val (prevStart, prevEnd) = when (period) {
            StatisticsPeriod.DAY -> {
                Pair(start.minus(1), start.minus(1))
            }
            StatisticsPeriod.WEEK -> {
                val pStart = start.minus(7)
                val pEnd = if (isPartial) pStart.plus(elapsedDays - 1) else start.minus(1)
                Pair(pStart, pEnd)
            }
            StatisticsPeriod.MONTH -> {
                val pStart = addMonths(start, -1)
                val pEnd = if (isPartial) {
                    pStart.plus(minOf(elapsedDays - 1, pStart.monthLength - 1))
                } else {
                    pStart.plus(pStart.monthLength - 1)
                }
                Pair(pStart, pEnd)
            }
            StatisticsPeriod.YEAR -> {
                val pStart = LocalDate(start.year - 1, 1, 1)
                val pEnd = if (isPartial) {
                    val prevMonthLen = LocalDate(start.year - 1, currentEnd.month, 1).monthLength
                    LocalDate(start.year - 1, currentEnd.month, minOf(currentEnd.day, prevMonthLen))
                } else {
                    LocalDate(start.year - 1, 12, 31)
                }
                Pair(pStart, pEnd)
            }
            StatisticsPeriod.ALL -> return null
        }

        return ComparisonWindow(
            currentStart = currentStart,
            currentEnd = currentEnd,
            previousStart = prevStart,
            previousEnd = prevEnd,
            isPartial = isPartial
        )
    }

    fun evaluateHabitSlice(
        habit: Habit,
        sliceStart: LocalDate,
        sliceEnd: LocalDate
    ): HabitSliceEvaluation? {
        val statisticsStart = habit.effectiveStatisticsStartDate()
        val effectiveStart = if (statisticsStart != null && statisticsStart > sliceStart) statisticsStart else sliceStart
        val totalDays = effectiveStart.daysUntil(sliceEnd) + 1
        if (totalDays <= 0) return null

        var actual = 0.0
        var skippedDays = 0
        var hasEntries = false
        var observations = 0

        var curr = effectiveStart
        while (curr <= sliceEnd) {
            val entry = habit.computedEntries.get(curr)
            val v = entry.value
            if (v == Entry.SKIP) {
                skippedDays++
            } else {
                if (v != Entry.UNKNOWN) {
                    hasEntries = true
                    observations++
                }
                if (habit.isNumerical) {
                    if (v != Entry.UNKNOWN && v > 0) {
                        actual += v / 1000.0
                    }
                } else {
                    if (v == Entry.YES_MANUAL || v == Entry.YES_AUTO) {
                        actual += 1.0
                    }
                }
            }
            curr = curr.plus(1)
        }

        val eligibleDays = totalDays - skippedDays
        if (eligibleDays == 0) {
            return HabitSliceEvaluation(
                progress = null,
                eligibleDays = 0,
                observations = 0,
                hasEntries = false,
                actual = 0.0,
                target = 0.0
            )
        }

        val goal = habit.goalAt(sliceEnd)
        val denom = goal.frequency.denominator
        val baseTarget = if (habit.isNumerical) goal.targetValue else goal.frequency.numerator.toDouble()
        val target = if (denom == 1) {
            baseTarget * eligibleDays
        } else {
            val expectedTarget = (baseTarget / denom.toDouble()) * totalDays
            expectedTarget * (eligibleDays.toDouble() / totalDays.toDouble())
        }

        val progress: Double? = if (!hasEntries) {
            null
        } else if (habit.isNumerical && goal.targetType == NumericalHabitType.AT_MOST) {
            if (actual > target) 0.0
            else 1.0
        } else {
            if (target > 0.0) minOf(actual / target, 1.0) else 1.0
        }

        return HabitSliceEvaluation(
            progress = progress,
            eligibleDays = eligibleDays,
            observations = observations,
            hasEntries = hasEntries,
            actual = actual,
            target = target
        )
    }

    private fun addMonths(date: LocalDate, n: Int): LocalDate {
        var y = date.year
        var m = date.month + n
        while (m > 12) {
            m -= 12
            y += 1
        }
        while (m < 1) {
            m += 12
            y -= 1
        }
        return LocalDate(y, m, 1)
    }

    fun calculateOverallProgressForRange(
        habits: List<Habit>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek
    ): Double? {
        val effectiveEnd = if (end.isNewerThan(today)) today else end
        if (start.isNewerThan(effectiveEnd)) return null
        val progresses = habits.mapNotNull { habit ->
            val habitStart = getHabitStartDate(habit, today)
            if (habitStart > effectiveEnd) null
            else evaluateHabitSlice(habit, maxOf(start, habitStart), effectiveEnd)?.progress
        }
        return if (progresses.isNotEmpty()) progresses.average() else null
    }
}
