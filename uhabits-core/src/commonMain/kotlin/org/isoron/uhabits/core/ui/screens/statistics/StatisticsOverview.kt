/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */
package org.isoron.uhabits.core.ui.screens.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.isMinuteUnit
import kotlin.math.roundToInt

data class StatisticsOverviewState(
    val date: LocalDate,
    val completedCount: Int,
    val totalCount: Int,
    val focusMinutes: Double,
    val tiers: List<StatisticsTierProgress>
)

data class StatisticsTierProgress(
    val tier: DayTier,
    val completedCount: Int,
    val totalCount: Int
)

object StatisticsOverviewStateBuilder {
    fun build(
        habitList: HabitList,
        date: LocalDate = getToday(),
        snapshots: Map<org.isoron.uhabits.core.containers.HabitRef, org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot>? = null
    ): StatisticsOverviewState {
        val activeHabits = habitList.toList().filter { !it.isArchived }
        return build(activeHabits, date, date, snapshots = snapshots)
    }

    fun build(
        habits: List<Habit>,
        start: LocalDate,
        end: LocalDate,
        firstWeekday: DayOfWeek = DayOfWeek.entries[getFirstWeekdayNumberAccordingToLocale() - 1],
        snapshots: Map<org.isoron.uhabits.core.containers.HabitRef, org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot>? = null
    ): StatisticsOverviewState {
        var totalCompleted = 0
        var totalCount = 0
        var totalFocusMinutes = 0.0
        val tierCompleted = mutableMapOf<DayTier, Int>()
        val tierTotal = mutableMapOf<DayTier, Int>()
        for (tier in DayTier.entries) {
            tierCompleted[tier] = 0
            tierTotal[tier] = 0
        }

        val items = habits.flatMap { habit ->
            val snapshot = habit.uuid?.let { snapshots?.get(org.isoron.uhabits.core.containers.HabitRef(it)) }
            StatisticsCompletionPolicy.evaluate(habit, start, end, firstWeekday, snapshot)
        }

        val countable = items.filter { it.countable }
        totalCompleted += countable.count { it.completed }
        totalCount += countable.size
        totalFocusMinutes += items.sumOf { it.focusMinutes }

        for (tier in DayTier.entries) {
            val tierItems = when (tier) {
                DayTier.MINIMUM -> countable.filter { it.tier == DayTier.MINIMUM }
                DayTier.NORMAL -> countable.filter { it.tier == DayTier.MINIMUM || it.tier == DayTier.NORMAL }
                DayTier.IDEAL -> countable.filter { it.tier == DayTier.MINIMUM || it.tier == DayTier.NORMAL || it.tier == DayTier.IDEAL }
                DayTier.OPTIONAL -> countable
            }
            tierCompleted[tier] = tierCompleted[tier]!! + tierItems.count { it.completed }
            tierTotal[tier] = tierTotal[tier]!! + tierItems.size
        }

        val tiersList = DayTier.entries.map { tier ->
            StatisticsTierProgress(
                tier = tier,
                completedCount = tierCompleted[tier] ?: 0,
                totalCount = tierTotal[tier] ?: 0
            )
        }

        return StatisticsOverviewState(
            date = start,
            completedCount = totalCompleted,
            totalCount = totalCount,
            focusMinutes = totalFocusMinutes,
            tiers = tiersList
        )
    }
}

data class StatisticsCompletion(
    val date: LocalDate,
    val tier: DayTier,
    val countable: Boolean,
    val completed: Boolean,
    val skipped: Boolean,
    val failed: Boolean,
    val focusMinutes: Double,
    val limitViolation: Boolean
)

object StatisticsCompletionPolicy {
    fun evaluate(
        habit: Habit,
        start: LocalDate,
        end: LocalDate,
        firstWeekday: DayOfWeek,
        snapshot: org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot? = null
    ): List<StatisticsCompletion> {
        val habitStart = snapshot?.observedFrom
            ?: habit.effectiveStatisticsStartDate()
            ?: start
        val effectiveStart = habit.effectiveStatisticsStartDate()
            ?.let { if (it.isNewerThan(start)) it else start }
            ?: if (habitStart.isNewerThan(start)) habitStart else start
        var current = effectiveStart
        val result = mutableListOf<StatisticsCompletion>()

        while (current <= end) {
            val goal = habit.goalAt(current)
            if (habit.isNumerical &&
                goal.targetType == NumericalHabitType.AT_MOST &&
                (goal.frequency.denominator == 7 || goal.frequency.denominator == 30)
            ) {
                val periodStart = if (goal.frequency.denominator == 7) {
                    current.startOfWeek(firstWeekday)
                } else {
                    current.startOfMonth()
                }
                val periodEnd = if (goal.frequency.denominator == 7) {
                    periodStart.plus(6)
                } else {
                    periodStart.plus(periodStart.monthLength - 1)
                }
                val unitStart = if (periodStart.isOlderThan(current)) current else periodStart
                val unitEnd = if (periodEnd.isNewerThan(end)) end else periodEnd
                result.add(evaluateAtMostPeriod(habit, unitStart, unitEnd, goal.targetValue, snapshot))
                current = unitEnd.plus(1)
            } else {
                result.add(evaluateDay(habit, current, snapshot))
                current = current.plus(1)
            }
        }

        return result
    }

