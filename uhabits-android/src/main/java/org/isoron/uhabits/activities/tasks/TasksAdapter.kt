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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import androidx.appcompat.content.res.AppCompatResources
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
                    oldT.containerId?.let { containers[it] } == newT.containerId?.let { newContainers[it] } &&
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
        private val tvTaskContext: TextView = itemView.findViewById(R.id.tvTaskContext)
        private val tvTaskMeta: TextView = itemView.findViewById(R.id.tvTaskMeta)

        fun bind(task: UserTask) {
            val isCompleted = task.isCompleted
            applyRowProfile()
            btnCheck.contentDescription = (if (isCompleted) "Возобновить: " else "Завершить: ") + task.title

            // 1. Title & Strikethrough
            tvTitle.text = task.title
            if (isCompleted) {
                tvTitle.setTextColor(palette.onSurfaceVariant)
                tvTitle.paintFlags = tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tvTitle.setTextColor(palette.onSurface)
                tvTitle.paintFlags = tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // Completion affordance stays inside a 48dp target.
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
                circleDrawable.setStroke(strokeWidth, MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.7f))
                viewCheckCircle.background = circleDrawable
                ivCheckmark.visibility = View.GONE
            }

            btnCheck.setOnClickListener {
                onToggleComplete(task)
            }

            // Secondary property lines; dates remain independent and may wrap.
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

        private fun applyRowProfile() {

            itemView.setPaddingRelative(dp(8f), dp(8f), dp(20f), dp(8f))
            tvTitle.textSize = 15.5f
            tvTitle.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            tvTaskContext.textSize = 12f
            tvTaskMeta.textSize = 12f
            (layoutTaskMeta.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(4f) }
            (tvTaskMeta.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(4f) }
            viewCheckCircle.layoutParams = (viewCheckCircle.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(20f)
                height = dp(20f)
            }
            ivCheckmark.layoutParams = (ivCheckmark.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = dp((20f - 12f) / 2f)
            }
        }

        private fun dp(value: Float) = TaskThemeUtils.dpInt(itemView.context, value)

        private fun SpannableStringBuilder.appendProperty(icon: Int, label: String, color: Int) {
            if (isNotEmpty()) append("   ")
            val start = length
            val drawable = AppCompatResources.getDrawable(itemView.context, icon)!!.mutate().apply {
                setTint(color)
                val size = dp(12f)
                setBounds(0, 0, size, size)
            }
            append("\uFFFC ")
            setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(label)
            setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun bindMetadata(task: UserTask) {
            val context = SpannableStringBuilder()
            val containerName = task.containerId?.let { containers[it]?.name ?: "Раздел" } ?: if (task.isCompleted) "Без раздела" else "Входящие"
            context.appendProperty(R.drawable.ic_cat_folder, containerName, palette.onSurfaceVariant)
            val counts = subtaskCounts[task.id]
            if (counts != null && counts.second > 0) {
                context.appendProperty(R.drawable.ic_cat_list_todo, "${counts.first}/${counts.second}", palette.onSurfaceVariant)
            }
            tvTaskContext.text = context
            tvTaskContext.setTextColor(palette.onSurfaceVariant)
            tvTaskContext.contentDescription = "Раздел: " + containerName +
                (if (counts != null && counts.second > 0) "; подзадачи: ${counts.first} из ${counts.second}" else "")

            val dates = SpannableStringBuilder()
            val spokenDates = mutableListOf<String>()
            if (!task.isCompleted) {
                task.plannedDate?.let { planned ->
                    val missed = task.isMissedPlan(today, nowMillis)
                    val label = when {
                        missed -> "План прошёл: ${planned.day}.${planned.month.toString().padStart(2, '0')}"
                        planned == today -> "План сегодня"
                        else -> "План ${planned.day}.${planned.month.toString().padStart(2, '0')}"
                    }
                    val color = if (missed) {
                        if (palette.isDark) 0xFFF0C170.toInt() else 0xFF926018.toInt()
                    } else palette.onSurfaceVariant
                    dates.appendProperty(R.drawable.ic_cat_calendar, label, color)
                    spokenDates.add(label)
                }
                task.deadline?.let { deadline ->
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = deadline }
                    val overdue = task.isHardOverdue(nowMillis)
                    val prefix = if (overdue) "Дедлайн прошёл:" else "Дедлайн"
                    val label = "$prefix ${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}, ${timeFormat.format(Date(deadline))}"
                    val color = if (overdue) {
                        if (palette.isDark) 0xFFFFAAA3.toInt() else 0xFFAB3D36.toInt()
                    } else palette.onSurfaceVariant
                    dates.appendProperty(R.drawable.ic_cat_flag, label, color)
                    spokenDates.add(label)
                }
            }
            tvTaskMeta.text = dates
            tvTaskMeta.contentDescription = spokenDates.joinToString("; ")
            tvTaskMeta.setTextColor(palette.onSurfaceVariant)
            tvTaskMeta.visibility = if (dates.isEmpty()) View.GONE else View.VISIBLE
            layoutTaskMeta.visibility = View.VISIBLE
        }
    }
}
