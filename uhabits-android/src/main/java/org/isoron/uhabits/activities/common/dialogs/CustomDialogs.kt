/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */
package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.isoron.uhabits.R

object CustomDialogs {

    fun showConfirmDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        isDestructive: Boolean,
        onConfirm: () -> Unit
    ): AlertDialog {
        // Build dialog first to get its themed context. Inflating with the raw
        // activity context in dark/AMOLED themes causes textColorPrimary to
        // resolve to white — invisible on the dialog's light surface.
        val builder = MaterialAlertDialogBuilder(context)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_confirm, null)
        val dialogTitle   = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val btnNegative   = view.findViewById<Button>(R.id.button_negative)
        val btnPositive   = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text   = title
        dialogMessage.text = message

        btnNegative.text = context.getString(android.R.string.cancel)
        btnPositive.text = context.getString(android.R.string.ok)

        if (isDestructive) {
            btnPositive.setTextColor(0xFFD32F2F.toInt())
        }

        dialog.setView(view)

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }

    fun showInputDialog(
        context: Context,
        title: CharSequence,
        initialValue: String,
        onConfirm: (String) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_input, null)
        val dialogTitle   = view.findViewById<TextView>(R.id.dialog_title)
        val inputEditText = view.findViewById<TextInputEditText>(R.id.input_edit_text)
        val btnNegative   = view.findViewById<Button>(R.id.button_negative)
        val btnPositive   = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title
        inputEditText.setText(initialValue)
        inputEditText.setSelection(initialValue.length)

        btnNegative.text = context.getString(android.R.string.cancel)
        btnPositive.text = context.getString(android.R.string.ok)

        dialog.setView(view)

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            onConfirm(inputEditText.text?.toString() ?: "")
            dialog.dismiss()
        }

        inputEditText.requestFocus()
        inputEditText.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(inputEditText, InputMethodManager.SHOW_IMPLICIT)
        }, 150)

        dialog.show()
        return dialog
    }

    fun showSingleChoiceDialog(
        context: Context,
        title: CharSequence,
        options: List<String>,
        selectedIndex: Int,
        message: CharSequence? = null,
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_single_choice, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.options_container)
        val buttonNegative = view.findViewById<Button>(R.id.button_negative)

        dialogTitle.text = title
        if (message.isNullOrBlank()) {
            dialogMessage.visibility = View.GONE
        } else {
            dialogMessage.visibility = View.VISIBLE
            dialogMessage.text = message
        }

        var currentSelection = selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
        val radios = mutableListOf<RadioButton>()

        options.forEachIndexed { index, option ->
            val row = LayoutInflater.from(dialogContext)
                .inflate(R.layout.item_dialog_choice, optionsContainer, false)
            val radio = row.findViewById<RadioButton>(R.id.choice_radio)
            val label = row.findViewById<TextView>(R.id.choice_label)

            radio.isChecked = index == currentSelection
            label.text = option
            row.setOnClickListener {
                currentSelection = index
                radios.forEachIndexed { radioIndex, item -> item.isChecked = radioIndex == index }
                onSelect(index)
                dialog.dismiss()
            }

            radios += radio
            optionsContainer.addView(row)
        }

        buttonNegative.text = context.getString(android.R.string.cancel)
        buttonNegative.setOnClickListener { dialog.dismiss() }

        dialog.setView(view)
        dialog.show()
        return dialog
    }
}
