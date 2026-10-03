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

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult

object RawLegacyInventoryReader {

    fun read(db: Database): RawLegacyInventory {
        val tables = listTables(db)

        val blocks = if (tables.contains("HabitBlocks")) readBlocks(db) else emptyList()
        val habits = if (tables.contains("Habits")) readHabits(db) else emptyList()
        val extensions = if (tables.contains("HabitExtensions")) readExtensions(db) else emptyList()
        val goals = if (tables.contains("HabitGoals")) readGoals(db) else emptyList()
        val repetitions = if (tables.contains("Repetitions")) readRepetitions(db) else emptyList()
        val entryOps = if (tables.contains("EntryOps")) readEntryOps(db) else emptyList()
        val syncQueue = if (tables.contains("SyncQueue")) readSyncQueue(db) else emptyList()
        val settings = if (tables.contains("AppSettings")) readSettings(db) else emptyList()

        val checksum = computeChecksum(
            blocks = blocks,
            habits = habits,
            extensions = extensions,
            goals = goals,
            repetitions = repetitions,
            entryOps = entryOps,
            syncQueue = syncQueue,
            settings = settings,
        )

        return RawLegacyInventory(
            blocks = blocks,
            habits = habits,
            extensions = extensions,
            goals = goals,
            repetitions = repetitions,
            entryOps = entryOps,
            syncQueue = syncQueue,
            settings = settings,
            snapshotSha256 = checksum,
            existingTables = tables,
        )
    }

    private fun listTables(db: Database): Set<String> {
        val result = mutableSetOf<String>()
        val stmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
        while (stmt.step() == StepResult.ROW) {
            result.add(stmt.getText(0))
        }
        stmt.finalize()
        return result
    }

