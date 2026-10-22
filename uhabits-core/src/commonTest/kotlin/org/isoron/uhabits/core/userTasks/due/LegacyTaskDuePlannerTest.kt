/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LegacyTaskDuePlannerTest {
    private fun source(plan: String?, deadline: Long? = null) = LegacyTaskDueSnapshot(
        "task-id", plan, deadline, 123L, "legacy-sqlite-v30", "revision-1", "op-1",
    )

    @Test
    fun planOnlyPastTodayAndFutureStayNoneWithExplicitReviewProposal() {
        for (raw in listOf("2000-01-01", "2026-10-22", "2099-12-31")) {
            val result = LegacyTaskDuePlanner.plan(source(raw))
            assertSame(TaskDue.None, result.due)
            assertEquals(raw, result.proposedDay?.isoDate)
            assertEquals(setOf(LegacyTaskDueReview.PLAN_REQUIRES_REVIEW), result.review)
            assertEquals(raw, result.source.rawPlannedDate)
        }
    }

    @Test
    fun deadlineAlwaysWinsExactlyWithoutInferringTimezoneOrTimeIntent() {
        for (plan in listOf(null, "2026-10-19", "2027-01-01", "2026-02-30", "")) {
            for (instant in listOf(0L, 64800000L, 1792368000123L)) {
                val original = source(plan, instant)
                val result = LegacyTaskDuePlanner.plan(original)
                assertEquals(TaskDue.Timed.legacy(instant), result.due)
                assertEquals(original, result.source)
                assertTrue(LegacyTaskDueReview.LEGACY_TIME_EXPLICITNESS_AND_ZONE_UNKNOWN in result.review)
            }
        }
    }

    @Test
    fun malformedRawAndNullEmptyLiteralNullRemainDistinct() {
        val snapshots = listOf(null, "", "null", "2026-02-30", " 2026-10-19", "1792368000000")
            .map { source(it) }
        assertEquals(6, snapshots.toSet().size)
        for (snapshot in snapshots) {
            val result = LegacyTaskDuePlanner.plan(snapshot)
            assertEquals(snapshot, result.source)
            assertSame(TaskDue.None, result.due)
            assertNull(result.proposedDay)
            assertEquals(snapshot.rawPlannedDate != null, LegacyTaskDueReview.INVALID_PLAN in result.review)
        }
    }

    @Test
    fun numericPlanRequiresKnownProvenanceAndExactEpochDay() {
        val known = source("1792368000000").copy(numericPlanEncoding = LegacyPlanEncoding.KNOWN_SYNC_UTC_EPOCH_DAY_MILLIS)
        assertEquals("2026-10-19", LegacyTaskDuePlanner.plan(known).proposedDay?.isoDate)
        assertNull(LegacyTaskDuePlanner.plan(known.copy(rawPlannedDate = "1792368000001")).proposedDay)
        assertNull(LegacyTaskDuePlanner.plan(known.copy(rawPlannedDate = Long.MAX_VALUE.toString())).proposedDay)
    }

    @Test
    fun retriesAndChangedSourcesNeverRecreateDueAfterExplicitClear() {
        val original = source("2026-10-19", 1792368000123L)
        val cleared = LegacyTaskDuePlanner.plan(original).copy(due = TaskDue.None)
        assertSame(cleared, LegacyTaskDuePlanner.plan(original, cleared))
        val changed = original.copy(rawPlannedDate = "2027-01-01", updatedAt = 456L)
        val next = LegacyTaskDuePlanner.plan(changed, cleared)
        assertSame(TaskDue.None, next.due)
        assertEquals(original, next.source)
        assertEquals(setOf(changed), next.pendingSources)
        assertSame(next, LegacyTaskDuePlanner.plan(changed, next))
        assertFailsWith<IllegalArgumentException> {
            LegacyTaskDuePlanner.plan(changed.copy(taskId = "other"), next)
        }
    }

    @Test
    fun unknownSourceFormatIsRejectedWithoutChangingRawOrPriorConversion() {
        val valid = source("2026-10-19")
        val existing = LegacyTaskDuePlanner.plan(valid)
        for (format in listOf("legacy-sqlite-v31", "unknown", "legacy-v30")) {
            val raw = valid.copy(rawPlannedDate = " exact raw ", sourceFormat = format)
            assertFailsWith<IllegalArgumentException> { LegacyTaskDuePlanner.plan(raw) }
            assertFailsWith<IllegalArgumentException> { LegacyTaskDuePlanner.plan(raw, existing) }
            assertEquals(" exact raw ", raw.rawPlannedDate)
            assertEquals(valid, existing.source)
        }
        assertEquals(TaskDue.None, LegacyTaskDuePlanner.plan(valid.copy(sourceFormat = "legacy-sqlite-v29")).due)
    }
}
