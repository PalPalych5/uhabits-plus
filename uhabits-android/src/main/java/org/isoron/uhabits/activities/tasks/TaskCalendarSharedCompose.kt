/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.views.DarkTheme
import org.isoron.uhabits.core.ui.views.LightTheme
import org.isoron.uhabits.core.ui.views.PureBlackTheme
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberResolvedHabitColor(
    paletteColor: PaletteColor?,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
): Color {
    return remember(paletteColor, colors.isDark, colors.isPureBlack, colors.accent) {
        if (paletteColor == null) {
            colors.accentColor
        } else {
            val theme = when {
                colors.isPureBlack -> PureBlackTheme()
                colors.isDark -> DarkTheme()
                else -> LightTheme()
            }
            Color(theme.color(paletteColor).toInt())
        }
    }
}

internal fun resolveHabitColorValue(
    paletteColor: PaletteColor?,
    colors: UHabitComposeColors
): Color {
    if (paletteColor == null) return colors.accentColor
    val theme = when {
        colors.isPureBlack -> PureBlackTheme()
        colors.isDark -> DarkTheme()
        else -> LightTheme()
    }
    return Color(theme.color(paletteColor).toInt())
}

internal fun formatCalendarDurationCompact(context: Context, ms: Long): String {
    val totalMin = ms / 60_000L
    if (totalMin <= 0L) return context.getString(R.string.task_duration_compact_zero)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L && m > 0L -> context.getString(R.string.task_duration_compact_hm, h, m)
        h > 0L -> context.getString(R.string.task_duration_compact_h, h)
        else -> context.getString(R.string.task_duration_compact_m, m)
    }
}

internal fun formatCalendarDurationFull(context: Context, ms: Long): String {
    val totalMin = ms / 60_000L
    if (totalMin <= 0L) return context.getString(R.string.task_duration_full_zero)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L && m > 0L -> context.getString(R.string.task_duration_full_hm, h, m)
        h > 0L -> context.getString(R.string.task_duration_full_h, h)
        else -> context.getString(R.string.task_duration_full_m, m)
    }
}

