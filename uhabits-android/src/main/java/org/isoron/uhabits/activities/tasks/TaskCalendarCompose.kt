/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.compose.LocalUHabitDesignTokens
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.userTasks.UserTask
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TaskCalendarRootCompose(
    state: TaskCalendarUiState,
    locale: Locale,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onToday: () -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    onModeChange: (CalendarMode) -> Unit,
    onScaleChange: (CalendarScale) -> Unit,
    onTaskClick: (UserTask) -> Unit,
    onTaskToggleComplete: (UserTask) -> Unit,
    onTaskQuickMenu: (UserTask) -> Unit,
    onHabitClick: (PlannedHabit) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCluster by remember { mutableStateOf<List<FocusHistorySegment>?>(null) }
    var activeSession by remember { mutableStateOf<FocusSession?>(null) }

    LaunchedEffect(state.mode) {
        if (state.mode != CalendarMode.HISTORY) {
            activeCluster = null
            activeSession = null
        }
    }

    UHabitComposeTheme(colors = state.colors) {
        val tokens = LocalUHabitDesignTokens.current
        val config = LocalConfiguration.current
        val compactPhone = !state.wide && config.screenWidthDp >= 320 && config.fontScale <= 1.15f
        val periodTitle = remember(
            state.scale,
            state.selectedDay,
            state.visibleYear,
            state.visibleMonth,
            locale
        ) {
            TaskCalendarStateHelper.formatPeriodTitle(
                scale = state.scale,
                selectedDay = state.selectedDay,
                visibleYear = state.visibleYear,
                visibleMonth = state.visibleMonth,
                locale = locale
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(state.colors.backgroundColor)
                .semantics { testTagsAsResourceId = true }
        ) {
            TaskCalendarControlsCompose(
                colors = state.colors,
                title = periodTitle,
                today = state.selectedDay == state.today,
                compactPhone = compactPhone,
                mode = state.mode,
                scale = state.scale,
                onPrevious = onPreviousPeriod,
                onNext = onNextPeriod,
                onToday = onToday,
                onMode = onModeChange,
                onScale = onScaleChange
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = state.mode to state.scale,
                    transitionSpec = {
                        fadeIn(animationSpec = tokens.motion.fastTween()) togetherWith
                            fadeOut(animationSpec = tokens.motion.fastTween())
                    },
                    label = "calendarModeScaleTransition"
                ) { (mode, scale) ->
                    when (mode to scale) {
                        CalendarMode.PLANNING to CalendarScale.MONTH -> {
                            TaskCalendarPlanningMonth(
                                state = state,
                                locale = locale,
                                onSelectDay = onSelectDay,
                                onTaskClick = onTaskClick,
                                onTaskToggleComplete = onTaskToggleComplete,
                                onTaskQuickMenu = onTaskQuickMenu,
                                onHabitClick = onHabitClick
                            )
                        }
                        CalendarMode.PLANNING to CalendarScale.WEEK -> {
                            TaskCalendarPlanningWeek(
                                state = state,
                                locale = locale,
                                onSelectDay = onSelectDay,
                                onTaskClick = onTaskClick,
                                onTaskToggleComplete = onTaskToggleComplete,
                                onTaskQuickMenu = onTaskQuickMenu,
                                onHabitClick = onHabitClick
                            )
                        }
                        CalendarMode.PLANNING to CalendarScale.DAY -> {
                            TaskCalendarPlanningDay(
                                state = state,
                                locale = locale,
                                onTaskClick = onTaskClick,
                                onTaskToggleComplete = onTaskToggleComplete,
                                onTaskQuickMenu = onTaskQuickMenu,
                                onHabitClick = onHabitClick
                            )
                        }
                        CalendarMode.HISTORY to CalendarScale.MONTH -> {
                            TaskCalendarHistoryMonth(
                                state = state,
                                locale = locale,
                                onSelectDay = onSelectDay,
                                onSessionClick = { activeSession = it }
                            )
                        }
                        CalendarMode.HISTORY to CalendarScale.WEEK -> {
                            TaskCalendarHistoryWeek(
                                state = state,
                                locale = locale,
                                onSelectDay = onSelectDay,
                                onSessionClick = { activeSession = it },
                                onClusterClick = { activeCluster = it }
                            )
                        }
                        CalendarMode.HISTORY to CalendarScale.DAY -> {
                            TaskCalendarHistoryDay(
                                state = state,
                                locale = locale,
                                onSessionClick = { activeSession = it },
                                onClusterClick = { activeCluster = it }
                            )
                        }
                    }
                }
            }
        }

        activeCluster?.let { cluster ->
            TaskCalendarClusterDisclosureDialog(
                cluster = cluster,
                tasks = state.tasks,
                habits = state.habits,
                containers = state.containers,
                locale = locale,
                onSelectSession = { session ->
                    activeCluster = null
                    activeSession = session
                },
                onDismiss = { activeCluster = null }
            )
        }

        activeSession?.let { session ->
            TaskCalendarSessionDetailsDialog(
                session = session,
                selectedDay = state.selectedDay,
                tasks = state.tasks,
                habits = state.habits,
                containers = state.containers,
                locale = locale,
                onOpenTask = onTaskClick,
                onOpenHabit = onHabitClick,
                onDismiss = { activeSession = null }
            )
        }
    }
}
