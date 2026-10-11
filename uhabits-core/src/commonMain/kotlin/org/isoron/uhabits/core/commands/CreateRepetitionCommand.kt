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
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.time.TimeSource

data class CreateRepetitionCommand(
    val habitList: HabitList,
    val habit: Habit,
    val date: LocalDate,
    val value: Int,
    val notes: String
) : Command {
    var timingObserver: ((String, Double) -> Unit)? = null
    private inline fun timed(stage: String, action: () -> Unit) {
        val observer = timingObserver
        if (observer == null) { action(); return }
        val started = TimeSource.Monotonic.markNow()
        action()
        observer(stage, started.elapsedNow().inWholeNanoseconds / 1e6)
    }
    override fun run() {
        val entries = habit.originalEntries
        val previous = entries.get(date)
        if (previous.value == value && previous.notes == notes) return
        timed("repetition.sqliteWrite") { entries.add(Entry(date, value, notes)) }
        timed("repetition.modelRecompute") { habit.recompute() }
        timed("repetition.modelRefresh") {
            habit.id?.let { habitList.refreshHabitFromDatabase(it, habit) }
        }
        timed("repetition.resortAndObservers") { habitList.resort() }
        val syncManager = (habitList as? SQLiteHabitList)?.syncManager ?: return
        timed("repetition.syncQueue") {
            if (value == Entry.UNKNOWN) {
                syncManager.enqueueEntryDelete(habit, date)
            } else {
                syncManager.enqueueEntrySet(habit, date, value, notes)
            }
        }
    }
}
