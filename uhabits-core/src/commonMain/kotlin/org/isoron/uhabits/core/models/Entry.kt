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

import org.isoron.platform.time.LocalDate
import kotlin.math.roundToInt

data class Entry(
    val date: LocalDate,
    val value: Int,
    val notes: String = ""
) {
    val formattedValue: String
        get() = when (value) {
            YES_MANUAL -> "YES_MANUAL"
            YES_AUTO -> "YES_AUTO"
            NO -> "NO"
            SKIP -> "SKIP"
            UNKNOWN -> "UNKNOWN"
            else -> value.toString()
        }

    companion object {
        /**
         * Value indicating that the habit is not applicable for this timestamp.
         */
        const val SKIP = 3

        /**
         * Value indicating that the user has performed the habit at this timestamp.
         */
        const val YES_MANUAL = 2

        /**
         * Value indicating that the user did not perform the habit, but they were not
         * expected to, because of the frequency of the habit.
         */
        const val YES_AUTO = 1

        /**
         * Value indicating that the user did not perform the habit, even though they were
         * expected to perform it.
         */
        const val NO = 0

        /**
         * Value indicating that no data is available for the given timestamp.
         */
        const val UNKNOWN = -1

        /**
         * Maximum user-facing numerical quantity representable as a 32-bit scaled integer.
         * Int.MAX_VALUE (2,147,483,647) / 1000.0 = 2,147,483.647.
         */
        const val MAX_NUMERICAL_VALUE: Double = Int.MAX_VALUE / 1000.0

        /**
         * Maximum scaled integer value stored in repetitions.value.
         */
        const val MAX_SCALED_VALUE: Int = Int.MAX_VALUE

        /**
         * Validates and converts a raw numerical habit value into its x1000 scaled integer representation.
         *
         * Rejects non-finite values (NaN, Inf), negative values (except UNKNOWN sentinel -0.001),
         * and out-of-range values (> 2,147,483.647) by throwing [IllegalArgumentException]
         * rather than silently clamping or wrapping around.
         */
        fun toScaledValue(value: Double): Int {
            if (!value.isFinite()) {
                throw IllegalArgumentException("Numerical value must be finite, was: $value")
            }
            if (value == UNKNOWN.toDouble() / 1000.0) return UNKNOWN
            if (value == SKIP.toDouble() / 1000.0) return SKIP
            if (value < 0.0) {
                throw IllegalArgumentException("Numerical value must be non-negative, was: $value")
            }
            val scaled = value * 1000.0
            if (scaled > MAX_SCALED_VALUE.toDouble()) {
                throw IllegalArgumentException(
                    "Numerical value exceeds maximum representable entry ($MAX_NUMERICAL_VALUE): $value"
                )
            }
            return scaled.roundToInt()
        }

        fun nextToggleValue(
            value: Int,
            isSkipEnabled: Boolean,
            areQuestionMarksEnabled: Boolean
        ): Int {
            return when (value) {
                YES_AUTO -> YES_MANUAL
                YES_MANUAL -> if (isSkipEnabled) SKIP else NO
                SKIP -> NO
                NO -> if (areQuestionMarksEnabled) UNKNOWN else YES_MANUAL
                UNKNOWN -> YES_MANUAL
                else -> YES_MANUAL
            }
        }
    }
}
