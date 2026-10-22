/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due

import org.isoron.platform.time.LocalDate

/** Canonical Due domain: NONE, DAY, TIMED. */
sealed class TaskDue {
    object None : TaskDue() {
        override fun toString(): String = "None"
    }

    data class Day(val date: TaskDueDate) : TaskDue()

    class Timed private constructor(val epochMillis: Long, val originZoneId: String?) : TaskDue() {
        override fun equals(other: Any?): Boolean = other is Timed &&
            epochMillis == other.epochMillis && originZoneId == other.originZoneId
        override fun hashCode(): Int = 31 * epochMillis.hashCode() + (originZoneId?.hashCode() ?: 0)
        override fun toString(): String = "Timed(epochMillis=$epochMillis, originZoneId=$originZoneId)"

        companion object {
            fun of(epochMillis: Long, originZoneId: String? = null): Timed = Timed(epochMillis, originZoneId)

            /** Legacy sources preserve the instant without inventing an origin timezone. */
            fun legacy(epochMillis: Long): Timed = Timed(epochMillis, null)

            /** Carries an already resolved instant; local-clock DST selection belongs at the input boundary. */
            fun local(epochMillis: Long, originZoneId: String): Timed {
                require(Regex("[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+|UTC").matches(originZoneId)) {
                    "Invalid origin timezone identifier shape"
                }
                require(isKnownTaskDueZone(originZoneId)) { "Unknown origin timezone" }
                return Timed(epochMillis, originZoneId)
            }
        }
    }
}

/** Calendar-only value: strict Gregorian date, no timestamp or implicit timezone. */
class TaskDueDate private constructor(val isoDate: String) : Comparable<TaskDueDate> {
    override fun equals(other: Any?): Boolean = other is TaskDueDate && isoDate == other.isoDate
    override fun hashCode(): Int = isoDate.hashCode()
    override fun toString(): String = isoDate
    override fun compareTo(other: TaskDueDate): Int = isoDate.compareTo(other.isoDate)

    fun toLocalDate(): LocalDate {
        val y = isoDate.substring(0, 4).toInt()
        val m = isoDate.substring(5, 7).toInt()
        val d = isoDate.substring(8, 10).toInt()
        return LocalDate(y, m, d)
    }

    companion object {
        fun parse(raw: String): TaskDueDate {
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw)) { "Invalid Due date syntax" }
            val year = raw.substring(0, 4).toInt()
            val month = raw.substring(5, 7).toInt()
            val day = raw.substring(8, 10).toInt()
            require(year in 1..9999 && month in 1..12) { "Invalid Due calendar date" }
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val days = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            require(day in 1..days) { "Invalid Due calendar date" }
            return TaskDueDate(raw)
        }

        fun from(date: LocalDate): TaskDueDate {
            val y = date.year.toString().padStart(4, '0')
            val m = date.month.toString().padStart(2, '0')
            val d = date.day.toString().padStart(2, '0')
            return parse("$y-$m-$d")
        }
    }
}

fun LocalDate.toTaskDueDate(): TaskDueDate = TaskDueDate.from(this)

/** null patch means absent; explicit None means clear. */
fun applyTaskDuePatch(existing: TaskDue, patch: TaskDue?): TaskDue = patch ?: existing

internal expect fun isKnownTaskDueZone(zoneId: String): Boolean
