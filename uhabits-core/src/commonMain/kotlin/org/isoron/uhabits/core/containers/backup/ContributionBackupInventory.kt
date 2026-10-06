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
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.migration.Sha256
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType

/**
 * Backup inventory and deterministic checksum calculation for the Contribution ledger.
 * Preserves active and tombstoned contribution facts during backup/restore roundtrips.
 */
internal object ContributionBackupInventory {
    private val expectedColumns = listOf(
        "id",
        "target_habit_uuid",
        "source_type",
        "source_id",
        "contributed_value",
        "effective_timestamp",
        "created_at",
        "updated_at",
        "removed_at",
    )

    val emptyChecksum: String get() = hash("CONTRIBUTION:v1\n")

    fun schemaError(db: Database): String? {
        if ("Contributions" !in DatabaseSnapshotter.listTables(db)) return null
        val columns = mutableSetOf<String>()
        val stmt = db.prepareStatement("PRAGMA table_info(Contributions)")
        try {
            while (stmt.step() == StepResult.ROW) columns.add(stmt.getText(1))
        } finally {
            stmt.finalize()
        }
        return if (columns == expectedColumns.toSet()) null
        else "Unsupported or incomplete Contributions schema: $columns"
    }

    fun read(db: Database): List<Contribution> {
        require(schemaError(db) == null) { schemaError(db) ?: "Invalid Contributions" }
        if ("Contributions" !in DatabaseSnapshotter.listTables(db)) return emptyList()

        val stmt = db.prepareStatement(
            "SELECT ${expectedColumns.joinToString()} FROM Contributions ORDER BY id"
        )
        return try {
            buildList {
                while (stmt.step() == StepResult.ROW) {
                    add(
                        Contribution(
                            id = ContributionId(stmt.getText(0)),
                            targetHabit = HabitRef(stmt.getText(1)),
                            sourceType = ContributionSourceType.valueOf(stmt.getText(2)),
                            sourceId = stmt.getText(3),
                            contributedValue = stmt.getLong(4),
                            effectiveTimestamp = stmt.getLong(5),
                            createdAt = stmt.getLong(6),
                            updatedAt = stmt.getLong(7),
                            removedAt = stmt.getLongOrNull(8),
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
            append("CONTRIBUTION:v1\n")
            for (c in read(db)) {
                val values = listOf(
                    c.id.value,
                    c.targetHabit.uuid,
                    c.sourceType.name,
                    c.sourceId,
                    c.contributedValue.toString(),
                    c.effectiveTimestamp.toString(),
                    c.createdAt.toString(),
                    c.updatedAt.toString(),
                    c.removedAt?.toString(),
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
