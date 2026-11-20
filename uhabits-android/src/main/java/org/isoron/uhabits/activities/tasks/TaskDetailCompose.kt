/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Immutable UI State for the Task Detail Editor.
 */
data class TaskDetailUiState(
    val isCreateMode: Boolean,
    val title: String,
    val notes: String,
    val isCompleted: Boolean,
    val due: TaskDue,
    val dueFormatted: String,
    val isOverdue: Boolean,
    val containerName: String?,
    val containerColor: Int?,
    val important: Boolean?,
    val urgent: Boolean?,
    val tags: List<TagItemState>,
    val subtasks: List<SubtaskItemState>,
    val focusSummary: String?,
    val hasFocusFeature: Boolean,
    val linkedHabit: LinkedHabitState?,
    val pendingLegacyPlanDate: String?,
    val saveStatusText: String,
    val canCreate: Boolean,
    val isSaveError: Boolean = false,
    val isSaving: Boolean = false
)

data class SubtaskItemState(
    val id: String,
    val title: String,
    val isCompleted: Boolean
)

data class LinkedHabitState(
    val uuid: String,
    val name: String,
    val unit: String,
    val color: Int?,
    val isArchived: Boolean,
    val isIncompatible: Boolean,
    val timeAccrualEnabled: Boolean
)

data class TagItemState(
    val id: String,
    val name: String,
    val color: Int?
)

