/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitSegmentedPillTab
import org.isoron.uhabits.core.statistics.HabitCadence

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedStatisticsCadences(
    cadences: List<HabitCadence>,
    active: HabitCadence,
    onSelect: (HabitCadence) -> Unit,
) {
    if (cadences.size < 2) return
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        cadences.forEach { cadence ->
            val (label, tag) = when (cadence) {
                HabitCadence.DAILY -> R.string.unified_statistics_cadence_daily to "habitCadenceTabDaily"
                HabitCadence.WEEKLY -> R.string.unified_statistics_cadence_weekly to "habitCadenceTabWeekly"
                HabitCadence.MONTHLY -> R.string.unified_statistics_cadence_monthly to "habitCadenceTabMonthly"
                HabitCadence.UNSUPPORTED -> R.string.unified_statistics_cadence_unsupported to "habitCadenceTabUnsupported"
            }
            UHabitSegmentedPillTab(
                stringResource(label), cadence == active, onClick = { onSelect(cadence) },
                modifier = Modifier.testTag(tag).heightIn(min = 48.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedStatisticsCadenceHeadline(
    tag: String,
    headline: String,
    fraction: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Text(headline, fontSize = if (fraction == null) 16.sp else 28.sp, fontWeight = FontWeight.Bold,
                color = if (fraction == null) colors.secondaryColor else colors.foregroundColor)
            if (fraction != null) UnifiedStatisticsSecondary(fraction, Modifier.padding(bottom = 3.dp))
        }
        Text(
            if (LocalLayoutDirection.current == LayoutDirection.Ltr) "›" else "‹",
            Modifier.clearAndSetSemantics {}, fontSize = 20.sp, color = colors.secondaryColor,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedStatisticsHabitLegend(satisfied: Int, missed: Int) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendItem(colorResource(R.color.statistics_goal_satisfied), stringResource(R.string.unified_statistics_legend_done, satisfied))
        if (missed > 0) LegendItem(colorResource(R.color.statistics_goal_missed), stringResource(R.string.unified_statistics_legend_missed, missed))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        UnifiedStatisticsSecondary(label)
    }
}

@Composable
internal fun UnifiedStatisticsHabitProgressRow(
    model: StatisticsRedesignModels.HabitProgressRowModel,
    palette: SettingsThemePalette,
    tone: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val neutral = model.tone == StatisticsRedesignModels.ProgressTone.NEUTRAL
    val status: @Composable (Modifier) -> Unit = { modifier ->
        Text(model.statusLabel, modifier, color = if (neutral) colors.secondaryColor else tone,
            fontSize = 12.5.sp, fontWeight = if (neutral) FontWeight.Normal else FontWeight.Bold)
    }
    val date = model.dateRangeLabel.takeIf { model.showDateRange && it.isNotBlank() }
    Column(
        Modifier.fillMaxWidth().testTag(model.cycleKey).heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        UnifiedStatisticsValueRow(model.habitName, model.headlineValue, emphasizeTitle = true, valueColor = colors.foregroundColor)
        UnifiedStatisticsProgress(model.progressRatio, tone, palette, Modifier.padding(vertical = 6.dp), model.isAtMost)
        if (statisticsStacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                status(Modifier)
                if (date != null) UnifiedStatisticsSecondary(date)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                status(Modifier.weight(1f))
                if (date != null) UnifiedStatisticsSecondary(date, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
internal fun UnifiedStatisticsHabitActions(
    count: Int,
    enabled: Boolean,
    pillSurface: Color,
    pillBorder: Color,
    onAll: () -> Unit,
    onCompare: () -> Unit,
) {
    UnifiedStatisticsCard(Modifier.testTag("habitAlphaPreviewList")) {
        val allLabel = if (count > 0) stringResource(R.string.unified_statistics_habits_all_button, count)
            else stringResource(R.string.unified_statistics_habits_all_button_no_count)
        val compare: @Composable (Modifier) -> Unit = { modifier ->
            val shape = RoundedCornerShape(16.dp)
            Box(
                modifier.testTag("habitCompareEntry").heightIn(min = 48.dp)
                    .clip(shape).background(pillSurface).border(1.dp, pillBorder, shape)
                    .clickable(role = Role.Button, onClick = onCompare).padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.unified_statistics_action_compare_habits), fontSize = 13.sp,
                    color = LocalUHabitComposeColors.current.accentColor)
            }
        }
        if (statisticsStacked) {
            UnifiedStatisticsReportAction(allLabel, enabled, onAll, Modifier.testTag("habitAllHabitsButton"))
            compare(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                UnifiedStatisticsReportAction(allLabel, enabled, onAll, Modifier.weight(1f).testTag("habitAllHabitsButton"))
                compare(Modifier.weight(1f, fill = false))
            }
        }
    }
}
