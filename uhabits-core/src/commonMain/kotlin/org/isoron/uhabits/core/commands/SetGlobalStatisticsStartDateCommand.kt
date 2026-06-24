package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class SetGlobalStatisticsStartDateCommand(
    val habitList: HabitList,
    val statisticsStartDate: LocalDate?
) : Command {
    override fun run() {
        habitList.globalStatisticsStartDate = statisticsStartDate
        for (habit in habitList) {
            habit.globalStatisticsStartDate = statisticsStartDate
            habit.recompute()
        }
        habitList.resort()
        (habitList as? SQLiteHabitList)?.syncManager?.enqueueAppSettingChange(
            "global_stats_start_timestamp",
            statisticsStartDate?.unixTime
        )
    }
}
