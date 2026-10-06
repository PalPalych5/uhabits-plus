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

package org.isoron.uhabits.core.focus.runtime

import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.timer.TimerSessionSnapshot

/** Frozen primary identity plus historical organization captured when work starts. */
data class FocusCaptureTarget(
    val kind: FocusPrimaryTargetType,
    val id: String,
    val containerId: ContainerId? = null,
    val organizationContext: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(containerId == null || containerId.value.isNotBlank())
    }
}

data class FocusCaptureSegment(
    val id: FocusSessionId,
    val startedAt: Long,
    val elapsedOffsetMillis: Long = 0,
    val deviceId: String? = null,
)

/** Raw engine state is authoritative, including pauses, phase and completion latch. */
data class FocusCaptureJournal(
    val timer: TimerSessionSnapshot,
    val autoSwitch: Boolean,
    val target: FocusCaptureTarget?,
    val active: FocusCaptureSegment?,
    val observedAt: Long,
)

/** Commits journal advancement and any newly finalized facts together or throws without changes. */
interface FocusCaptureStore {
    fun load(): FocusCaptureJournal?
    fun commit(journal: FocusCaptureJournal, facts: List<FocusSession>)
}
