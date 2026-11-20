/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.UserTask
import java.util.Calendar
import java.util.Locale

@Composable
internal fun TaskCalendarPlanningMonth(
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
    val weekdayNames = remember(locale) { TaskCalendarStateHelper.buildWeekdayAbbreviations(locale) }
    val weeks = remember(state.visibleYear, state.visibleMonth, state.selectedDay, state.today) {
        TaskCalendarStateHelper.buildMonthGridWeeks(
            year = state.visibleYear,
            month = state.visibleMonth,
            selectedDay = state.selectedDay,
            today = state.today
        )
    }

    val taskEventsByDay = remember(state.tasks) {
        TaskCalendarEvents.from(state.tasks).groupBy { it.day }
    }

    val monthHabitsByDay = remember(state.habits, state.showHabits, state.visibleYear, state.visibleMonth) {
        if (!state.showHabits) {
            emptyMap()
        } else {
            val cal = Calendar.getInstance().apply {
                clear()
                set(Calendar.YEAR, state.visibleYear)
                set(Calendar.MONTH, state.visibleMonth - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val start = LocalDate(state.visibleYear, state.visibleMonth, 1)
            val end = LocalDate(state.visibleYear, state.visibleMonth, daysInMonth)
            PlannedHabitProjector.projectForRange(state.habits, start, end).groupBy { it.date }
        }
    }

    val agenda = remember(state.tasks, state.habits, state.showHabits, state.selectedDay) {
        val agendaHabits = if (state.showHabits) state.habits else emptyList()
        TaskCalendarEvents.buildAgendaForDay(state.tasks, agendaHabits, state.selectedDay)
    }

    val dayTasks = remember(agenda) { agenda.allDayTasks + agenda.timedTasks.map { it.task } }
    val dayHabits = remember(agenda) { agenda.allDayHabits + agenda.timedHabits }
    val dayHeaderTitle = remember(state.selectedDay, locale) {
        TaskCalendarStateHelper.formatDayHeaderTitle(state.selectedDay, locale)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("calendar_vertical_scroll"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "weekday_header") {
            CalendarWeekdayHeaderRow(weekdayNames = weekdayNames, colors = colors)
        }

        items(weeks.size, key = { "planning_week_$it" }) { weekIndex ->
            val week = weeks[weekIndex]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                week.forEach { cell ->
                    val day = cell.date
                    val dayTaskList = if (day != null) taskEventsByDay[day].orEmpty() else emptyList()
                    val dayHabitList = if (day != null) monthHabitsByDay[day].orEmpty() else emptyList()
                    PlanningMonthDayCell(
                        cell = cell,
                        hasTasks = dayTaskList.isNotEmpty(),
                        firstHabitColor = dayHabitList.firstOrNull()?.color,
                        wide = state.wide,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { if (day != null) onSelectDay(day) }
                    )
                }
            }
        }

        item(key = "month_divider") {
            Spacer(Modifier.height(8.dp))
            CalendarHairlineDivider(colors = colors)
        }

        item(key = "agenda_header") {
            BasicText(
                text = dayHeaderTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        if (!agenda.hasItems) {
            item(key = "agenda_empty") {
                BasicText(
                    text = stringResource(
                        if (state.showHabits) R.string.task_calendar_empty_agenda
                        else R.string.task_calendar_no_tasks
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default
                    )
                )
            }
        } else {
            if (dayTasks.isNotEmpty()) {
                item(key = "tasks_header") {
                    CalendarSectionHeader(
                        title = stringResource(R.string.task_calendar_section_tasks),
                        count = dayTasks.size,
                        colors = colors
                    )
                }
                items(dayTasks, key = { "agenda_task:${it.id.value}" }) { task ->
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
            }

            if (dayHabits.isNotEmpty()) {
                item(key = "habits_header") {
                    CalendarSectionHeader(
                        title = stringResource(R.string.task_calendar_section_habits),
                        count = dayHabits.size,
                        colors = colors
                    )
                }
                items(dayHabits, key = { "agenda_habit:${it.habitId}:${it.date.daysSince2000}" }) { habit ->
                    CalendarPlannedHabitRow(
                        habit = habit,
                        locale = locale,
                        colors = colors,
                        onClick = { onHabitClick(habit) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanningMonthDayCell(
    cell: TaskCalendarMonthCell,
    hasTasks: Boolean,
    firstHabitColor: org.isoron.uhabits.core.models.PaletteColor?,
    wide: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val minHeight = if (wide) 80.dp else 50.dp
    val day = cell.date
    val clickMod = if (day != null) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                selected = cell.isSelected
                contentDescription = day.day.toString()
            }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .then(clickMod)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (cell.dayOfMonth != null) {
            CalendarDayNumberCircle(
                dayNumber = cell.dayOfMonth,
                isSelected = cell.isSelected,
                isToday = cell.isToday,
                wide = wide,
                colors = colors
            )

            if (hasTasks || firstHabitColor != null) {
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasTasks) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    color = if (cell.isSelected) colors.accentColor else colors.secondaryColor,
                                    shape = CircleShape
                                )
                        )
                    }
                    if (firstHabitColor != null) {
                        val habitColor = rememberResolvedHabitColor(firstHabitColor, colors)
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(color = habitColor, shape = CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TaskCalendarHistoryMonth(
    state: TaskCalendarUiState,
    locale: Locale,
    onSelectDay: (LocalDate) -> Unit,
    onSessionClick: (FocusSession) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val weekdayNames = remember(locale) { TaskCalendarStateHelper.buildWeekdayAbbreviations(locale) }
    val weeks = remember(state.visibleYear, state.visibleMonth, state.selectedDay, state.today) {
        TaskCalendarStateHelper.buildMonthGridWeeks(
            year = state.visibleYear,
            month = state.visibleMonth,
            selectedDay = state.selectedDay,
            today = state.today
        )
    }

    val monthSummariesByDay = remember(state.focusSessions, state.visibleYear, state.visibleMonth) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, state.visibleYear)
            set(Calendar.MONTH, state.visibleMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        (1..daysInMonth).associate { dayNum ->
            val date = LocalDate(state.visibleYear, state.visibleMonth, dayNum)
            date to FocusHistoryProjector.buildSummaryForDay(state.focusSessions, date)
        }
    }

    val selectedSummary = remember(state.focusSessions, state.selectedDay, monthSummariesByDay) {
        monthSummariesByDay[state.selectedDay]
            ?: FocusHistoryProjector.buildSummaryForDay(state.focusSessions, state.selectedDay)
    }

    val dayTitle = remember(state.selectedDay, locale) {
        TaskCalendarStateHelper.formatDayHeaderTitle(state.selectedDay, locale)
    }
    val historyDayHeader = stringResource(R.string.task_calendar_history_day_prefix, dayTitle)

    val defaultTask = stringResource(R.string.task_calendar_target_task)
    val defaultHabit = stringResource(R.string.task_calendar_target_habit)
    val defaultContainer = stringResource(R.string.task_calendar_target_container)
    val defaultFocus = stringResource(R.string.task_calendar_target_focus)
    val tasksMap = remember(state.tasks) { state.tasks.associateBy { it.id } }
    val habitsMap = remember(state.habits) { state.habits.associateBy { it.uuid.orEmpty() } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("calendar_vertical_scroll"),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "history_weekday_header") {
            CalendarWeekdayHeaderRow(weekdayNames = weekdayNames, colors = colors)
        }

        items(weeks.size, key = { "history_week_$it" }) { weekIndex ->
            val week = weeks[weekIndex]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                week.forEach { cell ->
                    val day = cell.date
                    val daySummary = if (day != null) monthSummariesByDay[day] else null
                    HistoryMonthDayCell(
                        cell = cell,
                        summary = daySummary,
                        wide = state.wide,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { if (day != null) onSelectDay(day) }
                    )
                }
            }
        }

        item(key = "history_month_divider") {
            Spacer(Modifier.height(8.dp))
            CalendarHairlineDivider(colors = colors)
        }

        item(key = "history_agenda_header") {
            BasicText(
                text = historyDayHeader,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        if (selectedSummary.sessionCount == 0) {
            item(key = "history_agenda_empty") {
                BasicText(
                    text = stringResource(R.string.task_calendar_no_sessions_date),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Default
                    )
                )
            }
        } else {
            item(key = "history_summary_card") {
                CalendarHistorySummaryCard(summary = selectedSummary, colors = colors)
                Spacer(Modifier.height(12.dp))
            }

            item(key = "history_sessions_header") {
                CalendarSectionHeader(
                    title = stringResource(R.string.task_calendar_section_work_sessions),
                    count = selectedSummary.segments.size,
                    colors = colors
                )
            }

            items(
                selectedSummary.segments,
                key = { "hist_seg:${it.session.id.value}:${it.segmentStartMs}" }
            ) { seg ->
                val segTitle = remember(seg.session, tasksMap, habitsMap, state.containers) {
                    FocusHistoryProjector.resolveTitle(
                        session = seg.session,
                        tasksMap = tasksMap,
                        habitsMap = habitsMap,
                        containersMap = state.containers,
                        defaultTaskLabel = defaultTask,
                        defaultHabitLabel = defaultHabit,
                        defaultContainerLabel = defaultContainer,
                        defaultFocusLabel = defaultFocus
                    )
                }
                Box(
                    modifier = Modifier.padding(
                        horizontal = if (colors.isClassicSectionLayout) 0.dp else 16.dp,
                        vertical = if (colors.isClassicSectionLayout) 0.dp else 3.dp
                    )
                ) {
                    CalendarHistorySessionCard(
                        segment = seg,
                        title = segTitle,
                        habits = state.habits,
                        locale = locale,
                        colors = colors,
                        showEstimatedPartitionNote = true,
                        tagValue = seg.session.id.value,
                        onClick = { onSessionClick(seg.session) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryMonthDayCell(
    cell: TaskCalendarMonthCell,
    summary: DayHistorySummary?,
    wide: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val minHeight = if (wide) 80.dp else 54.dp
    val day = cell.date
    val clickMod = if (day != null) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                selected = cell.isSelected
                contentDescription = day.day.toString()
            }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .then(clickMod)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (cell.dayOfMonth != null) {
            CalendarDayNumberCircle(
                dayNumber = cell.dayOfMonth,
                isSelected = cell.isSelected,
                isToday = cell.isToday,
                wide = wide,
                colors = colors
            )

            if (summary != null && summary.sessionCount > 0) {
                val isEstimated = summary.hasEstimatedDayAllocation
                val prefix = if (isEstimated) "~" else ""
                val durationLabel = remember(summary.totalWorkDurationMs, isEstimated, context) {
                    prefix + formatCalendarDurationCompact(context, summary.totalWorkDurationMs)
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(color = colors.accentColor, shape = CircleShape)
                    )
                    Spacer(Modifier.width(2.dp))
                    BasicText(
                        text = durationLabel,
                        style = TextStyle(
                            color = if (cell.isSelected) colors.accentColor else colors.secondaryColor,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
