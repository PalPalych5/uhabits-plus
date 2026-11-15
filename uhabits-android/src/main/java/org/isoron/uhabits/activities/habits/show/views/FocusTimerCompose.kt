/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.show.views

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertHealth
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.PomodoroDurations
import org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitSegmentedPillTab
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionSnapshot
import org.isoron.uhabits.core.userTasks.TaskId

/** Immutable UI state projection from the authoritative TimerSessionManager. */
data class TimerUiState(
    val mode: TimerMode = TimerMode.STOPWATCH,
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    val isRunning: Boolean = false,
    val isSessionActive: Boolean = false,
    val hasElapsed: Boolean = false,
    val elapsedMillis: Long = 0L,
    val displayMillis: Long = 0L,
    val isOvertime: Boolean = false,
    val focusMinutes: Int = 25,
    val breakMinutes: Int = 5,
    val isConflict: Boolean = false,
    val conflictHabitName: String = "",
    val canSwitchMode: Boolean = true,
    val canConfigureDuration: Boolean = true,
    val canReset: Boolean = false,
    val canFinish: Boolean = false,
    val canTakeBreak: Boolean = false
)

fun formatTimerDigits(millis: Long, isOvertime: Boolean): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val prefix = if (isOvertime) "+" else ""
    return if (hours > 0) {
        String.format("%s%02d:%02d:%02d", prefix, hours, minutes, seconds)
    } else {
        String.format("%s%02d:%02d", prefix, minutes, seconds)
    }
}

fun computeTimerUiState(
    manager: TimerSessionManager?,
    isTask: Boolean,
    taskId: TaskId?,
    habit: Habit?
): TimerUiState {
    val currentManager = manager ?: return TimerUiState()
    val sharedState = currentManager.snapshot()
    val matchesTarget = if (isTask && taskId != null) {
        currentManager.isCurrentTask(taskId)
    } else if (habit != null) {
        val target = currentManager.currentTarget
        if (target != null) target.kind == FocusPrimaryTargetType.HABIT && target.id == habit.uuid
        else sharedState.habitId == habit.id
    } else false

    val conflict = sharedState.hasActiveSession && !matchesTarget
    val state = if (matchesTarget || conflict || (isTask && !sharedState.hasActiveSession)) sharedState else TimerSessionSnapshot()

    val durations = if (isTask && taskId != null) {
        currentManager.taskDurations(taskId)
    } else if (habit != null) {
        currentManager.durations(habit)
    } else {
        PomodoroDurations(25, 5)
    }

    val hasElapsed = state.elapsedMillis > 0L
    val isSessionActive = state.hasActiveSession || hasElapsed
    val isOvertimeFocus = state.mode == TimerMode.POMODORO &&
        state.phase == PomodoroPhase.FOCUS &&
        state.isOvertime &&
        !conflict
    val isBreakPhase = state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK

    return TimerUiState(
        mode = state.mode,
        phase = state.phase,
        isRunning = state.isRunning,
        isSessionActive = isSessionActive,
        hasElapsed = hasElapsed,
        elapsedMillis = state.elapsedMillis,
        displayMillis = state.displayMillis,
        isOvertime = state.isOvertime,
        focusMinutes = durations.focusMinutes,
        breakMinutes = durations.breakMinutes,
        isConflict = conflict,
        conflictHabitName = sharedState.habitName,
        canSwitchMode = !conflict && !state.isRunning && state.elapsedMillis == 0L && state.phase == PomodoroPhase.FOCUS,
        canConfigureDuration = !conflict && !state.isRunning && state.elapsedMillis == 0L && state.phase == PomodoroPhase.FOCUS && state.mode == TimerMode.POMODORO,
        canReset = !conflict && matchesTarget && (isSessionActive || state.phase == PomodoroPhase.BREAK),
        canFinish = !conflict && (isBreakPhase || hasElapsed),
        canTakeBreak = isOvertimeFocus
    )
}

/**
 * Resolves the optimal, accessible foreground color (dark or light) for a given rendered container color.
 * Enforces WCAG contrast requirements: >= 3.0:1 for icons, >= 4.5:1 for text.
 */
