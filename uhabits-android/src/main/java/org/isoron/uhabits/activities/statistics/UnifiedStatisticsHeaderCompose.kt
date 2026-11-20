/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors

internal data class UnifiedStatisticsHeaderState(
    val colors: UHabitComposeColors,
    val tab: UnifiedAnalyticsTab,
    val period: UnifiedPeriod,
    val periodLabel: String,
    val scopeLabel: String,
    val canGoNext: Boolean,
    val status: String?,
    val loading: Boolean,
)

internal fun UnifiedPeriod.labelRes(): Int = when (this) {
    UnifiedPeriod.DAY -> R.string.unified_statistics_period_day
    UnifiedPeriod.WEEK -> R.string.unified_statistics_period_week
    UnifiedPeriod.MONTH -> R.string.unified_statistics_period_month
    UnifiedPeriod.YEAR -> R.string.unified_statistics_period_year
    UnifiedPeriod.ALL -> R.string.unified_statistics_period_all
}

private fun UnifiedAnalyticsTab.labelRes(): Int = when (this) {
    UnifiedAnalyticsTab.OVERVIEW -> R.string.unified_statistics_tab_overview
    UnifiedAnalyticsTab.HABITS -> R.string.unified_statistics_tab_habits
    UnifiedAnalyticsTab.TASKS -> R.string.unified_statistics_tab_tasks
    UnifiedAnalyticsTab.FOCUS -> R.string.unified_statistics_tab_focus
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UnifiedStatisticsHeaderCompose(
    state: UnifiedStatisticsHeaderState,
    pillSurface: Color,
    pillBorder: Color,
    onTab: (UnifiedAnalyticsTab) -> Unit,
    onPeriod: (UnifiedPeriod) -> Unit,
    onScope: () -> Unit,
    onNavigate: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // MainActivity's weighted host may probe the fragment with no width constraint.
        if (!constraints.hasBoundedWidth) return@BoxWithConstraints
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
            val selectedTabRequester = remember { BringIntoViewRequester() }
            LaunchedEffect(state.tab) {
                selectedTabRequester.bringIntoView()
            }
            Row(
                Modifier
                    .testTag("unified_tabs")
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                UnifiedAnalyticsTab.entries.forEach { tab ->
                    val active = tab == state.tab
                    Box(
                        Modifier
                            .then(if (active) Modifier.bringIntoViewRequester(selectedTabRequester) else Modifier)
                            .heightIn(min = 48.dp)
                            .selectable(selected = active, role = Role.Tab, onClick = { onTab(tab) })
                            .testTag("unified_tab_${tab.name}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(tab.labelRes()),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (active) colors.accentColor else colors.secondaryColor,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        if (active) Box(
                            Modifier.align(Alignment.BottomCenter).matchParentSize(),
                        ) {
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(colors.accentColor))
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.resolveDividerColor())

            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                val stacked = maxWidth < 364.dp || LocalConfiguration.current.fontScale >= 1.3f
                val dateControls: @Composable (Modifier) -> Unit = { modifier ->
                    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                        if (state.period != UnifiedPeriod.ALL) PeriodArrow(previous = true, enabled = true) { onNavigate(-1) }
                        PeriodDropdown(state, pillSurface, pillBorder, Modifier.weight(1f), onPeriod)
                        if (state.period != UnifiedPeriod.ALL) PeriodArrow(previous = false, enabled = state.canGoNext) { onNavigate(1) }
                    }
                }
                val scope: @Composable (Modifier) -> Unit = { modifier ->
                    HeaderPill(state.scopeLabel, "unified_scope", pillSurface, pillBorder, modifier, onClick = onScope)
                }
                if (stacked) {
                    Column {
                        dateControls(Modifier.fillMaxWidth())
                        scope(Modifier.padding(start = 4.dp))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        dateControls(Modifier.weight(1f))
                        scope(Modifier.widthIn(max = 148.dp))
                    }
                }
            }
            if (colors.isClassicSectionLayout) HorizontalDivider(color = colors.resolveDividerColor())
            state.status?.let { status ->
                Row(
                    Modifier.fillMaxWidth().testTag("unified_status")
                        .background(colors.accentColor.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(status, Modifier.weight(1f), fontSize = 13.sp, color = colors.foregroundColor)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.unified_statistics_state_retry)) }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp).size(40.dp).testTag("unified_loading"),
                    color = colors.accentColor,
                )
            }
        }
    }
}

@Composable
private fun PeriodArrow(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(if (previous) "unified_prev" else "unified_next"),
    ) {
        Icon(
            painterResource(if (previous) R.drawable.ic_arrow_up_dark else R.drawable.ic_arrow_down_dark),
            contentDescription = stringResource(if (previous) R.string.unified_statistics_prev_period else R.string.unified_statistics_next_period),
            modifier = Modifier.size(24.dp).rotate(if (rtl) 90f else -90f),
            tint = LocalUHabitComposeColors.current.secondaryColor.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PeriodDropdown(
    state: UnifiedStatisticsHeaderState,
    surface: Color,
    border: Color,
    modifier: Modifier,
    onPeriod: (UnifiedPeriod) -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        HeaderPill(
            state.periodLabel, "unified_period", surface, border, Modifier.fillMaxWidth(), true,
        ) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = colors.dialogSurfaceColor,
        ) {
            UnifiedPeriod.entries.forEach { period ->
                DropdownMenuItem(
                    text = {
                        Text(stringResource(period.labelRes()), color = if (period == state.period) colors.accentColor else colors.foregroundColor)
                    },
                    onClick = { expanded = false; onPeriod(period) },
                    modifier = Modifier.testTag("unified_period_${period.name}")
                        .semantics { selected = period == state.period },
                )
            }
        }
    }
}

@Composable
private fun HeaderPill(
    label: String,
    tag: String,
    surface: Color,
    border: Color,
    modifier: Modifier = Modifier,
    dropdown: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.testTag(tag).widthIn(min = 48.dp).heightIn(min = 48.dp).clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.then(if (dropdown) Modifier.fillMaxWidth() else Modifier)
                .clip(shape).background(surface).border(1.dp, border, shape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                Modifier.weight(1f, fill = dropdown).testTag(if (dropdown) "unified_date_range" else "unified_scope_label"),
                color = colors.foregroundColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (dropdown) Text("▾", color = colors.secondaryColor, fontSize = 11.sp)
        }
    }
}
