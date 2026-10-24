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

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.startOfLocalDay
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.userTasks.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.Calendar
import java.util.TimeZone

class FocusHistoryProjectorTest {

    private fun createSession(
        id: String = "session-1",
        startedAt: Long,
        endedAt: Long,
        durationMs: Long = endedAt - startedAt,
        taskId: TaskId? = null,
        containerId: ContainerId? = null,
        organizationContext: String? = null
    ): FocusSession {
        return FocusSession(
            id = FocusSessionId(id),
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = durationMs,
            taskId = taskId,
            containerId = containerId,
            source = FocusSessionSource.TIMER,
            createdAt = startedAt,
            updatedAt = endedAt,
            organizationContext = organizationContext
        )
    }

    @Test
    fun singleDaySessionProjectsToSingleSegment() {
        val date = LocalDate(2026, 10, 24)
        val dayStart = startOfLocalDay(date, 0, 0)
        // 10:00 to 11:30 (1h 30m)
        val start = dayStart + 10 * 3600 * 1000L
        val end = dayStart + 11 * 3600 * 1000L + 30 * 60 * 1000L
        val session = createSession("s1", start, end)

        val segments = FocusHistoryProjector.projectSessionToDays(session)
        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals(date, seg.day)
        assertEquals(start, seg.segmentStartMs)
        assertEquals(end, seg.segmentEndMs)
        assertEquals(90 * 60 * 1000L, seg.segmentDurationMs)
        assertFalse(seg.isContinuationFromPrevDay)
        assertFalse(seg.continuesToNextDay)
        assertEquals("s1", seg.session.id.value)
    }

    @Test
    fun midnightCrossingSessionSplitsAcrossDaysWithExactDurationSum() {
        val day1 = LocalDate(2026, 10, 24)
        val day2 = LocalDate(2026, 10, 25)
        val day2Midnight = startOfLocalDay(day2, 0, 0)

        // Starts 23:30 on day 1, ends 01:30 on day 2 (total 2 hours = 7,200,000 ms)
        val start = day2Midnight - 30 * 60 * 1000L // 30 min before midnight
        val end = day2Midnight + 90 * 60 * 1000L // 90 min after midnight
        // Active duration has pauses: 100 minutes = 6,000,000 ms out of 120 min elapsed
        val durationMs = 100 * 60 * 1000L
        val session = createSession("s_midnight", start, end, durationMs = durationMs)

        val segments = FocusHistoryProjector.projectSessionToDays(session)
        assertEquals(2, segments.size)

        val seg1 = segments[0]
        assertEquals(day1, seg1.day)
        assertEquals(start, seg1.segmentStartMs)
        assertEquals(day2Midnight, seg1.segmentEndMs)
        assertFalse(seg1.isContinuationFromPrevDay)
        assertTrue(seg1.continuesToNextDay)
        assertEquals("s_midnight", seg1.session.id.value)

        val seg2 = segments[1]
        assertEquals(day2, seg2.day)
        assertEquals(day2Midnight, seg2.segmentStartMs)
        assertEquals(end, seg2.segmentEndMs)
        assertTrue(seg2.isContinuationFromPrevDay)
        assertFalse(seg2.continuesToNextDay)
        assertEquals("s_midnight", seg2.session.id.value)

        // Sum of segment durations must EXACTLY equal the original session duration
        assertEquals(durationMs, seg1.segmentDurationMs + seg2.segmentDurationMs)
        // Day 1 has 30/120 = 25% of elapsed time -> 25% of 100m = 25m = 1,500,000 ms
        assertEquals(25 * 60 * 1000L, seg1.segmentDurationMs)
        // Day 2 has 90/120 = 75% of elapsed time -> 75% of 100m = 75m = 4,500,000 ms
        assertEquals(75 * 60 * 1000L, seg2.segmentDurationMs)
    }

