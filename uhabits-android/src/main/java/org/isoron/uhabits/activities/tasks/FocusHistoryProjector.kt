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

package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.containers.statistics.HistoricalPathParser
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FocusHistorySegment(
    val session: FocusSession,
    val day: LocalDate,
    val segmentStartMs: Long,
    val segmentEndMs: Long,
    val segmentDurationMs: Long,
    val segmentElapsedMs: Long,
    val isContinuationFromPrevDay: Boolean,
    val continuesToNextDay: Boolean,
    val laneIndex: Int = 0,
    val totalLanes: Int = 1
) {
    /**
     * Explicit computed property identifying estimated day allocation.
     * True only when the session crosses midnight AND active durationMs differs from elapsed interval.
     * Retains original session durationMs on [session].
     */
    val isDayAllocationEstimated: Boolean
        get() = (isContinuationFromPrevDay || continuesToNextDay) &&
                session.durationMs != (session.endedAt - session.startedAt)

}

data class DayHistorySummary(
    val day: LocalDate,
    val segments: List<FocusHistorySegment>,
    val totalWorkDurationMs: Long,
    val mergedBusyDurationMs: Long,
    val hasOverlaps: Boolean,
    val sessionCount: Int
) {
    val hasEstimatedDayAllocation: Boolean
        get() = segments.any { it.isDayAllocationEstimated }
}

object FocusHistoryProjector {

    /**
     * Projects a finalized [FocusSession] onto local calendar dates.
     *
     * Invariants:
     * - Only valid intervals (startedAt >= 0, endedAt > startedAt, durationMs > 0) are accepted.
     * - Sessions crossing midnight are partitioned into day-bounded segments referencing the same sessionId.
     * - Segment durations proportionally partition the session's active work time so that
     *   the sum of segment durations exactly equals session.durationMs.
     *   For paused sessions this is only an estimated day allocation: finalized facts
     *   contain no pause boundaries, so conservation does not prove daily activity.
     * - Local midnight boundaries rely on [startOfLocalDay] which handles time zones and DST transitions.
     */
    fun projectSessionToDays(session: FocusSession): List<FocusHistorySegment> {
        if (session.startedAt < 0 || session.endedAt <= session.startedAt || session.durationMs <= 0) {
            return emptyList()
        }
        val totalElapsed = session.endedAt - session.startedAt
        if (session.durationMs > totalElapsed) {
            return emptyList()
        }

        val rawSegments = mutableListOf<FocusHistorySegment>()
        var curDate = localDateAt(session.startedAt, 0, 0)

        while (true) {
            val dayStartMs = startOfLocalDay(curDate, 0, 0)
            val dayEndMs = startOfLocalDay(curDate.plus(1), 0, 0)

            val segStartMs = maxOf(session.startedAt, dayStartMs)
            val segEndMs = minOf(session.endedAt, dayEndMs)

            if (segEndMs > segStartMs) {
                val segElapsed = segEndMs - segStartMs
                val isPrev = segStartMs == dayStartMs && session.startedAt < dayStartMs
                val isNext = segEndMs == dayEndMs && session.endedAt > dayEndMs
                rawSegments.add(
                    FocusHistorySegment(
                        session = session,
                        day = curDate,
                        segmentStartMs = segStartMs,
                        segmentEndMs = segEndMs,
                        segmentDurationMs = 0L,
                        segmentElapsedMs = segElapsed,
                        isContinuationFromPrevDay = isPrev,
                        continuesToNextDay = isNext
                    )
                )
            }

            if (dayEndMs >= session.endedAt) break
            curDate = curDate.plus(1)
        }

        if (rawSegments.isEmpty()) return emptyList()

        if (rawSegments.size == 1) {
            return listOf(rawSegments[0].copy(segmentDurationMs = session.durationMs))
        }

        val finalSegments = ArrayList<FocusHistorySegment>(rawSegments.size)
        var allocated = 0L
        for (i in 0 until rawSegments.size - 1) {
            val seg = rawSegments[i]
            val segDur = (session.durationMs * seg.segmentElapsedMs) / totalElapsed
            allocated += segDur
            finalSegments.add(seg.copy(segmentDurationMs = segDur))
        }
        val lastSeg = rawSegments.last()
        finalSegments.add(lastSeg.copy(segmentDurationMs = session.durationMs - allocated))

        return finalSegments
    }

