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

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.tasks.FocusHistoryProjector
import org.isoron.uhabits.core.containers.statistics.HistoricalPathParser
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.utils.InterfaceUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inspection sheet for a single finalized FocusSession fact.
 * All repository lookups are performed off-main thread via TaskRunner.
 */
class FocusSessionDetailBottomSheet : BottomSheetDialogFragment() {

    private var sessionId: String? = null
    private var datasetId: String? = null
    private var querySignature: String? = null
    private var pendingTask: Task? = null
    private var reload: (() -> Unit)? = null
    private var generation = 0
    private var loadedRevision: String? = null
    private var content: LinearLayout? = null

    private fun revision(): String {
        val component = (requireContext().applicationContext as HabitsApplication).component
        return "${component.effectiveReadRevision.value}|${component.workspaceReadRevision.value}|${component.organizationReadRevision.value}"
    }

    internal fun invalidateReport(state: UnifiedReportUiState) {
        if (!isAdded || view == null) return
        val application = requireContext().applicationContext as HabitsApplication
        val currentQuery = (parentFragment as? UnifiedStatisticsFragment)?.activeReport?.context?.let {
            "${it.from}|${it.to}|${it.sectionScope}|${it.timezoneId}"
        }
        if (querySignature == null) querySignature = currentQuery
        if (currentQuery != null && querySignature != currentQuery) {
            pendingTask?.cancel()
            dismissAllowingStateLoss()
            return
        }
        if (application.isDatasetRebuilding || application.component.session.datasetId != datasetId) {
            pendingTask?.cancel()
            dismissAllowingStateLoss()
        } else if (state is UnifiedReportUiState.Success && !state.isRefreshing) {
            if (loadedRevision != revision()) reload?.invoke()
        } else {
            pendingTask?.cancel()
            generation++
            loadedRevision = null
            content?.removeAllViews()
            content?.tag = null
        }
    }

    override fun onDestroyView() {
        pendingTask?.cancel()
        generation++
        reload = null
        content = null
        super.onDestroyView()
    }

    private val palette: SettingsThemePalette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

    private fun dp(value: Float): Int = InterfaceUtils.dpToPixels(requireContext(), value).toInt()

    private data class ContributionItem(
        val habitName: String,
        val valueStr: String,
        val isRemoved: Boolean,
        val removedDateStr: String? = null,
    )

    private data class FocusSessionDetailPayload(
        val session: FocusSession,
        val startedStr: String,
        val endedStr: String,
        val activeDurationStr: String,
        val elapsedDurationStr: String,
        val isEstimatedAllocation: Boolean,
        val targetTypeStr: String,
        val targetName: String,
        val frozenPathStr: String,
        val contributions: List<ContributionItem>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Statistics_BottomSheetDialogTheme)
        val args = savedInstanceState ?: arguments
        if (args != null) {
            sessionId = args.getString(ARG_SESSION_ID)
            datasetId = args.getString(ARG_DATASET_ID)
            querySignature = args.getString("querySignature")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_SESSION_ID, sessionId)
        outState.putString(ARG_DATASET_ID, datasetId)
        outState.putString("querySignature", querySignature)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val app = context.applicationContext as HabitsApplication
        if (querySignature == null) querySignature = (parentFragment as? UnifiedStatisticsFragment)?.activeReport?.context?.let {
            "${it.from}|${it.to}|${it.sectionScope}|${it.timezoneId}"
        }

        // Dataset switch guard: dismiss immediately if dataset changed
        val currentDataset = app.component.session.datasetId
        if (datasetId != null && datasetId != currentDataset) {
            dismissAllowingStateLoss()
            return View(context)
        }

        val root = FrameLayout(context).apply {
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
            setPadding(dp(20f), dp(20f), dp(20f), dp(28f))
        }

        // Header Title
        val titleView = TextView(context).apply {
            text = getString(R.string.unified_statistics_session_detail_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
        }
        contentLayout.addView(titleView)

        val id = sessionId
        if (id == null) {
            contentLayout.addView(TextView(context).apply {
                text = getString(R.string.unified_statistics_domain_failed)
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, dp(12f), 0, 0)
            })
            scrollView.addView(contentLayout)
            root.addView(scrollView)
            return root
        }

