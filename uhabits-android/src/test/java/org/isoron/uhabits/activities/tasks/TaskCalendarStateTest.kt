/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TaskCalendarStateTest {

    @Test
    fun monthGridBuildsMondayFirstWeeksWithLeadingAndTrailingNulls() {
        // October 2026 starts on Thursday (offset 3: Mon, Tue, Wed are null) and has 31 days -> 5 weeks
        val selected = LocalDate(2026, 10, 24)
        val today = LocalDate(2026, 10, 10)
        val weeks = TaskCalendarStateHelper.buildMonthGridWeeks(
            year = 2026,
            month = 10,
            selectedDay = selected,
            today = today
        )

        assertEquals(5, weeks.size)
        assertNull(weeks[0][0].date)
        assertNull(weeks[0][1].date)
        assertNull(weeks[0][2].date)
        assertEquals(LocalDate(2026, 10, 1), weeks[0][3].date)
        assertEquals(1, weeks[0][3].dayOfMonth)
        assertEquals(LocalDate(2026, 10, 31), weeks[4][5].date)
        assertNull(weeks[4][6].date)

        val selectedCell = weeks.flatten().first { it.isSelected }
        assertEquals(selected, selectedCell.date)
        val todayCell = weeks.flatten().first { it.isToday }
        assertEquals(today, todayCell.date)
    }

    @Test
    fun monthNavigationClampsDayAtShorterMonthBoundary() {
        val jan31 = LocalDate(2026, 1, 31)
        val (nextDate, nextYear, nextMonth) = TaskCalendarStateHelper.computePeriodNavigation(
            scale = CalendarScale.MONTH,
            selectedDay = jan31,
            visibleYear = 2026,
            visibleMonth = 1,
            delta = 1
        )
        assertEquals(LocalDate(2026, 2, 28), nextDate)
        assertEquals(2026, nextYear)
        assertEquals(2, nextMonth)
    }

    @Test
    fun periodTitleMatchesScaleFormats() {
        val day = LocalDate(2026, 10, 24)
        val monthTitle = TaskCalendarStateHelper.formatPeriodTitle(
            scale = CalendarScale.MONTH,
            selectedDay = day,
            visibleYear = 2026,
            visibleMonth = 10,
            locale = Locale.US
        )
        assertTrue(monthTitle.contains("2026"))
        assertTrue(monthTitle.startsWith("October"))

        val weekTitle = TaskCalendarStateHelper.formatPeriodTitle(
            scale = CalendarScale.WEEK,
            selectedDay = day,
            visibleYear = 2026,
            visibleMonth = 10,
            locale = Locale.US
        )
        assertTrue(weekTitle.contains("19–25"))

        val dayTitle = TaskCalendarStateHelper.formatPeriodTitle(
            scale = CalendarScale.DAY,
            selectedDay = day,
            visibleYear = 2026,
            visibleMonth = 10,
            locale = Locale.US
        )
        assertTrue(dayTitle.contains("24"))
    }

    @Test
    fun clusterGroupingSeparatesNonOverlappingAndGroupsOverlaps() {
        val day = LocalDate(2026, 10, 26)
        val dayStart = startOfLocalDay(day, 0, 0)
        fun seg(id: String, startHour: Int, endHour: Int): FocusHistorySegment {
            val s = dayStart + startHour * 3_600_000L
            val e = dayStart + endHour * 3_600_000L
            val session = FocusSession(
                id = FocusSessionId(id),
                startedAt = s,
                endedAt = e,
                durationMs = e - s,
                source = FocusSessionSource.TIMER,
                createdAt = s,
                updatedAt = e
            )
            return FocusHistoryProjector.projectSessionToDays(session).single()
        }

        val laned = FocusHistoryProjector.assignLanes(
            listOf(
                seg("s1", 9, 11),
                seg("s2", 10, 12),
                seg("s3", 10, 11),
                seg("s4", 10, 12),
                seg("s5", 14, 15)
            )
        )
        val clusters = TaskCalendarStateHelper.groupSegmentsIntoClusters(laned)
        assertEquals(2, clusters.size)
        assertEquals(4, clusters[0].size)
        assertEquals(1, clusters[1].size)
    }

    @Test
    fun sessionDetailSpecDetectsDstPausesAndDrilldownTargets() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val fallDay = LocalDate(2026, 10, 25)
            val from = OffsetDateTime.parse("2026-10-25T02:15:00+02:00").toInstant().toEpochMilli()
            val to = OffsetDateTime.parse("2026-10-25T02:45:00+01:00").toInstant().toEpochMilli()
            val task = UserTask(TaskId("t1"), "Review PR", createdAt = 0L, updatedAt = 0L)
            val session = FocusSession(
                id = FocusSessionId("dst_s1"),
                startedAt = from,
                endedAt = to,
                durationMs = 45 * 60_000L, // paused inside 90m elapsed
                taskId = task.id,
                primaryTargetType = FocusPrimaryTargetType.TASK,
                source = FocusSessionSource.TIMER,
                createdAt = from,
                updatedAt = to
            )

            val spec = TaskCalendarStateHelper.buildSessionDetailSpec(
                session = session,
                selectedDay = fallDay,
                tasks = listOf(task),
                habits = emptyList(),
                containers = emptyMap(),
                defaultTaskLabel = "Task",
                defaultHabitLabel = "Habit",
                defaultContainerLabel = "Container",
                defaultFocusLabel = "Focus"
            )

            assertEquals("Review PR", spec.title)
            assertTrue(spec.isDstSession)
            assertEquals("+02", spec.startOffset)
            assertEquals("+01", spec.endOffset)
            assertTrue(spec.hasPauses)
            assertFalse(spec.isEstimatedPartition)
            assertEquals(task, spec.relatedTask)
            assertNull(spec.relatedHabit)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun sessionDetailSpecResolvesHabitDrilldownWhenNoTask() {
        val factory = MemoryModelFactory()
        val habit = factory.buildHabit().apply {
            id = 42L
            uuid = "hab-uuid-42"
            name = "Meditate"
            color = PaletteColor(3)
            frequency = Frequency.DAILY
        }
        val day = LocalDate(2026, 10, 24)
        val dayStart = startOfLocalDay(day, 0, 0)
        val session = FocusSession(
            id = FocusSessionId("hs1"),
            startedAt = dayStart + 8 * 3_600_000L,
            endedAt = dayStart + 9 * 3_600_000L,
            durationMs = 60 * 60_000L,
            habit = org.isoron.uhabits.core.containers.HabitRef("hab-uuid-42"),
            primaryTargetType = FocusPrimaryTargetType.HABIT,
            source = FocusSessionSource.MANUAL,
            createdAt = dayStart,
            updatedAt = dayStart
        )

        val spec = TaskCalendarStateHelper.buildSessionDetailSpec(
            session = session,
            selectedDay = day,
            tasks = emptyList(),
            habits = listOf(habit),
            containers = emptyMap(),
            defaultTaskLabel = "Task",
            defaultHabitLabel = "Habit",
            defaultContainerLabel = "Container",
            defaultFocusLabel = "Focus"
        )

        assertEquals("Meditate", spec.title)
        assertNull(spec.relatedTask)
        assertNotNull(spec.relatedHabit)
        assertEquals(42L, spec.relatedHabit?.habitId)
        assertEquals("hab-uuid-42", spec.relatedHabit?.habitUuid)
    }

    @Test
    fun planningWeekHourCountsScaleAcrossDaysAndHabits() {
        val mon = LocalDate(2026, 10, 19)
        val weekDays = (0..6).map { mon.plus(it) }
        val cal1 = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 20, 9, 15)
        }
        val cal2 = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 20, 9, 45)
        }
        val t1 = UserTask(TaskId("t1"), "A", due = TaskDue.Timed.of(cal1.timeInMillis), createdAt = 0L, updatedAt = 0L)
        val t2 = UserTask(TaskId("t2"), "B", due = TaskDue.Timed.of(cal2.timeInMillis), createdAt = 0L, updatedAt = 0L)
        val factory = MemoryModelFactory()
        val habit = factory.buildHabit().apply {
            id = 1L
            name = "Stretch"
            reminder = Reminder(9, 0, WeekdayList(127))
        }

        val events = TaskCalendarEvents.from(listOf(t1, t2))
        val plannedHabits = PlannedHabitProjector.projectForRange(listOf(habit), mon, mon.plus(6))
        val counts = TaskCalendarStateHelper.computePlanningWeekHourCounts(weekDays, events, plannedHabits)

        assertEquals(24, counts.size)
        assertEquals(3, counts[9]) // 2 tasks + 1 habit on Tuesday at 09:00
        assertEquals(0, counts[8])
    }

    @Test
    fun weekAndDayPeriodNavigationPreservesOffsets() {
        val saturday = LocalDate(2026, 10, 24)
        val (nextWeekDay, _, _) = TaskCalendarStateHelper.computePeriodNavigation(
            scale = CalendarScale.WEEK,
            selectedDay = saturday,
            visibleYear = 2026,
            visibleMonth = 10,
            delta = 1
        )
        assertEquals(LocalDate(2026, 10, 31), nextWeekDay)

        val (prevWeekDay, _, _) = TaskCalendarStateHelper.computePeriodNavigation(
            scale = CalendarScale.WEEK,
            selectedDay = saturday,
            visibleYear = 2026,
            visibleMonth = 10,
            delta = -1
        )
        assertEquals(LocalDate(2026, 10, 17), prevWeekDay)

        val (nextSingleDay, _, _) = TaskCalendarStateHelper.computePeriodNavigation(
            scale = CalendarScale.DAY,
            selectedDay = saturday,
            visibleYear = 2026,
            visibleMonth = 10,
            delta = 1
        )
        assertEquals(LocalDate(2026, 10, 25), nextSingleDay)
    }

    @Test
    fun dstDayHourLabelsMarkSpringForwardAndFallBackRepeatedHours() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))

            // Ordinary 24h day
            val normalDay = LocalDate(2026, 10, 24)
            val normalLabels = TaskCalendarStateHelper.buildDstHourLabels(
                normalDay,
                FocusHistoryProjector.getDayLengthHours(normalDay)
            )
            assertEquals(24, normalLabels.size)
            assertEquals("02:00", normalLabels[2])

            // Fall back 25h day (Oct 25, 2026 in Europe/Berlin)
            val fallBackDay = LocalDate(2026, 10, 25)
            val fallHours = FocusHistoryProjector.getDayLengthHours(fallBackDay)
            assertEquals(25, fallHours)
            val fallLabels = TaskCalendarStateHelper.buildDstHourLabels(fallBackDay, fallHours)
            assertEquals(25, fallLabels.size)
            assertTrue(fallLabels[2].contains("02:00") && fallLabels[2].contains("UTC+02"))
            assertTrue(fallLabels[3].contains("02:00") && fallLabels[3].contains("UTC+01"))

            // Spring forward 23h day (Mar 29, 2026 in Europe/Berlin)
            val springForwardDay = LocalDate(2026, 3, 29)
            val springHours = FocusHistoryProjector.getDayLengthHours(springForwardDay)
            assertEquals(23, springHours)
            val springLabels = TaskCalendarStateHelper.buildDstHourLabels(springForwardDay, springHours)
            assertEquals(23, springLabels.size)
            assertEquals("01:00", springLabels[1])
            assertEquals("03:00", springLabels[2])
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun midnightCrossingWithPausesSetsEstimatedPartitionFlag() {
        val day = LocalDate(2026, 10, 26)
        val dayStart = startOfLocalDay(day, 0, 0)
        val start = dayStart + 23 * 3_600_000L
        val end = dayStart + 25 * 3_600_000L // 2h elapsed across midnight
        val session = FocusSession(
            id = FocusSessionId("paused_mid"),
            startedAt = start,
            endedAt = end,
            durationMs = 60 * 60_000L, // 1h active -> pauses present
            source = FocusSessionSource.TIMER,
            createdAt = start,
            updatedAt = end
        )
        val spec = TaskCalendarStateHelper.buildSessionDetailSpec(
            session = session,
            selectedDay = day,
            tasks = emptyList(),
            habits = emptyList(),
            containers = emptyMap(),
            defaultTaskLabel = "Task",
            defaultHabitLabel = "Habit",
            defaultContainerLabel = "Container",
            defaultFocusLabel = "Focus"
        )
        assertTrue(spec.hasPauses)
        assertTrue(spec.isEstimatedPartition)
        assertFalse(spec.sameLocalDay)
    }

    @Test
    fun controllerUsesUnifiedComposePathWithoutLegacyViewsOrDialogs() {
        val paths = listOf(
            java.nio.file.Path.of("src/main/java/org/isoron/uhabits/activities/tasks/TaskCalendarViewController.kt"),
            java.nio.file.Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/tasks/TaskCalendarViewController.kt")
        )
        val controllerFile = paths.first { java.nio.file.Files.isRegularFile(it) }
        val source = String(java.nio.file.Files.readAllBytes(controllerFile), Charsets.UTF_8)

        assertTrue(source.contains("TaskCalendarRootCompose("))
        assertFalse(source.contains("AlertDialog"))
        assertFalse(source.contains("ScrollView"))
        assertFalse(source.contains("HorizontalScrollView"))
    }
}
