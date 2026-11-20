/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.tasks.FocusHistoryProjector
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.statistics.HistoricalPathParser
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.tasks.Task
import kotlinx.coroutines.CancellationException
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
    @Volatile private var generation = 0
    private var loadedRevision: String? = null
    private var detailState by mutableStateOf<DetailState>(DetailState.Loading)

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
            detailState = DetailState.Loading
        }
    }

    override fun onDestroyView() {
        pendingTask?.cancel()
        generation++
        reload = null
        detailState = DetailState.Loading
        super.onDestroyView()
    }

    private val palette: SettingsThemePalette get() = MainTabsThemeBridge.resolve(requireContext())
    private val isRussian get() = Locale.getDefault().language == "ru"

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

    private sealed interface DetailState {
        data object Loading : DetailState
        data class Unavailable(val retry: Boolean) : DetailState
        data class Ready(val data: FocusSessionDetailPayload) : DetailState
    }

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

    override fun onStart() {
        super.onStart()
        org.isoron.uhabits.activities.common.dialogs.CustomDialogs.styleBottomSheet(dialog, requireContext(), palette)
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

        val root = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = palette
                UHabitComposeTheme(UHabitComposeColors.fromPalette(colors)) {
                    DetailContent(detailState, colors, onRetry = { reload?.invoke() })
                }
            }
        }

        val id = sessionId
        if (id == null) {
            detailState = DetailState.Unavailable(retry = false)
            return root
        }
        val targetComponent = app.component

        reload = {
            pendingTask?.cancel()
            val requestGeneration = ++generation
            val requestedRevision = revision()
            loadedRevision = requestedRevision
            detailState = DetailState.Loading
            val detailTask = object : Task {
                @Volatile private var isCancelled = false
                private var payload: FocusSessionDetailPayload? = null

                override fun cancel() {
                    isCancelled = true
                }

                override fun isCanceled(): Boolean = isCancelled

                override suspend fun doInBackground() {
                    if (isCancelled || requestGeneration != generation || app.isDatasetRebuilding || app.component !== targetComponent || targetComponent.session.datasetId != datasetId) return

                    try {
                        val session = targetComponent.focusSessionRepository?.getById(FocusSessionId(id)) ?: return

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
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Missing or unreadable facts are shown with the same retry action.
                    }
                }

                override fun onPostExecute() {
                    if (isCancelled || requestGeneration != generation || !isAdded || view == null) return
                    if (app.isDatasetRebuilding || app.component !== targetComponent || app.component.session.datasetId != datasetId) {
                        dismissAllowingStateLoss()
                        return
                    }

                    if (revision() != requestedRevision) { loadedRevision = null; return }
                    detailState = payload?.let { DetailState.Ready(it) } ?: DetailState.Unavailable(retry = true)
                }
            }
            pendingTask = detailTask
            targetComponent.taskRunner.execute(detailTask)
        }
        reload?.invoke()

        return root
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun DetailContent(
        state: DetailState,
        colors: SettingsThemePalette,
        onRetry: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(colors.dialogSurface))
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp)
                .semantics { testTagsAsResourceId = true },
        ) {
            BasicText(
                text = stringResource(R.string.unified_statistics_session_detail_title),
                modifier = Modifier.semantics { heading() },
                style = TextStyle(color = Color(colors.onSurface), fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            when (state) {
                DetailState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = Color(colors.accent))
                }
                is DetailState.Unavailable -> {
                    BasicText(
                        text = stringResource(R.string.unified_statistics_domain_failed),
                        modifier = Modifier.padding(top = 12.dp),
                        style = TextStyle(color = Color(colors.onSurfaceVariant), fontSize = 14.sp),
                    )
                    if (state.retry) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.unified_statistics_state_retry))
                        }
                    }
                }
                is DetailState.Ready -> DetailFacts(state.data, colors)
            }
        }
    }

    @Composable
    private fun DetailFacts(data: FocusSessionDetailPayload, colors: SettingsThemePalette) {
        val foreground = Color(colors.onSurface)
        val secondary = Color(colors.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().testTag("session-detail:${data.session.id.value}")) {
            SectionHeading(R.string.unified_statistics_session_timing_label, foreground)
            DetailFact(if (isRussian) "Начало" else "Started", data.startedStr, foreground, secondary)
            DetailFact(if (isRussian) "Завершение" else "Ended", data.endedStr, foreground, secondary)
            DetailFact(
                stringResource(R.string.unified_statistics_session_active_duration_label),
                data.activeDurationStr, foreground, secondary,
            )
            DetailFact(
                stringResource(R.string.unified_statistics_session_elapsed_label),
                data.elapsedDurationStr, foreground, secondary,
            )
            DetailFact(if (isRussian) "Источник" else "Source", data.session.source.name, foreground, secondary)
            DetailFact("Session ID", data.session.id.value, foreground, secondary)

            if (data.isEstimatedAllocation) {
                BasicText(
                    text = stringResource(R.string.unified_statistics_session_allocation_estimated),
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = TextStyle(
                        color = Color(colors.accent), fontSize = 13.sp, fontStyle = FontStyle.Italic,
                    ),
                )
            }

            SectionHeading(R.string.unified_statistics_session_primary_target_label, foreground)
            DetailFact(data.targetTypeStr, data.targetName, foreground, secondary)
            SectionHeading(R.string.unified_statistics_session_frozen_path_label, foreground)
            BasicText(
                text = data.frozenPathStr,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = TextStyle(color = secondary, fontSize = 14.sp),
            )
            SectionHeading(R.string.unified_statistics_session_contributions_label, foreground)
            if (data.contributions.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.unified_statistics_session_no_contributions),
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = TextStyle(color = secondary, fontSize = 14.sp),
                )
            } else {
                for (contribution in data.contributions) {
                    val text = if (contribution.isRemoved) {
                        stringResource(
                            R.string.unified_statistics_session_contribution_tombstone,
                            contribution.valueStr, contribution.removedDateStr.orEmpty(),
                        )
                    } else {
                        stringResource(
                            R.string.unified_statistics_session_contribution_active,
                            contribution.valueStr, contribution.habitName,
                        )
                    }
                    val semanticTag = if (contribution.isRemoved) {
                        Modifier.testTag("unified_session_detail_tombstone")
                    } else Modifier
                    BasicText(
                        text = text,
                        modifier = semanticTag.padding(vertical = 4.dp),
                        style = TextStyle(
                            color = if (contribution.isRemoved) secondary else foreground,
                            fontSize = 14.sp,
                            fontStyle = if (contribution.isRemoved) FontStyle.Italic else FontStyle.Normal,
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionHeading(title: Int, color: Color) {
        BasicText(
            text = stringResource(title),
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() },
            style = TextStyle(color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
    }

    @Composable
    private fun DetailFact(key: String, value: String, foreground: Color, secondary: Color) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            BasicText(key, style = TextStyle(color = secondary, fontSize = 14.sp))
            BasicText(value, style = TextStyle(color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
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
