/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.userTasks.due.TaskDue

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun QuickAddCompose(
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    title: String,
    onTitleChange: (String) -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit = {},
    onOpenDetail: () -> Unit = {},
    onSubmit: () -> Unit,
    due: TaskDue = TaskDue.None,
    dueFormatted: String = "",
    container: ContainerId? = null,
    containerName: String? = null,
    containerColor: Int? = null,
    tags: Set<String> = emptySet(),
    tagLabel: String = "",
    onOpenDuePicker: () -> Unit = {},
    onOpenContainerPicker: () -> Unit = {},
    onOpenTagPicker: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { testTagsAsResourceId = true }
            .background(colors.backgroundColor)
    ) {
        // Top Hairline Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.resolveDividerColor())
        )

        if (!expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(onClick = onExpand)
                    .testTag("layoutQuickAddCollapsed")
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_toolbar_add),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.accentColor),
                    modifier = Modifier.size(18.dp)
                )
                BasicText(
                    text = stringResource(R.string.task_quick_add_hint),
                    modifier = Modifier.padding(start = 12.dp),
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = colors.secondaryColor,
                        fontFamily = FontFamily.Default
                    )
                )
            }
        } else {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }

            // Input Row: text field + expand button + submit button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag("etQuickAddTitle"),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = colors.foregroundColor,
                        fontFamily = FontFamily.Default
                    ),
                    cursorBrush = SolidColor(colors.accentColor),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (title.isNotBlank()) onSubmit()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (title.isEmpty()) {
                                BasicText(
                                    text = stringResource(R.string.task_quick_add_hint),
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        color = colors.secondaryColor.copy(alpha = 0.6f),
                                        fontFamily = FontFamily.Default
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                IconButton(
                    onClick = onOpenDetail,
                    modifier = Modifier.testTag("btnQuickAddExpand")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_up),
                        contentDescription = stringResource(R.string.task_quick_add_expand_desc),
                        tint = colors.secondaryColor
                    )
                }
                IconButton(
                    onClick = onSubmit,
                    enabled = title.isNotBlank(),
                    modifier = Modifier.testTag("btnQuickAddSubmit")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_toolbar_add),
                        contentDescription = stringResource(R.string.task_quick_add_submit_desc),
                        tint = if (title.isNotBlank()) colors.accentColor else colors.secondaryColor.copy(alpha = 0.45f)
                    )
                }
            }

            // Compact Metadata Options Row (Due, Container, Tags)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Due Chip
                val hasDue = due !is TaskDue.None
                val dueText = if (hasDue && dueFormatted.isNotBlank()) dueFormatted else stringResource(R.string.task_due_set_prompt)
                val dueIconRes = if (due is TaskDue.Timed) R.drawable.ic_cat_calendar_clock else R.drawable.ic_cat_calendar
                val dueContentColor = if (hasDue) colors.accentColor else colors.secondaryColor
                val dueBgColor = if (hasDue) colors.accentColor.copy(alpha = 0.12f) else colors.surfaceVariantColor.copy(alpha = 0.35f)
                val dueBorderColor = if (hasDue) colors.accentColor.copy(alpha = 0.45f) else colors.borderColor.copy(alpha = 0.5f)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(dueBgColor)
                        .border(1.dp, dueBorderColor, RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenDuePicker)
                        .testTag("quickDue")
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Image(
                        painter = painterResource(dueIconRes),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(dueContentColor),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    BasicText(
                        text = dueText,
                        modifier = Modifier.testTag("quickDueLabel"),
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = if (hasDue) FontWeight.Medium else FontWeight.Normal,
                            color = dueContentColor,
                            fontFamily = FontFamily.Default
                        )
                    )
                }

                // Container Chip
                val hasContainer = container != null
                val containerText = containerName ?: stringResource(R.string.task_smart_inbox)
                val containerBorderColor = if (hasContainer) colors.borderColor.copy(alpha = 0.7f) else colors.borderColor.copy(alpha = 0.5f)
                val containerContentColor = if (hasContainer) colors.foregroundColor else colors.secondaryColor

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surfaceVariantColor.copy(alpha = 0.35f))
                        .border(1.dp, containerBorderColor, RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenContainerPicker)
                        .testTag("quickContainer")
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
                            colorFilter = ColorFilter.tint(colors.secondaryColor),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    BasicText(
                        text = containerText,
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            color = containerContentColor,
                            fontFamily = FontFamily.Default
                        )
                    )
                }

                // Tags Chip
                val hasTags = tags.isNotEmpty()
                val textTags = if (tagLabel.isNotBlank()) tagLabel else stringResource(R.string.task_tags_label)
                val tagsContentColor = if (hasTags) colors.accentColor else colors.secondaryColor
                val tagsBgColor = if (hasTags) colors.accentColor.copy(alpha = 0.12f) else colors.surfaceVariantColor.copy(alpha = 0.35f)
                val tagsBorderColor = if (hasTags) colors.accentColor.copy(alpha = 0.45f) else colors.borderColor.copy(alpha = 0.5f)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(tagsBgColor)
                        .border(1.dp, tagsBorderColor, RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenTagPicker)
                        .testTag("quickTags")
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_cat_tag),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tagsContentColor),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    BasicText(
                        text = textTags,
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = if (hasTags) FontWeight.Medium else FontWeight.Normal,
                            color = tagsContentColor,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }
        }
    }
}
