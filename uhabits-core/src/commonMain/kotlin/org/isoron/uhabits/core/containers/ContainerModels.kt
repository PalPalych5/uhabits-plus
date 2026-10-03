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

package org.isoron.uhabits.core.containers

import org.isoron.uhabits.core.models.PaletteColor
import kotlin.jvm.JvmInline

@JvmInline
value class ContainerId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class HabitRef(val uuid: String) {
    override fun toString(): String = uuid
}

@JvmInline
value class OrganizationRevision(val value: Long) : Comparable<OrganizationRevision> {
    override fun compareTo(other: OrganizationRevision): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
}

data class Container(
    val id: ContainerId,
    val parentId: ContainerId?,
    val name: String,
    val color: PaletteColor?,
    val icon: String?,
    val siblingOrder: Int,
    val isArchived: Boolean,
    val createdAt: Long?,
    val updatedAt: Long,
    val deletedAt: Long?,
    val revision: OrganizationRevision,
)

data class HabitPlacement(
    val habit: HabitRef,
    val containerId: ContainerId?,
    val localOrder: Int,
    val origin: PlacementOrigin,
    val revision: OrganizationRevision,
)

enum class PlacementOrigin {
    MIGRATION_SNAPSHOT,
    USER_CHANGE,
    IMPORT_SNAPSHOT,
    UNKNOWN_LEGACY,
}

data class ContainerPath(
    val nodes: List<ContainerId>,
    val revision: OrganizationRevision,
)

sealed interface HistoricalLocation {
    data class Known(val path: ContainerPath) : HistoricalLocation
    data object Unassigned : HistoricalLocation
    data object UnknownBeforeCutover : HistoricalLocation
    data object UnknownLegacyAssignment : HistoricalLocation
}

data class ContainerSearchHit(
    val container: Container,
    val path: ContainerPath,
)

data class OrganizationChangeRecord(
    val revision: OrganizationRevision,
    val opUuid: String,
    val recordedAt: Long,
    val operationType: String,
    val origin: String,
    val commandPayload: String,
)

data class OrganizationStateSnapshot(
    val cutoverRevision: OrganizationRevision,
    val currentRevision: OrganizationRevision,
    val cutoverAt: Long,
)
