package org.isoron.uhabits.activities.tasks

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskListAdapterTest {
    // Android's JVM Observable methods are stubs. Supply their storage and registration,
    // while retaining the real RecyclerView notification and composition implementations.
    @Suppress("UNCHECKED_CAST")
    private fun observers(adapter: RecyclerView.Adapter<*>): MutableList<RecyclerView.AdapterDataObserver> {
        val observable = RecyclerView.Adapter::class.java.getDeclaredField("mObservable")
            .apply { isAccessible = true }.get(adapter)
        val storage = android.database.Observable::class.java.getDeclaredField("mObservers")
            .apply { isAccessible = true }
        if (storage.get(observable) == null) storage.set(observable, arrayListOf<RecyclerView.AdapterDataObserver>())
        return storage.get(observable) as MutableList<RecyclerView.AdapterDataObserver>
    }

    private inner class Section(private var count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = error("No Android UI in this test")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        override fun registerAdapterDataObserver(observer: RecyclerView.AdapterDataObserver) {
            observers(this).add(observer)
        }
        override fun unregisterAdapterDataObserver(observer: RecyclerView.AdapterDataObserver) {
            observers(this).remove(observer)
        }
        fun insert(start: Int, amount: Int) { count += amount; notifyItemRangeInserted(start, amount) }
        fun remove(start: Int, amount: Int) { count -= amount; notifyItemRangeRemoved(start, amount) }
    }

    private fun record(adapter: TaskListAdapter): MutableList<String> = mutableListOf<String>().also { events ->
        observers(adapter).add(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events.add("all:${adapter.itemCount}") }
            override fun onItemRangeInserted(start: Int, count: Int) { events.add("insert:$start:$count:${adapter.itemCount}") }
            override fun onItemRangeRemoved(start: Int, count: Int) { events.add("remove:$start:$count:${adapter.itemCount}") }
            override fun onItemRangeMoved(from: Int, to: Int, count: Int) { events.add("move:$from:$to:$count") }
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { events.add("change:$start:$count:$payload") }
        })
    }

    @Test fun childNotificationsKeepOffsetsPayloadAndCurrentCount() {
        val tasks = Section(3)
        val trailing = Section(2)
        val adapter = TaskListAdapter(Section(1), tasks, Section(1), trailing)
        val events = record(adapter)
        tasks.insert(1, 2)
        tasks.remove(0, 1)
        tasks.notifyItemMoved(2, 0)
        tasks.notifyItemRangeChanged(0, 2, "due")
        trailing.notifyItemChanged(1, "completed")
        assertEquals(listOf("insert:2:2:9", "remove:1:1:8", "move:3:1:1", "change:1:2:due", "change:7:1:completed"), events)
        assertEquals(8, adapter.itemCount)
    }

    @Test fun collapseAndReopenRecalculateFollowingOffsetsAndViewTypes() {
        val overdueHeader = Section(1)
        val overdue = Section(2)
        val active = Section(2)
        val adapter = TaskListAdapter(overdueHeader, overdue, Section(1), active)
        val events = record(adapter)
        overdue.remove(0, 2)
        overdueHeader.remove(0, 1)
        assertEquals(listOf(2, 3, 3), (0 until adapter.itemCount).map(adapter::getItemViewType))
        active.notifyItemChanged(0, "title")
        overdueHeader.insert(0, 1)
        overdue.insert(0, 1)
        assertEquals(listOf(0, 1, 2, 3, 3), (0 until adapter.itemCount).map(adapter::getItemViewType))
        assertEquals(listOf("remove:1:2:4", "remove:0:1:3", "change:1:1:title", "insert:0:1:4", "insert:1:1:5"), events)
    }

    @Test fun emptySectionsDoNotClaimPositionsAndGlobalChangesStillPropagate() {
        val initiallyEmpty = Section(0)
        val tasks = Section(1)
        val adapter = TaskListAdapter(Section(0), initiallyEmpty, tasks, Section(0))
        val events = record(adapter)
        assertEquals(2, adapter.getItemViewType(0))
        initiallyEmpty.insert(0, 2)
        tasks.notifyItemChanged(0)
        tasks.notifyDataSetChanged()
        assertEquals(listOf(1, 1, 2), (0 until adapter.itemCount).map(adapter::getItemViewType))
        assertEquals(listOf("insert:0:2:3", "change:2:1:null", "all:3"), events)
    }

    @Test fun replacingGroupsDetachesOldObserversAndNeverReusesHolderTypes() {
        val old = Section(2)
        val replacement = Section(3)
        val adapter = TaskListAdapter(old)
        val oldType = adapter.getItemViewType(0)
        val events = record(adapter)
        adapter.replaceSections(listOf(replacement))
        assertEquals(listOf("all:3"), events)
        old.insert(0, 1)
        assertEquals(listOf("all:3"), events)
        replacement.insert(1, 1)
        assertEquals("insert:1:1:4", events.last())
        org.junit.Assert.assertNotEquals(oldType, adapter.getItemViewType(0))
        adapter.replaceSections(emptyList())
        assertEquals(0, adapter.itemCount)
    }

    @Test fun nestedCompositionKeepsHeaderAndRowTypesDistinct() {
        val nested = TaskListAdapter(Section(1), Section(2))
        val outer = TaskListAdapter(nested)
        org.junit.Assert.assertNotEquals(outer.getItemViewType(0), outer.getItemViewType(1))
        assertEquals(outer.getItemViewType(1), outer.getItemViewType(2))
    }
}
