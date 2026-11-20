/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.view.Window

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.activities.containers.CreateContainerDialog
import org.isoron.uhabits.activities.containers.DrillDownMoveDialog
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.Habit
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
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
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
import kotlin.math.roundToInt

class TasksFragment : Fragment() {

    internal data class TaskUndoEntry(
        val before: UserTask,
        val after: UserTask,
    )

    private data class InformationDialog(
        val message: String,
        val title: String? = null,
        val openHabit: (() -> Unit)? = null,
    )

    private var informationDialog by mutableStateOf<InformationDialog?>(null)
    private var quickActionMenu by mutableStateOf<UserTask?>(null)
    private var deleteConfirmation by mutableStateOf<Pair<String, String>?>(null)

    private lateinit var taskRepo: UserTaskRepository
    private lateinit var taskService: UserTaskService
    private lateinit var orgFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette
    private lateinit var component: HabitsApplicationComponent
    private var workspaceSnapshot by mutableStateOf<TasksWorkspaceSnapshot?>(null)
    private var composePalette by mutableStateOf<SettingsThemePalette?>(null)
    private var snapshotRevision = -1L
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var workspaceActive = false
    private val workspaceChanged: () -> Unit = {
        activity?.runOnUiThread { if (workspaceActive && view != null) loadTasks() }
    }
    private val timerListener = org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager.Listener {
        activity?.runOnUiThread { if (workspaceActive && view != null && calendarMode) loadTasks() }
    }

    var workspaceNavigator: ((String) -> Unit)? = null
    private var drawerOpen by mutableStateOf(false)
    private var topBarCoordinates: LayoutCoordinates? = null
    private var navigationCoordinates: LayoutCoordinates? = null
    private var quickAddAnchor: View? = null
    private var localTreeRoot by mutableStateOf<ContainerId?>(null)
    private var collapsedContainers by mutableStateOf(emptySet<String>())
    private var smartViewsExpanded by mutableStateOf(false)
    private var navigationHiddenSections by mutableStateOf(emptySet<String>())

    private var calendarMode = false
    private var matrixMode = false
    private var calendarScale = CalendarScale.MONTH
    private var calendarViewMode = CalendarMode.PLANNING
    private var activeHabits: List<Habit> = emptyList()
    private var historyFocusSessions: List<org.isoron.uhabits.core.focus.FocusSession> = emptyList()
    private var calendarViewController: TaskCalendarViewController? = null
    private var calendarShowHabits: Boolean? = null
    private var quickImportant: Boolean? = null
    private var quickUrgent: Boolean? = null
    private var renderedCalendarMonth: String? = null
    private var selectedDay = getToday()
    private var calendarMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var restoredTitle: String? = null
    private var wide = false

    private var quickTaskDue by mutableStateOf<TaskDue>(TaskDue.None)
    private var quickContainer by mutableStateOf<ContainerId?>(null)
    private var quickTagIds by mutableStateOf<Set<String>>(emptySet())
    private var quickDraftRestored = false
    private var quickExpanded by mutableStateOf(false)
    private var quickTitle by mutableStateOf("")
    private var quickDraftNotes: String = ""
    private val quickDraftSubtasks = mutableListOf<TaskDraft.DraftSubtask>()
    private var activeSmartView: TaskSmartView = TaskSmartView.TODAY
    private var selectedContainerFilter: ContainerId? = null
    private var selectedTagFilter: Set<String> = emptySet()

    private val selectedTaskIds = linkedSetOf<TaskId>()
    private val pendingActionTaskIds = linkedSetOf<TaskId>()
    private val pendingDeleteConfirmationIds = linkedSetOf<TaskId>()
    private var activeSnackbar: Snackbar? = null
    private var lastUndoEntries: List<TaskUndoEntry> = emptyList()

    private data class WorkspaceScope(
        val smartView: TaskSmartView = TaskSmartView.TODAY,
        val container: ContainerId? = null,
        val tags: Set<String> = emptySet(),
        val calendar: Boolean = false,
        val matrix: Boolean = false,
    )

    // The Fragment owns and restores the filters. Publish the scope together for the screen.
    private var workspaceScope by mutableStateOf(WorkspaceScope())
    private var currentOverdueCount by mutableStateOf(0)
    private var currentActiveCount by mutableStateOf(0)
    private var showEmptyState by mutableStateOf(false)

    internal var taskListState = LazyListState()
        private set
    internal var taskListContent by mutableStateOf<TasksListContent?>(null)
        private set
    private var listSelection by mutableStateOf(emptySet<TaskId>())
    private var pendingListAnchor: ListAnchor? = null

