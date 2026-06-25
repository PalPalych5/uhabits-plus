package org.isoron.uhabits.core.commands

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

data class AddNumericalEntryOpCommand(
    val habitList: HabitList,
    val habit: Habit,
    val date: LocalDate,
    val deltaValue: Int,
    val notes: String,
    val opType: String = "timer_delta"
) : Command {
    override fun run() {
        if (deltaValue == 0) return
        val entries = habit.originalEntries as? SQLiteEntryList ?: return
        val current = habit.computedEntries.get(date)
        val oldValue = if (current.value == Entry.UNKNOWN || current.value == Entry.SKIP) 0 else current.value
        val newValue = oldValue + deltaValue
        entries.addWithDelta(date, deltaValue, newValue, notes)
        habit.recompute()
        habit.id?.let {
            habitList.refreshHabitFromDatabase(it, habit)
        }
        habitList.resort()
        (habitList as? SQLiteHabitList)?.syncManager?.recordEntryOp(habit, date, deltaValue, opType, notes)
    }
}
