package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadExactness

enum class ComparePeriod(val days: Int) {
    WEEK(7),
    MONTH(30),
    THREE_MONTHS(90),
    YEAR(365),
    ALL(-1)
}

data class CompareOverviewMetricState(
    val habit: Habit,
    val hasActivity: Boolean,
    val scoreToday: Double?,
    val periodDelta: Double?,
    val periodCompletion: Double?,
    val streakLength: Int?
)

data class CompareStreakState(
    val habit: Habit,
    val latestStreak: Streak?,
    val recordStreak: Streak?,
    val isExact: Boolean = true
)

object CompareHabitsPresenter {

    fun getQueryStartDate(today: LocalDate, period: ComparePeriod): LocalDate {
        return when (period) {
            ComparePeriod.WEEK -> today.minus(7)
            ComparePeriod.MONTH -> minOf(today.minus(30), today.startOfMonth())
            ComparePeriod.THREE_MONTHS -> today.minus(90)
            ComparePeriod.YEAR -> today.minus(365)
            ComparePeriod.ALL -> LocalDate(1970, 1, 1)
        }
    }

    fun getStartDateForPeriod(
        habits: List<Habit>,
        today: LocalDate,
        period: ComparePeriod,
        snapshots: Map<HabitRef, EffectiveHabitReadSnapshot>? = null
    ): LocalDate {
        val periodWindowStart = when (period) {
            ComparePeriod.WEEK -> today.minus(6)
            ComparePeriod.MONTH -> today.minus(29)
            ComparePeriod.THREE_MONTHS -> today.minus(89)
            ComparePeriod.YEAR -> today.minus(364)
            ComparePeriod.ALL -> LocalDate(1970, 1, 1)
        }
        val earliestHabitStart = habits.map {
            val snapshot = it.uuid?.let { u -> snapshots?.get(HabitRef(u)) }
            StatisticsReportStateBuilder.getHabitStartDate(it, today, snapshot)
        }.minOrNull() ?: today
        return maxOf(periodWindowStart, earliestHabitStart)
    }

    fun buildOverviewMetrics(
        habit: Habit,
        startDate: LocalDate,
        today: LocalDate,
        period: ComparePeriod,
        snapshot: EffectiveHabitReadSnapshot? = null
    ): CompareOverviewMetricState {
        val knownEntries = habit.originalEntries.getKnown().filter { entry ->
            val start = habit.effectiveStatisticsStartDate()
            (start == null || entry.date >= start) && entry.date <= today && entry.value != Entry.SKIP
        }
        val hasActivity = knownEntries.isNotEmpty() || (snapshot != null && snapshot.hasContributions)

        // 1. Stability (Score on today)
        val scoreToday: Double? = if (snapshot != null && snapshot.hasContributions) {
            if (snapshot.scoreResult.exactness == EffectiveReadExactness.INCOMPLETE) null
            else snapshot.scoreResult.on(today)
        } else {
            habit.scores.getByInterval(today, today).firstOrNull()?.value ?: habit.scores[today].value
        }

        // 2. Period Change (delta to start of period)
        val habitStart = StatisticsReportStateBuilder.getHabitStartDate(habit, today, snapshot)
        val prevDate = when (period) {
            ComparePeriod.WEEK -> today.minus(7)
            ComparePeriod.MONTH -> today.minus(30)
            ComparePeriod.THREE_MONTHS -> today.minus(90)
            ComparePeriod.YEAR -> today.minus(365)
            ComparePeriod.ALL -> habitStart
        }
        val scorePrev: Double? = if (snapshot != null && snapshot.hasContributions) {
            if (snapshot.scoreResult.exactness == EffectiveReadExactness.INCOMPLETE) null
            else snapshot.scoreResult.on(prevDate)
        } else {
            habit.scores.getByInterval(prevDate, prevDate).firstOrNull()?.value
        }
        val delta: Double? = if (scoreToday != null && scorePrev != null && habitStart <= prevDate) {
            scoreToday - scorePrev
        } else null

        // 3. Period Completion
        val sliceProg = if (habitStart > today) null else {
            StatisticsReportStateBuilder.evaluateHabitSlice(habit, maxOf(startDate, habitStart), today, snapshot)?.progress
        }

        val exactSliceProgress = if (snapshot != null && snapshot.hasContributions && !snapshot.canUseLegacyEntryAdapter) null
        else if (snapshot != null && snapshot.hasContributions &&
            habit.goalAt(today).targetType == NumericalHabitType.AT_LEAST &&
            sliceProg != null && sliceProg < 1.0 &&
            snapshot.days.any { it.manualEntry.date in maxOf(startDate, habitStart)..today &&
                it.includedInStatistics && !it.isSkipped && !it.hasCompleteCoverage }) null else sliceProg

        // 4. Streak
        val streakLength: Int? = if (snapshot != null && snapshot.hasContributions) {
            if (snapshot.streakResult.streaks == null) null
            else snapshot.streakResult.streaks?.firstOrNull { it.end == today || it.end == today.minus(1) }?.length ?: 0
        } else {
            val presentation = snapshot?.presentationHabit() ?: habit
            presentation.streaks.getLatest()?.takeIf { it.end == today || it.end == today.minus(1) }?.length ?: 0
        }

        return CompareOverviewMetricState(
            habit = habit,
            hasActivity = hasActivity,
            scoreToday = if (hasActivity) scoreToday else null,
            periodDelta = delta,
            periodCompletion = exactSliceProgress,
            streakLength = streakLength
        )
    }