@Composable
internal fun CalendarWeekdayHeaderRow(
    weekdayNames: List<String>,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        weekdayNames.forEach { name ->
            BasicText(
                text = name,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    textAlign = TextAlign.Center
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun CalendarDayNumberCircle(
    dayNumber: Int,
    isSelected: Boolean,
    isToday: Boolean,
    wide: Boolean,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    textSizeSp: Float = 13f,
    modifier: Modifier = Modifier
) {
    val fontScale = LocalConfiguration.current.fontScale
    val maxCircleDp = if (wide) 38f else if (fontScale > 1.4f) 38f else 32f
    val circleSize = minOf(maxCircleDp, maxOf(24f, 20f * fontScale)).dp
    val onAccent = MaterialTheme.colorScheme.onPrimary

    val textColor = when {
        isSelected -> onAccent
        isToday -> colors.accentColor
        else -> colors.foregroundColor
    }

    val circleMod = when {
        isSelected -> Modifier.background(colors.accentColor, CircleShape)
        isToday -> Modifier.border(1.dp, colors.accentColor, CircleShape)
        else -> Modifier
    }

    Box(
        modifier = modifier
            .size(circleSize)
            .clip(CircleShape)
            .then(circleMod),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = dayNumber.toString(),
            style = TextStyle(
                color = textColor,
                fontSize = textSizeSp.sp,
                fontFamily = FontFamily.Default,
                fontWeight = if (isSelected || isToday) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun CalendarSectionHeader(
    title: String,
    count: Int,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier
) {
    BasicText(
        text = "$title ($count)",
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        style = TextStyle(
            color = colors.secondaryColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CalendarTaskRow(
    task: UserTask,
    containerName: String?,
    tags: List<TaskTag>,
    locale: Locale,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit,
    onQuickMenu: () -> Unit
) {
    val context = LocalContext.current
    val timeStr = remember(task.due, locale) {
        (task.due as? TaskDue.Timed)?.let {
            SimpleDateFormat("HH:mm", locale).format(Date(it.epochMillis))
        }
    }
    val subtext = remember(timeStr, containerName, tags, context) {
        buildString {
            if (timeStr != null) {
                append(context.getString(R.string.task_calendar_due_timed_format, timeStr))
            } else {
                append(context.getString(R.string.task_calendar_due_all_day))
            }
            if (!containerName.isNullOrBlank()) append(" · $containerName")
            if (tags.isNotEmpty()) append(" · ").append(tags.joinToString(" ") { "#${it.name}" })
        }
    }

    val toggleLabel = stringResource(
        if (task.isCompleted) R.string.task_row_a11y_reopen_title else R.string.task_row_a11y_complete_title,
        task.title
    )
    val actionsLabel = stringResource(R.string.task_row_a11y_actions)
    val completedState = stringResource(R.string.task_row_state_completed)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onQuickMenu
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "${task.title}, $subtext"
                if (task.isCompleted) stateDescription = completedState
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
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Checkbox, onClickLabel = toggleLabel, onClick = onToggleComplete)
                .semantics {
                    role = Role.Checkbox
                    contentDescription = toggleLabel
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(
                    if (task.isCompleted) R.drawable.ic_tasks_completed else R.drawable.ic_cat_activity
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (task.isCompleted) colors.accentColor else colors.secondaryColor),
                modifier = Modifier.size(22.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp, top = 2.dp, bottom = 2.dp)
        ) {
            BasicText(
                text = task.title,
                style = TextStyle(
                    color = if (task.isCompleted) colors.secondaryColor else colors.foregroundColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = subtext,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun CalendarPlannedHabitRow(
    habit: PlannedHabit,
    locale: Locale,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val habitColor = rememberResolvedHabitColor(habit.color, colors)
    val subtitle = remember(habit.reminderHour, habit.reminderMinute, locale, context) {
        if (habit.reminderHour != null && habit.reminderMinute != null) {
            val time = String.format(locale, "%02d:%02d", habit.reminderHour, habit.reminderMinute)
            context.getString(R.string.task_calendar_habit_reminder_format, time)
        } else {
            context.getString(R.string.task_calendar_habit_scheduled)
        }
    }
    val pillLabel = stringResource(R.string.task_calendar_habit_pill)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${habit.name}, $subtitle"
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_cat_award),
            contentDescription = null,
            colorFilter = ColorFilter.tint(habitColor),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = habit.name,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = subtitle,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        val pillShape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .clip(pillShape)
                .background(colors.surfaceColor)
                .border(1.dp, habitColor, pillShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = pillLabel,
                style = TextStyle(
                    color = habitColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun CalendarHistorySummaryCard(
    summary: DayHistorySummary,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasEstimated = summary.hasEstimatedDayAllocation
    val prefix = if (hasEstimated) "~" else ""
    val estNote = if (hasEstimated) stringResource(R.string.task_calendar_estimated_note) else ""
    val sessionsCountStr = context.resources.getQuantityString(
        R.plurals.task_calendar_sessions_count,
        summary.sessionCount,
        summary.sessionCount
    )
    val summaryText = stringResource(R.string.task_calendar_total_work_label) + " " +
        "$prefix${formatCalendarDurationFull(context, summary.totalWorkDurationMs)}$estNote · $sessionsCountStr"

    UHabitSectionCard(
        modifier = modifier.padding(
            horizontal = if (colors.isClassicSectionLayout) 0.dp else 16.dp
        ),
        colors = colors,
        cornerRadiusDp = if (colors.isClassicSectionLayout) 0 else 12,
        contentPadding = PaddingValues(
            horizontal = if (colors.isClassicSectionLayout) 16.dp else 12.dp,
            vertical = 8.dp
        )
    ) {
        BasicText(
            text = summaryText,
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
}

@Composable
internal fun CalendarHistorySessionCard(
    segment: FocusHistorySegment,
    title: String,
    habits: List<Habit>,
    locale: Locale,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    showEstimatedPartitionNote: Boolean = true,
    tagValue: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val primaryType = TaskCalendarStateHelper.resolveSessionTargetType(segment.session)
    val habitColor = remember(segment.session.habit?.uuid, habits, colors.isDark, colors.isPureBlack, colors.accent) {
        val h = habits.find { it.uuid == segment.session.habit?.uuid }
        resolveHabitColorValue(h?.color, colors)
    }

    val (iconRes, iconTint) = when (primaryType) {
        FocusPrimaryTargetType.TASK -> R.drawable.ic_tasks_completed to colors.accentColor
        FocusPrimaryTargetType.HABIT -> R.drawable.ic_cat_activity to habitColor
        FocusPrimaryTargetType.CONTAINER -> R.drawable.ic_cat_folder to colors.secondaryColor
        null -> R.drawable.ic_cat_activity to colors.secondaryColor
    }

    val intervalText = remember(segment, locale) {
        FocusHistoryProjector.formatSegmentIntervalText(segment, locale)
    }
    val isEst = segment.isDayAllocationEstimated
    val prefix = if (isEst) "~" else ""
    val durationText = prefix + formatCalendarDurationCompact(context, segment.segmentDurationMs)

    val containerModifier = if (colors.isClassicSectionLayout) {
        val dividerColor = colors.resolveDividerColor()
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            }
    } else {
        val shape = RoundedCornerShape(12.dp)
        val surface = colors.resolveSectionSurfaceColor()
        val borderMod = if (colors.showCardBorders) {
            Modifier.border(
                width = UHabitElevationAndBorders.hairlineBorderWidth,
                color = colors.resolveSectionBorderColor(),
                shape = shape
            )
        } else {
            Modifier
        }
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(surface)
            .then(borderMod)
    }

    val pillBg = colors.foregroundColor.copy(alpha = if (colors.isDark) 0.13f else 0.07f)

    Row(
        modifier = modifier
            .then(containerModifier)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (tagValue != null) Modifier.testTag(tagValue) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $intervalText, $durationText"
            }
            .padding(
                horizontal = if (colors.isClassicSectionLayout) 16.dp else 12.dp,
                vertical = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconTint),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            BasicText(
                text = intervalText,
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (showEstimatedPartitionNote && isEst) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = stringResource(R.string.task_calendar_estimated_midnight_partition),
                    style = TextStyle(
                        color = colors.accentColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Default
                    )
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(pillBg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = durationText,
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
    }
}

@Composable
internal fun CalendarCurrentTimeLine(
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    showDot: Boolean = true,
    modifier: Modifier = Modifier
) {
    val lineColor = colors.dangerColor
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(lineColor, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(lineColor)
        )
    }
}

@Composable
internal fun CalendarHairlineDivider(
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.resolveDividerColor())
    )
}
