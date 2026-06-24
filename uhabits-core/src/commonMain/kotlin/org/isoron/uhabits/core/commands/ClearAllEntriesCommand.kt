package org.isoron.uhabits.core.commands

import org.isoron.uhabits.core.models.HabitList

data class ClearAllEntriesCommand(
    val habitList: HabitList
) : Command {
    override fun run() {
        for (habit in habitList.toList()) {
            habit.originalEntries.clear()
            habit.recompute()
        }
        habitList.resort()
    }
}
