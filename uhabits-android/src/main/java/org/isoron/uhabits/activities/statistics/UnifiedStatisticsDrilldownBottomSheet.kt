/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.statistics

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
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
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.utils.InterfaceUtils
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
    internal var visibleIds: List<String> = emptyList()
        private set

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
    private var habitSearchQuery: String = ""

    private lateinit var listContainer: LinearLayout
    private lateinit var subtitleView: TextView
    private lateinit var queryContextView: TextView
    private lateinit var loadMoreButton: MaterialButton
    private lateinit var previousPageButton: MaterialButton
    internal val nextPageButton: MaterialButton get() = loadMoreButton

    private val palette: SettingsThemePalette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

    private fun dp(value: Float): Int = InterfaceUtils.dpToPixels(requireContext(), value).toInt()

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()

        val root = FrameLayout(context).apply {
            tag = if (kind == KIND_HABIT_CYCLES || kind == KIND_HABIT_LIST) "habitDrilldownSheet" else "unifiedDrilldownSheet"
            setBackgroundColor(palette.surface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            isFillViewport = true
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
        }

        // Title
        val tvTitle = TextView(context).apply {
            text = titleText
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
        }
        contentLayout.addView(tvTitle)

        // Subtitle
        subtitleView = TextView(context).apply {
            text = subtitleText
            textSize = 14f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(4f), 0, dp(12f))
        }
        contentLayout.addView(subtitleView)
        queryContextView = TextView(context).apply {
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, 0, 0, dp(12f))
        }
        contentLayout.addView(queryContextView)

        if (kind == KIND_HABIT_LIST) {
            val searchInput = android.widget.EditText(context).apply {
                tag = "habitSearchInput"
                hint = getString(R.string.unified_statistics_habits_search_hint)
                setTextColor(palette.onSurface)
                setHintTextColor(palette.onSurfaceVariant)
                background = GradientDrawable().apply {
                    setColor(palette.surfaceVariant)
                    cornerRadius = dp(8f).toFloat()
                }
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12f)
                }
                setText(habitSearchQuery)
                doAfterTextChanged { text ->
                    val q = text?.toString().orEmpty()
                    if (q != habitSearchQuery) {
                        habitSearchQuery = q
                        pageIndex = 0
                        populateItems()
                    }
                }
            }
            contentLayout.addView(searchInput)
        }

        // Items container
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentLayout.addView(listContainer)

        previousPageButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = if (isRussian) "Предыдущая страница" else "Previous page"
            setTextColor(palette.accent)
            minHeight = dp(48f)
            setOnClickListener { pageIndex = (pageIndex - 1).coerceAtLeast(0); populateItems() }
        }
        contentLayout.addView(previousPageButton)
        loadMoreButton = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = if (isRussian) "Следующая страница" else "Next page"
            setTextColor(palette.accent)
            visibility = View.GONE
            setOnClickListener {
                pageIndex++
                populateItems()
            }
        }
        contentLayout.addView(loadMoreButton)

        scrollView.addView(contentLayout)
        root.addView(scrollView)

        populateItems()

        return root
    }

    private fun populateItems() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        visibleIds = emptyList()
        previousPageButton.visibility = View.GONE
        loadMoreButton.visibility = View.GONE

        val parentFrag = parentFragment as? UnifiedStatisticsFragment
        val report = parentFrag?.activeReport
        val state = parentFrag?.currentState
        val ready = state is UnifiedReportUiState.Success && !state.isRefreshing
        val signature = report?.context?.let(::statisticsQuerySignature)
        if (report != null) {
            if (datasetId == null) datasetId = report.context.datasetId
            if (querySignature == null) querySignature = signature
            if (datasetId != report.context.datasetId || querySignature != signature) {
                dismissAllowingStateLoss()
                return
            }
        }

        if (report == null || !ready) {
            val emptyTv = TextView(requireContext()).apply {
                text = if (state is UnifiedReportUiState.Failure) getString(R.string.unified_statistics_domain_failed)
                    else getString(R.string.unified_statistics_state_refreshing)
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, dp(12f), 0, dp(12f))
            }
            listContainer.addView(emptyTv)
            loadMoreButton.visibility = View.GONE
            return
        }

        val nameSnapshot = parentFrag.nameSnapshot
        val scopeLabel = when (val scope = report.context.sectionScope) {
            org.isoron.uhabits.core.statistics.StatisticsSectionScope.All -> getString(R.string.unified_statistics_scope_all)
            org.isoron.uhabits.core.statistics.StatisticsSectionScope.Unassigned -> getString(R.string.unified_statistics_scope_unassigned)
            is org.isoron.uhabits.core.statistics.StatisticsSectionScope.Container ->
                nameSnapshot.resolveContainerName(scope.id.value, isRussian) + " · " +
                    if (scope.includeDescendants) getString(R.string.unified_statistics_scope_mode_subtree)
                    else getString(R.string.unified_statistics_scope_mode_here)
        }
        queryContextView.text = "${formatStatisticsDate(report.context.from)} — ${formatStatisticsDate(report.context.to)}\n$scopeLabel"
        val unavailable = when (kind) {
            KIND_TASKS -> report.tasks?.dataState.let { it == null || it == StatisticsDataState.UNAVAILABLE }
            KIND_FOCUS -> report.focus?.dataState.let { it == null || it == StatisticsDataState.UNAVAILABLE }
            KIND_HABIT_CYCLES, KIND_HABIT_LIST -> report.habits?.dataState.let { it == null || it == StatisticsDataState.UNAVAILABLE }
            else -> false
        }
        if (unavailable) {
            listContainer.addView(TextView(requireContext()).apply {
                text = getString(R.string.unified_statistics_domain_failed)
                setTextColor(palette.onSurface)
            })
            listContainer.addView(MaterialButton(requireContext()).apply {
                text = getString(R.string.unified_statistics_state_retry)
                minHeight = dp(48f)
                setOnClickListener { parentFrag.refresh() }
            })
            return
        }
        fun page(total: Int): IntRange {
            pageIndex = pageIndex.coerceIn(0, ((total - 1) / 30).coerceAtLeast(0))
            previousPageButton.visibility = if (pageIndex > 0) View.VISIBLE else View.GONE
            loadMoreButton.visibility = if ((pageIndex + 1) * 30 < total) View.VISIBLE else View.GONE
            return pageIndex * 30 until minOf((pageIndex + 1) * 30, total)
        }

        if (kind == KIND_TASKS) {
            val descriptor = reconstructTasksDescriptor()
            val tasks = DrilldownSelectionResolver.resolveTasks(report, descriptor)
            subtitleView.text = getString(R.string.unified_statistics_drilldown_tasks_count, tasks.size)

            if (tasks.isEmpty()) {
                val emptyTv = TextView(requireContext()).apply {
                    text = getString(R.string.unified_statistics_drilldown_empty)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(12f), 0, dp(12f))
                }
                listContainer.addView(emptyTv)
                loadMoreButton.visibility = View.GONE
                return
            }

            val visibleTasks = page(tasks.size).map { tasks[it] }
            visibleIds = visibleTasks.map { it.id.value }
            visibleTasks.forEach { task ->
                val row = createTaskRow(task, report.context.today, report.context.now)
                listContainer.addView(row)
            }

        } else if (kind == KIND_FOCUS) {
            val descriptor = reconstructFocusDescriptor()
            val sessions = DrilldownSelectionResolver.resolveFocus(report, descriptor)
            subtitleView.text = getString(R.string.unified_statistics_drilldown_sessions_count, sessions.size)

            if (sessions.isEmpty()) {
                val emptyTv = TextView(requireContext()).apply {
                    text = getString(R.string.unified_statistics_drilldown_empty)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(12f), 0, dp(12f))
                }
                listContainer.addView(emptyTv)
                loadMoreButton.visibility = View.GONE
                return
            }

            if (descriptor == FocusDrilldownDescriptor.AllSections) {
                val branches = report.focus!!.sectionBreakdowns
                page(branches.size).forEach { index ->
                    val branch = branches[index]
                    listContainer.addView(navigationRow(branch.name, branch.durationMs, "section:${branch.category}:${branch.containerId}") {
                        showForFocus(parentFragmentManager, branch.name, subtitleText,
                            FocusDrilldownDescriptor.SectionBranch(branch.category, branch.containerId))
                    })
                }
                return
            }
            if (descriptor is FocusDrilldownDescriptor.SectionBranch || descriptor is FocusDrilldownDescriptor.TargetType) {
                val objects = report.focus!!.targetsWithin(sessions)
                subtitleView.text = if (isRussian) "Первичные объекты · ${objects.size}" else "Primary objects · ${objects.size}"
                page(objects.size).forEach { index ->
                    val target = objects[index]
                    val name = when (target.primaryTargetType) {
                        FocusPrimaryTargetType.TASK -> nameSnapshot.resolveTaskName(target.targetId, isRussian)
                        FocusPrimaryTargetType.HABIT -> nameSnapshot.resolveHabitName(target.targetId, isRussian)
                        FocusPrimaryTargetType.CONTAINER -> nameSnapshot.resolveContainerName(target.targetId, isRussian)
                        null -> if (isRussian) "Неизвестный объект" else "Unknown target"
                    }
                    listContainer.addView(navigationRow(name, target.durationMs, "target:${target.primaryTargetType}:${target.targetId}") {
                        val branch = descriptor as? FocusDrilldownDescriptor.SectionBranch
                        showForFocus(parentFragmentManager, name, subtitleText, FocusDrilldownDescriptor.TargetObject(
                            target.primaryTargetType, target.targetId, branch?.category, branch?.containerId))
                    })
                }
                return
            }
            val visibleSessions = page(sessions.size).map { sessions[it] }
            visibleIds = visibleSessions.map { it.id.value }
            visibleSessions.forEach { session ->
                val row = createSessionRow(session, nameSnapshot, report.context.datasetId)
                listContainer.addView(row)
            }

        } else if (kind == KIND_HABIT_CYCLES) {
            val descriptor = reconstructHabitDescriptor()
            val cycles = DrilldownSelectionResolver.resolveHabitCycles(report, descriptor)
            subtitleView.text = getString(R.string.unified_statistics_drilldown_cycles_count, cycles.size)

            if (cycles.isEmpty()) {
                val emptyTv = TextView(requireContext()).apply {
                    text = getString(R.string.unified_statistics_drilldown_empty)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(12f), 0, dp(12f))
                }
                listContainer.addView(emptyTv)
                loadMoreButton.visibility = View.GONE
                return
            }

            val visibleCycles = page(cycles.size).map { cycles[it] }
            visibleIds = visibleCycles.map { it.key.toString() }
            visibleCycles.forEach { cycle ->
                val row = createCycleRow(cycle, report.context.datasetId)
                listContainer.addView(row)
            }

        } else if (kind == KIND_HABIT_LIST) {
            val summaries = DrilldownSelectionResolver.resolveHabitSummaries(report, habitSearchQuery)
            subtitleView.text = if (isRussian) "Всего привычек: ${summaries.size}" else "Total habits: ${summaries.size}"

            if (summaries.isEmpty()) {
                val emptyTv = TextView(requireContext()).apply {
                    text = getString(R.string.unified_statistics_drilldown_empty)
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(12f), 0, dp(12f))
                }
                listContainer.addView(emptyTv)
                loadMoreButton.visibility = View.GONE
                return
            }

            val visibleSummaries = page(summaries.size).map { summaries[it] }
            visibleIds = visibleSummaries.map { it.habitUuid }
            visibleSummaries.forEach { summary ->
                val row = createHabitSummaryRow(summary)
                listContainer.addView(row)
            }
        }
    }

    internal fun refreshReport() { if (view != null) populateItems() }

    private fun navigationRow(name: String, duration: Long, id: String, action: () -> Unit): View =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "$name\n${UnifiedStatisticsStateUtils.formatDurationMs(duration, isRussian)}  ›"
            tag = id
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(48f)
            setTextColor(palette.onSurface)
            setOnClickListener { if ((parentFragment as? UnifiedStatisticsFragment)?.isRefreshingState == false) action() }
        }

    private fun createTaskRow(task: UserTask, today: LocalDate, now: Long): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            tag = task.id.value
            minimumHeight = dp(48f)
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8f)
            }
            setOnClickListener {
                if ((parentFragment as? UnifiedStatisticsFragment)?.isRefreshingState != false) return@setOnClickListener
                TaskDetailBottomSheet.newInstance(task.id).show(parentFragmentManager, "TaskDetailBottomSheet")
            }
        }

        val titleTv = TextView(context).apply {
            text = task.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
        }
        row.addView(titleTv)

        val metaLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, 0)
        }

        val statusTv = TextView(context).apply {
            text = if (task.status == TaskStatus.COMPLETED) {
                getString(R.string.unified_statistics_due_state_completed)
            } else if (task.isOverdue(today, now)) {
                getString(R.string.unified_statistics_due_state_overdue)
            } else {
                getString(R.string.unified_statistics_due_state_pending)
            }
            textSize = 12f
            setTextColor(
                if (task.status == TaskStatus.COMPLETED) palette.accent
                else if (task.isOverdue(today, now)) palette.accent
                else palette.onSurfaceVariant
            )
            setTypeface(typeface, Typeface.BOLD)
        }
        metaLayout.addView(statusTv)

        row.addView(metaLayout)
        return row
    }

    private fun createSessionRow(
        session: FocusSession,
        nameSnapshot: StatisticsEntityNameSnapshot,
        datasetId: String,
    ): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            tag = session.id.value
            minimumHeight = dp(48f)
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8f)
            }
            setOnClickListener {
                if ((parentFragment as? UnifiedStatisticsFragment)?.isRefreshingState != false) return@setOnClickListener
                FocusSessionDetailBottomSheet.newInstance(session.id.value, datasetId)
                    .show(parentFragmentManager, FocusSessionDetailBottomSheet.TAG)
            }
        }

        val targetName = when (session.primaryTargetType) {
            FocusPrimaryTargetType.TASK -> nameSnapshot.resolveTaskName(session.taskId?.value, isRussian)
            FocusPrimaryTargetType.HABIT -> nameSnapshot.resolveHabitName(session.habit?.uuid, isRussian)
            FocusPrimaryTargetType.CONTAINER -> nameSnapshot.resolveContainerName(session.containerId?.value, isRussian)
            null -> if (isRussian) "Без раздела" else "Unassigned"
        }

        val titleTv = TextView(context).apply {
            text = targetName
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
        }
        row.addView(titleTv)

        val durationStr = UnifiedStatisticsStateUtils.formatDurationMs(session.durationMs, isRussian)
        val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val endedStr = timeFormat.format(Date(session.endedAt))

        val metaTv = TextView(context).apply {
            text = "$durationStr · $endedStr"
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(4f), 0, 0)
        }
        row.addView(metaTv)

        return row
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

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom)
            insets
        }
    }

    companion object {
        const val TAG = "UnifiedStatisticsDrilldownBottomSheet"
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

    private fun createCycleRow(cycle: HabitCycle, datasetId: String): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            tag = cycle.key.toString()
            minimumHeight = dp(48f)
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8f)
            }
            setOnClickListener {
                if ((parentFragment as? UnifiedStatisticsFragment)?.isRefreshingState != false) return@setOnClickListener
                UnifiedStatisticsCycleDetailBottomSheet.show(
                    parentFragmentManager,
                    cycle.key.toString(),
                    datasetId,
                    querySignature
                )
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(context).apply {
            text = cycle.habitName
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(titleTv)

        val (outcomeLabel, outcomeColor) = when {
            (parentFragment as? UnifiedStatisticsFragment)?.activeReport?.context?.let {
                isHistoricalCycleSlice(cycle, it)
            } == true -> {
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

        val outcomeTv = TextView(context).apply {
            text = outcomeLabel
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(outcomeColor)
        }
        topRow.addView(outcomeTv)
        row.addView(topRow)

        val dateTv = TextView(context).apply {
            text = "${formatStatisticsDate(cycle.startDate)} — ${formatStatisticsDate(cycle.endDate)}"
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(4f), 0, 0)
        }
        row.addView(dateTv)

        return row
    }

    private fun createHabitSummaryRow(summary: HabitSummary): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            tag = summary.habitUuid
            minimumHeight = dp(48f)
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8f)
            }
            setOnClickListener {
                if ((parentFragment as? UnifiedStatisticsFragment)?.isRefreshingState != false) return@setOnClickListener
                showForHabitCycles(
                    parentFragmentManager,
                    summary.habitName,
                    subtitleText,
                    HabitsDrilldownDescriptor.HabitCycles(summary.habitUuid, summary.habitName)
                )
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(context).apply {
            text = summary.habitName
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(titleTv)

        val chevronTv = TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(palette.onSurfaceVariant)
        }
        topRow.addView(chevronTv)
        row.addView(topRow)

        val summaries = (parentFragment as? UnifiedStatisticsFragment)?.activeReport?.habits
            ?.habitSummaries?.filter { it.habitUuid == summary.habitUuid }.orEmpty()
            .ifEmpty { listOf(summary) }
        summaries.forEach { item ->
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

        val metaTv = TextView(context).apply {
            text = "$cadenceStr · $resultStr"
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(4f), 0, 0)
        }
        row.addView(metaTv)
        }

        return row
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
