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

import org.isoron.uhabits.core.containers.*
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.memory.MemoryContributionRepository
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.memory.MemoryFocusSessionRepository
import org.isoron.uhabits.core.rules.memory.MemorySourceRuleRepository
import kotlin.test.*

class FocusCreditIntegrationTest {
    @Test fun deletedSourceCutoffAllowsEarlierRecoveryAndRejectsLaterWork() {
        val rules = MemorySourceRuleRepository()
        val ledger = MemoryContributionRepository()
        SourceRuleConfigurationService(rules) { SourceRuleId("r") }.configure(habit, info, container, true, 100_000)
        val deletedAt = 200_000L
        rules.remove(SourceRuleId("r"), 300_000L)
        val attribution = SourceRuleAttributionService(rules, ledger,
            sourceEligibility = { _, fact -> fact.endedAt < deletedAt }) { info }
        attribution.attributeSession(session("before-delete", 170_000))
        attribution.attributeSession(session("after-delete", 270_000))
        assertEquals(listOf("before-delete"), ledger.getAll().map { it.sourceId })
    }
    @Test fun unavailableSourceBlocksNewDerivationButPreservesOldAndCorrectionFacts() {
        val rules = MemorySourceRuleRepository()
        val ledger = MemoryContributionRepository()
        var available = true
        SourceRuleConfigurationService(rules) { SourceRuleId("r") }.configure(habit, info, container, true, 100_000)
        val attribution = SourceRuleAttributionService(rules, ledger, sourceEligibility = { _, _ -> available }) { info }
        val oldSession = session("old", 170_000)
        attribution.attributeSession(oldSession)
        val historical = ledger.getAll().single()
        available = false
        assertEquals(historical, (attribution.attributeSession(oldSession).single() as AttributionOutcome.Attributed).contribution)
        assertTrue(attribution.attributeSession(session("unavailable", 190_000)).none { it is AttributionOutcome.Attributed })
        assertEquals(listOf(historical), ledger.getAll())
        ContributionService(ledger).revokeFocusSessionContribution(oldSession.id, habit, 300_000)
        available = true
        assertTrue(attribution.attributeSession(oldSession).isEmpty())
        assertEquals(300_000L, ledger.getById(historical.id)?.removedAt)
    }
    private val habit = HabitRef("study")
    private val container = ContainerId("study-section")
    private val info = TargetHabitInfo(true, "мин")
    private fun session(id: String, end: Long, containerId: ContainerId = container) = FocusSession(
        FocusSessionId(id), end - 60_000, end, 60_000, containerId = containerId,
        source = FocusSessionSource.TIMER, createdAt = end - 60_000, updatedAt = end,
        organizationContext = "{\"path\":[\"study-section\",\"child\"]}",
    )

    @Test fun configureReplaceDisableAndReenablePreserveHistoryAndDoNotBackfill() {
        val rules = MemorySourceRuleRepository()
        val ledger = MemoryContributionRepository()
        var sequence = 0
        val config = SourceRuleConfigurationService(rules) { SourceRuleId("r${++sequence}") }
        val attribution = SourceRuleAttributionService(rules, ledger) { info }
        val first = assertNotNull(config.configure(habit, info, container, true, 100_000))
        assertEquals(first, config.configure(habit, info, container, true, 110_000))
        assertEquals(1, sequence)
        assertTrue(attribution.attributeSession(session("before", 90_000)).none { it is AttributionOutcome.Attributed })
        attribution.attributeSession(session("old", 170_000))
        val old = assertNotNull(ledger.getBySource(ContributionSourceType.FOCUS_SESSION, "old").singleOrNull())
        val changed = assertNotNull(config.configure(habit, info, container, false, 200_000))
        assertNotEquals(first.id, changed.id)
        assertEquals(200_000L, rules.getById(first.id)?.removedAt)
        assertEquals(first.id, old.sourceRuleId)
        assertTrue(attribution.attributeSession(session("child-new", 270_000, ContainerId("child"))).none { it is AttributionOutcome.Attributed })
        config.configure(habit, info, null, false, 300_000)
        assertNull(config.current(habit))
        attribution.attributeSession(session("disabled", 370_000))
        assertEquals(listOf(old), ledger.getAll())
        val enabled = assertNotNull(config.configure(habit, info, container, true, 400_000))
        assertEquals(400_000L, enabled.effectiveFrom)
        attribution.attributeSession(session("disabled", 370_000))
        assertEquals(listOf(old), ledger.getAll())
        attribution.attributeSession(session("new", 470_000))
        assertEquals(2, ledger.getAll().size)
        assertEquals(old, ledger.getById(old.id))
    }

