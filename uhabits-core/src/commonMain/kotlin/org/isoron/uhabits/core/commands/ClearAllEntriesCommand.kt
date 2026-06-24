package org.isoron.uhabits.core.commands

import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class ClearAllEntriesCommand(
    val habitList: HabitList
) : Command {
    override fun run() {
        val sqliteHabitList = habitList as? SQLiteHabitList
        for (habit in habitList.toList()) {
            val deletedDates = habit.originalEntries.getKnown().map { it.date }
            habit.originalEntries.clear()
            habit.recompute()
            deletedDates.forEach { sqliteHabitList?.syncManager?.enqueueEntryDelete(habit, it) }
        }
        habitList.resort()
    }
}
