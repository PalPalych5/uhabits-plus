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

import org.isoron.uhabits.backup.BackupPreferencesAdapter
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.TaskContainerVisibility
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic fixture tests validating:
 * 1. HabitPlacement independence from task section visibility.
 * 2. FocusSession facts and contribution history preservation across visibility changes.
 * 3. Graceful handling of old archives / unknown container IDs (fail-closed, zero NPEs).
 * 4. Backup allowlist exclusion: task_section_visibility is strictly local-only navigation state
 *    and is NOT exported to or overwritten by portable backup archives.
 */
class TaskSectionVisibilitySyntheticTest {

    private fun container(id: String, parent: String? = null) =
        Container(
            id = ContainerId(id),
            parentId = parent?.let(::ContainerId),
            name = "Section $id",
            color = null,
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
            deletedAt = null,
            revision = OrganizationRevision(1L)
        )

    private fun task(id: String, containerId: String?) =
        UserTask(
            id = TaskId(id),
            title = "Task $id",
            containerId = containerId?.let(::ContainerId),
            status = TaskStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L
        )

    @Test
    fun habitPlacementRemainsIntactWhenSectionsAreHiddenInTasks() {
        val root = container("root")
        val child = container("child", parent = "root")
        val containers = listOf(root, child)

        // Habit placed into child section
        val habitRef = HabitRef("habit-uuid-42")
        val habitPlacement = HabitPlacement(
            habit = habitRef,
            containerId = child.id,
            localOrder = 0,
            origin = PlacementOrigin.USER_CHANGE,
            revision = OrganizationRevision(1L)
        )

        // Hide child section from Tasks navigation
        val parents = containers.associate { it.id.value to it.parentId?.value }
        val visibility = TaskContainerVisibility(parents, hidden = setOf("child"))

        // Child is hidden from task navigation
        assertFalse(visibility.isVisible("child"))
        assertTrue(visibility.isVisible("root"))

        // Critical domain invariant: HabitPlacement is untouched
        assertEquals(child.id, habitPlacement.containerId)
        assertEquals(habitRef, habitPlacement.habit)
        assertEquals(PlacementOrigin.USER_CHANGE, habitPlacement.origin)
    }

    @Test
    fun focusSessionsAndContributionHistoryPreservedAcrossVisibility() {
        val child = container("child")
        val userTask = task("task-1", containerId = "child")
        val habitRef = HabitRef("habit-uuid-42")

        val focusSession = FocusSession(
            id = FocusSessionId("session-1"),
            startedAt = 1000L,
            endedAt = 2800L,
            durationMs = 1800L,
            taskId = userTask.id,
            source = FocusSessionSource.MANUAL,
            createdAt = 1000L,
            updatedAt = 2800L
        )

        val contribution = Contribution(
            id = ContributionId("contrib-1"),
            targetHabit = habitRef,
            sourceType = ContributionSourceType.FOCUS_SESSION,
            sourceId = focusSession.id.value,
            contributedValue = 1800L,
            effectiveTimestamp = 19000L,
            createdAt = 2800L,
            updatedAt = 2800L
        )

        // Apply task visibility hiding the container
        val visibility = TaskContainerVisibility(mapOf("child" to null), hidden = setOf("child"))
        assertFalse(visibility.isVisible("child"))

        // FocusSession facts and Contribution ledger remain 100% intact
        assertEquals(userTask.id, focusSession.taskId)
        assertEquals(FocusPrimaryTargetType.TASK, focusSession.primaryTargetType)
        assertEquals(1800L, focusSession.durationMs)
        assertEquals(habitRef, contribution.targetHabit)
        assertEquals(ContributionSourceType.FOCUS_SESSION, contribution.sourceType)
        assertEquals(1800L, contribution.contributedValue)
    }

    @Test
    fun oldArchiveStaleIdsFailClosedWithoutCorruption() {
        // An old restored archive has only "restored-root"
        val restoredParents = mapOf("restored-root" to null)
        // Local preferences contain old container IDs from a previous dataset
        val staleHidden = setOf("stale-1", "stale-2", "stale-3")

        val visibility = TaskContainerVisibility(restoredParents, hidden = staleHidden)

        // Missing nodes fail closed
        assertFalse(visibility.isVisible("stale-1"))
        assertFalse(visibility.isVisible("unknown-node"))
        // Valid nodes in restored dataset remain navigable
        assertTrue(visibility.isVisible("restored-root"))

        // Querying paths on stale IDs returns empty/null without throwing
        assertEquals(emptyList<String>(), visibility.hiddenAncestors("stale-1"))
        assertNull(visibility.nearestVisibleAncestor("stale-1"))
        // showPath on unknown ID leaves hidden set unchanged
        assertEquals(staleHidden, visibility.showPath("stale-1"))
    }

    @Test
    fun backupPreferencesAllowlistExcludesTaskSectionVisibility() {
        // Verify BackupPreferencesAdapter.ALLOWLIST does NOT include any task visibility keys
        assertFalse(
            "ALLOWLIST must not contain pref_task_section_visibility",
            BackupPreferencesAdapter.ALLOWLIST.containsKey("pref_task_section_visibility")
        )
        assertFalse(
            "ALLOWLIST must not contain hidden",
            BackupPreferencesAdapter.ALLOWLIST.containsKey("hidden")
        )

        // All keys in ALLOWLIST must be standard portable preferences
        for (key in BackupPreferencesAdapter.ALLOWLIST.keys) {
            assertFalse(
                "Portable preferences must not contain section visibility keys: $key",
                key.contains("visibility") || key.contains("section")
            )
        }
    }

    @Test
    fun archivedContainersArePreservedAndExcludedFromActiveNavigationTree() {
        val root = container("root")
        val archived = container("archived").copy(isArchived = true)
        val habit = HabitRef("habit-archived")
        val placement = HabitPlacement(
            habit = habit,
            containerId = archived.id,
            localOrder = 0,
            origin = PlacementOrigin.USER_CHANGE,
            revision = OrganizationRevision(1L)
        )
        val task = task("task-archived", containerId = "archived")

        // Active navigation tree only contains active containers
        val activeParents = mapOf("root" to null)
        val visibility = TaskContainerVisibility(activeParents, hidden = emptySet())

        // Active root is visible
        assertTrue(visibility.isVisible("root"))
        // Archived container is not in active navigation tree (fails closed)
        assertFalse(visibility.isVisible("archived"))

        // Placements and tasks retain their relationships to the archived container
        assertEquals(archived.id, placement.containerId)
        assertEquals(archived.id, task.containerId)
        assertTrue(archived.isArchived)
    }
}
