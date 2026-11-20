/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors

internal data class CycleDetailValue(
    val label: String,
    val value: String = "",
    val tag: String? = null,
    val secondary: Boolean = false,
)

internal data class CycleDetailPage<T>(
    val index: Int,
    val rows: List<T>,
    val summary: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

internal data class CycleDetailContribution(
    val label: String,
    val color: Int,
    val tag: String,
)

internal data class CycleDetailContent(
    val habitUuid: String,
    val habitName: String,
    val outcome: String,
    val outcomeTint: Int,
    val date: String,
    val target: String,
    val cadence: String,
    val goalVersion: String?,
    val progress: List<CycleDetailValue>,
    val entries: CycleDetailPage<CycleDetailValue>,
    val contributions: CycleDetailPage<CycleDetailContribution>,
)

internal sealed interface CycleDetailWarning {
    data object Missing : CycleDetailWarning
    data class Open(val uuid: String) : CycleDetailWarning
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UnifiedStatisticsCycleDetailCompose(
    content: CycleDetailContent?,
    message: String,
    warning: CycleDetailWarning?,
    onManualPage: (Int) -> Unit,
    onContributionsPage: (Int) -> Unit,
    onOpenHabit: () -> Unit,
    onDismissWarning: () -> Unit,
    onConfirmWarning: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().testTag("habitCycleDetailSheet")
            .semantics { testTagsAsResourceId = true }.navigationBarsPadding(),
    ) {
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) return@BoxWithConstraints
        Column(
            Modifier.fillMaxWidth().testTag("habitCycleDetailScroll")
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        ) {
            if (content == null) {
                CycleDetailSecondary(message, Modifier.testTag("habitCycleDetailMessage"))
            } else {
                CycleHeader(content)
                CycleSectionTitle(R.string.unified_statistics_cycle_dates_label)
                CycleValueRow(CycleDetailValue(content.date, tag = "habitCycleDatesText"))

                CycleSectionTitle(R.string.unified_statistics_cycle_goal_label)
                CycleValueRow(CycleDetailValue(content.target, content.cadence, "habitCycleTargetText"))
                content.goalVersion?.let { CycleDetailSecondary(it, Modifier.testTag("habitCycleGoalVersion")) }

                CycleSectionTitle(R.string.unified_statistics_cycle_progress_label)
                content.progress.forEach { row -> CycleValueRow(row) }

                CycleSectionTitle(R.string.unified_statistics_cycle_manual_entries)
                Column(Modifier.fillMaxWidth().testTag("habitCycleEntriesLayout")) {
                    if (content.entries.rows.isEmpty()) {
                        CycleDetailSecondary(
                            stringResource(R.string.unified_statistics_drilldown_empty),
                            Modifier.testTag("habitCycleEntriesEmpty"),
                        )
                    }
                    content.entries.rows.forEach { row -> CycleValueRow(row) }
                    CyclePagination(content.entries, "habitCycleEntries", onManualPage)
                }

                CycleSectionTitle(R.string.unified_statistics_cycle_contributions)
                Column(
                    Modifier.fillMaxWidth().testTag("habitCycleContributionsLayout"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (content.contributions.rows.isEmpty()) {
                        CycleDetailSecondary(stringResource(R.string.unified_statistics_cycle_no_contributions))
                    }
                    content.contributions.rows.forEach { entry ->
                        Text(
                            entry.label,
                            Modifier.fillMaxWidth().testTag(entry.tag)
                                .background(LocalUHabitComposeColors.current.surfaceVariantColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            color = Color(entry.color), fontSize = 13.sp, lineHeight = 17.sp,
                        )
                    }
                    CyclePagination(content.contributions, "habitCycleContributions", onContributionsPage)
                }

                CycleDetailButton(
                    stringResource(R.string.unified_statistics_cycle_open_habit),
                    Modifier.fillMaxWidth().padding(top = 16.dp).testTag("habitCycleOpenHabitButton"),
                    enabled = true, onClick = onOpenHabit,
                )
            }
        }
    }

    if (warning != null) {
        CycleWarningDialog(warning, onDismissWarning, onConfirmWarning)
    }
}

@Composable
private fun CycleHeader(content: CycleDetailContent) {
    val colors = LocalUHabitComposeColors.current
    val title: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            content.habitName, modifier.semantics { heading() },
            color = colors.foregroundColor, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold,
        )
    }
    val badge: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            content.outcome,
            modifier.testTag("habitCycleDetailOutcome")
                .background(Color(content.outcomeTint), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = colors.foregroundColor, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
        )
    }
    if (statisticsStacked) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title(Modifier.fillMaxWidth())
            badge(Modifier)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            title(Modifier.weight(1f))
            badge(Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun CycleSectionTitle(title: Int) {
    Text(
        stringResource(title), Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
        color = LocalUHabitComposeColors.current.accentColor,
        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CycleValueRow(row: CycleDetailValue) {
    val color = LocalUHabitComposeColors.current.foregroundColor
    val tagged = if (row.tag != null) Modifier.testTag(row.tag) else Modifier
    if (row.secondary) {
        CycleDetailSecondary(row.label, tagged.fillMaxWidth())
        return
    }
    val modifier = tagged.fillMaxWidth().padding(vertical = 2.dp)
    val label: @Composable (Modifier) -> Unit = { layout ->
        Text(row.label, layout, color = color, fontSize = 14.sp, lineHeight = 19.sp)
    }
    val value: @Composable (Modifier) -> Unit = { layout ->
        Text(row.value, layout, color = color, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
    }
    if (row.value.isBlank()) {
        label(modifier)
    } else if (statisticsStacked) {
        Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            label(Modifier)
            value(Modifier)
        }
    } else {
        Row(
            modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
        ) {
            label(Modifier.weight(1f))
            value(Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun CycleDetailSecondary(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(top = 2.dp, bottom = 4.dp),
        color = LocalUHabitComposeColors.current.secondaryColor, fontSize = 13.sp, lineHeight = 17.sp,
    )
}

@Composable
private fun CyclePagination(page: CycleDetailPage<*>, tag: String, onChange: (Int) -> Unit) {
    val summary = page.summary ?: return
    val pageInfo: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            summary, modifier.testTag("${tag}_page"),
            color = LocalUHabitComposeColors.current.secondaryColor,
            fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
        )
    }
    val previous = stringResource(R.string.unified_statistics_pagination_prev)
    val next = stringResource(R.string.unified_statistics_pagination_next)
    if (statisticsStacked) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            pageInfo(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CycleDetailButton(previous, Modifier.weight(1f).testTag("${tag}_previous"), page.hasPrevious) { onChange(-1) }
                CycleDetailButton(next, Modifier.weight(1f).testTag("${tag}_next"), page.hasNext) { onChange(1) }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CycleDetailButton(previous, Modifier.testTag("${tag}_previous"), page.hasPrevious) { onChange(-1) }
            pageInfo(Modifier.weight(1f))
            CycleDetailButton(next, Modifier.testTag("${tag}_next"), page.hasNext) { onChange(1) }
        }
    }
}

@Composable
private fun CycleDetailButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalUHabitComposeColors.current
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) {
        Text(
            label, color = if (enabled) colors.accentColor else colors.secondaryColor,
            fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CycleWarningDialog(warning: CycleDetailWarning, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = LocalUHabitComposeColors.current
    val missing = warning is CycleDetailWarning.Missing
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("habitCycleDetailWarningDialog").semantics { testTagsAsResourceId = true },
        title = { Text(stringResource(R.string.unified_statistics_cycle_unfiltered_warning_title)) },
        text = {
            Text(
                stringResource(if (missing) R.string.unified_statistics_cycle_habit_deleted else R.string.unified_statistics_cycle_unfiltered_warning_body),
                Modifier.testTag("habitCycleDetailWarningMessage"),
            )
        },
        confirmButton = {
            CycleDetailButton(
                stringResource(if (missing) android.R.string.ok else R.string.unified_statistics_cycle_unfiltered_warning_continue),
                Modifier.testTag("habitCycleDetailWarningConfirm"), enabled = true, onClick = if (missing) onDismiss else onConfirm,
            )
        },
        dismissButton = if (missing) null else {
            {
                CycleDetailButton(
                    stringResource(R.string.unified_statistics_cycle_unfiltered_warning_cancel),
                    Modifier.testTag("habitCycleDetailWarningCancel"), enabled = true, onClick = onDismiss,
                )
            }
        },
        containerColor = colors.dialogSurfaceColor,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        titleContentColor = colors.foregroundColor,
        textContentColor = colors.secondaryColor,
    )
}
