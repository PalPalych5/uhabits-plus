/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.statistics

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitBottomSheetShell
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsGoalTypeFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter

internal data class LegacyFilterSphereOption(
    val id: Long,
    val name: String
)

internal data class LegacyStatisticsFilterDraft(
    val sphereId: Long? = null,
    val habitStatus: StatisticsHabitStatusFilter = StatisticsHabitStatusFilter.ACTIVE,
    val goalType: StatisticsGoalTypeFilter = StatisticsGoalTypeFilter.ALL
) {
    fun reset(): LegacyStatisticsFilterDraft = LegacyStatisticsFilterDraft()
}

internal fun sortSelectableFilterBlocks(blocks: List<HabitBlock>): List<LegacyFilterSphereOption> =
    blocks
        .filter { it.id != null }
        .sortedWith(compareBy<HabitBlock> { it.position }.thenBy { it.name })
        .map { LegacyFilterSphereOption(id = it.id!!, name = it.name) }

internal fun resolveLegacySelectedSphereId(
    selectedSphereId: Long?,
    sphereOptions: List<LegacyFilterSphereOption>
): Long? = selectedSphereId?.takeIf { id -> sphereOptions.any { it.id == id } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LegacyStatisticsFiltersSheetContent(
    draft: LegacyStatisticsFilterDraft,
    sphereOptions: List<LegacyFilterSphereOption>,
    spheresEnabled: Boolean,
    onSelectSphere: (Long?) -> Unit,
    onSelectStatus: (StatisticsHabitStatusFilter) -> Unit,
    onSelectGoalType: (StatisticsGoalTypeFilter) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val effectiveSphereId = resolveLegacySelectedSphereId(draft.sphereId, sphereOptions)
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp.coerceAtLeast(180.dp)

    UHabitBottomSheetShell(
        modifier = modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("statisticsFiltersSheet"),
        colors = colors,
        showDragHandle = true,
        contentPadding = PaddingValues(bottom = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.statistics_filters_title),
                color = colors.foregroundColor,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
            )

            if (spheresEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                LegacyFilterSectionLabel(
                    text = stringResource(R.string.statistics_filters_sphere),
                    colors = colors
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LegacyFilterChoiceChip(
                        label = stringResource(R.string.reports_filter_all_spheres),
                        selected = effectiveSphereId == null,
                        onClick = { onSelectSphere(null) },
                        colors = colors
                    )
                    sphereOptions.forEach { option ->
                        LegacyFilterChoiceChip(
                            label = option.name,
                            selected = effectiveSphereId == option.id,
                            onClick = { onSelectSphere(option.id) },
                            colors = colors
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LegacyFilterSectionLabel(
                text = stringResource(R.string.statistics_filters_status),
                colors = colors
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LegacyFilterChoiceChip(
                    label = stringResource(R.string.reports_filter_all_habits),
                    selected = draft.habitStatus == StatisticsHabitStatusFilter.ALL,
                    onClick = { onSelectStatus(StatisticsHabitStatusFilter.ALL) },
                    colors = colors
                )
                LegacyFilterChoiceChip(
                    label = stringResource(R.string.statistics_filters_active),
                    selected = draft.habitStatus == StatisticsHabitStatusFilter.ACTIVE,
                    onClick = { onSelectStatus(StatisticsHabitStatusFilter.ACTIVE) },
                    colors = colors
                )
                LegacyFilterChoiceChip(
                    label = stringResource(R.string.statistics_filters_archived),
                    selected = draft.habitStatus == StatisticsHabitStatusFilter.ARCHIVED,
                    onClick = { onSelectStatus(StatisticsHabitStatusFilter.ARCHIVED) },
                    colors = colors
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            LegacyFilterSectionLabel(
                text = stringResource(R.string.statistics_filters_goal_type),
                colors = colors
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LegacyFilterChoiceChip(
                    label = stringResource(R.string.statistics_filters_all_types),
                    selected = draft.goalType == StatisticsGoalTypeFilter.ALL,
                    onClick = { onSelectGoalType(StatisticsGoalTypeFilter.ALL) },
                    colors = colors
                )
                LegacyFilterChoiceChip(
                    label = stringResource(R.string.statistics_filters_boolean),
                    selected = draft.goalType == StatisticsGoalTypeFilter.YES_NO,
                    onClick = { onSelectGoalType(StatisticsGoalTypeFilter.YES_NO) },
                    colors = colors
                )
                LegacyFilterChoiceChip(
                    label = stringResource(R.string.statistics_filters_numerical),
                    selected = draft.goalType == StatisticsGoalTypeFilter.NUMERICAL,
                    onClick = { onSelectGoalType(StatisticsGoalTypeFilter.NUMERICAL) },
                    colors = colors
                )
            }
        }

        LegacyFilterActionButtons(
            onReset = onReset,
            onApply = onApply,
            colors = colors
        )
    }
}

@Composable
private fun LegacyFilterSectionLabel(
    text: String,
    colors: UHabitComposeColors
) {
    Text(
        text = text,
        color = colors.secondaryColor,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LegacyFilterChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: UHabitComposeColors
) {
    val shape = RoundedCornerShape(8.dp)
    val baseSurface = colors.dialogSurfaceColor
    val backgroundColor = if (selected) {
        lerp(baseSurface, colors.accentColor, 0.18f)
    } else {
        baseSurface
    }
    val borderColor = when {
        selected -> colors.accentColor
        colors.isPureBlack -> colors.dialogBorderColor
        else -> colors.borderColor
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = colors.foregroundColor,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LegacyFilterActionButtons(
    onReset: () -> Unit,
    onApply: () -> Unit,
    colors: UHabitComposeColors
) {
    val buttonShape = RoundedCornerShape(12.dp)
    val resetBorder = if (colors.isPureBlack) colors.dialogBorderColor else colors.borderColor
    val applyTextColor = if (ColorUtils.calculateContrast(AndroidColor.BLACK, colors.accent) >= 4.5) {
        Color.Black
    } else {
        Color.White
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("resetFiltersButton")
                .clip(buttonShape)
                .background(colors.dialogSurfaceColor)
                .border(width = 1.dp, color = resetBorder, shape = buttonShape)
                .clickable(role = Role.Button, onClick = onReset)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.statistics_filters_reset),
                color = colors.foregroundColor,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("applyFiltersButton")
                .clip(buttonShape)
                .background(colors.accentColor)
                .clickable(role = Role.Button, onClick = onApply)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.statistics_filters_apply),
                color = applyTextColor,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
