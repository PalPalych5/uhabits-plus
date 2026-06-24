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
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.TruncateField
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class Habit(
    var color: PaletteColor = PaletteColor(8),
    var description: String = "",
    var frequency: Frequency = Frequency.DAILY,
    var id: Long? = null,
    var isArchived: Boolean = false,
    var name: String = "",
    var position: Int = 0,
    var question: String = "",
    var reminder: Reminder? = null,
    var targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
    var targetValue: Double = 0.0,
    var type: HabitType = HabitType.YES_NO,
    var unit: String = "",
    var uuid: String? = null,
    var goalHistory: MutableList<HabitGoal> = mutableListOf(),
    var statisticsStartDate: LocalDate? = null,
    var globalStatisticsStartDate: LocalDate? = null,
    var dayTier: DayTier = DayTier.NORMAL,
    var timerEnabled: Boolean = false,
    var blockId: Long? = null,
    val computedEntries: EntryList,
    val originalEntries: EntryList,
    val scores: ScoreList,
    val streaks: StreakList
) {
    init {
        if (uuid == null) this.uuid = Uuid.random().toHexString()
    }

    var observable = ModelObservable()

    val isNumerical: Boolean
        get() = type == HabitType.NUMERICAL

    val uriString: String
        get() = "content://org.isoron.uhabits/habit/$id"

    fun hasReminder(): Boolean = reminder != null

    fun isCompletedToday(): Boolean {
        return isCompletedOn(getToday())
    }

    fun isEnteredToday(): Boolean {
        val today = getToday()
        val value = computedEntries.get(today).value
        return value != Entry.UNKNOWN
    }

    fun currentGoal(): HabitGoal = HabitGoal(
        effectiveDate = goalHistory.maxByOrNull { it.effectiveDate }?.effectiveDate ?: LocalDate(2000, 1, 1),
        frequency = frequency,
        targetType = targetType,
        targetValue = targetValue,
        unit = unit
    )

    fun normalizedGoalHistory(): List<HabitGoal> {
        if (goalHistory.isEmpty()) {
            goalHistory = mutableListOf(
                HabitGoal(
                    effectiveDate = LocalDate(2000, 1, 1),
                    frequency = frequency,
                    targetType = targetType,
                    targetValue = targetValue,
                    unit = unit
                )
            )
        } else if (goalHistory.size == 1) {
            goalHistory[0] = goalHistory[0].copy(
                frequency = frequency,
                targetType = targetType,
                targetValue = targetValue,
                unit = unit
            )
        }
        val sorted = goalHistory.sortedBy { it.effectiveDate }
        val deduped = sorted.fold(mutableListOf<HabitGoal>()) { acc, goal ->
            if (acc.isNotEmpty() && acc.last().effectiveDate == goal.effectiveDate) {
                acc[acc.lastIndex] = goal
            } else {
                acc.add(goal)
            }
            acc
        }
        return deduped
    }

    fun goalAt(date: LocalDate): HabitGoal {
        val history = normalizedGoalHistory()
        return history.lastOrNull { !it.effectiveDate.isNewerThan(date) } ?: history.first()
    }

    fun effectiveStatisticsStartDate(): LocalDate? {
        val local = statisticsStartDate
        val global = globalStatisticsStartDate
        return when {
            local == null -> global
            global == null -> local
            local.isNewerThan(global) -> local
            else -> global
        }
    }

    fun isDateIncludedInStatistics(date: LocalDate): Boolean {
        val start = effectiveStatisticsStartDate() ?: return true
        return !date.isOlderThan(start)
    }

    fun isCompletedOn(date: LocalDate): Boolean {
        if (!isDateIncludedInStatistics(date)) return false
        val entry = computedEntries.get(date)
        val value = entry.value
        if (isNumerical) {
            if (value == Entry.UNKNOWN || value == Entry.SKIP) return false
            val goal = goalAt(date)
            return when (goal.targetType) {
                NumericalHabitType.AT_LEAST -> value / 1000.0 >= goal.targetValue
                NumericalHabitType.AT_MOST -> {
                    val actual = periodActualOn(date)
                    actual != null && actual <= goal.targetValue
                }
            }
        }
        return value != Entry.NO && value != Entry.UNKNOWN
    }

    fun periodActualOn(date: LocalDate): Double? {
        val entry = computedEntries.get(date)
        if (entry.value == Entry.UNKNOWN || entry.value == Entry.SKIP) return null
        val goal = goalAt(date)
        return when (goal.frequency.denominator) {
            7 -> {
                val firstWeekdayNum = getFirstWeekdayNumberAccordingToLocale()
                val start = date.startOfWeek(org.isoron.platform.time.DayOfWeek.entries[firstWeekdayNum - 1])
                val end = start.plus(6)
                statisticsEntries(start, end).groupedSum(
                    truncateField = TruncateField.WEEK_NUMBER,
                    firstWeekday = firstWeekdayNum,
                    isNumerical = true
                ).firstOrNull()?.value?.div(1000.0) ?: 0.0
            }
            30 -> {
                val start = date.startOfMonth()
                val end = start.plus(date.monthLength - 1)
                statisticsEntries(start, end).groupedSum(
                    truncateField = TruncateField.MONTH,
                    isNumerical = true
                ).firstOrNull()?.value?.div(1000.0) ?: 0.0
            }
            else -> entry.value / 1000.0
        }
    }

    fun statisticsEntries(from: LocalDate, to: LocalDate): List<Entry> {
        return computedEntries.getByInterval(from, to).filter { isDateIncludedInStatistics(it.date) }
    }

    fun recompute() {
        val history = normalizedGoalHistory()
        val latestGoal = history.last()
        frequency = latestGoal.frequency
        targetType = latestGoal.targetType
        targetValue = latestGoal.targetValue
        unit = latestGoal.unit

        computedEntries.recomputeFrom(
            originalEntries = originalEntries,
            frequency = frequency,
            isNumerical = isNumerical,
            goalHistory = history
        )

        val today = getToday()
        val to = today.plus(30)
        val entries = computedEntries.getKnown()
        var from = effectiveStatisticsStartDate() ?: entries.lastOrNull()?.date ?: today
        val oldestEntry = entries.lastOrNull()?.date
        if (oldestEntry != null && oldestEntry.isOlderThan(from)) from = oldestEntry
        if (from.isNewerThan(to)) from = to

        scores.recompute(
            habit = this,
            computedEntries = computedEntries,
            from = from,
            to = to
        )

        streaks.recompute(
            habit = this,
            computedEntries,
            from,
            to
        )
    }

    fun copyFrom(other: Habit) {
        this.color = other.color
        this.description = other.description
        this.frequency = other.frequency
        // this.id should not be copied
        this.isArchived = other.isArchived
        this.name = other.name
        this.position = other.position
        this.question = other.question
        this.reminder = other.reminder
        this.targetType = other.targetType
        this.targetValue = other.targetValue
        this.type = other.type
        this.unit = other.unit
        this.uuid = other.uuid
        this.goalHistory = other.goalHistory.toMutableList()
        this.statisticsStartDate = other.statisticsStartDate
        this.globalStatisticsStartDate = other.globalStatisticsStartDate
        this.dayTier = other.dayTier
        this.timerEnabled = other.timerEnabled
        this.blockId = other.blockId
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Habit) return false

        if (color != other.color) return false
        if (description != other.description) return false
        if (frequency != other.frequency) return false
        if (id != other.id) return false
        if (isArchived != other.isArchived) return false
        if (name != other.name) return false
        if (position != other.position) return false
        if (question != other.question) return false
        if (reminder != other.reminder) return false
        if (targetType != other.targetType) return false
        if (targetValue != other.targetValue) return false
        if (type != other.type) return false
        if (unit != other.unit) return false
        if (uuid != other.uuid) return false
        if (goalHistory != other.goalHistory) return false
        if (statisticsStartDate != other.statisticsStartDate) return false
        if (globalStatisticsStartDate != other.globalStatisticsStartDate) return false
        if (dayTier != other.dayTier) return false
        if (timerEnabled != other.timerEnabled) return false
        if (blockId != other.blockId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + frequency.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + isArchived.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + position
        result = 31 * result + question.hashCode()
        result = 31 * result + (reminder?.hashCode() ?: 0)
        result = 31 * result + targetType.value
        result = 31 * result + targetValue.hashCode()
        result = 31 * result + type.value
        result = 31 * result + unit.hashCode()
        result = 31 * result + (uuid?.hashCode() ?: 0)
        result = 31 * result + goalHistory.hashCode()
        result = 31 * result + (statisticsStartDate?.hashCode() ?: 0)
        result = 31 * result + (globalStatisticsStartDate?.hashCode() ?: 0)
        result = 31 * result + dayTier.hashCode()
        result = 31 * result + timerEnabled.hashCode()
        result = 31 * result + (blockId?.hashCode() ?: 0)
        return result
    }
}
