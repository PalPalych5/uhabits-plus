/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.facade.OrganizationalItem

enum class PickerIconKind {
    CloseSmall,
    Close,
    Search,
    Check,
    Chevron,
    Branch,
    Inbox
}

data class ExternalSearchCommand(
    val query: String,
    val token: Long = System.nanoTime()
)

data class ContainerPickerRowItem(
    val item: OrganizationalItem?,
    val title: String,
    val pathSubtitle: String?,
    val depth: Int,
    val isSelected: Boolean,
    val hasChildren: Boolean,
    val isParent: Boolean
)

fun buildContainerPickerRows(
    items: List<OrganizationalItem>,
    paths: Map<String, List<Container>>,
    selectedId: String?,
    query: String,
    inboxTitle: String
): List<ContainerPickerRowItem> {
    val searching = query.isNotBlank()
    val rows = mutableListOf<ContainerPickerRowItem>()
    if (!searching) {
        rows.add(
            ContainerPickerRowItem(
                item = null,
                title = inboxTitle,
                pathSubtitle = null,
                depth = 0,
                isSelected = (selectedId == null),
                hasChildren = false,
                isParent = false
            )
        )
    }
    val matching = if (searching) {
        items.filter { it.name.contains(query.trim(), ignoreCase = true) }
    } else {
        items
    }
    for (item in matching) {
        val path = paths[item.key].orEmpty()
        val parentPath = path.dropLast(1).joinToString(" / ") { it.name }
        val hasChildren = paths.values.any { candidate ->
            candidate.dropLast(1).lastOrNull()?.id?.value == item.key
        }
        val depth = if (searching) 0 else (path.size - 1).coerceAtLeast(0)
        val isParent = hasChildren && !searching
        rows.add(
            ContainerPickerRowItem(
                item = item,
                title = item.name,
                pathSubtitle = parentPath.takeIf { searching && it.isNotEmpty() },
                depth = depth,
                isSelected = (selectedId == item.key),
                hasChildren = hasChildren,
                isParent = isParent
            )
        )
    }
    return rows
}

