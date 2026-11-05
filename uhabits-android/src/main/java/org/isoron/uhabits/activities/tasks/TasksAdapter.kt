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
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TasksAdapter(
    private var palette: SettingsThemePalette,
    private val onTaskClicked: (UserTask) -> Unit,
    private val onToggleComplete: (UserTask) -> Unit,
    private val onTaskLongClicked: (UserTask, View) -> Unit,
    private val onChangeDue: ((UserTask) -> Unit)? = null,
    private val onMoveContainer: ((UserTask) -> Unit)? = null,
    private val onShowContextMenu: ((UserTask) -> Unit)? = null,
    var hideContainerContext: Boolean = false,
) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

    companion object {
        /** Internal toggle for comparison between modern Compose task row and legacy Views. */
        var useComposeTaskRow: Boolean = true
    }

    private var tasks: List<UserTask> = emptyList()
    private var subtaskCounts: Map<TaskId, Pair<Int, Int>> = emptyMap() // taskId -> (completed, total)
    private var containers: Map<ContainerId, OrganizationalItem> = emptyMap()
    private var taskTags: Map<TaskId, List<TaskTag>> = emptyMap()
    private var today: LocalDate = LocalDate(2000, 1, 1)
    private var nowMillis: Long = System.currentTimeMillis()
    private var selectedIds: Set<TaskId> = emptySet()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun updateData(
        newTasks: List<UserTask>,
        newSubtaskCounts: Map<TaskId, Pair<Int, Int>>,
        newContainers: Map<ContainerId, OrganizationalItem>,
        currentToday: LocalDate,
        currentTimeMillis: Long,
        newSelectedIds: Set<TaskId> = selectedIds,
        newTaskTags: Map<TaskId, List<TaskTag>> = taskTags,
    ) {
        val oldSelectionActive = selectedIds.isNotEmpty()
        val newSelectionActive = newSelectedIds.isNotEmpty()
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
                    taskTags[oldT.id] == newTaskTags[newT.id] &&
                    today == currentToday &&
                    oldT.containerId?.let { containers[it] } == newT.containerId?.let { newContainers[it] } &&
                    oldT.isOverdue(today, nowMillis) == newT.isOverdue(currentToday, currentTimeMillis) &&
                    (oldT.id in selectedIds) == (newT.id in newSelectedIds) &&
                    oldSelectionActive == newSelectionActive
            }
        })
        tasks = newTasks
        subtaskCounts = newSubtaskCounts
        containers = newContainers
        taskTags = newTaskTags
        today = currentToday
        nowMillis = currentTimeMillis
        selectedIds = newSelectedIds.toSet()
        diff.dispatchUpdatesTo(this)
    }

    fun updateSelection(newSelectedIds: Set<TaskId>) {
        if (selectedIds == newSelectedIds) return
        updateData(tasks, subtaskCounts, containers, today, nowMillis, newSelectedIds, taskTags)
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
        var currentTask: UserTask? = null
            private set

        private val composeView: ComposeView? = itemView.findViewById(R.id.composeTaskRow)
        private val layoutLegacy: View? = itemView.findViewById(R.id.layoutLegacyTaskRow)
        private val btnCheck: FrameLayout? = itemView.findViewById(R.id.btnTaskCheck)
        private val viewCheckCircle: View? = itemView.findViewById(R.id.viewCheckCircle)
        private val ivCheckmark: ImageView? = itemView.findViewById(R.id.ivCheckmark)
        private val tvTitle: TextView? = itemView.findViewById(R.id.tvTaskTitle)
        private val layoutTaskMeta: LinearLayout? = itemView.findViewById(R.id.layoutTaskMeta)
        private val tvTaskContext: TextView? = itemView.findViewById(R.id.tvTaskContext)
        private val tvTaskMeta: TextView? = itemView.findViewById(R.id.tvTaskMeta)
        private val btnTaskMore: FrameLayout? = itemView.findViewById(R.id.btnTaskMore)
        private val ivTaskMore: ImageView? = itemView.findViewById(R.id.ivTaskMore)
        private val accessibilityActionIds = mutableListOf<Int>()

        init {
            composeView?.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
        }

        fun bind(task: UserTask) {
            currentTask = task
            itemView.translationX = 0f
            val isCompleted = task.isCompleted
            val isSelected = task.id in selectedIds
            val selectionActive = selectedIds.isNotEmpty()

            // Keep legacy test proxies populated
            tvTitle?.text = task.title
            btnCheck?.setOnClickListener { onToggleComplete(task) }

            if (useComposeTaskRow && composeView != null) {
                layoutLegacy?.visibility = View.GONE
                composeView.visibility = View.VISIBLE
                itemView.setBackgroundColor(palette.background)

                val danger = ContextCompat.getColor(
                    itemView.context,
                    if (palette.isDark) R.color.color_picker_danger_dark else R.color.color_picker_danger_light
                )
                val warning = if (palette.isDark) Color.parseColor("#FFB74D") else Color.parseColor("#B45309")
                val composeColors = UHabitComposeColors.fromPalette(palette, danger, warning)

                val containerItem = task.containerId?.let { containers[it] }
                val containerName = if (hideContainerContext) null else {
                    containerItem?.name ?: if (task.isCompleted) null else "Входящие"
                }
                val containerColor = containerItem?.color?.let {
                    TaskThemeUtils.resolvePaletteColor(itemView.context, it, palette.accent)
                }

                composeView.setContent {
                    UHabitComposeTheme(composeColors) {
                        TaskRowCompose(
                            task = task,
                            isCompleted = isCompleted,
                            isSelected = isSelected,
                            selectionActive = selectionActive,
                            subtaskCount = subtaskCounts[task.id],
                            containerName = containerName,
                            containerColor = containerColor,
                            tags = taskTags[task.id].orEmpty(),
                            today = today,
                            nowMillis = nowMillis,
                            colors = composeColors,
                            onTaskClicked = { onTaskClicked(task) },
                            onToggleComplete = { onToggleComplete(task) },
                            onTaskLongClicked = { onTaskLongClicked(task, itemView) },
                            onShowContextMenu = { onShowContextMenu?.invoke(task) ?: onTaskLongClicked(task, itemView) },
                            onChangeDue = onChangeDue?.let { cb -> { cb(task) } },
                            onMoveContainer = onMoveContainer?.let { cb -> { cb(task) } }
                        )
                    }
                }
            } else {
                composeView?.visibility = View.GONE
                layoutLegacy?.visibility = View.VISIBLE
                bindLegacy(task, isCompleted, isSelected, selectionActive)
            }
        }

        private fun bindLegacy(
            task: UserTask,
            isCompleted: Boolean,
            isSelected: Boolean,
            selectionActive: Boolean,
        ) {
            applyRowProfile()
            applySelectionBackground(isSelected)
            itemView.isActivated = isSelected
            ViewCompat.setStateDescription(itemView, if (isSelected) "Выбрано" else null)
            btnCheck?.contentDescription = when {
                selectionActive && isSelected -> "Снять выделение: ${task.title}"
                selectionActive -> "Выбрать: ${task.title}"
                isCompleted -> "Возобновить: ${task.title}"
                else -> "Завершить: ${task.title}"
            }

            // 1. Title & Strikethrough
            tvTitle?.let { titleView ->
                titleView.text = task.title
                if (isCompleted) {
                    titleView.setTextColor(palette.onSurfaceVariant)
                    titleView.paintFlags = titleView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    titleView.setTextColor(palette.onSurface)
                    titleView.paintFlags = titleView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
            }

            // Completion / Selection affordance inside 48dp target
            val circleDrawable = GradientDrawable()
            circleDrawable.shape = GradientDrawable.OVAL

            if (isSelected) {
                circleDrawable.setColor(palette.accent)
                circleDrawable.setStroke(dp(1.5f), palette.accent)
                viewCheckCircle?.background = circleDrawable
                ivCheckmark?.setImageResource(R.drawable.ic_action_check)
                ivCheckmark?.visibility = View.VISIBLE
                ivCheckmark?.setColorFilter(if (palette.isDark) Color.parseColor("#102638") else Color.WHITE)
            } else if (isCompleted) {
                circleDrawable.setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
                circleDrawable.setStroke(dp(1f), palette.onSurfaceVariant)
                viewCheckCircle?.background = circleDrawable
                ivCheckmark?.setImageResource(R.drawable.ic_action_check)
                ivCheckmark?.visibility = View.VISIBLE
                ivCheckmark?.setColorFilter(palette.onSurfaceVariant)
            } else {
                circleDrawable.setColor(Color.TRANSPARENT)
                val strokeWidth = TaskThemeUtils.dpInt(itemView.context, 1.5f)
                circleDrawable.setStroke(strokeWidth, MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.7f))
                viewCheckCircle?.background = circleDrawable
                ivCheckmark?.visibility = View.GONE
            }

            btnCheck?.setOnClickListener {
                onToggleComplete(task)
            }
            btnCheck?.setOnLongClickListener {
                onTaskLongClicked(task, it)
                true
            }

            // Quick action overflow trigger (48dp touch target)
            btnTaskMore?.let { btn ->
                if (selectionActive) {
                    btn.visibility = View.GONE
                } else {
                    btn.visibility = View.VISIBLE
                    btn.contentDescription = "Действия с задачей: ${task.title}"
                    TooltipCompat.setTooltipText(btn, "Действия")
                    ivTaskMore?.setColorFilter(palette.onSurfaceVariant)
                    btn.setOnClickListener {
                        onShowContextMenu?.invoke(task) ?: onTaskLongClicked(task, it)
                    }
                    btn.setOnLongClickListener {
                        onTaskLongClicked(task, it)
                        true
                    }
                }
            }

            // Secondary property lines with hierarchy
            bindMetadata(task)

            // Click & Long Click
            itemView.setOnClickListener {
                onTaskClicked(task)
            }
            itemView.setOnLongClickListener {
                onTaskLongClicked(task, it)
                true
            }
            bindAccessibilityActions(task, isSelected)
        }

        private fun applySelectionBackground(isSelected: Boolean) {
            if (isSelected) {
                itemView.setBackgroundColor(
                    MainTabsThemeBridge.withAlpha(palette.accent, if (palette.isDark) 0.18f else 0.10f)
                )
            } else {
                val value = android.util.TypedValue()
                itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                itemView.setBackgroundResource(value.resourceId)
            }
        }

        private fun bindAccessibilityActions(task: UserTask, isSelected: Boolean) {
            accessibilityActionIds.forEach { ViewCompat.removeAccessibilityAction(itemView, it) }
            accessibilityActionIds.clear()

            onChangeDue?.let { action ->
                val id = ViewCompat.addAccessibilityAction(itemView, "Изменить срок") { _, _ ->
                    action(task)
                    true
                }
                if (id != View.NO_ID) accessibilityActionIds.add(id)
            }
            onMoveContainer?.let { action ->
                val id = ViewCompat.addAccessibilityAction(itemView, "Переместить в раздел") { _, _ ->
                    action(task)
                    true
                }
                if (id != View.NO_ID) accessibilityActionIds.add(id)
            }
            val selectLabel = if (isSelected) "Снять выделение" else "Выбрать задачу"
            val selectId = ViewCompat.addAccessibilityAction(itemView, selectLabel) { view, _ ->
                onTaskLongClicked(task, view)
                true
            }
            if (selectId != View.NO_ID) accessibilityActionIds.add(selectId)

            onShowContextMenu?.let { action ->
                val id = ViewCompat.addAccessibilityAction(itemView, "Действия с задачей") { _, _ ->
                    action(task)
                    true
                }
                if (id != View.NO_ID) accessibilityActionIds.add(id)
            }

            val toggleLabel = if (task.isCompleted) "Возобновить задачу" else "Завершить задачу"
            val toggleId = ViewCompat.addAccessibilityAction(itemView, toggleLabel) { _, _ ->
                onToggleComplete(task)
                true
            }
            if (toggleId != View.NO_ID) accessibilityActionIds.add(toggleId)
        }

        private fun applyRowProfile() {
            itemView.setPaddingRelative(dp(6f), dp(8f), dp(4f), dp(8f))
            tvTitle?.let { titleView ->
                titleView.textSize = 15.5f
                titleView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                titleView.maxLines = 4
                titleView.ellipsize = TextUtils.TruncateAt.END
                titleView.setLineSpacing(dp(2.5f).toFloat(), 1.12f)
            }
            tvTaskContext?.textSize = 12f
            tvTaskMeta?.let { metaView ->
                metaView.textSize = 12f
                metaView.maxLines = 3
                metaView.ellipsize = TextUtils.TruncateAt.END
                metaView.setLineSpacing(dp(1.5f).toFloat(), 1.08f)
            }
            layoutTaskMeta?.let {
                (it.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = dp(4f) }
            }
            tvTaskMeta?.let {
                (it.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = 0 }
            }
            viewCheckCircle?.let {
                it.layoutParams = (it.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    width = dp(20f)
                    height = dp(20f)
                    topMargin = dp(2f)
                } ?: it.layoutParams
            }
            ivCheckmark?.let {
                it.layoutParams = (it.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    topMargin = dp(6f)
                } ?: it.layoutParams
            }
        }

        private fun dp(value: Float) = TaskThemeUtils.dpInt(itemView.context, value)

        private fun SpannableStringBuilder.appendProperty(icon: Int, label: String, color: Int) {
            if (isNotEmpty()) append("  ·  ")
            val start = length
            val drawable = AppCompatResources.getDrawable(itemView.context, icon)?.mutate()?.apply {
                setTint(color)
                val size = dp(12.5f)
                setBounds(0, 0, size, size)
            }
            if (drawable != null) {
                append("\uFFFC ")
                setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            append(label)
            setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun bindMetadata(task: UserTask) {
            val meta = SpannableStringBuilder()
            val spokenParts = mutableListOf<String>()

            // 1. Priority Hierarchy (Eisenhower matrix: Important & Urgent)
            if (!task.isCompleted) {
                when {
                    task.important == true && task.urgent == true -> {
                        meta.appendProperty(R.drawable.ic_cat_flame, "Срочно и важно", dangerColor())
                        spokenParts.add("Приоритет: срочно и важно")
                    }
                    task.important == true -> {
                        meta.appendProperty(R.drawable.ic_cat_star, "Важно", warningColor())
                        spokenParts.add("Приоритет: важно")
                    }
                    task.urgent == true -> {
                        meta.appendProperty(R.drawable.ic_cat_flame, "Срочно", warningColor())
                        spokenParts.add("Приоритет: срочно")
                    }
                }
            }

            // 2. Dates / Completion Status
            if (!task.isCompleted) {
                when (val due = task.due) {
                    is TaskDue.None -> {
                        if (task.hasPendingLegacyPlan) {
                            val label = "План: ${task.legacyPlanDate}"
                            meta.appendProperty(R.drawable.ic_cat_calendar, label, palette.onSurfaceVariant)
                            spokenParts.add(label)
                        }
                    }
                    is TaskDue.Day -> {
                        val d = due.date.toLocalDate()
                        val overdue = task.isOverdue(today, nowMillis)
                        val isToday = task.isDueToday(today, nowMillis)
                        val label = when {
                            overdue -> "Просрочено · ${d.day}.${d.month.toString().padStart(2, '0')}"
                            isToday -> "Сегодня"
                            d.daysSince2000 == today.daysSince2000 + 1 -> "Завтра"
                            else -> TaskDueDisplayFormatter.format(due, TaskDueDisplayFormatter.selectedLocale(itemView.context))
                        }
                        val color = when {
                            overdue -> dangerColor()
                            isToday -> palette.accent
                            else -> palette.onSurfaceVariant
                        }
                        meta.appendProperty(R.drawable.ic_cat_calendar, label, color)
                        spokenParts.add(label)
                    }
                    is TaskDue.Timed -> {
                        val overdue = task.isOverdue(today, nowMillis)
                        val isToday = task.isDueToday(today, nowMillis)
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = due.epochMillis }
                        val timeStr = timeFormat.format(Date(due.epochMillis))
                        val label = when {
                            overdue -> "Просрочено · ${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}, $timeStr"
                            isToday -> "Сегодня, $timeStr"
                            else -> TaskDueDisplayFormatter.format(due, TaskDueDisplayFormatter.selectedLocale(itemView.context))
                        }
                        val color = when {
                            overdue -> dangerColor()
                            isToday -> palette.accent
                            else -> palette.onSurfaceVariant
                        }
                        meta.appendProperty(R.drawable.ic_cat_calendar_clock, label, color)
                        spokenParts.add(label)
                    }
                }
            }

            val completedAt = task.completedAt
            if (task.isCompleted && completedAt != null && completedAt > 0L) {
                val label = "Выполнено " + SimpleDateFormat("d MMM", TaskDueDisplayFormatter.selectedLocale(itemView.context)).format(Date(completedAt))
                meta.appendProperty(R.drawable.ic_action_check, label, palette.onSurfaceVariant)
                spokenParts.add(label)
            }

            // 3. Organizational Container Context (suppressed if context matches)
            if (!hideContainerContext) {
                val containerName = task.containerId?.let { containers[it]?.name ?: "Раздел" } ?: if (task.isCompleted) "Без раздела" else "Входящие"
                meta.appendProperty(R.drawable.ic_cat_folder, containerName, palette.onSurfaceVariant)
                spokenParts.add("Раздел: $containerName")
            }

            // 4. Subtasks Progress
            val counts = subtaskCounts[task.id]
            if (counts != null && counts.second > 0) {
                val icon = if (counts.first == counts.second) R.drawable.ic_cat_check_square else R.drawable.ic_cat_list_todo
                val subtaskColor = if (counts.first == counts.second && !task.isCompleted) palette.accent else palette.onSurfaceVariant
                meta.appendProperty(icon, "${counts.first}/${counts.second}", subtaskColor)
                spokenParts.add("подзадачи: ${counts.first} из ${counts.second}")
            }

            // 5. Tags
            val tags = taskTags[task.id].orEmpty()
            if (tags.isNotEmpty()) {
                val visibleTags = tags.take(2)
                val extraCount = tags.size - visibleTags.size
                val tagText = buildString {
                    append(visibleTags.joinToString("  ") { "#${it.name}" })
                    if (extraCount > 0) append(" +$extraCount")
                }
                val tagColor = if (task.isCompleted) palette.onSurfaceVariant else MainTabsThemeBridge.withAlpha(palette.accent, 0.9f)
                meta.appendProperty(R.drawable.ic_cat_tag, tagText, tagColor)
                spokenParts.add("теги: ${tags.joinToString(", ") { it.name }}")
            }

            // 6. Notes indicator (hierarchy hint)
            if (task.description.isNotBlank()) {
                meta.appendProperty(R.drawable.ic_cat_file_text, "Заметка", palette.onSurfaceVariant)
                spokenParts.add("есть заметка")
            }

            // 7. Linked Habit indicator (hierarchy hint)
            if (task.linkedHabitUuid != null) {
                meta.appendProperty(R.drawable.ic_cat_repeat, "Привычка", palette.onSurfaceVariant)
                spokenParts.add("связана с привычкой")
            }

            tvTaskContext?.visibility = View.GONE
            tvTaskMeta?.let { metaView ->
                metaView.text = meta
                metaView.contentDescription = spokenParts.joinToString("; ")
                metaView.setTextColor(palette.onSurfaceVariant)
                metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            }
            layoutTaskMeta?.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun dangerColor() = ContextCompat.getColor(itemView.context,
            if (palette.isDark) R.color.color_picker_danger_dark else R.color.color_picker_danger_light)

        private fun warningColor() = if (palette.isDark) Color.parseColor("#FFB74D") else Color.parseColor("#B45309")
    }
}
