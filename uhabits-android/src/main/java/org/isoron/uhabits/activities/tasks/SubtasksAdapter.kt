/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

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
import org.isoron.uhabits.core.userTasks.UserTask

class SubtasksAdapter(
    private var palette: SettingsThemePalette,
    private val onToggleComplete: (UserTask) -> Unit,
    private val onDeleteSubtask: (UserTask) -> Unit,
) : RecyclerView.Adapter<SubtasksAdapter.SubtaskViewHolder>() {

    private var subtasks: List<UserTask> = emptyList()

    fun updateData(newSubtasks: List<UserTask>) {
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

        fun bind(subtask: UserTask) {
            val isCompleted = subtask.isCompleted

            tvTitle.text = subtask.title
            if (isCompleted) {
                tvTitle.setTextColor(palette.onSurfaceVariant)
                tvTitle.paintFlags = tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tvTitle.setTextColor(palette.onSurface)
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

            btnCheck.setOnClickListener {
                onToggleComplete(subtask)
            }

            btnDelete.setColorFilter(palette.onSurfaceVariant)
            btnDelete.setOnClickListener {
                onDeleteSubtask(subtask)
            }
        }
    }
}
