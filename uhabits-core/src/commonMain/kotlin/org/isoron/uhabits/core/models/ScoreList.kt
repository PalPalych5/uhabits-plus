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
        var previousValue = if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_MOST) 1.0 else 0.0
        var date = from
        while (!date.isNewerThan(to)) {
            val goal = habit.goalAt(date)
            val entry = computedEntries.get(date)
            if (!habit.isDateIncludedInStatistics(date)) {
                map[date] = Score(date, 0.0)
                previousValue = 0.0
                date = date.plus(1)
                continue
            }

            var numerator = goal.frequency.numerator
            var denominator = goal.frequency.denominator
            val freq = goal.frequency.toDouble()

            if (!habit.isNumerical && freq < 1.0) {
                numerator *= 2
                denominator *= 2
            }

            if (entry.value != Entry.SKIP) {
                val percentageCompleted = if (habit.isNumerical) {
                    val rollingSum = sumNumericalWindow(computedEntries, date, denominator)
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
                    val rollingSum = sumBooleanWindow(computedEntries, date, denominator)
                    min(1.0, rollingSum / numerator)
                }
                previousValue = compute(freq, previousValue, percentageCompleted)
            }

            map[date] = Score(date, previousValue)
            date = date.plus(1)
        }
    }

    private fun sumNumericalWindow(computedEntries: EntryList, date: LocalDate, denominator: Int): Int {
        var sum = 0
        for (offset in 0 until denominator) {
            sum += max(0, computedEntries.get(date.minus(offset)).value)
        }
        return sum
    }

    private fun sumBooleanWindow(computedEntries: EntryList, date: LocalDate, denominator: Int): Double {
        var sum = 0.0
        for (offset in 0 until denominator) {
            if (computedEntries.get(date.minus(offset)).value == Entry.YES_MANUAL) {
                sum += 1.0
            }
        }
        return sum
    }
}
