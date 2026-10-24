package org.isoron.uhabits.activities.tasks

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Small composition bridge for the project's RecyclerView version (before ConcatAdapter). */
internal class TaskListAdapter(vararg initialSections: RecyclerView.Adapter<out RecyclerView.ViewHolder>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    @Suppress("UNCHECKED_CAST")
    private var sections = emptyList<RecyclerView.Adapter<RecyclerView.ViewHolder>>()
    private val observers = mutableListOf<Pair<RecyclerView.Adapter<RecyclerView.ViewHolder>, RecyclerView.AdapterDataObserver>>()
    private val viewTypes = linkedMapOf<Int, Pair<RecyclerView.Adapter<RecyclerView.ViewHolder>, Int>>()
    private var nextViewType = 0

    init {
        setSections(initialSections.toList(), false)
    }

    fun replaceSections(newSections: List<RecyclerView.Adapter<out RecyclerView.ViewHolder>>) {
        setSections(newSections, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setSections(newSections: List<RecyclerView.Adapter<out RecyclerView.ViewHolder>>, notify: Boolean) {
        observers.forEach { (adapter, observer) -> adapter.unregisterAdapterDataObserver(observer) }
        observers.clear()
        sections = newSections.map { it as RecyclerView.Adapter<RecyclerView.ViewHolder> }
        viewTypes.entries.removeAll { it.value.first !in sections }
        // Never reuse a removed child's type: RecyclerView may still retain its holders.
        sections.forEach { if ((it to 0) !in viewTypes.values) viewTypes[nextViewType++] = it to 0 }
        this.sections.forEachIndexed { index, adapter ->
            val observer = object : RecyclerView.AdapterDataObserver() {
                private fun offset() = this@TaskListAdapter.sections.take(index).sumOf { it.itemCount }
                override fun onChanged() = notifyDataSetChanged()
                override fun onItemRangeInserted(start: Int, count: Int) = notifyItemRangeInserted(offset() + start, count)
                override fun onItemRangeRemoved(start: Int, count: Int) = notifyItemRangeRemoved(offset() + start, count)
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) = notifyItemRangeChanged(offset() + start, count, payload)
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) {
                    check(count == 1) { "Task sections publish individual moves" }
                    notifyItemMoved(offset() + from, offset() + to)
                }
            }
            observers.add(adapter to observer)
            adapter.registerAdapterDataObserver(observer)
        }
        if (notify) notifyDataSetChanged()
    }

    override fun getItemCount() = sections.sumOf { it.itemCount }
    private fun locate(position: Int): Pair<Int, Int> {
        var local = position
        sections.forEachIndexed { index, adapter ->
            if (local < adapter.itemCount) return index to local
            local -= adapter.itemCount
        }
        error("Invalid task list position $position")
    }
    override fun getItemViewType(position: Int): Int {
        val (section, local) = locate(position)
        val type = sections[section] to sections[section].getItemViewType(local)
        val existing = viewTypes.entries.firstOrNull { it.value == type }?.key
        if (existing != null) return existing
        return nextViewType++.also { viewTypes[it] = type }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val (adapter, localType) = requireNotNull(viewTypes[viewType])
        return adapter.onCreateViewHolder(parent, localType)
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val (section, local) = locate(position)
        sections[section].onBindViewHolder(holder, local)
    }
}
