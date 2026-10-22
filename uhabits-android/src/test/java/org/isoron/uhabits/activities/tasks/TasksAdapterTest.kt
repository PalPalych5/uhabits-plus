package org.isoron.uhabits.activities.tasks

import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksAdapterTest {
    private val day = LocalDate(2026, 10, 17)
    private fun task(id: String) = UserTask(TaskId(id), id, createdAt = 0, updatedAt = 0)
    private fun adapter() = TasksAdapter(SettingsThemePalette(
        background = 0, surface = 0, border = 0, divider = 0, onSurface = 0,
        onSurfaceVariant = 0, isDark = false, isPureBlack = false), {}, {}, { _, _ -> }).also {
        // The Android JVM jar stubs Observable's constructor and registerObserver.
        // Supply only that missing platform storage; AndroidX notification and
        // DiffUtil implementations still execute unchanged.
        val observable = RecyclerView.Adapter::class.java.getDeclaredField("mObservable")
            .apply { isAccessible = true }.get(it)
        android.database.Observable::class.java.getDeclaredField("mObservers")
            .apply { isAccessible = true }.set(observable, arrayListOf<RecyclerView.AdapterDataObserver>())
    }

    @Suppress("UNCHECKED_CAST")
    private fun observe(adapter: TasksAdapter, observer: RecyclerView.AdapterDataObserver) {
        val observable = RecyclerView.Adapter::class.java.getDeclaredField("mObservable")
            .apply { isAccessible = true }.get(adapter)
        val observers = android.database.Observable::class.java.getDeclaredField("mObservers")
            .apply { isAccessible = true }.get(observable) as MutableList<RecyclerView.AdapterDataObserver>
        observers.add(observer)
    }

    @Test fun emptyTransitionsPublishConsistentDataBeforePreciseNotifications() {
        val adapter = adapter()
        val events = mutableListOf<String>()
        observe(adapter, object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events.add("all") }
            override fun onItemRangeInserted(start: Int, count: Int) {
                events.add("insert:$start:$count:${adapter.itemCount}")
            }
            override fun onItemRangeRemoved(start: Int, count: Int) {
                events.add("remove:$start:$count:${adapter.itemCount}")
            }
        })
        fun update(tasks: List<UserTask>) = adapter.updateData(tasks, emptyMap(), emptyMap(), day, 0)
        update(emptyList())
        update(listOf(task("a"), task("b")))
        update(emptyList())
        update(emptyList())
        update(listOf(task("c")))
        assertEquals(listOf("insert:0:2:2", "remove:0:2:0", "insert:0:1:1"), events)
    }

    @Test fun nonemptyUpdatesNotifyMovesAndEveryBindingDependency() {
        val adapter = adapter()
        val a = task("a").copy(due = TaskDue.Timed.of(100))
        val b = task("b")
        var changes = 0
        var moves = 0
        adapter.updateData(listOf(a, b), emptyMap(), emptyMap(), day, 0)
        observe(adapter, object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { changes += count }
            override fun onItemRangeMoved(from: Int, to: Int, count: Int) { moves += count }
        })
        adapter.updateData(listOf(b, a), emptyMap(), emptyMap(), day, 0)
        assertTrue(moves > 0)
        assertEquals(0, changes)
        adapter.updateData(listOf(b, a), mapOf(a.id to (0 to 1)), emptyMap(), day, 0)
        assertEquals(1, changes)
        adapter.updateData(listOf(b, a), mapOf(a.id to (0 to 1)), emptyMap(), day, 101)
        assertEquals(2, changes)
        adapter.updateData(listOf(b, a), mapOf(a.id to (0 to 1)), emptyMap(), LocalDate(2026, 10, 18), 101)
        assertEquals(4, changes)
        adapter.updateData(listOf(b.copy(title = "Renamed"), a), mapOf(a.id to (0 to 1)),
            emptyMap(), LocalDate(2026, 10, 18), 101)
        assertEquals(5, changes)
    }
}
