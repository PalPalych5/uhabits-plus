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
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.TruncateField
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.groupedSum
import kotlin.math.roundToInt
import kotlin.math.max

object TodayScreenStateBuilder {
    fun build(habitList: HabitList, date: LocalDate = getToday()): TodayScreenState {
        val blocks = habitList.getBlocks()
        val blocksMap = blocks.associateBy { it.id }
        val fallbackBlock = HabitBlock(
            id = null,
            name = "Прочее",
            color = PaletteColor(18),
            icon = "more_horiz",
            position = 1000
        )

        val items = habitList
            .toList()
            .filter { !it.isArchived }
            .sortedWith(
                compareBy<Habit> { h ->
                    val block = blocksMap[h.blockId] ?: fallbackBlock
                    block.position
                }
                .thenBy { it.position }
                .thenBy { it.name }
            )
            .map { it.toTodayItem(date) }

        val sections = items
            .groupBy { item ->
                val habit = habitList.getById(item.habitId ?: -1)
                blocksMap[habit?.blockId] ?: fallbackBlock
            }
            .toSortedMap(compareBy { it.position })
            .map { (block, sectionItems) ->
                val countableSectionItems = sectionItems.filter { it.status != TodayHabitStatus.SKIPPED }
                TodaySectionState(
                    blockId = block.id,
                    blockName = block.name,
                    blockPosition = block.position,
                    color = block.color,
                    completedCount = countableSectionItems.count { it.isCompleted },
                    totalCount = countableSectionItems.size,
                    focusMinutes = sectionItems.sumOf { it.focusMinutes },
                    items = sectionItems
                )
            }

        val countableItems = items.filter { it.status != TodayHabitStatus.SKIPPED }
        val motivations = buildMotivations(habitList, date, items)
        return TodayScreenState(
            date = date,
            completedCount = countableItems.count { it.isCompleted },
            totalCount = countableItems.size,
            focusMinutes = items.sumOf { it.focusMinutes },
            remaining = countableItems.filter { it.dayTier == DayTier.MINIMUM && !it.isCompleted },
            sections = sections,
            minimum = countableItems.progressFor(setOf(DayTier.MINIMUM)),
            normal = countableItems.progressFor(setOf(DayTier.MINIMUM, DayTier.NORMAL)),
            ideal = countableItems.progressFor(setOf(DayTier.MINIMUM, DayTier.NORMAL, DayTier.IDEAL)),
            motivations = motivations
        )
    }

    private fun Habit.toTodayItem(date: LocalDate): TodayHabitItem {
        val entry = computedEntries.get(date)
        val currentValue = if (isNumerical && entry.value != Entry.UNKNOWN && entry.value != Entry.SKIP) {
            entry.value / 1000.0
        } else {
            null
        }

        val isWeekly = frequency.denominator == 7
        val isMonthly = frequency.denominator == 30
        val isLimit = isNumerical && targetType == NumericalHabitType.AT_MOST

        var periodProgressActual: Double? = null
        var periodProgressTarget: Double? = null
        var periodLabel = PeriodLabel.DAY

        if (isWeekly) {
            val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
            val firstWeekdayEnum = DayOfWeek.values()[firstWeekdayNum - 1]
            val startOfWeek = date.startOfWeek(firstWeekdayEnum)
            val endOfWeek = startOfWeek.plus(6)
            val weekEntries = computedEntries.getByInterval(startOfWeek, endOfWeek)
            val weekSum = weekEntries.groupedSum(
                truncateField = TruncateField.WEEK_NUMBER,
                firstWeekday = firstWeekdayNum,
                isNumerical = isNumerical
            ).firstOrNull()?.value ?: 0
            periodProgressActual = weekSum / 1000.0
            periodProgressTarget = if (isNumerical) {
                frequency.numerator * targetValue
            } else {
                frequency.numerator.toDouble()
            }
            periodLabel = PeriodLabel.WEEK
        } else if (isMonthly) {
            val startOfMonth = date.startOfMonth()
            val endOfMonth = startOfMonth.plus(date.monthLength - 1)
            val monthEntries = computedEntries.getByInterval(startOfMonth, endOfMonth)
            val monthSum = monthEntries.groupedSum(
                truncateField = TruncateField.MONTH,
                isNumerical = isNumerical
            ).firstOrNull()?.value ?: 0
            periodProgressActual = monthSum / 1000.0
            periodProgressTarget = if (isNumerical) {
                frequency.numerator * targetValue
            } else {
                frequency.numerator.toDouble()
            }
            periodLabel = PeriodLabel.MONTH
        } else {
            periodProgressActual = currentValue
            periodProgressTarget = targetValue
            periodLabel = PeriodLabel.DAY
        }

        return TodayHabitItem(
            habitId = id,
            name = name,
            color = color,
            habitType = type,
            targetType = targetType,
            status = todayStatus(entry, periodProgressActual, periodProgressTarget),
            currentValue = currentValue,
            targetValue = if (isNumerical) targetValue else null,
            unit = if (isNumerical) unit else "",
            notes = entry.notes,
            dayTier = dayTier,
            isLimitHabit = isLimit,
            periodProgressActual = periodProgressActual,
            periodProgressTarget = periodProgressTarget,
            periodLabel = periodLabel
        )
    }

