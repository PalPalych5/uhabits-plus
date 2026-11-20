/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.statistics.FocusSectionCategory
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.HabitSummary
import org.isoron.uhabits.core.statistics.targetsWithin
import org.isoron.uhabits.core.statistics.StatisticsDataState
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.StatisticsSectionScope
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reusable modal bottom sheet displaying drill-down lists for Tasks and Focus.
 * Features:
 * - Title and cohort context subtitle.
 * - Dynamic resolution of selection descriptors against active report.
 * - Bounded pagination (PAGE_SIZE = 30 rows).
 * - Clicking a Task launches TaskDetailBottomSheet.
 * - Clicking a FocusSession launches FocusSessionDetailBottomSheet.
 * - Safe layout under 320dp width and 200% font scale.
 */
class UnifiedStatisticsDrilldownBottomSheet : BottomSheetDialogFragment() {

    private var titleText: String = ""
    private var subtitleText: String = ""
    private var kind: String = KIND_TASKS
    private var pageIndex: Int = 0
    private var datasetId: String? = null
    private var querySignature: String? = null

    // Tasks descriptor parameters
    private var taskDescriptorType: String = ""
    private var taskDateDays: Int = -1
    private var taskFromDays: Int = -1
    private var taskToDays: Int = -1
    private var taskFilterName: String? = null

    // Focus descriptor parameters
    private var focusDescriptorType: String = ""
    private var focusDateDays: Int = -1
    private var focusFromDays: Int = -1
    private var focusToDays: Int = -1
    private var focusCategoryName: String? = null
    private var focusContainerIdVal: String? = null
    private var focusTargetTypeName: String? = null
    private var focusTargetIdVal: String? = null
    private var targetBranchCategory: String? = null
    private var targetBranchId: String? = null

    // Habits descriptor parameters
    private var habitDescriptorType: String = ""
    private var habitCadenceName: String? = null
    private var habitDateDays: Int = -1
    private var habitFromDays: Int = -1
    private var habitToDays: Int = -1
    private var habitUuidVal: String? = null
    private var habitNameVal: String? = null
    private var habitSearchQuery by mutableStateOf("")

    private var content by mutableStateOf(StatisticsDrilldownContent())
    internal val visibleIds: List<String> get() = content.rows.filter { !it.navigation }.map { it.id }
    internal val hasNextPage: Boolean get() = content.hasNextPage

