/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.util.Calendar
import java.util.Locale

class TaskCalendarViewController(
    private val context: Context,
    private var palette: SettingsThemePalette,
    private val wide: Boolean,
    private val onTaskClicked: (UserTask) -> Unit,
    private val onTaskToggleComplete: (UserTask) -> Unit,
    private val onTaskQuickMenu: (UserTask) -> Unit,
    private val onHabitClicked: (PlannedHabit) -> Unit,
    private val onDateChanged: (LocalDate) -> Unit,
    private val onScaleChanged: (CalendarScale) -> Unit,
    private val onModeChanged: (CalendarMode) -> Unit = {},
    private val onShowHabitsChanged: ((Boolean) -> Unit)? = null
) {
    val calendarPreferences = TaskCalendarPreferences(context)
    var showHabits: Boolean = calendarPreferences.showHabits
        set(value) {
            if (field != value) {
                field = value
                calendarPreferences.showHabits = value
            }
        }

    var selectedDay: LocalDate = getToday()
        set(value) {
            field = value
            calendarMonth.set(Calendar.YEAR, value.year)
            calendarMonth.set(Calendar.MONTH, value.month - 1)
            calendarMonth.set(Calendar.DAY_OF_MONTH, 1)
        }

    var scale: CalendarScale = CalendarScale.MONTH
    var mode: CalendarMode = CalendarMode.PLANNING

    var calendarMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

    private var tasks: List<UserTask> = emptyList()
    private var habits: List<Habit> = emptyList()
    private var containers: Map<ContainerId, OrganizationalItem> = emptyMap()
    private var taskTags: Map<TaskId, List<TaskTag>> = emptyMap()
    private var focusSessions: List<FocusSession> = emptyList()

    private val composeState = mutableStateOf<TaskCalendarUiState?>(null)

    private val locale: Locale
        get() = TaskDueDisplayFormatter.selectedLocale(context)

    fun updatePalette(newPalette: SettingsThemePalette) {
        palette = newPalette
    }

    fun updateData(
        newTasks: List<UserTask>,
        newHabits: List<Habit>,
        newContainers: Map<ContainerId, OrganizationalItem>,
        newTaskTags: Map<TaskId, List<TaskTag>>,
        newFocusSessions: List<FocusSession> = focusSessions
    ) {
        tasks = newTasks
        habits = newHabits
        containers = newContainers
        taskTags = newTaskTags
        focusSessions = newFocusSessions
    }

    fun render() {
        composeState.value = buildUiState()
    }

    private fun buildUiState(): TaskCalendarUiState {
        return TaskCalendarUiState(
            colors = UHabitComposeColors.fromPalette(palette),
            wide = wide,
            selectedDay = selectedDay,
            today = getToday(),
            visibleYear = calendarMonth.get(Calendar.YEAR),
            visibleMonth = calendarMonth.get(Calendar.MONTH) + 1,
            scale = scale,
            mode = mode,
            showHabits = showHabits,
            tasks = tasks,
            habits = habits,
            containers = containers,
            taskTags = taskTags,
            focusSessions = focusSessions
        )
    }

    @Composable
    internal fun Content() {
        val state = composeState.value ?: return
        TaskCalendarRootCompose(
            state = state,
            locale = locale,
            onPreviousPeriod = { navigatePeriod(-1) },
            onNextPeriod = { navigatePeriod(1) },
            onToday = {
                selectedDay = getToday()
                onDateChanged(selectedDay)
                render()
            },
            onSelectDay = { day ->
                if (selectedDay != day) {
                    selectedDay = day
                    onDateChanged(selectedDay)
                    render()
                }
            },
            onModeChange = { nextMode ->
                if (mode != nextMode) {
                    mode = nextMode
                    onModeChanged(mode)
                    render()
                }
            },
            onScaleChange = { nextScale ->
                if (scale != nextScale) {
                    scale = nextScale
                    onScaleChanged(scale)
                    render()
                }
            },
            onTaskClick = onTaskClicked,
            onTaskToggleComplete = onTaskToggleComplete,
            onTaskQuickMenu = onTaskQuickMenu,
            onHabitClick = onHabitClicked
        )
    }

    private fun navigatePeriod(delta: Int) {
        val (nextDay, nextYear, nextMonth) = TaskCalendarStateHelper.computePeriodNavigation(
            scale = scale,
            selectedDay = selectedDay,
            visibleYear = calendarMonth.get(Calendar.YEAR),
            visibleMonth = calendarMonth.get(Calendar.MONTH) + 1,
            delta = delta
        )
        selectedDay = nextDay
        calendarMonth.set(Calendar.YEAR, nextYear)
        calendarMonth.set(Calendar.MONTH, nextMonth - 1)
        calendarMonth.set(Calendar.DAY_OF_MONTH, 1)
        onDateChanged(selectedDay)
        render()
    }
}