    fun buildStabilitySeries(
        habit: Habit,
        startDate: LocalDate,
        today: LocalDate,
        snapshot: EffectiveHabitReadSnapshot? = null
    ): List<Pair<LocalDate, Double>> {
        val habitStart = StatisticsReportStateBuilder.getHabitStartDate(habit, today, snapshot).let { maxOf(startDate, it) }
        return if (snapshot != null && snapshot.hasContributions) {
            if (snapshot.scoreResult.exactness == EffectiveReadExactness.INCOMPLETE) {
                emptyList()
            } else {
                snapshot.scoreResult.scores?.filter { it.date in habitStart..today }?.map {
                    Pair(it.date, it.value)
                } ?: emptyList()
            }
        } else {
            habit.scores.getByInterval(habitStart, today).map {
                Pair(it.date, it.value)
            }
        }
    }

    fun buildExecutionSeries(
        habit: Habit,
        startDate: LocalDate,
        today: LocalDate,
        snapshot: EffectiveHabitReadSnapshot? = null
    ): List<Pair<LocalDate, Double>> {
        val points = mutableListOf<Pair<LocalDate, Double>>()
        val habitStart = StatisticsReportStateBuilder.getHabitStartDate(habit, today, snapshot).let { maxOf(startDate, it) }
        var curr = habitStart
        while (curr <= today) {
            val normProgress = computeDailyNormalizedProgress(habit, curr, snapshot)
            if (normProgress != null) {
                points.add(Pair(curr, normProgress))
            }
            curr = curr.plus(1)
        }
        return points
    }