    private data class ListAnchor(val key: String, val index: Int, val offset: Int)
    private var overdueExpanded = true
    private var completedExpanded = false

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
            selectedTagFilter = emptySet()
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
            if (!restoredTitle.isNullOrEmpty()) {
                quickTitle = restoredTitle!!
            }
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
            if (it.containsKey("calendarShowHabits")) {
                calendarShowHabits = it.getBoolean("calendarShowHabits")
            }
            quickImportant = if (it.containsKey("quickImportant")) it.getBoolean("quickImportant") else null
            quickUrgent = if (it.containsKey("quickUrgent")) it.getBoolean("quickUrgent") else null
            selectedDay = LocalDate(it.getInt("selectedDay", getToday().daysSince2000))
            calendarMonth.timeInMillis = it.getLong("calendarMonth", calendarMonth.timeInMillis)
            localTreeRoot = it.getString("localTreeRoot")?.let(::ContainerId)
            collapsedContainers = it.getStringArrayList("collapsedContainers").orEmpty().toSet()
            smartViewsExpanded = it.getBoolean("smartViewsExpanded", false)
            selectedTaskIds.clear()
            it.getStringArrayList("selectedTaskIds")?.mapTo(selectedTaskIds, ::TaskId)
            pendingActionTaskIds.clear()
            it.getStringArrayList("pendingActionTaskIds")?.mapTo(pendingActionTaskIds, ::TaskId)
            pendingDeleteConfirmationIds.clear()
            it.getStringArrayList("pendingDeleteConfirmationIds")?.mapTo(pendingDeleteConfirmationIds, ::TaskId)
        }
        val app = requireContext().applicationContext as HabitsApplication
        savedInstanceState?.let { state ->
            pendingListAnchor = state.getString("taskListAnchorKey")
                ?.takeIf { state.getString("taskListDataset") == app.component.session.datasetId }
                ?.let { ListAnchor(it, state.getInt("taskListAnchorIndex"), state.getInt("taskListAnchorOffset")) }
        }
        if (::component.isInitialized && component !== app.component) {
            workspaceSnapshot = null
            snapshotRevision = -1L
            selectedTaskIds.clear()
            pendingActionTaskIds.clear()
            pendingDeleteConfirmationIds.clear()
            lastUndoEntries = emptyList()
            pendingListAnchor = null
        }
        component = app.component
        taskRepo = component.userTaskRepository
        taskService = app.component.userTaskService
        orgFacade = app.component.organizationFacade
        palette = MainTabsThemeBridge.resolve(requireContext())
        composePalette = palette

        wide = resources.configuration.screenWidthDp >= 600
        taskListState = LazyListState()
        showEmptyState = false
        navigationHiddenSections = hiddenSections()
        syncWorkspaceScope()
        listSelection = selectedTaskIds.toSet()

        val calendar = createCalendarController()
        calendarViewController = calendar
        return inflater.inflate(R.layout.fragment_tasks, container, false).apply {
            findViewById<ComposeView>(R.id.tasksWorkspaceComposeView).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val currentPalette = composePalette ?: return@setContent
                    val colors = UHabitComposeColors.fromPalette(currentPalette)
                    UHabitComposeTheme(colors = colors) {
                        TasksWorkspaceCompose(
                            wide = wide,
                            drawerOpen = drawerOpen,
                            calendarVisible = workspaceScope.calendar,
                            onCloseDrawer = ::closeNavigation,
                            onQuickAddAnchor = { quickAddAnchor = it },
                            navigation = { WorkspaceNavigation(colors) },
                            topBar = { WorkspaceTopBar(colors) },
                            listInfo = { WorkspaceListInfo(colors) },
                            list = {
                                if (showEmptyState) WorkspaceEmptyState(colors)
                                else WorkspaceList(currentPalette)
                            },
                            calendar = { calendar.Content() },
                            quickAdd = { WorkspaceQuickAdd(colors) },
                            dialogs = { WorkspaceDialogs() },
                        )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSelectionBarResultListeners()
        setupQuickAddResultListeners()
        applyWindowTheme(view)
        if (pendingDeleteConfirmationIds.isNotEmpty()) {
            view.post {
                if (isAdded && pendingDeleteConfirmationIds.isNotEmpty()) {
                    confirmDeleteTasks(pendingDeleteConfirmationIds.toList())
                }
            }
        }
    }

    @Composable
    private fun WorkspaceDialogs() {
        informationDialog?.let { dialog ->
            TaskInformationDialog(
                title = dialog.title,
                message = dialog.message,
                onDismiss = { informationDialog = null },
                onOpenHabit = dialog.openHabit,
            )
        }
        quickActionMenu?.let { task ->
            TaskQuickActionMenu(
                task = task,
                isSelected = task.id in listSelection,
                onDismiss = { quickActionMenu = null },
                onActionSelected = { which ->
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
            )
        }
        deleteConfirmation?.let { (title, message) ->
            TaskDeleteConfirmationDialog(
                title = title,
                message = message,
                onDismiss = {
                    pendingDeleteConfirmationIds.clear()
                    deleteConfirmation = null
                },
                onConfirm = {
                    val toDelete = pendingDeleteConfirmationIds.toList()
                    pendingDeleteConfirmationIds.clear()
                    executeDeleteTasks(toDelete)
                    deleteConfirmation = null
                }
            )
        }
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
        composePalette = palette
        view?.let(::applyWindowTheme)
        calendarViewController?.updatePalette(palette)
        if (calendarMode && taskListContent != null) calendarViewController?.render()
        if (drawerOpen) (activity as? MainNavigationHost)?.setBottomNavigationVisible(false, false)
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
        pendingListAnchor = currentListAnchor() ?: pendingListAnchor
        taskListContent = null
        informationDialog = null
        quickActionMenu = null
        deleteConfirmation = null
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        activeSnackbar?.dismiss()
        activeSnackbar = null
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        drawerOpen = false
        topBarCoordinates = null
        navigationCoordinates = null
        quickAddAnchor = null
        calendarViewController = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (currentListAnchor() ?: pendingListAnchor)?.let { anchor ->
            outState.putString("taskListDataset", component.session.datasetId)
            outState.putString("taskListAnchorKey", anchor.key)
            outState.putInt("taskListAnchorIndex", anchor.index)
            outState.putInt("taskListAnchorOffset", anchor.offset)
        }
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
        outState.putString("quickTitle", quickTitle.ifEmpty { restoredTitle })
        outState.putString("quickDraftNotes", quickDraftNotes)
        outState.putStringArrayList("quickDraftSubtaskTitles", ArrayList(quickDraftSubtasks.map { it.title }))
        outState.putBooleanArray("quickDraftSubtaskCompleted", quickDraftSubtasks.map { it.isCompleted }.toBooleanArray())
        outState.putBoolean("calendarMode", calendarMode)
        outState.putBoolean("matrixMode", matrixMode)
        outState.putString("calendarScale", calendarScale.name)
        outState.putString("calendarViewMode", calendarViewMode.name)
        calendarViewController?.let {
            outState.putBoolean("calendarShowHabits", it.showHabits)
        } ?: calendarShowHabits?.let {
            outState.putBoolean("calendarShowHabits", it)
        }
        quickImportant?.let { outState.putBoolean("quickImportant", it) }
        quickUrgent?.let { outState.putBoolean("quickUrgent", it) }
        outState.putInt("selectedDay", selectedDay.daysSince2000)
        outState.putLong("calendarMonth", calendarMonth.timeInMillis)
        outState.putString("localTreeRoot", localTreeRoot?.value)
        outState.putStringArrayList("collapsedContainers", ArrayList(collapsedContainers))
        outState.putBoolean("smartViewsExpanded", smartViewsExpanded)
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

    private fun applyWindowTheme(view: View) {
        androidx.core.view.WindowInsetsControllerCompat(requireActivity().window, view).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    private fun LayoutCoordinates?.popupBounds(): Rect? {
        if (this == null || !isAttached) return null
        val offset = positionOnScreen()
        if (!offset.x.isFinite() || !offset.y.isFinite()) return null
        val left = offset.x.roundToInt()
        val top = offset.y.roundToInt()
        return Rect(left, top, left + size.width, top + size.height)
    }

    private fun setupSelectionBarResultListeners() {
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

    private fun showOverflowMenu() {
        val anchor = view ?: return
        val bounds = topBarCoordinates.popupBounds() ?: return
        val entries = mutableListOf<CompactPopupMenu.Entry>()
        if (calendarMode) {
            val prefs = TaskCalendarPreferences(requireContext())
            val isShown = calendarViewController?.showHabits ?: (calendarShowHabits ?: prefs.showHabits)
            val habitsTitle = getString(R.string.task_calendar_menu_show_habits)
            entries += CompactPopupMenu.Entry.Item(
                id = 10,
                title = habitsTitle,
                iconRes = if (isShown) R.drawable.ic_tabler_check else null,
                selected = isShown,
            )
            entries += CompactPopupMenu.Entry.Divider
            entries += CompactPopupMenu.Entry.Item(
                id = 3,
                title = getString(R.string.settings),
            )
        } else {
            entries += CompactPopupMenu.Entry.Item(
                id = 1,
                title = TaskSmartView.ALL.label(requireContext()),
                iconRes = if (selectedContainerFilter == null && activeSmartView == TaskSmartView.ALL) R.drawable.ic_tabler_check else null,
                selected = selectedContainerFilter == null && activeSmartView == TaskSmartView.ALL,
            )
            entries += CompactPopupMenu.Entry.Item(
                id = 2,
                title = TaskSmartView.COMPLETED.label(requireContext()),
                iconRes = if (selectedContainerFilter == null && activeSmartView == TaskSmartView.COMPLETED) R.drawable.ic_tabler_check else null,
                selected = selectedContainerFilter == null && activeSmartView == TaskSmartView.COMPLETED,
            )
            if (selectedContainerFilter != null) {
                entries += CompactPopupMenu.Entry.Item(
                    id = 4,
                    title = getString(R.string.container_statistics_title),
                )
            }
            entries += CompactPopupMenu.Entry.Divider
            entries += CompactPopupMenu.Entry.Item(
                id = 3,
                title = getString(R.string.settings),
            )
        }
        CompactPopupMenu.show(
            anchor = anchor,
            anchorRectOnScreen = bounds,
            entries = entries,
            minWidthDp = 180,
            maxWidthDp = 240,
        ) { itemId ->
            when (itemId) {
                10 -> {
                    val prefs = TaskCalendarPreferences(requireContext())
                    val current = calendarViewController?.showHabits ?: (calendarShowHabits ?: prefs.showHabits)
                    val next = !current
                    prefs.showHabits = next
                    calendarShowHabits = next
                    calendarViewController?.showHabits = next
                    calendarViewController?.render()
                }
                1 -> {
                    clearSelection()
                    selectedContainerFilter = null
                    activeSmartView = TaskSmartView.ALL
                    syncWorkspaceScope()
                    loadTasks()
                }
                2 -> {
                    clearSelection()
                    selectedContainerFilter = null
                    activeSmartView = TaskSmartView.COMPLETED
                    syncWorkspaceScope()
                    loadTasks()
                }
                3 -> {
                    clearSelection()
                    workspaceNavigator?.invoke("Settings")
                }
                4 -> {
                    selectedContainerFilter?.let { filter ->
                        org.isoron.uhabits.activities.containers.statistics.ContainerStatisticsActivity.start(
                            requireContext(),
                            org.isoron.uhabits.core.containers.ContainerId(filter.value)
                        )
                    }
                }
            }
        }
    }

    private fun showRepresentationDialog() {
        clearSelection()
        val labels = listOf(
            getString(R.string.task_view_list),
            getString(R.string.task_view_calendar),
            getString(R.string.task_view_matrix),
        )
        CustomDialogs.showSingleChoiceDialog(
            context = requireContext(),
            title = getString(R.string.task_view_title),
            options = labels,
            selectedIndex = if (matrixMode) 2 else if (calendarMode) 1 else 0,
        ) { choice ->
            calendarMode = choice == 1
            matrixMode = choice == 2
            syncWorkspaceScope()
            loadTasks()
        }
    }

    private fun syncWorkspaceScope() {
        workspaceScope = WorkspaceScope(activeSmartView, selectedContainerFilter, selectedTagFilter, calendarMode, matrixMode)
    }

    private fun currentScreenTitle(scope: WorkspaceScope): String {
        return if (scope.calendar) {
            getString(R.string.task_view_calendar)
        } else {
            scope.container?.let { workspaceSnapshot?.availableContainers.orEmpty().find { item -> item.key == it.value }?.name }
                ?: if (scope.matrix) {
                    getString(R.string.task_view_matrix)
                } else scope.smartView.label(requireContext())
        }
    }

    @Composable
    private fun WorkspaceTopBar(colors: UHabitComposeColors) {
        val scope = workspaceScope
        val title = currentScreenTitle(scope)
        val selectedTasks = if (listSelection.isEmpty()) emptyList() else selectedTasksFromSnapshot()
        val isAllSelectedCompleted = selectedTasks.isNotEmpty() && selectedTasks.all { it.isCompleted }
        TasksTopBarCompose(
            title = title,
            selectedCount = listSelection.size,
            isAllSelectedCompleted = isAllSelectedCompleted,
            isCalendarMode = scope.calendar,
            isWide = wide,
            colors = colors,
            modifier = Modifier.onGloballyPositioned { topBarCoordinates = it },
            onOpenNavigation = { openNavigation() },
            onTitleLongClick = if (scope.container != null) {
                {
                    val id = scope.container
                    val path = workspaceSnapshot?.availableContainers.orEmpty().find { it.key == id.value }?.path
                    informationDialog = InformationDialog(message = path ?: title)
                }
            } else null,
            onSwitchRepresentation = { showRepresentationDialog() },
            onOpenOverflowMenu = { showOverflowMenu() },
            onCloseSelection = { clearSelection() },
            onSelectionDue = {
                val ids = selectedTaskIds.toList()
                if (ids.isNotEmpty()) openActionDuePicker(ids)
            },
            onSelectionContainer = {
                val ids = selectedTaskIds.toList()
                if (ids.isNotEmpty()) openActionContainerPicker(ids)
            },
            onSelectionComplete = {
                val ids = selectedTaskIds.toList()
                if (ids.isNotEmpty()) executeCompleteAction(ids)
            },
            onSelectionDelete = {
                val ids = selectedTaskIds.toList()
                if (ids.isNotEmpty()) confirmDeleteTasks(ids)
            },
            onSelectionMore = {
                val single = selectedTasksFromSnapshot().singleOrNull()
                if (single != null) showTaskQuickMenu(single)
            }
        )
    }

    @Composable
    private fun WorkspaceListInfo(colors: UHabitComposeColors) {
        val scope = workspaceScope
        val overdueCount = currentOverdueCount
        val activeCount = currentActiveCount
        val totalPending = activeCount + overdueCount
        val countText = resources.getQuantityString(R.plurals.task_count, totalPending, totalPending)
        val subtitleText = when {
            !scope.matrix && scope.smartView == TaskSmartView.COMPLETED -> ""
            scope.smartView == TaskSmartView.TODAY && overdueCount > 0 && activeCount > 0 ->
                getString(R.string.task_overdue_with_count, countText, overdueCount)
            scope.smartView == TaskSmartView.TODAY && overdueCount > 0 && activeCount == 0 ->
                getString(R.string.task_overdue_count, overdueCount)
            else ->
                countText
        }

        val dateStr = if (!scope.matrix && scope.smartView == TaskSmartView.TODAY) {
            val locale = TaskDueDisplayFormatter.selectedLocale(requireContext())
            val rawDate = SimpleDateFormat("EEE, d MMMM", locale).format(Date(System.currentTimeMillis()))
            rawDate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        } else null

        val tagLabel = if (scope.tags.isNotEmpty()) {
            scope.tags.joinToString(", ") { "#$it" }
        } else null

        TasksListInfoCompose(
            subtitle = subtitleText,
            dateText = dateStr,
            tagFilterLabel = tagLabel,
            colors = colors,
            onTagFilterClick = if (scope.tags.isNotEmpty()) {
                {
                    selectedTagFilter = emptySet()
                    loadTasks()
                }
            } else null
        )
    }

    private fun toggleTaskSelection(taskId: TaskId) {
        activeSnackbar?.dismiss()
        if (!selectedTaskIds.add(taskId)) {
            selectedTaskIds.remove(taskId)
        }
        if (selectedTaskIds.isNotEmpty() && quickExpanded) {
            collapseQuickComposerQuietly()
        }
        updateSelectionBarUi()
    }

    internal fun clearSelection() {
        if (selectedTaskIds.isEmpty()) return
        selectedTaskIds.clear()
        updateSelectionBarUi()
    }

    private fun selectedTasksFromSnapshot(): List<UserTask> {
        val rootsById = workspaceSnapshot?.roots.orEmpty().associateBy { it.id }
        return selectedTaskIds.mapNotNull { rootsById[it] }
    }

    private fun updateSelectionBarUi() {
        listSelection = selectedTaskIds.toSet()
    }

    private fun collapseQuickComposerQuietly() {
        if (!quickExpanded) return
        collapseComposer()
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
            Toast.makeText(requireContext(), getString(R.string.task_action_due_update_failed), Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        clearSelection()
        loadTasks()
        if (entries.isNotEmpty()) {
            val msg = if (entries.size == 1) getString(R.string.task_action_due_updated_single) else getString(R.string.task_action_due_updated_multi, entries.size)
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
            Toast.makeText(requireContext(), getString(R.string.task_action_move_failed), Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        clearSelection()
        loadTasks()
        if (entries.isNotEmpty()) {
            val msg = if (entries.size == 1) getString(R.string.task_action_moved_single) else getString(R.string.task_action_moved_multi, entries.size)
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
            Toast.makeText(requireContext(), getString(R.string.task_action_status_failed), Toast.LENGTH_LONG).show()
            loadTasks()
            return
        }
        val (targetCompleted, entries) = result
        clearSelection()
        loadTasks()
        if (entries.isNotEmpty()) {
            val msg = when {
                entries.size == 1 && targetCompleted -> getString(R.string.task_action_completed_single)
                entries.size == 1 && !targetCompleted -> getString(R.string.task_action_reopened_single)
                targetCompleted -> getString(R.string.task_action_completed_multi, entries.size)
                else -> getString(R.string.task_action_reopened_multi, entries.size)
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

        val title = if (uniqueIds.size == 1) {
            getString(R.string.task_detail_delete_confirm_title)
        } else {
            getString(R.string.task_delete_multi_title, uniqueIds.size)
        }
        val message = if (uniqueIds.size == 1) {
            getString(R.string.task_delete_single_irreversible)
        } else {
            getString(R.string.task_delete_multi_irreversible)
        }
        deleteConfirmation = title to message
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
            Toast.makeText(requireContext(), getString(R.string.task_action_delete_failed), Toast.LENGTH_LONG).show()
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
        val snackbar = Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
        quickAddAnchor?.takeIf { it.isAttachedToWindow }?.let { snackbar.anchorView = it }
        val bgColor = if (palette.isDark) palette.surfaceVariant else Color.parseColor("#252B33")
        snackbar.setBackgroundTint(bgColor)
        snackbar.setTextColor(Color.WHITE)
        snackbar.setActionTextColor(if (palette.isDark) palette.accent else Color.parseColor("#7CC4FA"))
        snackbar.setAction(getString(R.string.task_action_undo)) {
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
            Toast.makeText(requireContext(), getString(R.string.task_action_undo_failed), Toast.LENGTH_LONG).show()
            loadTasks()
            return 0
        }
        lastUndoEntries = emptyList()
        loadTasks()
        return restoredCount
    }

    @Composable
    private fun WorkspaceList(currentPalette: SettingsThemePalette) {
        val content = taskListContent ?: return
        val danger = colorResource(
            if (currentPalette.isDark) R.color.color_picker_danger_dark else R.color.color_picker_danger_light,
        )
        val warning = if (currentPalette.isDark) Color.rgb(255, 183, 77) else Color.rgb(180, 83, 9)
        val colors = UHabitComposeColors.fromPalette(
            currentPalette,
            danger.toArgb(),
            warning,
        )
        UHabitComposeTheme(colors) {
            TasksListCompose(
                content = content,
                state = taskListState,
                selectedIds = listSelection,
                onDisclosure = { disclosure ->
                    when (disclosure) {
                        TaskListDisclosure.OVERDUE -> overdueExpanded = !overdueExpanded
                        TaskListDisclosure.COMPLETED -> completedExpanded = !completedExpanded
                    }
                    workspaceSnapshot?.let(::renderTasks)
                },
                onClick = { task ->
                    if (selectedTaskIds.isNotEmpty()) {
                        toggleTaskSelection(task.id)
                    } else {
                        val sheet = TaskDetailBottomSheet.newInstance(task.id)
                        sheet.onTaskUpdatedListener = { loadTasks() }
                        sheet.show(childFragmentManager, "task_detail")
                    }
                },
                onComplete = { task ->
                    if (selectedTaskIds.isNotEmpty()) toggleTaskSelection(task.id)
                    else executeCompleteAction(listOf(task.id))
                },
                onSelect = { toggleTaskSelection(it.id) },
                onQuickMenu = ::showTaskQuickMenu,
                onChangeDue = { openActionDuePicker(listOf(it.id)) },
                onMoveContainer = { openActionContainerPicker(listOf(it.id)) },
                onSwipe = ::onTaskSwiped,
            )
        }
    }

    private fun onTaskSwiped(task: UserTask, action: TaskSwipeAction) {
        if (!workspaceActive || calendarMode || selectedTaskIds.isNotEmpty() ||
            component !== HabitsApplication.component || childFragmentManager.isStateSaved
        ) return
        if (taskListContent?.items?.none { it is TaskListItem.Row && it.task.id == task.id } != false) return
        when (action) {
            TaskSwipeAction.CHANGE_DUE -> openActionDuePicker(listOf(task.id))
            TaskSwipeAction.MOVE_CONTAINER -> openActionContainerPicker(listOf(task.id))
        }
    }

    private fun currentListAnchor(): ListAnchor? {
        val index = taskListState.firstVisibleItemIndex
        val item = taskListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        return ListAnchor(item.key as String, index, taskListState.firstVisibleItemScrollOffset)
    }

    private fun expandComposer() {
        clearSelection()
        quickExpanded = true
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(true)

        if (quickTitle.isBlank() && !quickDraftRestored) {
            quickTaskDue = if (activeSmartView == TaskSmartView.TODAY) {
                val t = getToday()
                TaskDue.Day(TaskDueDate.from(t))
            } else TaskDue.None
            quickContainer = if (activeSmartView == TaskSmartView.INBOX) null else selectedContainerFilter
        }
    }

    private fun collapseComposer() {
        quickExpanded = false
        (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(false)
        view?.windowToken?.let { token ->
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun openDetailFromQuickAdd() {
        val draft = TaskDraft(
            title = quickTitle,
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

    private fun openDuePicker() {
        if (childFragmentManager.findFragmentByTag("quickDue") == null) {
            TaskDuePickerBottomSheet.newInstance("quickDue", quickTaskDue)
                .show(childFragmentManager, "quickDue")
        }
    }

    private fun openContainerPicker() {
        if (childFragmentManager.findFragmentByTag("quickContainer") == null) {
            TaskContainerPickerBottomSheet.newInstance(quickContainer, "quickContainer", component.session.datasetId)
                .show(childFragmentManager, "quickContainer")
        }
    }

    private fun openTagPicker() {
        if (childFragmentManager.findFragmentByTag("quickTags") == null) {
            TaskTagPickerBottomSheet.newInstance(quickTagIds, "quickTags", component.session.datasetId)
                .show(childFragmentManager, "quickTags")
        }
    }

    private fun submitQuickAdd() {
        val title = quickTitle.trim()
        if (title.isBlank()) return

        val notes = quickDraftNotes
        val subs = quickDraftSubtasks.toList()
        val due = quickTaskDue
        val cid = quickContainer
        val tagsToAssign = quickTagIds
        val important = quickImportant
        val urgent = quickUrgent

        val created = try {
            component.db.runInTransaction {
                val mainTask = taskService.createTask(
                    title = title,
                    description = notes,
                    containerId = cid,
                    due = due,
                    important = important,
                    urgent = urgent,
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
            }
        } catch (e: Exception) {
            android.util.Log.e("TasksFragment", "Task creation failed", e)
            Toast.makeText(requireContext(), getString(R.string.task_detail_create_error), Toast.LENGTH_LONG).show()
            return
        }

        quickTitle = ""
        quickDraftNotes = ""
        quickDraftSubtasks.clear()
        quickTagIds = emptySet()
        quickImportant = null
        quickUrgent = null
        quickDraftRestored = false
        collapseComposer()

        // Reveal the new task even when it no longer matches the current view/filter.
        if (!activeSmartView.matches(created, getToday(), System.currentTimeMillis())) {
            activeSmartView = TaskSmartView.ALL
        }
        if (cid != selectedContainerFilter) {
            selectedContainerFilter = cid
        }
        if (selectedTagFilter.isNotEmpty() && !tagsToAssign.containsAll(selectedTagFilter)) {
            selectedTagFilter = emptySet()
        }
        syncWorkspaceScope()
        loadTasks()
    }

    private fun setupQuickAddResultListeners() {
        childFragmentManager.setFragmentResultListener("quickDue", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            quickTaskDue = TaskDuePickerBottomSheet.readResult(result)
            quickDraftRestored = true
        }
        childFragmentManager.setFragmentResultListener("quickContainer", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            quickContainer = result.getString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID)?.let(::ContainerId)
            quickDraftRestored = true
        }
        childFragmentManager.setFragmentResultListener("quickTags", viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            if (result.getBoolean(TaskTagPickerBottomSheet.RESULT_APPLIED, false)) {
                quickTagIds = result.getStringArrayList(TaskTagPickerBottomSheet.RESULT_TAG_IDS).orEmpty().toSet()
                quickDraftRestored = true
            }
            loadTasks()
        }
        childFragmentManager.setFragmentResultListener(TaskDetailBottomSheet.REQUEST_KEY_CREATE, viewLifecycleOwner) { _, result ->
            if (component !== HabitsApplication.component) return@setFragmentResultListener
            val action = result.getString(TaskDetailBottomSheet.RESULT_ACTION)
            if (action == TaskDetailBottomSheet.ACTION_CREATED) {
                quickTitle = ""
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
                quickTitle = returnedDraft.title
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
            }
        }

        if (quickExpanded) {
            (activity as? org.isoron.uhabits.activities.main.MainActivity)?.setTaskQuickAddActive(true)
        }

    }

    @Composable
    private fun WorkspaceQuickAdd(colors: UHabitComposeColors) {
        val context = requireContext()
        val containerItem = workspaceSnapshot?.availableContainers.orEmpty().find { it.key == quickContainer?.value }
        val availableTags = workspaceSnapshot?.availableTags.orEmpty()
        val selectedTagNames = availableTags.filter { it.id in quickTagIds }.map { it.name }
        val tagLabel = if (selectedTagNames.isNotEmpty()) {
            val firstTag = "#${selectedTagNames.first()}"
            if (selectedTagNames.size > 1) "$firstTag +${selectedTagNames.size - 1}" else firstTag
        } else ""
        val dueFormatted = TaskDueDisplayFormatter.format(
            quickTaskDue,
            TaskDueDisplayFormatter.selectedLocale(context)
        )

        QuickAddCompose(
            colors = colors,
            title = quickTitle,
            onTitleChange = { quickTitle = it },
            expanded = quickExpanded,
            onExpand = { expandComposer() },
            onCollapse = { collapseComposer() },
            onOpenDetail = { openDetailFromQuickAdd() },
            onSubmit = { submitQuickAdd() },
            due = quickTaskDue,
            dueFormatted = dueFormatted,
            container = quickContainer,
            containerName = containerItem?.name,
            containerColor = containerItem?.color?.let { TaskThemeUtils.resolvePaletteColor(context, it, colors.accent) },
            tags = quickTagIds,
            tagLabel = tagLabel,
            onOpenDuePicker = { openDuePicker() },
            onOpenContainerPicker = { openContainerPicker() },
            onOpenTagPicker = { openTagPicker() }
        )
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
        applyVisibilitySelection(snapshot)
        val availableTagIds = snapshot.availableTags.mapTo(mutableSetOf()) { it.id }
        selectedTagFilter = emptySet()
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

        syncWorkspaceScope()
        navigationHiddenSections = hiddenSections()
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

        val items = sections.toListItems(
            overdueTitle = getString(R.string.task_smart_overdue),
            activeTitle = getString(if (activeSmartView == TaskSmartView.TODAY) R.string.task_smart_today else R.string.tasks_title),
            completedTitle = getString(R.string.task_smart_completed),
            overdueExpanded = overdueExpanded,
            completedExpanded = completedExpanded,
            hideContainer = selectedContainerFilter != null || activeSmartView == TaskSmartView.INBOX,
        )
        val renderedTaskIds = if (calendarMode) emptySet() else items.mapNotNullTo(hashSetOf()) {
            (it as? TaskListItem.Row)?.task?.id
        }
        selectedTaskIds.retainAll(renderedTaskIds)
        updateSelectionBarUi()
        taskListContent = TasksListContent(
            items = items,
            today = today,
            nowMillis = nowMillis,
            subtaskCounts = subtaskMap,
            containers = availableContainers.associateBy { ContainerId(it.key) },
            tags = taskTagMap,
        )
        if (!calendarMode && items.isNotEmpty()) {
            pendingListAnchor?.let { anchor ->
                val index = items.indexOfFirst { it.key == anchor.key }
                taskListState.requestScrollToItem(
                    index = if (index >= 0) index else anchor.index.coerceIn(items.indices),
                    scrollOffset = if (index >= 0) anchor.offset else 0,
                )
                pendingListAnchor = null
            }
        }

        currentOverdueCount = sections.overdue.size
        currentActiveCount = sections.active.size
        showEmptyState = if (matrixMode) {
            sections.groups.none { it.tasks.isNotEmpty() }
        } else {
            sections.active.isEmpty() && sections.overdue.isEmpty() && sections.completed.isEmpty()
        }
    }

    @Composable
    private fun WorkspaceEmptyState(colors: UHabitComposeColors) {
        val scope = workspaceScope
        val (title, message) = when {
            scope.tags.isNotEmpty() ->
                getString(R.string.task_empty_tags_title) to getString(R.string.task_empty_tags_message)
            scope.container != null ->
                getString(R.string.task_empty_container_title) to getString(R.string.task_empty_container_message)
            scope.matrix ->
                getString(R.string.task_empty_matrix_title) to getString(R.string.task_empty_matrix_message)
            scope.smartView == TaskSmartView.TODAY ->
                getString(R.string.task_empty_today_title) to getString(R.string.task_empty_today_message)
            scope.smartView == TaskSmartView.OVERDUE ->
                getString(R.string.task_empty_overdue_title) to getString(R.string.task_empty_overdue_message)
            scope.smartView == TaskSmartView.INBOX ->
                getString(R.string.task_empty_inbox_title) to getString(R.string.task_empty_inbox_message)
            scope.smartView == TaskSmartView.UPCOMING ->
                getString(R.string.task_empty_upcoming_title) to getString(R.string.task_empty_upcoming_message)
            scope.smartView == TaskSmartView.ALL ->
                getString(R.string.task_empty_all_title) to getString(R.string.task_empty_all_message)
            scope.smartView == TaskSmartView.COMPLETED ->
                getString(R.string.task_empty_completed_title) to getString(R.string.task_empty_completed_message)
            else ->
                getString(R.string.task_empty_all_title) to getString(R.string.task_empty_all_message)
        }
        TasksEmptyStateCompose(title = title, message = message, colors = colors)
    }

    fun openNavigation() {
        if (wide || drawerOpen) return
        collapseQuickComposerQuietly()
        (activity as? MainNavigationHost)?.setBottomNavigationVisible(false, false)
        drawerOpen = true
    }

    fun closeNavigation() {
        if (!drawerOpen) return
        drawerOpen = false
        (activity as? MainNavigationHost)?.setBottomNavigationVisible(true, false)
    }

    fun onBackPressed(): Boolean {
        if (drawerOpen) {
            if (localTreeRoot != null) {
                localTreeRoot = workspaceSnapshot?.containers?.get(localTreeRoot!!)?.parentId
            } else closeNavigation()
            return true
        }
        if (selectedTaskIds.isNotEmpty()) {
            clearSelection()
            return true
        }
        if (quickExpanded) {
            collapseComposer()
            return true
        }
        return false
    }

    @Composable
    private fun WorkspaceNavigation(colors: UHabitComposeColors) {
        val snapshot = workspaceSnapshot ?: return
        val scope = workspaceScope
        val context = requireContext()
        val taskVisibility = taskVisibility(snapshot, navigationHiddenSections)
        val containerNavItems = mutableListOf<ContainerNavItem>()

        fun addContainer(item: org.isoron.uhabits.core.containers.Container, depth: Int) {
            if (!taskVisibility.isVisible(item.id.value)) return
            val children = snapshot.children[item.id].orEmpty().filter { taskVisibility.isVisible(it.id.value) }
            val count = snapshot.pendingCounts[item.id] ?: 0
            val iconRef = org.isoron.uhabits.core.containers.IconRef.fromString(item.icon)
            val itemColor = item.color?.let { TaskThemeUtils.resolvePaletteColor(context, it, colors.accent) }
            val isSelected = (scope.container == item.id)
            val isCollapsed = (depth >= 2 || item.id.value in collapsedContainers)

            containerNavItems.add(
                ContainerNavItem(
                    id = item.id,
                    name = item.name,
                    depth = depth,
                    hasChildren = children.isNotEmpty(),
                    isCollapsed = isCollapsed,
                    count = count,
                    iconRef = iconRef,
                    color = itemColor,
                    isSelected = isSelected
                )
            )

            if (depth < 2 && item.id.value !in collapsedContainers) {
                children.forEach { addContainer(it, depth + 1) }
            }
        }

        val local = localTreeRoot?.let { snapshot.containers[it] }
        if (local != null) {
            addContainer(local, 0)
        } else {
            snapshot.children[null].orEmpty().forEach { addContainer(it, 0) }
        }

        val primaryViews = listOf(TaskSmartView.TODAY, TaskSmartView.INBOX)
        val visibleSmartViews = if (smartViewsExpanded) {
            TaskSmartView.entries
        } else {
            if (scope.smartView !in primaryViews && scope.container == null) {
                primaryViews + scope.smartView
            } else {
                primaryViews
            }
        }

        val smartCountsMap = TaskSmartView.entries.associateWith { smart ->
            snapshot.roots.count { smart.matches(it, getToday(), System.currentTimeMillis()) }
        }

        val localParentName = localTreeRoot?.let { id ->
            snapshot.containers[id]?.parentId?.let { snapshot.containers[it]?.name } ?: getString(R.string.containers_root_all)
        }

        TasksNavigationCompose(
            isWide = wide,
            activeSmartView = scope.smartView,
            smartViewsExpanded = smartViewsExpanded,
            visibleSmartViews = visibleSmartViews,
            smartViewCounts = smartCountsMap,
            containers = containerNavItems,
            selectedContainerFilter = scope.container,
            localParentName = localParentName,
            colors = colors,
            modifier = Modifier.onGloballyPositioned { navigationCoordinates = it },
            onSelectSmartView = { smart ->
                clearSelection()
                selectedContainerFilter = null
                activeSmartView = smart
                syncWorkspaceScope()
                closeNavigation()
                loadTasks()
            },
            onToggleSmartViewsExpanded = {
                smartViewsExpanded = !smartViewsExpanded
            },
            onSelectContainer = { id ->
                clearSelection()
                selectedContainerFilter = id
                activeSmartView = TaskSmartView.ALL
                syncWorkspaceScope()
                closeNavigation()
                loadTasks()
            },
            onToggleContainerCollapse = { idStr ->
                collapsedContainers = if (idStr in collapsedContainers) collapsedContainers - idStr else collapsedContainers + idStr
            },
            onOpenContainerChildren = { id ->
                localTreeRoot = id
            },
            onNavigateToParent = {
                localTreeRoot = localTreeRoot?.let { snapshot.containers[it]?.parentId }
            },
            onContainerLongClick = { id ->
                snapshot.containers[id]?.let { showSectionContextMenu(it) }
            },
            onContainerMoreClick = { id ->
                snapshot.containers[id]?.let { showSectionContextMenu(it) }
            },
            onManageHiddenSections = {
                showSectionVisibilityDialog()
            },
            onCreateContainer = {
                showCreateSectionDialog(parentId = null)
            },
            onOpenSettings = {
                clearSelection()
                closeNavigation()
                workspaceNavigator?.invoke("Settings")
            },
            onWideTabSelected = { key ->
                clearSelection()
                workspaceNavigator?.invoke(key)
            }
        )
    }

    private fun createCalendarController() = TaskCalendarViewController(
        context = requireContext(),
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
        },
        onShowHabitsChanged = { shown ->
            calendarShowHabits = shown
        }
    )

    private fun renderCalendar(
        tasks: List<UserTask>,
        habits: List<Habit> = activeHabits,
        sessions: List<org.isoron.uhabits.core.focus.FocusSession> = historyFocusSessions
    ) {
        if (!calendarMode) return
        val controller = calendarViewController ?: return
        calendarShowHabits?.let { controller.showHabits = it }
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
        val habit = component.habitList.getById(plannedHabit.habitId)
        val details = buildString {
            if (plannedHabit.reminderHour != null && plannedHabit.reminderMinute != null) {
                val time = String.format(Locale.getDefault(), "%02d:%02d", plannedHabit.reminderHour, plannedHabit.reminderMinute)
                append(getString(R.string.task_planned_habit_reminder, time))
            } else {
                append(getString(R.string.task_planned_habit_regular))
            }
            append("\n\n")
            append(getString(R.string.task_planned_habit_note))
        }

        informationDialog = InformationDialog(
            title = plannedHabit.name,
            message = details,
            openHabit = {
                if (habit != null) {
                    val intent = Intent(requireContext(), ShowHabitActivity::class.java).apply {
                        data = Uri.parse(habit.uriString)
                    }
                    startActivity(intent)
                }
            },
        )
    }

    internal fun showTaskQuickMenu(task: UserTask) {
        quickActionMenu = task
    }

    companion object {
        internal const val REQUEST_KEY_ACTION_DUE = "taskActionDue"
        internal const val REQUEST_KEY_ACTION_CONTAINER = "taskActionContainer"
    }

    private fun showCreateSectionDialog(parentId: ContainerId?) {
        val dialog = CreateContainerDialog().apply {
            initialParentId = parentId
            initialParentName = parentId?.let { workspaceSnapshot?.containers?.get(it)?.name }
            containerQueries = orgFacade.containerQueries
            onSaveWithIcon = { name, parent, color, icon ->
                System.err.println("DEBUG_SECTIONS: onSaveWithIcon invoked with name='$name', parent=$parent, color=$color, icon=$icon")
                val res = orgFacade.createContainer(
                    name = name,
                    parentId = parent,
                    color = color,
                    icon = org.isoron.uhabits.core.containers.IconRef.toStorageString(icon)
                )
                System.err.println("DEBUG_SECTIONS: createContainer returned res=$res")
                if (res.isSuccess) {
                    if (parent != null) {
                        collapsedContainers -= parent.value
                    } else {
                        res.getOrNull()?.let { selectedContainerFilter = it.id }
                    }
                    loadTasks()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.task_container_create_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show(parentFragmentManager, "create_section_dialog")
    }

    private fun showEditSectionDialog(container: Container) {
        val dialog = CreateContainerDialog().apply {
            containerToEdit = container
            containerQueries = orgFacade.containerQueries
            onSaveWithIcon = { name, parent, color, icon ->
                val editRes = orgFacade.editContainer(
                    id = container.id,
                    name = name,
                    color = color,
                    icon = org.isoron.uhabits.core.containers.IconRef.toStorageString(icon)
                )
                if (parent != container.parentId) {
                    orgFacade.moveContainer(container.id, parent)
                }
                if (editRes.isSuccess) {
                    loadTasks()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.task_container_update_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show(parentFragmentManager, "edit_section_dialog")
    }

    private fun showMoveSectionDialog(container: Container) {
        val dialog = DrillDownMoveDialog().apply {
            itemName = container.name
            movingContainerId = container.id
            isParentPickerOnly = true
            containerQueries = orgFacade.containerQueries
            onTargetSelected = { targetId ->
                val res = orgFacade.moveContainer(container.id, targetId)
                if (res.isSuccess) {
                    if (targetId != null) collapsedContainers -= targetId.value
                    loadTasks()
                } else {
                    Toast.makeText(requireContext(), sectionString(R.string.section_move_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show(parentFragmentManager, "move_section_dialog")
    }

    private fun sectionResources(): android.content.res.Resources {
        val context = requireContext()
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(TaskDueDisplayFormatter.selectedLocale(context))
        }
        return context.createConfigurationContext(config).resources
    }

    private fun sectionString(id: Int, vararg args: Any): String =
        sectionResources().getString(id, *args)

    private fun visibilityPrefs() = requireContext().getSharedPreferences(
        "task_section_visibility_" + component.session.datasetId, Context.MODE_PRIVATE
    )

    private fun hiddenSections(): Set<String> =
        visibilityPrefs().getStringSet("hidden", emptySet()).orEmpty().toSet()

    private fun taskVisibility(snapshot: TasksWorkspaceSnapshot, hidden: Set<String> = hiddenSections()) =
        org.isoron.uhabits.core.containers.TaskContainerVisibility(
            snapshot.containers.values.associate { it.id.value to it.parentId?.value }, hidden
        )

    private fun saveHiddenSections(hidden: Set<String>): Boolean =
        visibilityPrefs().edit().putStringSet("hidden", hidden.toSet()).commit()

    private fun applyVisibilitySelection(snapshot: TasksWorkspaceSnapshot) {
        val visibility = taskVisibility(snapshot)
        val current = selectedContainerFilter
        if (current != null && !visibility.isVisible(current.value)) {
            selectedContainerFilter = visibility.nearestVisibleAncestor(current.value)?.let(::ContainerId)
            activeSmartView = TaskSmartView.ALL
        }
        if (localTreeRoot?.let { !visibility.isVisible(it.value) } == true) localTreeRoot = null
        if (quickContainer?.let { !visibility.isVisible(it.value) } == true) {
            quickContainer = visibility.nearestVisibleAncestor(quickContainer!!.value)?.let(::ContainerId)
        }
    }

    private fun showHideSectionDialog(container: Container) {
        val snapshot = workspaceSnapshot ?: return
        if (!saveHiddenSections(hiddenSections() + container.id.value)) {
            Toast.makeText(requireContext(), sectionString(R.string.section_hide_failed), Toast.LENGTH_SHORT).show()
            return
        }
        applyVisibilitySelection(snapshot)
        closeNavigation()
        loadTasks()
        val datasetId = component.session.datasetId
        activeSnackbar?.dismiss()
        activeSnackbar = Snackbar.make(requireView(), sectionString(R.string.section_hidden), Snackbar.LENGTH_LONG)
            .setAction(sectionString(R.string.section_visibility_undo)) {
                if (component.session.datasetId == datasetId &&
                    saveHiddenSections(hiddenSections() - container.id.value)) {
                    workspaceSnapshot?.let(::applyVisibilitySelection)
                    loadTasks()
                }
            }.also { it.show() }
    }

    private fun showSectionVisibilityDialog() {
        val snapshot = workspaceSnapshot ?: return
        val datasetId = component.session.datasetId
        val themeRes = when {
            palette.isPureBlack -> R.style.ColorPickerFullScreenTheme_Amoled
            palette.isDark -> R.style.ColorPickerFullScreenTheme_Dark
            else -> R.style.ColorPickerFullScreenTheme
        }
        val dialog = androidx.activity.ComponentDialog(requireContext(), themeRes).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val pageRoot = FrameLayout(requireContext()).apply { setBackgroundColor(palette.background) }
        pageRoot.setViewTreeViewModelStoreOwner(this)

        fun path(item: Container): List<Container> {
            val result = mutableListOf<Container>()
            val visited = mutableSetOf<ContainerId>()
            var node: Container? = item
            while (node != null && visited.add(node.id)) {
                result.add(node)
                node = node.parentId?.let { snapshot.containers[it] }
            }
            return result.reversed()
        }
        val ordered = mutableListOf<Container>()
        fun collect(parent: ContainerId?) {
            snapshot.children[parent].orEmpty().forEach { ordered.add(it); collect(it.id) }
        }
        collect(null)
        val composeRevision = androidx.compose.runtime.mutableIntStateOf(0)
        val composeView = androidx.compose.ui.platform.ComposeView(requireContext())
        pageRoot.addView(composeView, FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        composeView.setContent {
            composeRevision.intValue
            val current = taskVisibility(snapshot)
            val composeRows = ordered.filter { !current.isVisible(it.id.value) }.map { item ->
                HiddenSectionRow(item.id.value, item.name, path(item).dropLast(1).joinToString(" / ") { it.name },
                    current.hiddenAncestors(item.id.value).any { it != item.id.value }, item.icon,
                    item.color?.let { TaskThemeUtils.resolvePaletteColor(requireContext(), it, palette.accent) } ?: palette.onSurfaceVariant)
            }
            HiddenSectionsCompose(composeRows, org.isoron.uhabits.compose.UHabitComposeColors(
                palette.background, palette.onSurface, palette.onSurfaceVariant, palette.accent),
                sectionString(R.string.section_hidden_sections), sectionString(R.string.section_hidden_search),
                sectionString(R.string.section_back), sectionString(R.string.clear), sectionString(R.string.section_hidden_empty),
                sectionString(R.string.section_hidden_no_results), sectionString(R.string.section_show), sectionString(R.string.section_show_path),
                onBack = { dialog.dismiss() }, onShow = { id ->
                    if (component !== HabitsApplication.component || component.session.datasetId != datasetId) {
                        dialog.dismiss()
                    } else {
                        val before = hiddenSections()
                        val after = taskVisibility(snapshot, before).showPath(id)
                        if (saveHiddenSections(after)) {
                            applyVisibilitySelection(snapshot)
                            composeRevision.intValue++; loadTasks()
                            activeSnackbar?.dismiss()
                            activeSnackbar = Snackbar.make(pageRoot, sectionString(R.string.section_shown), Snackbar.LENGTH_LONG)
                                .setAction(sectionString(R.string.section_visibility_undo)) {
                                    if (component === HabitsApplication.component && component.session.datasetId == datasetId && saveHiddenSections(before)) {
                                        applyVisibilitySelection(snapshot); composeRevision.intValue++; loadTasks()
                                    }
                                }.also { it.show() }
                        } else Toast.makeText(requireContext(), sectionString(R.string.section_hide_failed), Toast.LENGTH_SHORT).show()
                    }
                })
        }
        dialog.setContentView(pageRoot)
        dialog.setOnDismissListener {
            composeView.disposeComposition()
            if (isAdded && component === HabitsApplication.component) {
                navigationHiddenSections = hiddenSections()
                loadTasks()
            }
        }
        dialog.window?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false) }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.let { window ->
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !palette.isDark
            controller.isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    private fun showDeleteSectionDialog(container: Container) {
        val snapshot = workspaceSnapshot
        val taskCount = snapshot?.roots.orEmpty().count { it.containerId == container.id }
        val habitCount = orgFacade.getHabitUuidsForContainer(container.id.value, subtree = false).size
        val childCount = snapshot?.children?.get(container.id).orEmpty().size

        if (taskCount > 0 || habitCount > 0 || childCount > 0) {
            val parts = mutableListOf<String>()
            if (taskCount > 0) parts.add(sectionResources().getQuantityString(R.plurals.section_tasks_count, taskCount, taskCount))
            if (habitCount > 0) parts.add(sectionResources().getQuantityString(R.plurals.section_habits_count, habitCount, habitCount))
            if (childCount > 0) parts.add(sectionResources().getQuantityString(R.plurals.section_children_count, childCount, childCount))

            org.isoron.uhabits.activities.common.dialogs.CustomDialogs.showConfirmDialog(
                context = requireContext(),
                title = sectionString(R.string.section_not_empty),
                message = sectionString(R.string.section_not_empty_message, container.name, parts.joinToString(", ")),
                isDestructive = false,
                positiveText = sectionString(R.string.section_hide_action),
                negativeText = sectionString(R.string.containers_cancel),
                iconRes = R.drawable.ic_tabler_alert_triangle,
                iconTint = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.amber_500)
            ) { showHideSectionDialog(container) }
            return
        }

        org.isoron.uhabits.activities.common.dialogs.CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = sectionString(R.string.section_delete_title),
            message = sectionString(R.string.section_delete_message, container.name),
            isDestructive = true,
            positiveText = sectionString(R.string.delete),
            negativeText = sectionString(R.string.containers_cancel),
            iconRes = R.drawable.ic_tabler_trash,
            iconTint = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.red_600)
        ) {
            val service = orgFacade.organizationService
            val res = service?.deleteEmpty(container.id, container.revision)
            if (res != null && res.isSuccess) {
                if (selectedContainerFilter == container.id) {
                    selectedContainerFilter = null
                    activeSmartView = TaskSmartView.ALL
                }
                loadTasks()
                Toast.makeText(requireContext(), sectionString(R.string.section_deleted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), sectionString(R.string.section_delete_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSectionContextMenu(container: Container) {
        val anchor = view ?: return
        val bounds = navigationCoordinates.popupBounds() ?: return
        val entries = listOf(
            CompactPopupMenu.Entry.Item(
                id = 1,
                title = sectionString(R.string.containers_create_subsection),
                iconRes = R.drawable.ic_tabler_folder_plus
            ),
            CompactPopupMenu.Entry.Item(
                id = 2,
                title = sectionString(R.string.containers_edit_section),
                iconRes = R.drawable.ic_tabler_pencil
            ),
            CompactPopupMenu.Entry.Item(
                id = 3,
                title = sectionString(R.string.containers_move),
                iconRes = R.drawable.ic_tabler_folder_share
            ),
            CompactPopupMenu.Entry.Divider,
            CompactPopupMenu.Entry.Item(
                id = 4,
                title = sectionString(R.string.section_hide_from_tasks),
                iconRes = R.drawable.ic_tabler_eye_off
            ),
            CompactPopupMenu.Entry.Item(
                id = 5,
                title = sectionString(R.string.delete),
                iconRes = R.drawable.ic_tabler_trash,
                destructive = true
            )
        )
        CompactPopupMenu.show(anchor, entries, minWidthDp = 160, maxWidthDp = 220, anchorRectOnScreen = bounds) { actionId ->
            when (actionId) {
                1 -> showCreateSectionDialog(parentId = container.id)
                2 -> showEditSectionDialog(container)
                3 -> showMoveSectionDialog(container)
                4 -> showHideSectionDialog(container)
                5 -> showDeleteSectionDialog(container)
            }
        }
    }
}
