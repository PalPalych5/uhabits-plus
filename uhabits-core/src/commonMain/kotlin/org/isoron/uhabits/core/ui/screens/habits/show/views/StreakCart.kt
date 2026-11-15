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

package org.isoron.uhabits.core.ui.screens.habits.show.views

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.ui.views.Theme

enum class StreakPeriod {
    WEEK,
    MONTH,
    ALL
}

data class StreakSlice(
    val latestStreak: Streak?,
    val bestStreaks: List<Streak>
)

data class StreakCardState(
    val color: PaletteColor,
    val latestStreak: Streak?,
    val bestStreaks: List<Streak>,
    val theme: Theme,
    val allStreaks: List<Streak> = (listOfNotNull(latestStreak) + bestStreaks).distinct(),
    val firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
    val today: LocalDate = getToday()
) {
    fun forPeriod(period: StreakPeriod): StreakSlice {
        val pool = (allStreaks + listOfNotNull(latestStreak) + bestStreaks)
            .distinct()
            .filter { it.length > 0 }
        val filtered = when (period) {
            StreakPeriod.ALL -> pool
            StreakPeriod.WEEK -> {
                val periodStart = today.startOfWeek(firstWeekday)
                val periodEnd = periodStart.plus(6)
                pool.filter { !it.end.isOlderThan(periodStart) && !it.start.isNewerThan(periodEnd) }
            }
            StreakPeriod.MONTH -> {
                val periodStart = today.startOfMonth()
                val periodEnd = periodStart.plus(periodStart.monthLength - 1)
                pool.filter { !it.end.isOlderThan(periodStart) && !it.start.isNewerThan(periodEnd) }
            }
        }
        val latest = filtered.maxWithOrNull { a, b -> a.compareNewer(b) }
        val best = filtered
            .filterNot { it == latest }
            .sortedWith { a, b -> b.compareLonger(a) }
            .take(5)
        return StreakSlice(latestStreak = latest, bestStreaks = best)
    }
}

class StreakCartPresenter {
    companion object {
        fun buildState(
            habit: Habit,
            theme: Theme,
            firstWeekday: DayOfWeek = DayOfWeek.MONDAY,
            today: LocalDate = getToday()
        ): StreakCardState {
            val allStreaks = habit.streaks.getAll()
            val latestStreak = allStreaks.maxWithOrNull { a, b -> a.compareNewer(b) } ?: habit.streaks.getLatest()
            return StreakCardState(
                color = habit.color,
                latestStreak = latestStreak,
                bestStreaks = habit.streaks
                    .getBest(11)
                    .filterNot { it == latestStreak }
                    .take(10),
                theme = theme,
                allStreaks = allStreaks,
                firstWeekday = firstWeekday,
                today = today
            )
        }
    }
}
