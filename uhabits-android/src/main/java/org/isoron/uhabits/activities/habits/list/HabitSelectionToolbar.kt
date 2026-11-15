package org.isoron.uhabits.activities.habits.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors

@Composable
internal fun HabitSelectionToolbar(menu: ListHabitsSelectionMenu, colors: UHabitComposeColors) {
    val revision = menu.selectionRevision
    val count = remember(revision) { menu.selectedCount }
    if (count == 0) return
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(colors.backgroundColor).statusBarsPadding().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = menu::cancelSelection) {
            Icon(painterResource(R.drawable.ic_settings_back), stringResource(android.R.string.cancel), tint = colors.foregroundColor)
        }
        Text(
            text = count.toString(),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            color = colors.foregroundColor,
            style = MaterialTheme.typography.titleLarge
        )
        if (menu.canEdit()) {
            IconButton(onClick = { menu.onActionItemClicked(R.id.action_edit_habit) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_toolbar_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = colors.foregroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        IconButton(onClick = { menu.onActionItemClicked(R.id.action_color) }) {
            Icon(
                painter = painterResource(R.drawable.ic_toolbar_palette),
                contentDescription = stringResource(R.string.color_picker_default_title),
                tint = colors.foregroundColor,
                modifier = Modifier.size(24.dp)
            )
        }
        val overflowActions = buildList {
            if (menu.canArchive()) add(R.id.action_archive_habit to R.string.archive)
            if (menu.canUnarchive()) add(R.id.action_unarchive_habit to R.string.unarchive)
            add(R.id.action_delete to R.string.delete)
            if (menu.canNotify()) add(R.id.action_notify to R.string.reminder)
        }
        if (overflowActions.isNotEmpty()) {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(painterResource(R.drawable.ic_toolbar_more_vertical), stringResource(R.string.containers_more), tint = colors.foregroundColor)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = colors.dialogSurfaceColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, colors.dialogBorderColor)
                ) {
                    overflowActions.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = colors.foregroundColor) },
                            onClick = {
                                expanded = false
                                menu.onActionItemClicked(id)
                            }
                        )
                    }
                }
            }
        }
    }
}
