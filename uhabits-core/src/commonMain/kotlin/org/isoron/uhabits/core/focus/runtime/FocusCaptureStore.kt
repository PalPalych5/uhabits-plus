/* SPDX-License-Identifier: GPL-3.0-or-later */

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
