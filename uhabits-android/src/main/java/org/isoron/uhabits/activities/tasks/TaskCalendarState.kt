/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.runtime.Immutable
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.localDateAt
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Immutable
data class TaskCalendarUiState(
    val colors: UHabitComposeColors,
    val wide: Boolean,
    val selectedDay: LocalDate,
    val today: LocalDate,
    val visibleYear: Int,
    val visibleMonth: Int,
    val scale: CalendarScale,
    val mode: CalendarMode,
    val showHabits: Boolean,
    val tasks: List<UserTask>,
    val habits: List<Habit>,
    val containers: Map<ContainerId, OrganizationalItem>,
    val taskTags: Map<TaskId, List<TaskTag>>,
    val focusSessions: List<FocusSession>,
    val nowMillis: Long = System.currentTimeMillis()
)

data class TaskCalendarMonthCell(
    val date: LocalDate?,
    val dayOfMonth: Int?,
    val isSelected: Boolean,
    val isToday: Boolean
)

data class SessionDetailSpec(
    val session: FocusSession,
    val title: String,
    val targetType: FocusPrimaryTargetType?,
    val isDstSession: Boolean,
    val startOffset: String,
    val endOffset: String,
    val sameLocalDay: Boolean,
    val hasPauses: Boolean,
    val elapsedMinutes: Long,
    val isEstimatedPartition: Boolean,
    val relatedTask: UserTask?,
    val relatedHabit: PlannedHabit?
)

object TaskCalendarStateHelper {

