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
    }
}