    fun computeDailyNormalizedProgress(
        habit: Habit,
        date: LocalDate,
        snapshot: EffectiveHabitReadSnapshot? = null
    ): Double? {
        val goal = habit.goalAt(date)
        if (snapshot != null && snapshot.hasContributions) {
            val day = snapshot.dayOrNull(date) ?: return null
            val totalValue = day.totalValue
            if (!day.includedInStatistics || day.isSkipped || totalValue == null) {
                return null
            }
            if (habit.isNumerical) {
                val target = if (goal.targetValue > 0.0) goal.targetValue else 1.0
                return if (goal.targetType == NumericalHabitType.AT_MOST) {
                    if (totalValue / 1000.0 > target) 0.0
                    else if (!day.hasCompleteCoverage) null
                    else 1.0
                } else {
                    if (totalValue / 1000.0 >= target) 1.0
                    else if (!day.hasCompleteCoverage) null
                    else (totalValue / 1000.0 / target).coerceIn(0.0, 1.0)
                }
            } else {
                val v = day.manualEntry.value
                return if (v == Entry.YES_MANUAL || v == Entry.YES_AUTO) 1.0 else 0.0
            }
        } else {
            val entry = habit.computedEntries.get(date)
            val value = entry.value
            if (!habit.isDateIncludedInStatistics(date) || value == Entry.SKIP || value == Entry.UNKNOWN) {
                return null
            }
            if (habit.isNumerical) {
                val target = if (goal.targetValue > 0.0) goal.targetValue else 1.0
                return if (goal.targetType == NumericalHabitType.AT_MOST) {
                    if (value / 1000.0 > target) 0.0 else 1.0
                } else {
                    (value / 1000.0 / target).coerceIn(0.0, 1.0)
                }
            } else {
                return if (value == Entry.YES_MANUAL || value == Entry.YES_AUTO) 1.0 else 0.0
            }
        }
    }

    fun buildStreakState(
        habit: Habit,
        snapshot: EffectiveHabitReadSnapshot? = null
    ): CompareStreakState {
        if (snapshot != null && snapshot.hasContributions) {
            val streaks = snapshot.streakResult.streaks
                ?: return CompareStreakState(habit, null, null, isExact = false)
            val latest = streaks.maxByOrNull { it.end }
            val record = streaks.maxByOrNull { it.length }
            return CompareStreakState(habit, latest, record)
        }
        val presentation = snapshot?.presentationHabit() ?: habit
        val latest = presentation.streaks.getLatest()
        val best = presentation.streaks.getBest(1).firstOrNull()
        val record = if (latest != null && (best == null || latest.length >= best.length)) latest else best
        return CompareStreakState(
            habit = habit,
            latestStreak = latest,
            recordStreak = record
        )
    }

    fun buildFrequency(
        habit: Habit,
        snapshot: EffectiveHabitReadSnapshot? = null
    ): HashMap<LocalDate, Array<Int>> {
        val statsStart = habit.effectiveStatisticsStartDate()
        val map = HashMap<LocalDate, Array<Int>>()
        if (snapshot != null && snapshot.hasContributions) {
            // Snapshot days include algorithm warm-up and future calendar envelopes. Those
            // synthetic UNKNOWN dates must not invalidate or enter historical frequency.
            val observedStart = snapshot.observedFrom ?: return map
            val observedEnd = minOf(snapshot.observedTo ?: return map, getToday())
            val historicalDays = snapshot.days.filter { it.manualEntry.date in observedStart..observedEnd }
            if (!snapshot.canUseLegacyEntryAdapter || historicalDays.any {
                    it.includedInStatistics && !it.isSkipped && !it.hasCompleteCoverage
                }) return map
            historicalDays.forEach { day ->
                val date = day.manualEntry.date
                if (statsStart == null || !date.isOlderThan(statsStart)) {
                    if (day.includedInStatistics && !day.isSkipped) {
                        val weekday = (date.dayOfWeek.daysSinceSunday + 1) % 7
                        val monthStart = date.startOfMonth()
                        val list = map.getOrPut(monthStart) { arrayOf(0, 0, 0, 0, 0, 0, 0) }
                        if (habit.isNumerical) {
                            val value = day.totalValue ?: return hashMapOf()
                            val sum = list[weekday].toLong() + value
                            if (sum > Int.MAX_VALUE || sum < 0) return hashMapOf()
                            list[weekday] = sum.toInt()
                        } else {
                            val v = day.manualEntry.value
                            if (v == Entry.YES_MANUAL || v == Entry.YES_AUTO) {
                                list[weekday] += 1
                            }
                        }
                    }
                }
            }
        } else {
            return habit.originalEntries.computeWeekdayFrequency(
                isNumerical = habit.isNumerical,
                from = statsStart
            )
        }
        return map
    }
}
