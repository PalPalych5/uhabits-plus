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

package org.isoron.uhabits.core.containers.sqlite

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerPath
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.ContainerSearchHit
import org.isoron.uhabits.core.containers.HabitPlacement
import org.isoron.uhabits.core.containers.HabitPlacementQueries
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.HistoricalLocation
import org.isoron.uhabits.core.containers.OrganizationChangeRecord
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.OrganizationStateSnapshot
import org.isoron.uhabits.core.containers.OrganizationStore
import org.isoron.uhabits.core.containers.OrganizationTransaction
import org.isoron.uhabits.core.containers.PlacementOrigin
import org.isoron.uhabits.core.containers.TreePolicy
import org.isoron.uhabits.core.containers.UnitOfWork
import org.isoron.uhabits.core.models.PaletteColor

class SQLiteOrganizationStore(
    private val db: Database,
) : OrganizationStore, OrganizationTransaction, ContainerQueries, HabitPlacementQueries, UnitOfWork {

    private var transactionDepth = 0

    init {
        OrganizationSchema.enableForeignKeys(db)
    }

    // ========================================================================
    // Seeding Helper
    // ========================================================================

    fun seedBaseline(
        revision: OrganizationRevision = OrganizationRevision(1L),
        recordedAt: Long = 0L,
        datasetUuid: String = "test-dataset",
        containersList: List<Container> = emptyList(),
        placementsList: List<HabitPlacement> = emptyList(),
    ) {
        executeInTransaction { tx ->
            val stmt = db.prepareStatement(
                """
                INSERT OR REPLACE INTO OrganizationState (
                    id, dataset_uuid, foundation_version, mode, cutover_revision,
                    current_revision, cutover_at, source_schema_version, source_snapshot_sha256
                ) VALUES (1, ?, 1, 'CONTAINER_LOCAL', ?, ?, ?, 29, '')
                """.trimIndent()
            )
            stmt.bindText(1, datasetUuid)
            stmt.bindLong(2, revision.value)
            stmt.bindLong(3, revision.value)
            stmt.bindLong(4, recordedAt)
            stmt.step()
            stmt.finalize()

            val baselineRecord = OrganizationChangeRecord(
                revision = revision,
                opUuid = "baseline-cutover",
                recordedAt = recordedAt,
                operationType = "BASELINE",
                origin = "BASELINE",
                commandPayload = "BASELINE_SNAPSHOT",
            )
            tx.recordChange(baselineRecord)

            for (c in containersList) {
                tx.saveContainer(c)
                tx.recordContainerHistory(c, revision)
            }
            for (p in placementsList) {
                tx.saveHabitPlacement(p)
                tx.recordHabitPlacementHistory(p, revision)
            }
        }
    }

    // ========================================================================
    // Entity Local ID Resolution
    // ========================================================================

    fun getLocalContainerId(id: ContainerId): Long? {
        val stmt = db.prepareStatement("SELECT id FROM Containers WHERE uuid = ?")
        stmt.bindText(1, id.value)
        val result = if (stmt.step() == StepResult.ROW) stmt.getLong(0) else null
        stmt.finalize()
        return result
    }

    fun getLocalHabitId(habit: HabitRef): Long? {
        val stmt = db.prepareStatement("SELECT id FROM Habits WHERE uuid = ?")
        stmt.bindText(1, habit.uuid)
        val result = if (stmt.step() == StepResult.ROW) stmt.getLong(0) else null
        stmt.finalize()
        return result
    }

    // ========================================================================
    // OrganizationStore & Transaction
    // ========================================================================

    override fun readState(): OrganizationStateSnapshot {
        val stmt = db.prepareStatement(
            "SELECT cutover_revision, current_revision, cutover_at FROM OrganizationState WHERE id = 1"
        )
        val result = if (stmt.step() == StepResult.ROW) {
            OrganizationStateSnapshot(
                cutoverRevision = OrganizationRevision(stmt.getLong(0)),
                currentRevision = OrganizationRevision(stmt.getLong(1)),
                cutoverAt = stmt.getLong(2),
            )
        } else {
            OrganizationStateSnapshot(
                cutoverRevision = OrganizationRevision(1L),
                currentRevision = OrganizationRevision(1L),
                cutoverAt = 0L,
            )
        }
        stmt.finalize()
        return result
    }

    override fun updateState(newRevision: OrganizationRevision) {
        val stmt = db.prepareStatement("UPDATE OrganizationState SET current_revision = ? WHERE id = 1")
        stmt.bindLong(1, newRevision.value)
        stmt.step()
        stmt.finalize()
    }

    override fun getContainer(id: ContainerId, includeDeleted: Boolean): Container? {
        val sql = """
            SELECT c.uuid, p.uuid AS parent_uuid, c.name, c.color, c.icon,
                   c.sibling_order, c.is_archived, c.created_at, c.updated_at, c.deleted_at, c.revision
            FROM Containers c
            LEFT JOIN Containers p ON c.parent_id = p.id
            WHERE c.uuid = ? ${if (!includeDeleted) "AND c.deleted_at IS NULL" else ""}
        """.trimIndent()
        val stmt = db.prepareStatement(sql)
        stmt.bindText(1, id.value)
        val result = if (stmt.step() == StepResult.ROW) readContainerRow(stmt) else null
        stmt.finalize()
        return result
    }

    override fun getAllContainers(includeDeleted: Boolean): List<Container> {
        val sql = """
            SELECT c.uuid, p.uuid AS parent_uuid, c.name, c.color, c.icon,
                   c.sibling_order, c.is_archived, c.created_at, c.updated_at, c.deleted_at, c.revision
            FROM Containers c
            LEFT JOIN Containers p ON c.parent_id = p.id
            ${if (!includeDeleted) "WHERE c.deleted_at IS NULL" else ""}
            ORDER BY c.sibling_order ASC, c.uuid ASC
        """.trimIndent()
        val stmt = db.prepareStatement(sql)
        val list = mutableListOf<Container>()
        while (stmt.step() == StepResult.ROW) {
            list.add(readContainerRow(stmt))
        }
        stmt.finalize()
        return list
    }

    override fun getHabitPlacement(habit: HabitRef): HabitPlacement? {
        val stmt = db.prepareStatement(
            """
            SELECT h.uuid, c.uuid, hp.local_order, hp.placement_origin, hp.revision
            FROM HabitPlacements hp
            JOIN Habits h ON hp.habit_id = h.id
            LEFT JOIN Containers c ON hp.container_id = c.id
            WHERE h.uuid = ?
            """.trimIndent()
        )
        stmt.bindText(1, habit.uuid)
        val result = if (stmt.step() == StepResult.ROW) readPlacementRow(stmt) else null
        stmt.finalize()
        return result
    }

    override fun getAllHabitPlacements(): List<HabitPlacement> {
        val stmt = db.prepareStatement(
            """
            SELECT h.uuid, c.uuid, hp.local_order, hp.placement_origin, hp.revision
            FROM HabitPlacements hp
            JOIN Habits h ON hp.habit_id = h.id
            LEFT JOIN Containers c ON hp.container_id = c.id
            ORDER BY hp.local_order ASC, h.uuid ASC
            """.trimIndent()
        )
        val list = mutableListOf<HabitPlacement>()
        while (stmt.step() == StepResult.ROW) {
            list.add(readPlacementRow(stmt))
        }
        stmt.finalize()
        return list
    }

    override fun getChangeByOpUuid(opUuid: String): OrganizationChangeRecord? {
        val stmt = db.prepareStatement(
            """
            SELECT revision, op_uuid, recorded_at, operation_type, origin, command_payload
            FROM OrganizationChanges WHERE op_uuid = ?
            """.trimIndent()
        )
        stmt.bindText(1, opUuid)
        val result = if (stmt.step() == StepResult.ROW) {
            OrganizationChangeRecord(
                revision = OrganizationRevision(stmt.getLong(0)),
                opUuid = stmt.getText(1),
                recordedAt = stmt.getLong(2),
                operationType = stmt.getText(3),
                origin = stmt.getText(4),
                commandPayload = stmt.getText(5),
            )
        } else {
            null
        }
        stmt.finalize()
        return result
    }

    override fun getContainerHistoryAtRevision(
        id: ContainerId,
        revision: OrganizationRevision,
    ): Container? {
        val stmt = db.prepareStatement(
            """
            SELECT c.uuid, p.uuid AS parent_uuid, ch.name, ch.color, ch.icon,
                   ch.sibling_order, ch.is_archived, ch.created_at, ch.updated_at, ch.deleted_at, ch.revision
            FROM ContainerHistory ch
            JOIN Containers c ON ch.container_id = c.id
            LEFT JOIN Containers p ON ch.parent_id = p.id
            WHERE c.uuid = ? AND ch.revision <= ?
            ORDER BY ch.revision DESC
            LIMIT 1
            """.trimIndent()
        )
        stmt.bindText(1, id.value)
        stmt.bindLong(2, revision.value)
        val result = if (stmt.step() == StepResult.ROW) readContainerRow(stmt) else null
        stmt.finalize()
        return result
    }

    override fun getHabitPlacementHistoryAtRevision(
        habit: HabitRef,
        revision: OrganizationRevision,
    ): HabitPlacement? {
        val stmt = db.prepareStatement(
            """
            SELECT h.uuid, c.uuid, hph.local_order, hph.placement_origin, hph.revision
            FROM HabitPlacementHistory hph
            JOIN Habits h ON hph.habit_id = h.id
            LEFT JOIN Containers c ON hph.container_id = c.id
            WHERE h.uuid = ? AND hph.revision <= ?
            ORDER BY hph.revision DESC
            LIMIT 1
            """.trimIndent()
        )
        stmt.bindText(1, habit.uuid)
        stmt.bindLong(2, revision.value)
        val result = if (stmt.step() == StepResult.ROW) readPlacementRow(stmt) else null
        stmt.finalize()
        return result
    }

    override fun saveContainer(container: Container) {
        val parentLocalId = container.parentId?.let {
            getLocalContainerId(it) ?: error("Parent container with UUID ${it.value} not found")
        }

        val existingId = getLocalContainerId(container.id)
        if (existingId == null) {
            val stmt = db.prepareStatement(
                """
                INSERT INTO Containers (
                    uuid, parent_id, name, color, icon, sibling_order,
                    is_archived, created_at, updated_at, deleted_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            stmt.bindText(1, container.id.value)
            if (parentLocalId != null) stmt.bindLong(2, parentLocalId) else stmt.bindNull(2)
            stmt.bindText(3, container.name)
            val color = container.color
            if (color != null) stmt.bindInt(4, color.paletteIndex) else stmt.bindNull(4)
            val icon = container.icon
            if (icon != null) stmt.bindText(5, icon) else stmt.bindNull(5)
            stmt.bindInt(6, container.siblingOrder)
            stmt.bindInt(7, if (container.isArchived) 1 else 0)
            val createdAt = container.createdAt
            if (createdAt != null) stmt.bindLong(8, createdAt) else stmt.bindNull(8)
            stmt.bindLong(9, container.updatedAt)
            val deletedAt = container.deletedAt
            if (deletedAt != null) stmt.bindLong(10, deletedAt) else stmt.bindNull(10)
            stmt.bindLong(11, container.revision.value)

            stmt.step()
            stmt.finalize()
        } else {
            val stmt = db.prepareStatement(
                """
                UPDATE Containers SET
                    parent_id = ?, name = ?, color = ?, icon = ?, sibling_order = ?,
                    is_archived = ?, created_at = ?, updated_at = ?, deleted_at = ?, revision = ?
                WHERE uuid = ?
                """.trimIndent()
            )
            if (parentLocalId != null) stmt.bindLong(1, parentLocalId) else stmt.bindNull(1)
            stmt.bindText(2, container.name)
            val color = container.color
            if (color != null) stmt.bindInt(3, color.paletteIndex) else stmt.bindNull(3)
            val icon = container.icon
            if (icon != null) stmt.bindText(4, icon) else stmt.bindNull(4)
            stmt.bindInt(5, container.siblingOrder)
            stmt.bindInt(6, if (container.isArchived) 1 else 0)
            val createdAt = container.createdAt
            if (createdAt != null) stmt.bindLong(7, createdAt) else stmt.bindNull(7)
            stmt.bindLong(8, container.updatedAt)
            val deletedAt = container.deletedAt
            if (deletedAt != null) stmt.bindLong(9, deletedAt) else stmt.bindNull(9)
            stmt.bindLong(10, container.revision.value)
            stmt.bindText(11, container.id.value)

            stmt.step()
            stmt.finalize()
        }
    }

    override fun saveHabitPlacement(placement: HabitPlacement) {
        val habitLocalId = getLocalHabitId(placement.habit)
            ?: error("Habit with UUID ${placement.habit.uuid} does not exist in Habits table")
        val containerLocalId = placement.containerId?.let {
            getLocalContainerId(it) ?: error("Container with UUID ${it.value} does not exist in Containers table")
        }

        val stmt = db.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitPlacements (
                habit_id, container_id, local_order, placement_origin, revision
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        )
        stmt.bindLong(1, habitLocalId)
        if (containerLocalId != null) stmt.bindLong(2, containerLocalId) else stmt.bindNull(2)
        stmt.bindInt(3, placement.localOrder)
        stmt.bindText(4, placement.origin.name)
        stmt.bindLong(5, placement.revision.value)

        stmt.step()
        stmt.finalize()
    }

    override fun recordChange(change: OrganizationChangeRecord) {
        val stmt = db.prepareStatement(
            """
            INSERT INTO OrganizationChanges (
                revision, op_uuid, recorded_at, operation_type, origin, command_payload
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        stmt.bindLong(1, change.revision.value)
        stmt.bindText(2, change.opUuid)
        stmt.bindLong(3, change.recordedAt)
        stmt.bindText(4, change.operationType)
        stmt.bindText(5, change.origin)
        stmt.bindText(6, change.commandPayload)

        stmt.step()
        stmt.finalize()
    }

    override fun recordContainerHistory(container: Container, revision: OrganizationRevision) {
        val containerLocalId = getLocalContainerId(container.id)
            ?: error("Cannot record history for non-existent container ${container.id.value}")
        val parentLocalId = container.parentId?.let {
            getLocalContainerId(it) ?: error("Parent container with UUID ${it.value} not found")
        }

        val stmt = db.prepareStatement(
            """
            INSERT OR REPLACE INTO ContainerHistory (
                container_id, revision, parent_id, name, color, icon,
                sibling_order, is_archived, created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        )
        stmt.bindLong(1, containerLocalId)
        stmt.bindLong(2, revision.value)
        if (parentLocalId != null) stmt.bindLong(3, parentLocalId) else stmt.bindNull(3)
        stmt.bindText(4, container.name)
        val color = container.color
        if (color != null) stmt.bindInt(5, color.paletteIndex) else stmt.bindNull(5)
        val icon = container.icon
        if (icon != null) stmt.bindText(6, icon) else stmt.bindNull(6)
        stmt.bindInt(7, container.siblingOrder)
        stmt.bindInt(8, if (container.isArchived) 1 else 0)
        val createdAt = container.createdAt
        if (createdAt != null) stmt.bindLong(9, createdAt) else stmt.bindNull(9)
        stmt.bindLong(10, container.updatedAt)
        val deletedAt = container.deletedAt
        if (deletedAt != null) stmt.bindLong(11, deletedAt) else stmt.bindNull(11)

        stmt.step()
        stmt.finalize()
    }

    override fun recordHabitPlacementHistory(
        placement: HabitPlacement,
        revision: OrganizationRevision,
    ) {
        val habitLocalId = getLocalHabitId(placement.habit)
            ?: error("Habit with UUID ${placement.habit.uuid} does not exist in Habits table")
        val containerLocalId = placement.containerId?.let {
            getLocalContainerId(it) ?: error("Container with UUID ${it.value} does not exist in Containers table")
        }

        val stmt = db.prepareStatement(
            """
            INSERT OR REPLACE INTO HabitPlacementHistory (
                habit_id, revision, container_id, local_order, placement_origin
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        )
        stmt.bindLong(1, habitLocalId)
        stmt.bindLong(2, revision.value)
        if (containerLocalId != null) stmt.bindLong(3, containerLocalId) else stmt.bindNull(3)
        stmt.bindInt(4, placement.localOrder)
        stmt.bindText(5, placement.origin.name)

        stmt.step()
        stmt.finalize()
    }

    // ========================================================================
    // ContainerQueries
    // ========================================================================

    override fun find(id: ContainerId, includeDeleted: Boolean): Container? =
        getContainer(id, includeDeleted)

    override fun roots(includeArchived: Boolean): List<Container> {
        val sql = """
            SELECT c.uuid, p.uuid AS parent_uuid, c.name, c.color, c.icon,
                   c.sibling_order, c.is_archived, c.created_at, c.updated_at, c.deleted_at, c.revision
            FROM Containers c
            LEFT JOIN Containers p ON c.parent_id = p.id
            WHERE c.parent_id IS NULL AND c.deleted_at IS NULL
            ${if (!includeArchived) "AND c.is_archived = 0" else ""}
            ORDER BY c.sibling_order ASC, c.uuid ASC
        """.trimIndent()
        val stmt = db.prepareStatement(sql)
        val list = mutableListOf<Container>()
        while (stmt.step() == StepResult.ROW) {
            list.add(readContainerRow(stmt))
        }
        stmt.finalize()
        return list
    }

    override fun children(parent: ContainerId, includeArchived: Boolean): List<Container> {
        val sql = """
            SELECT c.uuid, p.uuid AS parent_uuid, c.name, c.color, c.icon,
                   c.sibling_order, c.is_archived, c.created_at, c.updated_at, c.deleted_at, c.revision
            FROM Containers c
            JOIN Containers p ON c.parent_id = p.id
            WHERE p.uuid = ? AND c.deleted_at IS NULL
            ${if (!includeArchived) "AND c.is_archived = 0" else ""}
            ORDER BY c.sibling_order ASC, c.uuid ASC
        """.trimIndent()
        val stmt = db.prepareStatement(sql)
        stmt.bindText(1, parent.value)
        val list = mutableListOf<Container>()
        while (stmt.step() == StepResult.ROW) {
            list.add(readContainerRow(stmt))
        }
        stmt.finalize()
        return list
    }

    override fun path(id: ContainerId): ContainerPath =
        TreePolicy.buildPath(id, readState().currentRevision) { getContainer(it) }

    override fun subtreeIds(id: ContainerId): Set<ContainerId> =
        TreePolicy.collectSubtreeIds(id) { parentId ->
            children(parentId, includeArchived = true)
        }

    override fun isEffectivelyArchived(id: ContainerId): Boolean =
        TreePolicy.isEffectivelyArchived(id) { getContainer(it) }

    override fun search(query: String): List<ContainerSearchHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val sql = """
            SELECT c.uuid, p.uuid AS parent_uuid, c.name, c.color, c.icon,
                   c.sibling_order, c.is_archived, c.created_at, c.updated_at, c.deleted_at, c.revision
            FROM Containers c
            LEFT JOIN Containers p ON c.parent_id = p.id
            WHERE c.deleted_at IS NULL AND lower(c.name) LIKE ?
            ORDER BY lower(c.name) ASC, c.uuid ASC
        """.trimIndent()
        val stmt = db.prepareStatement(sql)
        stmt.bindText(1, "%${trimmed.lowercase()}%")
        val hits = mutableListOf<ContainerSearchHit>()
        while (stmt.step() == StepResult.ROW) {
            val container = readContainerRow(stmt)
            hits.add(
                ContainerSearchHit(
                    container = container,
                    path = path(container.id),
                )
            )
        }
        stmt.finalize()
        return hits
    }

    // ========================================================================
    // HabitPlacementQueries
    // ========================================================================

    override fun current(habit: HabitRef): HabitPlacement? =
        getHabitPlacement(habit)

    override fun directHabits(container: ContainerId?): List<HabitRef> {
        val sql = if (container == null) {
            """
            SELECT h.uuid
            FROM HabitPlacements hp
            JOIN Habits h ON hp.habit_id = h.id
            WHERE hp.container_id IS NULL
            ORDER BY hp.local_order ASC, h.uuid ASC
            """.trimIndent()
        } else {
            """
            SELECT h.uuid
            FROM HabitPlacements hp
            JOIN Habits h ON hp.habit_id = h.id
            JOIN Containers c ON hp.container_id = c.id
            WHERE c.uuid = ?
            ORDER BY hp.local_order ASC, h.uuid ASC
            """.trimIndent()
        }

        val stmt = db.prepareStatement(sql)
        if (container != null) {
            stmt.bindText(1, container.value)
        }
        val list = mutableListOf<HabitRef>()
        while (stmt.step() == StepResult.ROW) {
            list.add(HabitRef(stmt.getText(0)))
        }
        stmt.finalize()
        return list
    }

    override fun subtreeHabits(container: ContainerId): Set<HabitRef> {
        val allContainerIds = subtreeIds(container) + container
        val result = mutableSetOf<HabitRef>()
        for (cid in allContainerIds) {
            result.addAll(directHabits(cid))
        }
        return result
    }

    override fun locationAtRevision(
        habit: HabitRef,
        at: OrganizationRevision,
    ): HistoricalLocation {
        val state = readState()
        if (at < state.cutoverRevision) {
            return HistoricalLocation.UnknownBeforeCutover
        }

        val placementAtRevision = getHabitPlacementHistoryAtRevision(habit, at)
            ?: return HistoricalLocation.UnknownBeforeCutover

        val containerId = placementAtRevision.containerId
        if (containerId == null) {
            return if (placementAtRevision.origin == PlacementOrigin.UNKNOWN_LEGACY) {
                HistoricalLocation.UnknownLegacyAssignment
            } else {
                HistoricalLocation.Unassigned
            }
        }

        val nodes = mutableListOf<ContainerId>()
        var current: ContainerId? = containerId
        val visited = mutableSetOf<ContainerId>()

        while (current != null) {
            if (!visited.add(current)) break
            nodes.add(current)
            val histContainer = getContainerHistoryAtRevision(current, at) ?: break
            current = histContainer.parentId
        }
        nodes.reverse()
        return HistoricalLocation.Known(ContainerPath(nodes = nodes, revision = at))
    }

    // ========================================================================
    // UnitOfWork & Transaction Runner
    // ========================================================================

    override fun <T> executeInTransaction(block: (OrganizationTransaction) -> T): T {
        val isTopLevel = transactionDepth == 0
        val savepointName = "sp_$transactionDepth"

        if (isTopLevel) {
            db.run("BEGIN IMMEDIATE")
        } else {
            db.run("SAVEPOINT $savepointName")
        }
        transactionDepth++

        val result: T
        try {
            result = block(this)
        } catch (t: Throwable) {
            transactionDepth--
            if (isTopLevel) {
                try { db.run("ROLLBACK") } catch (_: Throwable) {}
            } else {
                try {
                    db.run("ROLLBACK TO $savepointName")
                    db.run("RELEASE $savepointName")
                } catch (_: Throwable) {}
            }
            throw t
        }

        if (result is OrganizationResult<*> && result.isFailure) {
            transactionDepth--
            if (isTopLevel) {
                try { db.run("ROLLBACK") } catch (_: Throwable) {}
            } else {
                try {
                    db.run("ROLLBACK TO $savepointName")
                    db.run("RELEASE $savepointName")
                } catch (_: Throwable) {}
            }
            return result
        }

        transactionDepth--
        if (isTopLevel) {
            db.run("COMMIT")
        } else {
            db.run("RELEASE $savepointName")
        }

        return result
    }

    // ========================================================================
    // Row Parsers
    // ========================================================================

    private fun readContainerRow(stmt: PreparedStatement): Container {
        val uuid = stmt.getText(0)
        val parentUuid = stmt.getTextOrNull(1)?.let { ContainerId(it) }
        val name = stmt.getText(2)
        val color = stmt.getIntOrNull(3)?.let { PaletteColor(it) }
        val icon = stmt.getTextOrNull(4)
        val siblingOrder = stmt.getInt(5)
        val isArchived = stmt.getInt(6) == 1
        val createdAt = stmt.getLongOrNull(7)
        val updatedAt = stmt.getLong(8)
        val deletedAt = stmt.getLongOrNull(9)
        val revision = OrganizationRevision(stmt.getLong(10))

        return Container(
            id = ContainerId(uuid),
            parentId = parentUuid,
            name = name,
            color = color,
            icon = icon,
            siblingOrder = siblingOrder,
            isArchived = isArchived,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            revision = revision,
        )
    }

    private fun readPlacementRow(stmt: PreparedStatement): HabitPlacement {
        val habitUuid = stmt.getText(0)
        val containerUuid = stmt.getTextOrNull(1)?.let { ContainerId(it) }
        val localOrder = stmt.getInt(2)
        val origin = PlacementOrigin.valueOf(stmt.getText(3))
        val revision = OrganizationRevision(stmt.getLong(4))

        return HabitPlacement(
            habit = HabitRef(habitUuid),
            containerId = containerUuid,
            localOrder = localOrder,
            origin = origin,
            revision = revision,
        )
    }
}
