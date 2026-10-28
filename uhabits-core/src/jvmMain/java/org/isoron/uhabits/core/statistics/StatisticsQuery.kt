/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.statistics

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.ContainerId
import java.time.Instant
import java.time.ZoneId

/** Captured current organization membership. Focus additionally uses its frozen path. */
sealed class StatisticsSectionScope {
    data object All : StatisticsSectionScope()
    data object Unassigned : StatisticsSectionScope()
    data class Container(
        val id: ContainerId,
        val includeDescendants: Boolean = true,
        val currentDescendantIds: Set<ContainerId> = emptySet(),
    ) : StatisticsSectionScope()

    fun includesCurrent(id: ContainerId?): Boolean = when (this) {
        All -> true
        Unassigned -> id == null
        is Container -> id == this.id || (includeDescendants && id in currentDescendantIds)
    }
}

/** All dates in [from, to] are inclusive; timestamp selections are [startMillis, endMillis). */
data class StatisticsQueryContext(
    val from: LocalDate,
    val to: LocalDate,
    val timezoneId: String,
    val firstWeekday: DayOfWeek,
    val habitHourOffset: Int = 0,
    val habitMinuteOffset: Int = 0,
    val datasetId: String,
    val revision: Long,
    val now: Long,
    val sectionScope: StatisticsSectionScope = StatisticsSectionScope.All,
) {
    private val zone = ZoneId.of(timezoneId)

    init {
        require(from <= to) { "Reversed statistics period" }
        require(datasetId.isNotBlank()) { "Missing statistics dataset identity" }
        require(revision >= 0) { "Negative statistics revision" }
        require(now >= 0) { "Negative statistics clock" }
        require(habitHourOffset in 0..23 && habitMinuteOffset in 0..59) { "Invalid Habit day offset" }
    }

    val startMillis: Long get() = civilDayStart(from)
    val endMillis: Long get() = civilDayStart(to.plus(1))
    val today: LocalDate get() = civilDateAt(now)
    val habitToday: LocalDate get() = habitDateAt(now)

    fun containsDate(date: LocalDate): Boolean = date >= from && date <= to
    fun containsInstant(timestamp: Long): Boolean = timestamp >= startMillis && timestamp < endMillis

    fun civilDateAt(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toCoreDate()

    /** Local wall-clock offset, matching localDateAt even in a repeated DST hour. */
    fun habitDateAt(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone)
        .toLocalDateTime().minusHours(habitHourOffset.toLong()).minusMinutes(habitMinuteOffset.toLong())
        .toLocalDate().toCoreDate()

    fun civilDayStart(date: LocalDate): Long = date.toJavaDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** A missing boundary starts at the DST jump; an overlap uses the first occurrence. */
    fun habitDayStart(date: LocalDate): Long {
        val boundary = date.toJavaDate().atTime(habitHourOffset, habitMinuteOffset)
        val offsets = zone.rules.getValidOffsets(boundary)
        return if (offsets.isEmpty()) zone.rules.getTransition(boundary).instant.toEpochMilli()
        else boundary.atZone(zone).toInstant().toEpochMilli()
    }

    fun dates(): List<LocalDate> = buildList {
        var date = from
        while (date <= to) {
            add(date)
            date = date.plus(1)
        }
    }
}

private fun java.time.LocalDate.toCoreDate() = LocalDate(year, monthValue, dayOfMonth)
private fun LocalDate.toJavaDate() = java.time.LocalDate.of(year, month, day)

enum class StatisticsDataState { EMPTY, COMPLETE, PARTIAL, UNAVAILABLE }

enum class StatisticsIssueCode {
    INVALID_TIMESTAMP,
    CONFLICTING_DUPLICATE,
    INCOMPLETE_HISTORY,
    MISSING_HISTORICAL_PATH,
    INVALID_GOAL,
    INCOMPATIBLE_UNIT,
    UNSUPPORTED_FREQUENCY,
    MISSING_OBSERVATIONS,
    INVALID_OBSERVATION,
    OVERFLOW,
    OVERLAPPING_SESSIONS,
    READ_FAILED,
}

data class StatisticsIssue(
    val code: StatisticsIssueCode,
    val entityId: String? = null,
    val message: String,
)

data class StatisticsReadIdentity(val datasetId: String, val revision: Long)

class StatisticsReadInvalidatedException : IllegalStateException("Statistics read context is no longer current")
