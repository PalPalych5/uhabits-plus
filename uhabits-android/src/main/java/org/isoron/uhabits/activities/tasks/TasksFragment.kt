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
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.models.Habit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.platform.io.runInTransaction
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.main.MainNavigationHost
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.UserTaskService
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.inject.HabitsApplicationComponent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TasksFragment : Fragment() {

    internal data class TaskUndoEntry(
        val before: UserTask,
        val after: UserTask,
    )

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
    private val timerListener = org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager.Listener {
        activity?.runOnUiThread { if (workspaceActive && view != null && calendarMode) loadTasks() }
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
    private var matrixMode = false
    private var calendarScale = CalendarScale.MONTH
    private var calendarViewMode = CalendarMode.PLANNING
    private var activeHabits: List<Habit> = emptyList()
    private var historyFocusSessions: List<org.isoron.uhabits.core.focus.FocusSession> = emptyList()
    private var calendarViewController: TaskCalendarViewController? = null
    private var quickImportant: Boolean? = null
    private var quickUrgent: Boolean? = null
    private var renderedCalendarMonth: String? = null
    private var selectedDay = getToday()
    private var calendarMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var restoredTitle: String? = null
    private var wide = false

    private var quickTaskDue: TaskDue = TaskDue.None
    private var quickContainer: ContainerId? = null
    private var quickTagIds: Set<String> = emptySet()
    private var quickDraftRestored = false
    private var quickExpanded = false
    private var quickDraftNotes: String = ""
    private val quickDraftSubtasks = mutableListOf<TaskDraft.DraftSubtask>()
    private var activeSmartView: TaskSmartView = TaskSmartView.TODAY
    private var selectedContainerFilter: ContainerId? = null
    private var selectedTagFilter: Set<String> = emptySet()

    private val selectedTaskIds = linkedSetOf<TaskId>()
    private val pendingActionTaskIds = linkedSetOf<TaskId>()
    private val pendingDeleteConfirmationIds = linkedSetOf<TaskId>()
    private var deleteConfirmationDialog: AlertDialog? = null
    private var activeSnackbar: Snackbar? = null
    private var lastUndoEntries: List<TaskUndoEntry> = emptyList()
    private lateinit var swipeCallback: TaskSwipeCallback

    private lateinit var tasksHeader: LinearLayout
    private lateinit var tasksSelectionBar: LinearLayout
    private lateinit var btnSelectionClose: ImageButton
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnSelectionDue: ImageButton
    private lateinit var btnSelectionContainer: ImageButton
    private lateinit var btnSelectionComplete: ImageButton
    private lateinit var btnSelectionDelete: ImageButton
    private lateinit var btnSelectionMore: ImageButton

    private lateinit var tvTitle: TextView
    private lateinit var btnTasksOverflow: ImageButton

    private lateinit var layoutListInfo: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var tvTasksTagFilter: TextView
    private lateinit var tvTasksDate: TextView

    private lateinit var rvTasks: RecyclerView
    private lateinit var adapter: TasksAdapter
    private lateinit var overdueAdapter: TasksAdapter
    private lateinit var completedAdapter: TasksAdapter
    private lateinit var groupedAdapter: TaskListAdapter
    private lateinit var createGroupAdapter: () -> TasksAdapter
    private val groupAdapters = mutableMapOf<String, Pair<TaskSectionAdapter, TasksAdapter>>()
    private lateinit var overdueHeader: TaskSectionAdapter
    private lateinit var activeHeader: TaskSectionAdapter
    private lateinit var completedHeader: TaskSectionAdapter
    private var overdueExpanded = true
    private var completedExpanded = false
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
    private lateinit var quickTagsView: TextView

    private val dateFormatRu = SimpleDateFormat("EEE, d MMMM", Locale("ru"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        savedInstanceState?.let {
            overdueExpanded = it.getBoolean("overdueExpanded", true)
            completedExpanded = it.getBoolean("completedExpanded", false)
            activeSmartView = TaskSmartView.entries.getOrElse(it.getInt("smartView")) { TaskSmartView.TODAY }
            selectedContainerFilter = it.getString("filter")?.let(::ContainerId)
            selectedTagFilter = it.getStringArrayList("tagFilter").orEmpty().toSet()
            val dueKind = it.getString("quickDueKind")
            val dueMillis = if (it.containsKey("quickDueMillis")) it.getLong("quickDueMillis") else null
            val dueDateDays = if (it.containsKey("quickDueDays")) it.getInt("quickDueDays") else null
            quickTaskDue = when (dueKind) {
                "DAY" -> dueDateDays?.let { days -> TaskDue.Day(TaskDueDate.from(LocalDate(days))) } ?: TaskDue.None
                "TIMED" -> dueMillis?.let { millis -> TaskDue.Timed.of(millis, it.getString("quickDueZone")) } ?: TaskDue.None
                else -> TaskDue.None
            }
            quickContainer = it.getString("quickContainer")?.let(::ContainerId)
            quickTagIds = it.getStringArrayList("quickTagIds").orEmpty().toSet()
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
            matrixMode = it.getBoolean("matrixMode")
            calendarScale = it.getString("calendarScale")?.let { name ->
                try { CalendarScale.valueOf(name) } catch (e: Exception) { CalendarScale.MONTH }
            } ?: CalendarScale.MONTH
            calendarViewMode = it.getString("calendarViewMode")?.let { name ->
                try { CalendarMode.valueOf(name) } catch (e: Exception) { CalendarMode.PLANNING }
            } ?: CalendarMode.PLANNING
            quickImportant = if (it.containsKey("quickImportant")) it.getBoolean("quickImportant") else null
            quickUrgent = if (it.containsKey("quickUrgent")) it.getBoolean("quickUrgent") else null
            selectedDay = LocalDate(it.getInt("selectedDay", getToday().daysSince2000))
            calendarMonth.timeInMillis = it.getLong("calendarMonth", calendarMonth.timeInMillis)
            localTreeRoot = it.getString("localTreeRoot")?.let(::ContainerId)
            collapsedContainers.addAll(it.getStringArrayList("collapsedContainers").orEmpty())
            selectedTaskIds.clear()
            it.getStringArrayList("selectedTaskIds")?.mapTo(selectedTaskIds, ::TaskId)
            pendingActionTaskIds.clear()
            it.getStringArrayList("pendingActionTaskIds")?.mapTo(pendingActionTaskIds, ::TaskId)
            pendingDeleteConfirmationIds.clear()
            it.getStringArrayList("pendingDeleteConfirmationIds")?.mapTo(pendingDeleteConfirmationIds, ::TaskId)
        }
        val app = requireContext().applicationContext as HabitsApplication
        if (::component.isInitialized && component !== app.component) {
            workspaceSnapshot = null
            snapshotRevision = -1L
            selectedTaskIds.clear()
            pendingActionTaskIds.clear()
            pendingDeleteConfirmationIds.clear()
            lastUndoEntries = emptyList()
        }
        component = app.component
        taskRepo = component.userTaskRepository
        taskService = app.component.userTaskService
        orgFacade = app.component.organizationFacade
        palette = MainTabsThemeBridge.resolve(requireContext())

        val view = inflater.inflate(R.layout.fragment_tasks, container, false)
        val headerView = view.findViewById<View>(R.id.tasksHeader)
        val selectionBarView = view.findViewById<View>(R.id.tasksSelectionBar)
        val topInsetListener = androidx.core.view.OnApplyWindowInsetsListener { v, insets ->
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBars.top, cutout.top)
            v.setPadding(v.paddingLeft, topInset + dpInt(6f), v.paddingRight, v.paddingBottom)
            insets
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(headerView, topInsetListener)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(selectionBarView, topInsetListener)
        bindViews(view)
        setupOverflowMenu()
        setupSelectionBar()
        setupRecyclerView()
        setupQuickAdd()
        setupAdaptiveNavigation(view)
        applyTheme(view)
        updateSelectionBarUi()
        if (pendingDeleteConfirmationIds.isNotEmpty()) {
            view.post {
                if (isAdded && pendingDeleteConfirmationIds.isNotEmpty()) {
                    confirmDeleteTasks(pendingDeleteConfirmationIds.toList())
                }
            }
        }
        return shellRoot
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            clearSelection()
            activeSnackbar?.dismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(quickExpanded)
        workspaceActive = true
        component.workspaceReadRevision.addListener(workspaceChanged)
        component.timerSessionManager.addListener(timerListener)
        palette = MainTabsThemeBridge.resolve(requireContext())
        view?.let { applyTheme(it) }
        adapter.updatePalette(palette)
        overdueAdapter.updatePalette(palette)
        completedAdapter.updatePalette(palette)
        if (::swipeCallback.isInitialized) swipeCallback.updatePalette(palette)
        (childFragmentManager.findFragmentByTag("task_detail") as? TaskDetailBottomSheet)?.onTaskUpdatedListener = { if (view != null) loadTasks() }
        loadTasks()
    }

    override fun onPause() {
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        workspaceActive = false
        component.workspaceReadRevision.removeListener(workspaceChanged)
        component.timerSessionManager.removeListener(timerListener)
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        super.onPause()
    }

    override fun onDestroyView() {
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        deleteConfirmationDialog?.setOnDismissListener(null)
        deleteConfirmationDialog?.dismiss()
        deleteConfirmationDialog = null
        activeSnackbar?.dismiss()
        activeSnackbar = null
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        refreshQuickLabels = null
        calendarViewController = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("overdueExpanded", overdueExpanded)
        outState.putBoolean("completedExpanded", completedExpanded)
        outState.putInt("smartView", activeSmartView.ordinal)
        outState.putString("filter", selectedContainerFilter?.value)
        outState.putStringArrayList("tagFilter", ArrayList(selectedTagFilter))
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
        outState.putStringArrayList("quickTagIds", ArrayList(quickTagIds))
        outState.putBoolean("quickExpanded", quickExpanded)
        outState.putString("quickTitle", if (::etQuickAddTitle.isInitialized) etQuickAddTitle.text?.toString() else restoredTitle)
        outState.putString("quickDraftNotes", quickDraftNotes)
        outState.putStringArrayList("quickDraftSubtaskTitles", ArrayList(quickDraftSubtasks.map { it.title }))
        outState.putBooleanArray("quickDraftSubtaskCompleted", quickDraftSubtasks.map { it.isCompleted }.toBooleanArray())
        outState.putBoolean("calendarMode", calendarMode)
        outState.putBoolean("matrixMode", matrixMode)
        outState.putString("calendarScale", calendarScale.name)
        outState.putString("calendarViewMode", calendarViewMode.name)
        quickImportant?.let { outState.putBoolean("quickImportant", it) }
        quickUrgent?.let { outState.putBoolean("quickUrgent", it) }
        outState.putInt("selectedDay", selectedDay.daysSince2000)
        outState.putLong("calendarMonth", calendarMonth.timeInMillis)
        outState.putString("localTreeRoot", localTreeRoot?.value)
        outState.putStringArrayList("collapsedContainers", ArrayList(collapsedContainers))
        outState.putStringArrayList("selectedTaskIds", ArrayList(selectedTaskIds.map { it.value }))
        outState.putStringArrayList("pendingActionTaskIds", ArrayList(pendingActionTaskIds.map { it.value }))
        outState.putStringArrayList("pendingDeleteConfirmationIds", ArrayList(pendingDeleteConfirmationIds.map { it.value }))
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
        tasksHeader = view.findViewById(R.id.tasksHeader)
        tasksSelectionBar = view.findViewById(R.id.tasksSelectionBar)
        btnSelectionClose = view.findViewById(R.id.btnSelectionClose)
        tvSelectionCount = view.findViewById(R.id.tvSelectionCount)
        btnSelectionDue = view.findViewById(R.id.btnSelectionDue)
        btnSelectionContainer = view.findViewById(R.id.btnSelectionContainer)
        btnSelectionComplete = view.findViewById(R.id.btnSelectionComplete)
        btnSelectionDelete = view.findViewById(R.id.btnSelectionDelete)
        btnSelectionMore = view.findViewById(R.id.btnSelectionMore)

        tvTitle = view.findViewById(R.id.tvTasksTitle)
        btnTasksOverflow = view.findViewById(R.id.btnTasksOverflow)

        layoutListInfo = view.findViewById(R.id.layoutListInfo)
        tvSubtitle = view.findViewById(R.id.tvTasksSubtitle)
        tvTasksTagFilter = view.findViewById(R.id.tvTasksTagFilter)
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
        quickTagsView = view.findViewById(R.id.quickTags)
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(requireContext(), value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(requireContext(), value)

    private fun dangerColor(): Int = androidx.core.content.ContextCompat.getColor(
        requireContext(),
        if (palette.isDark) R.color.color_picker_danger_dark else R.color.color_picker_danger_light,
    )

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

        tasksSelectionBar.setBackgroundColor(
            MainTabsThemeBridge.withAlpha(palette.accent, if (palette.isDark) 0.14f else 0.08f)
        )
        btnSelectionClose.setColorFilter(palette.onSurface)
        tvSelectionCount.setTextColor(palette.onSurface)
        btnSelectionDue.setColorFilter(palette.onSurface)
        btnSelectionContainer.setColorFilter(palette.onSurface)
        btnSelectionComplete.setColorFilter(palette.accent)
        btnSelectionDelete.setColorFilter(dangerColor())
        btnSelectionMore.setColorFilter(palette.onSurfaceVariant)

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
        quickTagsView.setTextColor(palette.onSurfaceVariant)

        updateSmartViewLabel()
        updateTagFilterChip()
        updateSelectionBarUi()
        if (::navigationColumn.isInitialized) renderNavigation()
        calendarViewController?.updatePalette(palette)
        if (calendarMode) calendarViewController?.render()
    }

    private fun openTagFilterPicker() {
        if (childFragmentManager.findFragmentByTag("filterTags") == null) {
            TaskTagPickerBottomSheet.newInstance(
                ids = selectedTagFilter,
                resultKey = "filterTags",
                datasetId = component.session.datasetId,
                filter = true,
            ).show(childFragmentManager, "filterTags")
        }
    }

    private fun updateTagFilterChip() {
        if (!::tvTasksTagFilter.isInitialized) return
        val available = workspaceSnapshot?.availableTags.orEmpty()
        val selectedNames = available.filter { it.id in selectedTagFilter }.map { it.name }
        val active = selectedNames.isNotEmpty()
        val bg = GradientDrawable().apply {
            cornerRadius = dp(12f)
            if (active) {
                setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.14f))
                setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.accent, 0.45f))
            } else {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.2f))
            }
        }
        tvTasksTagFilter.background = bg
        tvTasksTagFilter.setTextColor(if (active) palette.accent else palette.onSurfaceVariant)
        tvTasksTagFilter.text = if (active) {
            val preview = selectedNames.take(2).joinToString(", ") { "#$it" }
            val extra = selectedNames.size - 2
            if (extra > 0) "$preview +$extra" else preview
        } else {
            "Теги"
        }
        tvTasksTagFilter.contentDescription = if (active) {
            "Фильтр по тегам: ${selectedNames.joinToString(", ")}"
        } else {
            "Фильтр по тегам"
        }
    }

    private fun setupOverflowMenu() {
        tvTasksTagFilter.setOnClickListener {
            openTagFilterPicker()
        }
        tvTasksTagFilter.setOnLongClickListener {
            if (selectedTagFilter.isNotEmpty()) {
                selectedTagFilter = emptySet()
                loadTasks()
                true
            } else {
                false
            }
        }
        childFragmentManager.setFragmentResultListener("filterTags", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            if (result.getBoolean(TaskTagPickerBottomSheet.RESULT_APPLIED, false)) {
                selectedTagFilter = result.getStringArrayList(TaskTagPickerBottomSheet.RESULT_TAG_IDS).orEmpty().toSet()
            }
            loadTasks()
        }

        btnTasksOverflow.setOnClickListener {
            val popup = PopupMenu(requireContext(), btnTasksOverflow)
            popup.menu.add(0, 1, 0, "Все задачи")
            popup.menu.add(0, 2, 1, "Завершённые")
            popup.menu.add(0, 6, 2, "Фильтр по тегам")
            if (selectedTagFilter.isNotEmpty()) {
                popup.menu.add(0, 7, 3, "Сбросить фильтр по тегам")
            }
            popup.menu.add(0, 3, 4, "Настройки")
            if (selectedContainerFilter != null) {
                popup.menu.add(0, 4, 5, getString(R.string.container_statistics_title))
            }
            popup.menu.add(0, 5, 6, getString(R.string.today_eval_menu_item))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        clearSelection()
                        selectedContainerFilter = null
                        activeSmartView = TaskSmartView.ALL
                        updateSmartViewLabel()
                        loadTasks()
                        true
                    }
                    2 -> {
                        clearSelection()
                        selectedContainerFilter = null
                        activeSmartView = TaskSmartView.COMPLETED
                        updateSmartViewLabel()
                        loadTasks()
                        true
                    }
                    6 -> {
                        openTagFilterPicker()
                        true
                    }
                    7 -> {
                        selectedTagFilter = emptySet()
                        loadTasks()
                        true
                    }
                    3 -> {
                        clearSelection()
                        workspaceNavigator?.invoke("Settings")
                        true
                    }
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

    private fun setupSelectionBar() {
        btnSelectionClose.setOnClickListener {
            clearSelection()
        }
        btnSelectionDue.setOnClickListener {
            val ids = selectedTaskIds.toList()
            if (ids.isNotEmpty()) openActionDuePicker(ids)
        }
        btnSelectionContainer.setOnClickListener {
            val ids = selectedTaskIds.toList()
            if (ids.isNotEmpty()) openActionContainerPicker(ids)
        }
        btnSelectionComplete.setOnClickListener {
            val ids = selectedTaskIds.toList()
            if (ids.isNotEmpty()) executeCompleteAction(ids)
        }
        btnSelectionDelete.setOnClickListener {
            val ids = selectedTaskIds.toList()
            if (ids.isNotEmpty()) confirmDeleteTasks(ids)
        }
        btnSelectionMore.setOnClickListener {
            val single = selectedTasksFromSnapshot().singleOrNull()
            if (single != null) showTaskQuickMenu(single)
        }

        childFragmentManager.setFragmentResultListener(REQUEST_KEY_ACTION_DUE, viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            val targetIds = pendingActionTaskIds.toList()
            pendingActionTaskIds.clear()
            if (targetIds.isEmpty()) return@setFragmentResultListener
            val chosenDue = TaskDuePickerBottomSheet.readResult(result)
            applyDueToTasks(targetIds, chosenDue)
        }
        childFragmentManager.setFragmentResultListener(REQUEST_KEY_ACTION_CONTAINER, viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            val targetIds = pendingActionTaskIds.toList()
            pendingActionTaskIds.clear()
            if (targetIds.isEmpty()) return@setFragmentResultListener
            val chosenContainer = result.getString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID)?.let(::ContainerId)
            applyContainerToTasks(targetIds, chosenContainer)
        }
    }

    private fun toggleTaskSelection(taskId: TaskId) {
        activeSnackbar?.dismiss()
        if (!selectedTaskIds.add(taskId)) {
            selectedTaskIds.remove(taskId)
        }
        if (selectedTaskIds.isNotEmpty() && quickExpanded) {
            collapseQuickComposerQuietly()
        }
        syncSelectionToAdapters()
        updateSelectionBarUi()
    }

    internal fun clearSelection() {
        if (selectedTaskIds.isEmpty()) return
        selectedTaskIds.clear()
        syncSelectionToAdapters()
        updateSelectionBarUi()
    }

    private fun syncSelectionToAdapters() {
        val currentSelection = selectedTaskIds.toSet()
        if (::adapter.isInitialized) adapter.updateSelection(currentSelection)
        if (::overdueAdapter.isInitialized) overdueAdapter.updateSelection(currentSelection)
        if (::completedAdapter.isInitialized) completedAdapter.updateSelection(currentSelection)
        groupAdapters.values.forEach { (_, rows) -> rows.updateSelection(currentSelection) }
    }

    private fun selectedTasksFromSnapshot(): List<UserTask> {
        val rootsById = workspaceSnapshot?.roots.orEmpty().associateBy { it.id }
        return selectedTaskIds.mapNotNull { rootsById[it] }
    }

    private fun updateSelectionBarUi() {
        if (!::tasksHeader.isInitialized || !::tasksSelectionBar.isInitialized) return
        val count = selectedTaskIds.size
        if (count == 0) {
            tasksSelectionBar.visibility = View.GONE
            tasksHeader.visibility = View.VISIBLE
            return
        }
        tasksHeader.visibility = View.GONE
        tasksSelectionBar.visibility = View.VISIBLE
        val compactFont = resources.configuration.screenWidthDp <= 380 && resources.configuration.fontScale > 1.15f
        tvSelectionCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compactFont) 14f else 16f)
        tvSelectionCount.text = "Выбрано: $count"
        tvSelectionCount.contentDescription = "Выбрано задач: $count"
        btnSelectionMore.visibility = if (count == 1) View.VISIBLE else View.GONE

        val selectedTasks = selectedTasksFromSnapshot()
        val allCompleted = selectedTasks.isNotEmpty() && selectedTasks.all { it.isCompleted }
        if (allCompleted) {
            btnSelectionComplete.setImageResource(R.drawable.ic_cat_refresh_cw)
            btnSelectionComplete.contentDescription = "Вернуть в активные"
        } else {
            btnSelectionComplete.setImageResource(R.drawable.ic_action_check)
            btnSelectionComplete.contentDescription = "Завершить выбранные"
        }
    }

    private fun collapseQuickComposerQuietly() {
        if (!quickExpanded) return
        quickExpanded = false
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        if (::layoutQuickAddExpanded.isInitialized && ::layoutQuickAddCollapsed.isInitialized) {
            layoutQuickAddExpanded.visibility = View.GONE
            layoutQuickAddCollapsed.visibility = View.VISIBLE
        }
        if (::etQuickAddTitle.isInitialized) {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etQuickAddTitle.windowToken, 0)
        }
    }

    private fun openActionDuePicker(ids: List<TaskId>) {
        if (ids.isEmpty()) return
        if (childFragmentManager.findFragmentByTag(REQUEST_KEY_ACTION_DUE) != null) return
        activeSnackbar?.dismiss()
        pendingActionTaskIds.clear()
        pendingActionTaskIds.addAll(ids)
        val distinctDues = ids.mapNotNull { taskRepo.getById(it)?.due }.distinct()
        val initialDue = if (distinctDues.size == 1) distinctDues.single() else TaskDue.None
        TaskDuePickerBottomSheet.newInstance(REQUEST_KEY_ACTION_DUE, initialDue)
            .show(childFragmentManager, REQUEST_KEY_ACTION_DUE)
    }

    private fun openActionContainerPicker(ids: List<TaskId>) {
        if (ids.isEmpty()) return
        if (childFragmentManager.findFragmentByTag(REQUEST_KEY_ACTION_CONTAINER) != null) return
        activeSnackbar?.dismiss()
        pendingActionTaskIds.clear()
        pendingActionTaskIds.addAll(ids)
        val distinctContainers = ids.mapNotNull { taskRepo.getById(it) }.map { it.containerId }.distinct()
        val initialContainer = if (distinctContainers.size == 1) distinctContainers.single() else null
        TaskContainerPickerBottomSheet.newInstance(
            initialContainer,
            REQUEST_KEY_ACTION_CONTAINER,
            component.session.datasetId,
        ).show(childFragmentManager, REQUEST_KEY_ACTION_CONTAINER)
    }

    private fun applyDueToTasks(ids: List<TaskId>, targetDue: TaskDue) {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return
        val entries = try {
            component.db.runInTransaction {
                val changed = mutableListOf<TaskUndoEntry>()
                for (id in uniqueIds) {
                    val before = taskRepo.getById(id) ?: continue
                    if (before.due == targetDue && !before.hasPendingLegacyPlan) continue
                    val after = taskService.updateTask(id = id, due = targetDue)
                    changed.add(TaskUndoEntry(before = before, after = after))
                }
                changed
            }
        } catch (e: Exception) {
            android.util.Log.e("TasksFragment", "Failed to update task due", e)
            Toast.makeText(requireContext(), "Не удалось обновить срок. Попробуйте ещё раз.", Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        clearSelection()
        loadTasks()
        if (entries.isNotEmpty()) {
            val msg = if (entries.size == 1) "Срок задачи обновлён" else "Срок обновлён (${entries.size})"
            showUndoSnackbar(msg, entries)
        }
    }

    private fun applyContainerToTasks(ids: List<TaskId>, targetContainer: ContainerId?) {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return
        val entries = try {
            component.db.runInTransaction {
                val changed = mutableListOf<TaskUndoEntry>()
                for (id in uniqueIds) {
                    val before = taskRepo.getById(id) ?: continue
                    if (before.containerId == targetContainer) continue
                    val after = taskService.updateTask(
                        id = id,
                        containerId = targetContainer,
                        clearContainer = targetContainer == null,
                    )
                    changed.add(TaskUndoEntry(before = before, after = after))
                }
                changed
            }
        } catch (e: Exception) {
            android.util.Log.e("TasksFragment", "Failed to move task container", e)
            Toast.makeText(requireContext(), "Не удалось переместить задачи. Попробуйте ещё раз.", Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        clearSelection()
        loadTasks()
        if (entries.isNotEmpty()) {
            val msg = if (entries.size == 1) "Задача перемещена" else "Задачи перемещены (${entries.size})"
            showUndoSnackbar(msg, entries)
        }
    }

    private fun executeCompleteAction(ids: List<TaskId>) {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return
        val result = try {
            component.db.runInTransaction {
                val existing = uniqueIds.mapNotNull { taskRepo.getById(it) }
                if (existing.isEmpty()) return@runInTransaction false to emptyList<TaskUndoEntry>()
                val targetCompleted = existing.any { !it.isCompleted }
                val changed = mutableListOf<TaskUndoEntry>()
                for (before in existing) {
                    if (before.isCompleted == targetCompleted) continue
                    val after = taskService.setCompleted(before.id, targetCompleted)
                    changed.add(TaskUndoEntry(before = before, after = after))
                }
                targetCompleted to changed
            }
        } catch (e: Exception) {
            android.util.Log.e("TasksFragment", "Failed to update completion state", e)
            Toast.makeText(requireContext(), "Не удалось обновить статус задач. Попробуйте ещё раз.", Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        val (targetCompleted, entries) = result
        clearSelection()
        loadTasks()
        if (entries.isNotEmpty()) {
            val msg = when {
                entries.size == 1 && targetCompleted -> "Задача завершена"
                entries.size == 1 && !targetCompleted -> "Задача возвращена в активные"
                targetCompleted -> "Завершено задач: ${entries.size}"
                else -> "Возвращено в активные: ${entries.size}"
            }
            showUndoSnackbar(msg, entries)
        }
    }

    private fun confirmDeleteTasks(ids: List<TaskId>) {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return
        activeSnackbar?.dismiss()
        pendingDeleteConfirmationIds.clear()
        pendingDeleteConfirmationIds.addAll(uniqueIds)
        deleteConfirmationDialog?.setOnDismissListener(null)
        deleteConfirmationDialog?.dismiss()

        val title = if (uniqueIds.size == 1) {
            "Удалить задачу?"
        } else {
            "Удалить выбранные задачи (${uniqueIds.size})?"
        }
        val message = if (uniqueIds.size == 1) {
            "Задача и её подзадачи будут удалены без возможности отмены."
        } else {
            "Выбранные задачи и их подзадачи будут удалены без возможности отмены."
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Отмена") { _, _ ->
                pendingDeleteConfirmationIds.clear()
            }
            .setPositiveButton("Удалить") { _, _ ->
                val toDelete = pendingDeleteConfirmationIds.toList()
                pendingDeleteConfirmationIds.clear()
                executeDeleteTasks(toDelete)
            }
            .setOnCancelListener {
                pendingDeleteConfirmationIds.clear()
            }
            .create()
        deleteConfirmationDialog = dialog
        dialog.show()
    }

    private fun executeDeleteTasks(ids: List<TaskId>) {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return
        try {
            component.db.runInTransaction {
                for (id in uniqueIds) {
                    if (taskRepo.getById(id) != null) {
                        taskService.deleteTask(id, cascadeSubtasks = true)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TasksFragment", "Failed to delete tasks", e)
            Toast.makeText(requireContext(), "Не удалось удалить задачи. Попробуйте ещё раз.", Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        activeSnackbar?.dismiss()
        lastUndoEntries = emptyList()
        clearSelection()
        loadTasks()
    }

    private fun showUndoSnackbar(message: String, entries: List<TaskUndoEntry>) {
        if (entries.isEmpty() || !isAdded || view == null) return
        lastUndoEntries = entries
        activeSnackbar?.dismiss()
        val snackbar = Snackbar.make(shellRoot, message, Snackbar.LENGTH_LONG)
        if (::layoutQuickAddContainer.isInitialized && layoutQuickAddContainer.visibility == View.VISIBLE) {
            snackbar.anchorView = layoutQuickAddContainer
        }
        val bgColor = if (palette.isDark) palette.surfaceVariant else Color.parseColor("#252B33")
        snackbar.setBackgroundTint(bgColor)
        snackbar.setTextColor(Color.WHITE)
        snackbar.setActionTextColor(if (palette.isDark) palette.accent else Color.parseColor("#7CC4FA"))
        snackbar.setAction("Отменить") {
            performUndo(entries)
        }
        activeSnackbar = snackbar
        snackbar.show()
    }

    internal fun performUndo(entries: List<TaskUndoEntry> = lastUndoEntries): Int {
        if (entries.isEmpty()) return 0
        val restoredCount = try {
            component.db.runInTransaction {
                var count = 0
                for (entry in entries) {
                    val restored = taskService.restoreTaskSnapshot(
                        expectedCurrent = entry.after,
                        previousState = entry.before,
                    )
                    if (restored != null) count++
                }
                count
            }
        } catch (e: Exception) {
            android.util.Log.e("TasksFragment", "Failed to undo task operation", e)
            Toast.makeText(requireContext(), "Не удалось отменить действие.", Toast.LENGTH_LONG).show()
            loadTasks()
            return 0
        }
        lastUndoEntries = emptyList()
        loadTasks()
        return restoredCount
    }

    private fun updateSmartViewLabel() {
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
        tvTitle.text = if (calendarMode) {
            if (ru) "Календарь" else "Calendar"
        } else {
            selectedContainerFilter?.let { workspaceSnapshot?.availableContainers.orEmpty().find { item -> item.key == it.value }?.name }
                ?: if (matrixMode) {
                    if (ru) "Матрица" else "Matrix"
                } else activeSmartView.displayName
        }
    }

    private fun setupRecyclerView() {
        fun taskAdapter() = TasksAdapter(
            palette = palette,
            onTaskClicked = { task ->
                if (selectedTaskIds.isNotEmpty()) {
                    toggleTaskSelection(task.id)
                } else {
                    val sheet = TaskDetailBottomSheet.newInstance(task.id)
                    sheet.onTaskUpdatedListener = { loadTasks() }
                    sheet.show(childFragmentManager, "task_detail")
                }
            },
            onToggleComplete = { task ->
                if (selectedTaskIds.isNotEmpty()) {
                    toggleTaskSelection(task.id)
                } else {
                    executeCompleteAction(listOf(task.id))
                }
            },
            onTaskLongClicked = { task, _ ->
                toggleTaskSelection(task.id)
            },
            onChangeDue = { task ->
                openActionDuePicker(listOf(task.id))
            },
            onMoveContainer = { task ->
                openActionContainerPicker(listOf(task.id))
            },
            onShowContextMenu = { task ->
                showTaskQuickMenu(task)
            },
        )
        adapter = taskAdapter()
        createGroupAdapter = ::taskAdapter
        groupAdapters.clear()
        groupedAdapter = TaskListAdapter()
        overdueAdapter = taskAdapter()
        completedAdapter = taskAdapter()
        overdueHeader = TaskSectionAdapter("Просроченные") {
            overdueExpanded = !overdueExpanded
            workspaceSnapshot?.let(::renderTasks)
        }
        activeHeader = TaskSectionAdapter("Сегодня")
        completedHeader = TaskSectionAdapter("Завершённые") {
            completedExpanded = !completedExpanded
            workspaceSnapshot?.let(::renderTasks)
        }
        rvTasks.layoutManager = LinearLayoutManager(requireContext())
        rvTasks.itemAnimator = null
        rvTasks.adapter = TaskListAdapter(
            overdueHeader, overdueAdapter, activeHeader, groupedAdapter, completedHeader, completedAdapter
        )
        swipeCallback = TaskSwipeCallback(
            palette = palette,
            isSelectionModeActive = { selectedTaskIds.isNotEmpty() },
            onSwipeChangeDue = { task -> openActionDuePicker(listOf(task.id)) },
            onSwipeMoveContainer = { task -> openActionContainerPicker(listOf(task.id)) },
        )
        val helper = ItemTouchHelper(swipeCallback)
        swipeCallback.itemTouchHelper = helper
        swipeCallback.attachedRecyclerView = rvTasks
        helper.attachToRecyclerView(rvTasks)
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

            val availableTags = workspaceSnapshot?.availableTags.orEmpty()
            val selectedTagNames = availableTags.filter { it.id in quickTagIds }.map { it.name }
            val hasTags = selectedTagNames.isNotEmpty()
            val tagsBg = GradientDrawable().apply {
                cornerRadius = dp(18f)
                if (hasTags) {
                    setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.12f))
                    setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.accent, 0.4f))
                } else {
                    setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                    setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.2f))
                }
            }
            quickTagsView.background = tagsBg
            quickTagsView.setTextColor(if (hasTags) palette.accent else palette.onSurfaceVariant)
            quickTagsView.text = if (hasTags) {
                val firstTag = "#${selectedTagNames.first()}"
                if (selectedTagNames.size > 1) "$firstTag +${selectedTagNames.size - 1}" else firstTag
            } else {
                "Теги"
            }

            updateSubmitButtonState()
        }

        fun expandComposer() {
            clearSelection()
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
        childFragmentManager.setFragmentResultListener("quickTags", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            if (result.getBoolean(TaskTagPickerBottomSheet.RESULT_APPLIED, false)) {
                quickTagIds = result.getStringArrayList(TaskTagPickerBottomSheet.RESULT_TAG_IDS).orEmpty().toSet()
                quickDraftRestored = true
            }
            loadTasks()
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
        quickTagsView.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("quickTags") == null) {
                TaskTagPickerBottomSheet.newInstance(quickTagIds, "quickTags", component.session.datasetId)
                    .show(childFragmentManager, "quickTags")
            }
        }

        btnQuickAddExpand.setOnClickListener {
            val title = etQuickAddTitle.text?.toString().orEmpty()
            val draft = TaskDraft(
                title = title,
                description = quickDraftNotes,
                due = quickTaskDue,
                containerId = quickContainer,
                tagIds = quickTagIds,
                important = quickImportant,
                urgent = quickUrgent,
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
                quickTagIds = emptySet()
                quickImportant = null
                quickUrgent = null
                quickDraftRestored = false
                collapseComposer()
                loadTasks()
            } else if (action == TaskDetailBottomSheet.ACTION_COLLAPSED) {
                val returnedDraft = TaskDraft.fromBundle(result.getBundle(TaskDetailBottomSheet.RESULT_DRAFT))
                etQuickAddTitle.setText(returnedDraft.title)
                etQuickAddTitle.setSelection(returnedDraft.title.length)
                quickTaskDue = returnedDraft.due
                quickContainer = returnedDraft.containerId
                quickTagIds = returnedDraft.tagIds
                quickImportant = returnedDraft.important
                quickUrgent = returnedDraft.urgent
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
            val tagsToAssign = quickTagIds

            val created = try { component.db.runInTransaction {
                val mainTask = taskService.createTask(
                    title = title,
                    description = notes,
                    containerId = cid,
                    due = due,
                    important = quickImportant,
                    urgent = quickUrgent,
                )
                if (tagsToAssign.isNotEmpty()) {
                    component.taskTagRepository.setTags(mainTask.id, tagsToAssign)
                }
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
            quickTagIds = emptySet()
            quickImportant = null
            quickUrgent = null
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
            if (selectedTagFilter.isNotEmpty() && !tagsToAssign.containsAll(selectedTagFilter)) {
                selectedTagFilter = emptySet()
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
                var stable: Pair<Long, Triple<TasksWorkspaceSnapshot, List<Habit>, List<org.isoron.uhabits.core.focus.FocusSession>>>? = null
                repeat(3) {
                    if (stable == null) {
                        val before = component.workspaceReadRevision.value
                        val snapshot = TasksWorkspaceSnapshot.load(
                            taskRepo,
                            orgFacade.containerQueries,
                            component.taskTagRepository,
                        )
                        val habitsList = component.habitList.getFiltered(
                            org.isoron.uhabits.core.models.HabitMatcher(isArchivedAllowed = false)
                        ).toList()
                        val (qStart, qEnd) = FocusHistoryProjector.calculateQueryRange(calendarScale, selectedDay, calendarMonth)
                        val sessions = component.focusSessionRepository?.getByTimeRange(qStart, qEnd).orEmpty()
                        if (before == component.workspaceReadRevision.value) {
                            stable = before to Triple(snapshot, habitsList, sessions)
                        }
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
            workspaceSnapshot = loaded.second.first
            activeHabits = loaded.second.second
            renderTasks(loaded.second.first, loaded.second.third)
        }
    }

    private fun renderTasks(
        snapshot: TasksWorkspaceSnapshot,
        rawSessions: List<org.isoron.uhabits.core.focus.FocusSession> = historyFocusSessions
    ) {
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
        val availableTagIds = snapshot.availableTags.mapTo(mutableSetOf()) { it.id }
        selectedTagFilter = selectedTagFilter.intersect(availableTagIds)
        quickTagIds = quickTagIds.intersect(availableTagIds)
        val allRoots = snapshot.roots
        val subtaskMap = snapshot.subtaskCounts
        val taskTagMap = snapshot.taskTags

        val today = runCatching { getToday() }.getOrDefault(LocalDate(2026, 10, 4))
        val nowMillis = System.currentTimeMillis()

        // 1. Container filter
        val containerFiltered = if (selectedContainerFilter != null) {
            val ids = snapshot.scope(selectedContainerFilter!!)
            allRoots.filter { it.containerId in ids }
        } else {
            allRoots
        }

        // 2. Tag filter (intersection: task must have all selectedTagFilter tags)
        val tagFiltered = if (selectedTagFilter.isNotEmpty()) {
            containerFiltered.filter { task ->
                val assigned = taskTagMap[task.id].orEmpty().mapTo(mutableSetOf()) { it.id }
                assigned.containsAll(selectedTagFilter)
            }
        } else {
            containerFiltered
        }

        // 3. Smart View filter
        val viewFiltered = tagFiltered.filter { activeSmartView.matches(it, today, nowMillis) }
        updateSmartViewLabel()
        updateTagFilterChip()
        refreshQuickLabels?.invoke()
        renderNavigation()
        val scopeContainerIds = if (selectedContainerFilter != null) snapshot.scope(selectedContainerFilter!!) else emptySet()
        historyFocusSessions = FocusHistoryProjector.filterByContainer(
            sessions = rawSessions,
            targetContainer = selectedContainerFilter,
            currentSubtreeIds = scopeContainerIds,
            containerQueries = orgFacade.containerQueries
        )
        val calendarTasks = if (activeSmartView == TaskSmartView.COMPLETED) tagFiltered.filter { it.isCompleted } else tagFiltered
        renderCalendar(calendarTasks, activeHabits, historyFocusSessions)

        val sections = TaskListSections.build(
            tagFiltered,
            activeSmartView,
            today,
            nowMillis,
            availableContainers,
            TaskDueDisplayFormatter.selectedLocale(requireContext()),
            matrix = matrixMode,
        )

        val renderedTaskIds = hashSetOf<TaskId>().apply {
            if (!calendarMode) {
                if (sections.groups.isEmpty()) {
                    sections.active.forEach { add(it.id) }
                } else {
                    sections.groups.forEach { group -> group.tasks.forEach { add(it.id) } }
                }
                if (overdueExpanded) sections.overdue.forEach { add(it.id) }
                if (completedExpanded) sections.completed.forEach { add(it.id) }
            }
        }
        selectedTaskIds.retainAll(renderedTaskIds)
        val currentSelection = selectedTaskIds.toSet()
        updateSelectionBarUi()

        val containerItemMap = availableContainers.associateBy { ContainerId(it.key) }

        adapter.updateData(
            newTasks = if (sections.groups.isEmpty()) sections.active else emptyList(),
            newSubtaskCounts = subtaskMap,
            newContainers = containerItemMap,
            currentToday = today,
            currentTimeMillis = nowMillis,
            newSelectedIds = currentSelection,
            newTaskTags = taskTagMap,
        )
        groupAdapters.keys.retainAll(sections.groups.map { it.key }.toSet())
        groupedAdapter.replaceSections(if (sections.groups.isEmpty()) listOf(adapter) else
            sections.groups.flatMap { group ->
                val (header, rows) = groupAdapters.getOrPut(group.key) {
                    TaskSectionAdapter(group.title) to createGroupAdapter()
                }
                header.update(true, true, palette, group.title)
                rows.updatePalette(palette)
                rows.updateData(group.tasks, subtaskMap, containerItemMap, today, nowMillis, currentSelection, taskTagMap)
                listOf(header, rows)
            })
        overdueAdapter.updateData(if (overdueExpanded) sections.overdue else emptyList(), subtaskMap, containerItemMap, today, nowMillis, currentSelection, taskTagMap)
        completedAdapter.updateData(if (completedExpanded) sections.completed else emptyList(), subtaskMap, containerItemMap, today, nowMillis, currentSelection, taskTagMap)
        overdueHeader.update(sections.overdue.isNotEmpty(), overdueExpanded, palette)
        activeHeader.update(sections.groups.isEmpty() && sections.overdue.isNotEmpty() && sections.active.isNotEmpty(), true, palette,
            if (activeSmartView == TaskSmartView.TODAY) "Сегодня" else "Задачи")
        completedHeader.update(sections.completed.isNotEmpty(), completedExpanded, palette)

        // Subtitle count and info row visibility
        layoutListInfo.visibility = if (calendarMode) View.GONE else View.VISIBLE
        if (!calendarMode) {
            val activeCount = when {
                activeSmartView == TaskSmartView.TODAY -> sections.active.size
                else -> sections.active.size + sections.overdue.size
            }
            tvSubtitle.text = "$activeCount ${pluralTasks(activeCount)}"
            tvSubtitle.visibility = if (!matrixMode && activeSmartView == TaskSmartView.COMPLETED) View.GONE else View.VISIBLE

            // Date indicator on the right (shown in TODAY mode)
            if (!matrixMode && activeSmartView == TaskSmartView.TODAY) {
                val dateStr = dateFormatRu.format(Date(nowMillis))
                tvTasksDate.text = dateStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
                tvTasksDate.visibility = View.VISIBLE
            } else {
                tvTasksDate.visibility = View.GONE
            }
        }

        // Empty view visibility
        val hasAnyTasks = if (matrixMode) {
            sections.groups.any { it.tasks.isNotEmpty() }
        } else {
            sections.active.isNotEmpty() || sections.overdue.isNotEmpty() || sections.completed.isNotEmpty()
        }
        if (calendarMode) {
            rvTasks.visibility = View.GONE
            layoutEmpty.visibility = View.GONE
        } else if (!hasAnyTasks) {
            rvTasks.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            updateEmptyMessage()
        } else {
            rvTasks.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
        }
    }

    private fun updateEmptyMessage() {
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
        if (selectedTagFilter.isNotEmpty()) {
            tvEmptyTitle.text = if (ru) "Нет задач с выбранными тегами" else "No tasks with selected tags"
            tvEmptyMessage.text = if (ru) "Сбросьте фильтр по тегам или выберите другие теги" else "Clear tag filter or select other tags"
            return
        }
        if (selectedContainerFilter != null) {
            tvEmptyTitle.text = if (ru) "В этом разделе нет задач" else "No tasks in this container"
            tvEmptyMessage.text = if (ru) "Выберите другой режим или добавьте задачу ниже" else "Select another mode or add a task below"
            return
        }
        if (matrixMode) {
            tvEmptyTitle.text = if (ru) "Матрица пуста" else "Matrix is empty"
            tvEmptyMessage.text = if (ru) "Добавьте новую задачу через строку внизу" else "Add a new task using the field below"
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
            setOnClickListener {
                clearSelection()
                val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
                val labels = if (ru) arrayOf("Список", "Календарь", "Матрица") else arrayOf("List", "Calendar", "Matrix")
                AlertDialog.Builder(requireContext()).setSingleChoiceItems(labels,
                    if (matrixMode) 2 else if (calendarMode) 1 else 0) { dialog, choice ->
                    calendarMode = choice == 1
                    matrixMode = choice == 2
                    dialog.dismiss()
                    updateSmartViewLabel()
                    loadTasks()
                }.show()
            }
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
        if (selectedTaskIds.isNotEmpty()) {
            clearSelection()
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

    private fun navigationRow(
        label: String,
        icon: Int,
        count: Int? = null,
        selected: Boolean = false,
        depth: Int = 0,
        action: () -> Unit,
    ): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpInt(48f)
        setPaddingRelative(dpInt(16f + depth * 12f), 0, dpInt(12f), 0)
        val ripple = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        if (selected) background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(MainTabsThemeBridge.withAlpha(palette.accent, .16f)),
            GradientDrawable().apply { cornerRadius = dp(8f); setColor(MainTabsThemeBridge.withAlpha(palette.accent, .09f)) },
            null,
        )
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
        fun divider() {
            navigationColumn.addView(
                View(requireContext()).apply { setBackgroundColor(palette.divider) },
                LinearLayout.LayoutParams(-1, dpInt(1f)).apply { topMargin = dpInt(12f); bottomMargin = dpInt(12f) },
            )
        }
        if (wide) {
            listOf(
                Triple("Habits", "Привычки", R.drawable.ic_tasks_completed),
                Triple("Tasks", "Задачи", R.drawable.ic_cat_list_todo),
                Triple("Statistics", "Статистика", R.drawable.ic_settings_statistics),
            ).forEach { (key, name, icon) ->
                navigationColumn.addView(navigationRow(name, icon, selected = key == "Tasks") {
                    clearSelection()
                    workspaceNavigator?.invoke(key)
                })
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
            R.drawable.ic_tasks_completed,
        )
        TaskSmartView.entries.forEachIndexed { index, smart ->
            val count = roots.count { smart.matches(it, getToday(), System.currentTimeMillis()) }
            navigationColumn.addView(navigationRow(
                smart.displayName,
                smartIcons[index],
                count.takeUnless { smart == TaskSmartView.COMPLETED },
                selectedContainerFilter == null && activeSmartView == smart,
            ) {
                clearSelection()
                selectedContainerFilter = null
                activeSmartView = smart
                closeNavigation()
                loadTasks()
            })
        }
        divider()
        navigationColumn.addView(navText("Разделы").apply { textSize = 12f; minHeight = dpInt(32f); setTextColor(palette.onSurfaceVariant) })
        if (snapshot.containers.isNotEmpty()) {
            localTreeRoot?.let { id ->
                navigationColumn.addView(navigationRow(
                    snapshot.containers[id]?.parentId?.let { snapshot.containers[it]?.name } ?: "Все разделы",
                    R.drawable.ic_tasks_parent,
                ) { localTreeRoot = snapshot.containers[id]?.parentId; renderNavigation() })
            }
            fun addContainer(item: org.isoron.uhabits.core.containers.Container, depth: Int) {
                val children = snapshot.children[item.id].orEmpty()
                val count = snapshot.pendingCounts[item.id] ?: 0
                val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(navigationRow(
                    item.name,
                    if (depth == 0) R.drawable.ic_cat_folder else R.drawable.ic_cat_list_todo,
                    count,
                    selectedContainerFilter == item.id,
                    depth,
                ) {
                    clearSelection()
                    selectedContainerFilter = item.id
                    activeSmartView = TaskSmartView.ALL
                    closeNavigation()
                    loadTasks()
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
            if (snapshot.availableTags.isNotEmpty()) {
                divider()
                navigationColumn.addView(navText("Теги").apply { textSize = 12f; minHeight = dpInt(32f); setTextColor(palette.onSurfaceVariant) })
                snapshot.availableTags.forEach { tag ->
                    val isSelected = tag.id in selectedTagFilter
                    val count = snapshot.roots.count { root -> !root.isCompleted && snapshot.taskTags[root.id].orEmpty().any { it.id == tag.id } }
                    navigationColumn.addView(navigationRow(
                        "#${tag.name}",
                        R.drawable.ic_cat_flag,
                        count,
                        isSelected,
                    ) {
                        clearSelection()
                        selectedTagFilter = if (isSelected) selectedTagFilter - tag.id else selectedTagFilter + tag.id
                        closeNavigation()
                        loadTasks()
                    })
                }
            }
            divider()
            navigationColumn.addView(navigationRow("Настройки", R.drawable.ic_nav_settings) {
            clearSelection()
            closeNavigation()
            workspaceNavigator?.invoke("Settings")
        })
    }

    private fun renderCalendar(
        tasks: List<UserTask>,
        habits: List<Habit> = activeHabits,
        sessions: List<org.isoron.uhabits.core.focus.FocusSession> = historyFocusSessions
    ) {
        representationButton.setImageResource(if (calendarMode) R.drawable.ic_cat_list_todo else R.drawable.ic_cat_calendar)
        representationButton.contentDescription = "Представление: " + if (matrixMode) "Матрица" else if (calendarMode) "Календарь" else "Список"
        calendarPanel.visibility = if (calendarMode) View.VISIBLE else View.GONE
        if (!calendarMode) return

        val controller = calendarViewController ?: TaskCalendarViewController(
            context = requireContext(),
            container = calendarPanel,
            palette = palette,
            wide = wide,
            onTaskClicked = { task ->
                clearSelection()
                val sheet = TaskDetailBottomSheet.newInstance(task.id)
                sheet.onTaskUpdatedListener = { loadTasks() }
                sheet.show(childFragmentManager, "task_detail")
            },
            onTaskToggleComplete = { task ->
                executeCompleteAction(listOf(task.id))
            },
            onTaskQuickMenu = { task ->
                showTaskQuickMenu(task)
            },
            onHabitClicked = { plannedHabit ->
                showPlannedHabitDialog(plannedHabit)
            },
            onDateChanged = { newDate ->
                selectedDay = newDate
                loadTasks()
            },
            onScaleChanged = { newScale ->
                calendarScale = newScale
                loadTasks()
            },
            onModeChanged = { newMode ->
                calendarViewMode = newMode
                loadTasks()
            }
        ).also { calendarViewController = it }

        controller.selectedDay = selectedDay
        controller.scale = calendarScale
        controller.mode = calendarViewMode
        controller.calendarMonth = calendarMonth
        controller.updatePalette(palette)
        controller.updateData(
            newTasks = tasks,
            newHabits = habits,
            newContainers = workspaceSnapshot?.containers.orEmpty().mapValues { (id, c) ->
                OrganizationalItem(id.value, c.name, c.color, c.icon, c.name, c.parentId == null)
            },
            newTaskTags = workspaceSnapshot?.taskTags.orEmpty(),
            newFocusSessions = sessions
        )
        controller.render()
    }

    private fun showPlannedHabitDialog(plannedHabit: PlannedHabit) {
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
        val habit = component.habitList.getById(plannedHabit.habitId)
        val details = buildString {
            if (plannedHabit.reminderHour != null && plannedHabit.reminderMinute != null) {
                val time = String.format(Locale.getDefault(), "%02d:%02d", plannedHabit.reminderHour, plannedHabit.reminderMinute)
                append(if (ru) "Напоминание запланировано на $time." else "Reminder scheduled at $time.")
            } else {
                append(if (ru) "Запланировано по регулярному расписанию." else "Scheduled according to regular recurrence.")
            }
            append("\n\n")
            append(if (ru) "Привычки и задачи остаются раздельными сущностями. Просмотр календаря не изменяет прогресс привычки." else "Habits and tasks remain distinct entities. Viewing the calendar does not alter habit progress.")
        }

        AlertDialog.Builder(requireContext())
            .setTitle(plannedHabit.name)
            .setMessage(details)
            .setPositiveButton(if (ru) "Открыть привычку" else "Open habit") { _, _ ->
                if (habit != null) {
                    val intent = Intent(requireContext(), ShowHabitActivity::class.java).apply {
                        data = Uri.parse(habit.uriString)
                    }
                    startActivity(intent)
                }
            }
            .setNegativeButton(if (ru) "Закрыть" else "Close", null)
            .show()
    }

    internal fun showTaskQuickMenu(task: UserTask) {
        val isSelected = task.id in selectedTaskIds
        val actions = arrayOf(
            "Изменить срок",
            "Переместить в раздел",
            "Редактировать",
            if (task.isCompleted) "Отметить невыполненной" else "Завершить",
            if (isSelected) "Снять выделение" else "Выбрать",
            "Удалить",
        )
        AlertDialog.Builder(requireContext())
            .setTitle(task.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openActionDuePicker(listOf(task.id))
                    1 -> openActionContainerPicker(listOf(task.id))
                    2 -> {
                        clearSelection()
                        val sheet = TaskDetailBottomSheet.newInstance(task.id)
                        sheet.onTaskUpdatedListener = { loadTasks() }
                        sheet.show(childFragmentManager, "task_detail")
                    }
                    3 -> executeCompleteAction(listOf(task.id))
                    4 -> toggleTaskSelection(task.id)
                    5 -> confirmDeleteTasks(listOf(task.id))
                }
            }
            .show()
    }

    companion object {
        internal const val REQUEST_KEY_ACTION_DUE = "taskActionDue"
        internal const val REQUEST_KEY_ACTION_CONTAINER = "taskActionContainer"
    }
}
