/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.ui.screens.habits.today

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import kotlin.math.roundToInt

object TodayScreenStateBuilder {
    private val minuteUnits = setOf("min", "mins", "minute", "minutes", "мин", "минута", "минуты", "минут")

    fun build(habitList: HabitList, date: LocalDate = getToday()): TodayScreenState {
        val items = habitList
            .toList()
            .filter { !it.isArchived }
            .sortedWith(
                compareBy<Habit> { it.color.toTodaySectionId().order }
                    .thenBy { it.position }
                    .thenBy { it.name }
            )
            .map { it.toTodayItem(date) }

        val sections = items
            .groupBy { it.color.toTodaySectionId() }
            .toSortedMap(compareBy { it.order })
            .map { (sectionId, sectionItems) ->
                TodaySectionState(
                    id = sectionId,
                    color = sectionItems.first().color,
                    completedCount = sectionItems.count { it.isCompleted },
                    totalCount = sectionItems.size,
                    focusMinutes = sectionItems.sumOf { it.focusMinutes },
                    items = sectionItems
                )
            }

        return TodayScreenState(
            date = date,
            completedCount = items.count { it.isCompleted },
            totalCount = items.size,
            focusMinutes = items.sumOf { it.focusMinutes },
            remaining = items.filter { !it.isCompleted && it.status != TodayHabitStatus.SKIPPED },
            sections = sections
        )
    }

    private fun Habit.toTodayItem(date: LocalDate): TodayHabitItem {
        val entry = computedEntries.get(date)
        val currentValue = if (isNumerical && entry.value != Entry.UNKNOWN && entry.value != Entry.SKIP) {
            entry.value / 1000.0
        } else {
            null
        }
        return TodayHabitItem(
            habitId = id,
            name = name,
            color = color,
            habitType = type,
            targetType = targetType,
            status = todayStatus(entry),
            currentValue = currentValue,
            targetValue = if (isNumerical) targetValue else null,
            unit = if (isNumerical) unit else "",
            notes = entry.notes
        )
    }

    private fun Habit.todayStatus(entry: Entry): TodayHabitStatus {
        if (entry.value == Entry.SKIP) return TodayHabitStatus.SKIPPED

        if (type == HabitType.NUMERICAL) {
            if (entry.value == Entry.UNKNOWN) return TodayHabitStatus.UNKNOWN
            val value = entry.value / 1000.0
            return when (targetType) {
                NumericalHabitType.AT_LEAST -> {
                    if (value >= targetValue) TodayHabitStatus.COMPLETED else TodayHabitStatus.REMAINING
                }
                NumericalHabitType.AT_MOST -> {
                    if (value <= targetValue) TodayHabitStatus.COMPLETED else TodayHabitStatus.EXCEEDED
                }
            }
        }

        return when (entry.value) {
            Entry.NO -> TodayHabitStatus.REMAINING
            Entry.UNKNOWN -> TodayHabitStatus.UNKNOWN
            else -> TodayHabitStatus.COMPLETED
        }
    }

    private val TodayHabitItem.focusMinutes: Double
        get() {
            if (habitType != HabitType.NUMERICAL) return 0.0
            if (targetType != NumericalHabitType.AT_LEAST) return 0.0
            if (!unit.isMinuteUnit()) return 0.0
            return currentValue ?: 0.0
        }

    private fun String.isMinuteUnit(): Boolean = trim().lowercase() in minuteUnits

    private fun PaletteColor.toTodaySectionId(): TodaySectionId {
        return when (paletteIndex) {
            0, 1, 15 -> TodaySectionId.LIMITS
            2, 3, 4 -> TodaySectionId.ROUTINE
            5, 6, 7 -> TodaySectionId.BODY
            8 -> TodaySectionId.CARE
            9, 10, 11, 12 -> TodaySectionId.INTELLECT
            13, 14 -> TodaySectionId.SPEECH
            else -> TodaySectionId.OTHER
        }
    }
}

fun Double.formatTodayValue(): String {
    val rounded = roundToInt()
    return if (this == rounded.toDouble()) rounded.toString() else this.toString()
}
