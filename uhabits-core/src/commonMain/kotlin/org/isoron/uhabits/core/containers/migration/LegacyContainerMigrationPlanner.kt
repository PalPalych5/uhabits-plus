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

package org.isoron.uhabits.core.containers.migration

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.OrganizationChangeRecord
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.models.PaletteColor

object LegacyContainerMigrationPlanner {

    fun plan(
        inventory: RawLegacyInventory,
        cutoverTimestamp: Long,
        datasetUuid: String = "dataset-" + Sha256.hexDigest(inventory.snapshotSha256).take(16),
        mode: String = "CONTAINER_LOCAL",
    ): MigrationPlan {
        val issues = mutableListOf<MigrationIssue>()

        // 1. Validate Block UUIDs
        for (block in inventory.blocks) {
            if (block.uuid.isNullOrBlank()) {
                issues.add(MigrationIssue.BlankOrMissingBlockUuid(block.id, block.name))
            }
        }
        val blockUuids = inventory.blocks.mapNotNull { it.uuid?.takeIf { u -> u.isNotBlank() } }
        val duplicateBlockUuids = blockUuids.groupingBy { it }.eachCount().filter { it.value > 1 }
        for ((uuid, _) in duplicateBlockUuids) {
            val matchingIds = inventory.blocks.filter { it.uuid == uuid }.map { it.id }
            issues.add(MigrationIssue.DuplicateBlockUuid(uuid, matchingIds))
        }

        // 2. Validate Habit UUIDs
        for (habit in inventory.habits) {
            if (habit.uuid.isNullOrBlank()) {
                issues.add(MigrationIssue.BlankOrMissingHabitUuid(habit.id, habit.name))
            }
        }
        val habitUuids = inventory.habits.mapNotNull { it.uuid?.takeIf { u -> u.isNotBlank() } }
        val duplicateHabitUuids = habitUuids.groupingBy { it }.eachCount().filter { it.value > 1 }
        for ((uuid, _) in duplicateHabitUuids) {
            val matchingIds = inventory.habits.filter { it.uuid == uuid }.map { it.id }
            issues.add(MigrationIssue.DuplicateHabitUuid(uuid, matchingIds))
        }

        val blocksById = inventory.blocks.associateBy { it.id }

        // 3. Plan Containers and LegacyBlockMap
        // Sort blocks deterministically by position, then id
        val sortedBlocks = inventory.blocks.sortedWith(compareBy({ it.position }, { it.id }))
        val containers = mutableListOf<Container>()
        val legacyBlockMaps = mutableListOf<LegacyBlockMapRecord>()

        for ((index, block) in sortedBlocks.withIndex()) {
            val uuid = block.uuid ?: "missing-block-${block.id}"
            val containerId = ContainerId(uuid)
            containers.add(
                Container(
                    id = containerId,
                    parentId = null,
                    name = block.name,
                    color = PaletteColor(block.color),
                    icon = block.icon,
                    siblingOrder = index,
                    isArchived = block.isArchived,
                    createdAt = null,
                    updatedAt = block.updatedAt,
                    deletedAt = block.deletedAt,
                    revision = OrganizationRevision(1L),
                )
            )

            if (!block.uuid.isNullOrBlank()) {
                legacyBlockMaps.add(
                    LegacyBlockMapRecord(
                        legacyBlockId = block.id,
                        legacyBlockUuid = block.uuid,
                        containerId = containerId,
                    )
                )
            }
        }

        // 4. Plan Habit Placements
        val extensionsByHabitId = inventory.extensions.associateBy { it.habitId }

        data class HabitPlacementDraft(
            val habit: RawLegacyHabit,
            val containerId: ContainerId?,
            val origin: PlacementOrigin,
        )

        val drafts = mutableListOf<HabitPlacementDraft>()

        for (habit in inventory.habits) {
            val ext = extensionsByHabitId[habit.id]
            if (ext == null) {
                // Missing extension (e.g. tombstoned habit or legacy anomaly) -> unknown legacy
                drafts.add(
                    HabitPlacementDraft(
                        habit = habit,
                        containerId = null,
                        origin = PlacementOrigin.UNKNOWN_LEGACY,
                    )
                )
            } else if (ext.blockId == null) {
                // Known unassigned
                drafts.add(
                    HabitPlacementDraft(
                        habit = habit,
                        containerId = null,
                        origin = PlacementOrigin.MIGRATION_SNAPSHOT,
                    )
                )
            } else {
                val block = blocksById[ext.blockId]
                if (block == null) {
                    issues.add(
                        MigrationIssue.MissingReferencedBlock(
                            habitId = habit.id,
                            habitUuid = habit.uuid,
                            blockId = ext.blockId,
                        )
                    )
                    // Place as unassigned draft for planning completeness
                    drafts.add(
                        HabitPlacementDraft(
                            habit = habit,
                            containerId = null,
                            origin = PlacementOrigin.UNKNOWN_LEGACY,
                        )
                    )
                } else {
                    if (block.deletedAt != null && habit.deletedAt == null) {
                        issues.add(
                            MigrationIssue.ActiveHabitReferencingTombstonedBlock(
                                habitId = habit.id,
                                habitUuid = habit.uuid,
                                blockId = block.id,
                                blockUuid = block.uuid,
                            )
                        )
                    }

                    val cId = ContainerId(block.uuid ?: "missing-block-${block.id}")
                    drafts.add(
                        HabitPlacementDraft(
                            habit = habit,
                            containerId = cId,
                            origin = PlacementOrigin.MIGRATION_SNAPSHOT,
                        )
                    )
                }
            }
        }

        // 5. Compute dense localOrder for habits grouped by containerId
        val placements = mutableListOf<HabitPlacement>()
        val draftsByContainer = drafts.groupBy { it.containerId }

        for ((containerId, habitDrafts) in draftsByContainer) {
            // Sort by habit global position, then id
            val sortedDrafts = habitDrafts.sortedWith(compareBy({ it.habit.position }, { it.habit.id }))
            for ((localIdx, draft) in sortedDrafts.withIndex()) {
                val habitRef = HabitRef(draft.habit.uuid ?: "missing-habit-${draft.habit.id}")
                placements.add(
                    HabitPlacement(
                        habit = habitRef,
                        containerId = containerId,
                        localOrder = localIdx,
                        origin = draft.origin,
                        revision = OrganizationRevision(1L),
                    )
                )
            }
        }

        // 6. OrganizationChanges baseline record
        val baselineRecord = OrganizationChangeRecord(
            revision = OrganizationRevision(1L),
            opUuid = "baseline-cutover",
            recordedAt = cutoverTimestamp,
            operationType = "BASELINE",
            origin = "MIGRATION",
            commandPayload = "SNAPSHOT_SHA256:${inventory.snapshotSha256}",
        )

        // 7. OrganizationStateRecord
        val organizationState = OrganizationStateRecord(
            datasetUuid = datasetUuid,
            foundationVersion = 1,
            mode = mode,
            cutoverRevision = OrganizationRevision(1L),
            currentRevision = OrganizationRevision(1L),
            cutoverAt = cutoverTimestamp,
            sourceSchemaVersion = 29,
            sourceSnapshotSha256 = inventory.snapshotSha256,
        )

        return MigrationPlan(
            containers = containers,
            placements = placements,
            legacyBlockMaps = legacyBlockMaps,
            organizationState = organizationState,
            baselineRecord = baselineRecord,
            issues = issues,
            sourceSnapshotSha256 = inventory.snapshotSha256,
        )
    }
}