        val loadingProgress = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24f)
                bottomMargin = dp(24f)
            }
        }
        contentLayout.addView(loadingProgress)

        val detailContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content = detailContainer
        contentLayout.addView(detailContainer)

        scrollView.addView(contentLayout)
        root.addView(scrollView)

        // Asynchronous off-main thread loading
        val mainHandler = Handler(Looper.getMainLooper())
        val targetComponent = app.component

        reload = {
        pendingTask?.cancel()
        val requestGeneration = ++generation
        val requestedRevision = revision()
        loadedRevision = requestedRevision
        detailContainer.removeAllViews()
        detailContainer.tag = null
        loadingProgress.visibility = View.VISIBLE
        val detailTask = object : Task {
            @Volatile private var isCancelled = false
            private var payload: FocusSessionDetailPayload? = null
            private var loadError: Throwable? = null

            override fun cancel() {
                isCancelled = true
            }

            override fun isCanceled(): Boolean = isCancelled

            override suspend fun doInBackground() {
                if (isCancelled || requestGeneration != generation || app.isDatasetRebuilding || app.component !== targetComponent || targetComponent.session.datasetId != datasetId) return

                try {
                    val session = targetComponent.focusSessionRepository?.getById(FocusSessionId(id))
                    if (session == null) {
                        loadError = NoSuchElementException("Focus session $id not found")
                        return
                    }

                    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                    val startedStr = timeFormat.format(Date(session.startedAt))
                    val endedStr = timeFormat.format(Date(session.endedAt))
                    val activeStr = UnifiedStatisticsStateUtils.formatExactDurationMs(session.durationMs, isRussian)
                    val elapsedMs = (session.endedAt - session.startedAt).coerceAtLeast(0L)
                    val elapsedStr = UnifiedStatisticsStateUtils.formatExactDurationMs(elapsedMs, isRussian)

                    val segments = FocusHistoryProjector.projectSessionToDays(session)
                    val isEstimated = segments.any { it.isDayAllocationEstimated }

                    val targetName = when (session.primaryTargetType) {
                        FocusPrimaryTargetType.TASK -> {
                            val taskId = session.taskId
                            if (taskId != null) {
                                targetComponent.userTaskRepository.getById(taskId)?.title
                                    ?: "Deleted task (${taskId.value.take(8)})"
                            } else "Unnamed"
                        }
                        FocusPrimaryTargetType.HABIT -> {
                            val habitUuid = session.habit?.uuid
                            if (habitUuid != null) {
                                targetComponent.habitList.getByUUID(habitUuid)?.name
                                    ?: "Deleted habit (${habitUuid.take(8)})"
                            } else "Unnamed"
                        }
                        FocusPrimaryTargetType.CONTAINER -> {
                            val cid = session.containerId
                            if (cid != null) {
                                targetComponent.organizationFacade.containerQueries?.find(cid, includeDeleted = true)?.name
                                    ?: "Deleted section (${cid.value.take(8)})"
                            } else "Unnamed"
                        }
                        null -> if (isRussian) "Без раздела" else "Unassigned"
                    }

                    val targetTypeStr = session.primaryTargetType?.name ?: if (isRussian) "БЕЗ ТИПА" else "NO TYPE"

                    val parsedPath = HistoricalPathParser.parsePath(session.organizationContext)
                    val pathText = if (parsedPath.isNotEmpty()) {
                        parsedPath.joinToString(separator = " › ") { it.name ?: it.id.value }
                    } else if (session.containerId != null) {
                        val c = targetComponent.organizationFacade.containerQueries?.find(session.containerId!!, includeDeleted = true)
                        (c?.name ?: session.containerId!!.value) + (if (isRussian) " (текущее размещение)" else " (current placement)")
                    } else {
                        if (isRussian) "Без раздела" else "No section"
                    }

                    val contributionsList = mutableListOf<ContributionItem>()
                    val rawContribs = requireNotNull(targetComponent.contributionRepository).getBySource(
                        ContributionSourceType.FOCUS_SESSION,
                        session.id.value,
                        includeRemoved = true,
                    )

                    rawContribs.forEach { c ->
                        val hName = targetComponent.habitList.getByUUID(c.targetHabit.uuid)?.name
                            ?: "Deleted habit (${c.targetHabit.uuid.take(8)})"

                        // Ledger quantities are native x1000 Habit values, never Focus milliseconds.
                        // The ledger has no frozen unit, so do not infer one from today's Habit.
                        val valStr = "${java.math.BigDecimal.valueOf(c.contributedValue, 3).stripTrailingZeros().toPlainString()} " +
                            if (isRussian) "ед. значения (единица не сохранена в истории)" else "units (historical unit not recorded)"

                        val removedStr = c.removedAt?.let { timeFormat.format(Date(it)) }
                        contributionsList.add(ContributionItem(hName, valStr, c.removedAt != null, removedStr))
                    }

                    payload = FocusSessionDetailPayload(
                        session = session,
                        startedStr = startedStr,
                        endedStr = endedStr,
                        activeDurationStr = activeStr,
                        elapsedDurationStr = elapsedStr,
                        isEstimatedAllocation = isEstimated,
                        targetTypeStr = targetTypeStr,
                        targetName = targetName,
                        frozenPathStr = pathText,
                        contributions = contributionsList,
                    )
                } catch (t: Throwable) {
                    loadError = t
                }
            }

            override fun onPostExecute() {
                if (isCancelled || requestGeneration != generation || !isAdded || view == null) return
                if (app.isDatasetRebuilding || app.component !== targetComponent || app.component.session.datasetId != datasetId) {
                    dismissAllowingStateLoss()
                    return
                }

                if (revision() != requestedRevision) { loadedRevision = null; return }
                loadingProgress.visibility = View.GONE

                val data = payload
                if (data != null) {
                    renderPayload(detailContainer, data)
                } else {
                    val errView = TextView(context).apply {
                        text = getString(R.string.unified_statistics_domain_failed)
                        setTextColor(palette.onSurface)
                        setPadding(0, dp(12f), 0, 0)
                    }
                    detailContainer.addView(errView)
                    detailContainer.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = getString(R.string.unified_statistics_state_retry)
                        minHeight = dp(48f)
                        setOnClickListener { reload?.invoke() }
                    })
                }
            }
        }
        pendingTask = detailTask
        targetComponent.taskRunner.execute(detailTask)
        }
        reload?.invoke()

        return root
    }

    private fun renderPayload(container: LinearLayout, data: FocusSessionDetailPayload) {
        val context = requireContext()
        container.tag = "session-detail:${data.session.id.value}"

        // Timing section
        addSectionHeader(container, getString(R.string.unified_statistics_session_timing_label))
        addKeyValueRow(container, if (isRussian) "Начало" else "Started", data.startedStr)
        addKeyValueRow(container, if (isRussian) "Завершение" else "Ended", data.endedStr)
        addKeyValueRow(container, getString(R.string.unified_statistics_session_active_duration_label), data.activeDurationStr)
        addKeyValueRow(container, getString(R.string.unified_statistics_session_elapsed_label), data.elapsedDurationStr)
        addKeyValueRow(container, if (isRussian) "Источник" else "Source", data.session.source.name)
        addKeyValueRow(container, "Session ID", data.session.id.value)

        if (data.isEstimatedAllocation) {
            val estimatedBadge = TextView(context).apply {
                text = getString(R.string.unified_statistics_session_allocation_estimated)
                textSize = 13f
                setTextColor(palette.accent)
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(0, dp(6f), 0, dp(6f))
            }
            container.addView(estimatedBadge)
        }

        // Primary Target section
        addSectionHeader(container, getString(R.string.unified_statistics_session_primary_target_label))
        addKeyValueRow(container, data.targetTypeStr, data.targetName)

        // Frozen path section
        addSectionHeader(container, getString(R.string.unified_statistics_session_frozen_path_label))
        val pathView = TextView(context).apply {
            text = data.frozenPathStr
            textSize = 14f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(4f), 0, dp(8f))
        }
        container.addView(pathView)

        // Contributions section
        addSectionHeader(container, getString(R.string.unified_statistics_session_contributions_label))
        if (data.contributions.isEmpty()) {
            val emptyNotice = TextView(context).apply {
                text = getString(R.string.unified_statistics_session_no_contributions)
                textSize = 14f
                setTextColor(palette.onSurfaceVariant)
                setPadding(0, dp(4f), 0, dp(4f))
            }
            container.addView(emptyNotice)
        } else {
            data.contributions.forEach { c ->
                val rowView = TextView(context).apply {
                    if (c.isRemoved) {
                        tag = "unified_session_detail_tombstone"
                        text = getString(R.string.unified_statistics_session_contribution_tombstone, c.valueStr, c.removedDateStr ?: "")
                        setTextColor(palette.onSurfaceVariant)
                        setTypeface(typeface, Typeface.ITALIC)
                    } else {
                        text = getString(R.string.unified_statistics_session_contribution_active, c.valueStr, c.habitName)
                        setTextColor(palette.onSurface)
                    }
                    textSize = 14f
                    setPadding(0, dp(4f), 0, dp(4f))
                }
                container.addView(rowView)
            }
        }
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            setPadding(0, dp(14f), 0, dp(4f))
        }
        container.addView(tv)
    }

    private fun addKeyValueRow(container: LinearLayout, key: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3f), 0, dp(3f))
        }
        val keyView = TextView(requireContext()).apply {
            text = key
            textSize = 14f
            setTextColor(palette.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
        }
        row.addView(keyView)
        row.addView(valueView)
        container.addView(row)
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
        const val TAG = "FocusSessionDetailBottomSheet"
        private const val ARG_SESSION_ID = "arg_session_id"
        private const val ARG_DATASET_ID = "arg_dataset_id"

        fun newInstance(sessionId: String, datasetId: String): FocusSessionDetailBottomSheet {
            return FocusSessionDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                    putString(ARG_DATASET_ID, datasetId)
                }
            }
        }
    }
}
