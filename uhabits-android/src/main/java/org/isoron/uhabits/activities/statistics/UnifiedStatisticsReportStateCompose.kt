/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitActionChip
import org.isoron.uhabits.compose.components.UHabitSectionCard

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UnifiedStatisticsReportMessageCompose(
    message: String?,
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalUHabitComposeColors.current
    Column(
        Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true },
    ) {
        message?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().testTag("unified_report_message")
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                color = colors.secondaryColor,
            )
        }
        onRetry?.let {
            TextButton(
                onClick = it,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unified_retry"),
            ) {
                Text(stringResource(R.string.unified_statistics_state_retry), color = colors.accentColor)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UnifiedStatisticsTasksEmptyCompose(
    state: StatisticsRedesignModels.TasksEmptyStateModel,
    onResetScope: () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    UHabitSectionCard(
        modifier = Modifier
            .padding(top = if (!colors.isClassicSectionLayout && colors.showCardBorders) 8.dp else 0.dp)
            .testTag("unified_tasks_empty_card")
            .semantics { testTagsAsResourceId = true },
        cornerRadiusDp = colors.statisticsCardCornerRadiusDp,
        contentPadding = PaddingValues(18.dp),
    ) {
        Text(
            state.title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = colors.foregroundColor, fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            state.subtitle, Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center,
            color = colors.secondaryColor, fontSize = 13.sp,
        )
        if (state.showResetScopeButton) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                UHabitActionChip(
                    stringResource(R.string.unified_statistics_scope_all),
                    onClick = onResetScope,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("unified_tasks_reset_scope"),
                )
            }
        }
    }
}
