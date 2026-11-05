/* Copyright (C) 2026 UHabit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.activities.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class TaskMetaToken(
    val iconRes: Int?,
    val label: String,
    val color: Color,
    val isColoredDot: Boolean = false,
    val dotColor: Color = Color.Transparent,
    val spokenText: String = label
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRowCompose(
    task: UserTask,
    isCompleted: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean,
    subtaskCount: Pair<Int, Int>?,
    containerName: String?,
    containerColor: Int?,
    tags: List<TaskTag>,
    today: LocalDate,
    nowMillis: Long,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    onTaskClicked: () -> Unit,
    onToggleComplete: () -> Unit,
    onTaskLongClicked: () -> Unit,
    onShowContextMenu: () -> Unit,
    onChangeDue: (() -> Unit)? = null,
    onMoveContainer: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val locale = TaskDueDisplayFormatter.selectedLocale(context)
    val timeFormat = SimpleDateFormat("HH:mm", locale)

    // 1. Build metadata tokens
    val tokens = mutableListOf<TaskMetaToken>()

    // Priority token (compact text only if not completed)
    if (!isCompleted) {
        when {
            task.important == true && task.urgent == true -> {
                tokens.add(
                    TaskMetaToken(
                        iconRes = R.drawable.ic_cat_flame,
                        label = "Срочно и важно",
                        color = Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt()),
                        spokenText = "Приоритет: срочно и важно"
                    )
                )
            }
            task.important == true -> {
                tokens.add(
                    TaskMetaToken(
                        iconRes = R.drawable.ic_cat_star,
                        label = "Важно",
                        color = Color(if (colors.warning != 0) colors.warning else 0xFFFFB74D.toInt()),
                        spokenText = "Приоритет: важно"
                    )
                )
            }
            task.urgent == true -> {
                tokens.add(
                    TaskMetaToken(
                        iconRes = R.drawable.ic_cat_flame,
                        label = "Срочно",
                        color = Color(if (colors.warning != 0) colors.warning else 0xFFFFB74D.toInt()),
                        spokenText = "Приоритет: срочно"
                    )
                )
            }
        }
    }

    // Date / Due token
    if (!isCompleted) {
        when (val due = task.due) {
            is TaskDue.None -> {
                if (task.hasPendingLegacyPlan) {
                    val label = "План: ${task.legacyPlanDate}"
                    tokens.add(
                        TaskMetaToken(
                            iconRes = R.drawable.ic_cat_calendar,
                            label = label,
                            color = Color(colors.secondary),
                            spokenText = label
                        )
                    )
                }
            }
            is TaskDue.Day -> {
                val d = due.date.toLocalDate()
                val overdue = task.isOverdue(today, nowMillis)
                val isDueToday = task.isDueToday(today, nowMillis)
                val isTomorrow = d.daysSince2000 == today.daysSince2000 + 1
                val label = when {
                    overdue -> "${d.day}.${d.month.toString().padStart(2, '0')}"
                    isDueToday -> "Сегодня"
                    isTomorrow -> "Завтра"
                    else -> TaskDueDisplayFormatter.format(due, locale)
                }
                val dueColor = when {
                    overdue -> Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt())
                    isDueToday -> Color(colors.accent)
                    else -> Color(colors.secondary)
                }
                tokens.add(
                    TaskMetaToken(
                        iconRes = R.drawable.ic_cat_calendar,
                        label = label,
                        color = dueColor,
                        spokenText = if (overdue) "Просрочено: $label" else "Срок: $label"
                    )
                )
            }
            is TaskDue.Timed -> {
                val overdue = task.isOverdue(today, nowMillis)
                val isDueToday = task.isDueToday(today, nowMillis)
                val cal = Calendar.getInstance().apply { timeInMillis = due.epochMillis }
                val timeStr = timeFormat.format(Date(due.epochMillis))
                val label = when {
                    overdue -> "${cal.get(Calendar.DAY_OF_MONTH)}.${(cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}, $timeStr"
                    isDueToday -> "Сегодня, $timeStr"
                    else -> TaskDueDisplayFormatter.format(due, locale)
                }
                val dueColor = when {
                    overdue -> Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt())
                    isDueToday -> Color(colors.accent)
                    else -> Color(colors.secondary)
                }
                tokens.add(
                    TaskMetaToken(
                        iconRes = R.drawable.ic_cat_calendar_clock,
                        label = label,
                        color = dueColor,
                        spokenText = if (overdue) "Просрочено: $label" else "Срок со временем: $label"
                    )
                )
            }
        }
    } else {
        val completedAt = task.completedAt
        if (completedAt != null && completedAt > 0L) {
            val dateStr = SimpleDateFormat("d MMM", locale).format(Date(completedAt))
            val label = "Выполнено $dateStr"
            tokens.add(
                TaskMetaToken(
                    iconRes = R.drawable.ic_action_check,
                    label = label,
                    color = Color(colors.secondary),
                    spokenText = label
                )
            )
        }
    }

    // Container / Section token (only if not hidden by parent context)
    if (!containerName.isNullOrBlank()) {
        if (containerColor != null) {
            tokens.add(
                TaskMetaToken(
                    iconRes = null,
                    label = containerName,
                    color = Color(colors.secondary),
                    isColoredDot = true,
                    dotColor = Color(containerColor),
                    spokenText = "Раздел: $containerName"
                )
            )
        } else {
            tokens.add(
                TaskMetaToken(
                    iconRes = R.drawable.ic_cat_folder,
                    label = containerName,
                    color = Color(colors.secondary),
                    spokenText = "Раздел: $containerName"
                )
            )
        }
    }

    // Subtasks token
    if (subtaskCount != null && subtaskCount.second > 0) {
        val (completed, total) = subtaskCount
        val allDone = completed == total
        val icon = if (allDone) R.drawable.ic_cat_check_square else R.drawable.ic_cat_list_todo
        val subColor = if (allDone && !isCompleted) Color(colors.accent) else Color(colors.secondary)
        tokens.add(
            TaskMetaToken(
                iconRes = icon,
                label = "$completed/$total",
                color = subColor,
                spokenText = "Подзадачи: $completed из $total"
            )
        )
    }

    // Tags token
    if (tags.isNotEmpty()) {
        val visibleTags = tags.take(2)
        val extraCount = tags.size - visibleTags.size
        val tagText = buildString {
            append(visibleTags.joinToString(" ") { "#${it.name}" })
            if (extraCount > 0) append(" +$extraCount")
        }
        val tagColor = if (isCompleted) Color(colors.secondary) else Color(colors.accent).copy(alpha = 0.85f)
        tokens.add(
            TaskMetaToken(
                iconRes = R.drawable.ic_cat_tag,
                label = tagText,
                color = tagColor,
                spokenText = "Теги: ${tags.joinToString(", ") { it.name }}"
            )
        )
    }

    // Notes indicator icon
    if (task.description.isNotBlank()) {
        tokens.add(
            TaskMetaToken(
                iconRes = R.drawable.ic_cat_file_text,
                label = "",
                color = Color(colors.secondary),
                spokenText = "есть заметка"
            )
        )
    }

    // Linked habit indicator icon
    if (task.linkedHabitUuid != null) {
        tokens.add(
            TaskMetaToken(
                iconRes = R.drawable.ic_cat_repeat,
                label = "",
                color = Color(colors.secondary),
                spokenText = "связана с привычкой"
            )
        )
    }

    // 3. Accessibility spoken string
    val spokenDescription = buildString {
        append(task.title)
        if (isSelected) append("; выбрано")
        if (isCompleted) append("; завершено")
        tokens.forEach { token ->
            if (token.spokenText.isNotBlank()) {
                append("; ")
                append(token.spokenText)
            }
        }
    }

    // Selection background
    val rowBackground = when {
        isSelected -> Color(colors.accent).copy(alpha = if (colors.isDark) 0.18f else 0.10f)
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(rowBackground)
            .combinedClickable(
                onClick = onTaskClicked,
                onLongClick = onTaskLongClicked
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = spokenDescription
                if (isSelected) {
                    this.stateDescription = "Выбрано"
                } else if (isCompleted) {
                    this.stateDescription = "Завершено"
                }
                this.customActions = listOfNotNull(
                    onChangeDue?.let { action ->
                        CustomAccessibilityAction("Изменить срок") {
                            action()
                            true
                        }
                    },
                    onMoveContainer?.let { action ->
                        CustomAccessibilityAction("Переместить в раздел") {
                            action()
                            true
                        }
                    },
                    CustomAccessibilityAction(if (isSelected) "Снять выделение" else "Выбрать задачу") {
                        onTaskLongClicked()
                        true
                    },
                    CustomAccessibilityAction(if (isCompleted) "Возобновить задачу" else "Завершить задачу") {
                        onToggleComplete()
                        true
                    },
                    CustomAccessibilityAction("Действия с задачей") {
                        onShowContextMenu()
                        true
                    }
                )
            }
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // --- Circular Checkbox (48dp touch target) ---
        TaskCheckboxAffordance(
            isCompleted = isCompleted,
            isSelected = isSelected,
            selectionActive = selectionActive,
            colors = colors,
            taskTitle = task.title,
            onToggleComplete = onToggleComplete,
            onLongClick = onTaskLongClicked
        )

        // --- Content: Title & Metadata ---
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp, top = 2.dp)
        ) {
            val titleColor = if (isCompleted) Color(colors.secondary) else Color(colors.foreground)
            val textDecoration = if (isCompleted) TextDecoration.LineThrough else null

            BasicText(
                text = task.title,
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = titleColor,
                    fontSize = 15.5.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = if (task.important == true && !isCompleted) FontWeight.Medium else FontWeight.Normal,
                    lineHeight = 21.sp,
                    textDecoration = textDecoration
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (tokens.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    tokens.forEach { token ->
                        TaskMetadataBadge(token)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCheckboxAffordance(
    isCompleted: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean,
    colors: UHabitComposeColors,
    taskTitle: String,
    onToggleComplete: () -> Unit,
    onLongClick: () -> Unit
) {
    val checkmarkAlpha by animateFloatAsState(
        targetValue = if (isCompleted || isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "checkAlpha"
    )
    val checkmarkScale by animateFloatAsState(
        targetValue = if (isCompleted || isSelected) 1f else 0.5f,
        animationSpec = tween(durationMillis = 150),
        label = "checkScale"
    )

    val actionLabel = when {
        selectionActive && isSelected -> "Снять выделение: $taskTitle"
        selectionActive -> "Выбрать: $taskTitle"
        isCompleted -> "Возобновить: $taskTitle"
        else -> "Завершить: $taskTitle"
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onToggleComplete,
                onLongClick = onLongClick
            )
            .semantics {
                this.role = Role.Checkbox
                this.contentDescription = actionLabel
                if (isSelected) {
                    this.stateDescription = "Выбрано"
                } else if (isCompleted) {
                    this.stateDescription = "Завершено"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val circleModifier = Modifier.size(20.dp)

        when {
            isSelected -> {
                Box(
                    modifier = circleModifier
                        .background(Color(colors.accent), CircleShape)
                        .border(1.5.dp, Color(colors.accent), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_action_check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(if (colors.isDark) Color(0xFF102638) else Color.White),
                        modifier = Modifier
                            .size(11.dp)
                            .scale(checkmarkScale)
                    )
                }
            }
            isCompleted -> {
                Box(
                    modifier = circleModifier
                        .background(Color(colors.secondary).copy(alpha = 0.12f), CircleShape)
                        .border(1.dp, Color(colors.secondary).copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_action_check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier
                            .size(11.dp)
                            .scale(checkmarkScale)
                    )
                }
            }
            else -> {
                val strokeColor = if (selectionActive) {
                    Color(colors.accent)
                } else {
                    Color(colors.secondary).copy(alpha = 0.45f)
                }
                Box(
                    modifier = circleModifier
                        .border(1.5.dp, strokeColor, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun TaskMetadataBadge(token: TaskMetaToken) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        if (token.isColoredDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(token.dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else if (token.iconRes != null) {
            Image(
                painter = painterResource(token.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(token.color),
                modifier = Modifier.size(12.dp)
            )
            if (token.label.isNotEmpty()) {
                Spacer(modifier = Modifier.width(3.5.dp))
            }
        }
        if (token.label.isNotEmpty()) {
            BasicText(
                text = token.label,
                style = TextStyle(
                    color = token.color,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontFeatureSettings = "tnum"
                )
            )
        }
    }
}
