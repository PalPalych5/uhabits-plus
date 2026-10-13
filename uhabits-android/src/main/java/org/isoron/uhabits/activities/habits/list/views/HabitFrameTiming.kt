package org.isoron.uhabits.activities.habits.list.views

import android.os.Trace
import android.view.View

/** Optional disposable-QA observer. Stage names never include habit data. */
object HabitFrameTiming {
    @Volatile
    var observer: ((stage: String, view: View?, durationNanos: Long) -> Unit)? = null

    inline fun <T> measure(stage: String, view: View?, block: () -> T): T {
        val listener = observer ?: return block()
        val started = System.nanoTime()
        Trace.beginSection(stage)
        try {
            return block()
        } finally {
            Trace.endSection()
            listener(stage, view, System.nanoTime() - started)
        }
    }

    fun mark(stage: String, view: View?) {
        observer?.invoke(stage, view, 0L)
    }
}