    @Test
    fun monthBoundaryCrossingSessionIsPartitionedCorrectly() {
        val oct31 = LocalDate(2026, 10, 31)
        val nov1 = LocalDate(2026, 11, 1)
        val nov1Midnight = startOfLocalDay(nov1, 0, 0)

        val start = nov1Midnight - 60 * 60 * 1000L // 23:00 Oct 31
        val end = nov1Midnight + 60 * 60 * 1000L // 01:00 Nov 1
        val session = createSession("s_month", start, end)

        val segments = FocusHistoryProjector.projectSessionToDays(session)
        assertEquals(2, segments.size)
        assertEquals(oct31, segments[0].day)
        assertEquals(nov1, segments[1].day)
        assertEquals(session.durationMs, segments[0].segmentDurationMs + segments[1].segmentDurationMs)
    }

    @Test
    fun yearBoundaryCrossingSessionIsPartitionedCorrectly() {
        val dec31 = LocalDate(2026, 12, 31)
        val jan1 = LocalDate(2027, 1, 1)
        val jan1Midnight = startOfLocalDay(jan1, 0, 0)

        val start = jan1Midnight - 30 * 60 * 1000L
        val end = jan1Midnight + 30 * 60 * 1000L
        val session = createSession("s_year", start, end)

        val segments = FocusHistoryProjector.projectSessionToDays(session)
        assertEquals(2, segments.size)
        assertEquals(dec31, segments[0].day)
        assertEquals(jan1, segments[1].day)
        assertEquals(session.durationMs, segments[0].segmentDurationMs + segments[1].segmentDurationMs)
    }

