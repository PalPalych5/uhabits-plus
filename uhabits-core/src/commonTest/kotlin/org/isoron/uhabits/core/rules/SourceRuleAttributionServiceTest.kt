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
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import kotlin.test.*

class SourceRuleAttributionServiceTest {

    private lateinit var ruleRepo: MemorySourceRuleRepository
    private lateinit var contribRepo: MemoryContributionRepository

    private val habits = mutableMapOf(
        "h-deep-work" to TargetHabitInfo(isNumerical = true, unit = "min", name = "Deep Work"),
        "h-study" to TargetHabitInfo(isNumerical = true, unit = "минуты", name = "Study"),
        "h-boolean" to TargetHabitInfo(isNumerical = false, unit = "min", name = "Boolean Habit"),
        "h-steps" to TargetHabitInfo(isNumerical = true, unit = "steps", name = "Steps Habit"),
        "h-archived" to TargetHabitInfo(isNumerical = true, unit = "min", name = "Archived Habit", isArchived = true),
    )

    private val tasks = mutableMapOf<String, UserTask>()

    private fun createService(
        repository: SourceRuleRepository = ruleRepo,
        taskResolver: ((TaskId) -> UserTask?)? = { tasks[it.value] },
    ) = SourceRuleAttributionService(
        ruleRepository = repository,
        contributionRepository = contribRepo,
        habitResolver = { habits[it.uuid] },
        taskResolver = taskResolver,
    )

    @BeforeTest
    fun setup() {
        ruleRepo = MemorySourceRuleRepository()
        contribRepo = MemoryContributionRepository()
        tasks.clear()
    }

