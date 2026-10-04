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

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TasksFragment : Fragment() {

    private lateinit var taskRepo: UserTaskRepository
    private lateinit var taskService: UserTaskService
    private lateinit var orgFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette

    private var quickPlanned: LocalDate? = null
    private var quickContainer: ContainerId? = null
    private var quickDue: Long? = null
    private var quickDraftRestored = false
    private var quickExpanded = false
    private var activeSmartView: TaskSmartView = TaskSmartView.TODAY
    private var selectedContainerFilter: ContainerId? = null

    private lateinit var tvTitle: TextView
    private lateinit var btnTasksOverflow: ImageButton
    private lateinit var btnSmartView: TextView
    private lateinit var btnContainerFilter: LinearLayout
    private lateinit var viewFilterDot: View
    private lateinit var tvFilterName: TextView
    private lateinit var ivFilterArrow: ImageView

    private lateinit var tvSubtitle: TextView
    private lateinit var tvTasksDate: TextView

    private lateinit var rvTasks: RecyclerView
    private lateinit var adapter: TasksAdapter
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyMessage: TextView

    private lateinit var layoutQuickAddContainer: LinearLayout
    private lateinit var quickAddDivider: View
    private lateinit var layoutQuickAddCollapsed: LinearLayout
    private lateinit var ivQuickAddIcon: ImageView
    private lateinit var tvQuickAddCollapsedLabel: TextView
    private lateinit var layoutQuickAddExpanded: LinearLayout
    private lateinit var etQuickAddTitle: EditText
    private lateinit var btnQuickAddSubmit: ImageButton
    private lateinit var quickAddOptions: LinearLayout
    private lateinit var quickPlan: TextView
    private lateinit var quickContainerView: TextView
    private lateinit var quickDeadline: TextView

    private val dateFormatRu = SimpleDateFormat("EEE, d MMMM", Locale("ru"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        savedInstanceState?.let {
            activeSmartView = TaskSmartView.entries[it.getInt("smartView")]
            selectedContainerFilter = it.getString("filter")?.let(::ContainerId)
            quickPlanned = if (it.containsKey("quickPlan")) LocalDate(it.getInt("quickPlan")) else null
            quickContainer = it.getString("quickContainer")?.let(::ContainerId)
            quickDue = if (it.containsKey("quickDue")) it.getLong("quickDue") else null
            quickExpanded = it.getBoolean("quickExpanded", false)
            quickDraftRestored = true
        }
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
            v.setPadding(v.paddingLeft, topInset + dpInt(6f), v.paddingRight, v.paddingBottom)
            insets
        }
        bindViews(view)
        setupSmartViewSelector()
        setupOverflowMenu()
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
        adapter.updatePalette(palette)
        (childFragmentManager.findFragmentByTag("task_detail") as? TaskDetailBottomSheet)?.onTaskUpdatedListener = { if (view != null) loadTasks() }
        loadTasks()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("smartView", activeSmartView.ordinal)
        outState.putString("filter", selectedContainerFilter?.value)
        quickPlanned?.let { outState.putInt("quickPlan", it.daysSince2000) }
        outState.putString("quickContainer", quickContainer?.value)
        quickDue?.let { outState.putLong("quickDue", it) }
        outState.putBoolean("quickExpanded", quickExpanded)
        super.onSaveInstanceState(outState)
    }

    fun refresh() {
        if (isAdded && view != null) {
            loadTasks()
        }
    }

    private fun bindViews(view: View) {
        tvTitle = view.findViewById(R.id.tvTasksTitle)
        btnTasksOverflow = view.findViewById(R.id.btnTasksOverflow)
        btnSmartView = view.findViewById(R.id.btnSmartView)
        btnContainerFilter = view.findViewById(R.id.btnContainerFilter)
        viewFilterDot = view.findViewById(R.id.viewFilterColorDot)
        tvFilterName = view.findViewById(R.id.tvContainerFilterName)
        ivFilterArrow = view.findViewById(R.id.ivContainerFilterArrow)

        tvSubtitle = view.findViewById(R.id.tvTasksSubtitle)
        tvTasksDate = view.findViewById(R.id.tvTasksDate)

        rvTasks = view.findViewById(R.id.rvTasks)
        layoutEmpty = view.findViewById(R.id.layoutEmptyTasks)
        tvEmptyTitle = view.findViewById(R.id.tvEmptyTitle)
        tvEmptyMessage = view.findViewById(R.id.tvEmptyMessage)

        layoutQuickAddContainer = view.findViewById(R.id.layoutQuickAddContainer)
        quickAddDivider = view.findViewById(R.id.quickAddDivider)
        layoutQuickAddCollapsed = view.findViewById(R.id.layoutQuickAddCollapsed)
        ivQuickAddIcon = view.findViewById(R.id.ivQuickAddIcon)
        tvQuickAddCollapsedLabel = view.findViewById(R.id.tvQuickAddCollapsedLabel)
        layoutQuickAddExpanded = view.findViewById(R.id.layoutQuickAddExpanded)
        etQuickAddTitle = view.findViewById(R.id.etQuickAddTitle)
        btnQuickAddSubmit = view.findViewById(R.id.btnQuickAddSubmit)
        quickAddOptions = view.findViewById(R.id.quickAddOptions)
        quickPlan = view.findViewById(R.id.quickPlan)
        quickContainerView = view.findViewById(R.id.quickContainer)
        quickDeadline = view.findViewById(R.id.quickDeadline)
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(requireContext(), value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(requireContext(), value)

    private fun applyTheme(view: View) {
        view.setBackgroundColor(palette.background)
        androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, view).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }

        tvTitle.setTextColor(palette.onSurface)
        btnTasksOverflow.setColorFilter(palette.onSurfaceVariant)
        tvSubtitle.setTextColor(palette.onSurfaceVariant)
        tvTasksDate.setTextColor(palette.onSurfaceVariant)
        tvFilterName.setTextColor(palette.onSurfaceVariant)
        ivFilterArrow.setColorFilter(palette.onSurfaceVariant)

        // Concept A: Container filter is clean secondary text + chevron, NO heavy pill background!

        view.findViewById<ImageView>(R.id.ivEmptyIcon).setColorFilter(palette.onSurfaceVariant)
        tvEmptyTitle.setTextColor(palette.onSurface)
        tvEmptyMessage.setTextColor(palette.onSurfaceVariant)

        // Concept A: Quick Add is docked at the bottom with top hairline divider
        layoutQuickAddContainer.setBackgroundColor(palette.surface)
        quickAddDivider.setBackgroundColor(palette.divider)
        ivQuickAddIcon.setColorFilter(palette.accent)
        tvQuickAddCollapsedLabel.setTextColor(palette.onSurfaceVariant)
        etQuickAddTitle.setTextColor(palette.onSurface)
        etQuickAddTitle.setHintTextColor(palette.onSurfaceVariant)
        btnQuickAddSubmit.setColorFilter(palette.accent)

        quickPlan.setTextColor(palette.onSurfaceVariant)
        quickContainerView.setTextColor(palette.onSurfaceVariant)
        quickDeadline.setTextColor(palette.onSurfaceVariant)

        updateSmartViewLabel()
    }

    private fun setupOverflowMenu() {
        btnTasksOverflow.setOnClickListener {
            val popup = PopupMenu(requireContext(), btnTasksOverflow)
            popup.menu.add(0, 1, 0, "Все задачи")
            popup.menu.add(0, 2, 1, "Завершённые")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        activeSmartView = TaskSmartView.ALL
                        updateSmartViewLabel()
                        loadTasks()
                        true
                    }
                    2 -> {
                        activeSmartView = TaskSmartView.COMPLETED
                        updateSmartViewLabel()
                        loadTasks()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun setupSmartViewSelector() {
        btnSmartView.setOnClickListener {
            val modes = TaskSmartView.entries
            AlertDialog.Builder(requireContext())
                .setTitle("Показать задачи")
                .setSingleChoiceItems(modes.map { it.displayName }.toTypedArray(), activeSmartView.ordinal) { dialog, which ->
                    activeSmartView = modes[which]
                    updateSmartViewLabel()
                    loadTasks()
                    dialog.dismiss()
                }.show()
        }
    }

    private fun updateSmartViewLabel() {
        btnSmartView.text = "${activeSmartView.displayName} ▾"
        btnSmartView.setTextColor(palette.onSurface)
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
        fun refreshOptionLabels() {
            val planText = quickPlanned?.let {
                if (it == getToday()) "📅 Сегодня" else "📅 ${it.day}.${it.month}"
            } ?: "📅 План"
            quickPlan.text = planText
            quickPlan.setTextColor(if (quickPlanned != null) palette.accent else palette.onSurfaceVariant)

            val containerItem = orgFacade.availableContainers().find { it.key == quickContainer?.value }
            val containerText = containerItem?.let { "📁 ${it.name}" } ?: "📁 Раздел"
            quickContainerView.text = containerText
            quickContainerView.setTextColor(if (quickContainer != null) palette.accent else palette.onSurfaceVariant)

            val deadlineText = if (quickDue == null) "🕒 Дедлайн" else "🕒 Дедлайн ✓"
            quickDeadline.text = deadlineText
            quickDeadline.setTextColor(if (quickDue != null) palette.accent else palette.onSurfaceVariant)
        }

        fun expandComposer() {
            quickExpanded = true
            layoutQuickAddCollapsed.visibility = View.GONE
            layoutQuickAddExpanded.visibility = View.VISIBLE
            etQuickAddTitle.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etQuickAddTitle, InputMethodManager.SHOW_IMPLICIT)

            if (etQuickAddTitle.text.isNullOrBlank() && !quickDraftRestored) {
                quickPlanned = if (activeSmartView == TaskSmartView.TODAY) getToday() else null
                quickContainer = if (activeSmartView == TaskSmartView.INBOX) null else selectedContainerFilter
                quickDue = null
            }
            refreshOptionLabels()
        }

        fun collapseComposer() {
            quickExpanded = false
            layoutQuickAddExpanded.visibility = View.GONE
            layoutQuickAddCollapsed.visibility = View.VISIBLE
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etQuickAddTitle.windowToken, 0)
        }

        layoutQuickAddCollapsed.setOnClickListener {
            expandComposer()
        }

        if (quickExpanded) {
            expandComposer()
        } else {
            collapseComposer()
        }

        quickPlan.setOnClickListener {
            AlertDialog.Builder(requireContext()).setItems(arrayOf("Сегодня", "Завтра", "Выбрать дату", "Без плана")) { _, choice ->
                when (choice) {
                    0 -> quickPlanned = getToday()
                    1 -> quickPlanned = LocalDate(getToday().daysSince2000 + 1)
                    3 -> quickPlanned = null
                    2 -> {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(requireContext(), { _, y, m, d -> quickPlanned = LocalDate(y, m + 1, d); refreshOptionLabels() },
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }
                }
                refreshOptionLabels()
            }.show()
        }

        quickContainerView.setOnClickListener {
            val items = orgFacade.availableContainers()
            AlertDialog.Builder(requireContext()).setItems((listOf("Без раздела") + items.map { it.name }).toTypedArray()) { _, index ->
                quickContainer = if (index == 0) null else ContainerId(items[index - 1].key)
                refreshOptionLabels()
            }.show()
        }

        quickDeadline.setOnClickListener {
            AlertDialog.Builder(requireContext()).setItems(arrayOf("Выбрать дедлайн", "Без дедлайна")) { _, choice ->
                if (choice == 1) { quickDue = null; refreshOptionLabels() } else {
                    val cal = Calendar.getInstance()
                    DatePickerDialog(requireContext(), { _, y, m, d ->
                        TimePickerDialog(requireContext(), { _, h, minute ->
                            quickDue = Calendar.getInstance().apply { set(y, m, d, h, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                            refreshOptionLabels()
                        }, 18, 0, true).show()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }
            }.show()
        }

        fun submitQuickAdd() {
            val title = etQuickAddTitle.text?.toString()?.trim().orEmpty()
            if (title.isBlank()) return
            val created = taskService.createTask(title = title, containerId = quickContainer, plannedDate = quickPlanned, deadline = quickDue)
            etQuickAddTitle.text?.clear()
            quickDraftRestored = false
            collapseComposer()

            // Reveal the new task even when it no longer matches the current view/filter.
            if (!activeSmartView.matches(created, getToday(), System.currentTimeMillis())) {
                activeSmartView = TaskSmartView.ALL
                updateSmartViewLabel()
            }
            if (quickContainer != selectedContainerFilter) {
                selectedContainerFilter = quickContainer
                updateContainerFilterView()
            }
            loadTasks()
        }

        btnQuickAddSubmit.setOnClickListener { submitQuickAdd() }
        etQuickAddTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitQuickAdd(); true } else false
        }
        refreshOptionLabels()
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
        val viewFiltered = containerFiltered.filter { activeSmartView.matches(it, today, nowMillis) }

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

        // Date indicator on the right (shown in TODAY mode)
        if (activeSmartView == TaskSmartView.TODAY) {
            val dateStr = dateFormatRu.format(Date(nowMillis))
            tvTasksDate.text = dateStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
            tvTasksDate.visibility = View.VISIBLE
        } else {
            tvTasksDate.visibility = View.GONE
        }

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
        if (selectedContainerFilter != null) {
            tvEmptyTitle.text = "В этом разделе нет задач"
            tvEmptyMessage.text = "Выберите другой режим или добавьте задачу ниже"
            return
        }
        when (activeSmartView) {
            TaskSmartView.TODAY -> {
                tvEmptyTitle.text = "Сегодня пока нет задач"
                tvEmptyMessage.text = "Нет запланированных или просроченных задач"
            }
            TaskSmartView.INBOX -> {
                tvEmptyTitle.text = "Входящие пусты"
                tvEmptyMessage.text = "Здесь появляются задачи без раздела"
            }
            TaskSmartView.UPCOMING -> {
                tvEmptyTitle.text = "Нет предстоящих задач"
                tvEmptyMessage.text = "Выберите плановую дату в редакторе задачи"
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
