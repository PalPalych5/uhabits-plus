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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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
                    subtaskCounts[oldT.id] == newSubtaskCounts[newT.id] &&
                    today == currentToday &&
                    oldT.isHardOverdue(nowMillis) == newT.isHardOverdue(currentTimeMillis)
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
        private val layoutTaskMeta: LinearLayout = itemView.findViewById(R.id.layoutTaskMeta)
        private val viewContainerDot: View = itemView.findViewById(R.id.viewContainerDot)
        private val tvTaskMeta: TextView = itemView.findViewById(R.id.tvTaskMeta)

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

            // 2. Custom Check Circle (20dp circle on 48dp target)
            val circleDrawable = GradientDrawable()
            circleDrawable.shape = GradientDrawable.OVAL

            if (isCompleted) {
                circleDrawable.setColor(palette.accent)
                viewCheckCircle.background = circleDrawable
                ivCheckmark.setImageResource(R.drawable.ic_action_check)
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

            // 3. Metadata Line (Concept A: plain text, no capsule chips)
            bindMetadata(task)

            // Click & Long Click
            itemView.setOnClickListener {
                onTaskClicked(task)
            }
            itemView.setOnLongClickListener {
                onTaskLongClicked(task, it)
                true
            }
        }

        private fun bindMetadata(task: UserTask) {
            val ssb = SpannableStringBuilder()
            var hasElement = false

            // Container
            val containerId = task.containerId
            val containerItem = if (containerId != null) containers[containerId] else null
            if (containerItem != null) {
                viewContainerDot.visibility = View.VISIBLE
                val containerColor = TaskThemeUtils.resolvePaletteColor(itemView.context, containerItem.color, palette.accent)
                val dotDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(containerColor)
                }
                viewContainerDot.background = dotDrawable

                ssb.append(containerItem.name)
                hasElement = true
            } else {
                viewContainerDot.visibility = View.GONE
            }

            // Plan and deadline are independent; neither date suppresses the other.
            if (!task.isCompleted) {
                task.plannedDate?.let { planned ->
                    if (hasElement) ssb.append(" · ")
                    val start = ssb.length
                    val dateText = "${planned.day}.${planned.month.toString().padStart(2, '0')}"
                    val missed = task.isMissedPlan(today, nowMillis)
                    ssb.append(when {
                        missed -> "План прошёл · $dateText"
                        planned == today -> "План · Сегодня"
                        else -> "План · $dateText"
                    })
                    if (missed) {
                        val amberColor = if (palette.isDark) 0xFFE9B454.toInt() else 0xFFD97706.toInt()
                        ssb.setSpan(ForegroundColorSpan(amberColor), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    hasElement = true
                }
                task.deadline?.let { deadline ->
                    if (hasElement) ssb.append("\n")
                    val start = ssb.length
                    val date = Date(deadline)
                    val cal = java.util.Calendar.getInstance().apply { time = date }
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val time = timeFormat.format(date)
                    val overdue = task.isHardOverdue(nowMillis)
                    val prefix = if (overdue) "Дедлайн прошёл" else "Дедлайн"
                    ssb.append("$prefix · $day.${month.toString().padStart(2, '0')}, $time")
                    if (overdue) {
                        val dangerColor = if (palette.isDark) 0xFFFF8C8C.toInt() else 0xFFDC2626.toInt()
                        ssb.setSpan(ForegroundColorSpan(dangerColor), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    hasElement = true
                }
            }
            // Subtasks counter
            val counts = subtaskCounts[task.id]
            if (counts != null && counts.second > 0) {
                if (hasElement) ssb.append(" · ")
                ssb.append("✓ ${counts.first}/${counts.second}")
                hasElement = true
            }

            if (hasElement) {
                layoutTaskMeta.visibility = View.VISIBLE
                tvTaskMeta.setSingleLine(false)
                tvTaskMeta.maxLines = 3
                tvTaskMeta.text = ssb
                tvTaskMeta.setTextColor(palette.onSurfaceVariant)
            } else {
                layoutTaskMeta.visibility = View.GONE
            }
        }
    }
}
