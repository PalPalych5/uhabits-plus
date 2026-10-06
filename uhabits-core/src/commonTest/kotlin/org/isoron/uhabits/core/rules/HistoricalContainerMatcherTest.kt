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
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import kotlin.test.*

class HistoricalContainerMatcherTest {

    private fun createRule(
        containerId: String = "c-root",
        includeDescendants: Boolean = true,
        sourceKind: SourceRuleSourceKind = SourceRuleSourceKind.FOCUS_SESSION,
    ) = SourceRule(
        id = SourceRuleId("rule-1"),
        sourceKind = sourceKind,
        sourceContainerId = ContainerId(containerId),
        includeDescendants = includeDescendants,
        targetHabit = HabitRef("h-1"),
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private fun createSession(
        containerId: String? = null,
        organizationContext: String? = null,
    ) = FocusSession(
        id = FocusSessionId("sess-1"),
        startedAt = 1000L,
        endedAt = 3000L,
        durationMs = 2000L,
        source = FocusSessionSource.TIMER,
        containerId = containerId?.let(::ContainerId),
        organizationContext = organizationContext,
        createdAt = 1000L,
        updatedAt = 3000L,
    )

    @Test
    fun testDirectContainerMatchWithoutDescendants() {
        val rule = createRule(containerId = "c-work", includeDescendants = false)
        val sessionMatching = createSession(containerId = "c-work")
        val sessionOther = createSession(containerId = "c-personal")

        assertTrue(HistoricalContainerMatcher.matches(rule, sessionMatching))
        assertFalse(HistoricalContainerMatcher.matches(rule, sessionOther))
    }

    @Test
    fun testDirectContainerMatchWithDescendants() {
        val rule = createRule(containerId = "c-work", includeDescendants = true)
        val sessionMatching = createSession(containerId = "c-work")

        assertTrue(HistoricalContainerMatcher.matches(rule, sessionMatching))
    }

    @Test
    fun testSubtreeMatchingFromStructuredNodePath() {
        val ruleRoot = createRule(containerId = "c-root", includeDescendants = true)
        val ruleDirectOnly = createRule(containerId = "c-root", includeDescendants = false)

        val structuredContext = """
            {
              "path": [
                {"id": "c-root", "name": "Work"},
                {"id": "c-project", "name": "App"},
                {"id": "c-sub", "name": "Feature"}
              ]
            }
        """.trimIndent()

        val sessionInSub = createSession(
            containerId = "c-sub",
            organizationContext = structuredContext,
        )

        // Rule with includeDescendants = true matches ancestor c-root
        assertTrue(HistoricalContainerMatcher.matches(ruleRoot, sessionInSub))

        // Rule with includeDescendants = false does NOT match descendant c-sub
        assertFalse(HistoricalContainerMatcher.matches(ruleDirectOnly, sessionInSub))

        // Rule targeting c-project with includeDescendants = true matches
        val ruleProject = createRule(containerId = "c-project", includeDescendants = true)
        assertTrue(HistoricalContainerMatcher.matches(ruleProject, sessionInSub))

        // Rule targeting unreferenced container does not match
        val ruleUnrelated = createRule(containerId = "c-unrelated", includeDescendants = true)
        assertFalse(HistoricalContainerMatcher.matches(ruleUnrelated, sessionInSub))
    }

    @Test
    fun testSubtreeMatchingFromStringPath() {
        val rule = createRule(containerId = "c-category", includeDescendants = true)
        val stringPathContext = """{"path": ["c-category", "c-subcategory"]}"""

        val session = createSession(
            containerId = "c-subcategory",
            organizationContext = stringPathContext,
        )

        assertTrue(HistoricalContainerMatcher.matches(rule, session))
    }

    @Test
    fun testImmunityToSubsequentMoves() {
        // Even if containers are rearranged or deleted in live tree later,
        // historical context preserved on the session fact is authoritative.
        val historicalContext = """{"path": [{"id": "c-original-parent"}]}"""
        val session = createSession(
            containerId = "c-child",
            organizationContext = historicalContext,
        )

        val ruleOriginalParent = createRule(containerId = "c-original-parent", includeDescendants = true)
        val ruleNewParent = createRule(containerId = "c-new-parent", includeDescendants = true)

        assertTrue(HistoricalContainerMatcher.matches(ruleOriginalParent, session))
        assertFalse(HistoricalContainerMatcher.matches(ruleNewParent, session))
    }

    @Test
    fun testNullOrEmptyContextWithoutDirectMatchReturnsFalse() {
        val rule = createRule(containerId = "c-parent", includeDescendants = true)
        val sessionNullContext = createSession(containerId = "c-child", organizationContext = null)
        val sessionEmptyContext = createSession(containerId = "c-child", organizationContext = "")

        assertFalse(HistoricalContainerMatcher.matches(rule, sessionNullContext))
        assertFalse(HistoricalContainerMatcher.matches(rule, sessionEmptyContext))
    }
}