    private fun makeTask(
        id: String,
        containerId: String? = null,
        linkedHabitUuid: String? = null,
        habitTimeAccrualEnabled: Boolean = false,
        habitLinkEffectiveFrom: Long? = null,
    ) = UserTask(
        id = TaskId(id),
        title = "Task " + id,
        containerId = containerId?.let(::ContainerId),
        linkedHabitUuid = linkedHabitUuid,
        habitTimeAccrualEnabled = habitTimeAccrualEnabled,
        habitLinkEffectiveFrom = habitLinkEffectiveFrom,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

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

        // Deleted/unavailable targets are safely excluded from automatic runtime attribution.
        ruleRepo.record(makeRule("r-unknown", "c-unknown", "h-nonexistent"))
        val sessionUnknown = makeSession("s-u", "c-unknown", durationMs = 60_000L)
        assertTrue(service.attributeSession(sessionUnknown).single() is AttributionOutcome.IncompatibleTarget)
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
        assertEquals(2, report2.contributionsAlreadyPresent)
        assertEquals(0, report2.contributionsRevoked)

        assertEquals(historical, contribRepo.getAll())
        assertEquals(2, contribRepo.getAll(includeRemoved = false).size)
        // Ended rules remain visible to recovery, but work beyond their interval is ineligible.
        val futureOutcomes = service.attributeSession(future)
        assertTrue(futureOutcomes.none { it is AttributionOutcome.Attributed })
        assertTrue(futureOutcomes.all { it is AttributionOutcome.Ineligible })
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
            override fun getAll(includeRemoved: Boolean): List<SourceRule> =
                ruleRepo.getAll(includeRemoved).map { it.copy(isEnabled = enabled) }
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

    @Test
    fun testDirectStopwatchOnHabitCreditsHabitWithoutContainerRules() {
        val service = createService()
        // 15 minutes stopwatch = 900,000 ms
        val session = FocusSession(
            id = FocusSessionId("stopwatch-1"),
            startedAt = 1000L,
            endedAt = 1000L + 900_000L,
            durationMs = 900_000L,
            source = FocusSessionSource.TIMER,
            habit = HabitRef("h-deep-work"),
            createdAt = 1000L,
            updatedAt = 901_000L,
        )
        val outcomes = service.attributeSession(session)
        assertEquals(1, outcomes.size)
        val outcome = outcomes.single() as AttributionOutcome.Attributed
        assertTrue(outcome.wasInserted)
        assertEquals(HabitRef("h-deep-work"), outcome.contribution.targetHabit)
        assertEquals(15_000L, outcome.contribution.contributedValue) // 15.0 min on x1000 scale
        assertNull(outcome.contribution.sourceRuleId)

        // Idempotency: repeated attribution produces no duplicates
        val repeatOutcomes = service.attributeSession(session)
        assertEquals(1, repeatOutcomes.size)
        val repeat = repeatOutcomes.single() as AttributionOutcome.Attributed
        assertFalse(repeat.wasInserted)
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testDirectPomodoroOnHabitCreditsHabitWithoutContainerRules() {
        val service = createService()
        // 25 minutes pomodoro = 1,500,000 ms
        val session = FocusSession(
            id = FocusSessionId("pomodoro-1"),
            startedAt = 1000L,
            endedAt = 1000L + 1_500_000L,
            durationMs = 1_500_000L,
            source = FocusSessionSource.TIMER,
            habit = HabitRef("h-study"),
            createdAt = 1000L,
            updatedAt = 1_501_000L,
        )
        val outcomes = service.attributeSession(session)
        assertEquals(1, outcomes.size)
        val outcome = outcomes.single() as AttributionOutcome.Attributed
        assertTrue(outcome.wasInserted)
        assertEquals(HabitRef("h-study"), outcome.contribution.targetHabit)
        assertEquals(25_000L, outcome.contribution.contributedValue) // 25.0 min on x1000 scale
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testDirectSessionOnIncompatibleHabitDoesNotCredit() {
        val service = createService()
        // Session directly targeting boolean habit
        val sessionBoolean = FocusSession(
            id = FocusSessionId("s-bool"),
            startedAt = 1000L,
            endedAt = 61_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            habit = HabitRef("h-boolean"),
            createdAt = 1000L,
            updatedAt = 61_000L,
        )
        val outcomesBool = service.attributeSession(sessionBoolean)
        assertEquals(1, outcomesBool.size)
        assertTrue(outcomesBool.single() is AttributionOutcome.IncompatibleTarget)
        assertTrue(contribRepo.getAll().isEmpty())

        // Session directly targeting steps habit (non-minute unit)
        val sessionSteps = FocusSession(
            id = FocusSessionId("s-steps"),
            startedAt = 1000L,
            endedAt = 61_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            habit = HabitRef("h-steps"),
            createdAt = 1000L,
            updatedAt = 61_000L,
        )
        val outcomesSteps = service.attributeSession(sessionSteps)
        assertEquals(1, outcomesSteps.size)
        assertTrue(outcomesSteps.single() is AttributionOutcome.IncompatibleTarget)
        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testTaskSessionWithoutMatchingRuleRecordsNoContributions() {
        val service = createService()
        // Session on a task without any rules configured
        val taskSession = FocusSession(
            id = FocusSessionId("task-no-rule"),
            startedAt = 1000L,
            endedAt = 61_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = org.isoron.uhabits.core.userTasks.TaskId("task-1"),
            containerId = ContainerId("c-unrelated"),
            createdAt = 1000L,
            updatedAt = 61_000L,
        )
        val outcomes = service.attributeSession(taskSession)
        assertTrue(outcomes.isEmpty())
        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testManualDurationAndFocusDurationProduceEquivalentEffectiveQuantity() {
        // Manual 15 minutes in AddNumericalEntryOpCommand uses deltaValue = (15.0 * 1000.0).roundToInt() = 15000L
        val manualValue = 15_000L
        val focusDurationMs = 15 * 60 * 1000L
        val computedFocusValue = HabitTimeValueConverter.convertFocusDurationToHabitValue(focusDurationMs)
        assertEquals(manualValue, computedFocusValue)
    }

    @Test
    fun testTaskSessionWithActiveHabitLinkAttributesDuration() {
        val task = makeTask(
            id = "task-active",
            containerId = "c-work",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[task.id.value] = task

        val session = FocusSession(
            id = FocusSessionId("fs-task-1"),
            startedAt = 20_000L,
            endedAt = 50_000L,
            durationMs = 30_000L, // 30 seconds -> rounds to 1 min (1000L) or let's test minutes
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            containerId = ContainerId("c-work"),
            createdAt = 20_000L,
            updatedAt = 50_000L,
        )

        val service = createService()
        val outcomes = service.attributeSession(session, isLiveCapture = true)
        assertEquals(1, outcomes.size)
        val attributed = outcomes.single() as AttributionOutcome.Attributed
        assertTrue(attributed.wasInserted)
        assertEquals("h-deep-work", attributed.contribution.targetHabit.uuid)
        assertEquals(HabitTimeValueConverter.convertFocusDurationToHabitValue(30_000L), attributed.contribution.contributedValue)
        assertNull(attributed.contribution.sourceRuleId)
        assertEquals(SourceRuleAttributionService.deterministicContributionId("fs-task-1", "h-deep-work"), attributed.contribution.id)
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testTaskSessionReattributionIsIdempotentAndDeduplicated() {
        val task = makeTask(
            id = "task-idempotent",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[task.id.value] = task

        val session = FocusSession(
            id = FocusSessionId("fs-task-dedupe"),
            startedAt = 100_000L,
            endedAt = 160_000L,
            durationMs = 60_000L, // 1 minute -> 1000L
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 100_000L,
            updatedAt = 160_000L,
        )

        val service = createService()
        val firstOutcomes = service.attributeSession(session, isLiveCapture = true)
        assertEquals(1, firstOutcomes.size)
        val first = firstOutcomes.single() as AttributionOutcome.Attributed
        assertTrue(first.wasInserted)
        assertEquals(1000L, first.contribution.contributedValue)

        // Repeat attribution for the same session (even in background recovery sweep)
        val secondOutcomes = service.attributeSession(session, isLiveCapture = false)
        assertEquals(1, secondOutcomes.size)
        val second = secondOutcomes.single() as AttributionOutcome.Attributed
        assertFalse(second.wasInserted)
        assertEquals(first.contribution.id, second.contribution.id)
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testTaskSessionNonRetroactivityRejectsSessionsBeforeEffectiveFrom() {
        val task = makeTask(
            id = "task-non-retro",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 100_000L,
        )
        tasks[task.id.value] = task

        // Session starting BEFORE habitLinkEffectiveFrom (startedAt = 50_000L < 100_000L)
        val pastSession = FocusSession(
            id = FocusSessionId("fs-past"),
            startedAt = 50_000L,
            endedAt = 80_000L,
            durationMs = 30_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 50_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        val pastOutcomes = service.attributeSession(pastSession, isLiveCapture = true)
        assertEquals(1, pastOutcomes.size)
        val ineligible = pastOutcomes.single() as AttributionOutcome.Ineligible
        assertNull(ineligible.ruleId)
        assertTrue(contribRepo.getAll().isEmpty())

        // Session starting BEFORE cutoff even if endedAt >= cutoff (startedAt = 90_000L < 100_000L, endedAt = 110_000L)
        // Must be rejected under strict START >= cutoff rule!
        val straddlingSession = FocusSession(
            id = FocusSessionId("fs-straddle"),
            startedAt = 90_000L,
            endedAt = 110_000L,
            durationMs = 20_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 90_000L,
            updatedAt = 110_000L,
        )
        val straddleOutcomes = service.attributeSession(straddlingSession, isLiveCapture = true)
        assertEquals(1, straddleOutcomes.size)
        assertTrue(straddleOutcomes.single() is AttributionOutcome.Ineligible)
        assertTrue(contribRepo.getAll().isEmpty())

        // Session starting AT OR AFTER habitLinkEffectiveFrom (startedAt = 100_000L >= 100_000L)
        val validSession = FocusSession(
            id = FocusSessionId("fs-valid"),
            startedAt = 100_000L,
            endedAt = 110_000L,
            durationMs = 10_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 100_000L,
            updatedAt = 110_000L,
        )

        val validOutcomes = service.attributeSession(validSession, isLiveCapture = true)
        assertEquals(1, validOutcomes.size)
        assertTrue(validOutcomes.single() is AttributionOutcome.Attributed)
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testTaskSessionWithAccrualDisabledRecordsNoContributions() {
        val task = makeTask(
            id = "task-disabled",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = false,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[task.id.value] = task

        val session = FocusSession(
            id = FocusSessionId("fs-disabled"),
            startedAt = 20_000L,
            endedAt = 50_000L,
            durationMs = 30_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 20_000L,
            updatedAt = 50_000L,
        )

        val service = createService()
        val outcomes = service.attributeSession(session)
        assertTrue(outcomes.isEmpty())
        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testTaskSessionDisallowsAutomaticContainerRuleAttribution() {
        // Container rule configured for c-work targeting h-study
        ruleRepo.record(makeRule(
            id = "rule-c-work",
            containerId = "c-work",
            habitUuid = "h-study",
            includeDescendants = true,
        ))

        // Task in c-work without explicit habit link
        val unlinkedTask = makeTask(
            id = "task-unlinked",
            containerId = "c-work",
            linkedHabitUuid = null,
            habitTimeAccrualEnabled = false,
        )
        tasks[unlinkedTask.id.value] = unlinkedTask

        val sessionUnlinked = FocusSession(
            id = FocusSessionId("fs-unlinked"),
            startedAt = 10_000L,
            endedAt = 70_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = unlinkedTask.id,
            containerId = ContainerId("c-work"),
            createdAt = 10_000L,
            updatedAt = 70_000L,
        )

        val service = createService()
        val outcomesUnlinked = service.attributeSession(sessionUnlinked)
        // Automatic container rule attribution MUST NOT apply to task sessions
        assertTrue(outcomesUnlinked.isEmpty())
        assertTrue(contribRepo.getAll().isEmpty())

        // Task in c-work WITH explicit habit link to h-deep-work
        val linkedTask = makeTask(
            id = "task-explicit-link",
            containerId = "c-work",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 0L,
        )
        tasks[linkedTask.id.value] = linkedTask

        val sessionLinked = FocusSession(
            id = FocusSessionId("fs-explicit-link"),
            startedAt = 10_000L,
            endedAt = 70_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = linkedTask.id,
            containerId = ContainerId("c-work"),
            createdAt = 10_000L,
            updatedAt = 70_000L,
        )

        val outcomesLinked = service.attributeSession(sessionLinked, isLiveCapture = true)
        assertEquals(1, outcomesLinked.size)
        val attributed = outcomesLinked.single() as AttributionOutcome.Attributed
        // Must attribute to explicitly linked habit (h-deep-work), NEVER container rule (h-study)
        assertEquals("h-deep-work", attributed.contribution.targetHabit.uuid)
        assertNull(attributed.contribution.sourceRuleId)
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testTaskSessionWithIncompatibleTargetHabitReturnsIncompatibleTarget() {
        val taskBoolean = makeTask(
            id = "task-boolean",
            linkedHabitUuid = "h-boolean",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 0L,
        )
        val taskSteps = makeTask(
            id = "task-steps",
            linkedHabitUuid = "h-steps",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 0L,
        )
        tasks[taskBoolean.id.value] = taskBoolean
        tasks[taskSteps.id.value] = taskSteps

        val sessionBool = FocusSession(
            id = FocusSessionId("fs-bool"),
            startedAt = 10_000L,
            endedAt = 70_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = taskBoolean.id,
            createdAt = 10_000L,
            updatedAt = 70_000L,
        )
        val sessionSteps = FocusSession(
            id = FocusSessionId("fs-steps"),
            startedAt = 10_000L,
            endedAt = 70_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = taskSteps.id,
            createdAt = 10_000L,
            updatedAt = 70_000L,
        )

        val service = createService()
        val outcomesBool = service.attributeSession(sessionBool, isLiveCapture = true)
        assertEquals(1, outcomesBool.size)
        assertTrue(outcomesBool.single() is AttributionOutcome.IncompatibleTarget)

        val outcomesSteps = service.attributeSession(sessionSteps, isLiveCapture = true)
        assertEquals(1, outcomesSteps.size)
        assertTrue(outcomesSteps.single() is AttributionOutcome.IncompatibleTarget)

        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testRecoverySweepDoesNotDeriveNewTaskContributions() {
        val task = makeTask(
            id = "task-recovery",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[task.id.value] = task

        val session = FocusSession(
            id = FocusSessionId("fs-recovery"),
            startedAt = 20_000L,
            endedAt = 80_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 20_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        // During background recovery sweep (isLiveCapture = false), task sessions NEVER derive new contributions
        val outcomes = service.attributeSession(session, isLiveCapture = false)
        assertTrue(outcomes.isEmpty())
        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testTargetSwitchPreventsDoubleAccrualAcrossHabits() {
        val task = makeTask(
            id = "task-switch",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[task.id.value] = task

        val session = FocusSession(
            id = FocusSessionId("fs-switch"),
            startedAt = 20_000L,
            endedAt = 80_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 20_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        // 1. Live capture credits h-deep-work
        val outcomes1 = service.attributeSession(session, isLiveCapture = true)
        assertEquals(1, outcomes1.size)
        assertTrue(outcomes1.single() is AttributionOutcome.Attributed)
        assertEquals(1, contribRepo.getAll().size)
        assertEquals("h-deep-work", contribRepo.getAll().single().targetHabit.uuid)

        // 2. Task is later switched to h-study
        val switchedTask = task.copy(
            linkedHabitUuid = "h-study",
            habitLinkEffectiveFrom = 90_000L,
        )
        tasks[task.id.value] = switchedTask

        // 3. Re-evaluating the same session must be blocked: existing source credit prevents attributing to new target
        val outcomes2 = service.attributeSession(session, isLiveCapture = true)
        assertEquals(1, outcomes2.size)
        val ineligible = outcomes2.single() as AttributionOutcome.Ineligible
        assertTrue(ineligible.reason.contains("already attributed"))

        // Ledger must still contain ONLY the original single contribution to h-deep-work
        assertEquals(1, contribRepo.getAll().size)
        assertEquals("h-deep-work", contribRepo.getAll().single().targetHabit.uuid)
    }

    @Test
    fun testTaskSessionWithNullEffectiveCutoffFailsClosed() {
        val task = makeTask(
            id = "task-null-cutoff",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = null, // null cutoff
        )
        tasks[task.id.value] = task

        val session = FocusSession(
            id = FocusSessionId("fs-null-cutoff"),
            startedAt = 20_000L,
            endedAt = 80_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 20_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        val outcomes = service.attributeSession(session, isLiveCapture = true)
        assertEquals(1, outcomes.size)
        val ineligible = outcomes.single() as AttributionOutcome.Ineligible
        assertTrue(ineligible.reason.contains("cutoff is not set"))
        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testReEnableInactiveGapPreventsAccrualDuringDisabledPeriod() {
        // Accrual was disabled during t=10_000 to t=100_000. Re-enabled at t=100_000 with fresh cutoff.
        val task = makeTask(
            id = "task-gap",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 100_000L,
        )
        tasks[task.id.value] = task

        // Session occurred during the disabled gap at t=50_000
        val gapSession = FocusSession(
            id = FocusSessionId("fs-gap"),
            startedAt = 50_000L,
            endedAt = 80_000L,
            durationMs = 30_000L,
            source = FocusSessionSource.TIMER,
            taskId = task.id,
            createdAt = 50_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        val outcomes = service.attributeSession(gapSession, isLiveCapture = true)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is AttributionOutcome.Ineligible)
        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testArchivedOrDeletedHabitRejectsAttribution() {
        val taskArchived = makeTask(
            id = "task-archived-target",
            linkedHabitUuid = "h-archived",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        val taskDeleted = makeTask(
            id = "task-deleted-target",
            linkedHabitUuid = "h-nonexistent",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[taskArchived.id.value] = taskArchived
        tasks[taskDeleted.id.value] = taskDeleted

        val session1 = FocusSession(
            id = FocusSessionId("fs-archived"),
            startedAt = 20_000L,
            endedAt = 80_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = taskArchived.id,
            createdAt = 20_000L,
            updatedAt = 80_000L,
        )
        val session2 = FocusSession(
            id = FocusSessionId("fs-deleted"),
            startedAt = 20_000L,
            endedAt = 80_000L,
            durationMs = 60_000L,
            source = FocusSessionSource.TIMER,
            taskId = taskDeleted.id,
            createdAt = 20_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        val outcomes1 = service.attributeSession(session1, isLiveCapture = true)
        assertEquals(1, outcomes1.size)
        assertTrue(outcomes1.single() is AttributionOutcome.IncompatibleTarget)

        val outcomes2 = service.attributeSession(session2, isLiveCapture = true)
        assertEquals(1, outcomes2.size)
        assertTrue(outcomes2.single() is AttributionOutcome.IncompatibleTarget)

        assertTrue(contribRepo.getAll().isEmpty())
    }

    @Test
    fun testNonTaskPrimaryTargetTypeWithTaskIdDoesNotAccrueToHabitLink() {
        val task = makeTask(
            id = "task-context-only",
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 10_000L,
        )
        tasks[task.id.value] = task

        // Session with habit as primary target type, but taskId attached as context
        val habitSessionWithTaskContext = FocusSession(
            id = FocusSessionId("fs-habit-primary"),
            startedAt = 20_000L,
            endedAt = 80_000L,
            durationMs = 60_000L,
            taskId = task.id,
            habit = HabitRef("h-study"),
            primaryTargetType = org.isoron.uhabits.core.focus.FocusPrimaryTargetType.HABIT,
            source = FocusSessionSource.TIMER,
            createdAt = 20_000L,
            updatedAt = 80_000L,
        )

        val service = createService()
        val outcomes = service.attributeSession(habitSessionWithTaskContext, isLiveCapture = true)
        // Must attribute directly to h-study (direct habit attribution), NOT to task's linked habit (h-deep-work)
        assertEquals(1, outcomes.size)
        val attributed = outcomes.single() as AttributionOutcome.Attributed
        assertEquals("h-study", attributed.contribution.targetHabit.uuid)
    }

    @Test
    fun testActiveTimerLinkChangesConservativeNonRetroactiveSemantics() {
        val taskId = "task-active-link-change"
        val service = createService()

        // 1. Initial state: Task unlinked / accrual disabled
        tasks[taskId] = makeTask(
            id = taskId,
            linkedHabitUuid = null,
            habitTimeAccrualEnabled = false,
            habitLinkEffectiveFrom = null,
        )

        // Session 1 started at t=10_000. While running at t=20_000, user links habit A.
        // Timer finishes at t=30_000.
        tasks[taskId] = makeTask(
            id = taskId,
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 20_000L,
        )
        val session1 = FocusSession(
            id = FocusSessionId("fs-mid-enable"),
            startedAt = 10_000L,
            endedAt = 30_000L,
            durationMs = 20_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 10_000L,
            updatedAt = 30_000L,
        )
        val outcome1 = service.attributeSession(session1, isLiveCapture = true)
        assertEquals(1, outcome1.size)
        assertTrue(outcome1.single() is AttributionOutcome.Ineligible)
        val reason1 = (outcome1.single() as AttributionOutcome.Ineligible).reason
        assertTrue(reason1.contains("Session started before task habit link became effective"))
        assertTrue(contribRepo.getAll().isEmpty(), "No credit accrued for session started before habit link activation")

        // 2. Session 2 started at t=30_000 while habit A is active (effectiveFrom=20_000).
        // While running at t=40_000, user disables accrual.
        // Timer finishes at t=50_000.
        tasks[taskId] = makeTask(
            id = taskId,
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = false,
            habitLinkEffectiveFrom = 20_000L,
        )
        val session2 = FocusSession(
            id = FocusSessionId("fs-mid-disable"),
            startedAt = 30_000L,
            endedAt = 50_000L,
            durationMs = 20_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 30_000L,
            updatedAt = 50_000L,
        )
        val outcome2 = service.attributeSession(session2, isLiveCapture = true)
        assertTrue(outcome2.isEmpty(), "No credit accrued when accrual was disabled before completion")
        assertTrue(contribRepo.getAll().isEmpty())

        // 3. Re-enable habit A at t=50_000. Session 3 started at t=50_000.
        // While running at t=60_000, user switches link to habit B (h-study) with fresh effectiveFrom=60_000.
        // Timer finishes at t=70_000.
        tasks[taskId] = makeTask(
            id = taskId,
            linkedHabitUuid = "h-study",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 60_000L,
        )
        val session3 = FocusSession(
            id = FocusSessionId("fs-mid-switch"),
            startedAt = 50_000L,
            endedAt = 70_000L,
            durationMs = 20_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 50_000L,
            updatedAt = 70_000L,
        )
        val outcome3 = service.attributeSession(session3, isLiveCapture = true)
        assertEquals(1, outcome3.size)
        assertTrue(outcome3.single() is AttributionOutcome.Ineligible)
        val reason3 = (outcome3.single() as AttributionOutcome.Ineligible).reason
        assertTrue(reason3.contains("Session started before task habit link became effective"))
        assertTrue(contribRepo.getAll().isEmpty(), "Target B must not receive credit for session started before switch")

        // 4. Fresh session started at t=70_000 AFTER switch to habit B (effectiveFrom=60_000).
        // Timer finishes at t=90_000.
        val session4 = FocusSession(
            id = FocusSessionId("fs-after-switch"),
            startedAt = 70_000L,
            endedAt = 90_000L,
            durationMs = 20_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 70_000L,
            updatedAt = 90_000L,
        )
        val outcome4 = service.attributeSession(session4, isLiveCapture = true)
        assertEquals(1, outcome4.size)
        assertTrue(outcome4.single() is AttributionOutcome.Attributed)
        val attributed = outcome4.single() as AttributionOutcome.Attributed
        assertEquals("h-study", attributed.contribution.targetHabit.uuid)
        assertEquals(1, contribRepo.getAll().size)
    }

    @Test
    fun testMultipleShortSessionsFractionalMinutesAndNoDoubleAccrual() {
        val taskId = "task-short-sessions"
        tasks[taskId] = makeTask(
            id = taskId,
            linkedHabitUuid = "h-deep-work",
            habitTimeAccrualEnabled = true,
            habitLinkEffectiveFrom = 1_000L,
        )
        val service = createService()

        // 3 short sessions of 15s (15_000ms), 30s (30_000ms), 45s (45_000ms)
        // With x1000 fixed point:
        // 15_000ms -> 250L (0.250 min)
        // 30_000ms -> 500L (0.500 min)
        // 45_000ms -> 750L (0.750 min)
        val s1 = FocusSession(
            id = FocusSessionId("fs-short-1"),
            startedAt = 10_000L,
            endedAt = 25_000L,
            durationMs = 15_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 10_000L,
            updatedAt = 25_000L,
        )
        val s2 = FocusSession(
            id = FocusSessionId("fs-short-2"),
            startedAt = 30_000L,
            endedAt = 60_000L,
            durationMs = 30_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 30_000L,
            updatedAt = 60_000L,
        )
        val s3 = FocusSession(
            id = FocusSessionId("fs-short-3"),
            startedAt = 70_000L,
            endedAt = 115_000L,
            durationMs = 45_000L,
            taskId = org.isoron.uhabits.core.userTasks.TaskId(taskId),
            source = FocusSessionSource.TIMER,
            createdAt = 70_000L,
            updatedAt = 115_000L,
        )

        val out1 = service.attributeSession(s1, isLiveCapture = true)
        val out2 = service.attributeSession(s2, isLiveCapture = true)
        val out3 = service.attributeSession(s3, isLiveCapture = true)

        assertEquals(250L, (out1.single() as AttributionOutcome.Attributed).contribution.contributedValue)
        assertEquals(500L, (out2.single() as AttributionOutcome.Attributed).contribution.contributedValue)
        assertEquals(750L, (out3.single() as AttributionOutcome.Attributed).contribution.contributedValue)

        val allContribs = contribRepo.getAll()
        assertEquals(3, allContribs.size)
        val totalContributedValue = allContribs.sumOf { it.contributedValue }
        assertEquals(1500L, totalContributedValue) // Exactly 1.500 minutes in aggregate

        // Idempotency: re-evaluating each session must not duplicate or alter ledger
        val reeval1 = service.attributeSession(s1, isLiveCapture = true)
        val reeval2 = service.attributeSession(s2, isLiveCapture = true)
        val reeval3 = service.attributeSession(s3, isLiveCapture = true)

        assertFalse((reeval1.single() as AttributionOutcome.Attributed).wasInserted)
        assertFalse((reeval2.single() as AttributionOutcome.Attributed).wasInserted)
        assertFalse((reeval3.single() as AttributionOutcome.Attributed).wasInserted)

        assertEquals(3, contribRepo.getAll().size, "No duplicate contributions created on re-evaluation")
        assertEquals(1500L, contribRepo.getAll().sumOf { it.contributedValue }, "Total aggregate value remains identical")
    }
}
