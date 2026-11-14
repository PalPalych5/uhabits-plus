/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.inject.ActivityContext

class ConfirmDeleteDialog(
    @ActivityContext context: Context,
    callback: OnConfirmedCallback,
    quantity: Int
) : androidx.appcompat.app.AlertDialog(
    context,
    R.style.CustomTransparentDialogTheme
) {
    init {
        val res = context.resources
        val dialogTitle = res.getQuantityString(R.plurals.delete_habits_title, quantity)
        val dialogMessage = res.getQuantityString(R.plurals.delete_habits_message, quantity)

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    ConfirmDeleteDialogContent(
                        title = dialogTitle,
                        message = dialogMessage,
                        colors = colors,
                        onCancel = { dismiss() },
                        onConfirm = {
                            callback.onConfirmed()
                            dismiss()
                        }
                    )
                }
            }
        }
        setView(composeView, 0, 0, 0, 0)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.decorView?.setPadding(0, 0, 0, 0)
        setOnShowListener {
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.decorView?.setPadding(0, 0, 0, 0)
        }
    }
}

@Composable
internal fun ConfirmDeleteDialogContent(
    title: String,
    message: String,
    colors: UHabitComposeColors,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceColor,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.foregroundColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryColor
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(android.R.string.cancel), color = colors.secondaryColor)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}
