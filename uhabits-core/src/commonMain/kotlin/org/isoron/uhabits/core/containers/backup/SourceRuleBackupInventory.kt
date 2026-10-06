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

package org.isoron.uhabits.core.containers.backup

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.migration.Sha256
import org.isoron.uhabits.core.rules.*

/**
 * Backup inventory and deterministic checksum calculation for the SourceRule table.
 * Preserves active and tombstoned rules during backup/restore roundtrips.
 */
internal object SourceRuleBackupInventory {
    private val expectedColumns = listOf(
        "id",
        "source_kind",
        "source_container_id",
        "include_descendants",
        "target_habit_uuid",
        "value_mapping",
        "is_enabled",
        "effective_from",
        "created_at",
        "updated_at",
        "removed_at",
    )

    val emptyChecksum: String get() = hash("SOURCERULES:v1\n")

    fun schemaError(db: Database): String? {
        if ("SourceRules" !in DatabaseSnapshotter.listTables(db)) return null
        val columns = mutableSetOf<String>()
        val stmt = db.prepareStatement("PRAGMA table_info(SourceRules)")
        try {
            while (stmt.step() == StepResult.ROW) columns.add(stmt.getText(1))
        } finally {
            stmt.finalize()
        }
        return if (columns == expectedColumns.toSet()) null
        else "Unsupported or incomplete SourceRules schema: $columns"
    }

    fun read(db: Database): List<SourceRule> {
        require(schemaError(db) == null) { schemaError(db) ?: "Invalid SourceRules" }
        if ("SourceRules" !in DatabaseSnapshotter.listTables(db)) return emptyList()

        val stmt = db.prepareStatement(
            "SELECT ${expectedColumns.joinToString()} FROM SourceRules ORDER BY id"
        )
        return try {
            buildList {
                while (stmt.step() == StepResult.ROW) {
                    add(
                        SourceRule(
                            id = SourceRuleId(stmt.getText(0)),
                            sourceKind = SourceRuleSourceKind.valueOf(stmt.getText(1)),
                            sourceContainerId = ContainerId(stmt.getText(2)),
                            includeDescendants = stmt.getLong(3) == 1L,
                            targetHabit = HabitRef(stmt.getText(4)),
                            valueMapping = SourceRuleValueMapping.valueOf(stmt.getText(5)),
                            isEnabled = stmt.getLong(6) == 1L,
                            effectiveFrom = stmt.getLong(7),
                            createdAt = stmt.getLong(8),
                            updatedAt = stmt.getLong(9),
                            removedAt = stmt.getLongOrNull(10),
                        )
                    )
                }
            }
        } finally {
            stmt.finalize()
        }
    }

    fun checksum(db: Database): String {
        val text = buildString {
            append("SOURCERULES:v1\n")
            for (r in read(db)) {
                val values = listOf(
                    r.id.value,
                    r.sourceKind.name,
                    r.sourceContainerId.value,
                    if (r.includeDescendants) "1" else "0",
                    r.targetHabit.uuid,
                    r.valueMapping.name,
                    if (r.isEnabled) "1" else "0",
                    r.effectiveFrom.toString(),
                    r.createdAt.toString(),
                    r.updatedAt.toString(),
                    r.removedAt?.toString(),
                )
                for (v in values) {
                    if (v == null) append("-1:") else append(v.length).append(':').append(v)
                }
                append('\n')
            }
        }
        return hash(text)
    }

    private fun hash(text: String) = Sha256.hexDigest(Sha256.digest(text.encodeToByteArray()))
}
