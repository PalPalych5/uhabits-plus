package org.isoron.uhabits.activities.habits.show

/** Main-thread owned publication fence for non-cancellable model/SQLite reads. */
internal class HabitDetailReadGeneration {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun invalidate() { generation++ }
    fun accepts(request: Long): Boolean = request == generation
}
