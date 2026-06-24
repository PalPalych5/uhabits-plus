package org.isoron.uhabits.core.sync

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit

/**
 * Local sync foundation only. Future remote sync should apply:
 * - habit metadata by last-write-wins on updated_at;
 * - boolean/day-state entries by last explicit action wins;
 * - numerical/timer accumulation by summing immutable EntryOps, deduped by op_uuid;
 * - tombstones (deleted_at) over older updates;
 * - backup restore as destructive local replacement, not sync reconciliation.
 */
@OptIn(ExperimentalUuidApi::class)
class SyncManager(
    private val queueRepository: SyncQueueRepository,
    val entryOpRepository: EntryOpRepository,
    private val deviceIdProvider: () -> String,
    private val nowProvider: () -> Long,
    private val blockUuidProvider: (Long?) -> String? = { null }
) {
    private var pausedDepth = 0

    fun now(): Long = nowProvider()

    fun isCaptureEnabled(): Boolean = pausedDepth == 0

    fun <T> withCapturePaused(block: () -> T): T {
        pausedDepth += 1
        return try {
            block()
        } finally {
            pausedDepth -= 1
        }
    }

    suspend fun <T> withCapturePausedSuspend(block: suspend () -> T): T {
        pausedDepth += 1
        return try {
            block()
        } finally {
            pausedDepth -= 1
        }
    }

    fun enqueueHabitCreate(habit: Habit) {
        val habitUuid = habit.uuid ?: return
        enqueue("habit", habitUuid, "create", habitPayload(habit))
    }

    fun enqueueHabitUpdate(habit: Habit) {
        val habitUuid = habit.uuid ?: return
        enqueue("habit", habitUuid, "update", habitPayload(habit))
    }

    fun enqueueHabitArchive(habit: Habit) {
        val habitUuid = habit.uuid ?: return
        enqueue("habit", habitUuid, "archive", habitPayload(habit))
    }

    fun enqueueHabitUnarchive(habit: Habit) {
        val habitUuid = habit.uuid ?: return
        enqueue("habit", habitUuid, "unarchive", habitPayload(habit))
    }

    fun enqueueHabitDelete(habit: Habit) {
        val habitUuid = habit.uuid ?: return
        enqueue("habit", habitUuid, "delete", habitPayload(habit))
    }

    fun enqueueEntrySet(habit: Habit, date: LocalDate, value: Int, notes: String) =
        enqueue(
            "entry",
            entryUuid(habit, date),
            "entry_set",
            json(
                "habit_uuid" to habit.uuid,
                "entry_date" to date.unixTime.toString(),
                "value" to value.toString(),
                "notes" to notes
            )
        )

    fun enqueueEntryDelete(habit: Habit, date: LocalDate) =
        enqueue(
            "entry",
            entryUuid(habit, date),
            "delete",
            json(
                "habit_uuid" to habit.uuid,
                "entry_date" to date.unixTime.toString()
            )
        )

    fun enqueueGoalChange(habit: Habit, effectiveDate: LocalDate) =
        habit.normalizedGoalHistory()
            .firstOrNull { it.effectiveDate == effectiveDate }
            ?.let { goal ->
                enqueue(
                    "habit_goal",
                    "${habit.uuid}:goal:${effectiveDate.unixTime}",
                    "goal_change",
                    json(
                        "habit_uuid" to habit.uuid,
                        "effective_timestamp" to effectiveDate.unixTime.toString(),
                        "freq_num" to goal.frequency.numerator.toString(),
                        "freq_den" to goal.frequency.denominator.toString(),
                        "target_type" to goal.targetType.value.toString(),
                        "target_value" to goal.targetValue.toString(),
                        "unit" to goal.unit
                    )
                )
            }

    fun enqueueBlockChange(block: HabitBlockData, operationType: String) {
        val blockUuid = block.uuid ?: return
        enqueue(
            "habit_block",
            blockUuid,
            operationType,
            json(
                "name" to block.name,
                "color" to block.color.toString(),
                "position" to block.position.toString(),
                "is_archived" to block.isArchived.toString()
            )
        )
    }

    fun enqueueAppSettingChange(key: String, value: Long?) =
        enqueue(
            "app_setting",
            key,
            "update",
            json(
                "key" to key,
                "long_value" to value?.toString()
            )
        )

    fun recordEntryOp(habit: Habit, date: LocalDate, deltaValue: Int, opType: String, notes: String?) {
        if (!isCaptureEnabled()) return
        val habitUuid = habit.uuid ?: return
        val now = now()
        val opUuid = Uuid.random().toHexString()
        val deviceId = deviceIdProvider()
        entryOpRepository.insert(
            EntryOpRecord(
                opUuid = opUuid,
                habitUuid = habitUuid,
                entryTimestamp = date.unixTime,
                deltaValue = deltaValue,
                opType = opType,
                notes = notes,
                deviceId = deviceId,
                createdAt = now
            )
        )
        enqueue(
            "entry_op",
            opUuid,
            "entry_op_add",
            json(
                "habit_uuid" to habitUuid,
                "entry_date" to date.unixTime.toString(),
                "delta_value" to deltaValue.toString(),
                "op_type" to opType,
                "notes" to notes
            ),
            createdAt = now,
            deviceId = deviceId
        )
    }

    private fun habitPayload(habit: Habit): String {
        val goal = habit.currentGoal()
        return json(
            "name" to habit.name,
            "description" to habit.description,
            "question" to habit.question,
            "archived" to habit.isArchived.toString(),
            "type" to habit.type.value.toString(),
            "freq_num" to habit.frequency.numerator.toString(),
            "freq_den" to habit.frequency.denominator.toString(),
            "color" to habit.color.paletteIndex.toString(),
            "target_value" to goal.targetValue.toString(),
            "target_type" to goal.targetType.value.toString(),
            "unit" to goal.unit,
            "day_tier" to habit.dayTier.name,
            "block_uuid" to blockUuidProvider(habit.blockId),
            "timer_enabled" to habit.timerEnabled.toString(),
            "statistics_start" to habit.statisticsStartDate?.unixTime?.toString(),
            "reminder_hour" to habit.reminder?.hour?.toString(),
            "reminder_min" to habit.reminder?.minute?.toString(),
            "reminder_days" to habit.reminder?.days?.toInteger()?.toString()
        )
    }

    private fun entryUuid(habit: Habit, date: LocalDate): String =
        "${habit.uuid}:${date.unixTime}"

    private fun enqueue(
        entityType: String,
        entityUuid: String,
        operationType: String,
        payloadJson: String,
        createdAt: Long = now(),
        deviceId: String = deviceIdProvider()
    ) {
        if (!isCaptureEnabled()) return
        queueRepository.insert(
            SyncQueueRecord(
                opUuid = Uuid.random().toHexString(),
                entityType = entityType,
                entityUuid = entityUuid,
                operationType = operationType,
                payloadJson = payloadJson,
                createdAt = createdAt,
                deviceId = deviceId
            )
        )
    }

    private fun json(vararg pairs: Pair<String, String?>): String {
        return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":${value?.let { "\"${escape(it)}\"" } ?: "null"}"
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
