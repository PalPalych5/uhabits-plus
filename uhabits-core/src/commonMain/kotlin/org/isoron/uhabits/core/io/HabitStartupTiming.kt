package org.isoron.uhabits.core.io

import kotlin.concurrent.Volatile

/** Optional QA stage observer. The platform observer supplies monotonic timestamps. */
object HabitStartupTiming {
    @Volatile
    var observer: ((String, Long?) -> Unit)? = null

    fun mark(stage: String, generation: Long? = null) {
        observer?.invoke(stage, generation)
    }
}
