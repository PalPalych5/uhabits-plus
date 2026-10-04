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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import java.text.DateFormat
import java.util.Date

class TasksAdapter(
    private var palette: SettingsThemePalette,
    private val onTaskClicked: (UserTask) -> Unit,
    private val onToggleComplete: (UserTask) -> Unit,
    private val onTaskLongClicked: (UserTask, View) -> Unit,
) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

    private var tasks: List<UserTask> = emptyList()
    private var subtaskCounts: Map<TaskId, Pair<Int, Int>> = emptyMap() // taskId -> (completed, total)
    private var containers: Map<ContainerId, OrganizationalItem> = emptyMap()
    private var today: LocalDate = LocalDate(2000, 1, 1)
    private var nowMillis: Long = System.currentTimeMillis()

    fun updateData(
        newTasks: List<UserTask>,
        newSubtaskCounts: Map<TaskId, Pair<Int, Int>>,
        newContainers: Map<ContainerId, OrganizationalItem>,
        currentToday: LocalDate,
        currentTimeMillis: Long,
    ) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = tasks.size
            override fun getNewListSize(): Int = newTasks.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return tasks[oldItemPosition].id == newTasks[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldT = tasks[oldItemPosition]
                val newT = newTasks[newItemPosition]
                return oldT == newT &&
                    subtaskCounts[oldT.id] == newSubtaskCounts[newT.id]
            }
        })
        tasks = newTasks
        subtaskCounts = newSubtaskCounts
        containers = newContainers
        today = currentToday
        nowMillis = currentTimeMillis
        diff.dispatchUpdatesTo(this)
    }

    fun updatePalette(newPalette: SettingsThemePalette) {
        palette = newPalette
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    override fun getItemCount(): Int = tasks.size

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnCheck: FrameLayout = itemView.findViewById(R.id.btnTaskCheck)
        private val viewCheckCircle: View = itemView.findViewById(R.id.viewCheckCircle)
        private val ivCheckmark: ImageView = itemView.findViewById(R.id.ivCheckmark)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTaskTitle)
        private val pillContainer: LinearLayout = itemView.findViewById(R.id.pillContainer)
        private val viewContainerDot: View = itemView.findViewById(R.id.viewContainerDot)
        private val tvContainerName: TextView = itemView.findViewById(R.id.tvContainerName)
        private val tvSubtasksBadge: TextView = itemView.findViewById(R.id.tvSubtasksBadge)
        private val tvDateChip: TextView = itemView.findViewById(R.id.tvDateChip)

        fun bind(task: UserTask) {
            val isCompleted = task.isCompleted

            // 1. Title & Strikethrough
            tvTitle.text = task.title
            if (isCompleted) {
                tvTitle.setTextColor(palette.onSurfaceVariant)
                tvTitle.paintFlags = tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tvTitle.setTextColor(palette.onSurface)
                tvTitle.paintFlags = tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // 2. Custom Check Circle
            val circleDrawable = GradientDrawable()
            circleDrawable.shape = GradientDrawable.OVAL

            if (isCompleted) {
                circleDrawable.setColor(palette.accent)
                viewCheckCircle.background = circleDrawable
                ivCheckmark.visibility = View.VISIBLE
                ivCheckmark.setColorFilter(Color.WHITE)
            } else {
                circleDrawable.setColor(Color.TRANSPARENT)
                val strokeWidth = TaskThemeUtils.dpInt(itemView.context, 1.5f)
                circleDrawable.setStroke(strokeWidth, MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.45f))
                viewCheckCircle.background = circleDrawable
                ivCheckmark.visibility = View.GONE
            }

            btnCheck.setOnClickListener {
                onToggleComplete(task)
            }

            // 3. Container Pill
            val containerId = task.containerId
            val containerItem = if (containerId != null) containers[containerId] else null
            if (containerItem != null) {
                pillContainer.visibility = View.VISIBLE
                val containerColor = TaskThemeUtils.resolvePaletteColor(itemView.context, containerItem.color, palette.accent)
                val pillBg = GradientDrawable().apply {
                    cornerRadius = TaskThemeUtils.dp(itemView.context, 10f)
                    setColor(MainTabsThemeBridge.withAlpha(containerColor, if (palette.isDark) 0.22f else 0.12f))
                }
                pillContainer.background = pillBg
                tvContainerName.text = containerItem.name
                tvContainerName.setTextColor(if (palette.isDark) palette.onSurface else containerColor)

                val dotDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(containerColor)
                }
                viewContainerDot.background = dotDrawable
            } else {
                pillContainer.visibility = View.GONE
            }

            // 4. Subtasks Badge
            val counts = subtaskCounts[task.id]
            if (counts != null && counts.second > 0) {
                tvSubtasksBadge.visibility = View.VISIBLE
                tvSubtasksBadge.text = "✓ ${counts.first}/${counts.second}"
                val badgeBg = GradientDrawable().apply {
                    cornerRadius = TaskThemeUtils.dp(itemView.context, 6f)
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
                }
                tvSubtasksBadge.background = badgeBg
                tvSubtasksBadge.setTextColor(palette.onSurfaceVariant)
            } else {
                tvSubtasksBadge.visibility = View.GONE
            }

            // 5. Date Chip
            bindDateChip(task)

            // Click & Long Click
            itemView.setOnClickListener {
                onTaskClicked(task)
            }
            itemView.setOnLongClickListener {
                onTaskLongClicked(task, it)
                true
            }
        }

        private fun bindDateChip(task: UserTask) {
            if (task.isCompleted) {
                tvDateChip.visibility = View.GONE
                return
            }

            val isHard = task.isHardOverdue(nowMillis)
            val isMissed = task.isMissedPlan(today, nowMillis)

            when {
                isHard -> {
                    tvDateChip.visibility = View.VISIBLE
                    tvDateChip.text = "Просрочено"
                    val bg = GradientDrawable().apply {
                        cornerRadius = TaskThemeUtils.dp(itemView.context, 6f)
                        setColor(if (palette.isDark) 0x33DC2626.toInt() else 0x22EF4444.toInt())
                    }
                    tvDateChip.background = bg
                    tvDateChip.setTextColor(0xFFEF4444.toInt())
                }
                isMissed -> {
                    tvDateChip.visibility = View.VISIBLE
                    val p = task.plannedDate
                    val dateText = if (p != null) "${p.day}.${p.month.toString().padStart(2, '0')}" else ""
                    tvDateChip.text = "План: $dateText"
                    val bg = GradientDrawable().apply {
                        cornerRadius = TaskThemeUtils.dp(itemView.context, 6f)
                        setColor(if (palette.isDark) 0x33D97706.toInt() else 0x22F59E0B.toInt())
                    }
                    tvDateChip.background = bg
                    tvDateChip.setTextColor(0xFFF59E0B.toInt())
                }
                task.plannedDate != null && task.plannedDate == today -> {
                    tvDateChip.visibility = View.VISIBLE
                    tvDateChip.text = "Сегодня"
                    val bg = GradientDrawable().apply {
                        cornerRadius = TaskThemeUtils.dp(itemView.context, 6f)
                        setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.18f))
                    }
                    tvDateChip.background = bg
                    tvDateChip.setTextColor(palette.accent)
                }
                task.plannedDate != null -> {
                    tvDateChip.visibility = View.VISIBLE
                    val p = task.plannedDate!!
                    tvDateChip.text = "${p.day}.${p.month.toString().padStart(2, '0')}"
                    val bg = GradientDrawable().apply {
                        cornerRadius = TaskThemeUtils.dp(itemView.context, 6f)
                        setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
                    }
                    tvDateChip.background = bg
                    tvDateChip.setTextColor(palette.onSurfaceVariant)
                }
                task.deadline != null -> {
                    tvDateChip.visibility = View.VISIBLE
                    val dateStr = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(task.deadline!!))
                    tvDateChip.text = "До $dateStr"
                    val bg = GradientDrawable().apply {
                        cornerRadius = TaskThemeUtils.dp(itemView.context, 6f)
                        setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
                    }
                    tvDateChip.background = bg
                    tvDateChip.setTextColor(palette.onSurfaceVariant)
                }
                else -> {
                    tvDateChip.visibility = View.GONE
                }
            }
        }
    }
}