    @Test
    fun invalidOrZeroIntervalsAreSafelyRejected() {
        val sessionZero = runCatching {
            FocusSession(
                id = FocusSessionId("sz"),
                startedAt = 1000L,
                endedAt = 1000L, // invalid: endedAt == startedAt
                durationMs = 1L,
                source = FocusSessionSource.MANUAL,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        }.getOrNull()
        assertTrue(sessionZero == null)

        val validSession = FocusSession(
            id = FocusSessionId("sb"),
            startedAt = 1000L,
            endedAt = 2000L,
            durationMs = 1000L,
            source = FocusSessionSource.MANUAL,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        assertEquals(1, FocusHistoryProjector.projectSessionToDays(validSession).size)
    }

    @Test
    fun overlappingIntervalsAssignedSideBySideLanes() {
        val date = LocalDate(2026, 10, 24)
        val dayStart = startOfLocalDay(date, 0, 0)

        // Session 1: 10:00 to 11:00
        val s1 = createSession("s1", dayStart + 10 * 3600 * 1000L, dayStart + 11 * 3600 * 1000L)
        // Session 2: 10:30 to 11:30 (overlaps with s1)
        val s2 = createSession("s2", dayStart + 10 * 3600 * 1000L + 30 * 60 * 1000L, dayStart + 11 * 3600 * 1000L + 30 * 60 * 1000L)

        val seg1 = FocusHistoryProjector.projectSessionToDays(s1).single()
        val seg2 = FocusHistoryProjector.projectSessionToDays(s2).single()

        val laned = FocusHistoryProjector.assignLanes(listOf(seg1, seg2))
        assertEquals(2, laned.size)

        val laned1 = laned.find { it.session.id.value == "s1" }!!
        val laned2 = laned.find { it.session.id.value == "s2" }!!

        assertEquals(2, laned1.totalLanes)
        assertEquals(2, laned2.totalLanes)
        assertEquals(0, laned1.laneIndex)
        assertEquals(1, laned2.laneIndex)
    }

    @Test
    fun sequentialIntervalsReuseSingleLane() {
        val date = LocalDate(2026, 10, 24)
        val dayStart = startOfLocalDay(date, 0, 0)

        // Session 1: 10:00 to 11:00
        val s1 = createSession("s1", dayStart + 10 * 3600 * 1000L, dayStart + 11 * 3600 * 1000L)
        // Session 2: 11:00 to 12:00 (immediately adjacent, no overlap)
        val s2 = createSession("s2", dayStart + 11 * 3600 * 1000L, dayStart + 12 * 3600 * 1000L)

        val seg1 = FocusHistoryProjector.projectSessionToDays(s1).single()
        val seg2 = FocusHistoryProjector.projectSessionToDays(s2).single()

        val laned = FocusHistoryProjector.assignLanes(listOf(seg1, seg2))
        assertEquals(2, laned.size)
        // Because they don't overlap, totalLanes is 1 and both use lane 0
        assertEquals(1, laned[0].totalLanes)
        assertEquals(0, laned[0].laneIndex)
        assertEquals(1, laned[1].totalLanes)
        assertEquals(0, laned[1].laneIndex)
    }

    @Test
    fun mergedBusyTimeDistinguishedFromTotalWorkDuration() {
        val date = LocalDate(2026, 10, 24)
        val dayStart = startOfLocalDay(date, 0, 0)

        // Session 1: 10:00 to 11:00 (60 min)
        val s1 = createSession("s1", dayStart + 10 * 3600 * 1000L, dayStart + 11 * 3600 * 1000L)
        // Session 2: 10:30 to 11:30 (60 min)
        val s2 = createSession("s2", dayStart + 10 * 3600 * 1000L + 30 * 60 * 1000L, dayStart + 11 * 3600 * 1000L + 30 * 60 * 1000L)

        val summary = FocusHistoryProjector.buildSummaryForDay(listOf(s1, s2), date)

        // Total work duration = 60m + 60m = 120m (7,200,000 ms)
        assertEquals(120 * 60 * 1000L, summary.totalWorkDurationMs)
        // Real merged busy interval = 10:00 to 11:30 = 90m (5,400,000 ms)
        assertEquals(90 * 60 * 1000L, summary.mergedBusyDurationMs)
        // Overlap flag must be true
        assertTrue(summary.hasOverlaps)
        assertEquals(2, summary.sessionCount)
    }

    @Test
    fun frozenOrganizationContextPreservedUnderContainerFiltering() {
        val target = ContainerId("work-proj")
        val other = ContainerId("personal")

        val sWithContext = createSession(
            id = "s_frozen",
            startedAt = 1000L,
            endedAt = 5000L,
            organizationContext = """{"path":[{"id":"work-proj","name":"Work"}]}"""
        )
        val sOtherContext = createSession(
            id = "s_other",
            startedAt = 1000L,
            endedAt = 5000L,
            organizationContext = """{"path":[{"id":"personal","name":"Personal"}]}"""
        )

        val filtered = FocusHistoryProjector.filterByContainer(
            sessions = listOf(sWithContext, sOtherContext),
            targetContainer = target,
            currentSubtreeIds = emptySet()
        )
        assertEquals(1, filtered.size)
        assertEquals("s_frozen", filtered.single().id.value)
    }

    @Test
    fun calculateQueryRangeEncompassesBoundaryCrossings() {
        val day = LocalDate(2026, 10, 24)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 1)
        }

        val (monthStartTs, monthEndTs) = FocusHistoryProjector.calculateQueryRange(CalendarScale.MONTH, day, cal)
        val (weekStartTs, weekEndTs) = FocusHistoryProjector.calculateQueryRange(CalendarScale.WEEK, day, cal)
        val (dayStartTs, dayEndTs) = FocusHistoryProjector.calculateQueryRange(CalendarScale.DAY, day, cal)

        // Day range expands 1 day before and 2 days after
        assertTrue(dayStartTs < startOfLocalDay(day, 0, 0))
        assertTrue(dayEndTs > startOfLocalDay(day.plus(1), 0, 0))

        assertTrue(weekStartTs < dayStartTs)
        assertTrue(monthStartTs <= weekStartTs)
    }

