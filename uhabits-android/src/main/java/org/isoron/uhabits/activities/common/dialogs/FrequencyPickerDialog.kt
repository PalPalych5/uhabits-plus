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

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Button
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.R
import org.isoron.uhabits.databinding.FrequencyPickerDialogBinding

class FrequencyPickerDialog(
    var freqNumerator: Int,
    var freqDenominator: Int
) : AppCompatDialogFragment() {
    private var _binding: FrequencyPickerDialogBinding? = null
    private val binding get() = _binding!!

    var onFrequencyPicked: (num: Int, den: Int) -> Unit = { _, _ -> }

    constructor() : this(1, 1)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = FrequencyPickerDialogBinding.inflate(LayoutInflater.from(requireActivity()))

        addBeforeAfterText(
            this.getString(R.string.every_x_days),
            binding.everyXDaysContainer
        )

        addBeforeAfterText(
            this.getString(R.string.x_times_per_week),
            binding.xTimesPerWeekContainer
        )

        addBeforeAfterText(
            this.getString(R.string.x_times_per_month),
            binding.xTimesPerMonthContainer
        )

        addBeforeAfterText(
            this.getString(R.string.x_times_per_y_days),
            binding.xTimesPerYDaysContainer
        )

        binding.everyDayRadioButton.setOnClickListener {
            check(binding.everyDayRadioButton)
        }

        binding.everyXDaysRadioButton.setOnClickListener {
            check(binding.everyXDaysRadioButton)
            val everyXDaysTextView = binding.everyXDaysTextView
            selectInputField(everyXDaysTextView)
        }

        binding.everyXDaysTextView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) check(binding.everyXDaysRadioButton)
        }

        binding.xTimesPerWeekRadioButton.setOnClickListener {
            check(binding.xTimesPerWeekRadioButton)
            selectInputField(binding.xTimesPerWeekTextView)
        }

        binding.xTimesPerWeekTextView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) check(binding.xTimesPerWeekRadioButton)
        }

        binding.xTimesPerMonthRadioButton.setOnClickListener {
            check(binding.xTimesPerMonthRadioButton)
            selectInputField(binding.xTimesPerMonthTextView)
        }

        binding.xTimesPerMonthTextView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) check(binding.xTimesPerMonthRadioButton)
        }

        binding.xTimesPerYDaysRadioButton.setOnClickListener {
            check(binding.xTimesPerYDaysRadioButton)
            selectInputField(binding.xTimesPerYDaysXTextView)
        }

        binding.xTimesPerYDaysXTextView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) check(binding.xTimesPerYDaysRadioButton)
        }

        binding.xTimesPerYDaysYTextView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) check(binding.xTimesPerYDaysRadioButton)
        }

        val dialog = MaterialAlertDialogBuilder(requireActivity(), R.style.CustomTransparentDialogTheme).create()
        val dialogContext = dialog.context
        val view = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_custom_view, null)
        val dialogTitle = view.findViewById<TextView>(R.id.dialog_title)
        val customContainer = view.findViewById<FrameLayout>(R.id.custom_container)
        val btnNegative = view.findViewById<Button>(R.id.button_negative)
        val btnPositive = view.findViewById<Button>(R.id.button_positive)

        dialogTitle.text = getString(R.string.frequency)
        val bindingRoot = binding.root
        (bindingRoot.parent as? ViewGroup)?.removeView(bindingRoot)
        customContainer.addView(bindingRoot)

        btnNegative.text = getString(android.R.string.cancel)
        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.text = getString(R.string.save)
        btnPositive.setOnClickListener {
            onSaveClicked()
            dialog.dismiss()
        }

        dialog.setView(view, 0, 0, 0, 0)

        dialog.setOnShowListener {
            CustomDialogs.styleDialogShell(dialog, view)
            CustomDialogs.styleText(dialogTitle, null, btnNegative, btnPositive, null, isDestructive = false)
            CustomDialogs.styleCustomViewElements(bindingRoot)
        }

        return dialog
    }

    private fun addBeforeAfterText(
        str: String,
        container: LinearLayout
    ) {
        val parts = str.split("%d")
        for (i in parts.indices) {
            container.addView(
                TextView(activity).apply { text = parts[i].trim() },
                2 * i + 1
            )
        }
    }

    private fun onSaveClicked() {
        var numerator = 1
        var denominator = 1
        when {
            binding.everyDayRadioButton.isChecked -> {
                // NOP
            }

            binding.everyXDaysRadioButton.isChecked -> {
                if (binding.everyXDaysTextView.text.isNotEmpty()) {
                    denominator = Integer.parseInt(binding.everyXDaysTextView.text.toString())
                }
            }

            binding.xTimesPerWeekRadioButton.isChecked -> {
                if (binding.xTimesPerWeekTextView.text.isNotEmpty()) {
                    numerator = Integer.parseInt(binding.xTimesPerWeekTextView.text.toString())
                    denominator = 7
                }
            }

            binding.xTimesPerYDaysRadioButton.isChecked -> {
                if (binding.xTimesPerYDaysXTextView.text.isNotEmpty() && binding.xTimesPerYDaysYTextView.text.isNotEmpty()) {
                    numerator =
                        Integer.parseInt(binding.xTimesPerYDaysXTextView.text.toString())
                    denominator =
                        Integer.parseInt(binding.xTimesPerYDaysYTextView.text.toString())
                }
            }

            else -> {
                if (binding.xTimesPerMonthTextView.text.isNotEmpty()) {
                    numerator = Integer.parseInt(binding.xTimesPerMonthTextView.text.toString())
                    denominator = 30
                }
            }
        }
        if (numerator >= denominator || numerator < 1) {
            numerator = 1
            denominator = 1
        }
        onFrequencyPicked(numerator, denominator)
        dismiss()
    }

    private fun check(view: RadioButton) {
        uncheckAll()
        view.isChecked = true
        view.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        populateViews()
    }

    private fun populateViews() {
        uncheckAll()
        if (freqDenominator == 30 || freqDenominator == 31) {
            binding.xTimesPerMonthRadioButton.isChecked = true
            binding.xTimesPerMonthTextView.setText(freqNumerator.toString())
            selectInputField(binding.xTimesPerMonthTextView)
        } else {
            if (freqNumerator == 1) {
                if (freqDenominator == 1) {
                    binding.everyDayRadioButton.isChecked = true
                } else {
                    binding.everyXDaysRadioButton.isChecked = true
                    binding.everyXDaysTextView.setText(freqDenominator.toString())
                    selectInputField(binding.everyXDaysTextView)
                }
            } else {
                if (freqDenominator == 7) {
                    binding.xTimesPerWeekRadioButton.isChecked = true
                    binding.xTimesPerWeekTextView.setText(freqNumerator.toString())
                    selectInputField(binding.xTimesPerWeekTextView)
                } else {
                    binding.xTimesPerYDaysRadioButton.isChecked = true
                    binding.xTimesPerYDaysXTextView.setText(freqNumerator.toString())
                    binding.xTimesPerYDaysYTextView.setText(freqDenominator.toString())
                }
            }
        }
    }

    private fun selectInputField(view: EditText) {
        view.setSelection(view.text.length)
    }

    private fun uncheckAll() {
        binding.everyDayRadioButton.isChecked = false
        binding.everyXDaysRadioButton.isChecked = false
        binding.xTimesPerWeekRadioButton.isChecked = false
        binding.xTimesPerMonthRadioButton.isChecked = false
        binding.xTimesPerYDaysRadioButton.isChecked = false
    }
}
