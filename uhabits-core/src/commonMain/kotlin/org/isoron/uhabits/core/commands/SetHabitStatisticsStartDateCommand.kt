package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException

data class SetHabitStatisticsStartDateCommand(
    val habitList: HabitList,
    val habitId: Long,
    val statisticsStartDate: LocalDate?
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()
        habit.statisticsStartDate = statisticsStartDate
        habitList.update(habit)
        habit.observable.notifyListeners()
        habit.recompute()
        habitList.resort()
    }
}