    @Test
    fun nonOverlappingSessionsWithPausesDoNotFlagFalseOverlaps() {
        val day = LocalDate(2026, 10, 24)
        val dayStart = startOfLocalDay(day, 0, 0)

        // Session 1: 10:00 to 11:00 (elapsed 60m), but active duration 45m (15m pause)
        val s1 = createSession(
            id = "s1",
            startedAt = dayStart + 10 * 3600_000L,
            endedAt = dayStart + 11 * 3600_000L,
            durationMs = 45 * 60_000L
        )

        // Session 2: 14:00 to 15:00 (elapsed 60m), but active duration 50m (10m pause)
        val s2 = createSession(
            id = "s2",
            startedAt = dayStart + 14 * 3600_000L,
            endedAt = dayStart + 15 * 3600_000L,
            durationMs = 50 * 60_000L
        )

        val summary = FocusHistoryProjector.buildSummaryForDay(listOf(s1, s2), day)
        // Total work duration = 45m + 50m = 95m
        assertEquals(95 * 60_000L, summary.totalWorkDurationMs)
        // Merged busy duration = 60m + 60m = 120m
        assertEquals(120 * 60_000L, summary.mergedBusyDurationMs)
        // They do NOT overlap in wall-clock time -> hasOverlaps must be false!
        assertFalse("Sessions do not overlap in wall clock time", summary.hasOverlaps)
        assertEquals(2, summary.sessionCount)
        assertTrue(summary.segments.all { it.totalLanes == 1 && it.laneIndex == 0 })
    }

    @Test
    fun berlinDstFallBackTwoSeparate215IntervalsNoFalseOverlapOrMerge() {
        val prevZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val fallDay = LocalDate(2026, 10, 25)

            // Day length must be 25 hours (fall back)
            assertEquals(25 * 3600_000L, FocusHistoryProjector.getDayLengthMs(fallDay))
            assertEquals(25, FocusHistoryProjector.getDayLengthHours(fallDay))

            fun parseEpoch(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

            // Interval 1: 02:15 CEST (+02) to 02:45 CEST (+02)
            val s1Start = parseEpoch("2026-10-25T02:15:00+02:00")
            val s1End = parseEpoch("2026-10-25T02:45:00+02:00")
            val s1 = createSession("pr12_dst_first", s1Start, s1End)

            // Interval 2: 02:15 CET (+01) to 02:45 CET (+01)
            val s2Start = parseEpoch("2026-10-25T02:15:00+01:00")
            val s2End = parseEpoch("2026-10-25T02:45:00+01:00")
            val s2 = createSession("pr12_dst_second", s2Start, s2End)

            // s2 starts 1 hour after s1 starts, 30 min after s1 ends
            assertTrue(s2Start > s1End)
            assertEquals(30 * 60_000L, s2Start - s1End)

            // Project both to day summary
            val summary = FocusHistoryProjector.buildSummaryForDay(listOf(s1, s2), fallDay)

            // Both sessions must be included
            assertEquals(2, summary.sessionCount)
            assertEquals(2, summary.segments.size)

            // Total work duration is 30m + 30m = 60m
            assertEquals(60 * 60_000L, summary.totalWorkDurationMs)

            // Merged busy time must be 60m, NOT merged or collapsed into 30m
            assertEquals(60 * 60_000L, summary.mergedBusyDurationMs)

            // No false overlap
            assertFalse(summary.hasOverlaps)

            // Both segments reuse lane 0, totalLanes = 1
            assertTrue(summary.segments.all { it.laneIndex == 0 && it.totalLanes == 1 })

            // Test pure elapsed axis helpers
            val s1StartMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(fallDay, s1Start)
            val s1EndMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(fallDay, s1End)
            val s2StartMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(fallDay, s2Start)
            val s2EndMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(fallDay, s2End)

            // 02:15+02 is 135 minutes after midnight
            assertEquals(135f, s1StartMin, 0.001f)
            assertEquals(165f, s1EndMin, 0.001f)

            // 02:15+01 is 195 minutes after midnight
            assertEquals(195f, s2StartMin, 0.001f)
            assertEquals(225f, s2EndMin, 0.001f)

            // Ensure s1EndMin <= s2StartMin (no visual overlap on elapsed timeline axis)
            assertTrue("DST fall-back intervals must not overlap on elapsed axis", s1EndMin <= s2StartMin)

            // Test zone offset formatting
            assertEquals("+02", FocusHistoryProjector.formatZoneOffset(s1Start))
            assertEquals("+01", FocusHistoryProjector.formatZoneOffset(s2Start))

            // Test segment interval text containing explicit zone offsets
            val seg1 = summary.segments.first { it.session.id.value == "pr12_dst_first" }
            val seg2 = summary.segments.first { it.session.id.value == "pr12_dst_second" }
            val seg1Text = FocusHistoryProjector.formatSegmentIntervalText(seg1)
            val seg2Text = FocusHistoryProjector.formatSegmentIntervalText(seg2)

            assertTrue("Segment 1 text must include +02 offset", seg1Text.contains("+02"))
            assertTrue("Segment 2 text must include +01 offset", seg2Text.contains("+01"))
        } finally {
            TimeZone.setDefault(prevZone)
        }
    }

