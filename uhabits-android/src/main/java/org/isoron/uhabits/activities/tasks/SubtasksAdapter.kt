/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette

class SubtasksAdapter(
    private var palette: SettingsThemePalette,
    private val onToggleComplete: (SubtaskItem) -> Unit,
    private val onDeleteSubtask: (SubtaskItem) -> Unit,
    private val onEditSubtask: ((SubtaskItem) -> Unit)? = null,
) : RecyclerView.Adapter<SubtasksAdapter.SubtaskViewHolder>() {

    data class SubtaskItem(
        val id: String,
        val title: String,
        val isCompleted: Boolean,
    )

    private var subtasks: List<SubtaskItem> = emptyList()

    fun updateData(newSubtasks: List<SubtaskItem>) {
        subtasks = newSubtasks
        notifyDataSetChanged()
    }

    fun updatePalette(newPalette: SettingsThemePalette) {
        palette = newPalette
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_subtask, parent, false)
        return SubtaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubtaskViewHolder, position: Int) {
        holder.bind(subtasks[position])
    }

    override fun getItemCount(): Int = subtasks.size

    inner class SubtaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnCheck: FrameLayout = itemView.findViewById(R.id.btnSubtaskCheck)
        private val viewCheckCircle: View = itemView.findViewById(R.id.viewSubtaskCheckCircle)
        private val ivCheckmark: ImageView = itemView.findViewById(R.id.ivSubtaskCheckmark)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSubtaskTitle)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteSubtask)

        fun bind(subtask: SubtaskItem) {
            val isCompleted = subtask.isCompleted

            tvTitle.text = subtask.title
            if (isCompleted) {
                tvTitle.setTextColor(palette.onSurfaceVariant)
                tvTitle.alpha = 0.62f
                tvTitle.paintFlags = tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tvTitle.setTextColor(palette.onSurface)
                tvTitle.alpha = 1.0f
                tvTitle.paintFlags = tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
            }

            if (isCompleted) {
                circleDrawable.setColor(palette.accent)
                viewCheckCircle.background = circleDrawable
                ivCheckmark.setImageResource(R.drawable.ic_action_check)
                ivCheckmark.visibility = View.VISIBLE
                ivCheckmark.setColorFilter(Color.WHITE)
            } else {
                circleDrawable.setColor(Color.TRANSPARENT)
                val strokeWidth = (1.5f * itemView.resources.displayMetrics.density + 0.5f).toInt()
                circleDrawable.setStroke(strokeWidth, MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.45f))
                viewCheckCircle.background = circleDrawable
                ivCheckmark.visibility = View.GONE
            }

            btnCheck.contentDescription = if (isCompleted) {
                "Возобновить подзадачу: ${subtask.title}"
            } else {
                "Завершить подзадачу: ${subtask.title}"
            }
            btnCheck.setOnClickListener {
                onToggleComplete(subtask)
            }

            btnDelete.setColorFilter(palette.onSurfaceVariant)
            btnDelete.contentDescription = "Удалить подзадачу: ${subtask.title}"
            btnDelete.setOnClickListener {
                onDeleteSubtask(subtask)
            }

            if (onEditSubtask != null) {
                tvTitle.setOnClickListener {
                    onEditSubtask.invoke(subtask)
                }
            } else {
                tvTitle.setOnClickListener(null)
            }
        }
    }
}