    private fun evaluateDay(
        habit: Habit,
        date: LocalDate,
        snapshot: org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot? = null
    ): StatisticsCompletion {
        if (snapshot == null || !snapshot.hasContributions) {
            val entry = habit.computedEntries.get(date)
            if (entry.value == Entry.SKIP) {
                return completion(habit, date, skipped = true)
            }
            if (entry.value == Entry.UNKNOWN) {
                return completion(habit, date)
            }

            val goal = habit.goalAt(date)
            val completed = if (habit.isNumerical) {
                val value = entry.value / 1000.0
                when (goal.targetType) {
                    NumericalHabitType.AT_LEAST -> value >= goal.targetValue
                    NumericalHabitType.AT_MOST -> value <= goal.targetValue
                }
            } else {
                entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO
            }

            val limitViolation = habit.isNumerical &&
                goal.targetType == NumericalHabitType.AT_MOST &&
                !completed

            return completion(
                habit = habit,
                date = date,
                countable = true,
                completed = completed,
                failed = !completed,
                focusMinutes = focusMinutes(habit, entry.value / 1000.0, date),
                limitViolation = limitViolation
            )
        }

        val day = snapshot.day(date)
        if (!day.includedInStatistics) {
            return completion(habit, date)
        }
        if (day.isSkipped) {
            return completion(habit, date, skipped = true)
        }
        if (day.totalValue == null) {
            return completion(habit, date)
        }

        val goal = habit.goalAt(date)
        val value = day.totalValue / 1000.0
        val countable: Boolean
        val completed: Boolean
        val failed: Boolean
        val limitViolation: Boolean

        if (!habit.isNumerical) {
            val entry = day.manualEntry
            val checked = entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO
            countable = true
            completed = checked
            failed = !checked
            limitViolation = false
        } else {
            when (goal.targetType) {
                NumericalHabitType.AT_LEAST -> {
                    if (value >= goal.targetValue) {
                        countable = true
                        completed = true
                        failed = false
                        limitViolation = false
                    } else if (day.hasCompleteCoverage) {
                        countable = true
                        completed = false
                        failed = true
                        limitViolation = false
                    } else {
                        countable = false
                        completed = false
                        failed = false
                        limitViolation = false
                    }
                }
                NumericalHabitType.AT_MOST -> {
                    if (value > goal.targetValue) {
                        countable = true
                        completed = false
                        failed = true
                        limitViolation = true
                    } else if (day.hasCompleteCoverage) {
                        countable = true
                        completed = true
                        failed = false
                        limitViolation = false
                    } else {
                        countable = false
                        completed = false
                        failed = false
                        limitViolation = false
                    }
                }
            }
        }

        return completion(
            habit = habit,
            date = date,
            countable = countable,
            completed = completed,
            failed = failed,
            focusMinutes = focusMinutes(habit, value, date),
            limitViolation = limitViolation
        )
    }

    private fun evaluateAtMostPeriod(
        habit: Habit,
        start: LocalDate,
        end: LocalDate,
        targetValue: Double,
        snapshot: org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot? = null
    ): StatisticsCompletion {
        if (snapshot == null || !snapshot.hasContributions) {
            val entries = habit.statisticsEntries(start, end)
            val knownValues = entries.filter { it.value != Entry.SKIP && it.value != Entry.UNKNOWN }
            val skipped = knownValues.isEmpty() && entries.any { it.value == Entry.SKIP }
            if (knownValues.isEmpty()) {
                return completion(habit, start, skipped = skipped)
            }

            val actual = knownValues.sumOf { kotlin.math.max(0, it.value) } / 1000.0
            val completed = actual <= targetValue
            return completion(
                habit = habit,
                date = start,
                countable = true,
                completed = completed,
                failed = !completed,
                limitViolation = !completed
            )
        }

        val days = mutableListOf<org.isoron.uhabits.core.models.projection.EffectiveHabitDay>()
        var d = start
        while (d <= end) {
            days.add(snapshot.day(d))
            d = d.plus(1)
        }
        val includedDays = days.filter { it.includedInStatistics }
        val skipped = includedDays.isNotEmpty() && includedDays.all { it.isSkipped }
        val knownDays = includedDays.filter { !it.isSkipped && it.totalValue != null }
        if (knownDays.isEmpty()) {
            return completion(habit, start, skipped = skipped)
        }

        val actual = knownDays.sumOf { kotlin.math.max(0L, it.totalValue!!) } / 1000.0
        if (actual > targetValue) {
            return completion(
                habit = habit,
                date = start,
                countable = true,
                completed = false,
                failed = true,
                limitViolation = true
            )
        }
        val complete = includedDays.all { it.isSkipped || it.hasCompleteCoverage }
        if (complete) {
            return completion(
                habit = habit,
                date = start,
                countable = true,
                completed = true,
                failed = false,
                limitViolation = false
            )
        } else {
            return completion(
                habit = habit,
                date = start,
                countable = false,
                completed = false,
                failed = false,
                limitViolation = false
            )
        }
    }

    private fun focusMinutes(habit: Habit, value: Double, date: LocalDate): Double {
        if (!habit.isNumerical) return 0.0
        val goal = habit.goalAt(date)
        if (goal.targetType != NumericalHabitType.AT_LEAST) return 0.0
        if (!goal.unit.isMinuteUnit()) return 0.0
        return value
    }

    private fun completion(
        habit: Habit,
        date: LocalDate,
        countable: Boolean = false,
        completed: Boolean = false,
        skipped: Boolean = false,
        failed: Boolean = false,
        focusMinutes: Double = 0.0,
        limitViolation: Boolean = false
    ) = StatisticsCompletion(
        date = date,
        tier = habit.dayTier,
        countable = countable,
        completed = completed,
        skipped = skipped,
        failed = failed,
        focusMinutes = focusMinutes,
        limitViolation = limitViolation
    )
}

fun Double.formatStatisticsValue(): String {
    val rounded = roundToInt()
    return if (this == rounded.toDouble()) rounded.toString() else this.toString()
}