    @Test
    fun springForwardDayHas23HoursAndAccurateElapsedMapping() {
        val prevZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val springDay = LocalDate(2026, 3, 29)

            // Spring forward day has 23 hours
            assertEquals(23 * 3600_000L, FocusHistoryProjector.getDayLengthMs(springDay))
            assertEquals(23, FocusHistoryProjector.getDayLengthHours(springDay))

            fun parseEpoch(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

            val sBefore = createSession("s_pre", parseEpoch("2026-03-29T01:00:00+01:00"), parseEpoch("2026-03-29T01:45:00+01:00"))
            val sAfter = createSession("s_post", parseEpoch("2026-03-29T03:15:00+02:00"), parseEpoch("2026-03-29T04:00:00+02:00"))

            val summary = FocusHistoryProjector.buildSummaryForDay(listOf(sBefore, sAfter), springDay)
            assertEquals(2, summary.sessionCount)
            assertEquals(90 * 60_000L, summary.totalWorkDurationMs)
            assertFalse(summary.hasOverlaps)

            // Elapsed minutes from midnight
            val preMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(springDay, sBefore.startedAt)
            val postMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(springDay, sAfter.startedAt)

            // 01:00 is 60m from midnight
            assertEquals(60f, preMin, 0.001f)
            // Midnight is 23:00Z; 03:15 CEST is 01:15Z, 135 elapsed minutes later.
            assertEquals(135f, postMin, 0.001f)
            assertTrue(preMin < postMin)
        } finally {
            TimeZone.setDefault(prevZone)
        }
    }

    @Test
    fun ordinaryDayHas24HoursAndStandardIntervalText() {
        val prevZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val ordinaryDay = LocalDate(2026, 10, 26)

            assertEquals(24 * 3600_000L, FocusHistoryProjector.getDayLengthMs(ordinaryDay))
            assertEquals(24, FocusHistoryProjector.getDayLengthHours(ordinaryDay))

            fun parseEpoch(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            val s = createSession("s_ord", parseEpoch("2026-10-26T10:00:00+01:00"), parseEpoch("2026-10-26T11:30:00+01:00"))

            val seg = FocusHistoryProjector.projectSessionToDays(s).single()
            val text = FocusHistoryProjector.formatSegmentIntervalText(seg)
            // Standard formatting without offset clutter on ordinary days
            assertEquals("10:00 – 11:30", text)
        } finally {
            TimeZone.setDefault(prevZone)
        }
    }

