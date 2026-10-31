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
package org.isoron.uhabits.core.models

import org.isoron.platform.Synchronized
import org.isoron.platform.time.LocalDate
import kotlin.math.min

class StreakList {
    private val list = ArrayList<Streak>()

    @Synchronized
    fun getLatest(): Streak? = list.maxByOrNull { it.end.daysSince2000 }

    @Synchronized
    fun getBest(limit: Int): List<Streak> {
        list.sortWith { s1: Streak, s2: Streak -> s2.compareLonger(s1) }
        return list.subList(0, min(list.size, limit)).apply {
            sortWith { s1: Streak, s2: Streak -> s2.compareNewer(s1) }
        }.toList()
    }

    @Synchronized
    fun setAllFrom(streaks: List<Streak>) {
        list.clear()
        list.addAll(streaks)
    }

    @Synchronized
    fun recompute(
        habit: Habit,
        computedEntries: EntryList,
        from: LocalDate,
        to: LocalDate
    ) {
        list.clear()
        if (from.isNewerThan(to)) return

        var begin: LocalDate? = null
        var end: LocalDate? = null
        var current = to
        while (!current.isOlderThan(from)) {
            if (habit.isCompletedOn(current)) {
                if (begin == null) {
                    begin = current
                    end = current
                } else if (current == begin.minus(1)) {
                    begin = current
                } else {
                    list.add(Streak(begin, end!!))
                    begin = current
                    end = current
                }
            }
            current = current.minus(1)
        }
        if (begin != null && end != null) {
            list.add(Streak(begin, end))
        }
    }
}
