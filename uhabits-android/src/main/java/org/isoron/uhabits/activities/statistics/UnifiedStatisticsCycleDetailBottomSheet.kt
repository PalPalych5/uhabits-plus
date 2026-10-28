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

import android.app.Dialog
import android.content.DialogInterface
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
import androidx.appcompat.app.AlertDialog
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
import org.isoron.uhabits.activities.habits.show.ShowHabitActivity
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.projection.EffectiveHabitDay
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
import org.isoron.uhabits.utils.InterfaceUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed modal inspection sheet for a single HabitCycle fact (C1-C7).
 * Displays full dates, goal version, native units/target, coverage, manual entries,
 * active/revoked contributions, and provides safe entry to existing ShowHabitActivity
 * with an explicit unfiltered period warning.
 */
class UnifiedStatisticsCycleDetailBottomSheet : BottomSheetDialogFragment() {

    private var cycleKeyString: String? = null
    private var datasetId: String? = null
    private var querySignature: String? = null
    private var manualEntriesPage: Int = 0
    private var contributionsPage: Int = 0
    private var contentContainer: LinearLayout? = null

    private enum class ContributionClassification {
        ACTIVE,
        EXCLUDED,
        REVOKED,
    }

    private data class ClassifiedContributionFact(
        val date: LocalDate,
        val value: Long,
        val timestamp: Long,
        val classification: ContributionClassification,
    )

    private val palette: SettingsThemePalette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

    private fun dp(value: Float): Int = InterfaceUtils.dpToPixels(requireContext(), value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Statistics_BottomSheetDialogTheme)
        val args = savedInstanceState ?: arguments
        if (args != null) {
            cycleKeyString = args.getString(ARG_CYCLE_KEY)
            datasetId = args.getString(ARG_DATASET_ID)
            querySignature = args.getString(ARG_QUERY_SIGNATURE)
            manualEntriesPage = args.getInt(ARG_MANUAL_PAGE, 0)
            contributionsPage = args.getInt(ARG_CONTRIBUTIONS_PAGE, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_CYCLE_KEY, cycleKeyString)
        outState.putString(ARG_DATASET_ID, datasetId)
        outState.putString(ARG_QUERY_SIGNATURE, querySignature)
        outState.putInt(ARG_MANUAL_PAGE, manualEntriesPage)
        outState.putInt(ARG_CONTRIBUTIONS_PAGE, contributionsPage)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = FrameLayout(context).apply {
            tag = "habitCycleDetailSheet"
            setBackgroundColor(palette.surface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBars.bottom)
            insets
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
        }
        contentContainer = contentLayout

        populateContent(contentLayout)

        scrollView.addView(contentLayout)
        root.addView(scrollView)
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        contentContainer = null
    }

    internal fun invalidateReport(state: org.isoron.uhabits.activities.statistics.UnifiedReportUiState) {
        if (!isAdded || view == null) return
        val application = requireContext().applicationContext as HabitsApplication
        val currentReport = (parentFragment as? UnifiedStatisticsFragment)?.activeReport
        val currentQuery = currentReport?.context?.let(::statisticsQuerySignature)
        if (querySignature == null && currentQuery != null) querySignature = currentQuery
        if (currentQuery != null && querySignature != currentQuery) {
            dismissAllowingStateLoss()
            return
        }
        if (application.isDatasetRebuilding || (datasetId != null && application.component.session.datasetId != datasetId)) {
            dismissAllowingStateLoss()
            return
        }
        if (state is org.isoron.uhabits.activities.statistics.UnifiedReportUiState.Success && !state.isRefreshing) {
            contentContainer?.let {
                it.removeAllViews()
                populateContent(it)
            }
        } else if (state is org.isoron.uhabits.activities.statistics.UnifiedReportUiState.InitialLoading) {
            dismissAllowingStateLoss()
        } else {
            contentContainer?.let {
                it.removeAllViews()
                it.addView(createSecondaryInfo(requireContext(), getString(R.string.unified_statistics_state_refreshing)))
            }
        }
    }

