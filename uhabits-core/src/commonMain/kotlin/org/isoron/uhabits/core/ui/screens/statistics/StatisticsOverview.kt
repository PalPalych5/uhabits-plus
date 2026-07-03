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

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
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
    fun build(habitList: HabitList, date: LocalDate = getToday()): StatisticsOverviewState {
        val items = habitList
            .toList()
            .filter { !it.isArchived }
            .filter { it.isDateIncludedInStatistics(date) }
            .map { habit ->
                val entry = habit.computedEntries.get(date)
                OverviewItem(
                    tier = habit.dayTier,
                    completed = habit.isCompletedForOverview(entry),
                    skipped = entry.value == Entry.SKIP,
                    focusMinutes = habit.focusMinutesForOverview(entry)
                )
            }

        val countable = items.filter { !it.skipped }
        return StatisticsOverviewState(
            date = date,
            completedCount = countable.count { it.completed },
            totalCount = countable.size,
            focusMinutes = items.sumOf { it.focusMinutes },
            tiers = DayTier.entries.map { tier ->
                val tierItems = countable.filter { it.tier == tier }
                StatisticsTierProgress(
                    tier = tier,
                    completedCount = tierItems.count { it.completed },
                    totalCount = tierItems.size
                )
            }
        )
    }

    private fun Habit.isCompletedForOverview(entry: Entry): Boolean {
        if (entry.value == Entry.SKIP || entry.value == Entry.UNKNOWN) return false
        if (type != HabitType.NUMERICAL) {
            return entry.value == Entry.YES_MANUAL || entry.value == Entry.YES_AUTO
        }
        return isCompletedOn(entry.date)
    }

    private fun Habit.focusMinutesForOverview(entry: Entry): Double {
        if (type != HabitType.NUMERICAL) return 0.0
        val goal = goalAt(entry.date)
        if (goal.targetType != NumericalHabitType.AT_LEAST) return 0.0
        if (!goal.unit.isMinuteUnit()) return 0.0
        if (entry.value == Entry.SKIP || entry.value == Entry.UNKNOWN) return 0.0
        return entry.value / 1000.0
    }

    private data class OverviewItem(
        val tier: DayTier,
        val completed: Boolean,
        val skipped: Boolean,
        val focusMinutes: Double
    )
}

fun Double.formatStatisticsValue(): String {
    val rounded = roundToInt()
    return if (this == rounded.toDouble()) rounded.toString() else this.toString()
}
