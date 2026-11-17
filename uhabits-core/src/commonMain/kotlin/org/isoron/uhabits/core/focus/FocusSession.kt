/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.focus

import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.userTasks.TaskId
import kotlin.jvm.JvmInline

@JvmInline
value class FocusSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "FocusSession id must not be blank" }
    }

    override fun toString(): String = value
}

enum class FocusSessionSource {
    MANUAL,
    TIMER,
}

enum class FocusPrimaryTargetType { TASK, HABIT, CONTAINER }

/**
 * An immutable finalized work fact. References capture context at recording time;
 * they neither require live entities nor imply task completion or habit credit.
 * Duration counts active work and may be shorter than the elapsed interval.
 */
data class FocusSession(
    val id: FocusSessionId,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val taskId: TaskId? = null,
    val habit: HabitRef? = null,
    val containerId: ContainerId? = null,
    val source: FocusSessionSource,
    val deviceId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val primaryTargetType: FocusPrimaryTargetType? = when {
        taskId != null -> FocusPrimaryTargetType.TASK
        habit != null -> FocusPrimaryTargetType.HABIT
        containerId != null -> FocusPrimaryTargetType.CONTAINER
        else -> null
    },
    val organizationContext: String? = null,
) {
    init {
        require(startedAt >= 0) { "startedAt must be nonnegative" }
        require(endedAt > startedAt) { "endedAt must be after startedAt" }
        require(durationMs > 0 && durationMs <= endedAt - startedAt) {
            "durationMs must be positive and no greater than the elapsed interval"
        }
        require(createdAt >= 0) { "createdAt must be nonnegative" }
        require(updatedAt >= createdAt) { "updatedAt must not precede createdAt" }
        require(revision == 1L) { "Only initial finalized records are supported" }
        require(taskId == null || taskId.value.isNotBlank()) { "taskId must not be blank" }
        require(habit == null || habit.uuid.isNotBlank()) { "habit reference must not be blank" }
        require(containerId == null || containerId.value.isNotBlank()) { "containerId must not be blank" }
        require(deviceId == null || deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(primaryTargetType != FocusPrimaryTargetType.TASK || taskId != null)
        require(primaryTargetType != FocusPrimaryTargetType.HABIT || habit != null)
        require(primaryTargetType != FocusPrimaryTargetType.CONTAINER || containerId != null)
    }
}
