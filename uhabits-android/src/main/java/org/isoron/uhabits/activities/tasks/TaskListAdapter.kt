package org.isoron.uhabits.activities.tasks

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Small composition bridge for the project's RecyclerView version (before ConcatAdapter). */
internal class TaskListAdapter(vararg sections: RecyclerView.Adapter<out RecyclerView.ViewHolder>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    @Suppress("UNCHECKED_CAST")
    private val sections = sections.map { it as RecyclerView.Adapter<RecyclerView.ViewHolder> }

    init {
        this.sections.forEachIndexed { index, adapter ->
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                private fun offset() = this@TaskListAdapter.sections.take(index).sumOf { it.itemCount }
                override fun onChanged() = notifyDataSetChanged()
                override fun onItemRangeInserted(start: Int, count: Int) = notifyItemRangeInserted(offset() + start, count)
                override fun onItemRangeRemoved(start: Int, count: Int) = notifyItemRangeRemoved(offset() + start, count)
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) = notifyItemRangeChanged(offset() + start, count, payload)
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) {
                    check(count == 1) { "Task sections publish individual moves" }
                    notifyItemMoved(offset() + from, offset() + to)
                }
            })
        }
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
    override fun getItemViewType(position: Int) = locate(position).first
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = sections[viewType].onCreateViewHolder(parent, 0)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val (section, local) = locate(position)
        sections[section].onBindViewHolder(holder, local)
    }
}
