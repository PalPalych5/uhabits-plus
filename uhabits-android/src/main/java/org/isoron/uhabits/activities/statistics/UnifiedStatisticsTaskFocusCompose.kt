/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.LocalUHabitComposeColors

@Composable
internal fun UnifiedStatisticsTaskMetric(
    count: Int,
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalUHabitComposeColors.current
    val actionModifier = if (onClick != null) {
        modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else modifier
    Column(if (tag != null && onClick != null) actionModifier.testTag(tag) else actionModifier) {
        Text(
            count.toString(),
            if (tag != null && onClick == null) Modifier.testTag(tag) else Modifier,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (danger) colorResource(R.color.statistics_goal_missed) else colors.foregroundColor,
        )
        Text(label, fontSize = 12.sp, color = colors.secondaryColor)
    }
}

@Composable
internal fun UnifiedStatisticsCohortCard(
    title: String,
    total: Int,
    breakdown: String,
    chartTag: String,
    segments: List<StatisticsCohortSegment>,
    palette: SettingsThemePalette,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSegment: (StatisticsCohortSegment) -> Unit,
) {
    UnifiedStatisticsCard(Modifier.testTag("${chartTag}_card"), enabled, onOpen) {
        if (statisticsStacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UnifiedStatisticsTitle(title)
                UnifiedStatisticsBadge(total.toString())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                UnifiedStatisticsTitle(title, Modifier.weight(1f))
                UnifiedStatisticsBadge(total.toString())
            }
        }
        Column(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
            UnifiedStatisticsCohortBar(chartTag, segments, total, palette, enabled, onSegment)
        }
        UnifiedStatisticsSecondary(breakdown)
    }
}

@Composable
internal fun UnifiedStatisticsFocusHeadline(duration: String, count: String) {
    val colors = LocalUHabitComposeColors.current
    val durationText: @Composable (Modifier) -> Unit = { modifier ->
        Text(duration, modifier.testTag("unified_focus_f1"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colors.foregroundColor)
    }
    val countTag = Modifier.testTag("unified_focus_f2")
    if (statisticsStacked) {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            durationText(Modifier)
            UnifiedStatisticsBadge(count, countTag, primary = true)
        }
    } else {
        Row(
            Modifier.padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            durationText(Modifier.weight(1f))
            UnifiedStatisticsBadge(count, countTag, primary = true)
        }
    }
}

@Composable
internal fun UnifiedStatisticsFocusBreakdownRow(
    tag: String,
    title: String,
    value: String,
    enabled: Boolean,
    emphasizeTitle: Boolean = false,
    onClick: () -> Unit,
    progress: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().testTag(tag).heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        UnifiedStatisticsValueRow(title, value, emphasizeTitle = emphasizeTitle)
        progress()
    }
}

@Composable
internal fun UnifiedStatisticsReportDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = LocalUHabitComposeColors.current.resolveDividerColor())
}
