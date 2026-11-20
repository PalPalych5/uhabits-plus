/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitDialogShell

/** The two informational prompts opened from the Tasks workspace. */
@Composable
internal fun TaskInformationDialog(
    title: String?,
    message: String,
    onDismiss: () -> Unit,
    onOpenHabit: (() -> Unit)?,
) {
    val colors = LocalUHabitComposeColors.current
    Dialog(onDismissRequest = onDismiss) {
        UHabitDialogShell {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        color = colors.foregroundColor,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    text = message,
                    color = colors.foregroundColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (onOpenHabit == null) colors.accentColor else colors.secondaryColor,
                    ),
                ) {
                    Text(stringResource(if (onOpenHabit == null) android.R.string.ok else R.string.task_due_close))
                }
                if (onOpenHabit != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenHabit()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentColor),
                    ) {
                        Text(stringResource(R.string.task_planned_habit_open))
                    }
                }
            }
        }
    }
}
