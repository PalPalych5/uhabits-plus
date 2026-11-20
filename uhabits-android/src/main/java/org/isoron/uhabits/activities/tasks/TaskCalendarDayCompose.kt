/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.util.Calendar
import java.util.Locale

@Composable
internal fun TaskCalendarPlanningDay(
    state: TaskCalendarUiState,
    locale: Locale,
    onTaskClick: (UserTask) -> Unit,
    onTaskToggleComplete: (UserTask) -> Unit,
    onTaskQuickMenu: (UserTask) -> Unit,
    onHabitClick: (PlannedHabit) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val fontScale = LocalConfiguration.current.fontScale
    val density = LocalDensity.current

    val agenda = remember(state.tasks, state.habits, state.showHabits, state.selectedDay) {
        val agendaHabits = if (state.showHabits) state.habits else emptyList()
        TaskCalendarEvents.buildAgendaForDay(state.tasks, agendaHabits, state.selectedDay)
    }

    val leftColWidth = if (fontScale >= 1.5f) 64.dp else 56.dp
    val hourSlotHeight = 56.dp
    val isToday = state.selectedDay == state.today

    val nowCal = remember(state.nowMillis) {
        Calendar.getInstance().apply { timeInMillis = state.nowMillis }
    }
    val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
    val currentMinute = nowCal.get(Calendar.MINUTE)

    val verticalScrollState = rememberScrollState()
    val hourTopOffsets = remember { mutableStateMapOf<Int, Int>() }

    LaunchedEffect(state.selectedDay, state.mode, state.scale) {
        val targetHour = (if (isToday) currentHour - 1 else 8).coerceAtLeast(0)
        withFrameNanos { }
        val fallbackPx = with(density) { (hourSlotHeight * targetHour).toPx().toInt() }
        verticalScrollState.scrollTo(hourTopOffsets[targetHour] ?: fallbackPx)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Pinned All-Day Section
        if (agenda.allDayTasks.isNotEmpty() || agenda.allDayHabits.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(colors.surfaceVariantColor)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.task_calendar_all_day),
                    modifier = Modifier
                        .semantics { heading() }
                        .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium
                    )
                )
                agenda.allDayTasks.forEach { task ->
                    CalendarTaskRow(
                        task = task,
                        containerName = task.containerId?.let { state.containers[it]?.name },
                        tags = state.taskTags[task.id].orEmpty(),
                        locale = locale,
                        colors = colors,
                        onClick = { onTaskClick(task) },
                        onToggleComplete = { onTaskToggleComplete(task) },
                        onQuickMenu = { onTaskQuickMenu(task) }
                    )
                }
                agenda.allDayHabits.forEach { habit ->
                    CalendarPlannedHabitRow(
                        habit = habit,
                        locale = locale,
                        colors = colors,
                        onClick = { onHabitClick(habit) }
                    )
                }
            }
            CalendarHairlineDivider(colors = colors)
        }

        // 2. 24-Hour Scrollable Timeline
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val bottomSpaceDp = maxOf(360.dp, maxHeight)
            val dividerColor = colors.resolveDividerColor()
            val dotPx = with(density) { 6.dp.roundToPx() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calendar_vertical_scroll")
                    .verticalScroll(verticalScrollState)
            ) {
                for (hour in 0..23) {
                    val hourTasks = agenda.timedTasks.filter { it.hour == hour }
                    val hourHabits = agenda.timedHabits.filter { it.reminderHour == hour }
                    var cellHeightPx by remember(hour) {
                        mutableIntStateOf(with(density) { hourSlotHeight.roundToPx() })
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .defaultMinSize(minHeight = hourSlotHeight)
                            .onPlaced { coords ->
                                hourTopOffsets[hour] = coords.positionInParent().y.toInt()
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .width(leftColWidth)
                                .fillMaxHeight()
                                .padding(top = 2.dp, end = 12.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            BasicText(
                                text = String.format(Locale.getDefault(), "%02d:00", hour),
                                style = TextStyle(
                                    color = colors.secondaryColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight.Medium,
                                    fontFeatureSettings = "tnum"
                                ),
                                maxLines = 1
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = hourSlotHeight)
                                .onSizeChanged { cellHeightPx = it.height }
                                .drawBehind {
                                    val stroke = 1.dp.toPx()
                                    drawLine(
                                        color = dividerColor,
                                        start = Offset(0f, stroke / 2f),
                                        end = Offset(size.width, stroke / 2f),
                                        strokeWidth = stroke
                                    )
                                }
                        ) {
                            if (hourTasks.isNotEmpty() || hourHabits.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    hourTasks.forEach { ev ->
                                        DayTimelineTaskCard(
                                            event = ev,
                                            containerName = ev.task.containerId?.let { state.containers[it]?.name },
                                            tags = state.taskTags[ev.task.id].orEmpty(),
                                            colors = colors,
                                            onClick = { onTaskClick(ev.task) },
                                            onToggleComplete = { onTaskToggleComplete(ev.task) },
                                            onQuickMenu = { onTaskQuickMenu(ev.task) }
                                        )
                                    }
                                    hourHabits.forEach { habit ->
                                        CalendarPlannedHabitRow(
                                            habit = habit,
                                            locale = locale,
                                            colors = colors,
                                            onClick = { onHabitClick(habit) }
                                        )
                                    }
                                }
                            }

                            if (isToday && hour == currentHour) {
                                val maxOffset = (cellHeightPx - dotPx).coerceAtLeast(0)
                                val topPx = ((currentMinute / 60f) * cellHeightPx).toInt().coerceIn(0, maxOffset)
                                CalendarCurrentTimeLine(
                                    colors = colors,
                                    showDot = true,
                                    modifier = Modifier.offset { IntOffset(0, topPx) }
                                )
                            }
                        }
                    }
                }

                if (!agenda.hasItems) {
                    BasicText(
                        text = stringResource(
                            if (state.showHabits) R.string.task_calendar_empty_day
                            else R.string.task_calendar_no_tasks_day
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Default,
                            textAlign = TextAlign.Center
                        )
                    )
                }

                Spacer(Modifier.height(bottomSpaceDp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayTimelineTaskCard(
    event: TaskCalendarEvent,
    containerName: String?,
    tags: List<TaskTag>,
    colors: UHabitComposeColors,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit,
    onQuickMenu: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val timeStr = String.format(Locale.getDefault(), "%02d:%02d", event.hour ?: 0, event.minute ?: 0)
    val subText = remember(containerName, tags) {
        buildString {
            if (!containerName.isNullOrBlank()) append(containerName)
            if (tags.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(tags.joinToString(" ") { "#${it.name}" })
            }
        }
    }

    val toggleLabel = stringResource(
        if (event.task.isCompleted) R.string.task_row_a11y_reopen_title else R.string.task_row_a11y_complete_title,
        event.task.title
    )
    val actionsLabel = stringResource(R.string.task_row_a11y_actions)
    val completedState = stringResource(R.string.task_row_state_completed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(colors.surfaceColor)
            .border(1.dp, colors.borderColor, shape)
            .combinedClickable(onClick = onClick, onLongClick = onQuickMenu)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(event.task.title)
                    append(", ")
                    append(timeStr)
                    if (subText.isNotBlank()) {
                        append(", ")
                        append(subText)
                    }
                }
                if (event.task.isCompleted) stateDescription = completedState
                customActions = listOf(
                    CustomAccessibilityAction(toggleLabel) {
                        onToggleComplete()
                        true
                    },
                    CustomAccessibilityAction(actionsLabel) {
                        onQuickMenu()
                        true
                    }
                )
            }
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Checkbox, onClickLabel = toggleLabel, onClick = onToggleComplete),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    if (event.task.isCompleted) R.drawable.ic_tasks_completed else R.drawable.ic_cat_activity
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (event.task.isCompleted) colors.accentColor else colors.secondaryColor),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceVariantColor)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = timeStr,
                style = TextStyle(
                    color = colors.accentColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 1
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = event.task.title,
                style = TextStyle(
                    color = if (event.task.isCompleted) colors.secondaryColor else colors.foregroundColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default,
                    textDecoration = if (event.task.isCompleted) TextDecoration.LineThrough else null
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (subText.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = subText,
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun TaskCalendarHistoryDay(
    state: TaskCalendarUiState,
    locale: Locale,
    onSessionClick: (FocusSession) -> Unit,
    onClusterClick: (List<FocusHistorySegment>) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    val fontScale = LocalConfiguration.current.fontScale
    val density = LocalDensity.current

    val summary = remember(state.focusSessions, state.selectedDay) {
        FocusHistoryProjector.buildSummaryForDay(state.focusSessions, state.selectedDay)
    }
    val dayHours = remember(state.selectedDay) {
        FocusHistoryProjector.getDayLengthHours(state.selectedDay)
    }
    val hourLabels = remember(state.selectedDay, dayHours) {
        TaskCalendarStateHelper.buildDstHourLabels(state.selectedDay, dayHours)
    }
    val clusters = remember(summary.segments) {
        TaskCalendarStateHelper.groupSegmentsIntoClusters(summary.segments)
    }

    val leftColWidth = if (fontScale >= 1.5f) 66.dp else 56.dp
    val hourSlotHeight = 56.dp
    val isToday = state.selectedDay == state.today

    val nowCal = remember(state.nowMillis) {
        Calendar.getInstance().apply { timeInMillis = state.nowMillis }
    }
    val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)

    val defaultTask = stringResource(R.string.task_calendar_target_task)
    val defaultHabit = stringResource(R.string.task_calendar_target_habit)
    val defaultContainer = stringResource(R.string.task_calendar_target_container)
    val defaultFocus = stringResource(R.string.task_calendar_target_focus)
    val tasksMap = remember(state.tasks) { state.tasks.associateBy { it.id } }
    val habitsMap = remember(state.habits) { state.habits.associateBy { it.uuid.orEmpty() } }

    val verticalScrollState = rememberScrollState()
    LaunchedEffect(state.selectedDay, state.mode, state.scale) {
        val targetHour = (if (isToday) currentHour - 1 else 8).coerceAtLeast(0)
        val scrollTargetPx = with(density) { (hourSlotHeight * targetHour).toPx().toInt() }
        verticalScrollState.scrollTo(scrollTargetPx)
    }

    val hasEstimated = summary.hasEstimatedDayAllocation
    val prefix = if (hasEstimated) "~" else ""
    val estNote = if (hasEstimated) stringResource(R.string.task_calendar_estimated_note) else ""
    val sessionsCountStr = context.resources.getQuantityString(
        R.plurals.task_calendar_sessions_count,
        summary.sessionCount,
        summary.sessionCount
    )
    val totalWorkText = stringResource(R.string.task_calendar_total_work_label) + " " +
        "$prefix${formatCalendarDurationFull(context, summary.totalWorkDurationMs)}$estNote · $sessionsCountStr"

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Pinned Day Summary Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariantColor)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            BasicText(
                text = totalWorkText,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                )
            )
            if (summary.hasOverlaps) {
                Spacer(Modifier.height(2.dp))
                val busyLabel = stringResource(R.string.task_calendar_session_envelope_label)
                BasicText(
                    text = "$busyLabel ${formatCalendarDurationFull(context, summary.mergedBusyDurationMs)}",
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Default,
                        fontFeatureSettings = "tnum"
                    )
                )
            }
        }

        CalendarHairlineDivider(colors = colors)

        // 2. Elapsed-Time Timeline (23/24/25h DST-aware)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val bottomSpaceDp = maxOf(360.dp, maxHeight)
            val dividerColor = colors.resolveDividerColor()
            val timelineHeight = hourSlotHeight * dayHours

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calendar_vertical_scroll")
                    .verticalScroll(verticalScrollState)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Left Hour Labels Column
                    Column(modifier = Modifier.width(leftColWidth)) {
                        hourLabels.forEach { label ->
                            val isDstLabel = label.contains("\n")
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(hourSlotHeight)
                                    .padding(top = 2.dp, end = 8.dp),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                BasicText(
                                    text = label,
                                    style = TextStyle(
                                        color = colors.secondaryColor,
                                        fontSize = if (isDstLabel) 9.5.sp else 11.sp,
                                        fontFamily = FontFamily.Default,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.End,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    // Right Timeline Blocks Frame
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(timelineHeight)
                            .drawBehind {
                                val stroke = 1.dp.toPx()
                                val slotPx = hourSlotHeight.toPx()
                                for (h in 0 until dayHours) {
                                    val y = h * slotPx + stroke / 2f
                                    drawLine(
                                        color = dividerColor,
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = stroke
                                    )
                                }
                            }
                    ) {
                        if (isToday) {
                            val elapsedMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                state.selectedDay,
                                state.nowMillis
                            )
                            val lineTop = (elapsedMin / 60f * 56f).dp
                            CalendarCurrentTimeLine(
                                colors = colors,
                                showDot = true,
                                modifier = Modifier.offset(y = lineTop)
                            )
                        }

                        clusters.forEach { cluster ->
                            val totalLanes = cluster.maxOf { it.totalLanes }.coerceAtLeast(1)
                            val isCrowded = totalLanes >= 4 || cluster.size >= 4

                            val clusterTopDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                state.selectedDay,
                                cluster.minOf { it.segmentStartMs },
                                cluster.any { it.isContinuationFromPrevDay },
                                false
                            ) / 60f * 56f).dp

                            val clusterBottomDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                state.selectedDay,
                                cluster.maxOf { it.segmentEndMs },
                                false,
                                cluster.any { it.continuesToNextDay }
                            ) / 60f * 56f).dp

                            if (isCrowded) {
                                val clusterHeight = maxOf(48.dp, clusterBottomDp - clusterTopDp)
                                DayCrowdedClusterBlock(
                                    cluster = cluster,
                                    locale = locale,
                                    colors = colors,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, end = 16.dp)
                                        .offset(y = clusterTopDp)
                                        .height(clusterHeight),
                                    onClick = { onClusterClick(cluster) }
                                )
                            } else {
                                val clusterHeight = maxOf(28.dp, clusterBottomDp - clusterTopDp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, end = 16.dp)
                                        .offset(y = clusterTopDp)
                                        .height(clusterHeight)
                                ) {
                                    for (lane in 0 until totalLanes) {
                                        val laneSegments = cluster.filter {
                                            it.laneIndex.coerceIn(0, totalLanes - 1) == lane
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        ) {
                                            laneSegments.forEach { seg ->
                                                val segTopDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                                    state.selectedDay,
                                                    seg.segmentStartMs,
                                                    seg.isContinuationFromPrevDay,
                                                    false
                                                ) / 60f * 56f).dp
                                                val segBottomDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                                    state.selectedDay,
                                                    seg.segmentEndMs,
                                                    false,
                                                    seg.continuesToNextDay
                                                ) / 60f * 56f).dp
                                                val blockHeight = maxOf(26.dp, segBottomDp - segTopDp)
                                                val segTitle = FocusHistoryProjector.resolveTitle(
                                                    session = seg.session,
                                                    tasksMap = tasksMap,
                                                    habitsMap = habitsMap,
                                                    containersMap = state.containers,
                                                    defaultTaskLabel = defaultTask,
                                                    defaultHabitLabel = defaultHabit,
                                                    defaultContainerLabel = defaultContainer,
                                                    defaultFocusLabel = defaultFocus
                                                )
                                                val habitColor = resolveHabitColorValue(
                                                    state.habits.find { it.uuid == seg.session.habit?.uuid }?.color,
                                                    colors
                                                )
                                                DayHistorySegmentBlock(
                                                    segment = seg,
                                                    title = segTitle,
                                                    habitColor = habitColor,
                                                    blockHeight = blockHeight,
                                                    locale = locale,
                                                    colors = colors,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 2.dp)
                                                        .offset(y = maxOf(0.dp, segTopDp - clusterTopDp))
                                                        .height(blockHeight),
                                                    onClick = { onSessionClick(seg.session) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (summary.sessionCount == 0) {
                    BasicText(
                        text = stringResource(R.string.task_calendar_no_sessions_day),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Default,
                            textAlign = TextAlign.Center
                        )
                    )
                }

                Spacer(Modifier.height(bottomSpaceDp))
            }
        }
    }
}

