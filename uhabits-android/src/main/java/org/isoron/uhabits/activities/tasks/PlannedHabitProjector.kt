/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.WeekdayList

/**
 * Projects scheduled habits onto calendar dates according to their frequency and reminder schedule.
 *
 * Rules:
 * 1. Archived habits are never projected.
 * 2. Dates outside the habit's effective statistics start date are excluded.
 * 3. Habits with daily frequency (DAILY or numerator >= denominator) are scheduled every day.
 * 4. Habits with an active reminder on specific weekdays are scheduled on those weekdays at the reminder time.
 * 5. Habits with weekly frequency (e.g. 3/7) without specific reminder days cannot be placed on specific dates
 *    in the current domain model; these are intentionally not projected (limitation documented in PR 11).
 * 6. Planned habits are strictly informational markers and do NOT affect habit progress, repetitions, or scores.
 */
object PlannedHabitProjector {

    /**
     * Checks if the given date's day of week is enabled in the WeekdayList.
     * In Loop's WeekdayList packing:
     * Bit 0: Saturday (daysSince2000 % 7 == 0)
     * Bit 1: Sunday   (daysSince2000 % 7 == 1)
     * Bit 2: Monday   (daysSince2000 % 7 == 2)
     * Bit 3: Tuesday  (daysSince2000 % 7 == 3)
     * Bit 4: Wednesday(daysSince2000 % 7 == 4)
     * Bit 5: Thursday (daysSince2000 % 7 == 5)
     * Bit 6: Friday   (daysSince2000 % 7 == 6)
     */
    fun isWeekdayActive(weekdayList: WeekdayList, date: LocalDate): Boolean {
        val weekdayIndex = ((date.daysSince2000 % 7) + 7) % 7
        return (weekdayList.toInteger() and (1 shl weekdayIndex)) != 0
    }

    fun projectForDate(habits: List<Habit>, date: LocalDate): List<PlannedHabit> {
        val result = mutableListOf<PlannedHabit>()
        for (habit in habits) {
            val habitId = habit.id ?: continue
            if (habit.isArchived) continue
            if (!habit.isDateIncludedInStatistics(date)) continue

            val reminder = habit.reminder
            val hasActiveReminderToday = reminder != null && !reminder.days.isEmpty && isWeekdayActive(reminder.days, date)
            val isDaily = habit.frequency == Frequency.DAILY || habit.frequency.numerator >= habit.frequency.denominator

            if (isDaily || hasActiveReminderToday) {
                result.add(
                    PlannedHabit(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        name = habit.name,
                        color = habit.color,
                        date = date,
                        reminderHour = if (hasActiveReminderToday) reminder?.hour else null,
                        reminderMinute = if (hasActiveReminderToday) reminder?.minute else null,
                        isNumerical = habit.isNumerical,
                        targetValue = habit.targetValue,
                        unit = habit.unit
                    )
                )
            }
        }
        return result
    }

    fun projectForRange(habits: List<Habit>, startDate: LocalDate, endDate: LocalDate): List<PlannedHabit> {
        if (startDate > endDate) return emptyList()
        val result = mutableListOf<PlannedHabit>()
        var current = startDate
        while (current <= endDate) {
            result.addAll(projectForDate(habits, current))
            current = current.plus(1)
        }
        return result
    }
}