    private fun populateContent(container: LinearLayout) {
        val context = requireContext()
        val parentFrag = parentFragment as? UnifiedStatisticsFragment
        val report = parentFrag?.activeReport

        if (report == null) {
            container.addView(TextView(context).apply {
                text = getString(R.string.unified_statistics_state_refreshing)
                setTextColor(palette.onSurfaceVariant)
            })
            return
        }

        // Dataset & query guard
        val signature = statisticsQuerySignature(report.context)
        if (querySignature == null) querySignature = signature
        if (datasetId == null) datasetId = report.context.datasetId
        if (datasetId != report.context.datasetId || querySignature != signature) {
            dismissAllowingStateLoss()
            return
        }

        val habitsResult = report.habits
        val cycle = habitsResult?.allCycles?.firstOrNull { it.key.toString() == cycleKeyString }

        if (cycle == null) {
            container.addView(TextView(context).apply {
                text = getString(R.string.unified_statistics_drilldown_empty)
                setTextColor(palette.onSurfaceVariant)
            })
            return
        }

        // 1. Header: Habit Name & Outcome badge
        val stackHeader = context.resources.configuration.let { it.screenWidthDp < 360 || it.fontScale >= 1.3f }
        val headerRow = LinearLayout(context).apply {
            orientation = if (stackHeader) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (stackHeader) Gravity.START else Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8f)
            }
        }

        val titleTv = TextView(context).apply {
            text = cycle.habitName
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onSurface)
            layoutParams = if (stackHeader) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        headerRow.addView(titleTv)

        val outcomeBadge = TextView(context).apply {
            tag = "habitCycleDetailOutcome"
            if (stackHeader) {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4f)
                }
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))

            val (badgeText, badgeColor) = resolveOutcomeBadge(cycle, report.context)
            text = badgeText
            background = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = dp(4f).toFloat()
            }
            // The translucent status tint sits over the current surface; use
            // the theme's semantic foreground so it remains legible in dark
            // and AMOLED themes as well as light mode.
            setTextColor(palette.onSurface)
        }
        headerRow.addView(outcomeBadge)
        container.addView(headerRow)

        // 2. Dates Section
        container.addView(createSectionHeader(context, getString(R.string.unified_statistics_cycle_dates_label)))
        val dateRangeText = "${formatStatisticsDate(cycle.startDate)} — ${formatStatisticsDate(cycle.endDate)}"
        val dateSubtitle = if (cycle.isContextual && cycle.contextTruncatedTo != null) {
            getString(R.string.unified_statistics_habits_ongoing_context_format, dateRangeText, cycle.contextTruncatedTo?.let(::formatStatisticsDate).orEmpty())
        } else {
            dateRangeText
        }
        container.addView(createKeyValueRow(context, dateSubtitle, "", "habitCycleDatesText"))

        // 3. Goal & Version
        container.addView(createSectionHeader(context, getString(R.string.unified_statistics_cycle_goal_label)))
        val cadenceLabel = when (cycle.cadence) {
            HabitCadence.DAILY -> getString(R.string.unified_statistics_cadence_daily)
            HabitCadence.WEEKLY -> getString(R.string.unified_statistics_cadence_weekly)
            HabitCadence.MONTHLY -> getString(R.string.unified_statistics_cadence_monthly)
            HabitCadence.UNSUPPORTED -> getString(R.string.unified_statistics_cadence_unsupported)
        }
        val targetDescription = formatTargetDescription(cycle)
        container.addView(createKeyValueRow(context, getString(R.string.unified_statistics_cycle_target_format, targetDescription), cadenceLabel, "habitCycleTargetText"))
        if (cycle.key.goalVersionKey.isNotBlank()) {
            val effectiveDate = cycle.goal.effectiveDate
            val version = if (effectiveDate == LocalDate(2000, 1, 1)) {
                if (isRussian) "Историческая дата версии цели недоступна" else "Historical goal version date unavailable"
            } else {
                val date = formatStatisticsDate(effectiveDate)
                if (isRussian) "Версия цели действует с: $date" else "Goal version effective from: $date"
            }
            container.addView(createSecondaryInfo(context, version))
        }

        // 4. Progress and Quantities breakdown
        container.addView(createSectionHeader(context, getString(R.string.unified_statistics_cycle_progress_label)))
        val q = cycle.quantity
        if (q.isNumerical) {
            val manualStr = formatNumericalValue(q.manualValue, q.unit)
            val activeContribStr = formatNumericalValue(q.activeContributionValue, q.unit)
            val total = if (cycle.outcome == HabitCycleOutcome.UNAVAILABLE) {
                getString(R.string.unified_statistics_domain_failed)
            } else {
                (if (q.hasCompleteCoverage) "" else "≥") + formatNumericalValue(q.lowerBound, q.unit)
            }
            container.addView(createKeyValueRow(context, if (isRussian) "Всего:" else "Total:", total))
            container.addView(createKeyValueRow(context, if (isRussian) "Ручные записи:" else "Manual entries:", manualStr, "habitCycleQuantityText"))
            container.addView(createKeyValueRow(context, if (isRussian) "Активные начисления:" else "Active contributions:", activeContribStr))
            if (q.excludedContributionValue > 0L) {
                val excludedContribStr = formatNumericalValue(q.excludedContributionValue, q.unit)
                container.addView(createKeyValueRow(context, if (isRussian) "Исключённые начисления:" else "Excluded contributions:", excludedContribStr))
            }
            if (q.revokedContributionValue > 0L) {
                val revokedContribStr = formatNumericalValue(q.revokedContributionValue, q.unit)
                container.addView(createKeyValueRow(context, if (isRussian) "Отозванные начисления:" else "Revoked contributions:", revokedContribStr))
            }
            val daysCoverageStr = if (isRussian) {
                "Дней с данными: ${q.knownDays} из ${q.totalDays} · пропусков (SKIP): ${q.skippedDays}"
            } else {
                "Days with data: ${q.knownDays} of ${q.totalDays} · skips: ${q.skippedDays}"
            }
            container.addView(createSecondaryInfo(context, daysCoverageStr))
        } else {
            val actionsStr = if (isRussian) "${q.booleanManualCount} действий" else "${q.booleanManualCount} actions"
            container.addView(createKeyValueRow(context, if (isRussian) "Фактические действия:" else "Actual actions:", actionsStr, "habitCycleQuantityText"))
            val coverageInfo = if (isRussian) {
                "Дней с записями: ${q.knownDays} из ${q.totalDays} · без данных: ${q.unknownDays} · SKIP: ${q.skippedDays}"
            } else {
                "Days recorded: ${q.knownDays} of ${q.totalDays} · unknown: ${q.unknownDays} · SKIP: ${q.skippedDays}"
            }
            container.addView(createSecondaryInfo(context, coverageInfo))
            val booleanQuota = cycle.goal.frequency.numerator
            if (q.booleanCapacity < booleanQuota) {
                val capStr = if (isRussian) {
                    "Доступная ёмкость после SKIP: ${q.booleanCapacity} (меньше нормы $booleanQuota)"
                } else {
                    "Available capacity after SKIP: ${q.booleanCapacity} (less than quota $booleanQuota)"
                }
                container.addView(createSecondaryInfo(context, capStr))
            }
        }

        // 5. Original manual entries from cycle (bounded pagination, no N+1, use report)
        container.addView(createSectionHeader(context, getString(R.string.unified_statistics_cycle_manual_entries)))
        val entriesLayout = LinearLayout(context).apply {
            tag = "habitCycleEntriesLayout"
            orientation = LinearLayout.VERTICAL
        }
        val days = cycle.days
        if (days.isEmpty()) {
            entriesLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_drilldown_empty)))
        } else {
            val totalManualPages = (days.size + PAGE_SIZE - 1) / PAGE_SIZE
            val currentManualPage = manualEntriesPage.coerceIn(0, maxOf(0, totalManualPages - 1))
            manualEntriesPage = currentManualPage
            val fromIndex = currentManualPage * PAGE_SIZE
            val toIndex = minOf(fromIndex + PAGE_SIZE, days.size)
            val displayedDays = days.subList(fromIndex, toIndex)

            displayedDays.forEach { day ->
                val dateStr = formatStatisticsDate(day.manualEntry.date)
                val entryDesc = when {
                    day.isSkipped -> if (isRussian) "Пропуск (SKIP)" else "Skip (SKIP)"
                    !q.isNumerical && day.manualEntry.value == Entry.YES_MANUAL -> if (isRussian) "Да (выполнено)" else "Yes"
                    !q.isNumerical && day.manualEntry.value == Entry.NO -> if (isRussian) "Нет" else "No"
                    !q.isNumerical && day.manualEntry.value == Entry.YES_AUTO -> if (isRussian) "Автоматическая отметка · не действие" else "Automatic mark · not an action"
                    q.isNumerical && day.manualValue != null -> formatNumericalValue(requireNotNull(day.manualValue), q.unit)
                    else -> if (isRussian) "Без данных" else "No data"
                }
                entriesLayout.addView(createKeyValueRow(context, dateStr, entryDesc))
            }

            if (totalManualPages > 1) {
                val paginationRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(6f)
                    }
                }
                val btnPrev = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_pagination_prev)
                    minHeight = dp(48f)
                    isEnabled = currentManualPage > 0
                    setTextColor(if (currentManualPage > 0) palette.accent else palette.onSurfaceVariant)
                    setOnClickListener {
                        manualEntriesPage = currentManualPage - 1
                        contentContainer?.let {
                            it.removeAllViews()
                            populateContent(it)
                        }
                    }
                }
                paginationRow.addView(btnPrev)

                val pageInfo = TextView(context).apply {
                    text = getString(
                        R.string.unified_statistics_pagination_page_format,
                        currentManualPage + 1,
                        totalManualPages,
                        fromIndex + 1,
                        toIndex,
                        days.size
                    )
                    textSize = 13f
                    setTextColor(palette.onSurfaceVariant)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                paginationRow.addView(pageInfo)

                val btnNext = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_pagination_next)
                    minHeight = dp(48f)
                    isEnabled = currentManualPage < totalManualPages - 1
                    setTextColor(if (currentManualPage < totalManualPages - 1) palette.accent else palette.onSurfaceVariant)
                    setOnClickListener {
                        manualEntriesPage = currentManualPage + 1
                        contentContainer?.let {
                            it.removeAllViews()
                            populateContent(it)
                        }
                    }
                }
                paginationRow.addView(btnNext)
                entriesLayout.addView(paginationRow)
            }
        }
        container.addView(entriesLayout)

        // 6. Linked contributions from ledger (classified active/excluded/revoked, bounded pagination, no N+1)
        container.addView(createSectionHeader(context, getString(R.string.unified_statistics_cycle_contributions)))
        val contributionsLayout = LinearLayout(context).apply {
            tag = "habitCycleContributionsLayout"
            orientation = LinearLayout.VERTICAL
        }

        val allContributionFacts = mutableListOf<ClassifiedContributionFact>()
        cycle.days.forEach { day ->
            day.contributions.forEach { c ->
                val classification = if (day.isSkipped || !day.includedInStatistics) {
                    ContributionClassification.EXCLUDED
                } else {
                    ContributionClassification.ACTIVE
                }
                allContributionFacts.add(
                    ClassifiedContributionFact(
                        date = day.manualEntry.date,
                        value = c.contributedValue,
                        timestamp = c.effectiveTimestamp,
                        classification = classification
                    )
                )
            }
            day.tombstonedContributions.forEach { t ->
                allContributionFacts.add(
                    ClassifiedContributionFact(
                        date = day.manualEntry.date,
                        value = t.contributedValue,
                        timestamp = t.effectiveTimestamp,
                        classification = ContributionClassification.REVOKED
                    )
                )
            }
        }

        if (allContributionFacts.isEmpty()) {
            contributionsLayout.addView(createSecondaryInfo(context, getString(R.string.unified_statistics_cycle_no_contributions)))
        } else {
            val totalContribPages = (allContributionFacts.size + PAGE_SIZE - 1) / PAGE_SIZE
            val currentContribPage = contributionsPage.coerceIn(0, maxOf(0, totalContribPages - 1))
            contributionsPage = currentContribPage
            val cFromIndex = currentContribPage * PAGE_SIZE
            val cToIndex = minOf(cFromIndex + PAGE_SIZE, allContributionFacts.size)
            val pageFacts = allContributionFacts.subList(cFromIndex, cToIndex)

            pageFacts.forEach { fact ->
                val amount = formatNumericalValue(fact.value, q.unit)
                val dateStr = formatStatisticsDate(fact.date)
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fact.timestamp))
                val fullTimestamp = "$dateStr $timeStr"
                val (label, isRevoked, isExcluded) = when (fact.classification) {
                    ContributionClassification.ACTIVE -> {
                        Triple(
                            getString(R.string.unified_statistics_cycle_contribution_active, amount, fullTimestamp),
                            false,
                            false
                        )
                    }
                    ContributionClassification.EXCLUDED -> {
                        Triple(
                            getString(R.string.unified_statistics_cycle_contribution_excluded, amount, fullTimestamp),
                            false,
                            true
                        )
                    }
                    ContributionClassification.REVOKED -> {
                        Triple(
                            getString(R.string.unified_statistics_cycle_contribution_revoked, amount, fullTimestamp),
                            true,
                            false
                        )
                    }
                }
                contributionsLayout.addView(createContributionRow(context, label, isRevoked, isExcluded))
            }

            if (totalContribPages > 1) {
                val paginationRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(6f)
                    }
                }
                val btnPrev = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_pagination_prev)
                    minHeight = dp(48f)
                    isEnabled = currentContribPage > 0
                    setTextColor(if (currentContribPage > 0) palette.accent else palette.onSurfaceVariant)
                    setOnClickListener {
                        contributionsPage = currentContribPage - 1
                        contentContainer?.let {
                            it.removeAllViews()
                            populateContent(it)
                        }
                    }
                }
                paginationRow.addView(btnPrev)

                val pageInfo = TextView(context).apply {
                    text = getString(
                        R.string.unified_statistics_pagination_page_format,
                        currentContribPage + 1,
                        totalContribPages,
                        cFromIndex + 1,
                        cToIndex,
                        allContributionFacts.size
                    )
                    textSize = 13f
                    setTextColor(palette.onSurfaceVariant)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                paginationRow.addView(pageInfo)

                val btnNext = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.unified_statistics_pagination_next)
                    minHeight = dp(48f)
                    isEnabled = currentContribPage < totalContribPages - 1
                    setTextColor(if (currentContribPage < totalContribPages - 1) palette.accent else palette.onSurfaceVariant)
                    setOnClickListener {
                        contributionsPage = currentContribPage + 1
                        contentContainer?.let {
                            it.removeAllViews()
                            populateContent(it)
                        }
                    }
                }
                paginationRow.addView(btnNext)
                contributionsLayout.addView(paginationRow)
            }
        }
        container.addView(contributionsLayout)

        // 7. Action: Open habit with unfiltered warning
        val btnOpenHabit = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            tag = "habitCycleOpenHabitButton"
            text = getString(R.string.unified_statistics_cycle_open_habit)
            setTextColor(palette.accent)
            minHeight = dp(48f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16f)
            }
            setOnClickListener {
                showUnfilteredWarningAndLaunch(cycle.habitUuid)
            }
        }
        container.addView(btnOpenHabit)
    }

    private fun showUnfilteredWarningAndLaunch(habitUuid: String) {
        val context = requireContext()
        val app = context.applicationContext as HabitsApplication
        val habit = app.component.habitList.getByUUID(habitUuid)

        if (habit == null) {
            AlertDialog.Builder(context)
                .setTitle(getString(R.string.unified_statistics_cycle_unfiltered_warning_title))
                .setMessage(getString(R.string.unified_statistics_cycle_habit_deleted))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(getString(R.string.unified_statistics_cycle_unfiltered_warning_title))
            .setMessage(getString(R.string.unified_statistics_cycle_unfiltered_warning_body))
            .setPositiveButton(getString(R.string.unified_statistics_cycle_unfiltered_warning_continue)) { _, _ ->
                val freshHabit = app.component.habitList.getByUUID(habitUuid)
                if (freshHabit != null) {
                    val intent = app.component.intentFactory.startShowHabitActivity(context, freshHabit)
                    startActivity(intent)
                }
            }
            .setNegativeButton(getString(R.string.unified_statistics_cycle_unfiltered_warning_cancel), null)
            .create()
        dialog.show()
        dialog.window?.decorView?.tag = "habitCycleDetailWarningDialog"
    }

    private fun resolveOutcomeBadge(cycle: HabitCycle, query: org.isoron.uhabits.core.statistics.StatisticsQueryContext): Triple<String, Int, Int> {
        val q = cycle.quantity
        return when {
            isHistoricalCycleSlice(cycle, query) -> {
                Triple(getString(R.string.unified_statistics_habits_historical_cycle_status), 0x229E9E9E, 0xFF757575.toInt())
            }
            cycle.lifecycle == HabitCycleLifecycle.ONGOING -> {
                if (cycle.outcome == HabitCycleOutcome.SATISFIED) {
                    Triple(getString(R.string.unified_statistics_habits_ongoing_reached), 0x224CAF50, 0xFF4CAF50.toInt())
                } else {
                    Triple(getString(R.string.unified_statistics_habits_ongoing_in_progress), 0x222196F3, 0xFF2196F3.toInt())
                }
            }
            cycle.lifecycle == HabitCycleLifecycle.PARTIAL -> {
                Triple(getString(R.string.unified_statistics_cycle_lifecycle_partial), 0x22FF9800, 0xFFFF9800.toInt())
            }
            cycle.lifecycle == HabitCycleLifecycle.TRANSITION -> {
                Triple(getString(R.string.unified_statistics_cycle_lifecycle_transition), 0x22FF9800, 0xFFFF9800.toInt())
            }
            cycle.outcome == HabitCycleOutcome.SATISFIED -> {
                Triple(getString(R.string.unified_statistics_cycle_outcome_satisfied), 0x224CAF50, 0xFF4CAF50.toInt())
            }
            cycle.outcome == HabitCycleOutcome.NOT_SATISFIED -> {
                Triple(getString(R.string.unified_statistics_cycle_outcome_not_satisfied), 0x22E53935, 0xFFE53935.toInt())
            }
            cycle.outcome == HabitCycleOutcome.UNKNOWN -> {
                Triple(getString(R.string.unified_statistics_cycle_outcome_unknown), 0x229E9E9E, 0xFF757575.toInt())
            }
            cycle.outcome == HabitCycleOutcome.EXEMPT -> {
                Triple(getString(R.string.unified_statistics_cycle_outcome_exempt), 0x229E9E9E, 0xFF757575.toInt())
            }
            cycle.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED -> {
                Triple(getString(R.string.unified_statistics_cycle_outcome_capacity_excluded), 0x22FF9800, 0xFFFF9800.toInt())
            }
            else -> {
                Triple(getString(R.string.unified_statistics_cycle_outcome_unavailable), 0x229E9E9E, 0xFF757575.toInt())
            }
        }
    }

    private fun formatTargetDescription(cycle: HabitCycle): String {
        val goal = cycle.goal
        val isNumerical = cycle.quantity.isNumerical
        return if (isNumerical) {
            if (!goal.targetValue.isFinite()) return getString(R.string.unified_statistics_domain_failed)
            val number = java.math.BigDecimal.valueOf(goal.targetValue).stripTrailingZeros().toPlainString()
            val targetVal = if (goal.unit.isBlank()) number else "$number ${goal.unit}"
            "${if (goal.targetType == NumericalHabitType.AT_MOST) "≤" else "≥"} $targetVal"
        } else {
            val quota = goal.frequency.numerator
            when (cycle.cadence) {
                HabitCadence.DAILY -> if (isRussian) "1 раз в день" else "1 time/day"
                HabitCadence.WEEKLY -> if (isRussian) "≥$quota раз в неделю" else "≥$quota times/week"
                HabitCadence.MONTHLY -> if (isRussian) "≥$quota раз в месяц" else "≥$quota times/month"
                else -> "≥$quota"
            }
        }
    }

    private fun formatNumericalValue(nativeVal: Long, unit: String): String {
        val decimalVal = java.math.BigDecimal.valueOf(nativeVal, 3).stripTrailingZeros()
        val plainStr = decimalVal.toPlainString()
        return if (unit.isNotBlank()) "$plainStr $unit" else plainStr
    }

    private fun createSectionHeader(context: android.content.Context, text: String): View {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accent)
            setPadding(0, dp(12f), 0, dp(4f))
        }
    }

    private fun createKeyValueRow(
        context: android.content.Context,
        key: String,
        value: String,
        viewTag: String? = null
    ): View {
        return LinearLayout(context).apply {
            if (viewTag != null) tag = viewTag
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2f), 0, dp(2f))
            addView(TextView(context).apply {
                if (viewTag != null) tag = viewTag
                text = key
                textSize = 14f
                setTextColor(palette.onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (value.isNotBlank()) {
                addView(TextView(context).apply {
                    text = value
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.onSurface)
                })
            }
        }
    }

    private fun createSecondaryInfo(context: android.content.Context, text: String): View {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(2f), 0, dp(4f))
        }
    }

    private fun createContributionRow(
        context: android.content.Context,
        text: String,
        isRevoked: Boolean,
        isExcluded: Boolean = false,
    ): View {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            val textColor = when {
                isRevoked -> 0xFFE53935.toInt()
                isExcluded -> 0xFFFF9800.toInt()
                else -> palette.onSurface
            }
            setTextColor(textColor)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(4f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4f)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 25
        private const val ARG_CYCLE_KEY = "arg_cycle_key"
        private const val ARG_DATASET_ID = "arg_dataset_id"
        private const val ARG_QUERY_SIGNATURE = "arg_query_signature"
        private const val ARG_MANUAL_PAGE = "arg_manual_page"
        private const val ARG_CONTRIBUTIONS_PAGE = "arg_contributions_page"

        fun show(
            fragmentManager: FragmentManager,
            cycleKeyString: String,
            datasetId: String? = null,
            querySignature: String? = null,
        ) {
            val sheet = UnifiedStatisticsCycleDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CYCLE_KEY, cycleKeyString)
                    putString(ARG_DATASET_ID, datasetId)
                    putString(ARG_QUERY_SIGNATURE, querySignature)
                }
            }
            sheet.show(fragmentManager, "UnifiedStatisticsCycleDetailBottomSheet")
        }
    }
}