@Composable
private fun DayHistorySegmentBlock(
    segment: FocusHistorySegment,
    title: String,
    habitColor: Color,
    blockHeight: Dp,
    locale: Locale,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isEst = segment.isDayAllocationEstimated
    val prefix = if (isEst) "~" else ""
    val timeStr = remember(segment, locale) {
        FocusHistoryProjector.formatSegmentIntervalText(segment, locale)
    }
    val durCompact = remember(segment.segmentDurationMs, prefix, context) {
        prefix + formatCalendarDurationCompact(context, segment.segmentDurationMs)
    }
    val durLabel = durCompact + if (isEst) stringResource(R.string.task_calendar_estimated_compact_suffix) else ""
    val shape = RoundedCornerShape(6.dp)
    val bgTint = habitColor.copy(alpha = if (colors.isDark) 0.16f else 0.12f)

    Column(
        modifier = modifier
            .clip(shape)
            .background(bgTint)
            .border(1.dp, habitColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(segment.session.id.value)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $timeStr, $durCompact"
            }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = timeStr,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = colors.accentColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            BasicText(
                text = durLabel,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 1
            )
        }
        if (blockHeight >= 36.dp) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = if (blockHeight >= 56.dp) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DayCrowdedClusterBlock(
    cluster: List<FocusHistorySegment>,
    locale: Locale,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val clusterKey = remember(cluster) {
        cluster.map { it.session.id.value }.sorted().joinToString(",")
    }
    val intervalStr = remember(cluster, locale) {
        val firstSeg = cluster.minBy { it.segmentStartMs }
        val lastSeg = cluster.maxBy { it.segmentEndMs }
        val startStr = FocusHistoryProjector.formatSegmentIntervalText(firstSeg, locale).split("–")[0].trim()
        val endStr = FocusHistoryProjector.formatSegmentIntervalText(lastSeg, locale).split("–").last().trim()
        "$startStr – $endStr"
    }
    val title = stringResource(R.string.task_calendar_concurrent_sessions_title, cluster.size)
    val tapHint = stringResource(R.string.task_calendar_concurrent_sessions_tap)
    val desc = stringResource(R.string.task_calendar_concurrent_sessions_desc, cluster.size, intervalStr)
    val shape = RoundedCornerShape(8.dp)
    val bgTint = colors.accentColor.copy(alpha = if (colors.isDark) 0.16f else 0.10f)

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(bgTint)
            .border(1.5.dp, colors.accentColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("history_cluster:$clusterKey")
            .semantics(mergeDescendants = true) {
                contentDescription = desc
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_cat_activity),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.accentColor),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = "$intervalStr · $tapHint",
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicText(
            text = "▾",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = TextStyle(
                color = colors.accentColor,
                fontSize = 14.sp
            )
        )
    }
}
