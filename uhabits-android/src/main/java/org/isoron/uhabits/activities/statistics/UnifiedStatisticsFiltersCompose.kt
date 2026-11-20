/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors

internal data class UnifiedStatisticsScopeChoice(val id: String, val name: String)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedStatisticsFiltersCompose(
    type: String,
    containerId: String?,
    includeDescendants: Boolean,
    containers: List<UnifiedStatisticsScopeChoice>,
    failed: Boolean,
    onScope: (String, String?) -> Unit,
    onIncludeDescendants: (Boolean) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val subtree = stringResource(R.string.unified_statistics_scope_mode_subtree)
    val here = stringResource(R.string.unified_statistics_scope_mode_here)
    val all = UnifiedStatisticsFiltersBottomSheet.SCOPE_ALL
    val unassigned = UnifiedStatisticsFiltersBottomSheet.SCOPE_UNASSIGNED
    val container = UnifiedStatisticsFiltersBottomSheet.SCOPE_CONTAINER
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(colors.dialogSurfaceColor)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp)
            .semantics { testTagsAsResourceId = true },
    ) {
        Text(
            stringResource(R.string.unified_statistics_filters_title),
            Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics { heading() },
            color = colors.foregroundColor, fontSize = 18.sp, fontWeight = FontWeight.Medium,
        )
        LazyColumn(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
                .testTag("unified_scope_choices")
                .selectableGroup(),
        ) {
            item("all") {
                ScopeRadio(stringResource(R.string.unified_statistics_scope_all), type == all, "unified_scope_all") {
                    onScope(all, null)
                }
            }
            item("unassigned") {
                ScopeRadio(stringResource(R.string.unified_statistics_scope_unassigned), type == unassigned, "unified_scope_unassigned") {
                    onScope(unassigned, null)
                }
            }
            items(containers, key = { "container:${it.id}" }) { choice ->
                ScopeRadio(choice.name, type == container && containerId == choice.id, "unified_scope_container_${choice.id}") {
                    onScope(container, choice.id)
                }
            }
            item("mode_header") {
                Text("$subtree / $here", Modifier.padding(top = 12.dp, bottom = 6.dp), color = colors.secondaryColor, fontSize = 14.sp)
            }
            item("subtree") {
                ScopeRadio(subtree, includeDescendants, "unified_scope_subtree") { onIncludeDescendants(true) }
            }
            item("here") {
                ScopeRadio(here, !includeDescendants, "unified_scope_here") { onIncludeDescendants(false) }
            }
            if (failed) item("failure") {
                Text(stringResource(R.string.unified_statistics_state_failed), color = colors.foregroundColor)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp).testTag("unified_scope_reset")) {
                Text(stringResource(R.string.unified_statistics_filters_reset), color = colors.secondaryColor)
            }
            Button(
                onClick = onApply,
                modifier = Modifier.heightIn(min = 48.dp).testTag("unified_scope_apply"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentColor,
                    contentColor = if (colors.isDark) Color.Black else Color.White,
                ),
            ) {
                Text(stringResource(R.string.unified_statistics_filters_apply))
            }
        }
    }
}

@Composable
private fun ScopeRadio(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalUHabitComposeColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .selectable(selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accentColor, unselectedColor = colors.accentColor),
        )
        Text(label, color = colors.foregroundColor)
    }
}