fun resolveForegroundFor(
    containerColor: Color,
    isText: Boolean = false,
    preferredDark: Color = Color(0xFF2E2A24), // warm dark charcoal
    preferredLight: Color = Color(0xFFF5F6F9) // soft off-white
): Color {
    val containerInt = containerColor.toArgb()
    val threshold = if (isText) 4.5 else 3.0

    val preferredLightContrast = ColorUtils.calculateContrast(preferredLight.toArgb(), containerInt)
    val maxLightContrast = ColorUtils.calculateContrast(android.graphics.Color.WHITE, containerInt)
    val preferredDarkContrast = ColorUtils.calculateContrast(preferredDark.toArgb(), containerInt)
    val maxDarkContrast = ColorUtils.calculateContrast(android.graphics.Color.BLACK, containerInt)

    return if (maxDarkContrast > maxLightContrast) {
        when {
            preferredDarkContrast >= threshold -> preferredDark
            maxDarkContrast >= threshold -> Color(0xFF161514)
            preferredLightContrast >= threshold -> preferredLight
            maxLightContrast >= threshold -> Color.White
            else -> if (maxDarkContrast >= maxLightContrast) Color(0xFF161514) else Color.White
        }
    } else {
        when {
            preferredLightContrast >= threshold -> preferredLight
            maxLightContrast >= threshold -> Color.White
            preferredDarkContrast >= threshold -> preferredDark
            maxDarkContrast >= threshold -> Color(0xFF161514)
            else -> if (maxLightContrast >= maxDarkContrast) Color.White else Color(0xFF161514)
        }
    }
}

/** Cohesive button container and glyph color pair derived from theme and accent color. */
data class TimerActionTones(
    val container: Color,
    val content: Color,
    val isEnabled: Boolean
)

fun resolveTimerActionTones(
    accentColor: Color,
    isDark: Boolean,
    isConflict: Boolean,
    isEditingDuration: Boolean,
    fallbackSecondary: Color
): TimerActionTones {
    if (isConflict || isEditingDuration) {
        val container = fallbackSecondary.copy(alpha = 0.22f)
        val content = if (isDark) Color(0xFF8E9199) else Color(0xFF74777F)
        return TimerActionTones(container, content, isEnabled = false)
    }

    val content = resolveForegroundFor(accentColor, isText = false)
    return TimerActionTones(accentColor, content, isEnabled = true)
}

/**
 * Shared Compose Timer Component.
 *
 * Implements the approved Minimal & Refined design:
 * - Minimal mode selector pill tabs (Секундомер / Помодоро)
 * - Large tabular monospace timer digits
 * - Inline compact duration configuration with presets and stepper
 * - Central circular play/pause button with high contrast
 * - Animated reset and finish buttons when session is active
 */
