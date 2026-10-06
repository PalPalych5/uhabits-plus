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

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionRemovalResult
import org.isoron.uhabits.core.contribution.ContributionService
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.rules.memory.MemorySourceRuleRepository
import kotlin.test.*

class SourceRuleAttributionServiceTest {

    private lateinit var ruleRepo: MemorySourceRuleRepository
    private lateinit var contribRepo: MemoryContributionRepository

    private val habits = mutableMapOf(
        "h-deep-work" to TargetHabitInfo(isNumerical = true, unit = "min", name = "Deep Work"),
        "h-study" to TargetHabitInfo(isNumerical = true, unit = "минуты", name = "Study"),
        "h-boolean" to TargetHabitInfo(isNumerical = false, unit = "min", name = "Boolean Habit"),
        "h-steps" to TargetHabitInfo(isNumerical = true, unit = "steps", name = "Steps Habit"),
    )

    private fun createService(repository: SourceRuleRepository = ruleRepo) = SourceRuleAttributionService(
        ruleRepository = repository,
        contributionRepository = contribRepo,
        habitResolver = { habits[it.uuid] },
    )

    @BeforeTest
    fun setup() {
        ruleRepo = MemorySourceRuleRepository()
        contribRepo = MemoryContributionRepository()
    }

    private fun makeRule(
        id: String,
        containerId: String,
        habitUuid: String,
        includeDescendants: Boolean = true,
        effectiveFrom: Long = 0L,
        isEnabled: Boolean = true,
    ) = SourceRule(
        id = SourceRuleId(id),
        sourceKind = SourceRuleSourceKind.FOCUS_SESSION,
        sourceContainerId = ContainerId(containerId),
        includeDescendants = includeDescendants,
        targetHabit = HabitRef(habitUuid),
        valueMapping = SourceRuleValueMapping.FOCUS_DURATION,
        isEnabled = isEnabled,
        effectiveFrom = effectiveFrom,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private fun makeSession(
        id: String,
        containerId: String,
        durationMs: Long,
        startedAt: Long = 1000L,
        endedAt: Long = startedAt + durationMs,
        orgContext: String? = null,
    ) = FocusSession(
        id = FocusSessionId(id),
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = durationMs,
        source = FocusSessionSource.TIMER,
        containerId = ContainerId(containerId),
        organizationContext = orgContext,
        createdAt = startedAt,
        updatedAt = endedAt,
    )

    @Test
    fun testDeterministicContributionId() {
        val cid = SourceRuleAttributionService.deterministicContributionId("session-123", "habit-456")
        assertEquals("contrib:focus:session-123:habit-456", cid.value)
    }

    @Test
    fun testDirectAttributionAndIdempotency() {
        val service = createService()
        ruleRepo.record(makeRule("r-work", "c-work", "h-deep-work"))

        // 42 minutes = 2,520,000 ms -> 42,000L
        val session = makeSession("sess-1", "c-work", durationMs = 2_520_000L, startedAt = 1000L)

        // First attribution
        val outcomes = service.attributeSession(session)
        assertEquals(1, outcomes.size)
        val outcome = outcomes.first() as AttributionOutcome.Attributed
        assertTrue(outcome.wasInserted)
        assertEquals(42_000L, outcome.contribution.contributedValue)
        assertEquals("contrib:focus:sess-1:h-deep-work", outcome.contribution.id.value)
        assertEquals(SourceRuleId("r-work"), outcome.contribution.sourceRuleId)

        // Second attribution (idempotent retry)
        val retriedOutcomes = service.attributeSession(session)
        assertEquals(1, retriedOutcomes.size)
        val retriedOutcome = retriedOutcomes.first() as AttributionOutcome.Attributed
        assertFalse(retriedOutcome.wasInserted)
        assertEquals(outcome.contribution, retriedOutcome.contribution)

        // Ledger has exactly 1 record
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testDeduplicationOfOverlappingRules() {
        val service = createService()
        // Parent rule on c-parent and Child rule on c-child, both projecting into same habit h-deep-work
        ruleRepo.record(makeRule("r-parent", "c-parent", "h-deep-work", includeDescendants = true))
        ruleRepo.record(makeRule("r-child", "c-child", "h-deep-work", includeDescendants = true))

        val sessionInChild = makeSession(
            id = "sess-child",
            containerId = "c-child",
            durationMs = 1_500_000L, // 25 min -> 25,000
            startedAt = 1000L,
            orgContext = """{"path":[{"id":"c-parent"},{"id":"c-child"}]}""",
        )

        val outcomes = service.attributeSession(sessionInChild)
        assertEquals(1, outcomes.size)
        val outcome = outcomes.first() as AttributionOutcome.Attributed
        assertTrue(outcome.wasInserted)
        // More specific match (c-child direct match) wins over ancestor c-parent
        assertEquals(SourceRuleId("r-child"), outcome.contribution.sourceRuleId)
        assertEquals(25_000L, outcome.contribution.contributedValue)
    }

    @Test
    fun testEffectiveFromEnforcement() {
        val service = createService()
        // Rule effective starting from timestamp 100_000L
        ruleRepo.record(makeRule("r-future", "c-work", "h-deep-work", effectiveFrom = 100_000L))

        val pastSession = makeSession("sess-past", "c-work", durationMs = 60_000L, startedAt = 10_000L, endedAt = 70_000L)
        val currentSession = makeSession("sess-curr", "c-work", durationMs = 60_000L, startedAt = 40_000L, endedAt = 100_000L)

        // Past session is ineligible
        val pastOutcomes = service.attributeSession(pastSession)
        assertEquals(1, pastOutcomes.size)
        assertTrue(pastOutcomes.first() is AttributionOutcome.Ineligible)

        // Current session is eligible and attributed
        val currOutcomes = service.attributeSession(currentSession)
        assertEquals(1, currOutcomes.size)
        assertTrue(currOutcomes.first() is AttributionOutcome.Attributed)
    }

    @Test
    fun testIncompatibleTargetHabitHandling() {
        val service = createService()
        ruleRepo.record(makeRule("r-bool", "c-work", "h-boolean"))
        ruleRepo.record(makeRule("r-steps", "c-sport", "h-steps"))

        val sessionWork = makeSession("s-w", "c-work", durationMs = 60_000L)
        val sessionSport = makeSession("s-s", "c-sport", durationMs = 60_000L)

        val outcomeWork = service.attributeSession(sessionWork).first()
        assertTrue(outcomeWork is AttributionOutcome.IncompatibleTarget)

        val outcomeSport = service.attributeSession(sessionSport).first()
        assertTrue(outcomeSport is AttributionOutcome.IncompatibleTarget)

        // Nonexistent target habit throws
        ruleRepo.record(makeRule("r-unknown", "c-unknown", "h-nonexistent"))
        val sessionUnknown = makeSession("s-u", "c-unknown", durationMs = 60_000L)
        assertFailsWith<TargetHabitNotFoundException> {
            service.attributeSession(sessionUnknown)
        }
    }

    @Test
    fun testRevokeContributionsForRuleViaTombstone() {
        val service = createService()
        val rule = makeRule("r-rev", "c-work", "h-deep-work")
        ruleRepo.record(rule)

        val s1 = makeSession("s1", "c-work", durationMs = 60_000L)
        val s2 = makeSession("s2", "c-work", durationMs = 120_000L)
        service.attributeSession(s1)
        service.attributeSession(s2)

        assertEquals(2, contribRepo.getAll(includeRemoved = false).size)

        // Revoke rule contributions at timestamp 500_000L
        val revokedCount = service.revokeContributionsForRule(rule.id, now = 500_000L)
        assertEquals(2, revokedCount)

        // Active contributions are 0, tombstoned remain 2
        assertEquals(0, contribRepo.getAll(includeRemoved = false).size)
        val all = contribRepo.getAll(includeRemoved = true)
        assertEquals(2, all.size)
        assertTrue(all.all { it.removedAt == 500_000L && !it.isActive })
        val report = service.reconcileAll(listOf(s1, s2), now = 600_000L)
        assertEquals(0, report.contributionsCreated)
        assertEquals(0, report.contributionsRevoked)
        assertEquals(all, contribRepo.getAll(includeRemoved = true))
    }

    @Test
    fun testReconcileAllPreservesAttributionsAfterRuleRemoval() {
        val service = createService()
        val rule1 = makeRule("r1", "c-work", "h-deep-work")
        val rule2 = makeRule("r2", "c-study", "h-study")
        ruleRepo.record(rule1)
        ruleRepo.record(rule2)

        val s1 = makeSession("s1", "c-work", durationMs = 60_000L)
        val s2 = makeSession("s2", "c-study", durationMs = 60_000L)

        // 1. Initial reconcile creates both
        val report1 = service.reconcileAll(listOf(s1, s2), now = 300_000L)
        assertEquals(2, report1.contributionsCreated)
        assertEquals(0, report1.contributionsAlreadyPresent)
        assertEquals(0, report1.contributionsRevoked)
        val historical = contribRepo.getAll()

        // 2. Remove rule2
        ruleRepo.remove(rule2.id, removedAt = 400_000L)

        // Rule removal stops new attribution without changing historical ledger facts.
        val future = makeSession("s3", "c-study", durationMs = 60_000L, startedAt = 450_000L)
        val report2 = service.reconcileAll(listOf(s1, s2, future), now = 600_000L)
        assertEquals(0, report2.contributionsCreated)
        assertEquals(0, report2.contributionsRevoked)

        assertEquals(historical, contribRepo.getAll())
        assertEquals(2, contribRepo.getAll(includeRemoved = false).size)
        assertTrue(service.attributeSession(future).isEmpty())
        assertEquals(rule2.copy(removedAt = 400_000L, updatedAt = 400_000L), ruleRepo.getById(rule2.id))
        assertEquals(rule2.id, contribRepo.getById(ContributionId("contrib:focus:s2:h-study"))!!.sourceRuleId)
    }

    @Test
    fun testLaterDisablePreservesOldContributionAndRejectsFutureSession() {
        val rule = makeRule("r-work", "c-work", "h-deep-work")
        ruleRepo.record(rule)
        var enabled = true
        // PR14 has no rule update API. This decorator supplies the repository state
        // that a later disable would expose to the attribution service.
        val repository = object : SourceRuleRepository by ruleRepo {
            override fun getActiveRules(): List<SourceRule> =
                if (enabled) ruleRepo.getActiveRules() else emptyList()
            override fun getById(id: SourceRuleId): SourceRule? =
                ruleRepo.getById(id)?.copy(isEnabled = enabled)
        }
        val service = createService(repository)
        val past = makeSession("past", "c-work", durationMs = 7_200_000L)
        service.attributeSession(past)
        val historical = contribRepo.getAll().single()
        assertEquals(120_000L, historical.contributedValue)

        enabled = false
        val future = makeSession("future", "c-work", durationMs = 60_000L, startedAt = 10_000_000L)
        val report = service.reconcileAll(listOf(past, future), now = 11_000_000L)
        assertEquals(0, report.contributionsCreated)
        assertEquals(0, report.contributionsRevoked)
        assertTrue(service.attributeSession(future).isEmpty())
        assertEquals(listOf(historical), contribRepo.getAll())
        assertTrue(historical.isActive)
        assertEquals(rule.id, historical.sourceRuleId)
        assertFalse(repository.getById(rule.id)!!.isEnabled)
    }

    @Test
    fun testRemovedOverlapWinnerDoesNotRewriteHistoricalProvenance() {
        ruleRepo.record(makeRule("parent", "c-parent", "h-deep-work"))
        ruleRepo.record(makeRule("child", "c-child", "h-deep-work"))
        val service = createService()
        val past = makeSession(
            "past", "c-child", durationMs = 60_000L,
            orgContext = """{"path":[{"id":"c-parent"},{"id":"c-child"}]}""",
        )
        service.attributeSession(past)
        val historical = contribRepo.getAll().single()
        assertEquals(SourceRuleId("child"), historical.sourceRuleId)
        ruleRepo.remove(SourceRuleId("child"), removedAt = 100_000L)

        val future = past.copy(id = FocusSessionId("future"), startedAt = 200_000L,
            endedAt = 260_000L, createdAt = 200_000L, updatedAt = 260_000L)
        val report = service.reconcileAll(listOf(past, future), now = 300_000L)
        assertEquals(1, report.contributionsCreated)
        assertEquals(1, report.contributionsAlreadyPresent)
        assertEquals(0, report.contributionsRevoked)
        assertEquals(historical, contribRepo.getById(historical.id))
        assertEquals(SourceRuleId("parent"), contribRepo.getAll().single { it.sourceId == "future" }.sourceRuleId)
    }

    @Test
    fun testExplicitCorrectionOnlyRevokesIntendedAttributionAndSurvivesReconcile() {
        ruleRepo.record(makeRule("work", "c-work", "h-deep-work"))
        ruleRepo.record(makeRule("study", "c-work", "h-study"))
        val service = createService()
        val past = makeSession("past", "c-work", durationMs = 60_000L)
        val siblingSession = makeSession("sibling", "c-work", durationMs = 120_000L)
        service.reconcileAll(listOf(past, siblingSession), now = 200_000L)
        val explicit = ContributionService(contribRepo)
        // Explicit caller-supplied PR13 attribution has no SourceRule provenance.
        explicit.contributeFromFocusSession(past, HabitRef("h-manual"), ContributionId("manual"), 17L)
        val original = contribRepo.getAll().associateBy { it.id }
        val correctedId = ContributionId("contrib:focus:past:h-deep-work")

        assertEquals(ContributionRemovalResult.REMOVED, explicit.revokeFocusSessionContribution(
            past.id, HabitRef("h-deep-work"), removedAt = 300_000L,
        ))
        val corrected = contribRepo.getById(correctedId)!!
        assertEquals(300_000L, corrected.removedAt)
        assertEquals(SourceRuleId("work"), corrected.sourceRuleId)
        val report = service.reconcileAll(listOf(past, siblingSession), now = 400_000L)
        assertEquals(0, report.contributionsCreated)
        assertEquals(0, report.contributionsRevoked)
        assertEquals(corrected, contribRepo.getById(correctedId))
        for ((id, contribution) in original) {
            if (id != correctedId) assertEquals(contribution, contribRepo.getById(id))
        }
        assertEquals(5, contribRepo.getAll(includeRemoved = true).size)
        assertEquals(4, contribRepo.getAll().size)
        assertEquals(60_000L, past.durationMs)
        assertEquals(61_000L, past.endedAt)
    }
}
