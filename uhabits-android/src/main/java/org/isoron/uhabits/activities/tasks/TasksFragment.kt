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

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.UserTaskService

class TasksFragment : Fragment() {

    private lateinit var taskRepo: UserTaskRepository
    private lateinit var taskService: UserTaskService
    private lateinit var orgFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette

    private var activeSmartView: TaskSmartView = TaskSmartView.TODAY
    private var selectedContainerFilter: ContainerId? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnContainerFilter: LinearLayout
    private lateinit var viewFilterDot: View
    private lateinit var tvFilterName: TextView
    private lateinit var ivFilterArrow: ImageView

    private lateinit var chipToday: TextView
    private lateinit var chipInbox: TextView
    private lateinit var chipUpcoming: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipCompleted: TextView

    private lateinit var rvTasks: RecyclerView
    private lateinit var adapter: TasksAdapter
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyMessage: TextView

    private lateinit var layoutQuickAdd: LinearLayout
    private lateinit var etQuickAddTitle: EditText
    private lateinit var btnQuickAddSubmit: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val app = requireContext().applicationContext as HabitsApplication
        taskRepo = app.component.userTaskRepository
        taskService = app.component.userTaskService
        orgFacade = app.component.organizationFacade
        palette = MainTabsThemeBridge.resolve(requireContext())

