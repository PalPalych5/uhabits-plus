/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitDialogShell
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.UserTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun TaskCalendarClusterDisclosureDialog(
    cluster: List<FocusHistorySegment>,
    tasks: List<UserTask>,
    habits: List<Habit>,
    containers: Map<ContainerId, OrganizationalItem>,
    locale: Locale,
    onSelectSession: (FocusSession) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    val title = stringResource(R.string.task_calendar_concurrent_sessions_title, cluster.size)

    val defaultTask = stringResource(R.string.task_calendar_target_task)
    val defaultHabit = stringResource(R.string.task_calendar_target_habit)
    val defaultContainer = stringResource(R.string.task_calendar_target_container)
    val defaultFocus = stringResource(R.string.task_calendar_target_focus)

    val tasksMap = remember(tasks) { tasks.associateBy { it.id } }
    val habitsMap = remember(habits) { habits.associateBy { it.uuid.orEmpty() } }

    Dialog(onDismissRequest = onDismiss) {
        UHabitDialogShell(
            modifier = Modifier.semantics { testTagsAsResourceId = true }
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .semantics { heading() },
                color = colors.foregroundColor,
                style = MaterialTheme.typography.titleLarge
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                cluster.forEach { seg ->
                    val segTitle = remember(seg.session, tasksMap, habitsMap, containers) {
                        FocusHistoryProjector.resolveTitle(
                            session = seg.session,
                            tasksMap = tasksMap,
                            habitsMap = habitsMap,
                            containersMap = containers,
                            defaultTaskLabel = defaultTask,
                            defaultHabitLabel = defaultHabit,
                            defaultContainerLabel = defaultContainer,
                            defaultFocusLabel = defaultFocus
                        )
                    }
                    CalendarHistorySessionCard(
                        segment = seg,
                        title = segTitle,
                        habits = habits,
                        locale = locale,
                        colors = colors,
                        showEstimatedPartitionNote = false,
                        tagValue = "history_session:${seg.session.id.value}",
                        onClick = { onSelectSession(seg.session) }
                    )
                }
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accentColor)
                ) {
                    Text(context.getString(R.string.task_calendar_dialog_close))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskCalendarSessionDetailsDialog(
    session: FocusSession,
    selectedDay: LocalDate,
    tasks: List<UserTask>,
    habits: List<Habit>,
    containers: Map<ContainerId, OrganizationalItem>,
    locale: Locale,
    onOpenTask: (UserTask) -> Unit,
    onOpenHabit: (PlannedHabit) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current

    val defaultTask = stringResource(R.string.task_calendar_target_task)
    val defaultHabit = stringResource(R.string.task_calendar_target_habit)
    val defaultContainer = stringResource(R.string.task_calendar_target_container)
    val defaultFocus = stringResource(R.string.task_calendar_target_focus)

    val spec = remember(session, selectedDay, tasks, habits, containers) {
        TaskCalendarStateHelper.buildSessionDetailSpec(
            session = session,
            selectedDay = selectedDay,
            tasks = tasks,
            habits = habits,
            containers = containers,
            defaultTaskLabel = defaultTask,
            defaultHabitLabel = defaultHabit,
            defaultContainerLabel = defaultContainer,
            defaultFocusLabel = defaultFocus
        )
    }

    val targetKindName = when (spec.targetType) {
        FocusPrimaryTargetType.TASK -> defaultTask
        FocusPrimaryTargetType.HABIT -> defaultHabit
        FocusPrimaryTargetType.CONTAINER -> defaultContainer
        null -> defaultFocus
    }

    val timeStr = remember(spec, locale) {
        val dateFormat = SimpleDateFormat("d MMMM yyyy, HH:mm", locale)
        val timeFormat = SimpleDateFormat("HH:mm", locale)
        val startPart = "${dateFormat.format(Date(session.startedAt))}${if (spec.isDstSession) " (UTC${spec.startOffset})" else ""}"
        val endBase = if (spec.sameLocalDay) {
            timeFormat.format(Date(session.endedAt))
        } else {
            dateFormat.format(Date(session.endedAt))
        }
        val endPart = "$endBase${if (spec.isDstSession) " (UTC${spec.endOffset})" else ""}"
        "$startPart – $endPart"
    }

    val durationStr = remember(session.durationMs, context) {
        formatCalendarDurationFull(context, session.durationMs)
    }
    val sourceStr = stringResource(
        if (session.source == FocusSessionSource.TIMER) R.string.task_calendar_source_timer
        else R.string.task_calendar_source_manual
    )

    val message = remember(spec, targetKindName, timeStr, durationStr, sourceStr, context) {
        buildString {
            append(context.getString(R.string.task_calendar_dialog_type_label, targetKindName)).append("\n")
            append(context.getString(R.string.task_calendar_dialog_interval_label, timeStr)).append("\n")
            append(context.getString(R.string.task_calendar_dialog_duration_label, durationStr))
            if (spec.hasPauses) {
                append(context.getString(R.string.task_calendar_dialog_pauses_note, spec.elapsedMinutes))
            }
            if (spec.isEstimatedPartition) {
                append("\n")
                append(context.getString(R.string.task_calendar_dialog_midnight_note, durationStr))
            }
            append("\n")
            append(context.getString(R.string.task_calendar_dialog_source_label, sourceStr))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        UHabitDialogShell {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = spec.title,
                    modifier = Modifier.semantics { heading() },
                    color = colors.foregroundColor,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = message,
                    color = colors.foregroundColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                val relatedTask = spec.relatedTask
                val relatedHabit = spec.relatedHabit
                if (relatedTask != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenTask(relatedTask)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.secondaryColor)
                    ) {
                        Text(stringResource(R.string.task_calendar_dialog_to_task))
                    }
                } else if (relatedHabit != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenHabit(relatedHabit)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.secondaryColor)
                    ) {
                        Text(stringResource(R.string.task_calendar_dialog_to_habit))
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accentColor)
                ) {
                    Text(stringResource(R.string.task_calendar_dialog_close))
                }
            }
        }
    }
}
