package org.isoron.uhabits.activities.habits.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors

internal data class HabitToolbarItem(
    val id: Int,
    val title: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val isDescending: Boolean = false
)
internal data class HabitToolbarState(
    val title: String = "", val archive: Boolean = false, val searching: Boolean = false,
    val query: String = "", val syncing: Boolean = false, val syncVisible: Boolean = false,
    val filters: List<HabitToolbarItem> = emptyList(), val sorts: List<HabitToolbarItem> = emptyList(),
    val overflow: List<HabitToolbarItem> = emptyList()
)

@Composable
internal fun HabitToolbarCompose(
    state: HabitToolbarState, colors: UHabitComposeColors, onAction: (Int) -> Unit,
    onBack: () -> Unit, onQuery: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth().background(colors.backgroundColor).statusBarsPadding().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.searching) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_settings_back), stringResource(android.R.string.cancel), tint = colors.foregroundColor) }
            BasicTextField(value = state.query, onValueChange = onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foregroundColor), cursorBrush = SolidColor(colors.accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).focusRequester(focus), decorationBox = { field ->
                    Box { if (state.query.isEmpty()) Text(stringResource(R.string.search_habits), color = colors.secondaryColor); field() }
                })
            IconButton(onClick = { if (state.query.isEmpty()) onBack() else onQuery("") }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(if (state.query.isEmpty()) android.R.string.cancel else R.string.clear), tint = colors.foregroundColor)
            }
        } else {
            if (state.archive) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_settings_back), stringResource(android.R.string.cancel), tint = colors.foregroundColor) }
            Text(state.title, Modifier.weight(1f).padding(start = if (state.archive) 0.dp else 16.dp), color = colors.foregroundColor, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (!state.archive) {
                IconButton(onClick = { onAction(R.id.actionAddHabit) }) { Icon(painterResource(R.drawable.ic_toolbar_add), stringResource(R.string.create_habit), tint = colors.foregroundColor) }
                if (state.syncVisible) IconButton(onClick = { onAction(R.id.actionSync) }, enabled = !state.syncing) {
                    if (state.syncing) CircularProgressIndicator(Modifier.size(20.dp), color = colors.foregroundColor, strokeWidth = 2.dp) else Icon(painterResource(R.drawable.ic_toolbar_refresh), stringResource(R.string.sync_now), tint = colors.foregroundColor)
                }
                if (state.filters.isNotEmpty() || state.sorts.isNotEmpty()) {
                    HabitFilterSortPopup(state.filters, state.sorts, colors, onAction)
                }
            }
            if (state.overflow.isNotEmpty()) HabitToolbarPopup(R.drawable.ic_toolbar_more_vertical, stringResource(R.string.containers_more), state.overflow, colors, onAction)
        }
    }
}

@Composable
private fun HabitFilterSortPopup(
    filters: List<HabitToolbarItem>,
    sorts: List<HabitToolbarItem>,
    colors: UHabitComposeColors,
    onAction: (Int) -> Unit
) {
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val currentSort = sorts.firstOrNull { it.checked }?.title ?: ""

    Box {
        IconButton(onClick = { filterMenuExpanded = true }) {
            Icon(painterResource(R.drawable.ic_toolbar_sort_descending), stringResource(R.string.filter), tint = colors.foregroundColor)
        }

        // 1. Main Filter Popup (Compact Two-Level Primary Surface)
        DropdownMenu(
            expanded = filterMenuExpanded,
            onDismissRequest = { filterMenuExpanded = false },
            modifier = Modifier
                .background(colors.surfaceColor)
                .widthIn(min = 230.dp, max = 280.dp)
        ) {
            if (filters.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.filter).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.secondaryColor,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 4.dp)
                )
                filters.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    fontWeight = if (item.checked) FontWeight.Medium else FontWeight.Normal
                                ),
                                color = colors.foregroundColor
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(38.dp)
                            .background(if (item.checked) colors.accentColor.copy(alpha = 0.08f) else Color.Transparent)
                            .semantics {
                                role = Role.Checkbox
                                selected = item.checked
                            },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        enabled = item.enabled,
                        onClick = {
                            filterMenuExpanded = false
                            onAction(item.id)
                        }
                    )
                }
            }
            if (filters.isNotEmpty() && sorts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = colors.borderColor)
            }
            if (sorts.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sort),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = colors.foregroundColor
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentSort,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = colors.secondaryColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_tabler_chevron_right),
                                    contentDescription = null,
                                    tint = colors.secondaryColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(38.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    onClick = {
                        filterMenuExpanded = false
                        sortMenuExpanded = true
                    }
                )
            }
        }

        // 2. Sorting Submenu Popup (Dedicated Selection Surface)
        DropdownMenu(
            expanded = sortMenuExpanded,
            onDismissRequest = { sortMenuExpanded = false },
            modifier = Modifier
                .background(colors.surfaceColor)
                .widthIn(min = 210.dp, max = 260.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            sortMenuExpanded = false
                            filterMenuExpanded = true
                        }
                    )
                    .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 6.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tabler_chevron_left),
                    contentDescription = stringResource(R.string.settings_back),
                    tint = colors.secondaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.sort).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.secondaryColor
                )
            }
            sorts.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                fontWeight = if (item.checked) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = colors.foregroundColor
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(38.dp)
                        .background(if (item.checked) colors.accentColor.copy(alpha = 0.08f) else Color.Transparent)
                        .semantics {
                            role = Role.RadioButton
                            selected = item.checked
                        },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    enabled = item.enabled,
                    onClick = {
                        sortMenuExpanded = false
                        onAction(item.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun HabitToolbarPopup(icon: Int, description: String, items: List<HabitToolbarItem>, colors: UHabitComposeColors, onAction: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(painterResource(icon), description, tint = colors.foregroundColor) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(colors.surfaceColor)
                .widthIn(min = 180.dp, max = 240.dp)
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (item.checked) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_tabler_check),
                                    contentDescription = null,
                                    tint = colors.accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = colors.foregroundColor
                            )
                        }
                    },
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    enabled = item.enabled,
                    onClick = {
                        expanded = false
                        onAction(item.id)
                    }
                )
            }
        }
    }
}
