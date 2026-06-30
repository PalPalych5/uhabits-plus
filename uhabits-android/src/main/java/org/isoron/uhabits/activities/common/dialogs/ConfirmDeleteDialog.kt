/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.inject.ActivityContext

import org.isoron.uhabits.activities.common.dialogs.CustomDialogs

/**
 * Dialog that asks the user confirmation before executing a delete operation.
 */
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
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_custom_confirm, null)
        val dialogTitle  = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage= view.findViewById<TextView>(R.id.dialog_message)
        val btnNegative  = view.findViewById<Button>(R.id.button_negative)
        val btnPositive  = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text  = res.getQuantityString(R.plurals.delete_habits_title, quantity)
        dialogMessage.text= res.getQuantityString(R.plurals.delete_habits_message, quantity)

        btnNegative.text = context.getString(android.R.string.cancel)
        btnPositive.text = context.getString(android.R.string.ok)

        btnNegative.setOnClickListener { dismiss() }
        btnPositive.setOnClickListener {
            callback.onConfirmed()
            dismiss()
        }
        setView(view, 0, 0, 0, 0)

        setOnShowListener {
            CustomDialogs.styleDialogShell(this, view)
            CustomDialogs.styleText(dialogTitle, dialogMessage, btnNegative, btnPositive, null, isDestructive = true)
        }
    }
}
