package org.isoron.uhabits.core.sync

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.UserTask

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
    var authorityMode: OrganizationAuthorityMode = OrganizationAuthorityMode.LEGACY
    var onQueueAppended: (() -> Unit)? = null
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
        if (authorityMode == OrganizationAuthorityMode.CONTAINER_LOCAL) return
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
        val blockUuid = if (authorityMode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            null
        } else {
            blockUuidProvider(habit.blockId)
        }
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
            "block_uuid" to blockUuid,
            "timer_enabled" to habit.timerEnabled.toString(),
            "statistics_start" to habit.statisticsStartDate?.unixTime?.toString(),
            "reminder_hour" to habit.reminder?.hour?.toString(),
            "reminder_min" to habit.reminder?.minute?.toString(),
            "reminder_days" to habit.reminder?.days?.toInteger()?.toString()
        )
    }

    fun enqueueContainer(container: Container, operationType: String = "upsert") {
        enqueue(
            "container",
            container.id.value,
            operationType,
            json(
                "uuid" to container.id.value,
                "parent_uuid" to container.parentId?.value,
                "name" to container.name,
                "color" to container.color?.paletteIndex?.toString(),
                "icon" to container.icon,
                "sibling_order" to container.siblingOrder.toString(),
                "is_archived" to container.isArchived.toString(),
                "created_at" to container.createdAt?.toString(),
                "updated_at" to container.updatedAt.toString(),
                "deleted_at" to container.deletedAt?.toString()
            )
        )
    }

    fun enqueueContainerDelete(containerUuid: String, deletedAt: Long) {
        enqueue(
            "container",
            containerUuid,
            "delete",
            json(
                "uuid" to containerUuid,
                "deleted_at" to deletedAt.toString(),
                "updated_at" to deletedAt.toString()
            )
        )
    }

    fun enqueueHabitPlacement(
        habitUuid: String,
        containerUuid: String?,
        localOrder: Int,
        updatedAt: Long = now()
    ) {
        enqueue(
            "habit_placement",
            habitUuid,
            "place",
            json(
                "habit_uuid" to habitUuid,
                "container_uuid" to containerUuid,
                "local_order" to localOrder.toString(),
                "updated_at" to updatedAt.toString()
            )
        )
    }

    fun enqueueUserTask(task: UserTask, operationType: String = "upsert") {
        enqueue(
            "user_task",
            task.id.value,
            operationType,
            json(
                "id" to task.id.value,
                "title" to task.title,
                "description" to task.description,
                "container_id" to task.containerId?.value,
                "status" to task.status.name,
                "planned_date" to task.plannedDate?.unixTime?.toString(),
                "deadline" to task.deadline?.toString(),
                "completed_at" to task.completedAt?.toString(),
                "parent_task_id" to task.parentTaskId?.value,
                "sort_order" to task.sortOrder.toString(),
                "created_at" to task.createdAt.toString(),
                "updated_at" to task.updatedAt.toString()
            )
        )
    }

    fun enqueueUserTaskDelete(taskId: String, deletedAt: Long) {
        enqueue(
            "user_task",
            taskId,
            "delete",
            json(
                "id" to taskId,
                "deleted_at" to deletedAt.toString(),
                "updated_at" to deletedAt.toString()
            )
        )
    }

    fun enqueueFocusSession(session: FocusSession, operationType: String = "upsert") {
        enqueue(
            "focus_session",
            session.id.value,
            operationType,
            json(
                "id" to session.id.value,
                "started_at" to session.startedAt.toString(),
                "ended_at" to session.endedAt.toString(),
                "duration_ms" to session.durationMs.toString(),
                "task_id" to session.taskId?.value,
                "habit_uuid" to session.habit?.uuid,
                "container_id" to session.containerId?.value,
                "source" to session.source.name,
                "device_id" to session.deviceId,
                "created_at" to session.createdAt.toString(),
                "updated_at" to session.updatedAt.toString(),
                "primary_target_type" to session.primaryTargetType?.name,
                "organization_context" to session.organizationContext
            )
        )
    }

    fun enqueueContribution(contribution: Contribution, operationType: String = "upsert") {
        enqueue(
            "contribution",
            contribution.id.value,
            operationType,
            json(
                "id" to contribution.id.value,
                "target_habit_uuid" to contribution.targetHabit.uuid,
                "source_type" to contribution.sourceType.name,
                "source_id" to contribution.sourceId,
                "contributed_value" to contribution.contributedValue.toString(),
                "effective_timestamp" to contribution.effectiveTimestamp.toString(),
                "created_at" to contribution.createdAt.toString(),
                "updated_at" to contribution.updatedAt.toString(),
                "removed_at" to contribution.removedAt?.toString(),
                "source_rule_id" to contribution.sourceRuleId?.value
            )
        )
    }

    fun enqueueContributionRemove(contributionId: String, removedAt: Long) {
        enqueue(
            "contribution",
            contributionId,
            "remove",
            json(
                "id" to contributionId,
                "removed_at" to removedAt.toString(),
                "updated_at" to removedAt.toString()
            )
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
        onQueueAppended?.invoke()
    }

    private fun json(vararg pairs: Pair<String, String?>): String {
        return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":${value?.let { "\"${escape(it)}\"" } ?: "null"}"
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
