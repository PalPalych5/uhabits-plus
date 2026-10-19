package org.isoron.uhabits.core.userTasks.contract

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Executable proposal fixtures; passing does not establish a shipped migration or protocol. */
class ProposedTaskDueContractTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val utc = ZoneId.of("UTC")
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) { return }
        fail("Expected rejection")
    }

    @Test fun legacyMatrixPreservesEveryRawValueAndNeverInfersPlanDeadline() {
        val plans = listOf(null, "2026-10-18", "2026-10-19", "2026-10-20", "1792368000000", "2026-02-30", "", "2026-1-1")
        val deadlines = listOf(null, now.minusSeconds(86400).toEpochMilli(), now.toEpochMilli(), now.plusSeconds(86400).toEpochMilli())
        for (plan in plans) for (deadline in deadlines) {
            val raw = LegacyDates(plan, deadline)
            val result = ProposedTaskDueContract.convert(raw)
            assertEquals(raw, result.original)
            assertEquals(result, ProposedTaskDueContract.convert(raw, result))
            if (deadline == null) {
                assertSame(ProposedDue.None, result.due)
                assertFalse(ProposedTaskDueContract.overdue(result.due, false, now, utc))
            } else {
                assertEquals(ProposedDue.Timed(Instant.ofEpochMilli(deadline), null), result.due)
            }
            if (plan != null) assertTrue(result.review.isNotEmpty())
        }
    }

    @Test fun strictDatesRejectMalformedAndNormalizedDates() {
        for (raw in listOf("2026-02-29", "2026-04-31", "2026-13-01", "2026-00-01", "+2026-01-01", "2026-1-01", "2026-01-01 ", "1792368000000")) {
            rejects { ProposedTaskDueContract.strictDay(raw) }
        }
        assertEquals(LocalDate.of(2024, 2, 29), ProposedTaskDueContract.strictDay("2024-02-29"))
    }

    @Test fun explicitMidnightAndEighteenRemainTimed() {
        for (hour in listOf(0, 18)) {
            val timed = ProposedTaskDueContract.timed(LocalDateTime.of(2026, 10, 19, hour, 0), "UTC", null)
            assertEquals(hour, timed.instant.atZone(utc).hour)
            assertEquals(timed, ProposedDueEnvelope.decode(ProposedDueEnvelope.encode(timed)))
            val legacy = ProposedTaskDueContract.convert(LegacyDates(null, timed.instant.toEpochMilli()))
            assertTrue(legacy.due is ProposedDue.Timed)
        }
    }

    @Test fun dateOnlyFloatsWhileTimedFollowsViewingZoneWithoutChangingInstant() {
        val day = ProposedDue.Day(LocalDate.of(2026, 10, 19))
        val timed = ProposedDue.Timed(Instant.parse("2026-10-19T00:30:00Z"), null)
        val west = ZoneId.of("Pacific/Pago_Pago")
        val east = ZoneId.of("Pacific/Kiritimati")
        assertEquals(day.date, ProposedTaskDueContract.date(day, west))
        assertEquals(day.date, ProposedTaskDueContract.date(day, east))
        assertEquals(LocalDate.of(2026, 10, 18), ProposedTaskDueContract.date(timed, west))
        assertEquals(LocalDate.of(2026, 10, 19), ProposedTaskDueContract.date(timed, east))
        assertEquals(Instant.parse("2026-10-19T00:30:00Z"), timed.instant)
    }

    @Test fun dstGapRejectedAndOverlapRequiresValidExplicitOffset() {
        rejects { ProposedTaskDueContract.timed(LocalDateTime.parse("2026-03-08T02:30"), "America/New_York", null) }
        val overlap = LocalDateTime.parse("2026-11-01T01:30")
        rejects { ProposedTaskDueContract.timed(overlap, "America/New_York", null) }
        rejects { ProposedTaskDueContract.timed(overlap, "America/New_York", ZoneOffset.UTC) }
        val first = ProposedTaskDueContract.timed(overlap, "America/New_York", ZoneOffset.ofHours(-4))
        val second = ProposedTaskDueContract.timed(overlap, "America/New_York", ZoneOffset.ofHours(-5))
        assertEquals(3600L, second.instant.epochSecond - first.instant.epochSecond)
        rejects { ProposedTaskDueContract.timed(overlap, "Unknown/Zone", null) }
    }

    @Test fun legacyPlanAndDeadlineAreNeverCollapsedByAViewingZone() {
        val raw = LegacyDates("2026-10-19", Instant.parse("2026-10-19T00:30:00Z").toEpochMilli())
        val converted = ProposedTaskDueContract.convert(raw)
        assertEquals(LocalDate.of(2026, 10, 18), ProposedTaskDueContract.date(converted.due, ZoneId.of("Pacific/Pago_Pago")))
        assertEquals(LocalDate.of(2026, 10, 19), ProposedTaskDueContract.date(converted.due, ZoneId.of("Pacific/Kiritimati")))
        assertEquals(raw, converted.original)
        assertTrue("PLAN_REQUIRES_REVIEW" in converted.review)
        assertEquals(converted, ProposedTaskDueContract.convert(raw, converted))
    }

    @Test fun todayIsStrictAndOverdueSectionsAreDisjoint() {
        val candidates = listOf(ProposedDue.None, ProposedDue.Day(LocalDate.of(2026, 10, 18)), ProposedDue.Day(LocalDate.of(2026, 10, 19)), ProposedDue.Day(LocalDate.of(2026, 10, 20)), ProposedDue.Timed(now.minusSeconds(1), null), ProposedDue.Timed(now, null), ProposedDue.Timed(now.plusSeconds(1), null))
        for (due in candidates) for (completed in listOf(false, true)) {
            val today = ProposedTaskDueContract.today(due, completed, now, utc)
            val overdue = ProposedTaskDueContract.overdue(due, completed, now, utc)
            assertFalse(today && overdue)
            if (today) assertEquals(LocalDate.of(2026, 10, 19), ProposedTaskDueContract.date(due, utc))
            if (completed) { assertFalse(today); assertFalse(overdue) }
        }
        assertTrue(ProposedTaskDueContract.today(ProposedDue.Day(LocalDate.of(2026, 10, 19)), false, now, utc))
        assertFalse(ProposedTaskDueContract.overdue(ProposedDue.Timed(now, null), false, now, utc))
    }

    @Test fun rerunCannotResurrectClearOrOverwriteCanonicalEdit() {
        val raw = LegacyDates("2026-10-18", now.toEpochMilli())
        val converted = ProposedTaskDueContract.convert(raw)
        for (due in listOf(ProposedDue.None, ProposedDue.Day(LocalDate.of(2027, 1, 1)))) {
            val edited = converted.copy(due = due)
            assertEquals(edited, ProposedTaskDueContract.convert(raw, edited))
            val changed = ProposedTaskDueContract.convert(raw.copy(rawPlan = "2026-10-20"), edited)
            assertEquals(due, changed.due)
            assertEquals(raw, changed.original)
            val incoming = raw.copy(rawPlan = "2026-10-20")
            assertEquals(setOf(incoming), changed.pendingLegacy)
            assertEquals(changed, ProposedTaskDueContract.convert(incoming, changed))
            val another = raw.copy(rawPlan = "2026-10-21")
            val reviewed = ProposedTaskDueContract.convert(another, changed)
            assertEquals(setOf(incoming, another), reviewed.pendingLegacy)
            assertEquals(due, reviewed.due)
            assertTrue("LEGACY_CHANGED" in changed.review)
        }
    }

    @Test fun envelopeRoundTripsFixturesAndDistinguishesMissingFromClear() {
        val fixtures = listOf(ProposedDue.None, ProposedDue.Day(LocalDate.of(2024, 2, 29)), ProposedDue.Timed(now, null), ProposedDue.Timed(now, "Asia/Novosibirsk"))
        for (due in fixtures) assertEquals(due, ProposedDueEnvelope.decode(ProposedDueEnvelope.encode(due)))
        val existing = fixtures.last()
        assertEquals(existing, ProposedDueEnvelope.patch(existing, null))
        assertSame(ProposedDue.None, ProposedDueEnvelope.patch(existing, ProposedDueEnvelope.encode(ProposedDue.None)))
    }

    @Test fun envelopeFailsClosedForUnknownOrIncompleteContracts() {
        for (fields in listOf(emptyMap(), mapOf("version" to "2", "kind" to "NONE"), mapOf("version" to "1", "kind" to "OTHER"), mapOf("version" to "1", "kind" to "DAY"), mapOf("version" to "1", "kind" to "TIMED"), mapOf("version" to "1", "kind" to "NONE", "date" to "2026-10-19"))) {
            rejects { ProposedDueEnvelope.decode(fields) }
        }
    }
}