@Composable
fun FocusTimerContent(
    state: TimerUiState,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    wrapInCard: Boolean = true,
    onSwitchMode: (TimerMode) -> Unit,
    onTogglePlayPause: () -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    onTakeBreak: () -> Unit,
    onUpdateDurations: (focusMins: Int, breakMins: Int) -> Unit,
    alertIssue: PomodoroAlertIssue? = null,
    onFixAlertIssue: ((PomodoroAlertIssue) -> Unit)? = null
) {
    var isEditingDuration by remember { mutableStateOf(false) }
    var editBreakTab by remember { mutableStateOf(false) }
    var draftFocus by remember(state.focusMinutes) { mutableIntStateOf(state.focusMinutes) }
    var draftBreak by remember(state.breakMinutes) { mutableIntStateOf(state.breakMinutes) }

    val context = LocalContext.current
    val isReducedMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Throwable) {
            false
        }
    }

    val accent = Color(colors.accent)
    val onSurface = Color(colors.foreground)
    val onSurfaceVariant = Color(colors.secondary)
    val surfaceColor = Color(colors.surfaceVariant)
    val borderColor = Color(colors.border)

    val contentBlock: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Selector: Stopwatch / Pomodoro
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isStopwatch = state.mode == TimerMode.STOPWATCH
                val isPomodoro = state.mode == TimerMode.POMODORO

                // Stopwatch Tab
                UHabitSegmentedPillTab(
                    text = stringResource(R.string.timer_mode_stopwatch).uppercase(),
                    selected = isStopwatch,
                    enabled = state.canSwitchMode && !isEditingDuration,
                    colors = colors,
                    onClick = { onSwitchMode(TimerMode.STOPWATCH) }
                )

                Spacer(Modifier.width(8.dp))

                // Pomodoro Tab
                UHabitSegmentedPillTab(
                    text = stringResource(R.string.timer_mode_pomodoro).uppercase(),
                    selected = isPomodoro,
                    enabled = state.canSwitchMode && !isEditingDuration,
                    colors = colors,
                    onClick = { onSwitchMode(TimerMode.POMODORO) }
                )
            }

            // Status or conflict banner
            if (state.isConflict) {
                BasicText(
                    text = stringResource(R.string.timer_active_for_other, state.conflictHabitName),
                    style = TextStyle(
                        color = Color(colors.warning.takeIf { it != 0 } ?: colors.accent),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            } else if (state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.pomodoro_break_title).uppercase(),
                        style = TextStyle(
                            color = accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            // Alert issue warning (notifications / alarm permission disabled)
            val shouldShowAlert = alertIssue != null && !state.isConflict && state.mode == TimerMode.POMODORO && !state.isRunning && !state.hasElapsed
            if (shouldShowAlert && alertIssue != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(onSurfaceVariant.copy(alpha = 0.05f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val issueText = when (alertIssue) {
                        PomodoroAlertIssue.NOTIFICATIONS_DISABLED -> stringResource(R.string.pomodoro_alert_notifications_disabled)
                        PomodoroAlertIssue.CHANNEL_DISABLED -> stringResource(R.string.pomodoro_alert_channel_disabled)
                        PomodoroAlertIssue.CHANNEL_SILENT -> stringResource(R.string.pomodoro_alert_channel_silent)
                        PomodoroAlertIssue.EXACT_ALARMS_DISABLED -> stringResource(R.string.pomodoro_alert_exact_disabled)
                    }
                    BasicText(
                        text = issueText,
                        style = TextStyle(color = onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        text = stringResource(R.string.pomodoro_alert_fix),
                        style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onFixAlertIssue?.invoke(alertIssue) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Duration Editor OR Time Display + Control Buttons with single smooth SizeTransform
            AnimatedContent(
                targetState = isEditingDuration && state.canConfigureDuration,
                transitionSpec = {
                    (fadeIn(tween(150)) togetherWith fadeOut(tween(90)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(180, easing = FastOutSlowInEasing) })
                },
                label = "PomodoroDurationEditorTransition"
            ) { editing ->
                if (editing) {
                    // Animated Pomodoro Duration Wheel Editor
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Editor sub-tabs: Focus vs Break
                        Row(
                            modifier = Modifier.padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (!editBreakTab) accent.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { editBreakTab = false }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                BasicText(
                                    text = stringResource(R.string.pomodoro_focus_title),
                                    style = TextStyle(
                                        color = if (!editBreakTab) accent else onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (editBreakTab) accent.copy(alpha = 0.14f) else Color.Transparent)
                                    .clickable { editBreakTab = true }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                BasicText(
                                    text = stringResource(R.string.pomodoro_break_title),
                                    style = TextStyle(
                                        color = if (editBreakTab) accent else onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        // Minimalist 3-row Animated Minute Wheel (132dp) with tab transition
                        AnimatedContent(
                            targetState = editBreakTab,
                            transitionSpec = {
                                fadeIn(tween(130)) togetherWith fadeOut(tween(70))
                            },
                            label = "WheelTabTransition"
                        ) { breakTab ->
                            if (!breakTab) {
                                MinuteWheelPicker(
                                    value = draftFocus,
                                    onValueChange = { draftFocus = it },
                                    range = 1..180,
                                    textColor = onSurface
                                )
                            } else {
                                MinuteWheelPicker(
                                    value = draftBreak,
                                    onValueChange = { draftBreak = it },
                                    range = 1..60,
                                    textColor = onSurface
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Compact Done Confirmation Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(accent)
                                .clickable {
                                    onUpdateDurations(draftFocus, draftBreak)
                                    isEditingDuration = false
                                }
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = stringResource(R.string.pomodoro_done),
                                style = TextStyle(
                                    color = resolveForegroundFor(accent, isText = true),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Optical spacing above digits when no warning or conflict banner is present
                        if (!shouldShowAlert && !state.isConflict && !(state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK)) {
                            Spacer(Modifier.height(6.dp))
                        }

                        // Main Time Display
                        val displayString = formatTimerDigits(state.displayMillis, state.isOvertime)
                        val configureDurationDesc = stringResource(R.string.timer_tap_to_configure)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = state.canConfigureDuration) {
                                    isEditingDuration = true
                                }
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .semantics {
                                    if (state.canConfigureDuration) {
                                        role = Role.Button
                                        contentDescription = configureDurationDesc
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = displayString,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 48.sp,
                                    color = accent
                                )
                            )
                        }

                        // Control Buttons Row (Reset, Play/Pause, Break, Finish)
                        Spacer(Modifier.height(8.dp))
                        val sideEnter: EnterTransition = remember(isReducedMotion) {
                            if (isReducedMotion) {
                                fadeIn(snap())
                            } else {
                                fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                    expandHorizontally(
                                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                                        expandFrom = Alignment.CenterHorizontally
                                    )
                            }
                        }
                        val sideExit: ExitTransition = remember(isReducedMotion) {
                            if (isReducedMotion) {
                                fadeOut(snap())
                            } else {
                                fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                                    shrinkHorizontally(
                                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                                        shrinkTowards = Alignment.CenterHorizontally
                                    )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Reset Button (spacing sits inside AnimatedVisibility so Play remains centered when idle)
                            AnimatedVisibility(
                                visible = state.canReset,
                                enter = sideEnter,
                                exit = sideExit
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TimerSecondaryActionButton(
                                        iconRes = R.drawable.ic_timer_reset,
                                        contentDescription = stringResource(R.string.timer_reset),
                                        accent = accent,
                                        surfaceColor = surfaceColor,
                                        borderColor = borderColor,
                                        onClick = onReset
                                    )
                                    Spacer(Modifier.width(16.dp))
                                }
                            }

                            // Center: Primary Play/Pause Button
                            val primaryDesc = when {
                                state.isRunning -> stringResource(R.string.timer_pause)
                                state.hasElapsed -> stringResource(R.string.timer_resume)
                                state.mode == TimerMode.POMODORO && state.phase == PomodoroPhase.BREAK ->
                                    stringResource(R.string.pomodoro_start_break)
                                else -> stringResource(R.string.timer_start)
                            }
                            val tones = resolveTimerActionTones(
                                accentColor = accent,
                                isDark = colors.isDark,
                                isConflict = state.isConflict,
                                isEditingDuration = isEditingDuration,
                                fallbackSecondary = onSurfaceVariant
                            )
                            val primaryContainerColor by animateColorAsState(
                                targetValue = tones.container,
                                animationSpec = if (isReducedMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                                label = "TimerPrimaryContainer"
                            )
                            val primaryContentColor by animateColorAsState(
                                targetValue = tones.content,
                                animationSpec = if (isReducedMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                                label = "TimerPrimaryContent"
                            )
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(primaryContainerColor)
                                    .clickable(enabled = tones.isEnabled) {
                                        onTogglePlayPause()
                                    }
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = primaryDesc
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedContent(
                                    targetState = state.isRunning,
                                    transitionSpec = {
                                        if (isReducedMotion) {
                                            fadeIn(snap()) togetherWith fadeOut(snap())
                                        } else {
                                            (fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                                                scaleIn(initialScale = 0.86f, animationSpec = tween(180, easing = FastOutSlowInEasing))) togetherWith
                                                (fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                                                    scaleOut(targetScale = 0.86f, animationSpec = tween(150, easing = FastOutSlowInEasing)))
                                        }
                                    },
                                    label = "TimerPlayPauseIcon"
                                ) { running ->
                                    val iconRes = if (running) R.drawable.ic_timer_pause else R.drawable.ic_timer_play
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(iconRes),
                                        contentDescription = primaryDesc,
                                        modifier = Modifier.size(28.dp),
                                        colorFilter = ColorFilter.tint(primaryContentColor)
                                    )
                                }
                            }

                            // Right 1: Take Break Button (when in Pomodoro Overtime)
                            AnimatedVisibility(
                                visible = state.canTakeBreak,
                                enter = sideEnter,
                                exit = sideExit
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(16.dp))
                                    TimerSecondaryActionButton(
                                        iconRes = R.drawable.ic_timer_break,
                                        contentDescription = stringResource(R.string.pomodoro_start_break),
                                        accent = accent,
                                        surfaceColor = surfaceColor,
                                        borderColor = borderColor,
                                        onClick = onTakeBreak
                                    )
                                }
                            }

                            // Right 2: Finish Button (keeps exact 16dp gap even when Break is visible alongside Finish)
                            AnimatedVisibility(
                                visible = state.canFinish,
                                enter = sideEnter,
                                exit = sideExit
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(16.dp))
                                    TimerSecondaryActionButton(
                                        iconRes = R.drawable.ic_timer_finish,
                                        contentDescription = stringResource(R.string.timer_finish),
                                        accent = accent,
                                        surfaceColor = surfaceColor,
                                        borderColor = borderColor,
                                        onClick = onFinish
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (wrapInCard) {
        HabitSectionCard(
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)
        ) {
            contentBlock()
        }
    } else {
        contentBlock()
    }
}

/** Circular 48dp secondary timer action button shared across Reset, Break, and Finish controls. */
@Composable
private fun TimerSecondaryActionButton(
    iconRes: Int,
    contentDescription: String,
    accent: Color,
    surfaceColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(surfaceColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(accent)
        )
    }
}

/**
 * Native Compose content for TaskFocusBottomSheet.
 */
@Composable
fun TaskFocusSheetContent(
    taskId: TaskId,
    taskTitle: String,
    containerName: String,
    manager: TimerSessionManager,
    colors: UHabitComposeColors,
    onEnsurePermission: (action: () -> Unit) -> Unit,
    alertHealth: () -> PomodoroAlertHealth,
    onFixAlertIssue: (PomodoroAlertIssue) -> Unit
) {
    var uiState by remember {
        mutableStateOf(computeTimerUiState(manager, isTask = true, taskId = taskId, habit = null))
    }

    DisposableEffect(manager, taskId) {
        val listener = TimerSessionManager.Listener {
            uiState = computeTimerUiState(manager, isTask = true, taskId = taskId, habit = null)
        }
        manager.addListener(listener)
        onDispose {
            manager.removeListener(listener)
        }
    }

    val health = alertHealth()
    val alertIssue = when {
        !health.notificationsEnabled -> PomodoroAlertIssue.NOTIFICATIONS_DISABLED
        !health.channelEnabled -> PomodoroAlertIssue.CHANNEL_DISABLED
        !health.hasSound && !health.hasVibration -> PomodoroAlertIssue.CHANNEL_SILENT
        !health.exactAlarmsEnabled -> PomodoroAlertIssue.EXACT_ALARMS_DISABLED
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(colors.surface))
            .navigationBarsPadding()
            .padding(top = 16.dp, bottom = 16.dp)
    ) {
        // Task Title & Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            BasicText(
                text = taskTitle,
                style = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = containerName,
                style = TextStyle(
                    color = Color(colors.secondary),
                    fontSize = 14.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        FocusTimerContent(
            state = uiState,
            colors = colors,
            wrapInCard = false,
            onSwitchMode = { mode -> manager.switchTaskMode(taskId, taskTitle, mode) },
            onTogglePlayPause = {
                onEnsurePermission {
                    manager.toggleTask(taskId, taskTitle)
                }
            },
            onReset = { manager.resetCurrent() },
            onFinish = { manager.finishCurrent() },
            onTakeBreak = { manager.takeBreakCurrent() },
            onUpdateDurations = { focus, rest -> manager.updateTaskDurations(taskId, taskTitle, focus, rest) },
            alertIssue = alertIssue,
            onFixAlertIssue = onFixAlertIssue
        )
    }
}