/**
 * Modern, ergonomic Jetpack Compose Task Detail Screen.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TaskDetailComposeScreen(
    state: TaskDetailUiState,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onToggleComplete: () -> Unit,
    onDueClick: () -> Unit,
    onClearDue: () -> Unit,
    onContainerClick: () -> Unit,
    onImportanceClick: () -> Unit,
    onUrgencyClick: () -> Unit,
    onTagsClick: () -> Unit,
    onAddSubtask: (String) -> Unit,
    onToggleSubtask: (id: String) -> Unit,
    onEditSubtask: (id: String) -> Unit,
    onDeleteSubtask: (id: String) -> Unit,
    onFocusClick: () -> Unit,
    onHabitLinkClick: () -> Unit,
    onHabitUnlinkClick: () -> Unit,
    onHabitAccrualToggle: (Boolean) -> Unit,
    onAcceptLegacyPlan: () -> Unit,
    onDismissLegacyPlan: () -> Unit,
    onCreateSubmit: () -> Unit,
    onClose: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onRetrySave: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .semantics { testTagsAsResourceId = true }
    ) {
        // --- 1. Top Drag Handle & Action Bar ---
        TaskDetailTopBar(
            isCreateMode = state.isCreateMode,
            saveStatusText = state.saveStatusText,
            isSaveError = state.isSaveError,
            isSaving = state.isSaving,
            canCreate = state.canCreate,
            colors = colors,
            onClose = onClose,
            onCreateSubmit = onCreateSubmit,
            onMoreOptionsClick = onMoreOptionsClick,
            onRetrySave = onRetrySave
        )

        // --- 2. Scrollable Content Area ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .verticalScroll(scrollState)
                .testTag("taskDetailScroll")
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            // Task Title Row (with Circular Checkbox)
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
            ) {
                // Checkbox affordance
                TaskDetailCheckAffordance(
                    isCompleted = state.isCompleted,
                    isCreateMode = state.isCreateMode,
                    colors = colors,
                    onToggle = onToggleComplete
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Title Input
                val titleColor = if (state.isCompleted) Color(colors.secondary) else Color(colors.foreground)
                val textDecoration = if (state.isCompleted) TextDecoration.LineThrough else null

                BasicTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("taskDetailTitle")
                        .padding(top = 2.dp),
                    textStyle = TextStyle(
                        fontSize = 18.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        fontFamily = FontFamily.Default,
                        lineHeight = 25.sp,
                        textDecoration = textDecoration
                    ),
                    cursorBrush = SolidColor(Color(colors.accent)),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.title.isEmpty()) {
                                BasicText(
                                    text = stringResource(if (state.isCreateMode) R.string.task_detail_title_hint_create else R.string.task_detail_title_hint_edit),
                                    style = TextStyle(
                                        fontSize = 18.5.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color(colors.secondary).copy(alpha = 0.55f),
                                        lineHeight = 25.sp
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            // Description / Notes Field
            BasicTextField(
                value = state.notes,
                onValueChange = onNotesChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("taskDetailNotes")
                    .padding(start = 40.dp, top = 6.dp, bottom = 12.dp)
                    .heightIn(min = 44.dp),
                textStyle = TextStyle(
                    fontSize = 14.5.sp,
                    color = Color(colors.foreground).copy(alpha = 0.9f),
                    fontFamily = FontFamily.Default,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(Color(colors.accent)),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.notes.isEmpty()) {
                            BasicText(
                                text = stringResource(R.string.task_detail_notes_hint),
                                style = TextStyle(
                                    fontSize = 14.5.sp,
                                    color = Color(colors.secondary).copy(alpha = 0.5f),
                                    lineHeight = 20.sp
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // --- 3. Compact Properties Bar (FlowRow) ---
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Due Date Chip
                DetailDueChip(
                    due = state.due,
                    formatted = state.dueFormatted,
                    isOverdue = state.isOverdue,
                    colors = colors,
                    onClick = onDueClick,
                    onClear = onClearDue
                )

                // Container / Section Chip
                DetailContainerChip(
                    containerName = state.containerName,
                    containerColor = state.containerColor,
                    colors = colors,
                    onClick = onContainerClick
                )

                // Importance (Eisenhower Matrix) Chip
                DetailMatrixChip(
                    label = stringResource(
                        when (state.important) {
                            true -> R.string.task_detail_important
                            false -> R.string.task_detail_not_important
                            null -> R.string.task_detail_importance
                        }
                    ),
                    iconRes = R.drawable.ic_cat_star,
                    isActive = state.important == true,
                    isExplicitNegative = state.important == false,
                    accentColor = Color(if (colors.warning != 0) colors.warning else 0xFFFFB74D.toInt()),
                    colors = colors,
                    onClick = onImportanceClick
                )

                // Urgency (Eisenhower Matrix) Chip
                DetailMatrixChip(
                    label = stringResource(
                        when (state.urgent) {
                            true -> R.string.task_detail_urgent
                            false -> R.string.task_detail_not_urgent
                            null -> R.string.task_detail_urgency
                        }
                    ),
                    iconRes = R.drawable.ic_cat_flame,
                    isActive = state.urgent == true,
                    isExplicitNegative = state.urgent == false,
                    accentColor = Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt()),
                    colors = colors,
                    onClick = onUrgencyClick
                )

                // Tags Chips
                if (state.tags.isEmpty()) {
                    DetailAddTagChip(colors = colors, onClick = onTagsClick)
                } else {
                    state.tags.forEach { tag ->
                        DetailActiveTagChip(tag = tag, colors = colors, onClick = onTagsClick)
                    }
                    DetailAddTagChip(colors = colors, onClick = onTagsClick, compact = true)
                }
            }

            // Legacy Plan Decision Banner (if pending)
            if (!state.pendingLegacyPlanDate.isNullOrBlank()) {
                LegacyPlanBanner(
                    planDate = state.pendingLegacyPlanDate,
                    colors = colors,
                    onAccept = onAcceptLegacyPlan,
                    onDismiss = onDismissLegacyPlan
                )
            }

            // Hairline Divider
            DetailHairlineDivider(colors)

            // --- 4. Subtasks Section ---
            SubtasksSection(
                subtasks = state.subtasks,
                colors = colors,
                onAddSubtask = onAddSubtask,
                onToggleSubtask = onToggleSubtask,
                onEditSubtask = onEditSubtask,
                onDeleteSubtask = onDeleteSubtask
            )

            // --- 5. Focus & Habit Integrations (Sleek Compact Container) ---
            val showIntegrations = state.hasFocusFeature || state.linkedHabit != null || !state.isCreateMode
            if (showIntegrations) {
                // Hairline Divider
                DetailHairlineDivider(colors)

                IntegrationGroupContainer(
                    hasFocusFeature = state.hasFocusFeature,
                    focusSummary = state.focusSummary,
                    linkedHabit = state.linkedHabit,
                    colors = colors,
                    onFocusClick = onFocusClick,
                    onHabitLinkClick = onHabitLinkClick,
                    onHabitUnlinkClick = onHabitUnlinkClick,
                    onHabitAccrualToggle = onHabitAccrualToggle
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// Top Bar Component
// ============================================================================

@Composable
private fun TaskDetailTopBar(
    isCreateMode: Boolean,
    saveStatusText: String,
    isSaveError: Boolean,
    isSaving: Boolean,
    canCreate: Boolean,
    colors: UHabitComposeColors,
    onClose: () -> Unit,
    onCreateSubmit: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onRetrySave: () -> Unit = {}
) {
    if (isCreateMode) {
        val configuration = LocalConfiguration.current
        val useStackedHeader = configuration.screenWidthDp < 360 && configuration.fontScale >= 1.4f

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Drag Handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color(colors.secondary).copy(alpha = 0.35f))
                )
            }

            // Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel Text Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onClose)
                        .testTag("taskDetailCancel")
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    BasicText(
                        text = stringResource(android.R.string.cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = Color(colors.secondary),
                            fontFamily = FontFamily.Default
                        )
                    )
                }

                if (useStackedHeader) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    // Title
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = stringResource(R.string.task_detail_new_task),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(colors.foreground),
                                fontFamily = FontFamily.Default
                            )
                        )
                    }
                }

                // Create Button (Filled pill)
                val buttonBg = if (canCreate) Color(colors.accent) else Color(colors.secondary).copy(alpha = 0.25f)
                val buttonText = if (canCreate) {
                    if (colors.isDark) Color(0xFF102638) else Color.White
                } else {
                    Color(colors.secondary).copy(alpha = 0.6f)
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(buttonBg)
                        .clickable(
                            enabled = canCreate,
                            role = Role.Button,
                            onClick = onCreateSubmit
                        )
                        .testTag("taskDetailCreate")
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.task_detail_create),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = buttonText,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }

            if (useStackedHeader) {
                BasicText(
                    text = stringResource(R.string.task_detail_new_task),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                    style = TextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(colors.foreground),
                        fontFamily = FontFamily.Default
                    )
                )
            }
        }
    } else {
        val showError = isSaveError || saveStatusText.isNotEmpty()
        val showSaving = isSaving

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp)
                .padding(horizontal = 12.dp)
                .height(44.dp)
        ) {
            // Collapse Button on Start
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = stringResource(R.string.task_detail_collapse),
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Centered Drag Handle
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(colors.secondary).copy(alpha = 0.35f))
            )

            // Actions on End: Status badge + More options button
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(colors.danger).copy(alpha = 0.12f))
                            .clickable(role = Role.Button, onClick = onRetrySave)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = stringResource(R.string.task_detail_retry_save),
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(colors.danger),
                                fontFamily = FontFamily.Default
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (showSaving) {
                    BasicText(
                        text = stringResource(R.string.task_detail_saving),
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = Color(colors.secondary).copy(alpha = 0.7f),
                            fontFamily = FontFamily.Default
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                // More Options Menu Button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onMoreOptionsClick)
                        .testTag("taskDetailEditActions"),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_toolbar_more_vertical),
                        contentDescription = stringResource(R.string.task_detail_actions),
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Check Affordance
// ============================================================================

@Composable
private fun TaskDetailCheckAffordance(
    isCompleted: Boolean,
    isCreateMode: Boolean,
    colors: UHabitComposeColors,
    onToggle: () -> Unit
) {
    val checkmarkScale by animateFloatAsState(
        targetValue = if (isCompleted) 1f else 0.5f,
        animationSpec = tween(durationMillis = 150),
        label = "checkScale"
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(
                enabled = !isCreateMode,
                role = Role.Checkbox,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        val circleSize = 22.dp

        if (isCompleted) {
            Box(
                modifier = Modifier
                    .size(circleSize)
                    .background(Color(colors.secondary).copy(alpha = 0.14f), CircleShape)
                    .border(1.dp, Color(colors.secondary).copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_action_check),
                    contentDescription = stringResource(R.string.task_detail_completed_desc),
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier
                        .size(12.dp)
                        .scale(checkmarkScale)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(circleSize)
                    .border(1.5.dp, Color(colors.secondary).copy(alpha = 0.45f), CircleShape)
            )
        }
    }
}

// ============================================================================
// Property Chips
// ============================================================================

@Composable
private fun DetailDueChip(
    due: TaskDue,
    formatted: String,
    isOverdue: Boolean,
    colors: UHabitComposeColors,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    val hasDue = due !is TaskDue.None
    val iconRes = if (due is TaskDue.Timed) R.drawable.ic_cat_calendar_clock else R.drawable.ic_cat_calendar

    val chipBg = when {
        isOverdue -> Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt()).copy(alpha = 0.12f)
        hasDue -> Color(colors.accent).copy(alpha = 0.1f)
        else -> Color(colors.surfaceVariant).copy(alpha = 0.6f)
    }
    val chipBorder = when {
        isOverdue -> Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt()).copy(alpha = 0.4f)
        hasDue -> Color(colors.accent).copy(alpha = 0.45f)
        else -> Color(colors.border).copy(alpha = 0.6f)
    }
    val contentColor = when {
        isOverdue -> Color(if (colors.danger != 0) colors.danger else 0xFFEF5350.toInt())
        hasDue -> Color(colors.accent)
        else -> Color(colors.secondary)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(chipBg)
            .border(1.dp, chipBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (hasDue) 6.dp else 10.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        BasicText(
            text = if (hasDue) formatted else stringResource(R.string.task_detail_due),
            modifier = Modifier.testTag("taskDetailDue"),
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = if (hasDue) FontWeight.Medium else FontWeight.Normal,
                color = contentColor,
                fontFamily = FontFamily.Default
            )
        )
        if (hasDue) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.task_detail_clear_due),
                    colorFilter = ColorFilter.tint(contentColor.copy(alpha = 0.7f)),
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailContainerChip(
    containerName: String?,
    containerColor: Int?,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    val name = containerName ?: stringResource(R.string.task_detail_inbox)
    val hasContainer = !containerName.isNullOrBlank()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(colors.surfaceVariant).copy(alpha = 0.6f))
            .border(1.dp, Color(colors.border).copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("taskDetailContainer")
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (containerColor != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(containerColor))
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_cat_folder),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary)),
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        BasicText(
            text = name,
            modifier = Modifier.testTag("taskDetailContainerLabel"),
            style = TextStyle(
                fontSize = 12.5.sp,
                color = Color(colors.foreground),
                fontFamily = FontFamily.Default
            )
        )
    }
}

@Composable
private fun DetailMatrixChip(
    label: String,
    iconRes: Int,
    isActive: Boolean,
    isExplicitNegative: Boolean,
    accentColor: Color,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    val chipBg = when {
        isActive -> accentColor.copy(alpha = 0.14f)
        isExplicitNegative -> Color(colors.secondary).copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val chipBorder = when {
        isActive -> accentColor.copy(alpha = 0.5f)
        isExplicitNegative -> Color(colors.border).copy(alpha = 0.4f)
        else -> Color(colors.border).copy(alpha = 0.6f)
    }
    val contentColor = when {
        isActive -> accentColor
        isExplicitNegative -> Color(colors.secondary)
        else -> Color(colors.secondary)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(chipBg)
            .border(1.dp, chipBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        BasicText(
            text = label,
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = contentColor,
                fontFamily = FontFamily.Default
            )
        )
    }
}

@Composable
private fun DetailAddTagChip(
    colors: UHabitComposeColors,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
            .border(1.dp, Color(colors.border).copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_cat_tag),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color(colors.secondary)),
            modifier = Modifier.size(13.dp)
        )
        if (!compact) {
            Spacer(modifier = Modifier.width(4.dp))
            BasicText(
                text = stringResource(R.string.task_detail_add_tag),
                style = TextStyle(
                    fontSize = 12.5.sp,
                    color = Color(colors.secondary),
                    fontFamily = FontFamily.Default
                )
            )
        }
    }
}

@Composable
private fun DetailActiveTagChip(
    tag: TagItemState,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(colors.surfaceVariant).copy(alpha = 0.6f))
            .border(1.dp, Color(colors.border).copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        if (tag.color != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(tag.color))
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        BasicText(
            text = tag.name,
            style = TextStyle(
                fontSize = 12.sp,
                color = Color(colors.foreground),
                fontFamily = FontFamily.Default
            )
        )
    }
}

// ============================================================================
// Subtasks Section
// ============================================================================

@Composable
private fun SubtasksSection(
    subtasks: List<SubtaskItemState>,
    colors: UHabitComposeColors,
    onAddSubtask: (String) -> Unit,
    onToggleSubtask: (id: String) -> Unit,
    onEditSubtask: (id: String) -> Unit,
    onDeleteSubtask: (id: String) -> Unit
) {
    var newSubtaskText by remember { mutableStateOf("") }

    val total = subtasks.size
    val completed = subtasks.count { it.isCompleted }
    val allCompleted = completed == total && total > 0

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = stringResource(R.string.task_detail_subtasks),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(colors.foreground),
                    fontFamily = FontFamily.Default
                )
            )
            Spacer(modifier = Modifier.weight(1f))
            if (total > 0) {
                BasicText(
                    text = if (allCompleted) stringResource(R.string.task_detail_subtasks_all_done, total) else "$completed / $total",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = if (allCompleted) FontWeight.Medium else FontWeight.Normal,
                        color = if (allCompleted) Color(colors.accent) else Color(colors.secondary),
                        fontFamily = FontFamily.Default
                    )
                )
            }
        }

        // Subtask List Items
        subtasks.forEach { item ->
            SubtaskRowItem(
                item = item,
                colors = colors,
                onToggle = { onToggleSubtask(item.id) },
                onEdit = { onEditSubtask(item.id) },
                onDelete = { onDeleteSubtask(item.id) }
            )
        }

        // Sequential Add Subtask Input Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = newSubtaskText.isNotBlank(),
                        onClick = {
                            if (newSubtaskText.isNotBlank()) {
                                onAddSubtask(newSubtaskText.trim())
                                newSubtaskText = ""
                            }
                        }
                    )
                    .testTag("taskDetailAddSubtask"),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_toolbar_add),
                    contentDescription = stringResource(R.string.task_detail_add_subtask_desc),
                    colorFilter = ColorFilter.tint(
                        if (newSubtaskText.isNotBlank()) Color(colors.accent) else Color(colors.secondary).copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            BasicTextField(
                value = newSubtaskText,
                onValueChange = { newSubtaskText = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("taskDetailSubtaskInput")
                    .padding(vertical = 6.dp),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = Color(colors.foreground),
                    fontFamily = FontFamily.Default
                ),
                cursorBrush = SolidColor(Color(colors.accent)),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (newSubtaskText.isNotBlank()) {
                            onAddSubtask(newSubtaskText.trim())
                            newSubtaskText = ""
                        }
                    }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (newSubtaskText.isEmpty()) {
                            BasicText(
                                text = stringResource(R.string.task_detail_add_subtask_hint),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = Color(colors.secondary).copy(alpha = 0.5f)
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun SubtaskRowItem(
    item: SubtaskItemState,
    colors: UHabitComposeColors,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Subtask circular checkbox
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Checkbox, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            val circleSize = 18.dp
            if (item.isCompleted) {
                Box(
                    modifier = Modifier
                        .size(circleSize)
                        .background(Color(colors.secondary).copy(alpha = 0.14f), CircleShape)
                        .border(1.dp, Color(colors.secondary).copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_action_check),
                        contentDescription = stringResource(R.string.task_detail_completed_desc),
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier.size(10.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(circleSize)
                        .border(1.3.dp, Color(colors.secondary).copy(alpha = 0.4f), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Title
        val textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null
        val textColor = if (item.isCompleted) Color(colors.secondary) else Color(colors.foreground)

        BasicText(
            text = item.title,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 4.dp),
            style = TextStyle(
                fontSize = 14.sp,
                color = textColor,
                textDecoration = textDecoration,
                fontFamily = FontFamily.Default
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Delete button
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.task_detail_delete_subtask_desc),
                colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.5f)),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

// ============================================================================
// Focus & Habit Integrations (Sleek Grouped Container)
// ============================================================================

@Composable
private fun IntegrationGroupContainer(
    hasFocusFeature: Boolean,
    focusSummary: String?,
    linkedHabit: LinkedHabitState?,
    colors: UHabitComposeColors,
    onFocusClick: () -> Unit,
    onHabitLinkClick: () -> Unit,
    onHabitUnlinkClick: () -> Unit,
    onHabitAccrualToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(colors.surfaceVariant).copy(alpha = 0.35f))
            .border(0.5.dp, Color(colors.border).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
    ) {
        // 1. Focus Row
        if (hasFocusFeature) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onFocusClick)
                    .testTag("taskDetailFocus")
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_cat_focus),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.accent)),
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    BasicText(
                        text = stringResource(R.string.task_detail_focus),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(colors.foreground),
                            fontFamily = FontFamily.Default
                        )
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    BasicText(
                        text = focusSummary ?: stringResource(R.string.task_detail_focus_start_prompt),
                        modifier = Modifier.testTag("taskDetailFocusSummary"),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 11.5.sp,
                            color = Color(colors.secondary),
                            fontFamily = FontFamily.Default
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(colors.accent).copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.task_detail_start),
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(colors.accent),
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }

            // Divider between Focus and Habit
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp)
                    .height(0.5.dp)
                    .background(Color(colors.border).copy(alpha = 0.3f))
            )
        }

        // 2. Habit Link Row
        if (linkedHabit == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onHabitLinkClick)
                    .testTag("taskDetailHabitLink")
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_cat_list_todo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    BasicText(
                        text = stringResource(R.string.task_detail_habit_link),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(colors.foreground),
                            fontFamily = FontFamily.Default
                        )
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    BasicText(
                        text = stringResource(R.string.task_detail_habit_link_subtitle),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 11.5.sp,
                            color = Color(colors.secondary),
                            fontFamily = FontFamily.Default
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(colors.secondary).copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.task_detail_link_action),
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(colors.foreground),
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val habitColor = linkedHabit.color?.let { Color(it) } ?: Color(colors.accent)
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(habitColor)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = linkedHabit.name,
                            modifier = Modifier.testTag("taskDetailLinkedHabit"),
                            style = TextStyle(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(colors.foreground),
                                fontFamily = FontFamily.Default
                            )
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        BasicText(
                            text = "${linkedHabit.unit} · " + stringResource(
                                if (linkedHabit.timeAccrualEnabled) R.string.task_detail_accrual_on else R.string.task_detail_accrual_off
                            ),
                            modifier = Modifier.testTag("taskDetailHabitAccrualSummary"),
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                color = Color(colors.secondary),
                                fontFamily = FontFamily.Default
                            )
                        )
                    }

                    // Change button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onHabitLinkClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = stringResource(R.string.task_detail_change),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = Color(colors.accent),
                                fontFamily = FontFamily.Default
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Unlink button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onHabitUnlinkClick)
                            .testTag("taskDetailHabitUnlink"),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.task_detail_unlink),
                            colorFilter = ColorFilter.tint(Color(colors.secondary)),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                if (!linkedHabit.isArchived && !linkedHabit.isIncompatible) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    ) {
                        BasicText(
                            text = stringResource(R.string.task_detail_accrual_toggle_label),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = Color(colors.foreground).copy(alpha = 0.85f),
                                fontFamily = FontFamily.Default
                            )
                        )

                        CustomCompactSwitch(
                            checked = linkedHabit.timeAccrualEnabled,
                            colors = colors,
                            onCheckedChange = onHabitAccrualToggle
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomCompactSwitch(
    checked: Boolean,
    colors: UHabitComposeColors,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackBg = if (checked) Color(colors.accent) else Color(colors.secondary).copy(alpha = 0.25f)
    val label = stringResource(R.string.task_detail_accrual_toggle_label)
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 14f else 0f,
        animationSpec = tween(150),
        label = "switch"
    )

    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(trackBg)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag("taskDetailHabitAccrual")
            .semantics { contentDescription = label }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .padding(start = thumbOffset.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

// ============================================================================
// Legacy Plan Banner & Dividers
// ============================================================================

@Composable
private fun LegacyPlanBanner(
    planDate: String,
    colors: UHabitComposeColors,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(colors.warning).copy(alpha = 0.12f))
            .border(1.dp, Color(colors.warning).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        BasicText(
            text = stringResource(R.string.task_detail_legacy_plan_prompt, planDate),
            style = TextStyle(
                fontSize = 13.sp,
                color = Color(colors.foreground),
                lineHeight = 18.sp,
                fontFamily = FontFamily.Default
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.task_detail_dismiss),
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        color = Color(colors.secondary),
                        fontFamily = FontFamily.Default
                    )
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAccept)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.task_detail_accept),
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(colors.accent),
                        fontFamily = FontFamily.Default
                    )
                )
            }
        }
    }
}

@Composable
private fun DetailHairlineDivider(colors: UHabitComposeColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(0.5.dp)
            .background(Color(colors.divider).copy(alpha = 0.35f))
    )
}
