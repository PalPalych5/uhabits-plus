/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */
package org.isoron.uhabits.activities.common.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.settings.AccentColorManager
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver

/**
 * Helper class for creating and displaying beautifully styled, theme-consistent dialogs.
 * Uses custom transparent dialog hosts so that all backgrounds, borders, and margins are
 * explicitly owned by our custom layouts, avoiding platform differences.
 */
object CustomDialogs {

    fun resolvePalette(context: Context): SettingsThemePalette {
        val app = context.applicationContext as? HabitsApplication
        val prefs = app?.component?.preferences
        return if (prefs != null) {
            SettingsThemePaletteResolver.resolve(context, prefs)
        } else {
            val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            SettingsThemePalette(
                background = if (isDark) 0xFF121212.toInt() else Color.WHITE,
                surface = if (isDark) 0xFF1A1D23.toInt() else Color.WHITE,
                border = if (isDark) 0x1FFFFFFF else 0xFFD9DEE5.toInt(),
                divider = if (isDark) 0x1FFFFFFF else 0xFFE7EBF0.toInt(),
                onSurface = if (isDark) 0xFFF1F2F5.toInt() else 0xFF191C20.toInt(),
                onSurfaceVariant = if (isDark) 0xFFAEB3BD.toInt() else 0xFF626872.toInt(),
                isDark = isDark,
                isPureBlack = false
            )
        }
    }

    fun resolveAccentColor(context: Context): Int {
        val app = context.applicationContext as? HabitsApplication
        val prefs = app?.component?.preferences
        return if (prefs != null) {
            AccentColorManager.getAccentColor(context, prefs)
        } else {
            resolveColor(context, com.google.android.material.R.attr.colorPrimary, Color.BLACK)
        }
    }

    private fun resolveErrorColor(context: Context): Int {
        return resolveColor(context, com.google.android.material.R.attr.colorError, 0xFFD32F2F.toInt())
    }

    private fun resolveColor(view: View, attr: Int, fallback: Int): Int {
        return MaterialColors.getColor(view, attr, fallback)
    }

    private fun resolveColor(context: Context, attr: Int, fallback: Int): Int {
        return MaterialColors.getColor(context, attr, fallback)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * Styles the outer frame of the dialog to match the Settings cards.
     * Sets 16dp rounded corners, background surface color, and outline border stroke.
     * Sets window background to transparent to fully isolate our custom layout shapes.
     */
    fun styleDialogShell(dialog: AlertDialog, root: View) {
        val palette = resolvePalette(root.context)
        val density = root.resources.displayMetrics.density
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = density * 16f
            setColor(palette.surface)
            setStroke((density * 1f).toInt().coerceAtLeast(1), palette.border)
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Clear default window padding so custom dialog dimensions are fully in control
            window.decorView.setPadding(0, 0, 0, 0)
        }
    }

    fun styleText(
        title: TextView,
        message: TextView?,
        negativeButton: Button?,
        positiveButton: Button?,
        neutralButton: Button?,
        isDestructive: Boolean
    ) {
        val context = title.context
        val palette = resolvePalette(context)
        val accentColor = resolveAccentColor(context)

        title.setTextColor(palette.onSurface)
        message?.setTextColor(palette.onSurfaceVariant)
        negativeButton?.setTextColor(palette.onSurfaceVariant)
        neutralButton?.setTextColor(palette.onSurfaceVariant)

        if (positiveButton != null) {
            val color = if (isDestructive) resolveErrorColor(context) else accentColor
            positiveButton.setTextColor(color)
        }
    }

    fun styleTextInput(inputLayout: TextInputLayout, editText: TextInputEditText) {
        val accentColor = resolveAccentColor(inputLayout.context)
        val palette = resolvePalette(inputLayout.context)

        inputLayout.boxBackgroundColor = Color.TRANSPARENT
        inputLayout.boxStrokeColor = accentColor
        inputLayout.setHintTextColor(ColorStateList.valueOf(palette.onSurfaceVariant))
        editText.setTextColor(palette.onSurface)
        editText.setHintTextColor(palette.onSurfaceVariant)
        editText.highlightColor = adjustAlpha(accentColor, 0.30f)
    }

    fun stylePlainEditText(editText: EditText) {
        val context = editText.context
        val palette = resolvePalette(context)
        val accentColor = resolveAccentColor(context)

        editText.setTextColor(palette.onSurface)
        editText.setHintTextColor(palette.onSurfaceVariant)
        editText.highlightColor = adjustAlpha(accentColor, 0.30f)
        editText.backgroundTintList = ColorStateList.valueOf(accentColor)
    }

