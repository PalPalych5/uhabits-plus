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

import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.focus.FocusSession

/**
 * Metadata descriptor for validating a target habit's suitability for time attribution.
 */
data class TargetHabitInfo(
    val isNumerical: Boolean,
    val unit: String,
    val name: String? = null,
)

class AmbiguousAttributionException(
    message: String,
    val conflictingRules: List<SourceRuleId> = emptyList(),
) : IllegalStateException(message)

class TargetHabitNotFoundException(
    message: String,
    val habitRef: HabitRef,
) : IllegalArgumentException(message)

sealed class AttributionOutcome {
    data class Attributed(val contribution: Contribution, val wasInserted: Boolean) : AttributionOutcome()
    data class Ineligible(val ruleId: SourceRuleId, val reason: String) : AttributionOutcome()
    data class IncompatibleTarget(val habitRef: HabitRef, val reason: String) : AttributionOutcome()
    data class Ambiguous(val conflictingRules: List<SourceRuleId>, val message: String) : AttributionOutcome()
}

data class ReconcileReport(
    val contributionsCreated: Int,
    val contributionsAlreadyPresent: Int,
    val contributionsRevoked: Int,
    val skippedIncompatible: Int = 0,
)

/**
 * Core attribution engine for SourceRule v1.
 *
 * Projects finalized [FocusSession] facts into the [Contribution] ledger according to
 * active [SourceRule] definitions.
 *
 * Guarantees:
 * - Deterministic contribution identities: same session + target habit generates identical ContributionId.
 * - Idempotency: repeated evaluation does not create duplicate ledger records.
 * - Historical matching: evaluates container placement frozen on the session fact.
 * - Temporal bounds: sessions ending before a rule's [SourceRule.effectiveFrom] are not attributed by that rule.
 * - Rule deduplication & conflict detection: overlapping rules targeting the same habit are deduplicated
 *   if harmonious or rejected if conflicting.
 * - Historical attributions survive rule disablement/removal and explicit corrections survive retries.
 * - Reversal requires an explicit historical correction operation.
 */
