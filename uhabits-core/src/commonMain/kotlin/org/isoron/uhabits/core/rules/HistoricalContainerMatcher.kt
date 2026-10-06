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

import org.isoron.uhabits.core.focus.FocusSession

/**
 * Historical container scope matcher for attribution rules.
 *
 * Enforces historical invariance: attribution must evaluate container placement
 * frozen at the time work occurred ([FocusSession.containerId] and [FocusSession.organizationContext]),
 * never mutating or reinterpreting placement based on later container hierarchy changes.
 */
object HistoricalContainerMatcher {

    /**
     * Extracts all historical container identifiers preserved in [organizationContextJson].
     * Supports structured node paths ({"path":[{"id":"c1"}, ...]}) and
     * string paths ({"path":["c1", ...]}).
     */
    fun extractHistoricalContainerIds(organizationContextJson: String?): Set<String> {
        if (organizationContextJson.isNullOrBlank()) return emptySet()
        val ids = mutableSetOf<String>()

        // 1. Matches objects with "id": "..." in path
        val idRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        for (match in idRegex.findAll(organizationContextJson)) {
            ids.add(match.groupValues[1])
        }

        // 2. Matches array of strings: "path": ["c1", "c2"]
        val pathBlockRegex = Regex("\"path\"\\s*:\\s*\\[([^\\]]*)\\]")
        val pathBlock = pathBlockRegex.find(organizationContextJson)?.groupValues?.get(1)
        if (pathBlock != null) {
            val stringItemRegex = Regex("\"([^\"]+)\"")
            for (match in stringItemRegex.findAll(pathBlock)) {
                val item = match.groupValues[1]
                if (item != "id" && item != "name") {
                    ids.add(item)
                }
            }
        }

        return ids
    }

    /**
     * Determines whether [session] historically fell within the scope of [rule].
     */
    fun matches(rule: SourceRule, session: FocusSession): Boolean {
        if (rule.sourceKind != SourceRuleSourceKind.FOCUS_SESSION) return false

        val ruleContainerId = rule.sourceContainerId.value

        // Direct match with session's immediate container
        if (session.containerId?.value == ruleContainerId) {
            return true
        }

        // If rule does not include descendants, immediate match was required
        if (!rule.includeDescendants) {
            return false
        }

        // Check historical organization path
        val historicalIds = extractHistoricalContainerIds(session.organizationContext)
        return ruleContainerId in historicalIds
    }
}