    private val palette: SettingsThemePalette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Statistics_BottomSheetDialogTheme)
        val args = savedInstanceState ?: arguments
        if (args != null) {
            titleText = args.getString(ARG_TITLE, "")
            subtitleText = args.getString(ARG_SUBTITLE, "")
            kind = args.getString(ARG_KIND, KIND_TASKS)
            pageIndex = args.getInt(ARG_PAGE_SIZE_LIMIT, 0).coerceAtLeast(0)
            datasetId = args.getString("dataset")
            querySignature = args.getString("query")

            taskDescriptorType = args.getString(ARG_TASK_DESC_TYPE, "")
            taskDateDays = args.getInt(ARG_TASK_DATE, -1)
            taskFromDays = args.getInt(ARG_TASK_FROM, -1)
            taskToDays = args.getInt(ARG_TASK_TO, -1)
            taskFilterName = args.getString(ARG_TASK_FILTER)

            focusDescriptorType = args.getString(ARG_FOCUS_DESC_TYPE, "")
            focusDateDays = args.getInt(ARG_FOCUS_DATE, -1)
            focusFromDays = args.getInt(ARG_FOCUS_FROM, -1)
            focusToDays = args.getInt(ARG_FOCUS_TO, -1)
            focusCategoryName = args.getString(ARG_FOCUS_CATEGORY)
            focusContainerIdVal = args.getString(ARG_FOCUS_CONTAINER_ID)
            focusTargetTypeName = args.getString(ARG_FOCUS_TARGET_TYPE)
            focusTargetIdVal = args.getString(ARG_FOCUS_TARGET_ID)
            targetBranchCategory = args.getString("targetBranchCategory")
            targetBranchId = args.getString("targetBranchId")

            habitDescriptorType = args.getString(ARG_HABIT_DESC_TYPE, "")
            habitCadenceName = args.getString(ARG_HABIT_CADENCE)
            habitDateDays = args.getInt(ARG_HABIT_DATE, -1)
            habitFromDays = args.getInt(ARG_HABIT_FROM, -1)
            habitToDays = args.getInt(ARG_HABIT_TO, -1)
            habitUuidVal = args.getString(ARG_HABIT_UUID)
            habitNameVal = args.getString(ARG_HABIT_NAME)
            habitSearchQuery = args.getString(ARG_HABIT_SEARCH_QUERY, "")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_TITLE, titleText)
        outState.putString(ARG_SUBTITLE, subtitleText)
        outState.putString(ARG_KIND, kind)
        outState.putInt(ARG_PAGE_SIZE_LIMIT, pageIndex)
        outState.putString("dataset", datasetId)
        outState.putString("query", querySignature)

        outState.putString(ARG_TASK_DESC_TYPE, taskDescriptorType)
        outState.putInt(ARG_TASK_DATE, taskDateDays)
        outState.putInt(ARG_TASK_FROM, taskFromDays)
        outState.putInt(ARG_TASK_TO, taskToDays)
        outState.putString(ARG_TASK_FILTER, taskFilterName)

        outState.putString(ARG_FOCUS_DESC_TYPE, focusDescriptorType)
        outState.putInt(ARG_FOCUS_DATE, focusDateDays)
        outState.putInt(ARG_FOCUS_FROM, focusFromDays)
        outState.putInt(ARG_FOCUS_TO, focusToDays)
        outState.putString(ARG_FOCUS_CATEGORY, focusCategoryName)
        outState.putString(ARG_FOCUS_CONTAINER_ID, focusContainerIdVal)
        outState.putString(ARG_FOCUS_TARGET_TYPE, focusTargetTypeName)
        outState.putString(ARG_FOCUS_TARGET_ID, focusTargetIdVal)
        outState.putString("targetBranchCategory", targetBranchCategory)
        outState.putString("targetBranchId", targetBranchId)

        outState.putString(ARG_HABIT_DESC_TYPE, habitDescriptorType)
        outState.putString(ARG_HABIT_CADENCE, habitCadenceName)
        outState.putInt(ARG_HABIT_DATE, habitDateDays)
        outState.putInt(ARG_HABIT_FROM, habitFromDays)
        outState.putInt(ARG_HABIT_TO, habitToDays)
        outState.putString(ARG_HABIT_UUID, habitUuidVal)
        outState.putString(ARG_HABIT_NAME, habitNameVal)
        outState.putString(ARG_HABIT_SEARCH_QUERY, habitSearchQuery)
    }

    override fun onStart() {
        super.onStart()
        org.isoron.uhabits.activities.common.dialogs.CustomDialogs.styleBottomSheet(dialog, requireContext(), palette)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        content = StatisticsDrilldownContent(subtitle = subtitleText)
        populateItems()
        val colors = UHabitComposeColors.fromPalette(palette)
        val isHabits = kind == KIND_HABIT_CYCLES || kind == KIND_HABIT_LIST
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors) {
                    UnifiedStatisticsDrilldownCompose(
                        title = titleText,
                        tag = if (isHabits) "habitDrilldownSheet" else "unifiedDrilldownSheet",
                        content = content,
                        searchQuery = habitSearchQuery.takeIf { kind == KIND_HABIT_LIST },
                        onSearch = { query ->
                            if (query != habitSearchQuery) {
                                habitSearchQuery = query
                                pageIndex = 0
                                populateItems()
                            }
                        },
                        previousLabel = if (isRussian) "Предыдущая страница" else "Previous page",
                        nextLabel = if (isRussian) "Следующая страница" else "Next page",
                        onPrevious = {
                            if (content.hasPreviousPage) {
                                pageIndex--
                                populateItems()
                            }
                        },
                        onNext = {
                            if (content.hasNextPage) {
                                pageIndex++
                                populateItems()
                            }
                        },
                        onRetry = { (parentFragment as? UnifiedStatisticsFragment)?.refresh() },
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        content = StatisticsDrilldownContent()
        super.onDestroyView()
    }

    private fun populateItems() {
        val parent = parentFragment as? UnifiedStatisticsFragment
        val report = parent?.activeReport
        val state = parent?.currentState
        val ready = state is UnifiedReportUiState.Success && !state.isRefreshing
        if (report != null) {
            val signature = statisticsQuerySignature(report.context)
            if (datasetId == null) datasetId = report.context.datasetId
            if (querySignature == null) querySignature = signature
            if (datasetId != report.context.datasetId || querySignature != signature) {
                content = StatisticsDrilldownContent()
                dismissAllowingStateLoss()
                return
            }
        }
        if (parent == null || report == null || !ready) {
            content = content.copy(
                rows = emptyList(),
                hasPreviousPage = false,
                hasNextPage = false,
                showRetry = false,
                message = getString(if (state is UnifiedReportUiState.Failure) R.string.unified_statistics_domain_failed
                    else R.string.unified_statistics_state_refreshing),
            )
            return
        }

        val context = report.context
        val names = parent.nameSnapshot
        val scopeLabel = when (val scope = context.sectionScope) {
            StatisticsSectionScope.All -> getString(R.string.unified_statistics_scope_all)
            StatisticsSectionScope.Unassigned -> getString(R.string.unified_statistics_scope_unassigned)
            is StatisticsSectionScope.Container -> names.resolveContainerName(scope.id.value, isRussian) + " · " +
                if (scope.includeDescendants) getString(R.string.unified_statistics_scope_mode_subtree)
                else getString(R.string.unified_statistics_scope_mode_here)
        }
        val queryContext = "${formatStatisticsDate(context.from)} — ${formatStatisticsDate(context.to)}\n$scopeLabel"
        val domain = when (kind) {
            KIND_TASKS -> report.tasks?.dataState
            KIND_FOCUS -> report.focus?.dataState
            KIND_HABIT_CYCLES, KIND_HABIT_LIST -> report.habits?.dataState
            else -> null
        }
        if (domain == null || domain == StatisticsDataState.UNAVAILABLE) {
            content = StatisticsDrilldownContent(
                subtitle = content.subtitle, queryContext = queryContext,
                message = getString(R.string.unified_statistics_domain_failed), showRetry = true,
            )
            return
        }

        fun <T> showPage(items: List<T>, subtitle: String, toRow: (T) -> StatisticsDrilldownRow) {
            pageIndex = pageIndex.coerceIn(0, ((items.size - 1) / PAGE_SIZE).coerceAtLeast(0))
            val from = pageIndex * PAGE_SIZE
            val to = minOf(from + PAGE_SIZE, items.size)
            content = StatisticsDrilldownContent(
                subtitle = subtitle,
                queryContext = queryContext,
                rows = items.subList(from, to).map { item ->
                    val row = toRow(item)
                    row.copy(onOpen = {
                        if (!parent.isRefreshingState && parent.activeReport === report && parent.view != null) row.onOpen()
                    })
                },
                message = getString(R.string.unified_statistics_drilldown_empty).takeIf { items.isEmpty() },
                hasPreviousPage = pageIndex > 0,
                hasNextPage = to < items.size,
            )
        }

        when (kind) {
            KIND_TASKS -> {
                val tasks = DrilldownSelectionResolver.resolveTasks(report, reconstructTasksDescriptor())
                showPage(tasks, getString(R.string.unified_statistics_drilldown_tasks_count, tasks.size)) {
                    taskRow(it, context.today, context.now)
                }
            }
            KIND_FOCUS -> {
                val descriptor = reconstructFocusDescriptor()
                val sessions = DrilldownSelectionResolver.resolveFocus(report, descriptor)
                val sessionCount = getString(R.string.unified_statistics_drilldown_sessions_count, sessions.size)
                when {
                    sessions.isEmpty() -> showPage(sessions, sessionCount) { sessionRow(it, names, context.datasetId) }
                    descriptor == FocusDrilldownDescriptor.AllSections -> {
                        showPage(requireNotNull(report.focus).sectionBreakdowns, sessionCount) { branch ->
                            navigationRow(branch.name, branch.durationMs, "section:${branch.category}:${branch.containerId}") {
                                showForFocus(parentFragmentManager, branch.name, subtitleText,
                                    FocusDrilldownDescriptor.SectionBranch(branch.category, branch.containerId))
                            }
                        }
                    }
                    descriptor is FocusDrilldownDescriptor.SectionBranch || descriptor is FocusDrilldownDescriptor.TargetType -> {
                        val objects = requireNotNull(report.focus).targetsWithin(sessions)
                        val count = if (isRussian) "Первичные объекты · ${objects.size}" else "Primary objects · ${objects.size}"
                        showPage(objects, count) { target ->
                            val name = when (target.primaryTargetType) {
                                FocusPrimaryTargetType.TASK -> names.resolveTaskName(target.targetId, isRussian)
                                FocusPrimaryTargetType.HABIT -> names.resolveHabitName(target.targetId, isRussian)
                                FocusPrimaryTargetType.CONTAINER -> names.resolveContainerName(target.targetId, isRussian)
                                null -> if (isRussian) "Неизвестный объект" else "Unknown target"
                            }
                            navigationRow(name, target.durationMs, "target:${target.primaryTargetType}:${target.targetId}") {
                                val branch = descriptor as? FocusDrilldownDescriptor.SectionBranch
                                showForFocus(parentFragmentManager, name, subtitleText, FocusDrilldownDescriptor.TargetObject(
                                    target.primaryTargetType, target.targetId, branch?.category, branch?.containerId,
                                ))
                            }
                        }
                    }
                    else -> showPage(sessions, sessionCount) { sessionRow(it, names, context.datasetId) }
                }
            }
            KIND_HABIT_CYCLES -> {
                val cycles = DrilldownSelectionResolver.resolveHabitCycles(report, reconstructHabitDescriptor())
                showPage(cycles, getString(R.string.unified_statistics_drilldown_cycles_count, cycles.size)) {
                    cycleRow(it, context)
                }
            }
            KIND_HABIT_LIST -> {
                val summaries = DrilldownSelectionResolver.resolveHabitSummaries(report, habitSearchQuery)
                val count = if (isRussian) "Всего привычек: ${summaries.size}" else "Total habits: ${summaries.size}"
                val habitSummaries = report.habits?.habitSummaries.orEmpty().groupBy { it.habitUuid }
                showPage(summaries, count) { summary ->
                    habitSummaryRow(summary, habitSummaries[summary.habitUuid].orEmpty().ifEmpty { listOf(summary) })
                }
            }
        }
    }

    internal fun refreshReport() {
        if (view != null) populateItems()
    }

    private fun navigationRow(name: String, duration: Long, id: String, action: () -> Unit) =
        StatisticsDrilldownRow(id, name, listOf(UnifiedStatisticsStateUtils.formatDurationMs(duration, isRussian)),
            navigation = true, chevron = true, onOpen = action)

    private fun taskRow(task: UserTask, today: LocalDate, now: Long): StatisticsDrilldownRow {
        val completed = task.status == TaskStatus.COMPLETED
        val overdue = task.isOverdue(today, now)
        val status = getString(when {
            completed -> R.string.unified_statistics_due_state_completed
            overdue -> R.string.unified_statistics_due_state_overdue
            else -> R.string.unified_statistics_due_state_pending
        })
        return StatisticsDrilldownRow(task.id.value, task.title,
            status = status, statusColor = if (completed || overdue) palette.accent else palette.onSurfaceVariant) {
            TaskDetailBottomSheet.newInstance(task.id).show(parentFragmentManager, "TaskDetailBottomSheet")
        }
    }

    private fun sessionRow(session: FocusSession, names: StatisticsEntityNameSnapshot, datasetId: String): StatisticsDrilldownRow {
        val title = when (session.primaryTargetType) {
            FocusPrimaryTargetType.TASK -> names.resolveTaskName(session.taskId?.value, isRussian)
            FocusPrimaryTargetType.HABIT -> names.resolveHabitName(session.habit?.uuid, isRussian)
            FocusPrimaryTargetType.CONTAINER -> names.resolveContainerName(session.containerId?.value, isRussian)
            null -> if (isRussian) "Без раздела" else "Unassigned"
        }
        val duration = UnifiedStatisticsStateUtils.formatDurationMs(session.durationMs, isRussian)
        val ended = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(session.endedAt))
        return StatisticsDrilldownRow(session.id.value, title, listOf("$duration · $ended")) {
            FocusSessionDetailBottomSheet.newInstance(session.id.value, datasetId)
                .show(parentFragmentManager, FocusSessionDetailBottomSheet.TAG)
        }
    }

    private fun reconstructTasksDescriptor(): TasksDrilldownDescriptor = when (taskDescriptorType) {
        "COMPLETED_DAY" -> {
            val d = if (taskDateDays >= 0) LocalDate(taskDateDays) else LocalDate(2026, 1, 1)
            TasksDrilldownDescriptor.CompletedDay(d)
        }
        "COMPLETED_RANGE" -> {
            val f = if (taskFromDays >= 0) LocalDate(taskFromDays) else LocalDate(2026, 1, 1)
            val t = if (taskToDays >= 0) LocalDate(taskToDays) else LocalDate(2026, 1, 1)
            TasksDrilldownDescriptor.CompletedRange(f, t, titleText)
        }
        "CREATED_TASKS" -> {
            val f = if (taskFromDays >= 0) LocalDate(taskFromDays) else LocalDate(2026, 1, 1)
            val t = if (taskToDays >= 0) LocalDate(taskToDays) else LocalDate(2026, 1, 1)
            TasksDrilldownDescriptor.CreatedTasks(f, t, titleText)
        }
        "DUE_COHORT" -> {
            val filter = taskFilterName?.let {
                try { TasksDrilldownDescriptor.DueSegmentFilter.valueOf(it) } catch (e: Exception) { TasksDrilldownDescriptor.DueSegmentFilter.ALL_DUE }
            } ?: TasksDrilldownDescriptor.DueSegmentFilter.ALL_DUE
            TasksDrilldownDescriptor.DueCohort(filter)
        }
        "NODUE_COHORT" -> {
            val filter = taskFilterName?.let {
                try { TasksDrilldownDescriptor.NoDueSegmentFilter.valueOf(it) } catch (e: Exception) { TasksDrilldownDescriptor.NoDueSegmentFilter.ALL_NO_DUE }
            } ?: TasksDrilldownDescriptor.NoDueSegmentFilter.ALL_NO_DUE
            TasksDrilldownDescriptor.NoDueCohort(filter)
        }
        else -> TasksDrilldownDescriptor.None
    }

    private fun reconstructFocusDescriptor(): FocusDrilldownDescriptor = when (focusDescriptorType) {
        "ALL_SECTIONS" -> FocusDrilldownDescriptor.AllSections
        "TARGET_TYPE" -> FocusDrilldownDescriptor.TargetType(focusTargetTypeName?.let { FocusPrimaryTargetType.valueOf(it) })
        "DAY_SESSIONS" -> {
            val d = if (focusDateDays >= 0) LocalDate(focusDateDays) else LocalDate(2026, 1, 1)
            FocusDrilldownDescriptor.DaySessions(d)
        }
        "SESSIONS_RANGE" -> {
            val f = if (focusFromDays >= 0) LocalDate(focusFromDays) else LocalDate(2026, 1, 1)
            val t = if (focusToDays >= 0) LocalDate(focusToDays) else LocalDate(2026, 1, 1)
            FocusDrilldownDescriptor.SessionsRange(f, t, titleText)
        }
        "SECTION_BRANCH" -> {
            val cat = focusCategoryName?.let {
                try { FocusSectionCategory.valueOf(it) } catch (e: Exception) { FocusSectionCategory.ROOT_CONTAINER }
            } ?: FocusSectionCategory.ROOT_CONTAINER
            val cid = focusContainerIdVal?.let { ContainerId(it) }
            FocusDrilldownDescriptor.SectionBranch(cat, cid)
        }
        "TARGET_OBJECT" -> {
            val tType = focusTargetTypeName?.let {
                try { FocusPrimaryTargetType.valueOf(it) } catch (e: Exception) { null }
            }
            FocusDrilldownDescriptor.TargetObject(tType, focusTargetIdVal,
                targetBranchCategory?.let { FocusSectionCategory.valueOf(it) }, targetBranchId?.let(::ContainerId))
        }
        "CALENDAR_DAY" -> {
            val d = if (focusDateDays >= 0) LocalDate(focusDateDays) else LocalDate(2026, 1, 1)
            FocusDrilldownDescriptor.CalendarDay(d)
        }
        else -> FocusDrilldownDescriptor.None
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setOnShowListener {
            val d = it as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    companion object {
        const val TAG = "UnifiedStatisticsDrilldownBottomSheet"
        private const val PAGE_SIZE = 30
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUBTITLE = "arg_subtitle"
        private const val ARG_KIND = "arg_kind"
        private const val ARG_PAGE_SIZE_LIMIT = "arg_page_size_limit"

        private const val ARG_TASK_DESC_TYPE = "arg_task_desc_type"
        private const val ARG_TASK_DATE = "arg_task_date"
        private const val ARG_TASK_FROM = "arg_task_from"
        private const val ARG_TASK_TO = "arg_task_to"
        private const val ARG_TASK_FILTER = "arg_task_filter"

        private const val ARG_FOCUS_DESC_TYPE = "arg_focus_desc_type"
        private const val ARG_FOCUS_DATE = "arg_focus_date"
        private const val ARG_FOCUS_FROM = "arg_focus_from"
        private const val ARG_FOCUS_TO = "arg_focus_to"
        private const val ARG_FOCUS_CATEGORY = "arg_focus_category"
        private const val ARG_FOCUS_CONTAINER_ID = "arg_focus_container_id"
        private const val ARG_FOCUS_TARGET_TYPE = "arg_focus_target_type"
        private const val ARG_FOCUS_TARGET_ID = "arg_focus_target_id"

        private const val KIND_TASKS = "tasks"
        private const val KIND_FOCUS = "focus"
        private const val KIND_HABIT_CYCLES = "habit_cycles"
        private const val KIND_HABIT_LIST = "habit_list"

        private const val ARG_HABIT_DESC_TYPE = "arg_habit_desc_type"
        private const val ARG_HABIT_CADENCE = "arg_habit_cadence"
        private const val ARG_HABIT_DATE = "arg_habit_date"
        private const val ARG_HABIT_FROM = "arg_habit_from"
        private const val ARG_HABIT_TO = "arg_habit_to"
        private const val ARG_HABIT_UUID = "arg_habit_uuid"
        private const val ARG_HABIT_NAME = "arg_habit_name"
        private const val ARG_HABIT_SEARCH_QUERY = "arg_habit_search_query"

        fun showForTasks(
            fm: FragmentManager,
            title: String,
            subtitle: String,
            descriptor: TasksDrilldownDescriptor,
        ) {
            val sheet = UnifiedStatisticsDrilldownBottomSheet()
            val b = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_SUBTITLE, subtitle)
                putString(ARG_KIND, KIND_TASKS)
                when (descriptor) {
                    is TasksDrilldownDescriptor.CompletedDay -> {
                        putString(ARG_TASK_DESC_TYPE, "COMPLETED_DAY")
                        putInt(ARG_TASK_DATE, descriptor.date.daysSince2000)
                    }
                    is TasksDrilldownDescriptor.CompletedRange -> {
                        putString(ARG_TASK_DESC_TYPE, "COMPLETED_RANGE")
                        putInt(ARG_TASK_FROM, descriptor.from.daysSince2000)
                        putInt(ARG_TASK_TO, descriptor.to.daysSince2000)
                    }
                    is TasksDrilldownDescriptor.CreatedTasks -> {
                        putString(ARG_TASK_DESC_TYPE, "CREATED_TASKS")
                        putInt(ARG_TASK_FROM, descriptor.from.daysSince2000)
                        putInt(ARG_TASK_TO, descriptor.to.daysSince2000)
                    }
                    is TasksDrilldownDescriptor.DueCohort -> {
                        putString(ARG_TASK_DESC_TYPE, "DUE_COHORT")
                        putString(ARG_TASK_FILTER, descriptor.filter.name)
                    }
                    is TasksDrilldownDescriptor.NoDueCohort -> {
                        putString(ARG_TASK_DESC_TYPE, "NODUE_COHORT")
                        putString(ARG_TASK_FILTER, descriptor.filter.name)
                    }
                    TasksDrilldownDescriptor.None -> {}
                }
            }
            sheet.arguments = b
            sheet.show(fm, TAG)
        }

        fun showForFocus(
            fm: FragmentManager,
            title: String,
            subtitle: String,
            descriptor: FocusDrilldownDescriptor,
        ) {
            val sheet = UnifiedStatisticsDrilldownBottomSheet()
            val b = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_SUBTITLE, subtitle)
                putString(ARG_KIND, KIND_FOCUS)
                when (descriptor) {
                    FocusDrilldownDescriptor.AllSections -> putString(ARG_FOCUS_DESC_TYPE, "ALL_SECTIONS")
                    is FocusDrilldownDescriptor.TargetType -> {
                        putString(ARG_FOCUS_DESC_TYPE, "TARGET_TYPE")
                        putString(ARG_FOCUS_TARGET_TYPE, descriptor.type?.name)
                    }
                    is FocusDrilldownDescriptor.DaySessions -> {
                        putString(ARG_FOCUS_DESC_TYPE, "DAY_SESSIONS")
                        putInt(ARG_FOCUS_DATE, descriptor.date.daysSince2000)
                    }
                    is FocusDrilldownDescriptor.SessionsRange -> {
                        putString(ARG_FOCUS_DESC_TYPE, "SESSIONS_RANGE")
                        putInt(ARG_FOCUS_FROM, descriptor.from.daysSince2000)
                        putInt(ARG_FOCUS_TO, descriptor.to.daysSince2000)
                    }
                    is FocusDrilldownDescriptor.SectionBranch -> {
                        putString(ARG_FOCUS_DESC_TYPE, "SECTION_BRANCH")
                        putString(ARG_FOCUS_CATEGORY, descriptor.category.name)
                        putString(ARG_FOCUS_CONTAINER_ID, descriptor.containerId?.value)
                    }
                    is FocusDrilldownDescriptor.TargetObject -> {
                        putString(ARG_FOCUS_DESC_TYPE, "TARGET_OBJECT")
                        putString(ARG_FOCUS_TARGET_TYPE, descriptor.primaryTargetType?.name)
                        putString(ARG_FOCUS_TARGET_ID, descriptor.targetId)
                        putString("targetBranchCategory", descriptor.sectionCategory?.name)
                        putString("targetBranchId", descriptor.sectionContainerId?.value)
                    }
                    is FocusDrilldownDescriptor.CalendarDay -> {
                        putString(ARG_FOCUS_DESC_TYPE, "CALENDAR_DAY")
                        putInt(ARG_FOCUS_DATE, descriptor.date.daysSince2000)
                    }
                    FocusDrilldownDescriptor.None -> {}
                }
            }
            sheet.arguments = b
            sheet.show(fm, TAG)
        }

        fun showForHabitCycles(
            fm: FragmentManager,
            title: String,
            subtitle: String,
            descriptor: HabitsDrilldownDescriptor,
        ) {
            val sheet = UnifiedStatisticsDrilldownBottomSheet()
            val b = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_SUBTITLE, subtitle)
                putString(ARG_KIND, KIND_HABIT_CYCLES)
                when (descriptor) {
                    is HabitsDrilldownDescriptor.CadenceCycles -> {
                        putString(ARG_HABIT_DESC_TYPE, "CADENCE_CYCLES")
                        putString(ARG_HABIT_CADENCE, descriptor.cadence.name)
                    }
                    is HabitsDrilldownDescriptor.BucketCycles -> {
                        putString(ARG_HABIT_DESC_TYPE, "BUCKET_CYCLES")
                        putString(ARG_HABIT_CADENCE, descriptor.cadence.name)
                        putInt(ARG_HABIT_DATE, descriptor.date.daysSince2000)
                    }
                    is HabitsDrilldownDescriptor.CadenceRangeCycles -> {
                        putString(ARG_HABIT_DESC_TYPE, "CADENCE_RANGE")
                        putString(ARG_HABIT_CADENCE, descriptor.cadence.name)
                        putInt(ARG_HABIT_FROM, descriptor.from.daysSince2000)
                        putInt(ARG_HABIT_TO, descriptor.to.daysSince2000)
                    }
                    HabitsDrilldownDescriptor.OngoingCycles -> {
                        putString(ARG_HABIT_DESC_TYPE, "ONGOING_CYCLES")
                    }
                    is HabitsDrilldownDescriptor.HabitCycles -> {
                        putString(ARG_HABIT_DESC_TYPE, "HABIT_CYCLES")
                        putString(ARG_HABIT_UUID, descriptor.habitUuid)
                        putString(ARG_HABIT_NAME, descriptor.habitName)
                    }
                    else -> {}
                }
            }
            sheet.arguments = b
            sheet.show(fm, TAG)
        }

        fun showForHabitList(
            fm: FragmentManager,
            title: String,
            subtitle: String,
            initialQuery: String = "",
        ) {
            val sheet = UnifiedStatisticsDrilldownBottomSheet()
            val b = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_SUBTITLE, subtitle)
                putString(ARG_KIND, KIND_HABIT_LIST)
                putString(ARG_HABIT_DESC_TYPE, "ALL_HABITS")
                putString(ARG_HABIT_SEARCH_QUERY, initialQuery)
            }
            sheet.arguments = b
            sheet.show(fm, TAG)
        }
    }

    private fun cycleRow(cycle: HabitCycle, reportContext: StatisticsQueryContext): StatisticsDrilldownRow {
        val (outcomeLabel, outcomeColor) = when {
            isHistoricalCycleSlice(cycle, reportContext) -> {
                Pair(getString(R.string.unified_statistics_habits_historical_cycle_status), palette.onSurfaceVariant)
            }
            cycle.lifecycle == HabitCycleLifecycle.ONGOING -> {
                if (cycle.outcome == HabitCycleOutcome.SATISFIED) {
                    Pair(getString(R.string.unified_statistics_habits_ongoing_reached), 0xFF4CAF50.toInt())
                } else {
                    Pair(getString(R.string.unified_statistics_habits_ongoing_in_progress), 0xFF2196F3.toInt())
                }
            }
            cycle.lifecycle == HabitCycleLifecycle.PARTIAL -> {
                Pair(getString(R.string.unified_statistics_cycle_lifecycle_partial), 0xFFFF9800.toInt())
            }
            cycle.lifecycle == HabitCycleLifecycle.TRANSITION -> {
                Pair(getString(R.string.unified_statistics_cycle_lifecycle_transition), 0xFFFF9800.toInt())
            }
            cycle.outcome == HabitCycleOutcome.SATISFIED -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_satisfied), 0xFF4CAF50.toInt())
            }
            cycle.outcome == HabitCycleOutcome.NOT_SATISFIED -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_not_satisfied), 0xFFE53935.toInt())
            }
            cycle.outcome == HabitCycleOutcome.EXEMPT -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_exempt), palette.onSurfaceVariant)
            }
            cycle.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_capacity_excluded), 0xFFFF9800.toInt())
            }
            cycle.outcome == HabitCycleOutcome.UNKNOWN -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_unknown), palette.onSurfaceVariant)
            }
            else -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_unavailable), palette.onSurfaceVariant)
            }
        }
        val range = "${formatStatisticsDate(cycle.startDate)} — ${formatStatisticsDate(cycle.endDate)}"
        return StatisticsDrilldownRow(
            cycle.key.toString(), cycle.habitName, listOf(range),
            status = outcomeLabel, statusColor = outcomeColor, inlineStatus = true,
        ) {
            UnifiedStatisticsCycleDetailBottomSheet.show(
                parentFragmentManager, cycle.key.toString(), reportContext.datasetId, querySignature,
            )
        }
    }

    private fun habitSummaryRow(summary: HabitSummary, summaries: List<HabitSummary>): StatisticsDrilldownRow {
        val metadata = summaries.map { item ->
            val cadenceStr = when (item.cadence) {
                HabitCadence.DAILY -> getString(R.string.unified_statistics_cadence_daily)
                HabitCadence.WEEKLY -> getString(R.string.unified_statistics_cadence_weekly)
                HabitCadence.MONTHLY -> getString(R.string.unified_statistics_cadence_monthly)
                HabitCadence.UNSUPPORTED -> getString(R.string.unified_statistics_cadence_unsupported)
            }
            val resultStr = if (item.eligibleCount == 0) {
                getString(R.string.unified_statistics_cadence_no_closed_cycles)
            } else if (item.unknownCount > 0) {
                getString(
                    R.string.unified_statistics_cadence_result_with_unknown,
                    item.satisfiedCount,
                    item.knownCount,
                    item.unknownCount
                )
            } else {
                getString(
                    R.string.unified_statistics_cadence_result_format,
                    item.satisfiedCount,
                    item.knownCount
                )
            }
            "$cadenceStr · $resultStr"
        }
        return StatisticsDrilldownRow(summary.habitUuid, summary.habitName, metadata, chevron = true) {
            showForHabitCycles(parentFragmentManager, summary.habitName, subtitleText,
                HabitsDrilldownDescriptor.HabitCycles(summary.habitUuid, summary.habitName))
        }
    }

    private fun reconstructHabitDescriptor(): HabitsDrilldownDescriptor = when (habitDescriptorType) {
        "CADENCE_CYCLES" -> {
            val cad = HabitCadence.entries.firstOrNull { it.name == habitCadenceName } ?: HabitCadence.DAILY
            HabitsDrilldownDescriptor.CadenceCycles(cad)
        }
        "BUCKET_CYCLES" -> {
            val date = if (habitDateDays >= 0) LocalDate(habitDateDays) else LocalDate(2026, 1, 1)
            val cad = HabitCadence.entries.firstOrNull { it.name == habitCadenceName } ?: HabitCadence.DAILY
            HabitsDrilldownDescriptor.BucketCycles(date, cad, date.toString())
        }
        "CADENCE_RANGE" -> {
            val cad = HabitCadence.entries.firstOrNull { it.name == habitCadenceName } ?: HabitCadence.DAILY
            val from = if (habitFromDays >= 0) LocalDate(habitFromDays) else LocalDate(2026, 1, 1)
            val to = if (habitToDays >= 0) LocalDate(habitToDays) else LocalDate(2026, 1, 1)
            HabitsDrilldownDescriptor.CadenceRangeCycles(cad, from, to, "$from..$to")
        }
        "ONGOING_CYCLES" -> HabitsDrilldownDescriptor.OngoingCycles
        "HABIT_CYCLES" -> HabitsDrilldownDescriptor.HabitCycles(habitUuidVal.orEmpty(), habitNameVal.orEmpty())
        "ALL_HABITS" -> HabitsDrilldownDescriptor.AllHabitsList(habitSearchQuery)
        else -> HabitsDrilldownDescriptor.None
    }
}