    @Test fun incompatibleTargetsCannotConfigureAndNullSelectionDoesNotCreateRule() {
        val rules = MemorySourceRuleRepository()
        val config = SourceRuleConfigurationService(rules) { SourceRuleId("r") }
        for (invalid in listOf(TargetHabitInfo(false, "min"), TargetHabitInfo(true, "steps"), TargetHabitInfo(true, "m"))) {
            assertFailsWith<IncompatibleTargetHabitException> { config.configure(habit, invalid, container, true, 100_000) }
        }
        assertNull(config.configure(habit, info, null, true, 100_000))
        assertTrue(rules.getAll(true).isEmpty())
    }

    @Test fun crashRecoveryAfterDisableUsesEndedIntervalAndCorrectionIsNotRestored() {
        val rules = MemorySourceRuleRepository()
        val sessions = MemoryFocusSessionRepository()
        val ledger = MemoryContributionRepository()
        val config = SourceRuleConfigurationService(rules) { SourceRuleId("r") }
        config.configure(habit, info, container, true, 100_000)
        sessions.record(session("committed-before-crash", 170_000))
        config.configure(habit, info, null, true, 200_000)
        sessions.record(session("after-disable", 270_000))
        val recovery = FocusCreditReconciler(sessions, SourceRuleAttributionService(rules, ledger) { info })
        val firstPage = recovery.processPage(limit = 1, now = 300_000)
        assertEquals(1, firstPage.created)
        assertTrue(firstPage.hasMore)
        assertEquals(0, recovery.processPage(firstPage.nextCursor, 1, 300_000).created)
        assertEquals(0, recovery.processPage(now = 400_000).created)
        val original = ledger.getAll().single()
        ContributionService(ledger).revokeFocusSessionContribution(FocusSessionId("committed-before-crash"), habit, 500_000)
        assertEquals(0, recovery.processPage(now = 600_000).created)
        assertEquals(500_000L, ledger.getById(original.id)?.removedAt)
        assertEquals(2, sessions.getAll().size)
    }

    @Test fun interruptedAttributionReportsFailureAndNextSweepRecovers() {
        val rules = MemorySourceRuleRepository()
        val sessions = MemoryFocusSessionRepository()
        val backing = MemoryContributionRepository()
        var fail = true
        val ledger = object : ContributionRepository by backing {
            override fun record(contribution: Contribution): ContributionRecordResult {
                if (fail) throw IllegalStateException("interrupted")
                return backing.record(contribution)
            }
        }
        SourceRuleConfigurationService(rules) { SourceRuleId("r") }.configure(habit, info, container, true, 100_000)
        sessions.record(session("s", 170_000))
        val recovery = FocusCreditReconciler(sessions, SourceRuleAttributionService(rules, ledger) { info })
        assertEquals(1, recovery.processPage(now = 200_000).failures.size)
        assertNotNull(sessions.getById(FocusSessionId("s")))
        fail = false
        assertEquals(1, recovery.processPage(now = 300_000).created)
        assertEquals(0, recovery.processPage(now = 400_000).created)
    }

    @Test fun missingTargetIsSafeAndDoesNotCreateCredit() {
        val rules = MemorySourceRuleRepository()
        val ledger = MemoryContributionRepository()
        SourceRuleConfigurationService(rules) { SourceRuleId("r") }.configure(habit, info, container, true, 100_000)
        val outcome = SourceRuleAttributionService(rules, ledger) { null }.attributeSession(session("s", 170_000)).single()
        assertTrue(outcome is AttributionOutcome.IncompatibleTarget)
        assertTrue(ledger.getAll().isEmpty())
    }
}
