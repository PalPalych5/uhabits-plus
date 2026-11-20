/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.userTasks.tags.TaskTag

/** Filters tags case-insensitively by trimmed [query]. */
fun filterTaskTags(tags: List<TaskTag>, query: String): List<TaskTag> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return tags
    return tags.filter { it.name.contains(trimmed, ignoreCase = true) }
}

/** Formats the trimmed search query for the "+ Create ..." action, or returns null when blank. */
fun formatCreateTagQuery(query: String): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.take(40)
}

/** Toggles [tagId] in [selectedIds] without mutating the input set. */
fun toggleTagSelection(selectedIds: Set<String>, tagId: String): Set<String> =
    if (tagId in selectedIds) selectedIds - tagId else selectedIds + tagId

@Composable
fun TaskTagPickerContent(
    title: String,
    tags: List<TaskTag>,
    selectedIds: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onRenameTag: (TaskTag) -> Unit,
    onDeleteTag: (TaskTag) -> Unit,
    onCreateTag: () -> Unit,
    onClearSelection: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    val searchInteractionSource = remember { MutableInteractionSource() }
    val matches = remember(tags, query) { filterTaskTags(tags, query) }
    val createQuery = remember(query) { formatCreateTagQuery(query) }
    val createLabel = if (createQuery == null) {
        stringResource(R.string.task_tag_picker_create_new)
    } else {
        stringResource(R.string.task_tag_picker_create_named, createQuery)
    }
    val searchHint = stringResource(R.string.task_tag_picker_search_hint)
    val searchDesc = stringResource(R.string.task_tag_picker_search_desc)

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = colors.foregroundColor,
                fontFamily = FontFamily.Default
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
        )

        Text(
            text = stringResource(R.string.task_tag_picker_subtitle),
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = colors.secondaryColor,
                fontFamily = FontFamily.Default
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceVariantColor)
                .clickable(
                    interactionSource = searchInteractionSource,
                    indication = null
                ) { searchFocusRequester.requestFocus() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = colors.foregroundColor,
                    fontFamily = FontFamily.Default
                ),
                cursorBrush = SolidColor(colors.accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
                    .semantics { contentDescription = searchDesc },
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = searchHint,
                            style = TextStyle(
                                fontSize = 15.sp,
                                color = colors.secondaryColor,
                                fontFamily = FontFamily.Default
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(matches, key = { it.id }) { tag ->
                TaskTagPickerRow(
                    tag = tag,
                    isSelected = tag.id in selectedIds,
                    colors = colors,
                    onToggle = { onToggleTag(tag.id) },
                    onRename = { onRenameTag(tag) },
                    onDelete = { onDeleteTag(tag) }
                )
            }

            if (matches.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) R.string.task_tag_picker_empty else R.string.task_tag_picker_not_found
                        ),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = colors.secondaryColor,
                            fontFamily = FontFamily.Default
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 16.dp)
                    )
                }
            }

            item(key = "create") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onCreateTag),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = createLabel,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.accentColor,
                            fontFamily = FontFamily.Default
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onClearSelection)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = stringResource(R.string.task_tag_picker_clear_selection),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accentColor,
                        fontFamily = FontFamily.Default
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onApply)
                    .padding(start = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.task_due_apply),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accentColor,
                        fontFamily = FontFamily.Default
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TaskTagPickerRow(
    tag: TaskTag,
    isSelected: Boolean,
    colors: UHabitComposeColors,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val tagDesc = stringResource(R.string.task_tag_picker_tag_desc, tag.name)
    val actionsDesc = stringResource(R.string.task_tag_picker_actions_desc, tag.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .toggleable(
                    value = isSelected,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() }
                )
                .semantics {
                    contentDescription = tagDesc
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TagAccentCheckbox(
                checked = isSelected,
                accentColor = colors.accentColor,
                uncheckedBorderColor = colors.secondaryColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = tag.name,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = colors.foregroundColor,
                    fontFamily = FontFamily.Default
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { menuExpanded = true }
                    .semantics { contentDescription = actionsDesc },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⋮",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accentColor,
                        fontFamily = FontFamily.Default
                    )
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = colors.dialogSurfaceColor
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.task_tag_picker_rename),
                            color = colors.foregroundColor
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.delete),
                            color = colors.foregroundColor
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun TagAccentCheckbox(
    checked: Boolean,
    accentColor: Color,
    uncheckedBorderColor: Color
) {
    val shape = RoundedCornerShape(3.dp)
    val checkColor = if (accentColor.luminance() > 0.55f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .then(
                if (checked) {
                    Modifier.background(accentColor)
                } else {
                    Modifier.border(width = 1.75.dp, color = uncheckedBorderColor, shape = shape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Canvas(modifier = Modifier.size(12.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.78f)
                    lineTo(size.width * 0.84f, size.height * 0.24f)
                }
                drawPath(
                    path = path,
                    color = checkColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}