@Composable
fun PickerCanvasIcon(
    kind: PickerIconKind,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke2dp = 2.dp.toPx()
        val stroke1dp = 1.dp.toPx()
        val stroke = Stroke(width = stroke2dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float = stroke2dp) {
            drawLine(
                color = color,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        when (kind) {
            PickerIconKind.CloseSmall -> {
                val s = 4.5f.dp.toPx()
                line(cx - s, cy - s, cx + s, cy + s)
                line(cx + s, cy - s, cx - s, cy + s)
            }
            PickerIconKind.Close -> {
                val s = 6f.dp.toPx()
                line(cx - s, cy - s, cx + s, cy + s)
                line(cx + s, cy - s, cx - s, cy + s)
            }
            PickerIconKind.Search -> {
                drawCircle(
                    color = color,
                    radius = 4f.dp.toPx(),
                    center = Offset(cx - 1f.dp.toPx(), cy - 1f.dp.toPx()),
                    style = stroke
                )
                line(cx + 2f.dp.toPx(), cy + 2f.dp.toPx(), cx + 6f.dp.toPx(), cy + 6f.dp.toPx())
            }
            PickerIconKind.Check -> {
                line(cx - 5f.dp.toPx(), cy, cx - 1f.dp.toPx(), cy + 4f.dp.toPx())
                line(cx - 1f.dp.toPx(), cy + 4f.dp.toPx(), cx + 6f.dp.toPx(), cy - 4f.dp.toPx())
            }
            PickerIconKind.Chevron -> {
                val x = size.width - 18f.dp.toPx()
                line(x - 3f.dp.toPx(), cy - 1f.dp.toPx(), x, cy + 2f.dp.toPx())
                line(x, cy + 2f.dp.toPx(), x + 3f.dp.toPx(), cy - 1f.dp.toPx())
            }
            PickerIconKind.Branch -> {
                val x = size.width - 20f.dp.toPx()
                line(x, 0f, x, cy, strokeWidth = stroke1dp)
                line(x, cy, x + 12f.dp.toPx(), cy, strokeWidth = stroke1dp)
            }
            PickerIconKind.Inbox -> {
                val x = 10f.dp.toPx()
                val path = Path().apply {
                    moveTo(x - 7f.dp.toPx(), cy + 4f.dp.toPx())
                    lineTo(x - 7f.dp.toPx(), cy)
                    lineTo(x - 3f.dp.toPx(), cy - 5f.dp.toPx())
                    lineTo(x + 3f.dp.toPx(), cy - 5f.dp.toPx())
                    lineTo(x + 7f.dp.toPx(), cy)
                    lineTo(x + 7f.dp.toPx(), cy + 4f.dp.toPx())
                    close()
                }
                drawPath(path, color = color, style = stroke)
                line(x - 7f.dp.toPx(), cy, x - 2f.dp.toPx(), cy)
                line(x - 2f.dp.toPx(), cy, x - 1f.dp.toPx(), cy + 2f.dp.toPx())
                line(x - 1f.dp.toPx(), cy + 2f.dp.toPx(), x + 1f.dp.toPx(), cy + 2f.dp.toPx())
                line(x + 1f.dp.toPx(), cy + 2f.dp.toPx(), x + 2f.dp.toPx(), cy)
                line(x + 2f.dp.toPx(), cy, x + 7f.dp.toPx(), cy)
            }
        }
    }
}

@Composable
fun TaskContainerPickerContent(
    items: List<OrganizationalItem>,
    containerPaths: Map<String, List<Container>>,
    selectedContainerId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    pathExpanded: Boolean,
    onTogglePathExpanded: () -> Unit,
    onSelect: (OrganizationalItem?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    externalSearchCommand: ExternalSearchCommand? = null
) {
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSearchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(externalSearchCommand) {
        externalSearchCommand?.let { cmd ->
            onQueryChange(cmd.query)
            if (cmd.query.isNotEmpty()) {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    val searching = query.isNotBlank()
    val matching = if (searching) {
        items.filter { it.name.contains(query.trim(), ignoreCase = true) }
    } else {
        items
    }
    val rows = remember(items, containerPaths, selectedContainerId, query) {
        buildContainerPickerRows(
            items = items,
            paths = containerPaths,
            selectedId = selectedContainerId,
            query = query,
            inboxTitle = "" // replaced below dynamically
        )
    }
    val inboxTitle = stringResource(R.string.task_smart_inbox)
    val displayRows = remember(rows, inboxTitle) {
        if (rows.isNotEmpty() && rows.first().item == null) {
            listOf(rows.first().copy(title = inboxTitle)) + rows.drop(1)
        } else {
            rows
        }
    }

    val selectedItem = remember(items, selectedContainerId) {
        items.firstOrNull { it.key == selectedContainerId }
    }
    val fullPath = remember(selectedItem, containerPaths) {
        selectedItem?.let {
            containerPaths[it.key]?.joinToString(" / ") { c -> c.name }
        }.orEmpty()
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // --- Header (56dp) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            // Drag handle at top
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(colors.divider))
                    .align(Alignment.TopCenter)
            )

            // Title and Close action in bottom 48dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.task_container_picker_title),
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(colors.foreground),
                        fontFamily = FontFamily.Default
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onClose() }
                        .semantics {
                            contentDescription = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.task_container_picker_close_desc),
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // --- Search Frame (48dp) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background capsule
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(colors.surfaceVariant))
            )

            // Search Icon
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(48.dp)
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                PickerCanvasIcon(
                    kind = PickerIconKind.Search,
                    color = Color(colors.secondary),
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Input field
            val searchDesc = stringResource(R.string.task_container_picker_search_desc)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = Color(colors.foreground),
                    fontFamily = FontFamily.Default
                ),
                cursorBrush = SolidColor(Color(colors.accent)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 38.dp, end = 44.dp)
                    .align(Alignment.CenterStart)
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { isSearchFocused = it.isFocused }
                    .semantics {
                        contentDescription = searchDesc
                    },
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.task_container_picker_search_hint),
                            style = TextStyle(
                                fontSize = 15.sp,
                                color = Color(colors.secondary),
                                fontFamily = FontFamily.Default
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            )

            // Clear Icon
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 48.dp)
                        .align(Alignment.CenterEnd)
                        .clickable { onQueryChange("") }
                        .semantics {
                            contentDescription = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    PickerCanvasIcon(
                        kind = PickerIconKind.CloseSmall,
                        color = Color(colors.secondary),
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = ""
                            }
                    )
                }
            }

            // Focus Underline
            if (isSearchFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(top = 41.dp)
                        .height(1.dp)
                        .background(Color(colors.accent))
                        .align(Alignment.TopStart)
                )
            }
        }

        // --- Container List (Scrollable, weight 1f) ---
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp, bottom = 12.dp)
        ) {
            items(displayRows, key = { it.item?.key ?: "inbox" }) { row ->
                ContainerPickerRow(
                    row = row,
                    colors = colors,
                    searching = searching,
                    onSelect = onSelect
                )
            }

            if (matching.isEmpty()) {
                item(key = "empty_state") {
                    Text(
                        text = stringResource(
                            if (searching) R.string.task_container_picker_not_found
                            else R.string.task_container_picker_empty
                        ),
                        style = TextStyle(
                            fontSize = 15.sp,
                            color = Color(colors.secondary),
                            fontFamily = FontFamily.Default
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }

        // --- Expandable Footer ---
        ContainerPickerFooter(
            selectedItem = selectedItem,
            fullPath = fullPath,
            searching = searching,
            pathExpanded = pathExpanded,
            colors = colors,
            onTogglePathExpanded = onTogglePathExpanded
        )
    }
}

@Composable
private fun ContainerPickerRow(
    row: ContainerPickerRowItem,
    colors: UHabitComposeColors,
    searching: Boolean,
    onSelect: (OrganizationalItem?) -> Unit
) {
    val indentDp = if (searching) 0.dp else (24 + 12 * row.depth.coerceAtMost(2)).dp
    val minHeight = if (!searching && row.isSelected && row.depth >= 3) {
        64.dp
    } else if (row.pathSubtitle != null) {
        52.dp
    } else {
        48.dp
    }
    val contentDesc = if (row.isSelected) {
        stringResource(R.string.task_container_picker_selected_format, row.title)
    } else {
        row.title
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clickable { onSelect(row.item) }
            .semantics(mergeDescendants = true) {
                contentDescription = contentDesc
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!searching) {
            Box(
                modifier = Modifier
                    .width(indentDp)
                    .height(48.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (row.isParent || row.item == null || row.depth >= 3) {
                    val iconKind = when {
                        row.item == null -> PickerIconKind.Inbox
                        row.isParent -> PickerIconKind.Chevron
                        else -> PickerIconKind.Branch
                    }
                    val iconColor = if (row.depth >= 3 && !row.isParent) {
                        Color(colors.border)
                    } else {
                        Color(colors.secondary)
                    }
                    PickerCanvasIcon(
                        kind = iconKind,
                        color = iconColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = row.title,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = Color(colors.foreground),
                    fontWeight = if (row.isSelected || searching) FontWeight.Medium else FontWeight.Normal,
                    fontFamily = FontFamily.Default
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 20.dp)
            )
            if (row.pathSubtitle != null) {
                Text(
                    text = row.pathSubtitle,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(colors.secondary),
                        fontFamily = FontFamily.Default
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }

        Box(
            modifier = Modifier.size(width = 24.dp, height = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (row.isSelected) {
                PickerCanvasIcon(
                    kind = PickerIconKind.Check,
                    color = Color(colors.accent),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ContainerPickerFooter(
    selectedItem: OrganizationalItem?,
    fullPath: String,
    searching: Boolean,
    pathExpanded: Boolean,
    colors: UHabitComposeColors,
    onTogglePathExpanded: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(colors.divider))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (searching) 48.dp else 55.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searching) {
                Box(
                    modifier = Modifier.size(width = 24.dp, height = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PickerCanvasIcon(
                        kind = PickerIconKind.Check,
                        color = Color(colors.accent),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Text(
                text = if (searching) {
                    selectedItem?.name ?: stringResource(R.string.task_smart_inbox)
                } else {
                    fullPath.ifEmpty { stringResource(R.string.task_smart_inbox) }
                },
                style = TextStyle(
                    fontSize = 12.sp,
                    color = Color(colors.secondary),
                    fontFamily = FontFamily.Default
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selectedItem != null) {
                val toggleText = stringResource(
                    if (pathExpanded) {
                        R.string.task_container_picker_hide_path
                    } else if (searching) {
                        R.string.task_container_picker_path
                    } else {
                        R.string.task_container_picker_show_path
                    }
                )
                val toggleDesc = stringResource(
                    if (pathExpanded) {
                        R.string.task_container_picker_hide_path_desc
                    } else {
                        R.string.task_container_picker_show_path_desc
                    }
                )
                val minWidth = if (searching) 48.dp else 108.dp
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = minWidth, minHeight = 48.dp)
                        .clickable { onTogglePathExpanded() }
                        .semantics { contentDescription = toggleDesc },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = toggleText,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = Color(colors.secondary),
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }
        }
        if (pathExpanded && selectedItem != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = fullPath,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(colors.secondary),
                        fontFamily = FontFamily.Default
                    )
                )
            }
        }
    }
}
