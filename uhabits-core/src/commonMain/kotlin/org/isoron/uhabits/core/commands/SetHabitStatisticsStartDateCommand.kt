package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class SetHabitStatisticsStartDateCommand(
    val habitList: HabitList,
    val habitId: Long,
    val statisticsStartDate: LocalDate?
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()
        habit.statisticsStartDate = statisticsStartDate
        habit.recompute()
        habitList.update(habit)
        habit.observable.notifyListeners()
        habitList.resort()
        (habitList as? SQLiteHabitList)?.syncManager?.enqueueHabitUpdate(habit)
    }
}