    @Test
    fun fourPlusTrueOverlappingLanesAssignedCorrectly() {
        val date = LocalDate(2026, 10, 26)
        val dayStart = startOfLocalDay(date, 0, 0)

        // 4 concurrent overlapping sessions from 10:00 to 11:00
        val s0 = createSession("s0", dayStart + 10 * 3600_000L, dayStart + 11 * 3600_000L)
        val s1 = createSession("s1", dayStart + 10 * 3600_000L, dayStart + 11 * 3600_000L)
        val s2 = createSession("s2", dayStart + 10 * 3600_000L, dayStart + 11 * 3600_000L)
        val s3 = createSession("s3", dayStart + 10 * 3600_000L, dayStart + 11 * 3600_000L)
        // 5th session overlapping partially from 10:15 to 10:45
        val s4 = createSession("s4", dayStart + 10 * 3600_000L + 15 * 60_000L, dayStart + 10 * 3600_000L + 45 * 60_000L)

        val summary = FocusHistoryProjector.buildSummaryForDay(listOf(s0, s1, s2, s3, s4), date)
        assertEquals(5, summary.sessionCount)
        assertTrue(summary.hasOverlaps)

        // Merged busy duration is 10:00 to 11:00 = 60 min
        assertEquals(60 * 60_000L, summary.mergedBusyDurationMs)
        // Total work duration is 60 * 4 + 30 = 270 min
        assertEquals(270 * 60_000L, summary.totalWorkDurationMs)

        // All 5 must have totalLanes == 5 and distinct lane indices 0..4
        val laned = summary.segments
        assertEquals(5, laned.size)
        assertTrue(laned.all { it.totalLanes == 5 })
        val laneIndices = laned.map { it.laneIndex }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4), laneIndices)
    }

    @Test
    fun crossMidnightPausedSessionIdentifiesEstimatedAllocationAndPreservesOriginalDuration() {
        val day1 = LocalDate(2026, 10, 26)
        val day2 = LocalDate(2026, 10, 27)
        val day2Midnight = startOfLocalDay(day2, 0, 0)

        // Starts 23:00 on Day 1, ends 02:00 on Day 2 (elapsed = 3h = 180m = 10,800,000 ms)
        val start = day2Midnight - 60 * 60_000L // 1h before midnight
        val end = day2Midnight + 120 * 60_000L // 2h after midnight
        val elapsed = end - start // 180m

        // Active work duration is 120m (60m pause, so durationMs != elapsed)
        val originalDurationMs = 120 * 60_000L
        val pausedSession = createSession("s_paused_midnight", start, end, durationMs = originalDurationMs)

        val segments = FocusHistoryProjector.projectSessionToDays(pausedSession)
        assertEquals(2, segments.size)

        val seg1 = segments[0]
        val seg2 = segments[1]

        assertEquals(day1, seg1.day)
        assertEquals(day2, seg2.day)

        // Both segments MUST identify estimated day allocation
        assertTrue("Segment 1 must identify estimated allocation", seg1.isDayAllocationEstimated)
        assertTrue("Segment 2 must identify estimated allocation", seg2.isDayAllocationEstimated)

        // Both segments MUST retain original session.durationMs
        assertEquals(originalDurationMs, seg1.session.durationMs)
        assertEquals(originalDurationMs, seg2.session.durationMs)

        // Proportional duration partition: Day 1 has 60/180 = 1/3 -> 40m
        assertEquals(40 * 60_000L, seg1.segmentDurationMs)
        // Day 2 has 120/180 = 2/3 -> 80m
        assertEquals(80 * 60_000L, seg2.segmentDurationMs)

        // EXACT original duration conservation
        assertEquals(originalDurationMs, seg1.segmentDurationMs + seg2.segmentDurationMs)

        // Check DayHistorySummary flag
        val summary1 = FocusHistoryProjector.buildSummaryForDay(listOf(pausedSession), day1)
        val summary2 = FocusHistoryProjector.buildSummaryForDay(listOf(pausedSession), day2)
        assertTrue(summary1.hasEstimatedDayAllocation)
        assertTrue(summary2.hasEstimatedDayAllocation)

        // CONTRAST: Unpaused cross-midnight session (durationMs == elapsed)
        val unpausedSession = createSession("s_unpaused_midnight", start, end, durationMs = elapsed)
        val unpausedSegments = FocusHistoryProjector.projectSessionToDays(unpausedSession)
        assertFalse("Unpaused cross-midnight segment 1 is exact", unpausedSegments[0].isDayAllocationEstimated)
        assertFalse("Unpaused cross-midnight segment 2 is exact", unpausedSegments[1].isDayAllocationEstimated)

        // CONTRAST: Paused single-day session (does not cross midnight)
        val singleDayPaused = createSession("s_single_paused", start, day2Midnight - 1000L, durationMs = 30 * 60_000L)
        val singleDaySegments = FocusHistoryProjector.projectSessionToDays(singleDayPaused)
        assertEquals(1, singleDaySegments.size)
        assertFalse("Single-day paused session is not estimated across days", singleDaySegments[0].isDayAllocationEstimated)
    }
}