    private fun buildMotivations(habitList: HabitList, date: LocalDate, items: List<TodayHabitItem>): List<String> {
        val motivations = mutableListOf<String>()

        val minimumItems = items.filter { it.dayTier == DayTier.MINIMUM && it.status != TodayHabitStatus.SKIPPED }
        if (minimumItems.isNotEmpty() && minimumItems.all { it.isCompleted }) {
            motivations.add("minimum_completed")
        }

        for (habit in habitList.toList()) {
            if (habit.isArchived) continue

            val streaks = habit.streaks.getBest(100)
            val activeStreak = streaks.firstOrNull { it.end == date || it.end == date.minus(1) }

            if (activeStreak != null && (activeStreak.end == date || habit.isCompletedToday())) {
                val len = activeStreak.length
                if (len in setOf(3, 5, 7, 14, 30) || (len > 30 && len % 30 == 0)) {
                    motivations.add("streak_milestone|${habit.name}|$len")
                }
            }

            val todayItem = items.firstOrNull { it.habitId == habit.id }
            if (todayItem != null && todayItem.isCompleted) {
                val yesterdayEntry = habit.computedEntries.get(date.minus(1))
                val yesterdayCompleted = if (habit.isNumerical) {
                    yesterdayEntry.value != Entry.UNKNOWN && yesterdayEntry.value != Entry.SKIP &&
                        (yesterdayEntry.value / 1000.0 >= habit.targetValue)
                } else {
                    yesterdayEntry.value == Entry.YES_MANUAL || yesterdayEntry.value == Entry.YES_AUTO
                }
                if (!yesterdayCompleted) {
                    motivations.add("comeback|${habit.name}")
                }
            }
        }

        return motivations
    }

    private fun Habit.todayStatus(
        entry: Entry,
        periodActual: Double?,
        periodTarget: Double?
    ): TodayHabitStatus {
        if (entry.value == Entry.SKIP) return TodayHabitStatus.SKIPPED

        if (type == HabitType.NUMERICAL) {
            if (targetType == NumericalHabitType.AT_MOST) {
                if (periodActual != null && periodTarget != null) {
                    if (periodActual > periodTarget) return TodayHabitStatus.EXCEEDED
                } else if (entry.value != Entry.UNKNOWN) {
                    val value = entry.value / 1000.0
                    if (value > targetValue) return TodayHabitStatus.EXCEEDED
                }

                if (entry.value == Entry.UNKNOWN) return TodayHabitStatus.UNKNOWN
                return TodayHabitStatus.COMPLETED
            } else {
                if (entry.value == Entry.UNKNOWN) return TodayHabitStatus.UNKNOWN
                val value = entry.value / 1000.0
                return if (value >= targetValue) TodayHabitStatus.COMPLETED else TodayHabitStatus.REMAINING
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

    private fun List<TodayHabitItem>.progressFor(tiers: Set<DayTier>): TodayTierProgress {
        val included = filter { it.dayTier in tiers }
        return TodayTierProgress(
            completedCount = included.count { it.isCompleted },
            totalCount = included.size
        )
    }
}

fun Double.formatTodayValue(): String {
    val rounded = roundToInt()
    return if (this == rounded.toDouble()) rounded.toString() else this.toString()
}