    fun buildWeekdayAbbreviations(locale: Locale): List<String> {
        return (1..7).map { day ->
            java.time.DayOfWeek.of(day)
                .getDisplayName(java.time.format.TextStyle.SHORT, locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
                .trimEnd('.')
        }
    }

    fun calendarFromLocalDate(date: LocalDate): Calendar {
        return Calendar.getInstance().apply {
            clear()
            set(date.year, date.month - 1, date.day)
        }
    }

    fun formatPeriodTitle(
        scale: CalendarScale,
        selectedDay: LocalDate,
        visibleYear: Int,
        visibleMonth: Int,
        locale: Locale
    ): String {
        return when (scale) {
            CalendarScale.MONTH -> {
                val cal = Calendar.getInstance().apply {
                    clear()
                    set(Calendar.YEAR, visibleYear)
                    set(Calendar.MONTH, visibleMonth - 1)
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                SimpleDateFormat("LLLL yyyy", locale)
                    .format(cal.time)
                    .replaceFirstChar { it.uppercase(locale) }
            }
            CalendarScale.WEEK -> {
                val start = selectedDay.startOfWeek(DayOfWeek.MONDAY)
                val end = start.plus(6)
                if (start.month == end.month) {
                    val monthName = SimpleDateFormat("LLL", locale)
                        .format(Date(calendarFromLocalDate(start).timeInMillis))
                    "${start.day}–${end.day} $monthName ${start.year}"
                } else {
                    val m1 = SimpleDateFormat("LLL", locale)
                        .format(Date(calendarFromLocalDate(start).timeInMillis))
                    val m2 = SimpleDateFormat("LLL", locale)
                        .format(Date(calendarFromLocalDate(end).timeInMillis))
                    "${start.day} $m1 – ${end.day} $m2 ${end.year}"
                }
            }
            CalendarScale.DAY -> {
                val cal = calendarFromLocalDate(selectedDay)
                SimpleDateFormat("EEEE, d MMMM", locale)
                    .format(cal.time)
                    .replaceFirstChar { it.uppercase(locale) }
            }
        }
    }

    fun formatDayHeaderTitle(day: LocalDate, locale: Locale): String {
        val cal = calendarFromLocalDate(day)
        return SimpleDateFormat("EEEE, d MMMM", locale)
            .format(cal.time)
            .replaceFirstChar { it.uppercase(locale) }
    }

    fun computePeriodNavigation(
        scale: CalendarScale,
        selectedDay: LocalDate,
        visibleYear: Int,
        visibleMonth: Int,
        delta: Int
    ): Triple<LocalDate, Int, Int> {
        return when (scale) {
            CalendarScale.MONTH -> {
                val cal = Calendar.getInstance().apply {
                    clear()
                    set(Calendar.YEAR, visibleYear)
                    set(Calendar.MONTH, visibleMonth - 1)
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, delta)
                }
                val newYear = cal.get(Calendar.YEAR)
                val newMonth = cal.get(Calendar.MONTH) + 1
                val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val targetDay = minOf(selectedDay.day, maxDay)
                Triple(LocalDate(newYear, newMonth, targetDay), newYear, newMonth)
            }
            CalendarScale.WEEK -> {
                val nextDay = if (delta > 0) selectedDay.plus(7) else selectedDay.minus(7)
                Triple(nextDay, nextDay.year, nextDay.month)
            }
            CalendarScale.DAY -> {
                val nextDay = if (delta > 0) selectedDay.plus(1) else selectedDay.minus(1)
                Triple(nextDay, nextDay.year, nextDay.month)
            }
        }
    }

    /**
     * Builds Monday-first calendar weeks for [visibleYear]/[visibleMonth].
     * Preserves exact weekday offset and week row count from legacy Month view.
     */
    fun buildMonthGridWeeks(
        year: Int,
        month: Int,
        selectedDay: LocalDate,
        today: LocalDate
    ): List<List<TaskCalendarMonthCell>> {
        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // In Calendar, SUNDAY=1, MONDAY=2... Monday-first offset: 0 for Mon, 6 for Sun.
        val offset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val totalCells = offset + daysInMonth
        val weeksCount = (totalCells + 6) / 7

        return (0 until weeksCount).map { week ->
            (0..6).map { col ->
                val dayNum = week * 7 + col - offset + 1
                if (dayNum in 1..daysInMonth) {
                    val date = LocalDate(year, month, dayNum)
                    TaskCalendarMonthCell(
                        date = date,
                        dayOfMonth = dayNum,
                        isSelected = date == selectedDay,
                        isToday = date == today
                    )
                } else {
                    TaskCalendarMonthCell(
                        date = null,
                        dayOfMonth = null,
                        isSelected = false,
                        isToday = false
                    )
                }
            }
        }
    }

    /**
     * Groups focus history segments into temporally overlapping clusters.
     * Segments with segmentStartMs < current cluster max segmentEndMs belong to the same cluster.
     */
    fun groupSegmentsIntoClusters(segments: List<FocusHistorySegment>): List<List<FocusHistorySegment>> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedWith(compareBy({ it.segmentStartMs }, { it.segmentEndMs }))
        val clusters = mutableListOf<List<FocusHistorySegment>>()
        var curCluster = mutableListOf<FocusHistorySegment>()
        var curClusterEnd = Long.MIN_VALUE

        for (seg in sorted) {
            if (curCluster.isEmpty()) {
                curCluster.add(seg)
                curClusterEnd = seg.segmentEndMs
            } else if (seg.segmentStartMs < curClusterEnd) {
                curCluster.add(seg)
                curClusterEnd = maxOf(curClusterEnd, seg.segmentEndMs)
            } else {
                clusters.add(curCluster)
                curCluster = mutableListOf(seg)
                curClusterEnd = seg.segmentEndMs
            }
        }
        if (curCluster.isNotEmpty()) clusters.add(curCluster)
        return clusters
    }

    /**
     * Computes the maximum stacked item count per hour (0..23) across [weekDays]
     * so every day column and the time axis share uniform dynamic row heights.
     */
    fun computePlanningWeekHourCounts(
        weekDays: List<LocalDate>,
        taskEvents: List<TaskCalendarEvent>,
        weekPlannedHabits: List<PlannedHabit>
    ): List<Int> {
        return (0..23).map { hour ->
            weekDays.maxOfOrNull { day ->
                taskEvents.count { it.isTimed && it.day == day && it.hour == hour } +
                    weekPlannedHabits.count { !it.isAllDay && it.date == day && it.reminderHour == hour }
            } ?: 0
        }
    }

    fun resolveSessionTargetType(session: FocusSession): FocusPrimaryTargetType? {
        return session.primaryTargetType ?: when {
            session.taskId != null -> FocusPrimaryTargetType.TASK
            session.habit != null -> FocusPrimaryTargetType.HABIT
            session.containerId != null -> FocusPrimaryTargetType.CONTAINER
            else -> null
        }
    }

