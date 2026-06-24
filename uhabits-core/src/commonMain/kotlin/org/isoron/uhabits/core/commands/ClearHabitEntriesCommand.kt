package org.isoron.uhabits.core.commands

import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class ClearHabitEntriesCommand(
    val habitList: HabitList,
    val habitId: Long
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()
        val deletedDates = habit.originalEntries.getKnown().map { it.date }
        habit.originalEntries.clear()
        habit.recompute()
        habitList.resort()
        val syncManager = (habitList as? SQLiteHabitList)?.syncManager
        deletedDates.forEach { syncManager?.enqueueEntryDelete(habit, it) }
    }
}
