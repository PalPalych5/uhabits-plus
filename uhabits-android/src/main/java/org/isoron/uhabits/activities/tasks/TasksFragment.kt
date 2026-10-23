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
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import org.isoron.platform.io.runInTransaction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.isoron.uhabits.inject.HabitsApplicationComponent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainNavigationHost
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
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TasksFragment : Fragment() {

    private lateinit var taskRepo: UserTaskRepository
    private lateinit var taskService: UserTaskService
    private lateinit var orgFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette
    private lateinit var component: HabitsApplicationComponent
    private var workspaceSnapshot: TasksWorkspaceSnapshot? = null
    private var snapshotRevision = -1L
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var workspaceActive = false
    private var refreshQuickLabels: (() -> Unit)? = null
    private val workspaceChanged: () -> Unit = {
        activity?.runOnUiThread { if (workspaceActive && view != null) loadTasks() }
    }

    var workspaceNavigator: ((String) -> Unit)? = null
    private lateinit var shellRoot: FrameLayout
    private lateinit var navigationColumn: LinearLayout
    private lateinit var representationButton: ImageButton
    private lateinit var calendarPanel: LinearLayout
    private var drawerScrim: View? = null
    private var drawerScroll: ScrollView? = null
    private var localTreeRoot: ContainerId? = null
    private val collapsedContainers = mutableSetOf<String>()
    private var calendarMode = false
    private var renderedCalendarMonth: String? = null
    private var selectedDay = getToday()
    private var calendarMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var restoredTitle: String? = null
    private var wide = false

    private var quickTaskDue: TaskDue = TaskDue.None
    private var quickContainer: ContainerId? = null
    private var quickDraftRestored = false
    private var quickExpanded = false
    private var quickDraftNotes: String = ""
    private val quickDraftSubtasks = mutableListOf<TaskDraft.DraftSubtask>()
    private var activeSmartView: TaskSmartView = TaskSmartView.TODAY
    private var selectedContainerFilter: ContainerId? = null

    private lateinit var tvTitle: TextView
    private lateinit var btnTasksOverflow: ImageButton

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
    private lateinit var btnQuickAddExpand: ImageButton
    private lateinit var btnQuickAddSubmit: ImageButton
    private lateinit var quickAddOptions: LinearLayout
    private lateinit var quickDueView: TextView
    private lateinit var quickContainerView: TextView

    private val dateFormatRu = SimpleDateFormat("EEE, d MMMM", Locale("ru"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        savedInstanceState?.let {
            activeSmartView = TaskSmartView.entries.getOrElse(it.getInt("smartView")) { TaskSmartView.TODAY }
            selectedContainerFilter = it.getString("filter")?.let(::ContainerId)
            val dueKind = it.getString("quickDueKind")
            val dueMillis = if (it.containsKey("quickDueMillis")) it.getLong("quickDueMillis") else null
            val dueDateDays = if (it.containsKey("quickDueDays")) it.getInt("quickDueDays") else null
            quickTaskDue = when (dueKind) {
                "DAY" -> dueDateDays?.let { days -> TaskDue.Day(TaskDueDate.from(LocalDate(days))) } ?: TaskDue.None
                "TIMED" -> dueMillis?.let { millis -> TaskDue.Timed.of(millis, it.getString("quickDueZone")) } ?: TaskDue.None
                else -> TaskDue.None
            }
            quickContainer = it.getString("quickContainer")?.let(::ContainerId)
            quickExpanded = it.getBoolean("quickExpanded", false)
            quickDraftRestored = true
            restoredTitle = it.getString("quickTitle")
            quickDraftNotes = it.getString("quickDraftNotes").orEmpty()
            val subTitles = it.getStringArrayList("quickDraftSubtaskTitles").orEmpty()
            val subCompleted = it.getBooleanArray("quickDraftSubtaskCompleted") ?: BooleanArray(subTitles.size)
            quickDraftSubtasks.clear()
            subTitles.forEachIndexed { i, st ->
                quickDraftSubtasks.add(TaskDraft.DraftSubtask(st, subCompleted.getOrElse(i) { false }))
            }
            calendarMode = it.getBoolean("calendarMode")
            selectedDay = LocalDate(it.getInt("selectedDay", getToday().daysSince2000))
            calendarMonth.timeInMillis = it.getLong("calendarMonth", calendarMonth.timeInMillis)
            localTreeRoot = it.getString("localTreeRoot")?.let(::ContainerId)
            collapsedContainers.addAll(it.getStringArrayList("collapsedContainers").orEmpty())
        }
        val app = requireContext().applicationContext as HabitsApplication
        if (::component.isInitialized && component !== app.component) {
            workspaceSnapshot = null
            snapshotRevision = -1L
        }
        component = app.component
        taskRepo = component.userTaskRepository
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
        setupOverflowMenu()
        setupRecyclerView()
        setupQuickAdd()
        setupAdaptiveNavigation(view)
        applyTheme(view)
        return shellRoot
    }

    override fun onResume() {
        super.onResume()
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(quickExpanded)
        workspaceActive = true
        component.workspaceReadRevision.addListener(workspaceChanged)
        palette = MainTabsThemeBridge.resolve(requireContext())
        view?.let { applyTheme(it) }
        adapter.updatePalette(palette)
        (childFragmentManager.findFragmentByTag("task_detail") as? TaskDetailBottomSheet)?.onTaskUpdatedListener = { if (view != null) loadTasks() }
        loadTasks()
    }

    override fun onPause() {
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        workspaceActive = false
        component.workspaceReadRevision.removeListener(workspaceChanged)
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        super.onPause()
    }

    override fun onDestroyView() {
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        refreshQuickLabels = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("smartView", activeSmartView.ordinal)
        outState.putString("filter", selectedContainerFilter?.value)
        when (val d = quickTaskDue) {
            is TaskDue.None -> outState.putString("quickDueKind", "NONE")
            is TaskDue.Day -> {
                outState.putString("quickDueKind", "DAY")
                outState.putInt("quickDueDays", d.date.toLocalDate().daysSince2000)
            }
            is TaskDue.Timed -> {
                outState.putString("quickDueKind", "TIMED")
                outState.putLong("quickDueMillis", d.epochMillis)
                outState.putString("quickDueZone", d.originZoneId)
            }
        }
        outState.putString("quickContainer", quickContainer?.value)
        outState.putBoolean("quickExpanded", quickExpanded)
        outState.putString("quickTitle", if (::etQuickAddTitle.isInitialized) etQuickAddTitle.text?.toString() else restoredTitle)
        outState.putString("quickDraftNotes", quickDraftNotes)
        outState.putStringArrayList("quickDraftSubtaskTitles", ArrayList(quickDraftSubtasks.map { it.title }))
        outState.putBooleanArray("quickDraftSubtaskCompleted", quickDraftSubtasks.map { it.isCompleted }.toBooleanArray())
        outState.putBoolean("calendarMode", calendarMode)
        outState.putInt("selectedDay", selectedDay.daysSince2000)
        outState.putLong("calendarMonth", calendarMonth.timeInMillis)
        outState.putString("localTreeRoot", localTreeRoot?.value)
        outState.putStringArrayList("collapsedContainers", ArrayList(collapsedContainers))
        super.onSaveInstanceState(outState)
    }

    fun refresh() {
        // Sync can replace/reload data through a separate connection.
        workspaceSnapshot = null
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        if (isAdded && view != null) {
            loadTasks()
        }
    }

    private fun bindViews(view: View) {
        tvTitle = view.findViewById(R.id.tvTasksTitle)
        btnTasksOverflow = view.findViewById(R.id.btnTasksOverflow)

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
        btnQuickAddExpand = view.findViewById(R.id.btnQuickAddExpand)
        btnQuickAddSubmit = view.findViewById(R.id.btnQuickAddSubmit)
        quickAddOptions = view.findViewById(R.id.quickAddOptions)
        quickDueView = view.findViewById(R.id.quickDue)
        quickContainerView = view.findViewById(R.id.quickContainer)
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


        view.findViewById<ImageView>(R.id.ivEmptyIcon).setColorFilter(palette.onSurfaceVariant)
        tvEmptyTitle.setTextColor(palette.onSurface)
        tvEmptyMessage.setTextColor(palette.onSurfaceVariant)

        // Keep quick capture docked to the current workspace.
        layoutQuickAddContainer.setBackgroundColor(palette.surface)
        quickAddDivider.setBackgroundColor(palette.divider)
        ivQuickAddIcon.setColorFilter(palette.accent)
        tvQuickAddCollapsedLabel.setTextColor(palette.onSurfaceVariant)
        etQuickAddTitle.setTextColor(palette.onSurface)
        etQuickAddTitle.setHintTextColor(palette.onSurfaceVariant)
        btnQuickAddExpand.setColorFilter(palette.onSurfaceVariant)
        btnQuickAddSubmit.setColorFilter(palette.accent)

        quickDueView.setTextColor(palette.onSurfaceVariant)
        quickContainerView.setTextColor(palette.onSurfaceVariant)

        updateSmartViewLabel()
        if (::navigationColumn.isInitialized) renderNavigation()
    }

    private fun setupOverflowMenu() {
        btnTasksOverflow.setOnClickListener {
            val popup = PopupMenu(requireContext(), btnTasksOverflow)
            popup.menu.add(0, 1, 0, "Все задачи")
            popup.menu.add(0, 2, 1, "Завершённые")
            popup.menu.add(0, 3, 2, "Настройки")
            if (selectedContainerFilter != null) {
                popup.menu.add(0, 4, 3, getString(R.string.container_statistics_title))
            }
            popup.menu.add(0, 5, 4, getString(R.string.today_eval_menu_item))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        selectedContainerFilter = null
                        activeSmartView = TaskSmartView.ALL
                        updateSmartViewLabel()
                        loadTasks()
                        true
                    }
                    2 -> {
                        selectedContainerFilter = null
                        activeSmartView = TaskSmartView.COMPLETED
                        updateSmartViewLabel()
                        loadTasks()
                        true
                    }
                    3 -> { workspaceNavigator?.invoke("Settings"); true }
                    4 -> {
                        selectedContainerFilter?.let { filter ->
                            org.isoron.uhabits.activities.containers.statistics.ContainerStatisticsActivity.start(
                                requireContext(),
                                org.isoron.uhabits.core.containers.ContainerId(filter.value)
                            )
                        }
                        true
                    }
                    5 -> {
                        startActivity(org.isoron.uhabits.activities.today.TodayActivity.createIntent(requireContext()))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun updateSmartViewLabel() {
        tvTitle.text = selectedContainerFilter?.let { workspaceSnapshot?.availableContainers.orEmpty().find { item -> item.key == it.value }?.name } ?: activeSmartView.displayName
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
        fun updateSubmitButtonState() {
            val hasText = !etQuickAddTitle.text.isNullOrBlank()
            btnQuickAddSubmit.alpha = if (hasText) 1.0f else 0.45f
            btnQuickAddSubmit.setColorFilter(if (hasText) palette.accent else palette.onSurfaceVariant)
        }

        fun refreshOptionLabels() {
            val hasDue = quickTaskDue !is TaskDue.None
            val dueBg = GradientDrawable().apply {
                cornerRadius = dp(18f)
                if (hasDue) {
                    setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.12f))
                    setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.accent, 0.4f))
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                    setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.2f))
                }
            }
            quickDueView.background = dueBg

            val dueDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_cat_calendar)?.mutate()
            val dueColor = if (hasDue) palette.accent else palette.onSurfaceVariant
            dueDrawable?.setTint(dueColor)
            dueDrawable?.setBounds(0, 0, dpInt(16f), dpInt(16f))
            quickDueView.setCompoundDrawablesRelative(dueDrawable, null, null, null)
            quickDueView.compoundDrawablePadding = dpInt(6f)
            quickDueView.setTextColor(dueColor)

            val dueText = TaskDueDisplayFormatter.format(quickTaskDue, TaskDueDisplayFormatter.selectedLocale(requireContext()))
            quickDueView.text = dueText

            val hasContainer = quickContainer != null
            val containerBg = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, if (hasContainer) 0.35f else 0.2f))
            }
            quickContainerView.background = containerBg

            val containerDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_cat_folder)?.mutate()
            containerDrawable?.setTint(palette.onSurfaceVariant)
            containerDrawable?.setBounds(0, 0, dpInt(16f), dpInt(16f))
            quickContainerView.setCompoundDrawablesRelative(containerDrawable, null, null, null)
            quickContainerView.compoundDrawablePadding = dpInt(6f)

            val containerItem = workspaceSnapshot?.availableContainers.orEmpty().find { it.key == quickContainer?.value }
            val containerText = containerItem?.name ?: "Входящие"
            quickContainerView.text = containerText
            quickContainerView.setTextColor(if (hasContainer) palette.onSurface else palette.onSurfaceVariant)

            updateSubmitButtonState()
        }

        fun expandComposer() {
            quickExpanded = true
            (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(true)
            layoutQuickAddCollapsed.visibility = View.GONE
            layoutQuickAddExpanded.visibility = View.VISIBLE
            etQuickAddTitle.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etQuickAddTitle, InputMethodManager.SHOW_IMPLICIT)

            if (etQuickAddTitle.text.isNullOrBlank() && !quickDraftRestored) {
                quickTaskDue = if (activeSmartView == TaskSmartView.TODAY) {
                    val t = getToday()
                    TaskDue.Day(TaskDueDate.from(t))
                } else TaskDue.None
                quickContainer = if (activeSmartView == TaskSmartView.INBOX) null else selectedContainerFilter
            }
            refreshOptionLabels()
        }

        fun collapseComposer() {
            quickExpanded = false
            (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
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

        childFragmentManager.setFragmentResultListener("quickDue", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            quickTaskDue = TaskDuePickerBottomSheet.readResult(result)
            quickDraftRestored = true
            refreshOptionLabels()
        }
        childFragmentManager.setFragmentResultListener("quickContainer", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            quickContainer = result.getString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID)?.let(::ContainerId)
            quickDraftRestored = true
            refreshOptionLabels()
        }
        quickDueView.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("quickDue") == null) {
                TaskDuePickerBottomSheet.newInstance("quickDue", quickTaskDue)
                    .show(childFragmentManager, "quickDue")
            }
        }
        quickContainerView.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("quickContainer") == null) {
                TaskContainerPickerBottomSheet.newInstance(quickContainer, "quickContainer", component.session.datasetId)
                    .show(childFragmentManager, "quickContainer")
            }
        }

        btnQuickAddExpand.setOnClickListener {
            val title = etQuickAddTitle.text?.toString().orEmpty()
            val draft = TaskDraft(
                title = title,
                description = quickDraftNotes,
                due = quickTaskDue,
                containerId = quickContainer,
                subtasks = quickDraftSubtasks.toList()
            )
            TaskDetailBottomSheet.newInstanceForCreate(draft, component.session.datasetId)
                .show(childFragmentManager, "task_detail_create")
        }

        childFragmentManager.setFragmentResultListener(TaskDetailBottomSheet.REQUEST_KEY_CREATE, viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            val action = result.getString(TaskDetailBottomSheet.RESULT_ACTION)
            if (action == TaskDetailBottomSheet.ACTION_CREATED) {
                etQuickAddTitle.text?.clear()
                quickDraftNotes = ""
                quickDraftSubtasks.clear()
                quickDraftRestored = false
                collapseComposer()
                loadTasks()
            } else if (action == TaskDetailBottomSheet.ACTION_COLLAPSED) {
                val returnedDraft = TaskDraft.fromBundle(result.getBundle(TaskDetailBottomSheet.RESULT_DRAFT))
                etQuickAddTitle.setText(returnedDraft.title)
                etQuickAddTitle.setSelection(returnedDraft.title.length)
                quickTaskDue = returnedDraft.due
                quickContainer = returnedDraft.containerId
                quickDraftNotes = returnedDraft.description
                quickDraftSubtasks.clear()
                quickDraftSubtasks.addAll(returnedDraft.subtasks)
                quickDraftRestored = true
                expandComposer()
                refreshOptionLabels()
            }
        }

        fun submitQuickAdd() {
            if (!btnQuickAddSubmit.isEnabled) return
            val title = etQuickAddTitle.text?.toString()?.trim().orEmpty()
            if (title.isBlank()) return
            btnQuickAddSubmit.isEnabled = false

            val notes = quickDraftNotes
            val subs = quickDraftSubtasks.toList()
            val due = quickTaskDue
            val cid = quickContainer

            val created = try { component.db.runInTransaction {
                val mainTask = taskService.createTask(
                    title = title,
                    description = notes,
                    containerId = cid,
                    due = due,
                )
                for (sub in subs) {
                    if (sub.title.isNotBlank()) {
                        val createdSub = taskService.createTask(
                            title = sub.title,
                            parentTaskId = mainTask.id,
                            containerId = cid,
                        )
                        if (sub.isCompleted) {
                            taskService.setCompleted(createdSub.id, true)
                        }
                    }
                }
                mainTask
            } } catch (e: Exception) {
                android.util.Log.e("TasksFragment", "Task creation failed", e)
                btnQuickAddSubmit.isEnabled = true
                Toast.makeText(requireContext(), "Не удалось создать задачу. Попробуйте ещё раз.", Toast.LENGTH_LONG).show()
                return
            }

            etQuickAddTitle.text?.clear()
            quickDraftNotes = ""
            quickDraftSubtasks.clear()
            quickDraftRestored = false
            btnQuickAddSubmit.isEnabled = true
            collapseComposer()

            // Reveal the new task even when it no longer matches the current view/filter.
            if (!activeSmartView.matches(created, getToday(), System.currentTimeMillis())) {
                activeSmartView = TaskSmartView.ALL
                updateSmartViewLabel()
            }
            if (cid != selectedContainerFilter) {
                selectedContainerFilter = cid
                updateSmartViewLabel()
            }
            loadTasks()
        }

        btnQuickAddSubmit.setOnClickListener { submitQuickAdd() }
        restoredTitle?.let { etQuickAddTitle.setText(it); restoredTitle = null }
        etQuickAddTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitQuickAdd(); true } else false
        }
        etQuickAddTitle.doAfterTextChanged {
            updateSubmitButtonState()
        }
        refreshQuickLabels = ::refreshOptionLabels
        refreshOptionLabels()
    }

    fun loadTasks() {
        if (!isAdded || view == null || !workspaceActive || component !== HabitsApplication.component) return
        val revision = component.workspaceReadRevision.value
        val cached = workspaceSnapshot
        if (cached != null && snapshotRevision == revision) {
            renderTasks(cached)
            return
        }
        if (loadJob?.isActive == true) return
        val generation = ++loadGeneration
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                var stable: Pair<Long, TasksWorkspaceSnapshot>? = null
                repeat(3) {
                    if (stable == null) {
                        val before = component.workspaceReadRevision.value
                        val snapshot = TasksWorkspaceSnapshot.load(taskRepo, orgFacade.containerQueries)
                        if (before == component.workspaceReadRevision.value) stable = before to snapshot
                    }
                }
                stable
            }
            if (!workspaceActive || generation != loadGeneration || view == null ||
                component !== HabitsApplication.component) return@launch
            if (loaded == null || loaded.first != component.workspaceReadRevision.value) {
                delay(50)
                loadJob = null
                loadTasks()
                return@launch
            }
            snapshotRevision = loaded.first
            workspaceSnapshot = loaded.second
            renderTasks(loaded.second)
        }
    }

    private fun renderTasks(snapshot: TasksWorkspaceSnapshot) {
        // A restored context may have been archived/deleted while this workspace was hidden.
        // availableContainers excludes deleted and effectively archived branches.
        val availableContainers = snapshot.availableContainers
        val visibleContainerIds = availableContainers.mapTo(mutableSetOf()) { it.key }
        if (selectedContainerFilter?.value?.let { it !in visibleContainerIds } == true) {
            selectedContainerFilter = null
            activeSmartView = TaskSmartView.ALL
        }
        if (localTreeRoot?.value?.let { it !in visibleContainerIds } == true) {
            localTreeRoot = null
        }
        val allRoots = snapshot.roots
        val subtaskMap = snapshot.subtaskCounts

        val today = runCatching { getToday() }.getOrDefault(LocalDate(2026, 10, 4))
        val nowMillis = System.currentTimeMillis()

        // 1. Container filter
        val containerFiltered = if (selectedContainerFilter != null) {
            val ids = snapshot.scope(selectedContainerFilter!!)
            allRoots.filter { it.containerId in ids }
        } else {
            allRoots
        }

        // 2. Smart View filter
        val viewFiltered = containerFiltered.filter { activeSmartView.matches(it, today, nowMillis) }
        updateSmartViewLabel()
        refreshQuickLabels?.invoke()
        renderNavigation()
        renderCalendar(viewFiltered)

        // Sort: pending by overdue first, then sortOrder, then due timestamp/day; completed by completedAt DESC
        val sorted = if (activeSmartView == TaskSmartView.COMPLETED) {
            viewFiltered.sortedByDescending { it.completedAt ?: 0L }
        } else {
            viewFiltered.sortedWith(
                compareBy<UserTask> { !it.isOverdue(today, nowMillis) }
                    .thenBy { it.sortOrder }
                    .thenBy {
                        when (val d = it.due) {
                            is TaskDue.Timed -> d.epochMillis
                            is TaskDue.Day -> d.date.toLocalDate().daysSince2000.toLong() * 86400000L
                            is TaskDue.None -> Long.MAX_VALUE
                        }
                    }
            )
        }

        val containerItemMap = availableContainers.associateBy { ContainerId(it.key) }

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
        if (calendarMode) {
            rvTasks.visibility = View.GONE
            layoutEmpty.visibility = View.GONE
        } else if (sorted.isEmpty()) {
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
                tvEmptyMessage.text = "Нет задач со сроком на сегодня"
            }
            TaskSmartView.OVERDUE -> {
                tvEmptyTitle.text = "Нет просроченных задач"
                tvEmptyMessage.text = "Все задачи со сроком выполняются вовремя"
            }
            TaskSmartView.INBOX -> {
                tvEmptyTitle.text = "Входящие пусты"
                tvEmptyMessage.text = "Здесь появляются задачи без раздела"
            }
            TaskSmartView.UPCOMING -> {
                tvEmptyTitle.text = "Нет предстоящих задач"
                tvEmptyMessage.text = "Здесь отображаются задачи с будущим сроком"
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


    private fun navText(label: String, selected: Boolean = false, action: (() -> Unit)? = null): TextView = TextView(requireContext()).apply {
        text = label
        textSize = 15f
        minHeight = dpInt(48f)
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dpInt(16f), dpInt(8f), dpInt(12f), dpInt(8f))
        setTextColor(if (selected) palette.accent else palette.onSurface)
        if (selected) setBackgroundColor(palette.surface)
        action?.let { callback ->
            val value = android.util.TypedValue()
            requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            if (!selected) setBackgroundResource(value.resourceId)
            isFocusable = true
            setOnClickListener { callback() }
        }
    }

    private fun setupAdaptiveNavigation(content: View) {
        wide = resources.configuration.screenWidthDp >= 600
        if (wide) {
            // Phone capture is inset by the host's bottom navigation. Wide mode removes
            // that host, so its content must own navigation-bar and keyboard clearance.
            val initialBottom = content.paddingBottom
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottom + maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
        shellRoot = FrameLayout(requireContext())
        val body = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        shellRoot.addView(body, FrameLayout.LayoutParams(-1, -1))
        navigationColumn = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val navigationScroll = ScrollView(requireContext()).apply {
            setBackgroundColor(palette.background)
            addView(navigationColumn)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        if (wide) body.addView(navigationScroll, LinearLayout.LayoutParams(dpInt(280f), -1))
        // Bound the working surface on expanded windows instead of stretching rows across a desktop.
        val contentFrame = FrameLayout(requireContext()).apply { setBackgroundColor(palette.background) }
        body.addView(contentFrame, LinearLayout.LayoutParams(0, -1, 1f))
        contentFrame.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        contentFrame.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left
            val width = minOf(available, dpInt(840f))
            if (content.layoutParams.width != width) {
                content.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
            }
        }
        val header = content.findViewById<LinearLayout>(R.id.tasksHeader)
        val hamburger = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_tasks_menu)
            setColorFilter(palette.onSurfaceVariant)
            setPadding(dpInt(12f), dpInt(12f), dpInt(12f), dpInt(12f))
            setBackgroundResource(run {
                        val value = android.util.TypedValue()
                        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
                        value.resourceId
                    })
            setOnClickListener { openNavigation() }
            contentDescription = "Открыть навигацию задач"
            visibility = if (wide) View.GONE else View.VISIBLE
        }
        header.addView(hamburger, 0, LinearLayout.LayoutParams(dpInt(48f), dpInt(48f)))
        representationButton = ImageButton(requireContext()).apply {
            setPadding(dpInt(12f), dpInt(12f), dpInt(12f), dpInt(12f))
            setBackgroundResource(run {
                        val value = android.util.TypedValue()
                        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
                        value.resourceId
                    })
            setColorFilter(palette.onSurfaceVariant)
            setOnClickListener { calendarMode = !calendarMode; loadTasks() }
        }
        header.addView(representationButton, header.childCount - 1, LinearLayout.LayoutParams(dpInt(48f), dpInt(48f)))
        tvTitle.setOnLongClickListener {
            selectedContainerFilter?.let { id ->
                val path = workspaceSnapshot?.availableContainers.orEmpty().find { it.key == id.value }?.path
                AlertDialog.Builder(requireContext()).setMessage(path ?: tvTitle.text).setPositiveButton("OK", null).show()
            }
            true
        }
        calendarPanel = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        (rvTasks.parent as ViewGroup).addView(calendarPanel, ViewGroup.LayoutParams(-1, -1))
        if (!wide) drawerScroll = navigationScroll
        renderNavigation()
    }

    private fun openNavigation() {
        if (wide || drawerScrim != null) return
        etQuickAddTitle.clearFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etQuickAddTitle.windowToken, 0)
        (activity as? MainNavigationHost)?.setBottomNavigationVisible(false, false)
        (shellRoot.getChildAt(0) as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        shellRoot.getChildAt(0).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        drawerScrim = View(requireContext()).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            contentDescription = "Закрыть навигацию задач"
            isFocusable = true
            setOnClickListener { closeNavigation() }
        }
        shellRoot.addView(drawerScrim, FrameLayout.LayoutParams(-1, -1))
        shellRoot.addView(drawerScroll, FrameLayout.LayoutParams(dpInt(300f), -1, Gravity.START))
        drawerScroll?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }
        navigationColumn.getChildAt(0)?.requestFocus()
    }

    private fun closeNavigation() {
        if (drawerScrim != null) (activity as? MainNavigationHost)?.setBottomNavigationVisible(true, false)
        drawerScrim?.let { shellRoot.removeView(it) }
        drawerScrim = null
        (shellRoot.getChildAt(0) as ViewGroup).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        shellRoot.getChildAt(0).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (!wide) drawerScroll?.let { shellRoot.removeView(it) }
    }

    fun onBackPressed(): Boolean {
        if (drawerScrim != null) {
            if (localTreeRoot != null) {
                localTreeRoot = workspaceSnapshot?.containers?.get(localTreeRoot!!)?.parentId
                renderNavigation()
            } else closeNavigation()
            return true
        }
        if (quickExpanded) {
            quickExpanded = false
            (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
            layoutQuickAddExpanded.visibility = View.GONE
            layoutQuickAddCollapsed.visibility = View.VISIBLE
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(etQuickAddTitle.windowToken, 0)
            return true
        }
        return false
    }

    private fun navigationRow(label: String, icon: Int, count: Int? = null, selected: Boolean = false,
                              depth: Int = 0, action: () -> Unit): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpInt(48f)
        setPaddingRelative(dpInt(16f + depth * 12f), 0, dpInt(12f), 0)
        val ripple = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        if (selected) background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(MainTabsThemeBridge.withAlpha(palette.accent, .16f)),
            GradientDrawable().apply { cornerRadius = dp(8f); setColor(MainTabsThemeBridge.withAlpha(palette.accent, .09f)) }, null)
        isFocusable = true
        isSelected = selected
        setOnClickListener { action() }
        addView(ImageView(requireContext()).apply {
            setImageResource(icon); setColorFilter(if (selected) palette.accent else palette.onSurfaceVariant)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dpInt(20f), dpInt(20f)).apply { marginEnd = dpInt(12f) })
        addView(TextView(requireContext()).apply {
            text = label; textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (selected) palette.accent else palette.onSurface)
            if (selected) typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        count?.takeIf { it > 0 }?.let { number ->
            addView(TextView(requireContext()).apply {
                text = number.toString(); textSize = 12f; setTextColor(palette.onSurfaceVariant)
                setPaddingRelative(dpInt(8f), 0, 0, 0); fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(-2, -2))
        }
        contentDescription = label + (count?.let { ", $it ${pluralTasks(it)}" } ?: "")
    }

    private fun renderNavigation() {
        val snapshot = workspaceSnapshot ?: return
        navigationColumn.removeAllViews()
        navigationColumn.setBackgroundColor(palette.background)
        navigationColumn.setPaddingRelative(dpInt(8f), dpInt(8f), dpInt(8f), dpInt(16f))
        fun divider() { navigationColumn.addView(View(requireContext()).apply { setBackgroundColor(palette.divider) },
            LinearLayout.LayoutParams(-1, dpInt(1f)).apply { topMargin = dpInt(12f); bottomMargin = dpInt(12f) }) }
        if (wide) {
            listOf(Triple("Habits", "Привычки", R.drawable.ic_tasks_completed),
                Triple("Tasks", "Задачи", R.drawable.ic_cat_list_todo),
                Triple("Statistics", "Статистика", R.drawable.ic_settings_statistics)).forEach { (key, name, icon) ->
                navigationColumn.addView(navigationRow(name, icon, selected = key == "Tasks") { workspaceNavigator?.invoke(key) })
            }
            divider()
        } else navigationColumn.addView(navText("Задачи").apply {
            textSize = 22f; typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setPaddingRelative(dpInt(16f), dpInt(8f), dpInt(16f), dpInt(12f))
        })
        val roots = snapshot.roots
        val smartIcons = listOf(
            R.drawable.ic_cat_calendar,
            R.drawable.ic_cat_flag,
            R.drawable.ic_cat_folder_open,
            R.drawable.ic_cat_calendar_clock,
            R.drawable.ic_cat_list_todo,
            R.drawable.ic_tasks_completed
        )
        TaskSmartView.entries.forEachIndexed { index, smart ->
            val count = roots.count { smart.matches(it, getToday(), System.currentTimeMillis()) }
            navigationColumn.addView(navigationRow(smart.displayName, smartIcons[index], count,
                selectedContainerFilter == null && activeSmartView == smart) {
                selectedContainerFilter = null; activeSmartView = smart; closeNavigation(); loadTasks()
            })
        }
        divider()
        navigationColumn.addView(navText("Разделы").apply { textSize = 12f; minHeight = dpInt(32f); setTextColor(palette.onSurfaceVariant) })
        if (snapshot.containers.isNotEmpty()) {
            localTreeRoot?.let { id ->
                navigationColumn.addView(navigationRow(snapshot.containers[id]?.parentId?.let { snapshot.containers[it]?.name } ?: "Все разделы",
                    R.drawable.ic_tasks_parent) { localTreeRoot = snapshot.containers[id]?.parentId; renderNavigation() })
            }
            fun addContainer(item: org.isoron.uhabits.core.containers.Container, depth: Int) {
                val children = snapshot.children[item.id].orEmpty()
                val count = snapshot.pendingCounts[item.id] ?: 0
                val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(navigationRow(item.name, if (depth == 0) R.drawable.ic_cat_folder else R.drawable.ic_cat_list_todo,
                    count, selectedContainerFilter == item.id, depth) {
                    selectedContainerFilter = item.id; activeSmartView = TaskSmartView.ALL; closeNavigation(); loadTasks()
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (children.isNotEmpty()) row.addView(ImageButton(requireContext()).apply {
                    setImageResource(if (depth >= 2 || item.id.value in collapsedContainers) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down)
                    setColorFilter(palette.onSurfaceVariant)
                    val ripple = android.util.TypedValue()
                    requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    setPadding(dpInt(14f), dpInt(14f), dpInt(14f), dpInt(14f))
                    contentDescription = "Раскрыть раздел " + item.name
                    setOnClickListener {
                        if (depth >= 2) localTreeRoot = item.id
                        else if (!collapsedContainers.add(item.id.value)) collapsedContainers.remove(item.id.value)
                        renderNavigation()
                    }
                }, LinearLayout.LayoutParams(dpInt(48f), dpInt(48f)))
                navigationColumn.addView(row)
                if (depth < 2 && item.id.value !in collapsedContainers) children.forEach { addContainer(it, depth + 1) }
            }
            val local = localTreeRoot?.let { snapshot.containers[it] }
            if (local != null) addContainer(local, 0) else snapshot.children[null].orEmpty().forEach { addContainer(it, 0) }
        }
        divider()
        navigationColumn.addView(navigationRow("Настройки", R.drawable.ic_nav_settings) { closeNavigation(); workspaceNavigator?.invoke("Settings") })
    }

    private fun renderCalendar(tasks: List<UserTask>) {
        representationButton.setImageResource(if (calendarMode) R.drawable.ic_cat_list_todo else R.drawable.ic_cat_calendar)
        representationButton.contentDescription = if (calendarMode) "Показать список" else "Показать календарь"
        calendarPanel.visibility = if (calendarMode) View.VISIBLE else View.GONE
        if (!calendarMode) return
        val monthKey = "${calendarMonth.get(Calendar.YEAR)}-${calendarMonth.get(Calendar.MONTH)}"
        val scrollY = if (renderedCalendarMonth == monthKey) (calendarPanel.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        renderedCalendarMonth = monthKey
        calendarPanel.removeAllViews()
        val scroll = ScrollView(requireContext())
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        calendarPanel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        val monthRow = LinearLayout(requireContext())
        fun changeMonth(delta: Int) { calendarMonth.add(Calendar.MONTH, delta); renderCalendar(tasks) }
        monthRow.addView(navText("‹", action = { changeMonth(-1) }).apply { contentDescription = "Предыдущий месяц" }, LinearLayout.LayoutParams(dpInt(48f), -2))
        monthRow.addView(navText(SimpleDateFormat("LLLL yyyy", Locale("ru")).format(calendarMonth.time)) {
            calendarMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }; selectedDay = getToday(); renderCalendar(tasks)
        }.apply { typeface = android.graphics.Typeface.create("sans-serif-medium", 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        monthRow.addView(navText("›", action = { changeMonth(1) }).apply { contentDescription = "Следующий месяц" }, LinearLayout.LayoutParams(dpInt(48f), -2))
        content.addView(monthRow)
        val events = TaskCalendarEvents.from(tasks)
        val weekdays = LinearLayout(requireContext())
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { weekdays.addView(navText(it).apply {
            textSize = 11f; gravity = Gravity.CENTER; setPadding(0,0,0,0); minHeight = dpInt(24f); setTextColor(palette.onSurfaceVariant)
        }, LinearLayout.LayoutParams(0, -2, 1f)) }
        content.addView(weekdays)
        val month = calendarMonth.get(Calendar.MONTH) + 1
        val year = calendarMonth.get(Calendar.YEAR)
        val offset = (calendarMonth.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val days = calendarMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (week in 0 until (offset + days + 6) / 7) {
            val row = LinearLayout(requireContext())
            for (column in 0..6) {
                val number = week * 7 + column - offset + 1
                val day = if (number in 1..days) LocalDate(year, month, number) else null
                val dayEvents = events.filter { it.day == day }
                val cell = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dpInt(if (wide) 100f else 52f)
                    gravity = if (wide) Gravity.TOP else Gravity.CENTER_HORIZONTAL
                    setPaddingRelative(dpInt(4f), dpInt(4f), dpInt(4f), dpInt(4f))
                    if (day != null) {
                        isFocusable = true
                        val ripple = android.util.TypedValue()
                        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                        setBackgroundResource(ripple.resourceId)
                        setOnClickListener { selectedDay = day; renderCalendar(tasks) }
                        contentDescription = "${number}.${month}.${year}, ${dayEvents.size} событий"
                    }
                }
                cell.addView(TextView(requireContext()).apply {
                    text = if (day == null) "" else number.toString(); textSize = 13f
                    gravity = Gravity.CENTER; minimumHeight = dpInt(24f)
                    setTextColor(if (day == selectedDay) Color.WHITE else if (day == getToday()) palette.accent else palette.onSurface)
                    if (day == selectedDay) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.accent) }
                }, LinearLayout.LayoutParams(dpInt(maxOf(28f, 22f * resources.configuration.fontScale)), dpInt(maxOf(28f, 22f * resources.configuration.fontScale))).apply { gravity = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL })
                if (wide) {
                    dayEvents.take(2).forEach { event ->
                        cell.addView(TextView(requireContext()).apply {
                            text = event.task.title; textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            setPaddingRelative(dpInt(4f), dpInt(3f), dpInt(2f), dpInt(3f))
                            setTextColor(palette.onSurface)
                            val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(),
                                if (event.kind == TaskCalendarEventKind.PLAN) R.drawable.ic_cat_calendar else R.drawable.ic_cat_flag)?.mutate()
                            drawable?.setTint(if (event.kind == TaskCalendarEventKind.PLAN) palette.accent else palette.onSurfaceVariant)
                            drawable?.setBounds(0,0,dpInt(12f),dpInt(12f))
                            setCompoundDrawablesRelative(drawable,null,null,null); compoundDrawablePadding = dpInt(3f)
                        }, LinearLayout.LayoutParams(-1,-2))
                    }
                    if (dayEvents.size > 2) cell.addView(TextView(requireContext()).apply {
                        text = "+${dayEvents.size - 2} ещё"; textSize = 10f; setTextColor(palette.onSurfaceVariant)
                    })
                } else if (dayEvents.isNotEmpty()) {
                    val indicators = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER }
                    TaskCalendarEventKind.entries.filter { kind -> dayEvents.any { it.kind == kind } }.forEach { kind ->
                        indicators.addView(ImageView(requireContext()).apply {
                            setImageResource(if (kind == TaskCalendarEventKind.PLAN) R.drawable.ic_cat_calendar else R.drawable.ic_cat_flag)
                            setColorFilter(if (kind == TaskCalendarEventKind.PLAN) palette.accent else palette.onSurfaceVariant)
                        }, LinearLayout.LayoutParams(dpInt(12f),dpInt(12f)).apply { marginStart = dpInt(2f); marginEnd = dpInt(2f) })
                    }
                    cell.addView(indicators)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(row)
            if (wide) content.addView(View(requireContext()).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1,dpInt(1f)))
        }
        content.addView(navText(SimpleDateFormat("EEEE, d MMMM",Locale("ru")).format(Calendar.getInstance().apply {
            set(selectedDay.year, selectedDay.month - 1, selectedDay.day)
        }.time)).apply { textSize = 15f; typeface = android.graphics.Typeface.create("sans-serif-medium",0); minHeight = dpInt(48f) })
        val agenda = events.filter { it.day == selectedDay }
        if (agenda.isEmpty()) content.addView(navText("Нет задач на выбранный день").apply { setTextColor(palette.onSurfaceVariant) })
        agenda.forEach { event ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpInt(64f); setPaddingRelative(dpInt(16f),dpInt(8f),dpInt(16f),dpInt(8f))
                val ripple = android.util.TypedValue(); requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground,ripple,true)
                setBackgroundResource(ripple.resourceId); isFocusable = true
                setOnClickListener {
                    val sheet = TaskDetailBottomSheet.newInstance(event.task.id)
                    sheet.onTaskUpdatedListener = { loadTasks() }; sheet.show(childFragmentManager,"task_detail")
                }
            }
            row.addView(ImageView(requireContext()).apply {
                setImageResource(if (event.kind == TaskCalendarEventKind.PLAN) R.drawable.ic_cat_calendar else R.drawable.ic_cat_flag)
                setColorFilter(if (event.kind == TaskCalendarEventKind.PLAN) palette.accent else palette.onSurfaceVariant)
            },LinearLayout.LayoutParams(dpInt(20f),dpInt(20f)).apply { marginEnd = dpInt(16f) })
            val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(requireContext()).apply { text = event.task.title; textSize = 15f; maxLines = 2;
                ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(palette.onSurface) })
            labels.addView(TextView(requireContext()).apply {
                val timeStr = (event.task.due as? TaskDue.Timed)?.let {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.epochMillis))
                }.orEmpty()
                text = if (timeStr.isNotEmpty()) "Срок · $timeStr" else "Срок"
                textSize = 12f; setTextColor(palette.onSurfaceVariant)
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin = dpInt(4f) })
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
        }
        scroll.post { if (scroll.parent != null) scroll.scrollTo(0, scrollY) }
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
