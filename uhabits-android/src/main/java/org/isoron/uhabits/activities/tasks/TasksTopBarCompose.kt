/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

/**
 * Top App Bar and Multi-selection Bar rendered in Compose.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TasksTopBarCompose(
    title: String,
    selectedCount: Int,
    isAllSelectedCompleted: Boolean = false,
    isCalendarMode: Boolean = false,
    isWide: Boolean = false,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    onOpenNavigation: () -> Unit = {},
    onTitleLongClick: (() -> Unit)? = null,
    onSwitchRepresentation: () -> Unit = {},
    onOpenOverflowMenu: () -> Unit = {},
    onCloseSelection: () -> Unit = {},
    onSelectionDue: () -> Unit = {},
    onSelectionContainer: () -> Unit = {},
    onSelectionComplete: () -> Unit = {},
    onSelectionDelete: () -> Unit = {},
    onSelectionMore: () -> Unit = {}
) {
    UHabitComposeTheme(colors = colors) {
        val themeColors = LocalUHabitComposeColors.current
        val context = LocalContext.current
        val isSelectionActive = selectedCount > 0
        val selectionBg = if (isSelectionActive) {
            Color(themeColors.accent).copy(alpha = if (themeColors.isDark) 0.14f else 0.08f)
        } else {
            Color.Transparent
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(selectionBg)
                .statusBarsPadding()
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp)
                .semantics { testTagsAsResourceId = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionActive) {
                // Multi-Selection Bar
                val exitDesc = stringResource(R.string.task_selection_exit_desc)
                val countText = context.getString(R.string.task_selection_count, selectedCount)
                val countDesc = context.getString(R.string.task_selection_count_desc, selectedCount)

                // Exit selection button
                TopBarIconButton(
                    iconRes = R.drawable.ic_close,
                    contentDescription = exitDesc,
                    tint = Color(themeColors.foreground),
                    onClick = onCloseSelection,
                    modifier = Modifier.testTag("btnSelectionClose")
                )

                // Selection count
                BasicText(
                    text = countText,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .testTag("tvSelectionCount")
                        .semantics { this.contentDescription = countDesc },
                    style = TextStyle(
                        color = Color(themeColors.foreground),
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Action: Due Date
                TopBarIconButton(
                    iconRes = R.drawable.ic_cat_calendar,
                    contentDescription = stringResource(R.string.task_selection_due_desc),
                    tint = Color(themeColors.foreground),
                    onClick = onSelectionDue,
                    modifier = Modifier.testTag("btnSelectionDue")
                )

                // Action: Move Container
                TopBarIconButton(
                    iconRes = R.drawable.ic_cat_folder,
                    contentDescription = stringResource(R.string.task_selection_move_desc),
                    tint = Color(themeColors.foreground),
                    onClick = onSelectionContainer,
                    modifier = Modifier.testTag("btnSelectionContainer")
                )

                // Action: Complete or Reopen
                val completeIcon = if (isAllSelectedCompleted) R.drawable.ic_cat_refresh_cw else R.drawable.ic_action_check
                val completeDesc = stringResource(
                    if (isAllSelectedCompleted) R.string.task_selection_reopen_desc else R.string.task_selection_complete_desc
                )
                TopBarIconButton(
                    iconRes = completeIcon,
                    contentDescription = completeDesc,
                    tint = Color(themeColors.accent),
                    onClick = onSelectionComplete,
                    modifier = Modifier.testTag("btnSelectionComplete")
                )

                // Action: Delete
                val dangerColor = Color(if (themeColors.danger != 0) themeColors.danger else 0xFFEF5350.toInt())
                TopBarIconButton(
                    iconRes = R.drawable.ic_settings_trash,
                    contentDescription = stringResource(R.string.task_selection_delete_desc),
                    tint = dangerColor,
                    onClick = onSelectionDelete,
                    modifier = Modifier.testTag("btnSelectionDelete")
                )

                // Action: More (only for single selection)
                if (selectedCount == 1) {
                    TopBarIconButton(
                        iconRes = R.drawable.ic_toolbar_more_vertical,
                        contentDescription = stringResource(R.string.task_row_a11y_actions),
                        tint = Color(themeColors.secondary),
                        onClick = onSelectionMore,
                        modifier = Modifier.testTag("btnSelectionMore")
                    )
                }
            } else {
                // Normal App Bar
                if (!isWide) {
                    TopBarIconButton(
                        iconRes = R.drawable.ic_tasks_menu,
                        contentDescription = stringResource(R.string.task_nav_open_desc),
                        tint = Color(themeColors.secondary),
                        onClick = onOpenNavigation,
                        modifier = Modifier.testTag("btnNavOpen")
                    )
                }

                // Title
                val titleModifier = if (onTitleLongClick != null) {
                    Modifier
                        .weight(1f)
                        .padding(start = if (isWide) 12.dp else 4.dp, end = 4.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onTitleLongClick
                        )
                } else {
                    Modifier
                        .weight(1f)
                        .padding(start = if (isWide) 12.dp else 4.dp, end = 4.dp)
                }

                BasicText(
                    text = title,
                    modifier = titleModifier.testTag("tvTasksTitle"),
                    style = TextStyle(
                        color = Color(themeColors.foreground),
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Representation Switcher (List / Calendar)
                val repIcon = if (isCalendarMode) R.drawable.ic_cat_list_todo else R.drawable.ic_cat_calendar
                val repDesc = stringResource(
                    if (isCalendarMode) R.string.task_view_list else R.string.task_view_calendar
                )
                TopBarIconButton(
                    iconRes = repIcon,
                    contentDescription = repDesc,
                    tint = Color(themeColors.secondary),
                    onClick = onSwitchRepresentation,
                    modifier = Modifier.testTag("btnRepresentation")
                )

                // Overflow Menu
                TopBarIconButton(
                    iconRes = R.drawable.ic_toolbar_more_vertical,
                    contentDescription = stringResource(R.string.task_list_actions_desc),
                    tint = Color(themeColors.secondary),
                    onClick = onOpenOverflowMenu,
                    modifier = Modifier.testTag("btnTasksOverflow")
                )
            }
        }
    }
}

@Composable
private fun TopBarIconButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(20.dp)
        )
    }
}