    fun styleCheckBox(checkBox: android.widget.CompoundButton) {
        val accentColor = resolveAccentColor(checkBox.context)
        CompoundButtonCompat.setButtonTintList(checkBox, ColorStateList.valueOf(accentColor))
        val palette = resolvePalette(checkBox.context)
        checkBox.setTextColor(palette.onSurface)
    }

    fun styleChoiceRow(radio: RadioButton, label: TextView) {
        val palette = resolvePalette(label.context)
        val accentColor = resolveAccentColor(label.context)
        label.setTextColor(palette.onSurface)
        CompoundButtonCompat.setButtonTintList(radio, ColorStateList.valueOf(accentColor))
    }

    /**
     * Recursive visual walker helper to tint standard views that are dynamically inflated
     * inside custom sub-dialog fragments. Works as a fallback, though explicit binding is preferred.
     */
    fun styleCustomViewElements(view: View) {
        val palette = resolvePalette(view.context)

        fun walk(v: View) {
            if (v is EditText) {
                stylePlainEditText(v)
            } else if (v is android.widget.CompoundButton) {
                styleCheckBox(v)
            } else if (v is TextView && v !is Button) {
                val idStr = try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "" }
                if (idStr == "choice_label") {
                    v.setTextColor(palette.onSurface)
                } else if (v.textColors == ColorStateList.valueOf(0xFFD32F2F.toInt())) {
                    // Keep destructive styling
                } else {
                    v.setTextColor(palette.onSurface)
                }
            } else if (v is TextInputLayout) {
                val et = v.editText as? TextInputEditText
                if (et != null) {
                    styleTextInput(v, et)
                }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))
                }
            }
        }
        walk(view)
    }

    fun showConfirmDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        isDestructive: Boolean,
        positiveText: CharSequence = context.getString(android.R.string.ok),
        negativeText: CharSequence = context.getString(android.R.string.cancel),
        onConfirm: () -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
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

        btnNegative.text = negativeText
        btnPositive.text = positiveText

        dialog.setView(view, 0, 0, 0, 0)

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }

        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, dialogMessage, btnNegative, btnPositive, null, isDestructive)
        return dialog
    }

    fun showInputDialog(
        context: Context,
        title: CharSequence,
        initialValue: String,
        message: CharSequence? = null,
        hint: CharSequence? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        positiveText: CharSequence = context.getString(android.R.string.ok),
        onConfirm: (String) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_input, null)
        val dialogTitle   = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.input_layout)
        val inputEditText = view.findViewById<TextInputEditText>(R.id.input_edit_text)
        val btnNegative   = view.findViewById<Button>(R.id.button_negative)
        val btnPositive   = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title
        if (message.isNullOrBlank()) {
            dialogMessage.visibility = View.GONE
        } else {
            dialogMessage.visibility = View.VISIBLE
            dialogMessage.text = message
        }
        inputLayout.hint = hint
        inputEditText.setText(initialValue)
        inputEditText.setSelection(initialValue.length)
        inputEditText.inputType = inputType
        inputEditText.imeOptions = EditorInfo.IME_ACTION_DONE

        btnNegative.text = context.getString(android.R.string.cancel)
        btnPositive.text = positiveText

        dialog.setView(view, 0, 0, 0, 0)

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
        styleDialogShell(dialog, view)
        styleText(dialogTitle, dialogMessage, btnNegative, btnPositive, null, isDestructive = false)
        styleTextInput(inputLayout, inputEditText)
        return dialog
    }

    fun showCredentialsDialog(
        context: Context,
        title: CharSequence,
        primaryHint: CharSequence,
        secondaryHint: CharSequence,
        initialPrimaryValue: String = "",
        positiveText: CharSequence = context.getString(android.R.string.ok),
        onConfirm: (String, String) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_credentials, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val primaryInputLayout = view.findViewById<TextInputLayout>(R.id.primary_input_layout)
        val primaryInput = view.findViewById<TextInputEditText>(R.id.primary_input_edit_text)
        val secondaryInputLayout = view.findViewById<TextInputLayout>(R.id.secondary_input_layout)
        val secondaryInput = view.findViewById<TextInputEditText>(R.id.secondary_input_edit_text)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title
        primaryInputLayout.hint = primaryHint
        secondaryInputLayout.hint = secondaryHint
        primaryInput.setText(initialPrimaryValue)
        primaryInput.setSelection(initialPrimaryValue.length)
        primaryInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        secondaryInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        secondaryInput.imeOptions = EditorInfo.IME_ACTION_DONE

        btnNegative.text = context.getString(android.R.string.cancel)
        btnPositive.text = positiveText

        dialog.setView(view, 0, 0, 0, 0)

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            onConfirm(
                primaryInput.text?.toString().orEmpty(),
                secondaryInput.text?.toString().orEmpty()
            )
            dialog.dismiss()
        }

        primaryInput.requestFocus()
        primaryInput.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(primaryInput, InputMethodManager.SHOW_IMPLICIT)
        }, 150)

        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, null, btnNegative, btnPositive, null, isDestructive = false)
        styleTextInput(primaryInputLayout, primaryInput)
        styleTextInput(secondaryInputLayout, secondaryInput)
        return dialog
    }

    fun showSingleChoiceDialog(
        context: Context,
        title: CharSequence,
        options: List<String>,
        selectedIndex: Int,
        message: CharSequence? = null,
        neutralText: CharSequence? = null,
        onNeutral: (() -> Unit)? = null,
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_single_choice, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.options_container)
        val buttonNeutral = view.findViewById<Button>(R.id.button_neutral)
        val buttonNegative = view.findViewById<Button>(R.id.button_negative)

        dialogTitle.text = title
        if (message.isNullOrBlank()) {
            dialogMessage.visibility = View.GONE
        } else {
            dialogMessage.visibility = View.VISIBLE
            dialogMessage.text = message
        }

        var currentSelection = selectedIndex.takeIf { it in options.indices } ?: -1
        val radios = mutableListOf<RadioButton>()

        options.forEachIndexed { index, option ->
            val row = LayoutInflater.from(dialogContext)
                .inflate(R.layout.item_dialog_choice, optionsContainer, false)
            val radio = row.findViewById<RadioButton>(R.id.choice_radio)
            val label = row.findViewById<TextView>(R.id.choice_label)

            radio.isChecked = index == currentSelection
            label.text = option
            styleChoiceRow(radio, label)
            row.setOnClickListener {
                currentSelection = index
                radios.forEachIndexed { radioIndex, item -> item.isChecked = radioIndex == index }
                onSelect(index)
                dialog.dismiss()
            }

            radios += radio
            optionsContainer.addView(row)
        }

        if (!neutralText.isNullOrBlank() && onNeutral != null) {
            buttonNeutral.visibility = View.VISIBLE
            buttonNeutral.text = neutralText
            buttonNeutral.setOnClickListener {
                onNeutral()
                dialog.dismiss()
            }
        } else {
            buttonNeutral.visibility = View.GONE
        }

        buttonNegative.text = context.getString(android.R.string.cancel)
        buttonNegative.setOnClickListener { dialog.dismiss() }

        dialog.setView(view, 0, 0, 0, 0)
        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, dialogMessage, buttonNegative, null, buttonNeutral, isDestructive = false)
        return dialog
    }

    fun showDatePickerDialog(
        context: Context,
        title: CharSequence,
        initialDate: LocalDate,
        positiveText: CharSequence = context.getString(android.R.string.ok),
        onConfirm: (LocalDate) -> Unit
    ): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme).create()
        val dialogContext = dialog.context
        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_date_picker, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val datePicker = view.findViewById<DatePicker>(R.id.date_picker)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title
        btnNegative.text = context.getString(android.R.string.cancel)
        btnPositive.text = positiveText
        datePicker.updateDate(initialDate.year, initialDate.month - 1, initialDate.day)

        dialog.setView(view, 0, 0, 0, 0)
        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.setOnClickListener {
            onConfirm(
                LocalDate(
                    datePicker.year,
                    datePicker.month + 1,
                    datePicker.dayOfMonth
                )
            )
            dialog.dismiss()
        }

        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, null, btnNegative, btnPositive, null, isDestructive = false)
        return dialog
    }

    fun showTimePickerDialog(
        context: Context,
        title: CharSequence,
        initialHour: Int,
        initialMinute: Int,
        is24Hour: Boolean,
        neutralText: CharSequence? = null,
        onNeutral: (() -> Unit)? = null,
        onConfirm: (hour: Int, minute: Int) -> Unit
    ): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme).create()
        val dialogContext = dialog.context
        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_time_picker, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val timePicker = view.findViewById<android.widget.TimePicker>(R.id.time_picker)
        val btnNeutral = view.findViewById<Button>(R.id.button_neutral)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title
        timePicker.setIs24HourView(is24Hour)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            timePicker.hour = if (initialHour >= 0) initialHour else 12
            timePicker.minute = if (initialMinute >= 0) initialMinute else 0
        } else {
            @Suppress("DEPRECATION")
            timePicker.currentHour = if (initialHour >= 0) initialHour else 12
            @Suppress("DEPRECATION")
            timePicker.currentMinute = if (initialMinute >= 0) initialMinute else 0
        }

        btnNegative.text = context.getString(android.R.string.cancel)
        btnNegative.setOnClickListener { dialog.dismiss() }

        btnPositive.text = context.getString(android.R.string.ok)
        btnPositive.setOnClickListener {
            val h = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) timePicker.hour else @Suppress("DEPRECATION") timePicker.currentHour
            val m = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) timePicker.minute else @Suppress("DEPRECATION") timePicker.currentMinute
            onConfirm(h, m)
            dialog.dismiss()
        }

        if (!neutralText.isNullOrBlank() && onNeutral != null) {
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.text = neutralText
            btnNeutral.setOnClickListener {
                onNeutral()
                dialog.dismiss()
            }
        } else {
            btnNeutral.visibility = View.GONE
        }

        dialog.setView(view, 0, 0, 0, 0)
        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, null, btnNegative, btnPositive, btnNeutral, isDestructive = false)
        return dialog
    }

    fun showSimpleListDialog(
        context: Context,
        title: CharSequence,
        items: List<CharSequence>,
        summaries: List<CharSequence>? = null,
        emptyStateMessage: CharSequence? = null,
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_single_choice, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.options_container)
        val buttonNeutral = view.findViewById<Button>(R.id.button_neutral)
        val buttonNegative = view.findViewById<Button>(R.id.button_negative)

        dialogTitle.text = title
        buttonNeutral.visibility = View.GONE
        buttonNegative.text = context.getString(android.R.string.cancel)
        buttonNegative.setOnClickListener { dialog.dismiss() }

        val palette = resolvePalette(dialogContext)

        if (items.isEmpty()) {
            dialogMessage.visibility = View.VISIBLE
            dialogMessage.text = emptyStateMessage ?: context.getString(R.string.backup_restore_no_backups)
        } else {
            dialogMessage.visibility = View.GONE
            items.forEachIndexed { index, item ->
                val row = LayoutInflater.from(dialogContext)
                    .inflate(R.layout.item_dialog_choice, optionsContainer, false)
                val radio = row.findViewById<RadioButton>(R.id.choice_radio)
                val label = row.findViewById<TextView>(R.id.choice_label)

                radio.visibility = View.GONE

                val summary = summaries?.getOrNull(index)
                if (summary != null && summary.isNotEmpty()) {
                    label.text = android.text.Html.fromHtml("<b>$item</b><br/><small>$summary</small>")
                } else {
                    label.text = item
                }

                label.setTextColor(palette.onSurface)

                row.setOnClickListener {
                    onSelect(index)
                    dialog.dismiss()
                }
                optionsContainer.addView(row)
            }
        }

        dialog.setView(view, 0, 0, 0, 0)
        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, dialogMessage, buttonNegative, null, null, isDestructive = false)
        return dialog
    }

    fun showCustomViewDialog(
        context: Context,
        title: CharSequence,
        contentView: View,
        isDestructive: Boolean = false,
        positiveText: CharSequence = context.getString(android.R.string.ok),
        negativeText: CharSequence = context.getString(android.R.string.cancel),
        neutralText: CharSequence? = null,
        onNeutral: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_view, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val customContainer = view.findViewById<FrameLayout>(R.id.custom_container)
        val btnNeutral = view.findViewById<Button>(R.id.button_neutral)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title

        (contentView.parent as? ViewGroup)?.removeView(contentView)
        customContainer.addView(contentView)

        if (!neutralText.isNullOrBlank() && onNeutral != null) {
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.text = neutralText
            btnNeutral.setOnClickListener {
                onNeutral()
                dialog.dismiss()
            }
        } else {
            btnNeutral.visibility = View.GONE
        }

        btnNegative.text = negativeText
        btnNegative.setOnClickListener { dialog.dismiss() }

        btnPositive.text = positiveText
        btnPositive.setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }

        dialog.setView(view, 0, 0, 0, 0)
        dialog.show()

        styleDialogShell(dialog, view)
        styleText(dialogTitle, null, btnNegative, btnPositive, btnNeutral, isDestructive)
        styleCustomViewElements(contentView)

        return dialog
    }
}
