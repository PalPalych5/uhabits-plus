package org.isoron.uhabits.core.commands

import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException

data class ClearHabitEntriesCommand(
    val habitList: HabitList,
    val habitId: Long
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()
        habit.originalEntries.clear()
        habit.recompute()
        habitList.resort()
    }
}
