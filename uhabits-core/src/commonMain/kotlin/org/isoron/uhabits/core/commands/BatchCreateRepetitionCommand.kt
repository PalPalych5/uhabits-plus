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
package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class BatchCreateRepetitionCommand(
    val habitList: HabitList,
    val habits: List<Habit>,
    val date: LocalDate,
    val value: Int,
    val notes: String
) : Command {
    override fun run() {
        val firstSQLiteEntryList = habits.firstOrNull()?.originalEntries as? SQLiteEntryList
        val repository = firstSQLiteEntryList?.repository
        
        try {
            repository?.execSQL("BEGIN")
            for (habit in habits) {
                val previous = habit.originalEntries.get(date)
                habit.originalEntries.add(Entry(date, value, notes))
                habit.recompute()
                val syncManager = (habitList as? SQLiteHabitList)?.syncManager
                if (previous.value != value || previous.notes != notes) {
                    if (value == Entry.UNKNOWN) {
                        syncManager?.enqueueEntryDelete(habit, date)
                    } else {
                        syncManager?.enqueueEntrySet(habit, date, value, notes)
                    }
                }
            }
            repository?.execSQL("COMMIT")
        } catch (e: Exception) {
            repository?.execSQL("ROLLBACK")
            throw e
        }
        
        habitList.resort()
    }
}