class SourceRuleAttributionService(
    private val ruleRepository: SourceRuleRepository,
    private val contributionRepository: ContributionRepository,
    private val habitResolver: ((HabitRef) -> TargetHabitInfo?)? = null,
) {

    /**
     * Attributes a finalized [session] against all active rules.
     *
     * @return list of [AttributionOutcome] for each rule evaluation.
     */
    fun attributeSession(session: FocusSession, now: Long = session.endedAt): List<AttributionOutcome> {
        val activeRules = ruleRepository.getActiveRules()
        if (activeRules.isEmpty()) return emptyList()

        // 1. Filter rules whose historical scope matches the session
        val matchingRules = activeRules.filter { rule ->
            HistoricalContainerMatcher.matches(rule, session)
        }
        if (matchingRules.isEmpty()) return emptyList()

        // 2. Group matching rules by target habit to detect overlaps / conflicts
        val rulesByTarget = matchingRules.groupBy { it.targetHabit }
        val outcomes = mutableListOf<AttributionOutcome>()

        for ((targetHabit, rulesForTarget) in rulesByTarget) {
            // Existing attribution is historical, including an explicit correction tombstone.
            // Do not select a new winning rule or reinterpret the value from today's configuration.
            val historical = contributionRepository.getBySource(
                ContributionSourceType.FOCUS_SESSION, session.id.value, includeRemoved = true,
            ).firstOrNull { it.targetHabit == targetHabit }
            if (historical != null) {
                if (historical.isActive) {
                    outcomes.add(AttributionOutcome.Attributed(historical, wasInserted = false))
                }
                continue
            }
            // Check temporal eligibility
            val eligibleRules = rulesForTarget.filter { session.endedAt >= it.effectiveFrom }
            if (eligibleRules.isEmpty()) {
                for (r in rulesForTarget) {
                    outcomes.add(
                        AttributionOutcome.Ineligible(
                            r.id,
                            "Session ended at ${session.endedAt} before rule effectiveFrom ${r.effectiveFrom}"
                        )
                    )
                }
                continue
            }

            // Check for configuration conflicts among overlapping rules
            val distinctMappings = eligibleRules.map { it.valueMapping }.distinct()
            if (distinctMappings.size > 1) {
                val conflictIds = eligibleRules.map { it.id }
                throw AmbiguousAttributionException(
                    "Conflicting value mappings among overlapping rules for habit ${targetHabit.uuid}: $distinctMappings",
                    conflictIds,
                )
            }

            // Validate target habit compatibility if resolver is configured
            if (habitResolver != null) {
                val habitInfo = habitResolver.invoke(targetHabit)
                if (habitInfo == null) {
                    throw TargetHabitNotFoundException("Target habit ${targetHabit.uuid} not found", targetHabit)
                }
                try {
                    HabitTimeValueConverter.validateTargetHabit(
                        isNumerical = habitInfo.isNumerical,
                        unit = habitInfo.unit,
                        name = habitInfo.name,
                        uuid = targetHabit.uuid,
                    )
                } catch (e: IncompatibleTargetHabitException) {
                    outcomes.add(AttributionOutcome.IncompatibleTarget(targetHabit, e.message ?: "Incompatible target"))
                    continue
                }
            }

            // Deduplicate: pick winning rule deterministically (most specific container match, then by id)
            val winningRule = eligibleRules.minWithOrNull(
                compareByDescending<SourceRule> { it.sourceContainerId == session.containerId }
                    .thenBy { it.id.value }
            ) ?: eligibleRules.first()

            val habitValue = HabitTimeValueConverter.convertFocusDurationToHabitValue(session.durationMs)
            val contributionId = deterministicContributionId(session.id.value, targetHabit.uuid)

            val existingContribution = contributionRepository.getById(contributionId)
            if (existingContribution != null) {
                if (existingContribution.isActive &&
                    existingContribution.sourceRuleId == winningRule.id &&
                    existingContribution.contributedValue == habitValue
                ) {
                    outcomes.add(AttributionOutcome.Attributed(existingContribution, wasInserted = false))
                    continue
                }
            }

            val contribution = Contribution(
                id = contributionId,
                targetHabit = targetHabit,
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = session.id.value,
                contributedValue = habitValue,
                effectiveTimestamp = session.endedAt,
                createdAt = existingContribution?.createdAt ?: now,
                updatedAt = now,
                sourceRuleId = winningRule.id,
            )

            val recordResult = contributionRepository.record(contribution)
            val inserted = recordResult == ContributionRecordResult.INSERTED
            val finalContribution = if (inserted) contribution else (contributionRepository.getById(contributionId) ?: contribution)
            outcomes.add(AttributionOutcome.Attributed(finalContribution, wasInserted = inserted))
        }

        return outcomes
    }

    /**
     * Explicit historical correction: revokes ALL active contributions derived from [ruleId].
     * Never call as a side effect of disabling/removing a rule. For one session/target,
     * use [ContributionService.revokeFocusSessionContribution] instead.
     */
    fun revokeContributionsForRule(ruleId: SourceRuleId, now: Long): Int {
        val activeContributions = contributionRepository.getByRule(ruleId, includeRemoved = false)
        var count = 0
        for (c in activeContributions) {
            val result = contributionRepository.removeById(c.id, now)
            if (result == ContributionRemovalResult.REMOVED) {
                count++
            }
        }
        return count
    }

    /**
     * Adds missing attributions against currently active rules. Preserves historical
     * contributions (including correction tombstones), regardless of current rule state.
     */
    fun reconcileAll(sessions: List<FocusSession>, now: Long): ReconcileReport {
        var created = 0
        var alreadyPresent = 0
        var incompatible = 0

        // Rule state controls new attribution; it never invalidates existing ledger facts.
        for (session in sessions) {
            val outcomes = attributeSession(session, now)
            for (outcome in outcomes) {
                when (outcome) {
                    is AttributionOutcome.Attributed -> {
                        if (outcome.wasInserted) created++ else alreadyPresent++
                    }
                    is AttributionOutcome.IncompatibleTarget -> incompatible++
                    else -> Unit
                }
            }
        }

        return ReconcileReport(
            contributionsCreated = created,
            contributionsAlreadyPresent = alreadyPresent,
            contributionsRevoked = 0,
            skippedIncompatible = incompatible,
        )
    }

    companion object {
        fun deterministicContributionId(sessionId: String, targetHabitUuid: String): ContributionId =
            ContributionId("contrib:focus:${sessionId}:${targetHabitUuid}")
    }
}
