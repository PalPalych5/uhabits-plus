/* SPDX-License-Identifier: GPL-3.0-or-later */

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
