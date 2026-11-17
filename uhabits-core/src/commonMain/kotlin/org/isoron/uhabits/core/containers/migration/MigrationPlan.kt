/* SPDX-License-Identifier: GPL-3.0-or-later */

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
    val sourceFileSha256: String? = null,
    val sourceSchemaSha256: String? = null,
    val runtimeAuthority: String = "LEGACY",
    val authorityEpoch: Long = 0L,
    val conversionOpUuid: String? = null,
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
