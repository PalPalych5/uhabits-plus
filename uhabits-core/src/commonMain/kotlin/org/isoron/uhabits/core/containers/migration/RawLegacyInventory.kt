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

data class RawLegacyBlock(
    val id: Long,
    val uuid: String?,
    val name: String,
    val color: Int,
    val icon: String?,
    val position: Int,
    val isArchived: Boolean,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class RawLegacyHabit(
    val id: Long,
    val uuid: String?,
    val name: String,
    val description: String,
    val question: String,
    val freqNum: Int,
    val freqDen: Int,
    val color: Int,
    val position: Int,
    val reminderHour: Int?,
    val reminderMin: Int?,
    val reminderDays: Int,
    val highlight: Int,
    val archived: Boolean,
    val type: Int,
    val targetValue: Double,
    val targetType: Int,
    val unit: String,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class RawLegacyExtension(
    val habitId: Long,
    val dayTier: String,
    val timerEnabled: Boolean,
    val blockId: Long?,
    val statsStartTimestamp: Long?,
)

data class RawLegacyGoal(
    val id: Long,
    val habitId: Long,
    val effectiveTimestamp: Long,
    val freqNum: Int,
    val freqDen: Int,
    val targetType: Int,
    val targetValue: Double,
    val unit: String,
    val uuid: String?,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class RawLegacyRepetition(
    val habitId: Long,
    val timestamp: Long,
    val value: Int,
    val notes: String?,
    val uuid: String?,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class RawLegacyEntryOp(
    val opUuid: String,
    val habitUuid: String,
    val entryTimestamp: Long,
    val deltaValue: Int,
    val opType: String,
    val notes: String?,
    val deviceId: String,
    val createdAt: Long,
    val deletedAt: Long?,
)

data class RawLegacySyncQueueItem(
    val queueId: Long,
    val opUuid: String,
    val entityType: String,
    val entityUuid: String,
    val operationType: String,
    val payloadJson: String,
    val createdAt: Long,
    val deviceId: String,
    val pushedAt: Long?,
    val failedAt: Long?,
    val failureReason: String?,
)

data class RawLegacySetting(
    val key: String,
    val longValue: Long?,
)

data class RawLegacyInventory(
    val blocks: List<RawLegacyBlock>,
    val habits: List<RawLegacyHabit>,
    val extensions: List<RawLegacyExtension>,
    val goals: List<RawLegacyGoal>,
    val repetitions: List<RawLegacyRepetition>,
    val entryOps: List<RawLegacyEntryOp>,
    val syncQueue: List<RawLegacySyncQueueItem>,
    val settings: List<RawLegacySetting>,
    val snapshotSha256: String,
    val existingTables: Set<String> = emptySet(),
)
