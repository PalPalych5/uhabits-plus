package org.isoron.uhabits.core.commands

import org.isoron.platform.Synchronized
import kotlin.concurrent.Volatile

/** Disabled-by-default, run-local correlation. Contains no persistent record identity or contents. */
class HabitEditTrace(
    val ordinal: Long,
    val observer: (HabitEditTrace, String, Long?, Long?, Long?) -> Unit
) {
    @Volatile var durableRevision: Long? = null
    private var terminal = false

    fun event(stage: String, revision: Long? = null, generation: Long? = null, batch: Long? = null) {
        try { observer(this, stage, revision, generation, batch) } catch (_: Throwable) { /* Diagnostics cannot change behavior. */ }
    }

    @Synchronized
    fun finish(stage: String) {
        if (terminal) return
        terminal = true
        event(stage)
    }
}