    fun buildSessionDetailSpec(
        session: FocusSession,
        selectedDay: LocalDate,
        tasks: List<UserTask>,
        habits: List<Habit>,
        containers: Map<ContainerId, OrganizationalItem>,
        defaultTaskLabel: String,
        defaultHabitLabel: String,
        defaultContainerLabel: String,
        defaultFocusLabel: String
    ): SessionDetailSpec {
        val tasksById = tasks.associateBy { it.id }
        val habitsByUuid = habits.associateBy { it.uuid.orEmpty() }
        val title = FocusHistoryProjector.resolveTitle(
            session = session,
            tasksMap = tasksById,
            habitsMap = habitsByUuid,
            containersMap = containers,
            defaultTaskLabel = defaultTaskLabel,
            defaultHabitLabel = defaultHabitLabel,
            defaultContainerLabel = defaultContainerLabel,
            defaultFocusLabel = defaultFocusLabel
        )

        val startDay = localDateAt(session.startedAt, 0, 0)
        val endDay = localDateAt(session.endedAt, 0, 0)
        val startOffset = FocusHistoryProjector.formatZoneOffset(session.startedAt)
        val endOffset = FocusHistoryProjector.formatZoneOffset(session.endedAt)
        val isDstSession = FocusHistoryProjector.getDayLengthHours(startDay) != 24 ||
            FocusHistoryProjector.getDayLengthHours(endDay) != 24 ||
            startOffset != endOffset

        val crossesMidnight = startDay != endDay
        val hasPauses = session.endedAt - session.startedAt > session.durationMs
        val elapsedMinutes = (session.endedAt - session.startedAt) / 60_000L

        val relatedTask = session.taskId?.let { tasksById[it] }
        val relatedHabit = if (relatedTask == null && session.habit != null) {
            habitsByUuid[session.habit?.uuid.orEmpty()]?.let { habit ->
                PlannedHabit(
                    habitId = habit.id ?: 0L,
                    habitUuid = habit.uuid,
                    name = habit.name,
                    color = habit.color,
                    date = selectedDay
                )
            }
        } else {
            null
        }

        return SessionDetailSpec(
            session = session,
            title = title,
            targetType = session.primaryTargetType,
            isDstSession = isDstSession,
            startOffset = startOffset,
            endOffset = endOffset,
            sameLocalDay = startDay == endDay,
            hasPauses = hasPauses,
            elapsedMinutes = elapsedMinutes,
            isEstimatedPartition = crossesMidnight && hasPauses,
            relatedTask = relatedTask,
            relatedHabit = relatedHabit
        )
    }

    fun buildDstHourLabels(day: LocalDate, dayHours: Int): List<String> {
        val dayStartMs = startOfLocalDay(day, 0, 0)
        return (0 until dayHours).map { h ->
            if (dayHours == 24) {
                String.format(Locale.getDefault(), "%02d:00", h)
            } else {
                val slotStartMs = dayStartMs + h * 3_600_000L
                val cal = Calendar.getInstance().apply { timeInMillis = slotStartMs }
                val localH = cal.get(Calendar.HOUR_OF_DAY)
                val time = String.format(Locale.getDefault(), "%02d:00", localH)
                val offset = FocusHistoryProjector.formatZoneOffset(slotStartMs)
                val nextCal = if (h + 1 < dayHours) Calendar.getInstance().apply { timeInMillis = slotStartMs + 3_600_000L } else null
                val prevCal = if (h > 0) Calendar.getInstance().apply { timeInMillis = slotStartMs - 3_600_000L } else null
                val isRepeatingHour = (nextCal != null && nextCal.get(Calendar.HOUR_OF_DAY) == localH) ||
                    (prevCal != null && prevCal.get(Calendar.HOUR_OF_DAY) == localH)
                if (isRepeatingHour || (dayHours == 25 && (h == 2 || h == 3))) {
                    "$time\nUTC$offset"
                } else {
                    time
                }
            }
        }
    }
}
