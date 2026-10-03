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
import org.isoron.uhabits.core.containers.OrganizationChangeRecord
import org.isoron.uhabits.core.containers.OrganizationRevision

data class LegacyBlockMapRecord(
    val legacyBlockId: Long,
    val legacyBlockUuid: String,
    val containerId: ContainerId,
)

data class OrganizationStateRecord(
    val datasetUuid: String,
    val foundationVersion: Int,
    val mode: String,
    val cutoverRevision: OrganizationRevision,
    val currentRevision: OrganizationRevision,
    val cutoverAt: Long,
    val sourceSchemaVersion: Int,
    val sourceSnapshotSha256: String,
)

data class MigrationPlan(
    val containers: List<Container>,
    val placements: List<HabitPlacement>,
    val legacyBlockMaps: List<LegacyBlockMapRecord>,
    val organizationState: OrganizationStateRecord,
    val baselineRecord: OrganizationChangeRecord,
    val issues: List<MigrationIssue>,
    val sourceSnapshotSha256: String,
) {
    val canMigrate: Boolean get() = issues.none { it.isBlocking }
}
