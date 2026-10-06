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
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.userTasks.TaskId

/** Finalized facts only. Runtime intervals are deliberately not a recoverable backup domain. */
internal object FocusBackupInventory {
    private val legacyColumns = listOf("id", "started_at", "ended_at", "duration_ms", "task_id", "habit_uuid", "container_id", "source", "device_id", "created_at", "updated_at", "revision")
    private val metadataColumns = listOf("primary_target_type", "organization_context")
    val emptyChecksum: String get() = hash("FOCUS:v1\n")

    fun schemaError(db: Database): String? {
        if ("FocusSessions" !in DatabaseSnapshotter.listTables(db)) return null
        val columns = mutableSetOf<String>()
        val stmt = db.prepareStatement("PRAGMA table_info(FocusSessions)")
        try { while (stmt.step() == StepResult.ROW) columns.add(stmt.getText(1)) } finally { stmt.finalize() }
        return if (columns == legacyColumns.toSet() || columns == (legacyColumns + metadataColumns).toSet()) null
        else "Unsupported or incomplete FocusSessions schema: $columns"
    }

    fun read(db: Database): List<FocusSession> {
        require(schemaError(db) == null) { schemaError(db) ?: "Invalid FocusSessions" }
        if ("FocusSessions" !in DatabaseSnapshotter.listTables(db)) return emptyList()
        val columns = mutableSetOf<String>()
        val info = db.prepareStatement("PRAGMA table_info(FocusSessions)")
        try { while (info.step() == StepResult.ROW) columns.add(info.getText(1)) } finally { info.finalize() }
        val primary = if ("primary_target_type" in columns) "primary_target_type" else
            "CASE WHEN task_id IS NOT NULL THEN 'TASK' WHEN habit_uuid IS NOT NULL THEN 'HABIT' WHEN container_id IS NOT NULL THEN 'CONTAINER' END"
        val context = if ("organization_context" in columns) "organization_context" else "NULL"
        val stmt = db.prepareStatement("SELECT ${legacyColumns.joinToString()}, $primary, $context FROM FocusSessions ORDER BY id")
        return try {
            buildList {
                while (stmt.step() == StepResult.ROW) add(FocusSession(
                    id = FocusSessionId(stmt.getText(0)), startedAt = stmt.getLong(1), endedAt = stmt.getLong(2), durationMs = stmt.getLong(3),
                    taskId = stmt.getTextOrNull(4)?.let(::TaskId), habit = stmt.getTextOrNull(5)?.let(::HabitRef),
                    containerId = stmt.getTextOrNull(6)?.let(::ContainerId), source = FocusSessionSource.valueOf(stmt.getText(7)),
                    deviceId = stmt.getTextOrNull(8), createdAt = stmt.getLong(9), updatedAt = stmt.getLong(10), revision = stmt.getLong(11),
                    primaryTargetType = stmt.getTextOrNull(12)?.let(FocusPrimaryTargetType::valueOf), organizationContext = stmt.getTextOrNull(13),
                ))
            }
        } finally { stmt.finalize() }
    }

    fun checksum(db: Database): String {
        val text = buildString {
            append("FOCUS:v1\n")
            for (s in read(db)) {
                val values = listOf(s.id.value, s.startedAt.toString(), s.endedAt.toString(), s.durationMs.toString(),
                    s.taskId?.value, s.habit?.uuid, s.containerId?.value, s.source.name, s.deviceId,
                    s.createdAt.toString(), s.updatedAt.toString(), s.revision.toString(), s.primaryTargetType?.name, s.organizationContext)
                for (v in values) if (v == null) append("-1:") else append(v.length).append(':').append(v)
                append('\n')
            }
        }
        return hash(text)
    }
    private fun hash(text: String) = Sha256.hexDigest(Sha256.digest(text.encodeToByteArray()))
}
