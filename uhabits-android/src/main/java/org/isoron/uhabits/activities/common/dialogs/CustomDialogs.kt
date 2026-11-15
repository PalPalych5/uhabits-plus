/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */
package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
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
                background = if (isDark) 0xFF111318.toInt() else 0xFFF6F7F9.toInt(),
                surface = if (isDark) 0xFF1A1D23.toInt() else Color.WHITE,
                border = if (isDark) 0x1FFFFFFF else 0xFFD9DEE5.toInt(),
                divider = if (isDark) 0x1FFFFFFF else 0xFFE7EBF0.toInt(),
                onSurface = if (isDark) 0xFFF1F2F5.toInt() else 0xFF191C20.toInt(),
                onSurfaceVariant = if (isDark) 0xFFAEB3BD.toInt() else 0xFF626872.toInt(),
                isDark = isDark,
                isPureBlack = false,
                dialogSurface = if (isDark) 0xFF1A1D23.toInt() else Color.WHITE,
                dialogBorder = if (isDark) 0x1FFFFFFF else 0xFFD9DEE5.toInt()
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
            setColor(palette.dialogSurface)
            setStroke((density * 1f).toInt().coerceAtLeast(1), palette.dialogBorder)
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Clear default window padding so custom dialog dimensions are fully in control
            window.decorView.setPadding(0, 0, 0, 0)
        }
    }

    fun styleBottomSheet(
        dialog: Dialog?,
        context: Context,
        palette: SettingsThemePalette = resolvePalette(context),
        cornerRadiusDp: Float = 16f
    ) {
        if (dialog == null) return
        val density = context.resources.displayMetrics.density
        val r = cornerRadiusDp * density
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.elevation = 0f
            sheet.clipToOutline = true
            sheet.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
                    } else {
                        outline.setEmpty()
                    }
                }
            }
            sheet.background = GradientDrawable().apply {
                setColor(palette.dialogSurface)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                if (palette.isPureBlack) {
                    setStroke((density * 1f).toInt().coerceAtLeast(1), palette.dialogBorder)
                }
            }
        }
    }

    private fun styleDialogActionButton(button: Button?, textColor: Int) {
        if (button == null) return
        val density = button.resources.displayMetrics.density
        button.setTextColor(textColor)
        button.textSize = 14f
        button.isAllCaps = false
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(Color.WHITE)
        }
        val rippleColor = ColorStateList.valueOf(adjustAlpha(textColor, 0.14f))
        button.background = RippleDrawable(rippleColor, null, mask)
    }

    private fun styleChoiceRowBackground(
        rowView: View,
        palette: SettingsThemePalette,
        selectedAccentColor: Int? = null
    ) {
        val density = rowView.resources.displayMetrics.density
        val baseDrawable = if (selectedAccentColor != null) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(adjustAlpha(selectedAccentColor, 0.12f))
                setStroke((1f * density).toInt().coerceAtLeast(1), adjustAlpha(selectedAccentColor, 0.35f))
            }
        } else {
            null
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(Color.WHITE)
        }
        val rippleColor = ColorStateList.valueOf(adjustAlpha(palette.onSurface, 0.10f))
        rowView.background = RippleDrawable(rippleColor, baseDrawable, mask)
    }

    fun resolveStatisticsStartSelection(
        current: LocalDate?,
        today: LocalDate,
        thisMonday: LocalDate
    ): Int {
        if (current == null) return -1
        return when (current) {
            today -> 0
            thisMonday -> 1
            else -> 2
        }
    }

    private fun styleDatePickerHeader(datePicker: DatePicker, palette: SettingsThemePalette) {
        val density = datePicker.resources.displayMetrics.density
        val sysRes = android.content.res.Resources.getSystem()
        val headerId = sysRes.getIdentifier("date_picker_header", "id", "android")
        val yearId = sysRes.getIdentifier("date_picker_header_year", "id", "android")
        val dateId = sysRes.getIdentifier("date_picker_header_date", "id", "android")
        val lunarId = sysRes.getIdentifier("date_picker_header_lunar", "id", "android")
        val header = if (headerId != 0) datePicker.findViewById<View>(headerId) else null
        if (header != null) {
            header.minimumHeight = 0
            header.setPadding(
                (14f * density).toInt(),
                (10f * density).toInt(),
                (14f * density).toInt(),
                (10f * density).toInt()
            )
            header.layoutParams?.let { lp ->
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.leftMargin = 0
                    lp.rightMargin = 0
                    lp.topMargin = 0
                    lp.bottomMargin = (4f * density).toInt()
                }
                header.layoutParams = lp
            }
            header.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(palette.surfaceVariant)
                setStroke((1f * density).toInt().coerceAtLeast(1), palette.border)
            }
        }
        val yearView = if (yearId != 0) datePicker.findViewById<TextView>(yearId) else null
        yearView?.apply {
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = 0
                lp.bottomMargin = 0
                lp.marginStart = 0
                lp.leftMargin = 0
                layoutParams = lp
            }
        }
        val dateView = if (dateId != 0) datePicker.findViewById<TextView>(dateId) else null
        dateView?.apply {
            setTextColor(palette.onSurface)
            textSize = 21f
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = (3f * density).toInt()
                lp.bottomMargin = 0
                lp.marginStart = 0
                lp.leftMargin = 0
                if (lp is android.widget.RelativeLayout.LayoutParams && yearId != 0) {
                    lp.addRule(android.widget.RelativeLayout.BELOW, yearId)
                    lp.addRule(android.widget.RelativeLayout.ALIGN_START, yearId)
                }
                layoutParams = lp
            }
        }
        if (lunarId != 0) {
            datePicker.findViewById<TextView>(lunarId)?.setTextColor(palette.onSurfaceVariant)
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

        styleDialogActionButton(negativeButton, palette.onSurfaceVariant)
        styleDialogActionButton(neutralButton, palette.onSurfaceVariant)

        if (positiveButton != null) {
            val color = if (isDestructive) resolveErrorColor(context) else accentColor
            styleDialogActionButton(positiveButton, color)
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
        val density = context.resources.displayMetrics.density

        editText.setTextColor(palette.onSurface)
        editText.setHintTextColor(palette.onSurfaceVariant)
        editText.textSize = 15f
        editText.highlightColor = adjustAlpha(accentColor, 0.30f)
        editText.backgroundTintList = null

        val focusedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(palette.surfaceVariant)
            setStroke((1.5f * density).toInt().coerceAtLeast(2), accentColor)
        }
        val defaultDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(palette.surfaceVariant)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.border)
        }
        editText.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(), defaultDrawable)
        }
        val padH = (12f * density).toInt()
        val padV = (10f * density).toInt()
        editText.setPadding(padH, padV, padH, padV)
        editText.textCursorDrawable?.mutate()?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
    }

    fun styleCompactNumberInput(editText: EditText) {
        val context = editText.context
        val palette = resolvePalette(context)
        val accentColor = resolveAccentColor(context)
        val density = context.resources.displayMetrics.density

        editText.setTextColor(palette.onSurface)
        editText.setHintTextColor(palette.onSurfaceVariant)
        editText.textSize = 16f
        editText.gravity = android.view.Gravity.CENTER
        editText.backgroundTintList = null

        val focusedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(palette.background)
            setStroke((1.5f * density).toInt().coerceAtLeast(2), accentColor)
        }
        val defaultDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(palette.background)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.border)
        }

        val selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(), defaultDrawable)
        }
        editText.background = selector

        editText.highlightColor = adjustAlpha(accentColor, 0.30f)
        editText.textCursorDrawable?.mutate()?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
    }

    fun styleCheckBox(checkBox: android.widget.CompoundButton) {
        val accentColor = resolveAccentColor(checkBox.context)
        CompoundButtonCompat.setButtonTintList(checkBox, ColorStateList.valueOf(accentColor))
        val palette = resolvePalette(checkBox.context)
        checkBox.setTextColor(palette.onSurface)
        checkBox.textSize = 16f
        checkBox.minHeight = (44f * checkBox.resources.displayMetrics.density).toInt()
        if (checkBox is RadioButton) {
            checkBox.scaleX = 0.82f
            checkBox.scaleY = 0.82f
        }
    }

    fun styleChoiceRow(radio: RadioButton, label: TextView) {
        val palette = resolvePalette(label.context)
        val accentColor = resolveAccentColor(label.context)
        label.setTextColor(palette.onSurface)
        label.textSize = 15f
        radio.minHeight = (38f * radio.resources.displayMetrics.density).toInt()
        radio.minimumHeight = radio.minHeight
        radio.scaleX = 0.80f
        radio.scaleY = 0.80f
        CompoundButtonCompat.setButtonTintList(radio, ColorStateList.valueOf(accentColor))
        (radio.parent as? View)?.let { styleChoiceRowBackground(it, palette) }
    }

    fun styleCustomViewElements(view: View) {
        val palette = resolvePalette(view.context)

        fun walk(v: View) {
            if (v is EditText) {
                val idStr = try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "" }
                if (idStr.endsWith("TextView") || idStr.contains("xTimes") || idStr.contains("everyX")) {
                    styleCompactNumberInput(v)
                } else {
                    stylePlainEditText(v)
                }
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
        @androidx.annotation.DrawableRes iconRes: Int? = null,
        @androidx.annotation.ColorInt iconTint: Int? = null,
        onConfirm: () -> Unit
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_confirm, null)
        val dialogIcon    = view.findViewById<ImageView>(R.id.dialog_icon)
        val dialogTitle   = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val btnNegative   = view.findViewById<Button>(R.id.button_negative)
        val btnPositive   = view.findViewById<Button>(R.id.button_positive)

        if (iconRes != null && dialogIcon != null) {
            dialogIcon.visibility = View.VISIBLE
            dialogIcon.setImageResource(iconRes)
            if (iconTint != null) {
                dialogIcon.setColorFilter(iconTint)
            }
        } else {
            dialogIcon?.visibility = View.GONE
        }

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

    fun showInfoDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        positiveText: CharSequence = context.getString(R.string.done_label)
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme)
        val dialog = builder.create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_confirm, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val dialogMessage = view.findViewById<TextView>(R.id.dialog_message)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = title
        dialogMessage.text = message
        btnNegative.visibility = View.GONE
        btnPositive.text = positiveText
        btnPositive.setOnClickListener { dialog.dismiss() }

        dialog.setView(view, 0, 0, 0, 0)
        dialog.show()
        styleDialogShell(dialog, view)
        styleText(dialogTitle, dialogMessage, null, btnPositive, null, false)
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
        val palette = resolvePalette(dialogContext)
        val accentColor = resolveAccentColor(dialogContext)
        val rows = mutableListOf<View>()
        val radios = mutableListOf<RadioButton>()

        options.forEachIndexed { index, option ->
            val row = LayoutInflater.from(dialogContext)
                .inflate(R.layout.item_dialog_choice, optionsContainer, false)
            val radio = row.findViewById<RadioButton>(R.id.choice_radio)
            val label = row.findViewById<TextView>(R.id.choice_label)

            radio.isChecked = index == currentSelection
            label.text = option
            styleChoiceRow(radio, label)
            styleChoiceRowBackground(row, palette, if (index == currentSelection) accentColor else null)
            row.setOnClickListener {
                currentSelection = index
                radios.forEachIndexed { radioIndex, item -> item.isChecked = radioIndex == index }
                rows.forEachIndexed { rowIndex, r ->
                    styleChoiceRowBackground(r, palette, if (rowIndex == index) accentColor else null)
                }
                onSelect(index)
                dialog.dismiss()
            }

            rows += row
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
        onDateChanged: ((LocalDate) -> Unit)? = null,
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
        datePicker.init(initialDate.year, initialDate.month - 1, initialDate.day) { _, year, monthOfYear, dayOfMonth ->
            onDateChanged?.invoke(LocalDate(year, monthOfYear + 1, dayOfMonth))
        }

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
        styleDatePickerHeader(datePicker, resolvePalette(dialogContext))
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
                styleChoiceRowBackground(row, palette)

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
        positiveText: CharSequence? = context.getString(android.R.string.ok),
        negativeText: CharSequence = context.getString(android.R.string.cancel),
        neutralText: CharSequence? = null,
        onNeutral: (() -> Unit)? = null,
        onConfirm: (() -> Unit)? = null
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

        if (!positiveText.isNullOrBlank() && onConfirm != null) {
            btnPositive.visibility = View.VISIBLE
            btnPositive.text = positiveText
            btnPositive.setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        } else {
            btnPositive.visibility = View.GONE
        }

        dialog.setView(view, 0, 0, 0, 0)
        dialog.show()

        styleDialogShell(dialog, view)
        styleText(dialogTitle, null, btnNegative, btnPositive, btnNeutral, isDestructive)
        styleCustomViewElements(contentView)

        return dialog
    }
}
