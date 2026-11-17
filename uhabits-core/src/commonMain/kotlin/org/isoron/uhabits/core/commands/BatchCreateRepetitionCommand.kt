/* SPDX-License-Identifier: GPL-3.0-or-later */
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
        for (habit in habits) {
            if (habit.isNumerical) {
                val previous = habit.originalEntries.get(date)
                if (value != previous.value) {
                    require(value == Entry.UNKNOWN || value == Entry.SKIP || (value in 0..Entry.MAX_SCALED_VALUE)) {
                        "Numerical habit repetition value out of bounds: $value"
                    }
                }
            }
        }
        val firstSQLiteEntryList = habits.firstOrNull()?.originalEntries as? SQLiteEntryList
        val repository = firstSQLiteEntryList?.repository
        val scope = habits.mapNotNull { it.uuid?.let { u -> org.isoron.uhabits.core.containers.HabitRef(u) } }.toSet()
        val runBlock = {
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
                for (habit in habits) {
                    habit.id?.let {
                        habitList.refreshHabitFromDatabase(it, habit)
                    }
                }
            } catch (e: Exception) {
                repository?.execSQL("ROLLBACK")
                throw e
            }
            habitList.resort()
        }
        if (scope.isNotEmpty()) {
            org.isoron.uhabits.core.models.projection.EffectiveReadRevision.withMutationScope(scope, runBlock)
        } else {
            runBlock()
        }
    }
}
