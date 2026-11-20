/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.statistics.HabitCadence
import org.isoron.uhabits.core.statistics.HabitCycle
import org.isoron.uhabits.core.statistics.HabitCycleLifecycle
import org.isoron.uhabits.core.statistics.HabitCycleOutcome
import org.isoron.uhabits.core.statistics.StatisticsQueryContext
import org.isoron.uhabits.core.statistics.UnifiedStatisticsReport
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
    private var content by mutableStateOf<CycleDetailContent?>(null)
    private var message by mutableStateOf("")
    private var warning by mutableStateOf<CycleDetailWarning?>(null)

    private enum class ContributionClassification {
        ACTIVE,
        EXCLUDED,
        REVOKED,
    }

    private data class ClassifiedContributionFact(
        val id: String,
        val date: LocalDate,
        val value: Long,
        val timestamp: Long,
        val classification: ContributionClassification,
    )

    private val palette: SettingsThemePalette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

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
                org.isoron.uhabits.activities.common.dialogs.CustomDialogs.styleBottomSheet(dialog, requireContext(), palette)
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
        savedInstanceState: Bundle?,
    ): View {
        populateContent()
        val colors = UHabitComposeColors.fromPalette(palette)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors) {
                    UnifiedStatisticsCycleDetailCompose(
                        content = content,
                        message = message,
                        warning = warning,
                        onManualPage = { offset ->
                            manualEntriesPage += offset
                            populateContent()
                        },
                        onContributionsPage = { offset ->
                            contributionsPage += offset
                            populateContent()
                        },
                        onOpenHabit = ::requestOpenHabit,
                        onDismissWarning = { warning = null },
                        onConfirmWarning = ::confirmOpenHabit,
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        content = null
        warning = null
        super.onDestroyView()
    }

    internal fun invalidateReport(state: UnifiedReportUiState) {
        if (!isAdded || view == null) return
        val report = (parentFragment as? UnifiedStatisticsFragment)?.activeReport
        if (!acceptsDatasetAndQuery(report) || state is UnifiedReportUiState.InitialLoading) {
            warning = null
            dismissAllowingStateLoss()
        } else if (state is UnifiedReportUiState.Success && !state.isRefreshing) {
            populateContent()
        } else {
            showMessage(R.string.unified_statistics_state_refreshing)
        }
    }

    private fun acceptsDatasetAndQuery(report: UnifiedStatisticsReport?): Boolean {
        val app = requireContext().applicationContext as HabitsApplication
        if (app.isDatasetRebuilding || (datasetId != null && datasetId != app.component.session.datasetId)) return false
        if (report == null) return true
        val signature = statisticsQuerySignature(report.context)
        if (querySignature == null) querySignature = signature
        if (datasetId == null) datasetId = report.context.datasetId
        return datasetId == report.context.datasetId && querySignature == signature
    }

    private fun showMessage(stringId: Int) {
        content = null
        message = getString(stringId)
    }

    private fun populateContent() {
        val parent = parentFragment as? UnifiedStatisticsFragment
        val report = parent?.activeReport
        if (!acceptsDatasetAndQuery(report)) {
            warning = null
            dismissAllowingStateLoss()
            return
        }
        if (parent == null || report == null || parent.isRefreshingState) {
            showMessage(R.string.unified_statistics_state_refreshing)
            return
        }
        val cycle = report.habits?.allCycles?.firstOrNull { it.key.toString() == cycleKeyString }
        if (cycle == null) {
            showMessage(R.string.unified_statistics_drilldown_empty)
            return
        }

        val dateRange = "${formatStatisticsDate(cycle.startDate)} — ${formatStatisticsDate(cycle.endDate)}"
        val date = if (cycle.isContextual && cycle.contextTruncatedTo != null) {
            getString(R.string.unified_statistics_habits_ongoing_context_format, dateRange,
                cycle.contextTruncatedTo?.let(::formatStatisticsDate).orEmpty())
        } else dateRange
        val cadence = getString(when (cycle.cadence) {
            HabitCadence.DAILY -> R.string.unified_statistics_cadence_daily
            HabitCadence.WEEKLY -> R.string.unified_statistics_cadence_weekly
            HabitCadence.MONTHLY -> R.string.unified_statistics_cadence_monthly
            HabitCadence.UNSUPPORTED -> R.string.unified_statistics_cadence_unsupported
        })
        val version = cycle.goal.effectiveDate.takeIf { cycle.key.goalVersionKey.isNotBlank() }?.let { effectiveDate ->
            if (effectiveDate == LocalDate(2000, 1, 1)) {
                if (isRussian) "Историческая дата версии цели недоступна" else "Historical goal version date unavailable"
            } else {
                val effective = formatStatisticsDate(effectiveDate)
                if (isRussian) "Версия цели действует с: $effective" else "Goal version effective from: $effective"
            }
        }
        val (outcome, tint) = resolveOutcomeBadge(cycle, report.context)

        val q = cycle.quantity
        val entries = pageOf(cycle.days, manualEntriesPage) { day ->
            val dateStr = formatStatisticsDate(day.manualEntry.date)
            val entryDesc = when {
                day.isSkipped -> if (isRussian) "Пропуск (SKIP)" else "Skip (SKIP)"
                !q.isNumerical && day.manualEntry.value == Entry.YES_MANUAL -> if (isRussian) "Да (выполнено)" else "Yes"
                !q.isNumerical && day.manualEntry.value == Entry.NO -> if (isRussian) "Нет" else "No"
                !q.isNumerical && day.manualEntry.value == Entry.YES_AUTO -> if (isRussian) "Автоматическая отметка · не действие" else "Automatic mark · not an action"
                q.isNumerical && day.manualValue != null -> formatNumericalValue(requireNotNull(day.manualValue), q.unit)
                else -> if (isRussian) "Без данных" else "No data"
            }
            CycleDetailValue(dateStr, entryDesc, "habitCycleManualEntry:${day.manualEntry.date.daysSince2000}")
        }
        manualEntriesPage = entries.index

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
                        id = c.id.value,
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
                        id = t.id.value,
                        date = day.manualEntry.date,
                        value = t.contributedValue,
                        timestamp = t.effectiveTimestamp,
                        classification = ContributionClassification.REVOKED
                    )
                )
            }
        }
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val contributions = pageOf(allContributionFacts, contributionsPage) { fact ->
            val amount = formatNumericalValue(fact.value, cycle.quantity.unit)
            val timestamp = "${formatStatisticsDate(fact.date)} ${timeFormat.format(Date(fact.timestamp))}"
            val (labelRes, tintColor) = when (fact.classification) {
                ContributionClassification.ACTIVE -> R.string.unified_statistics_cycle_contribution_active to palette.onSurface
                ContributionClassification.EXCLUDED -> R.string.unified_statistics_cycle_contribution_excluded to 0xFFFF9800.toInt()
                ContributionClassification.REVOKED -> R.string.unified_statistics_cycle_contribution_revoked to 0xFFE53935.toInt()
            }
            val classification = fact.classification.name.lowercase(Locale.ROOT)
            CycleDetailContribution(getString(labelRes, amount, timestamp), tintColor, "habitCycleContribution:$classification:${fact.id}")
        }
        contributionsPage = contributions.index

        content = CycleDetailContent(
            habitUuid = cycle.habitUuid,
            habitName = cycle.habitName,
            outcome = outcome,
            outcomeTint = tint,
            date = date,
            target = getString(R.string.unified_statistics_cycle_target_format, formatTargetDescription(cycle)),
            cadence = cadence,
            goalVersion = version,
            progress = progressRows(cycle),
            entries = entries,
            contributions = contributions,
        )
    }

    private fun progressRows(cycle: HabitCycle): List<CycleDetailValue> {
        val rows = mutableListOf<CycleDetailValue>()
        val q = cycle.quantity
        if (q.isNumerical) {
            val manualStr = formatNumericalValue(q.manualValue, q.unit)
            val activeContribStr = formatNumericalValue(q.activeContributionValue, q.unit)
            val total = if (cycle.outcome == HabitCycleOutcome.UNAVAILABLE) {
                getString(R.string.unified_statistics_domain_failed)
            } else {
                (if (q.hasCompleteCoverage) "" else "≥") + formatNumericalValue(q.lowerBound, q.unit)
            }
            rows.add(CycleDetailValue(if (isRussian) "Всего:" else "Total:", total))
            rows.add(CycleDetailValue(if (isRussian) "Ручные записи:" else "Manual entries:", manualStr, "habitCycleQuantityText"))
            rows.add(CycleDetailValue(if (isRussian) "Активные начисления:" else "Active contributions:", activeContribStr))
            if (q.excludedContributionValue > 0L) {
                val excludedContribStr = formatNumericalValue(q.excludedContributionValue, q.unit)
                rows.add(CycleDetailValue(if (isRussian) "Исключённые начисления:" else "Excluded contributions:", excludedContribStr))
            }
            if (q.revokedContributionValue > 0L) {
                val revokedContribStr = formatNumericalValue(q.revokedContributionValue, q.unit)
                rows.add(CycleDetailValue(if (isRussian) "Отозванные начисления:" else "Revoked contributions:", revokedContribStr))
            }
            val daysCoverageStr = if (isRussian) {
                "Дней с данными: ${q.knownDays} из ${q.totalDays} · пропусков (SKIP): ${q.skippedDays}"
            } else {
                "Days with data: ${q.knownDays} of ${q.totalDays} · skips: ${q.skippedDays}"
            }
            rows.add(CycleDetailValue(daysCoverageStr, secondary = true))
        } else {
            val actionsStr = if (isRussian) "${q.booleanManualCount} действий" else "${q.booleanManualCount} actions"
            rows.add(CycleDetailValue(if (isRussian) "Фактические действия:" else "Actual actions:", actionsStr, "habitCycleQuantityText"))
            val coverageInfo = if (isRussian) {
                "Дней с записями: ${q.knownDays} из ${q.totalDays} · без данных: ${q.unknownDays} · SKIP: ${q.skippedDays}"
            } else {
                "Days recorded: ${q.knownDays} of ${q.totalDays} · unknown: ${q.unknownDays} · SKIP: ${q.skippedDays}"
            }
            rows.add(CycleDetailValue(coverageInfo, secondary = true))
            val booleanQuota = cycle.goal.frequency.numerator
            if (q.booleanCapacity < booleanQuota) {
                val capStr = if (isRussian) {
                    "Доступная ёмкость после SKIP: ${q.booleanCapacity} (меньше нормы $booleanQuota)"
                } else {
                    "Available capacity after SKIP: ${q.booleanCapacity} (less than quota $booleanQuota)"
                }
                rows.add(CycleDetailValue(capStr, secondary = true))
            }
        }
        return rows
    }

    private fun <T, R> pageOf(items: List<T>, requestedPage: Int, transform: (T) -> R): CycleDetailPage<R> {
        val pageCount = (items.size + PAGE_SIZE - 1) / PAGE_SIZE
        val index = requestedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val from = index * PAGE_SIZE
        val to = minOf(from + PAGE_SIZE, items.size)
        return CycleDetailPage(
            index = index,
            rows = items.subList(from, to).map(transform),
            summary = if (pageCount > 1) getString(
                R.string.unified_statistics_pagination_page_format, index + 1, pageCount, from + 1, to, items.size,
            ) else null,
            hasPrevious = index > 0,
            hasNext = index + 1 < pageCount,
        )
    }

    private fun requestOpenHabit() {
        val uuid = content?.habitUuid ?: return
        val parent = parentFragment as? UnifiedStatisticsFragment ?: return
        if (parent.isRefreshingState || !acceptsDatasetAndQuery(parent.activeReport)) return
        val app = requireContext().applicationContext as HabitsApplication
        warning = if (app.component.habitList.getByUUID(uuid) == null) CycleDetailWarning.Missing else CycleDetailWarning.Open(uuid)
    }

    private fun confirmOpenHabit() {
        val pending = warning
        warning = null
        if (pending !is CycleDetailWarning.Open) return
        val parent = parentFragment as? UnifiedStatisticsFragment ?: return
        if (!acceptsDatasetAndQuery(parent.activeReport)) return
        val context = requireContext()
        val app = context.applicationContext as HabitsApplication
        val freshHabit = app.component.habitList.getByUUID(pending.uuid) ?: return
        startActivity(app.component.intentFactory.startShowHabitActivity(context, freshHabit))
    }

    private fun resolveOutcomeBadge(cycle: HabitCycle, query: StatisticsQueryContext): Pair<String, Int> {
        return when {
            isHistoricalCycleSlice(cycle, query) -> {
                Pair(getString(R.string.unified_statistics_habits_historical_cycle_status), 0x229E9E9E)
            }
            cycle.lifecycle == HabitCycleLifecycle.ONGOING -> {
                if (cycle.outcome == HabitCycleOutcome.SATISFIED) {
                    Pair(getString(R.string.unified_statistics_habits_ongoing_reached), 0x224CAF50)
                } else {
                    Pair(getString(R.string.unified_statistics_habits_ongoing_in_progress), 0x222196F3)
                }
            }
            cycle.lifecycle == HabitCycleLifecycle.PARTIAL -> {
                Pair(getString(R.string.unified_statistics_cycle_lifecycle_partial), 0x22FF9800)
            }
            cycle.lifecycle == HabitCycleLifecycle.TRANSITION -> {
                Pair(getString(R.string.unified_statistics_cycle_lifecycle_transition), 0x22FF9800)
            }
            cycle.outcome == HabitCycleOutcome.SATISFIED -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_satisfied), 0x224CAF50)
            }
            cycle.outcome == HabitCycleOutcome.NOT_SATISFIED -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_not_satisfied), 0x22E53935)
            }
            cycle.outcome == HabitCycleOutcome.UNKNOWN -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_unknown), 0x229E9E9E)
            }
            cycle.outcome == HabitCycleOutcome.EXEMPT -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_exempt), 0x229E9E9E)
            }
            cycle.outcome == HabitCycleOutcome.CAPACITY_EXCLUDED -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_capacity_excluded), 0x22FF9800)
            }
            else -> {
                Pair(getString(R.string.unified_statistics_cycle_outcome_unavailable), 0x229E9E9E)
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
