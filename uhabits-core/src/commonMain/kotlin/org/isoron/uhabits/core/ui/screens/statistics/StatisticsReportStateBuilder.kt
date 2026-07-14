package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.isMinuteUnit

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
    val tier: DayTier? = null
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
    val status: StatisticsResultStatus
)

data class StatisticsBucket(
    val start: LocalDate,
    val end: LocalDate,
    val progress: Double?
)

data class StatisticsCalendarDay(
    val date: LocalDate,
    val progress: Double?,
    val skipped: Boolean
)

data class StatisticsReportTierProgress(
    val tier: DayTier,
    val progress: Double?
)

data class StatisticsFocusBySphere(
    val blockId: Long?,
    val minutes: Double
)

data class StatisticsWeekdayPatternItem(
    val dayOfWeek: DayOfWeek,
    val observations: Int,
    val progress: Double?
)

data class StatisticsReportState(
    val period: StatisticsPeriod,
    val start: LocalDate,
    val end: LocalDate,
    val overallProgress: Double?,
    val completedHabits: Int,
    val comparisonDelta: Double?,
    val focusMinutes: Double,
    val habits: List<StatisticsHabitResult>,
    val tiers: List<StatisticsReportTierProgress>,
    val trend: List<StatisticsBucket>,
    val calendar: List<StatisticsCalendarDay>,
    val focusBySphere: List<StatisticsFocusBySphere>,
    val weekdayPattern: List<StatisticsWeekdayPatternItem>
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
        filters: StatisticsFilterState
    ): StatisticsReportState {
        val filteredHabits = habits.filter { habit ->
            val matchSphere = filters.sphereId == null || habit.blockId == filters.sphereId
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
            val matchTier = filters.tier == null || habit.dayTier == filters.tier
            matchSphere && matchStatus && matchGoalType && matchTier
        }

        val earliestStart = filteredHabits.map { getHabitStartDate(it, today) }.minOrNull() ?: today
        val actualStart = if (period == StatisticsPeriod.ALL) earliestStart else start

        // Calculate independent result of each habit
        val habitResults = filteredHabits.mapNotNull { habit ->
            val relevantPeriods = getPeriodsForReport(habit, actualStart, end, today, firstWeekday)
            val evaluated = relevantPeriods.map { evaluatePeriod(habit, it, today) }
            val valid = evaluated.filter { it.progress != null }
            if (valid.isEmpty()) {
                null
            } else {
                val actual = valid.sumOf { it.actual }
                val target = valid.sumOf { it.target }
                val progress = valid.map { it.progress!! }.average()
                
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
                    status = status
                )
            }
        }

        val overallProgress = if (habitResults.isNotEmpty()) habitResults.map { it.progress }.average() else null
        val completedHabits = habitResults.count { it.progress >= 1.0 }

        // Comparison delta
        val prevRange = getPreviousPeriodRange(actualStart, end, period)
        val prevProgress = prevRange?.let { (pStart, pEnd) ->
            calculateOverallProgressForRange(filteredHabits, pStart, pEnd, today, firstWeekday)
        }
        val currentProgress = calculateOverallProgressForRange(filteredHabits, actualStart, end, today, firstWeekday)
        val comparisonDelta = if (currentProgress != null && prevProgress != null) {
            currentProgress - prevProgress
        } else {
            null
        }

        // Focus minutes
        val focusMinutes = filteredHabits
            .filter { it.isNumerical }
            .sumOf { habit ->
                val habitStart = getHabitStartDate(habit, today)
                var minutes = 0.0
                var curr = actualStart
                while (curr <= end) {
                    if (!curr.isOlderThan(habitStart)) {
                        val goal = habit.goalAt(curr)
                        if (goal.targetType == NumericalHabitType.AT_LEAST && goal.unit.isMinuteUnit()) {
                            val entry = habit.computedEntries.get(curr)
                            if (entry.value != Entry.SKIP && entry.value != Entry.UNKNOWN && entry.value > 0) {
                                minutes += entry.value / 1000.0
                            }
                        }
                    }
                    curr = curr.plus(1)
                }
                minutes
            }

        // Focus by sphere
        val focusBySphere = filteredHabits
            .filter { it.isNumerical }
            .flatMap { habit ->
                val habitStart = getHabitStartDate(habit, today)
                val entries = mutableListOf<Pair<Long?, Double>>()
                var curr = actualStart
                while (curr <= end) {
                    if (!curr.isOlderThan(habitStart)) {
                        val goal = habit.goalAt(curr)
                        if (goal.targetType == NumericalHabitType.AT_LEAST && goal.unit.isMinuteUnit()) {
                            val entry = habit.computedEntries.get(curr)
                            if (entry.value != Entry.SKIP && entry.value != Entry.UNKNOWN && entry.value > 0) {
                                entries.add(habit.blockId to (entry.value / 1000.0))
                            }
                        }
                    }
                    curr = curr.plus(1)
                }
                entries
            }
            .groupBy { it.first }
            .map { (blockId, group) -> StatisticsFocusBySphere(blockId, group.sumOf { it.second }) }
            .filter { it.minutes > 0.0 }

        // Tiers
        val tiers = DayTier.entries.map { tier ->
            val tierHabits = filteredHabits.filter { it.dayTier == tier }
            val progress = calculateOverallProgressForRange(tierHabits, actualStart, end, today, firstWeekday)
            StatisticsReportTierProgress(tier, progress)
        }

        // Trend
        val trend = generateTrendBuckets(actualStart, end, period, today, firstWeekday, filteredHabits)

        // Calendar
        val calendarStart = if (period == StatisticsPeriod.ALL) end.minus(364) else actualStart
        val calendar = mutableListOf<StatisticsCalendarDay>()
        var calCurr = calendarStart
        while (calCurr <= end) {
            val dayEvals = filteredHabits.mapNotNull { habit ->
                val periods = getPeriodsForReport(habit, calCurr, calCurr, today, firstWeekday)
                periods.firstOrNull()?.let { evaluatePeriod(habit, it, today) }
            }
            if (dayEvals.isEmpty()) {
                calendar.add(StatisticsCalendarDay(calCurr, null, false))
            } else {
                val nonSkipped = dayEvals.filter { it.progress != null }
                if (nonSkipped.isEmpty()) {
                    calendar.add(StatisticsCalendarDay(calCurr, null, true))
                } else {
                    calendar.add(StatisticsCalendarDay(calCurr, nonSkipped.map { it.progress!! }.average(), false))
                }
            }
            calCurr = calCurr.plus(1)
        }

        val weekdayPattern = DayOfWeek.entries.map { dow ->
            val datesOfW = mutableListOf<LocalDate>()
            var curr = actualStart
            while (curr <= end) {
                if (curr.dayOfWeek == dow && !curr.isOlderThan(earliestStart)) {
                    datesOfW.add(curr)
                }
                curr = curr.plus(1)
            }
            val observations = datesOfW.size
            val progress = if (observations >= 4) {
                val dateProgresses = datesOfW.mapNotNull { d ->
                    calculateOverallProgressForRange(filteredHabits, d, d, today, firstWeekday)
                }
                if (dateProgresses.isNotEmpty()) dateProgresses.average() else null
            } else {
                null
            }
            StatisticsWeekdayPatternItem(dow, observations, progress)
        }

        return StatisticsReportState(
            period = period,
            start = actualStart,
            end = end,
            overallProgress = overallProgress,
            completedHabits = completedHabits,
            comparisonDelta = comparisonDelta,
            focusMinutes = focusMinutes,
            habits = habitResults,
            tiers = tiers,
            trend = trend,
            calendar = calendar,
            focusBySphere = focusBySphere,
            weekdayPattern = weekdayPattern
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
        val L = start.daysUntil(end) + 1

        return if (L < freqLen) {
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
        val totalDays = p.start.daysUntil(p.end) + 1
        var hasEntries = false

        var curr = p.start
        while (curr <= p.end) {
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
                    if (v == Entry.YES_MANUAL) {
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

    private fun getPreviousPeriodRange(start: LocalDate, end: LocalDate, period: StatisticsPeriod): Pair<LocalDate, LocalDate>? {
        return when (period) {
            StatisticsPeriod.DAY -> Pair(start.minus(1), start.minus(1))
            StatisticsPeriod.WEEK -> {
                val numDays = start.daysUntil(end) + 1
                Pair(start.minus(7), start.minus(7).plus(numDays - 1))
            }
            StatisticsPeriod.MONTH -> {
                val numDays = start.daysUntil(end) + 1
                val prevMonthStart = addMonths(start, -1)
                val prevMonthEnd = prevMonthStart.plus(minOf(numDays - 1, prevMonthStart.monthLength - 1))
                Pair(prevMonthStart, prevMonthEnd)
            }
            StatisticsPeriod.YEAR -> {
                val numDays = start.daysUntil(end) + 1
                val prevYearStart = LocalDate(start.year - 1, 1, 1)
                val prevYearEnd = prevYearStart.plus(minOf(numDays - 1, prevYearStart.yearLength - 1))
                Pair(prevYearStart, prevYearEnd)
            }
            StatisticsPeriod.ALL -> null
        }
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

    private fun calculateOverallProgressForRange(
        habits: List<Habit>,
        start: LocalDate,
        end: LocalDate,
        today: LocalDate,
        firstWeekday: DayOfWeek
    ): Double? {
        val progresses = habits.mapNotNull { habit ->
            val periods = getPeriodsForReport(habit, start, end, today, firstWeekday)
            val valid = periods.map { evaluatePeriod(habit, it, today) }.filter { it.progress != null }
            if (valid.isNotEmpty()) {
                valid.map { it.progress!! }.average()
            } else {
                null
            }
        }
        return if (progresses.isNotEmpty()) progresses.average() else null
    }

    private fun generateTrendBuckets(
        start: LocalDate,
        end: LocalDate,
        period: StatisticsPeriod,
        today: LocalDate,
        firstWeekday: DayOfWeek,
        habits: List<Habit>
    ): List<StatisticsBucket> {
        val buckets = mutableListOf<StatisticsBucket>()
        when (period) {
            StatisticsPeriod.DAY -> {
                // No trend buckets needed for single day report
            }
            StatisticsPeriod.WEEK -> {
                // 7 daily buckets
                var curr = start
                while (curr <= end) {
                    buckets.add(StatisticsBucket(curr, curr, null))
                    curr = curr.plus(1)
                }
            }
            StatisticsPeriod.MONTH -> {
                // Weeks intersecting the month
                var weekStart = start.startOfWeek(firstWeekday)
                while (weekStart <= end) {
                    val weekEnd = weekStart.plus(6)
                    buckets.add(StatisticsBucket(weekStart, weekEnd, null))
                    weekStart = weekStart.plus(7)
                }
            }
            StatisticsPeriod.YEAR -> {
                // 12 months
                for (m in 1..12) {
                    val mStart = LocalDate(start.year, m, 1)
                    val mEnd = mStart.plus(mStart.monthLength - 1)
                    buckets.add(StatisticsBucket(mStart, mEnd, null))
                }
            }
            StatisticsPeriod.ALL -> {
                val earliestStart = habits.map { getHabitStartDate(it, today) }.minOrNull() ?: today
                val startGroup = if (earliestStart.isNewerThan(today)) today else earliestStart
                val totalDays = startGroup.daysUntil(today)

                if (totalDays <= 2 * 365) {
                    // Group by calendar months
                    var mStart = startGroup.startOfMonth()
                    while (mStart <= today) {
                        val mEnd = mStart.plus(mStart.monthLength - 1)
                        buckets.add(StatisticsBucket(mStart, mEnd, null))
                        mStart = mStart.plus(mStart.monthLength)
                    }
                } else if (totalDays <= 5 * 365) {
                    // Group by quarters
                    var qStart = startGroup.startOfQuarter()
                    while (qStart <= today) {
                        val qEndMonth = addMonths(qStart, 2)
                        val qEnd = qEndMonth.plus(qEndMonth.monthLength - 1)
                        buckets.add(StatisticsBucket(qStart, qEnd, null))
                        qStart = addMonths(qStart, 3)
                    }
                } else {
                    // Group by years
                    var y = startGroup.year
                    while (y <= today.year) {
                        buckets.add(StatisticsBucket(LocalDate(y, 1, 1), LocalDate(y, 12, 31), null))
                        y += 1
                    }
                }
            }
        }

        return buckets.map { b ->
            val progress = calculateOverallProgressForRange(habits, b.start, b.end, today, firstWeekday)
            b.copy(progress = progress)
        }
    }
}