    /**
     * Assigns side-by-side lanes for overlapping segments on a single day.
     * Non-overlapping segments receive laneIndex = 0 and totalLanes = 1.
     */
    fun assignLanes(segments: List<FocusHistorySegment>): List<FocusHistorySegment> {
        if (segments.size <= 1) return segments

        val sorted = segments.sortedWith(compareBy({ it.segmentStartMs }, { it.segmentEndMs }))

        // 1. Group segments into clusters of temporally overlapping intervals
        val clusters = mutableListOf<MutableList<FocusHistorySegment>>()
        var curCluster = mutableListOf<FocusHistorySegment>()
        var curClusterEnd = Long.MIN_VALUE

        for (seg in sorted) {
            if (curCluster.isEmpty()) {
                curCluster.add(seg)
                curClusterEnd = seg.segmentEndMs
            } else {
                if (seg.segmentStartMs < curClusterEnd) {
                    curCluster.add(seg)
                    curClusterEnd = maxOf(curClusterEnd, seg.segmentEndMs)
                } else {
                    clusters.add(curCluster)
                    curCluster = mutableListOf(seg)
                    curClusterEnd = seg.segmentEndMs
                }
            }
        }
        if (curCluster.isNotEmpty()) clusters.add(curCluster)

        // 2. Assign greedy lanes within each cluster
        val result = mutableListOf<FocusHistorySegment>()
        for (cluster in clusters) {
            val laneEndTimes = mutableListOf<Long>()
            val clusterWithLanes = mutableListOf<Pair<FocusHistorySegment, Int>>()
            for (seg in cluster) {
                var placed = false
                for (l in laneEndTimes.indices) {
                    if (laneEndTimes[l] <= seg.segmentStartMs) {
                        laneEndTimes[l] = seg.segmentEndMs
                        clusterWithLanes.add(seg to l)
                        placed = true
                        break
                    }
                }
                if (!placed) {
                    clusterWithLanes.add(seg to laneEndTimes.size)
                    laneEndTimes.add(seg.segmentEndMs)
                }
            }
            val totalLanes = laneEndTimes.size
            for ((seg, lane) in clusterWithLanes) {
                result.add(seg.copy(laneIndex = lane, totalLanes = totalLanes))
            }
        }
        return result
    }

    /**
     * Computes the real merged busy interval duration (union of all intervals).
     */
    fun calculateMergedBusyTime(segments: List<FocusHistorySegment>): Long {
        if (segments.isEmpty()) return 0L
        val sorted = segments.sortedBy { it.segmentStartMs }
        var mergedTime = 0L
        var curStart = sorted[0].segmentStartMs
        var curEnd = sorted[0].segmentEndMs

        for (i in 1 until sorted.size) {
            val seg = sorted[i]
            if (seg.segmentStartMs < curEnd) {
                curEnd = maxOf(curEnd, seg.segmentEndMs)
            } else {
                mergedTime += (curEnd - curStart)
                curStart = seg.segmentStartMs
                curEnd = seg.segmentEndMs
            }
        }
        mergedTime += (curEnd - curStart)
        return mergedTime
    }

    /**
     * Builds summary metrics for a given date from recorded sessions.
     */
    fun buildSummaryForDay(
        sessions: List<FocusSession>,
        day: LocalDate
    ): DayHistorySummary {
        val daySegments = mutableListOf<FocusHistorySegment>()
        for (session in sessions) {
            val projected = projectSessionToDays(session)
            for (seg in projected) {
                if (seg.day == day) daySegments.add(seg)
            }
        }

        val totalWorkDurationMs = daySegments.sumOf { it.segmentDurationMs }
        val mergedBusyDurationMs = calculateMergedBusyTime(daySegments)
        val distinctSessions = daySegments.map { it.session.id }.distinct().size
        val lanedSegments = assignLanes(daySegments)
        val hasOverlaps = lanedSegments.any { it.totalLanes > 1 }

        return DayHistorySummary(
            day = day,
            segments = lanedSegments,
            totalWorkDurationMs = totalWorkDurationMs,
            mergedBusyDurationMs = mergedBusyDurationMs,
            hasOverlaps = hasOverlaps,
            sessionCount = distinctSessions
        )
    }

    /**
     * Resolves the display title for a focus session based on its primary target.
     */
    fun resolveTitle(
        session: FocusSession,
        tasksMap: Map<TaskId, UserTask>,
        habitsMap: Map<String, Habit>,
        containersMap: Map<ContainerId, OrganizationalItem>
    ): String {
        return when (session.primaryTargetType) {
            FocusPrimaryTargetType.TASK -> {
                session.taskId?.let { tasksMap[it]?.title } ?: "Задача"
            }
            FocusPrimaryTargetType.HABIT -> {
                session.habit?.let { habitsMap[it.uuid]?.name } ?: "Привычка"
            }
            FocusPrimaryTargetType.CONTAINER -> {
                session.containerId?.let { containersMap[it]?.name } ?: "Раздел"
            }
            null -> {
                session.taskId?.let { tasksMap[it]?.title }
                    ?: session.habit?.let { habitsMap[it.uuid]?.name }
                    ?: session.containerId?.let { containersMap[it]?.name }
                    ?: "Сессия фокуса"
            }
        }
    }

