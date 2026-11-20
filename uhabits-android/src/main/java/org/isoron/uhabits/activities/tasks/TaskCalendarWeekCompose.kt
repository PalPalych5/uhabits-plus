/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.userTasks.UserTask
import java.util.Calendar
import java.util.Locale

@Composable
internal fun TaskCalendarPlanningWeek(
    state: TaskCalendarUiState,
    locale: Locale,
    onSelectDay: (LocalDate) -> Unit,
    onTaskClick: (UserTask) -> Unit,
    onTaskToggleComplete: (UserTask) -> Unit,
    onTaskQuickMenu: (UserTask) -> Unit,
    onHabitClick: (PlannedHabit) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val fontScale = LocalConfiguration.current.fontScale
    val density = LocalDensity.current

    val weekStart = remember(state.selectedDay) { state.selectedDay.startOfWeek(DayOfWeek.MONDAY) }
    val weekDays = remember(weekStart) { (0..6).map { weekStart.plus(it) } }
    val weekdayNames = remember(locale) { TaskCalendarStateHelper.buildWeekdayAbbreviations(locale) }

    val taskEvents = remember(state.tasks) { TaskCalendarEvents.from(state.tasks) }
    val weekPlannedHabits = remember(state.habits, state.showHabits, weekStart) {
        if (state.showHabits) {
            PlannedHabitProjector.projectForRange(state.habits, weekStart, weekDays.last())
        } else {
            emptyList()
        }
    }

    val timeColWidth = if (fontScale >= 1.5f) 58.dp else 48.dp
    val dayColWidthDp = 144.dp
    val baseSlotHeightDp = 56.dp
    val itemHeightDp = maxOf(56f, 56f * fontScale).dp

    val hourCounts = remember(weekDays, taskEvents, weekPlannedHabits) {
        TaskCalendarStateHelper.computePlanningWeekHourCounts(weekDays, taskEvents, weekPlannedHabits)
    }
    val hourHeights = remember(hourCounts, itemHeightDp) {
        hourCounts.map { count ->
            maxOf(baseSlotHeightDp, itemHeightDp * count + 4.dp)
        }
    }

    val hasAnyAllDay = remember(weekDays, taskEvents, weekPlannedHabits) {
        weekDays.any { d ->
            taskEvents.any { it.isAllDay && it.day == d } ||
                weekPlannedHabits.any { it.isAllDay && it.date == d }
        }
    }

    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    var hViewportPx by remember { mutableIntStateOf(0) }

    val nowCal = remember(state.nowMillis) {
        Calendar.getInstance().apply { timeInMillis = state.nowMillis }
    }
    val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
    val currentMinute = nowCal.get(Calendar.MINUTE)

    LaunchedEffect(weekStart, state.selectedDay, state.wide) {
        if (!state.wide) {
            val selectedIndex = weekDays.indexOf(state.selectedDay).coerceAtLeast(0)
            val colWidthPx = with(density) { dayColWidthDp.toPx() }
            val pad16Px = with(density) { 16.dp.toPx() }
            val dayLeftPx = (selectedIndex * colWidthPx).toInt()
            val dayRightPx = ((selectedIndex + 1) * colWidthPx).toInt()
            val targetX = (dayLeftPx - pad16Px).toInt().coerceAtLeast(0)
            if (hViewportPx <= 0 || dayLeftPx < horizontalScrollState.value || dayRightPx > horizontalScrollState.value + hViewportPx) {
                horizontalScrollState.scrollTo(targetX)
            }
        }
    }

    LaunchedEffect(weekStart, state.mode, state.scale) {
        val targetHour = (if (state.today in weekDays) currentHour - 1 else 8).coerceAtLeast(0)
        val scrollTargetPx = with(density) {
            hourHeights.take(targetHour).fold(0f) { acc, dp -> acc + dp.toPx() }.toInt()
        }
        verticalScrollState.scrollTo(scrollTargetPx)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Pinned Week Header (Dates, Weekdays, All-Day items)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(colors.surfaceColor)
        ) {
            Box(
                modifier = Modifier
                    .width(timeColWidth)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(colors.surfaceColor)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasAnyAllDay) {
                    BasicText(
                        text = stringResource(R.string.task_calendar_all_day_multiline),
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }

            WeekDayColumnsContainer(
                wide = state.wide,
                horizontalScrollState = horizontalScrollState,
                onViewportWidthMeasured = { hViewportPx = it },
                modifier = Modifier.weight(1f)
            ) {
                weekDays.forEachIndexed { index, day ->
                    val isSelected = day == state.selectedDay
                    val isToday = day == state.today
                    val dayAllDayTasks = taskEvents.filter { it.isAllDay && it.day == day }
                    val dayAllDayHabits = weekPlannedHabits.filter { it.isAllDay && it.date == day }
                    val colModifier = if (state.wide) Modifier.weight(1f) else Modifier.width(dayColWidthDp)
                    val selectedBg = if (isSelected) {
                        colors.foregroundColor.copy(alpha = if (colors.isDark) 0.10f else 0.07f)
                    } else {
                        Color.Transparent
                    }

                    Column(
                        modifier = colModifier
                            .fillMaxHeight()
                            .background(selectedBg)
                            .clickable(role = Role.Button) { onSelectDay(day) }
                            .semantics { selected = isSelected }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BasicText(
                            text = weekdayNames[index],
                            style = TextStyle(
                                color = if (isToday) colors.accentColor else colors.secondaryColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = if (isToday || isSelected) FontWeight.Medium else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            ),
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        CalendarDayNumberCircle(
                            dayNumber = day.day,
                            isSelected = isSelected,
                            isToday = isToday,
                            wide = state.wide,
                            colors = colors
                        )

                        if (hasAnyAllDay && (dayAllDayTasks.isNotEmpty() || dayAllDayHabits.isNotEmpty())) {
                            Spacer(Modifier.height(4.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                dayAllDayTasks.forEach { ev ->
                                    WeekAllDayTaskChip(
                                        task = ev.task,
                                        colors = colors,
                                        onClick = { onTaskClick(ev.task) },
                                        onToggleComplete = { onTaskToggleComplete(ev.task) }
                                    )
                                }
                                dayAllDayHabits.forEach { habit ->
                                    WeekAllDayHabitChip(
                                        habit = habit,
                                        colors = colors,
                                        onClick = { onHabitClick(habit) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        CalendarHairlineDivider(colors = colors)

        // 2. Scrollable 24-Hour Timeline with Dynamic Shared Row Heights
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val bottomSpaceDp = maxOf(360.dp, maxHeight)
            val dividerColor = colors.resolveDividerColor()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calendar_vertical_scroll")
                    .verticalScroll(verticalScrollState)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Left Time Labels Column
                    Column(modifier = Modifier.width(timeColWidth)) {
                        for (h in 0..23) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(hourHeights[h])
                                    .padding(top = 2.dp, end = 4.dp),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                BasicText(
                                    text = String.format(Locale.getDefault(), "%02d:00", h),
                                    style = TextStyle(
                                        color = colors.secondaryColor,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Default,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // 7 Day Timeline Columns
                    WeekDayColumnsContainer(
                        wide = state.wide,
                        horizontalScrollState = horizontalScrollState,
                        modifier = Modifier.weight(1f)
                    ) {
                        weekDays.forEach { day ->
                            val isToday = day == state.today
                            val isSelected = day == state.selectedDay
                            val dayTimedTasks = taskEvents.filter { it.isTimed && it.day == day }
                            val dayTimedHabits = weekPlannedHabits.filter { !it.isAllDay && it.date == day }
                            val colModifier = if (state.wide) Modifier.weight(1f) else Modifier.width(dayColWidthDp)
                            val colBg = if (isSelected) {
                                colors.foregroundColor.copy(alpha = if (colors.isDark) 0.03f else 0.025f)
                            } else {
                                Color.Transparent
                            }

                            Column(
                                modifier = colModifier
                                    .background(colBg)
                            ) {
                                for (hour in 0..23) {
                                    val cellHeight = hourHeights[hour]
                                    val hourTasks = dayTimedTasks.filter { it.hour == hour }
                                    val hourHabits = dayTimedHabits.filter { it.reminderHour == hour }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(cellHeight)
                                            .drawBehind {
                                                val stroke = 1.dp.toPx()
                                                // Top hour divider
                                                drawLine(
                                                    color = dividerColor,
                                                    start = Offset(0f, stroke / 2f),
                                                    end = Offset(size.width, stroke / 2f),
                                                    strokeWidth = stroke
                                                )
                                                // Logical end border between day columns
                                                val endX = if (layoutDirection == LayoutDirection.Rtl) {
                                                    stroke / 2f
                                                } else {
                                                    size.width - stroke / 2f
                                                }
                                                drawLine(
                                                    color = dividerColor,
                                                    start = Offset(endX, 0f),
                                                    end = Offset(endX, size.height),
                                                    strokeWidth = stroke
                                                )
                                            }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(2.dp)
                                        ) {
                                            hourTasks.forEach { ev ->
                                                CompactTimelineTaskCard(
                                                    event = ev,
                                                    colors = colors,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(itemHeightDp),
                                                    onClick = { onTaskClick(ev.task) },
                                                    onQuickMenu = { onTaskQuickMenu(ev.task) }
                                                )
                                            }
                                            hourHabits.forEach { habit ->
                                                CompactTimelineHabitCard(
                                                    habit = habit,
                                                    colors = colors,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(itemHeightDp),
                                                    onClick = { onHabitClick(habit) }
                                                )
                                            }
                                        }

                                        if (isToday && hour == currentHour) {
                                            val lineTop = cellHeight * (currentMinute / 60f)
                                            CalendarCurrentTimeLine(
                                                colors = colors,
                                                showDot = false,
                                                modifier = Modifier.offset(y = lineTop)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(bottomSpaceDp))
            }
        }
    }
}

@Composable
private fun WeekDayColumnsContainer(
    wide: Boolean,
    horizontalScrollState: ScrollState,
    modifier: Modifier = Modifier,
    onViewportWidthMeasured: ((Int) -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val rowMod = if (wide) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .then(
                if (onViewportWidthMeasured != null) {
                    Modifier.onSizeChanged { onViewportWidthMeasured(it.width) }
                } else {
                    Modifier.testTag("calendar_horizontal_scroll")
                }
            )
            .horizontalScroll(horizontalScrollState)
    }
    Row(modifier = rowMod, content = content)
}

@Composable
private fun WeekAllDayTaskChip(
    task: UserTask,
    colors: UHabitComposeColors,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit
) {
    val shape = RoundedCornerShape(4.dp)
    val toggleLabel = stringResource(
        if (task.isCompleted) R.string.task_row_a11y_reopen_title else R.string.task_row_a11y_complete_title,
        task.title
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(colors.surfaceColor)
            .border(1.dp, colors.borderColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = task.title
                customActions = listOf(
                    CustomAccessibilityAction(toggleLabel) {
                        onToggleComplete()
                        true
                    }
                )
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Checkbox, onClickLabel = toggleLabel, onClick = onToggleComplete),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    if (task.isCompleted) R.drawable.ic_tasks_completed else R.drawable.ic_cat_activity
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (task.isCompleted) colors.accentColor else colors.secondaryColor),
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.width(3.dp))
        BasicText(
            text = task.title,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = if (task.isCompleted) colors.secondaryColor else colors.foregroundColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Default,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WeekAllDayHabitChip(
    habit: PlannedHabit,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    val habitColor = rememberResolvedHabitColor(habit.color, colors)
    val shape = RoundedCornerShape(4.dp)
    val bgTint = habitColor.copy(alpha = if (colors.isDark) 0.14f else 0.09f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(bgTint)
            .border(1.dp, habitColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = habit.name }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(habitColor, CircleShape)
        )
        Spacer(Modifier.width(3.dp))
        BasicText(
            text = habit.name,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Default
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactTimelineTaskCard(
    event: TaskCalendarEvent,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onQuickMenu: () -> Unit
) {
    val shape = RoundedCornerShape(4.dp)
    val timeStr = String.format(Locale.getDefault(), "%02d:%02d", event.hour ?: 0, event.minute ?: 0)
    val actionsLabel = stringResource(R.string.task_row_a11y_actions)
    val completedState = stringResource(R.string.task_row_state_completed)

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceColor)
            .border(1.dp, colors.borderColor, shape)
            .combinedClickable(onClick = onClick, onLongClick = onQuickMenu)
            .semantics(mergeDescendants = true) {
                contentDescription = "${event.task.title}, $timeStr"
                if (event.task.isCompleted) stateDescription = completedState
                customActions = listOf(
                    CustomAccessibilityAction(actionsLabel) {
                        onQuickMenu()
                        true
                    }
                )
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center
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
        BasicText(
            text = event.task.title,
            style = TextStyle(
                color = if (event.task.isCompleted) colors.secondaryColor else colors.foregroundColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Default,
                textDecoration = if (event.task.isCompleted) TextDecoration.LineThrough else null
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactTimelineHabitCard(
    habit: PlannedHabit,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val habitColor = rememberResolvedHabitColor(habit.color, colors)
    val shape = RoundedCornerShape(4.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceColor)
            .border(1.dp, habitColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = habit.name }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(habitColor, CircleShape)
        )
        Spacer(Modifier.width(3.dp))
        BasicText(
            text = habit.name,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Default
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun TaskCalendarHistoryWeek(
    state: TaskCalendarUiState,
    locale: Locale,
    onSelectDay: (LocalDate) -> Unit,
    onSessionClick: (FocusSession) -> Unit,
    onClusterClick: (List<FocusHistorySegment>) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    val fontScale = LocalConfiguration.current.fontScale
    val density = LocalDensity.current

    val weekStart = remember(state.selectedDay) { state.selectedDay.startOfWeek(DayOfWeek.MONDAY) }
    val weekDays = remember(weekStart) { (0..6).map { weekStart.plus(it) } }
    val weekdayNames = remember(locale) { TaskCalendarStateHelper.buildWeekdayAbbreviations(locale) }
    val weekSummaries = remember(state.focusSessions, weekStart) {
        weekDays.map { FocusHistoryProjector.buildSummaryForDay(state.focusSessions, it) }
    }

    val hasDstDayInWeek = remember(weekDays) {
        weekDays.any { FocusHistoryProjector.getDayLengthHours(it) != 24 }
    }
    val maxDayHours = remember(weekDays) {
        weekDays.maxOf { FocusHistoryProjector.getDayLengthHours(it) }
    }

    val timeColWidth = if (fontScale >= 1.5f) 58.dp else 48.dp
    val dayColWidthDp = if (fontScale >= 1.5f) 118.dp else 104.dp
    val hourSlotHeight = 56.dp

    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    var hViewportPx by remember { mutableIntStateOf(0) }

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

    LaunchedEffect(weekStart, state.selectedDay, state.wide) {
        if (!state.wide) {
            val selectedIndex = weekDays.indexOf(state.selectedDay).coerceAtLeast(0)
            val colWidthPx = with(density) { dayColWidthDp.toPx() }
            val pad16Px = with(density) { 16.dp.toPx() }
            val dayLeftPx = (selectedIndex * colWidthPx).toInt()
            val dayRightPx = ((selectedIndex + 1) * colWidthPx).toInt()
            val targetX = (dayLeftPx - pad16Px).toInt().coerceAtLeast(0)
            if (hViewportPx <= 0 || dayLeftPx < horizontalScrollState.value || dayRightPx > horizontalScrollState.value + hViewportPx) {
                horizontalScrollState.scrollTo(targetX)
            }
        }
    }

    LaunchedEffect(weekStart, state.mode, state.scale) {
        val targetHour = (if (state.today in weekDays) currentHour - 1 else 8).coerceAtLeast(0)
        val scrollTargetPx = with(density) { (hourSlotHeight * targetHour).toPx().toInt() }
        verticalScrollState.scrollTo(scrollTargetPx)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Pinned Week Header (Dates, Weekdays, Daily Work Totals)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(colors.surfaceColor)
        ) {
            Box(
                modifier = Modifier
                    .width(timeColWidth)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 52.dp)
                    .background(colors.surfaceColor)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(
                        if (hasDstDayInWeek) R.string.task_calendar_history_from_midnight
                        else R.string.task_calendar_history_fact
                    ),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                )
            }

            WeekDayColumnsContainer(
                wide = state.wide,
                horizontalScrollState = horizontalScrollState,
                onViewportWidthMeasured = { hViewportPx = it },
                modifier = Modifier.weight(1f)
            ) {
                weekDays.forEachIndexed { index, day ->
                    val isSelected = day == state.selectedDay
                    val isToday = day == state.today
                    val daySummary = weekSummaries[index]
                    val colModifier = if (state.wide) Modifier.weight(1f) else Modifier.width(dayColWidthDp)
                    val selectedBg = if (isSelected) {
                        colors.foregroundColor.copy(alpha = if (colors.isDark) 0.10f else 0.07f)
                    } else {
                        Color.Transparent
                    }

                    val prefix = if (daySummary.hasEstimatedDayAllocation) "~" else ""
                    val durationText = if (daySummary.sessionCount > 0) {
                        prefix + formatCalendarDurationCompact(context, daySummary.totalWorkDurationMs)
                    } else {
                        "—"
                    }

                    Column(
                        modifier = colModifier
                            .fillMaxHeight()
                            .background(selectedBg)
                            .clickable(role = Role.Button) { onSelectDay(day) }
                            .semantics { selected = isSelected }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BasicText(
                            text = weekdayNames[index],
                            style = TextStyle(
                                color = if (isToday) colors.accentColor else colors.secondaryColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = if (isToday || isSelected) FontWeight.Medium else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            ),
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        CalendarDayNumberCircle(
                            dayNumber = day.day,
                            isSelected = isSelected,
                            isToday = isToday,
                            wide = state.wide,
                            colors = colors,
                            textSizeSp = 12f
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = durationText,
                            style = TextStyle(
                                color = if (daySummary.sessionCount > 0) colors.accentColor else colors.secondaryColor,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Default,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                fontFeatureSettings = "tnum"
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        CalendarHairlineDivider(colors = colors)

        // 2. Scrollable Elapsed-Time History Timeline
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val bottomSpaceDp = maxOf(24.dp, maxHeight / 2)
            val dividerColor = colors.resolveDividerColor()
            val totalColHeight = hourSlotHeight * maxDayHours

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calendar_vertical_scroll")
                    .verticalScroll(verticalScrollState)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Left Elapsed Time Axis
                    Column(modifier = Modifier.width(timeColWidth)) {
                        for (hour in 0 until maxDayHours) {
                            val slotLabel = if (!hasDstDayInWeek) {
                                String.format(Locale.getDefault(), "%02d:00", hour)
                            } else {
                                "+${hour}h"
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(hourSlotHeight)
                                    .padding(top = 2.dp, end = 4.dp),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                BasicText(
                                    text = slotLabel,
                                    style = TextStyle(
                                        color = colors.secondaryColor,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Default,
                                        fontWeight = FontWeight.Medium,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // 7 History Day Columns
                    WeekDayColumnsContainer(
                        wide = state.wide,
                        horizontalScrollState = horizontalScrollState,
                        modifier = Modifier.weight(1f)
                    ) {
                        weekDays.forEachIndexed { index, day ->
                            val isToday = day == state.today
                            val isSelected = day == state.selectedDay
                            val daySummary = weekSummaries[index]
                            val colModifier = if (state.wide) Modifier.weight(1f) else Modifier.width(dayColWidthDp)
                            val colBg = if (isSelected) {
                                colors.foregroundColor.copy(alpha = if (colors.isDark) 0.03f else 0.025f)
                            } else {
                                Color.Transparent
                            }

                            val clusters = remember(daySummary.segments) {
                                TaskCalendarStateHelper.groupSegmentsIntoClusters(daySummary.segments)
                            }

                            Box(
                                modifier = colModifier
                                    .height(totalColHeight)
                                    .background(colBg)
                                    .drawBehind {
                                        val stroke = 1.dp.toPx()
                                        val slotPx = hourSlotHeight.toPx()
                                        for (hour in 0 until maxDayHours) {
                                            val y = hour * slotPx + stroke / 2f
                                            drawLine(
                                                color = dividerColor,
                                                start = Offset(0f, y),
                                                end = Offset(size.width, y),
                                                strokeWidth = stroke
                                            )
                                        }
                                        val endX = if (layoutDirection == LayoutDirection.Rtl) {
                                            stroke / 2f
                                        } else {
                                            size.width - stroke / 2f
                                        }
                                        drawLine(
                                            color = dividerColor,
                                            start = Offset(endX, 0f),
                                            end = Offset(endX, size.height),
                                            strokeWidth = stroke
                                        )
                                    }
                            ) {
                                if (isToday) {
                                    val elapsedMin = FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                        day,
                                        state.nowMillis
                                    )
                                    val lineTop = (elapsedMin / 60f * 56f).dp
                                    CalendarCurrentTimeLine(
                                        colors = colors,
                                        showDot = false,
                                        modifier = Modifier.offset(y = lineTop)
                                    )
                                }

                                clusters.forEach { cluster ->
                                    val totalLanes = cluster.maxOf { it.totalLanes }.coerceAtLeast(1)
                                    val isCrowded = totalLanes >= 4 || cluster.size >= 4

                                    val clusterTopDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                        day,
                                        cluster.minOf { it.segmentStartMs },
                                        cluster.any { it.isContinuationFromPrevDay },
                                        false
                                    ) / 60f * 56f).dp

                                    val clusterBottomDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                        day,
                                        cluster.maxOf { it.segmentEndMs },
                                        false,
                                        cluster.any { it.continuesToNextDay }
                                    ) / 60f * 56f).dp

                                    if (isCrowded) {
                                        val clusterHeight = maxOf(48.dp, clusterBottomDp - clusterTopDp)
                                        WeekCrowdedClusterBlock(
                                            cluster = cluster,
                                            locale = locale,
                                            colors = colors,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 1.dp)
                                                .offset(y = clusterTopDp)
                                                .height(clusterHeight),
                                            onClick = { onClusterClick(cluster) }
                                        )
                                    } else {
                                        val clusterHeight = maxOf(22.dp, clusterBottomDp - clusterTopDp)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 1.dp)
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
                                                            day,
                                                            seg.segmentStartMs,
                                                            seg.isContinuationFromPrevDay,
                                                            false
                                                        ) / 60f * 56f).dp
                                                        val segBottomDp = (FocusHistoryProjector.calculateElapsedMinutesFromMidnight(
                                                            day,
                                                            seg.segmentEndMs,
                                                            false,
                                                            seg.continuesToNextDay
                                                        ) / 60f * 56f).dp
                                                        val blockHeight = maxOf(20.dp, segBottomDp - segTopDp)
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
                                                        WeekHistorySegmentBlock(
                                                            segment = seg,
                                                            title = segTitle,
                                                            habitColor = habitColor,
                                                            blockHeight = blockHeight,
                                                            locale = locale,
                                                            colors = colors,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 1.dp)
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
                    }
                }

                Spacer(Modifier.height(bottomSpaceDp))
            }
        }
    }
}

@Composable
private fun WeekHistorySegmentBlock(
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
    val prefix = if (segment.isDayAllocationEstimated) "~" else ""
    val intervalText = remember(segment, locale) {
        FocusHistoryProjector.formatSegmentIntervalText(segment, locale)
    }
    val durText = remember(segment.segmentDurationMs, prefix, context) {
        prefix + formatCalendarDurationCompact(context, segment.segmentDurationMs)
    }
    val shape = RoundedCornerShape(4.dp)
    val bgTint = habitColor.copy(alpha = if (colors.isDark) 0.14f else 0.10f)

    Column(
        modifier = modifier
            .clip(shape)
            .background(bgTint)
            .border(1.dp, habitColor, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(segment.session.id.value)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $intervalText, $durText"
            }
            .padding(horizontal = 3.dp, vertical = 2.dp)
    ) {
        BasicText(
            text = "$intervalText ($durText)",
            style = TextStyle(
                color = colors.accentColor,
                fontSize = 8.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (blockHeight >= 32.dp) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Default
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WeekCrowdedClusterBlock(
    cluster: List<FocusHistorySegment>,
    locale: Locale,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val clusterKey = remember(cluster) {
        cluster.map { it.session.id.value }.sorted().joinToString(",")
    }
    val intervalStr = remember(cluster, locale) {
        val firstSeg = cluster.minBy { it.segmentStartMs }
        val lastSeg = cluster.maxBy { it.segmentEndMs }
        val startStr = FocusHistoryProjector.formatSegmentIntervalText(firstSeg, locale).split("–")[0].trim()
        val endStr = FocusHistoryProjector.formatSegmentIntervalText(lastSeg, locale).split("–").last().trim()
        "$startStr–$endStr"
    }
    val btnLabel = remember(cluster.size, context) {
        context.resources.getQuantityString(R.plurals.task_calendar_cluster_sessions_btn, cluster.size, cluster.size)
    }
    val desc = stringResource(R.string.task_calendar_cluster_sessions_desc, cluster.size, intervalStr)
    val shape = RoundedCornerShape(6.dp)
    val bgTint = colors.accentColor.copy(alpha = if (colors.isDark) 0.16f else 0.10f)

    Column(
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
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BasicText(
            text = btnLabel,
            style = TextStyle(
                color = colors.accentColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        BasicText(
            text = intervalStr,
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Default,
                textAlign = TextAlign.Center,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
