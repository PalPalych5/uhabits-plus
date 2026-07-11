/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */
package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.WeekdayList
import java.util.Locale

/**
 * Dialog that allows the user to pick one or more days of the week.
 * Rebuilt to use the transparent dialog host and explicitly styled CheckBoxes.
 */
class WeekdayPickerDialog : AppCompatDialogFragment() {
    private var selectedDays: BooleanArray? = null
    private var listener: OnWeekdaysPickedListener? = null
    var onDismissCallback: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            selectedDays = savedInstanceState.getBooleanArray(KEY_SELECTED_DAYS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBooleanArray(KEY_SELECTED_DAYS, selectedDays)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = MaterialAlertDialogBuilder(requireActivity(), R.style.CustomTransparentDialogTheme).create()
        val dialogContext = dialog.context

        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_view, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val customContainer = view.findViewById<FrameLayout>(R.id.custom_container)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = getString(R.string.select_weekdays)
        btnNegative.text = getString(android.R.string.cancel)
        btnNegative.setOnClickListener { dialog.dismiss() }

        btnPositive.text = getString(android.R.string.yes)
        btnPositive.setOnClickListener {
            listener?.onWeekdaysSet(WeekdayList(selectedDays))
            dialog.dismiss()
        }

        val density = resources.displayMetrics.density
        val scroll = ScrollView(dialogContext).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = true
        }

        val layout = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val padding = (4 * density).toInt()
            setPadding(0, padding, 0, padding)
        }

        val weekdayNames = JavaLocalDateFormatter(Locale.getDefault()).longWeekdayNames(DayOfWeek.SATURDAY)
        weekdayNames.forEachIndexed { index, name ->
            val checkBox = CheckBox(dialogContext).apply {
                text = name
                isChecked = selectedDays?.getOrNull(index) ?: false
                setOnCheckedChangeListener { _, isChecked ->
                    selectedDays?.set(index, isChecked)
                }
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val margin = (4 * density).toInt()
                    setMargins(0, margin, 0, margin)
                }
            }
            CustomDialogs.styleCheckBox(checkBox)
            layout.addView(checkBox)
        }

        scroll.addView(layout)
        customContainer.addView(scroll)

        dialog.setView(view, 0, 0, 0, 0)

        dialog.setOnShowListener {
            CustomDialogs.styleDialogShell(dialog, view)
            CustomDialogs.styleText(dialogTitle, null, btnNegative, btnPositive, null, isDestructive = false)
        }

        return dialog
    }

    fun setListener(listener: OnWeekdaysPickedListener?) {
        this.listener = listener
    }

    fun setSelectedDays(days: WeekdayList) {
        selectedDays = days.toArray()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback()
    }

    fun interface OnWeekdaysPickedListener {
        fun onWeekdaysSet(days: WeekdayList)
    }

    companion object {
        private const val KEY_SELECTED_DAYS = "selectedDays"
    }
}