    private fun readBlocks(db: Database): List<RawLegacyBlock> {
        val list = mutableListOf<RawLegacyBlock>()
        val stmt = db.prepareStatement(
            """
            SELECT id, uuid, name, color, icon, position, is_archived, updated_at, deleted_at
            FROM HabitBlocks
            ORDER BY id ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacyBlock(
                    id = stmt.getLong(0),
                    uuid = stmt.getTextOrNull(1),
                    name = stmt.getTextOrNull(2) ?: "",
                    color = stmt.getInt(3),
                    icon = stmt.getTextOrNull(4),
                    position = stmt.getInt(5),
                    isArchived = stmt.getInt(6) != 0,
                    updatedAt = stmt.getLong(7),
                    deletedAt = stmt.getLongOrNull(8),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readHabits(db: Database): List<RawLegacyHabit> {
        val list = mutableListOf<RawLegacyHabit>()
        val stmt = db.prepareStatement(
            """
            SELECT id, uuid, name, description, question, freq_num, freq_den, color, position,
                   reminder_hour, reminder_min, reminder_days, highlight, archived, type,
                   target_value, target_type, unit, updated_at, deleted_at
            FROM Habits
            ORDER BY id ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacyHabit(
                    id = stmt.getLong(0),
                    uuid = stmt.getTextOrNull(1),
                    name = stmt.getTextOrNull(2) ?: "",
                    description = stmt.getTextOrNull(3) ?: "",
                    question = stmt.getTextOrNull(4) ?: "",
                    freqNum = stmt.getInt(5),
                    freqDen = stmt.getInt(6),
                    color = stmt.getInt(7),
                    position = stmt.getInt(8),
                    reminderHour = stmt.getIntOrNull(9),
                    reminderMin = stmt.getIntOrNull(10),
                    reminderDays = stmt.getInt(11),
                    highlight = stmt.getInt(12),
                    archived = stmt.getInt(13) != 0,
                    type = stmt.getInt(14),
                    targetValue = stmt.getReal(15),
                    targetType = stmt.getInt(16),
                    unit = stmt.getTextOrNull(17) ?: "",
                    updatedAt = stmt.getLong(18),
                    deletedAt = stmt.getLongOrNull(19),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readExtensions(db: Database): List<RawLegacyExtension> {
        val list = mutableListOf<RawLegacyExtension>()
        val stmt = db.prepareStatement(
            """
            SELECT habit_id, day_tier, timer_enabled, block_id, stats_start_timestamp
            FROM HabitExtensions
            ORDER BY habit_id ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacyExtension(
                    habitId = stmt.getLong(0),
                    dayTier = stmt.getTextOrNull(1) ?: "NORMAL",
                    timerEnabled = stmt.getInt(2) != 0,
                    blockId = stmt.getLongOrNull(3),
                    statsStartTimestamp = stmt.getLongOrNull(4),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readGoals(db: Database): List<RawLegacyGoal> {
        val list = mutableListOf<RawLegacyGoal>()
        val stmt = db.prepareStatement(
            """
            SELECT id, habit_id, effective_timestamp, freq_num, freq_den, target_type, target_value,
                   unit, uuid, updated_at, deleted_at
            FROM HabitGoals
            ORDER BY id ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacyGoal(
                    id = stmt.getLong(0),
                    habitId = stmt.getLong(1),
                    effectiveTimestamp = stmt.getLong(2),
                    freqNum = stmt.getInt(3),
                    freqDen = stmt.getInt(4),
                    targetType = stmt.getInt(5),
                    targetValue = stmt.getReal(6),
                    unit = stmt.getTextOrNull(7) ?: "",
                    uuid = stmt.getTextOrNull(8),
                    updatedAt = stmt.getLong(9),
                    deletedAt = stmt.getLongOrNull(10),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readRepetitions(db: Database): List<RawLegacyRepetition> {
        val list = mutableListOf<RawLegacyRepetition>()
        val stmt = db.prepareStatement(
            """
            SELECT habit, timestamp, value, notes, uuid, updated_at, deleted_at
            FROM Repetitions
            ORDER BY habit ASC, timestamp ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacyRepetition(
                    habitId = stmt.getLong(0),
                    timestamp = stmt.getLong(1),
                    value = stmt.getInt(2),
                    notes = stmt.getTextOrNull(3),
                    uuid = stmt.getTextOrNull(4),
                    updatedAt = stmt.getLong(5),
                    deletedAt = stmt.getLongOrNull(6),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readEntryOps(db: Database): List<RawLegacyEntryOp> {
        val list = mutableListOf<RawLegacyEntryOp>()
        val stmt = db.prepareStatement(
            """
            SELECT op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, notes,
                   device_id, created_at, deleted_at
            FROM EntryOps
            ORDER BY created_at ASC, op_uuid ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacyEntryOp(
                    opUuid = stmt.getText(0),
                    habitUuid = stmt.getText(1),
                    entryTimestamp = stmt.getLong(2),
                    deltaValue = stmt.getInt(3),
                    opType = stmt.getText(4),
                    notes = stmt.getTextOrNull(5),
                    deviceId = stmt.getText(6),
                    createdAt = stmt.getLong(7),
                    deletedAt = stmt.getLongOrNull(8),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readSyncQueue(db: Database): List<RawLegacySyncQueueItem> {
        val list = mutableListOf<RawLegacySyncQueueItem>()
        val stmt = db.prepareStatement(
            """
            SELECT queue_id, op_uuid, entity_type, entity_uuid, operation_type, payload_json,
                   created_at, device_id, pushed_at, failed_at, failure_reason
            FROM SyncQueue
            ORDER BY queue_id ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacySyncQueueItem(
                    queueId = stmt.getLong(0),
                    opUuid = stmt.getText(1),
                    entityType = stmt.getText(2),
                    entityUuid = stmt.getText(3),
                    operationType = stmt.getText(4),
                    payloadJson = stmt.getText(5),
                    createdAt = stmt.getLong(6),
                    deviceId = stmt.getText(7),
                    pushedAt = stmt.getLongOrNull(8),
                    failedAt = stmt.getLongOrNull(9),
                    failureReason = stmt.getTextOrNull(10),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun readSettings(db: Database): List<RawLegacySetting> {
        val list = mutableListOf<RawLegacySetting>()
        val stmt = db.prepareStatement(
            """
            SELECT key, long_value
            FROM AppSettings
            ORDER BY key ASC
            """.trimIndent()
        )
        while (stmt.step() == StepResult.ROW) {
            list.add(
                RawLegacySetting(
                    key = stmt.getText(0),
                    longValue = stmt.getLongOrNull(1),
                )
            )
        }
        stmt.finalize()
        return list
    }

    private fun computeChecksum(
        blocks: List<RawLegacyBlock>,
        habits: List<RawLegacyHabit>,
        extensions: List<RawLegacyExtension>,
        goals: List<RawLegacyGoal>,
        repetitions: List<RawLegacyRepetition>,
        entryOps: List<RawLegacyEntryOp>,
        syncQueue: List<RawLegacySyncQueueItem>,
        settings: List<RawLegacySetting>,
    ): String {
        val sb = StringBuilder()
        for (b in blocks) {
            sb.append("B:").append(b.id).append(':').append(b.uuid).append(':').append(b.name)
                .append(':').append(b.color).append(':').append(b.icon).append(':').append(b.position)
                .append(':').append(b.isArchived).append(':').append(b.updatedAt).append(':').append(b.deletedAt).append('\n')
        }
        for (h in habits) {
            sb.append("H:").append(h.id).append(':').append(h.uuid).append(':').append(h.name)
                .append(':').append(h.description).append(':').append(h.question)
                .append(':').append(h.freqNum).append(':').append(h.freqDen).append(':').append(h.color)
                .append(':').append(h.position).append(':').append(h.reminderHour).append(':').append(h.reminderMin)
                .append(':').append(h.reminderDays).append(':').append(h.highlight).append(':').append(h.archived)
                .append(':').append(h.type).append(':').append(h.targetValue).append(':').append(h.targetType)
                .append(':').append(h.unit).append(':').append(h.updatedAt).append(':').append(h.deletedAt).append('\n')
        }
        for (e in extensions) {
            sb.append("E:").append(e.habitId).append(':').append(e.dayTier).append(':').append(e.timerEnabled)
                .append(':').append(e.blockId).append(':').append(e.statsStartTimestamp).append('\n')
        }
        for (g in goals) {
            sb.append("G:").append(g.id).append(':').append(g.habitId).append(':').append(g.effectiveTimestamp)
                .append(':').append(g.freqNum).append(':').append(g.freqDen).append(':').append(g.targetType)
                .append(':').append(g.targetValue).append(':').append(g.unit).append(':').append(g.uuid)
                .append(':').append(g.updatedAt).append(':').append(g.deletedAt).append('\n')
        }
        for (r in repetitions) {
            sb.append("R:").append(r.habitId).append(':').append(r.timestamp).append(':').append(r.value)
                .append(':').append(r.notes).append(':').append(r.uuid).append(':').append(r.updatedAt)
                .append(':').append(r.deletedAt).append('\n')
        }
        for (op in entryOps) {
            sb.append("OP:").append(op.opUuid).append(':').append(op.habitUuid).append(':').append(op.entryTimestamp)
                .append(':').append(op.deltaValue).append(':').append(op.opType).append(':').append(op.notes)
                .append(':').append(op.deviceId).append(':').append(op.createdAt).append(':').append(op.deletedAt).append('\n')
        }
        for (q in syncQueue) {
            sb.append("Q:").append(q.queueId).append(':').append(q.opUuid).append(':').append(q.entityType)
                .append(':').append(q.entityUuid).append(':').append(q.operationType).append(':').append(q.payloadJson)
                .append(':').append(q.createdAt).append(':').append(q.deviceId).append(':').append(q.pushedAt)
                .append(':').append(q.failedAt).append(':').append(q.failureReason).append('\n')
        }
        for (s in settings) {
            sb.append("S:").append(s.key).append(':').append(s.longValue).append('\n')
        }
        return Sha256.hexDigest(sb.toString())
    }
}
