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

package org.isoron.uhabits.core.containers.statistics

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.focus.FocusSession

data class HistoricalPathNode(
    val id: ContainerId,
    val name: String? = null,
)

data class SessionHistoricalMatch(
    val isDirect: Boolean,
    val isDescendant: Boolean,
    val immediateChildBranch: HistoricalPathNode?,
)

object HistoricalPathParser {

    /**
     * Parses frozen historical organization path from [organizationContextJson].
     * Preserves the hierarchical order from root down to the container where the work occurred.
     */
    fun parsePath(organizationContextJson: String?): List<HistoricalPathNode> {
        if (organizationContextJson.isNullOrBlank()) return emptyList()
        val pathBlockRegex = Regex("\"path\"\\s*:\\s*\\[([^\\]]*)\\]")
        val pathBlock = pathBlockRegex.find(organizationContextJson)?.groupValues?.get(1) ?: return emptyList()

        val nodes = mutableListOf<HistoricalPathNode>()
        // 1. Matches object nodes: {"id": "...", "name": "..."}
        val objectRegex = Regex("\\{([^}]+)\\}")
        val objectMatches = objectRegex.findAll(pathBlock).toList()
        if (objectMatches.isNotEmpty()) {
            val idRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            for (match in objectMatches) {
                val objContent = match.groupValues[1]
                val id = idRegex.find(objContent)?.groupValues?.get(1)
                val name = nameRegex.find(objContent)?.groupValues?.get(1)
                if (id != null) {
                    nodes.add(HistoricalPathNode(ContainerId(id), name))
                }
            }
            return nodes
        }

        // 2. Fallback: string items ["id1", "id2"]
        val stringItemRegex = Regex("\"([^\"]+)\"")
        for (match in stringItemRegex.findAll(pathBlock)) {
            val item = match.groupValues[1]
            if (item != "id" && item != "name") {
                nodes.add(HistoricalPathNode(ContainerId(item), null))
            }
        }
        return nodes
    }

    /**
     * Determines whether [session] historically belongs to [targetContainer] as a direct
     * or descendant fact, and if descendant, identifies the immediate child container branch under [targetContainer].
     *
     * Invariance: If frozen organizationContext is present, uses the historical path frozen
     * at recording time, ignoring any later container moves or reorganizations.
     * If organizationContext is absent, falls back to session.containerId and current tree structure.
     */
    fun matchSession(
        session: FocusSession,
        targetContainer: ContainerId,
        currentSubtreeIds: Set<ContainerId>,
        containerQueries: ContainerQueries? = null,
    ): SessionHistoricalMatch? {
        val path = parsePath(session.organizationContext)
        if (path.isNotEmpty()) {
            val targetIdx = path.indexOfFirst { it.id == targetContainer }
            if (targetIdx == -1) {
                return null
            }
            val isDirect = (targetIdx == path.lastIndex)
            val isDescendant = (targetIdx < path.lastIndex)
            val immediateChild = if (isDescendant) path[targetIdx + 1] else null
            return SessionHistoricalMatch(
                isDirect = isDirect,
                isDescendant = isDescendant,
                immediateChildBranch = immediateChild,
            )
        }

        // Fallback for sessions recorded without path metadata:
        val sessionContainer = session.containerId ?: return null
        if (sessionContainer == targetContainer) {
            return SessionHistoricalMatch(
                isDirect = true,
                isDescendant = false,
                immediateChildBranch = null,
            )
        }
        if (sessionContainer in currentSubtreeIds) {
            var childNode: HistoricalPathNode? = null
            if (containerQueries != null) {
                val children = containerQueries.children(targetContainer, includeArchived = true)
                for (ch in children) {
                    if (ch.id == sessionContainer || sessionContainer in containerQueries.subtreeIds(ch.id)) {
                        childNode = HistoricalPathNode(ch.id, ch.name)
                        break
                    }
                }
            }
            return SessionHistoricalMatch(
                isDirect = false,
                isDescendant = true,
                immediateChildBranch = childNode,
            )
        }
        return null
    }
}
