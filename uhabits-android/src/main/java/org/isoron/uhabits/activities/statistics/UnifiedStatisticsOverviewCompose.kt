/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.compose.components.UHabitSegmentedPillTab

internal enum class UnifiedStatisticsOverviewMetric(val label: Int) {
    HABITS(R.string.unified_statistics_dynamics_metric_habits),
    TASKS(R.string.unified_statistics_dynamics_metric_tasks),
    FOCUS(R.string.unified_statistics_dynamics_metric_focus),
}

internal data class UnifiedStatisticsOverviewState(
    val pulse: StatisticsRedesignModels.OverviewPulseModel,
    val dailyOngoing: Boolean,
    val hasTasks: Boolean,
    val hasFocus: Boolean,
    val dailySatisfied: Int,
    val dailyMissed: Int,
    val metrics: List<UnifiedStatisticsOverviewMetric>,
    val selectedMetric: UnifiedStatisticsOverviewMetric,
    val canOpenHabitBreakdown: Boolean,
    val emptyTasksLabel: String,
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedStatisticsOverviewCompose(
    state: UnifiedStatisticsOverviewState,
    successColor: Color,
    dangerColor: Color,
    onTab: (UnifiedAnalyticsTab) -> Unit,
    onMetric: (UnifiedStatisticsOverviewMetric) -> Unit,
    chart: @Composable () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val pulse = state.pulse
    val configuration = LocalConfiguration.current
    val stacked = configuration.screenWidthDp < 360 || configuration.fontScale >= 1.3f
    val separated = !colors.isClassicSectionLayout && colors.showCardBorders
    val divider = colors.resolveDividerColor()
    val borderlessDivider = if (!colors.isClassicSectionLayout && !colors.showCardBorders) {
        Modifier.drawWithContent {
            drawContent()
            val position = size.height - 0.5.dp.toPx()
            drawLine(divider, Offset(0f, position), Offset(size.width, position), strokeWidth = 1.dp.toPx())
        }
    } else Modifier
    Column(
        Modifier.fillMaxWidth().testTag("habitOverviewLayout")
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(if (separated) 10.dp else 0.dp),
    ) {
        UHabitSectionCard(
            modifier = borderlessDivider.testTag("overviewCadenceGroup"),
            cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = state.canOpenHabitBreakdown, role = Role.Button) { onTab(UnifiedAnalyticsTab.HABITS) }
                    .testTag("habitCadenceDailyRow"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.unified_statistics_pulse_daily_label), fontSize = 12.sp, color = colors.secondaryColor)
                    if (pulse.dailyCompletionPercent != null && pulse.dailyFractionLabel != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.Bottom,
                        ) {
                            Text("${pulse.dailyCompletionPercent}%", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.foregroundColor)
                            Text(pulse.dailyFractionLabel, Modifier.padding(bottom = 4.dp), fontSize = 13.sp, color = colors.secondaryColor)
                        }
                    } else {
                        Text(
                            stringResource(if (state.dailyOngoing) R.string.unified_statistics_habits_ongoing_in_progress else R.string.unified_statistics_pulse_daily_empty),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryColor,
                        )
                    }
                }
                Text("›", Modifier.padding(start = 8.dp), fontSize = 20.sp, color = colors.secondaryColor)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 10.dp), color = colors.resolveDividerColor())
            val tasksMeta = when {
                !state.hasTasks -> null
                pulse.tasksOverdueCount > 0 -> stringResource(R.string.unified_statistics_pulse_tasks_overdue, pulse.tasksOverdueCount)
                pulse.tasksCreatedCount > 0 -> stringResource(R.string.unified_statistics_pulse_tasks_created, pulse.tasksCreatedCount)
                else -> state.emptyTasksLabel
            }
            val focusMeta = if (state.hasFocus && !pulse.focusHasOverflow) {
                listOfNotNull(
                    stringResource(R.string.unified_statistics_sessions_pill, pulse.focusSessionCount),
                    pulse.focusAvgPerDayFormatted,
                ).joinToString(" · ")
            } else null
            val focusValue = when {
                pulse.focusHasOverflow -> stringResource(R.string.unified_statistics_overflow)
                state.hasFocus -> pulse.focusDurationFormatted ?: "0"
                else -> "—"
            }
            val tasks: @Composable (Modifier) -> Unit = { modifier ->
                OverviewStat(
                    modifier.testTag("overviewTasksCard"),
                    stringResource(R.string.unified_statistics_overview_tasks_title),
                    if (state.hasTasks) pulse.tasksCompletedCount.toString() else "—",
                    tasksMeta,
                    if (pulse.tasksOverdueCount > 0) dangerColor else colors.secondaryColor,
                    valueTag = "overviewTasksValue",
                    onClick = { onTab(UnifiedAnalyticsTab.TASKS) },
                )
            }
            val focus: @Composable (Modifier) -> Unit = { modifier ->
                OverviewStat(
                    modifier.testTag("overviewFocusCard"),
                    stringResource(R.string.unified_statistics_overview_focus_title),
                    focusValue,
                    focusMeta,
                    colors.secondaryColor,
                    valueTag = "overviewFocusValue",
                    detailTag = "overview_focus_f2",
                    onClick = { onTab(UnifiedAnalyticsTab.FOCUS) },
                )
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tasks(Modifier.fillMaxWidth())
                    focus(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    tasks(Modifier.weight(1f))
                    focus(Modifier.weight(1f))
                }
            }
        }

        if (state.metrics.isNotEmpty()) {
            UHabitSectionCard(
                modifier = borderlessDivider.testTag("overviewDynamicsGroup"),
                cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(R.string.unified_statistics_overview_dynamics_title),
                    Modifier.padding(bottom = 8.dp),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.foregroundColor,
                )
                if (state.metrics.size > 1) {
                    FlowRow(
                        Modifier.selectableGroup().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.metrics.forEach { metric ->
                            UHabitSegmentedPillTab(
                                text = stringResource(metric.label),
                                selected = metric == state.selectedMetric,
                                onClick = { onMetric(metric) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("overviewMetric_${metric.name}"),
                            )
                        }
                    }
                }
                chart()
                if (state.selectedMetric == UnifiedStatisticsOverviewMetric.HABITS) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OverviewLegend(successColor, stringResource(R.string.unified_statistics_legend_done, state.dailySatisfied))
                        if (state.dailyMissed > 0) {
                            OverviewLegend(dangerColor, stringResource(R.string.unified_statistics_legend_missed, state.dailyMissed))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStat(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String?,
    detailColor: Color,
    valueTag: String,
    detailTag: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    Column(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)) {
        Text(title, fontSize = 12.sp, color = colors.secondaryColor)
        Text(value, Modifier.testTag(valueTag).padding(top = 2.dp, bottom = 1.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.foregroundColor)
        detail?.let { Text(it, if (detailTag == null) Modifier else Modifier.testTag(detailTag), fontSize = 12.sp, color = detailColor) }
    }
}

@Composable
private fun OverviewLegend(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, fontSize = 12.sp, color = LocalUHabitComposeColors.current.secondaryColor)
    }
}