        val view = inflater.inflate(R.layout.fragment_tasks, container, false)
        val tasksHeader = view.findViewById<View>(R.id.tasksHeader)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(tasksHeader) { v, insets ->
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBars.top, cutout.top)
            v.setPadding(v.paddingLeft, topInset + dpInt(12f), v.paddingRight, v.paddingBottom)
            insets
        }
        bindViews(view)
        setupSmartViewChips()
        setupRecyclerView()
        setupQuickAdd()
        setupContainerFilter()
        applyTheme(view)
        loadTasks()
        return view
    }

    override fun onResume() {
        super.onResume()
        palette = MainTabsThemeBridge.resolve(requireContext())
        view?.let { applyTheme(it) }
        loadTasks()
    }

    fun refresh() {
        if (isAdded && view != null) {
            loadTasks()
        }
    }

    private fun bindViews(view: View) {
        tvTitle = view.findViewById(R.id.tvTasksTitle)
        tvSubtitle = view.findViewById(R.id.tvTasksSubtitle)
        btnContainerFilter = view.findViewById(R.id.btnContainerFilter)
        viewFilterDot = view.findViewById(R.id.viewFilterColorDot)
        tvFilterName = view.findViewById(R.id.tvContainerFilterName)
        ivFilterArrow = view.findViewById(R.id.ivContainerFilterArrow)

        chipToday = view.findViewById(R.id.chipToday)
        chipInbox = view.findViewById(R.id.chipInbox)
        chipUpcoming = view.findViewById(R.id.chipUpcoming)
        chipAll = view.findViewById(R.id.chipAll)
        chipCompleted = view.findViewById(R.id.chipCompleted)

        rvTasks = view.findViewById(R.id.rvTasks)
        layoutEmpty = view.findViewById(R.id.layoutEmptyTasks)
        tvEmptyTitle = view.findViewById(R.id.tvEmptyTitle)
        tvEmptyMessage = view.findViewById(R.id.tvEmptyMessage)

        layoutQuickAdd = view.findViewById(R.id.layoutQuickAdd)
        etQuickAddTitle = view.findViewById(R.id.etQuickAddTitle)
        btnQuickAddSubmit = view.findViewById(R.id.btnQuickAddSubmit)
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(requireContext(), value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(requireContext(), value)

    private fun applyTheme(view: View) {
        view.setBackgroundColor(palette.background)

        tvTitle.setTextColor(palette.onSurface)
        tvSubtitle.setTextColor(palette.onSurfaceVariant)
        tvFilterName.setTextColor(palette.onSurface)
        ivFilterArrow.setColorFilter(palette.onSurfaceVariant)

        val filterBg = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
        }
        btnContainerFilter.background = filterBg

        tvEmptyTitle.setTextColor(palette.onSurface)
        tvEmptyMessage.setTextColor(palette.onSurfaceVariant)

        val quickAddBg = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setColor(palette.surface)
            setStroke(dpInt(1f), palette.border)
        }
        layoutQuickAdd.background = quickAddBg
        etQuickAddTitle.setTextColor(palette.onSurface)
        etQuickAddTitle.setHintTextColor(palette.onSurfaceVariant)
        btnQuickAddSubmit.setColorFilter(palette.accent)

        updateChipStyles()
    }

    private fun setupSmartViewChips() {
        chipToday.setOnClickListener { switchSmartView(TaskSmartView.TODAY) }
        chipInbox.setOnClickListener { switchSmartView(TaskSmartView.INBOX) }
        chipUpcoming.setOnClickListener { switchSmartView(TaskSmartView.UPCOMING) }
        chipAll.setOnClickListener { switchSmartView(TaskSmartView.ALL) }
        chipCompleted.setOnClickListener { switchSmartView(TaskSmartView.COMPLETED) }
    }

    private fun switchSmartView(view: TaskSmartView) {
        activeSmartView = view
        updateChipStyles()
        loadTasks()
    }

    private fun updateChipStyles() {
        val chips = mapOf(
            TaskSmartView.TODAY to chipToday,
            TaskSmartView.INBOX to chipInbox,
            TaskSmartView.UPCOMING to chipUpcoming,
            TaskSmartView.ALL to chipAll,
            TaskSmartView.COMPLETED to chipCompleted,
        )

        for ((view, chip) in chips) {
            val isActive = view == activeSmartView
            val bg = GradientDrawable().apply {
                cornerRadius = dp(16f)
                if (isActive) {
                    setColor(palette.accent)
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.10f))
                }
            }
            chip.background = bg
            chip.setTextColor(if (isActive) Color.WHITE else palette.onSurface)
        }
    }

    private fun setupRecyclerView() {
        adapter = TasksAdapter(
            palette = palette,
            onTaskClicked = { task ->
                val sheet = TaskDetailBottomSheet.newInstance(task.id)
                sheet.onTaskUpdatedListener = { loadTasks() }
                sheet.show(childFragmentManager, "task_detail")
            },
            onToggleComplete = { task ->
                taskService.toggleComplete(task.id)
                loadTasks()
            },
            onTaskLongClicked = { task, _ ->
                showTaskQuickMenu(task)
            }
        )
        rvTasks.layoutManager = LinearLayoutManager(requireContext())
        rvTasks.adapter = adapter
    }

    private fun setupQuickAdd() {
        fun submitQuickAdd() {
            val title = etQuickAddTitle.text?.toString()?.trim() ?: ""
            if (title.isBlank()) return

            val today = runCatching { getToday() }.getOrDefault(LocalDate(2026, 10, 4))
            val planned = if (activeSmartView == TaskSmartView.TODAY) today else null

            taskService.createTask(
                title = title,
                containerId = selectedContainerFilter,
                plannedDate = planned,
            )
            etQuickAddTitle.text?.clear()
            loadTasks()
        }

        btnQuickAddSubmit.setOnClickListener { submitQuickAdd() }
        etQuickAddTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitQuickAdd()
                true
            } else false
        }
    }

    private fun setupContainerFilter() {
        btnContainerFilter.setOnClickListener {
            val containers = orgFacade.availableContainers()
            val names = mutableListOf("Все разделы")
            names.addAll(containers.map { it.name })

            AlertDialog.Builder(requireContext())
                .setTitle("Фильтр по разделу")
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        selectedContainerFilter = null
                    } else {
                        selectedContainerFilter = ContainerId(containers[which - 1].key)
                    }
                    updateContainerFilterView()
                    loadTasks()
                }
                .show()
        }
        updateContainerFilterView()
    }

    private fun updateContainerFilterView() {
        val cid = selectedContainerFilter
        if (cid == null) {
            viewFilterDot.visibility = View.GONE
            tvFilterName.text = "Все разделы"
        } else {
            val containers = orgFacade.availableContainers()
            val item = containers.find { it.key == cid.value }
            if (item != null) {
                viewFilterDot.visibility = View.VISIBLE
                val dotColor = TaskThemeUtils.resolvePaletteColor(requireContext(), item.color, palette.accent)
                viewFilterDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
                tvFilterName.text = item.name
            } else {
                viewFilterDot.visibility = View.GONE
                tvFilterName.text = "Раздел"
            }
        }
    }

    fun loadTasks() {
        val allTasks = taskRepo.getAll()
        val allRoots = allTasks.filter { it.parentTaskId == null }

        // Compute subtasks count for each root task
        val subtaskMap = mutableMapOf<TaskId, Pair<Int, Int>>()
        for (root in allRoots) {
            val children = allTasks.filter { it.parentTaskId == root.id }
            if (children.isNotEmpty()) {
                val completed = children.count { it.isCompleted }
                subtaskMap[root.id] = completed to children.size
            }
        }

        val today = runCatching { getToday() }.getOrDefault(LocalDate(2026, 10, 4))
        val nowMillis = System.currentTimeMillis()

        // 1. Container filter
        val containerFiltered = if (selectedContainerFilter != null) {
            allRoots.filter { it.containerId == selectedContainerFilter }
        } else {
            allRoots
        }

        // 2. Smart View filter
        val viewFiltered = when (activeSmartView) {
            TaskSmartView.TODAY -> containerFiltered.filter { task ->
                !task.isCompleted && (
                    task.plannedDate == today ||
                    task.isMissedPlan(today, nowMillis) ||
                    task.isHardOverdue(nowMillis)
                )
            }
            TaskSmartView.INBOX -> containerFiltered.filter { task ->
                !task.isCompleted && (task.containerId == null || (task.plannedDate == null && task.deadline == null))
            }
            TaskSmartView.UPCOMING -> containerFiltered.filter { task ->
                !task.isCompleted && (
                    (task.plannedDate != null && task.plannedDate!! > today) ||
                    (task.deadline != null && task.deadline!! > nowMillis && (task.plannedDate == null || task.plannedDate!! >= today))
                )
            }
            TaskSmartView.ALL -> containerFiltered.filter { !it.isCompleted }
            TaskSmartView.COMPLETED -> containerFiltered.filter { it.isCompleted }
        }

        // Sort: pending by hard overdue first, then missed plan, then sortOrder, then plannedDate; completed by completedAt DESC
        val sorted = if (activeSmartView == TaskSmartView.COMPLETED) {
            viewFiltered.sortedByDescending { it.completedAt ?: 0L }
        } else {
            viewFiltered.sortedWith(
                compareBy<UserTask> { !it.isHardOverdue(nowMillis) }
                    .thenBy { !it.isMissedPlan(today, nowMillis) }
                    .thenBy { it.sortOrder }
                    .thenBy { it.plannedDate?.daysSince2000 ?: Int.MAX_VALUE }
            )
        }

        val containerItemMap = orgFacade.availableContainers().associateBy { ContainerId(it.key) }

        adapter.updateData(
            newTasks = sorted,
            newSubtaskCounts = subtaskMap,
            newContainers = containerItemMap,
            currentToday = today,
            currentTimeMillis = nowMillis,
        )

        // Subtitle count
        tvSubtitle.text = "${sorted.size} ${pluralTasks(sorted.size)}"

        // Empty view visibility
        if (sorted.isEmpty()) {
            rvTasks.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            updateEmptyMessage()
        } else {
            rvTasks.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
        }
    }

    private fun updateEmptyMessage() {
        when (activeSmartView) {
            TaskSmartView.TODAY -> {
                tvEmptyTitle.text = "На сегодня всё сделано"
                tvEmptyMessage.text = "Нет запланированных или просроченных задач"
            }
            TaskSmartView.INBOX -> {
                tvEmptyTitle.text = "Входящие пусты"
                tvEmptyMessage.text = "Все задачи распределены по разделам и датам"
            }
            TaskSmartView.UPCOMING -> {
                tvEmptyTitle.text = "Нет предстоящих задач"
                tvEmptyMessage.text = "Запланируйте задачи на будущее в карточке задачи"
            }
            TaskSmartView.ALL -> {
                tvEmptyTitle.text = "Нет активных задач"
                tvEmptyMessage.text = "Добавьте новую задачу через строку внизу"
            }
            TaskSmartView.COMPLETED -> {
                tvEmptyTitle.text = "Нет завершённых задач"
                tvEmptyMessage.text = "Выполняйте задачи, нажимая на кружок слева"
            }
        }
    }

    private fun pluralTasks(count: Int): String {
        val rem10 = count % 10
        val rem100 = count % 100
        return when {
            rem100 in 11..19 -> "задач"
            rem10 == 1 -> "задача"
            rem10 in 2..4 -> "задачи"
            else -> "задач"
        }
    }

    private fun showTaskQuickMenu(task: UserTask) {
        val actions = arrayOf("Редактировать", if (task.isCompleted) "Отметить невыполненной" else "Завершить", "Удалить")
        AlertDialog.Builder(requireContext())
            .setTitle(task.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val sheet = TaskDetailBottomSheet.newInstance(task.id)
                        sheet.onTaskUpdatedListener = { loadTasks() }
                        sheet.show(childFragmentManager, "task_detail")
                    }
                    1 -> {
                        taskService.toggleComplete(task.id)
                        loadTasks()
                    }
                    2 -> {
                        taskService.deleteTask(task.id, cascadeSubtasks = true)
                        loadTasks()
                    }
                }
            }
            .show()
    }
}