    /**
     * Computes the epoch timestamp range for SQLite querying given calendar scale and navigation date.
     * Expands the range by 1 day on each side to ensure sessions crossing midnight are captured.
     */
    fun calculateQueryRange(
        scale: CalendarScale,
        selectedDay: LocalDate,
        calendarMonth: Calendar
    ): Pair<Long, Long> {
        return when (scale) {
            CalendarScale.MONTH -> {
                val year = calendarMonth.get(Calendar.YEAR)
                val month = calendarMonth.get(Calendar.MONTH) + 1
                val daysInMonth = calendarMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
                val start = LocalDate(year, month, 1)
                val end = LocalDate(year, month, daysInMonth)
                val startTs = startOfLocalDay(start.minus(1), 0, 0)
                val endTs = startOfLocalDay(end.plus(2), 0, 0)
                startTs to endTs
            }
            CalendarScale.WEEK -> {
                val start = selectedDay.startOfWeek(DayOfWeek.MONDAY)
                val end = start.plus(6)
                val startTs = startOfLocalDay(start.minus(1), 0, 0)
                val endTs = startOfLocalDay(end.plus(2), 0, 0)
                startTs to endTs
            }
            CalendarScale.DAY -> {
                val startTs = startOfLocalDay(selectedDay.minus(1), 0, 0)
                val endTs = startOfLocalDay(selectedDay.plus(2), 0, 0)
                startTs to endTs
            }
        }
    }

    /**
     * Filters focus sessions by container using historical frozen organizationContext.
     */
    fun filterByContainer(
        sessions: List<FocusSession>,
        targetContainer: ContainerId?,
        currentSubtreeIds: Set<ContainerId>,
        containerQueries: ContainerQueries? = null
    ): List<FocusSession> {
        if (targetContainer == null) return sessions
        return sessions.filter { session ->
            HistoricalPathParser.matchSession(
                session = session,
                targetContainer = targetContainer,
                currentSubtreeIds = currentSubtreeIds,
                containerQueries = containerQueries
            ) != null
        }
    }

    /**
     * Returns the actual length of the day in milliseconds (difference between next local midnight and start of day).
     * Accurately accounts for DST transitions (23h on spring forward, 25h on autumn fall back).
     */
    fun getDayLengthMs(day: LocalDate): Long {
        val startMs = startOfLocalDay(day, 0, 0)
        val endMs = startOfLocalDay(day.plus(1), 0, 0)
        return endMs - startMs
    }

    /**
     * Returns the total hours in the local day (e.g. 23 on spring forward, 24 ordinary, 25 on autumn fall back).
     */
    fun getDayLengthHours(day: LocalDate): Int {
        return (getDayLengthMs(day) / 3_600_000L).toInt()
    }

    /**
     * Computes elapsed minutes from local midnight as a pure float:
     * (timestamp - startOfLocalDay) / 60000f.
     * Respects isStartMidnight and isEndMidnight boundaries.
     * During a 25-hour day, minutes range from 0f to 1500f.
     */
    fun calculateElapsedMinutesFromMidnight(
        day: LocalDate,
        timeMs: Long,
        isStartMidnight: Boolean = false,
        isEndMidnight: Boolean = false
    ): Float {
        if (isStartMidnight) return 0f
        val startMs = startOfLocalDay(day, 0, 0)
        val endMs = startOfLocalDay(day.plus(1), 0, 0)
        if (isEndMidnight) return (endMs - startMs) / 60_000f
        val clampedMs = timeMs.coerceIn(startMs, endMs)
        return (clampedMs - startMs) / 60_000f
    }

    /**
     * Formats the short zone offset string (e.g. "+02" or "+01") for a given timestamp.
     */
    fun formatZoneOffset(timeMs: Long, zoneId: ZoneId = TimeZone.getDefault().toZoneId()): String {
        val offset = zoneId.rules.getOffset(Instant.ofEpochMilli(timeMs))
        val totalSeconds = offset.totalSeconds
        val hours = totalSeconds / 3600
        val minutes = Math.abs((totalSeconds % 3600) / 60)
        val sign = if (totalSeconds >= 0) "+" else "-"
        return if (minutes == 0) {
            String.format(Locale.US, "%s%02d", sign, Math.abs(hours))
        } else {
            String.format(Locale.US, "%s%02d:%02d", sign, Math.abs(hours), minutes)
        }
    }

    /**
     * Formats interval text with time zone offset (e.g. "02:15 (+02) – 02:45 (+02)" or "02:15 – 02:45").
     * When [includeZoneOffset] is null, offset is included only if the day length != 24 hours (DST transition).
     */
    fun formatSegmentIntervalText(
        seg: FocusHistorySegment,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = TimeZone.getDefault().toZoneId(),
        includeZoneOffset: Boolean? = null
    ): String {
        val shouldIncludeOffset = includeZoneOffset ?: (getDayLengthHours(seg.day) != 24)
        val timeFormat = SimpleDateFormat("HH:mm", locale).apply {
            timeZone = TimeZone.getTimeZone(zoneId)
        }
        val startStr = if (seg.isContinuationFromPrevDay) {
            "00:00"
        } else {
            val base = timeFormat.format(Date(seg.segmentStartMs))
            if (shouldIncludeOffset) "$base (${formatZoneOffset(seg.segmentStartMs, zoneId)})" else base
        }
        val endStr = if (seg.continuesToNextDay) {
            "24:00"
        } else {
            val base = timeFormat.format(Date(seg.segmentEndMs))
            if (shouldIncludeOffset) "$base (${formatZoneOffset(seg.segmentEndMs, zoneId)})" else base
        }
        return "$startStr – $endStr"
    }
}
