/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitDialogShell
import org.isoron.uhabits.core.userTasks.UserTask

@Composable
internal fun TaskQuickActionMenu(
    task: UserTask,
    isSelected: Boolean,
    onDismiss: () -> Unit,
    onActionSelected: (Int) -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    Dialog(onDismissRequest = onDismiss) {
        UHabitDialogShell {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = task.title,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = colors.foregroundColor,
                    style = MaterialTheme.typography.titleMedium,
                )
                
                val actions = listOf(
                    R.string.task_quick_menu_change_due,
                    R.string.task_quick_menu_move_container,
                    R.string.task_quick_menu_edit,
                    if (task.isCompleted) R.string.task_quick_menu_reopen else R.string.task_quick_menu_complete,
                    if (isSelected) R.string.task_quick_menu_deselect else R.string.task_quick_menu_select,
                    R.string.delete
                )

                actions.forEachIndexed { index, resId ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            onActionSelected(index)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (resId == R.string.delete) colors.accentColor else colors.foregroundColor
                        ),
                    ) {
                        Text(
                            text = stringResource(resId),
                            modifier = Modifier.align(Alignment.CenterVertically).fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}
