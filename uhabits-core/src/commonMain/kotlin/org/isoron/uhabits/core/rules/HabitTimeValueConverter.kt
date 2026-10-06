/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.core.rules

import org.isoron.uhabits.core.models.Habit

class IncompatibleTargetHabitException(
    message: String,
    val habitUuid: String? = null,
) : IllegalArgumentException(message)

object HabitTimeValueConverter {

    // Explicit time labels only. Bare "m" may mean metres in a user-defined unit.
    private val minuteUnits = setOf("min", "mins", "minute", "minutes", "min.", "мин", "минута", "минуты", "минут", "мин.")

    /**
     * Converts a duration in milliseconds to Habit-native numerical value (x1000 scale) for minute-based habits.
     *
     * In Loop Habit Tracker, numerical habit values are scaled by 1000 (i.e. 1.0 unit = 1000L).
     * With rounding to nearest minute: (durationMs * 1000L + 30_000L) / 60_000L.
     * E.g. 42 minutes = 2,520,000 ms -> (2,520,000 * 1000 + 30_000) / 60_000 = 42,000L.
     */
    fun convertFocusDurationToHabitValue(durationMs: Long): Long {
        require(durationMs >= 0) { "durationMs must be nonnegative: $durationMs" }
        return (durationMs * 1000L + 30_000L) / 60_000L
    }

    /**
     * Validates that the target Habit is compatible with focus duration attribution.
     * Target must be a numerical habit with a recognized minute unit (e.g. min, mins, minutes, мин).
     */
    fun validateTargetHabit(habit: Habit) {
        validateTargetHabit(
            isNumerical = habit.isNumerical,
            unit = habit.unit,
            name = habit.name,
            uuid = habit.uuid,
        )
    }

    /**
     * Validates raw attributes of a target habit.
     */
    fun validateTargetHabit(
        isNumerical: Boolean,
        unit: String,
        name: String? = null,
        uuid: String? = null,
    ) {
        if (!isNumerical) {
            val label = name ?: uuid ?: "unknown"
            throw IncompatibleTargetHabitException(
                "Target habit '$label' is not numerical; only numerical habits support focus duration attribution",
                uuid,
            )
        }
        if (unit.trim().lowercase() !in minuteUnits) {
            val label = name ?: uuid ?: "unknown"
            throw IncompatibleTargetHabitException(
                "Target habit '$label' has unit '$unit' which is not a minute-based time unit",
                uuid,
            )
        }
    }
}
