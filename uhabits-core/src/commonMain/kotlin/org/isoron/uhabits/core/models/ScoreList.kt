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
import org.isoron.uhabits.core.models.Score.Companion.compute
import kotlin.math.max
import kotlin.math.min

class ScoreList {

    private val map = mutableMapOf<LocalDate, Score>()

    /**
     * Returns the score for a given day. If the date given happens before the first
     * repetition of the habit or after the last computed score, returns a score with value zero.
     */
    @Synchronized
    operator fun get(date: LocalDate): Score {
        return map[date] ?: Score(date, 0.0)
    }

    /**
     * Returns the list of scores that fall within the given interval.
     *
     * There is exactly one score per day in the interval. The endpoints of the interval are
     * included. The list is ordered by date (decreasing). That is, the first score
     * corresponds to the newest date, and the last score corresponds to the oldest date.
     */
    @Synchronized
    fun getByInterval(
        from: LocalDate,
        to: LocalDate
    ): List<Score> {
        val result: MutableList<Score> = mutableListOf()
        if (from.isNewerThan(to)) return result
        var current = to
        while (!current.isOlderThan(from)) {
            result.add(get(current))
            current = current.minus(1)
        }
        return result
    }

    @Synchronized
    fun setAllFrom(scores: List<Score>) {
        map.clear()
        for (score in scores) {
            map[score.date] = score
        }
    }

    /**
     * Recomputes all scores between the provided [from] and [to] dates.
     */
    @Synchronized
    fun recompute(
        habit: Habit,
        computedEntries: EntryList,
        from: LocalDate,
        to: LocalDate
    ) {
        map.clear()
        if (from.isNewerThan(to)) return
        val isNumerical = habit.isNumerical
        val statsStart = habit.effectiveStatisticsStartDate()
        val history = habit.normalizedGoalHistory()

        fun resolveGoal(date: LocalDate): HabitGoal {
            if (history.size == 1) return history[0]
            for (i in history.size - 1 downTo 0) {
                val g = history[i]
                if (!g.effectiveDate.isNewerThan(date)) return g
            }
            return history[0]
        }

        computedEntries.withLoadedEntries { entriesMap ->
            var previousValue = if (isNumerical && resolveGoal(from).targetType == NumericalHabitType.AT_MOST) 1.0 else 0.0
            var date = from
            while (!date.isNewerThan(to)) {
                if (statsStart != null && date.isOlderThan(statsStart)) {
                    map[date] = Score(date, 0.0)
                    previousValue = 0.0
                    date = date.plus(1)
                    continue
                }

                val goal = resolveGoal(date)
                val entryValue = entriesMap[date]?.value ?: Entry.UNKNOWN
                var numerator = goal.frequency.numerator
                var denominator = goal.frequency.denominator
                val freq = goal.frequency.toDouble()

                if (!isNumerical && freq < 1.0) {
                    numerator *= 2
                    denominator *= 2
                }

                if (entryValue != Entry.SKIP) {
                    val percentageCompleted = if (isNumerical) {
                        var rollingSum = 0
                        for (offset in 0 until denominator) {
                            val day = date.minus(offset)
                            if (statsStart == null || !day.isOlderThan(statsStart)) {
                                val v = entriesMap[day]?.value ?: Entry.UNKNOWN
                                if (v > 0) rollingSum += v
                            }
                        }
                        val normalizedRollingSum = rollingSum / 1000.0
                        if (goal.targetType == NumericalHabitType.AT_MOST) {
                            if (goal.targetValue > 0) {
                                (1 - ((normalizedRollingSum - goal.targetValue) / goal.targetValue))
                                    .coerceIn(0.0, 1.0)
                            } else {
                                if (normalizedRollingSum > 0) 0.0 else 1.0
                            }
                        } else {
                            if (goal.targetValue > 0) {
                                min(1.0, normalizedRollingSum / goal.targetValue)
                            } else {
                                1.0
                            }
                        }
                    } else {
                        var rollingSum = 0.0
                        for (offset in 0 until denominator) {
                            val day = date.minus(offset)
                            if ((statsStart == null || !day.isOlderThan(statsStart)) &&
                                entriesMap[day]?.value == Entry.YES_MANUAL
                            ) {
                                rollingSum += 1.0
                            }
                        }
                        min(1.0, rollingSum / numerator)
                    }
                    previousValue = compute(freq, previousValue, percentageCompleted)
                }

                map[date] = Score(date, previousValue)
                date = date.plus(1)
            }
        }
    }
}
