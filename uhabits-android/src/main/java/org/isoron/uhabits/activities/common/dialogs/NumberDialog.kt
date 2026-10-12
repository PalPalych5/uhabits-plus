package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.appcompat.app.AppCompatDialog
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.databinding.CheckmarkPopupBinding
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.getCenter
import org.isoron.uhabits.utils.requestFocusWithKeyboard
import org.isoron.uhabits.utils.sres
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParseException

class NumberDialog : AppCompatDialogFragment() {

    var onToggle: (Double, String) -> Unit = { _, _ -> }
    var onDismiss: () -> Unit = {}
    var onRevokeContributions: ((List<String>) -> Unit)? = null
    var onRestoreContributions: ((List<String>) -> Unit)? = null

    private var dismissedViaSaveAction = false
    private var originalNotes: String = ""
    private var originalValue: Double = 0.0
    private lateinit var view: CheckmarkPopupBinding

    private var currentActiveFocus: Double = 0.0
    private var currentTombstonedFocus: Double = 0.0
    private var activeIds = mutableListOf<String>()
    private var tombstonedIds = mutableListOf<String>()
    private var habitUnit: String = ""
    private var hasManualEntry: Boolean = false
    private var hasCompleteCoverage: Boolean = true

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appComponent = (requireActivity().application as HabitsApplication).component
        val prefs = appComponent.preferences
        val dialog = AppCompatDialog(requireContext(), R.style.CustomTransparentDialogTheme)
        view = CheckmarkPopupBinding.inflate(LayoutInflater.from(dialog.context))

        arguments?.getInt("color")?.let { color ->
            if (color != 0) view.yesBtn.setTextColor(color)
        }
        arrayOf(view.noBtn, view.unknownBtnNumber).forEach {
            it.setTextColor(view.root.sres.getColor(R.attr.contrast60))
        }
        arrayOf(view.yesBtn, view.noBtn, view.unknownBtnNumber).forEach {
            it.typeface = InterfaceUtils.getFontAwesome(requireContext())
        }
        if (!prefs.isSkipEnabled) view.skipBtnNumber.visibility = View.GONE
        if (!prefs.areQuestionMarksEnabled) view.unknownBtnNumber.visibility = View.GONE
        view.numberButtons.visibility = View.VISIBLE
        fixDecimalSeparator(view)

        originalNotes = requireArguments().getString("notes", "") ?: ""
        originalValue = requireArguments().getDouble("value", 0.0)

        val focusVal = arguments?.getDouble("focusValue", 0.0) ?: 0.0
        val tombstonedVal = arguments?.getDouble("tombstonedFocusValue", 0.0) ?: 0.0
        activeIds = (arguments?.getStringArrayList("activeContributionIds") ?: arrayListOf()).toMutableList()
        tombstonedIds = (arguments?.getStringArrayList("tombstonedContributionIds") ?: arrayListOf()).toMutableList()
        currentActiveFocus = focusVal
        currentTombstonedFocus = tombstonedVal
        habitUnit = arguments?.getString("unit") ?: ""
        hasManualEntry = arguments?.getBoolean("hasManualEntry", false) ?: false
        hasCompleteCoverage = arguments?.getBoolean("hasCompleteCoverage", true) ?: true

        val hasContributions = activeIds.isNotEmpty() || tombstonedIds.isNotEmpty() || currentActiveFocus > 0.0 || currentTombstonedFocus > 0.0
        if (hasContributions) {
            view.focusBreakdownPanel.visibility = View.VISIBLE
            view.focusBreakdownTitle.setText(R.string.focus_edit_breakdown_title)
            updateBreakdown()

            view.focusBreakdownExcludeBtn.setOnClickListener {
                if (currentActiveFocus > 0.0 && activeIds.isNotEmpty()) {
                    val toRevoke = activeIds.toList()
                    onRevokeContributions?.invoke(toRevoke)
                    tombstonedIds.addAll(toRevoke)
                    activeIds.clear()
                    currentTombstonedFocus += currentActiveFocus
                    currentActiveFocus = 0.0
                    updateBreakdown()
                } else if (tombstonedIds.isNotEmpty()) {
                    val toRestore = tombstonedIds.toList()
                    onRestoreContributions?.invoke(toRestore)
                    activeIds.addAll(toRestore)
                    tombstonedIds.clear()
                    currentActiveFocus += currentTombstonedFocus
                    currentTombstonedFocus = 0.0
                    updateBreakdown()
                }
            }

            view.value.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateBreakdown()
                }
            })
        }

        view.notes.setText(originalNotes)
        view.value.setText(
            when {
                originalValue < 0.0 -> ""
                originalValue < 0.01 -> "0"
                else -> DecimalFormat("#.##").format(originalValue)
            }
        )
        view.value.setOnKeyListener { _, keyCode, event ->
            if (event.action == MotionEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                save()
                return@setOnKeyListener true
            }
            return@setOnKeyListener false
        }
        view.saveBtn.setOnClickListener {
            save()
        }
        view.skipBtnNumber.setOnClickListener {
            view.value.setText(DecimalFormat("#.###").format((Entry.SKIP.toDouble() / 1000)))
            save()
        }

        view.unknownBtnNumber.setOnClickListener {
            view.value.setText(DecimalFormat("#.###").format((Entry.UNKNOWN.toDouble() / 1000)))
            save()
        }

        view.notes.setOnEditorActionListener { _, _, _ ->
            save()
            true
        }
        CustomDialogs.stylePlainEditText(view.notes)
        CustomDialogs.stylePlainEditText(view.value)
        view.value.requestFocusWithKeyboard()
        dialog.setContentView(view.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return dialog
    }

    private fun updateBreakdown() {
        val exactFormat = DecimalFormat("#.##")
        fun fmt(v: Double) = "${exactFormat.format(v)} $habitUnit".trim()

        val text = view.value.text.toString().trim()
        val parsedManual: Double? = if (text.isNotEmpty()) {
            try {
                NumberFormat.getInstance().parse(text)?.toDouble()
            } catch (e: ParseException) {
                null
            }
        } else {
            if (hasManualEntry && originalValue >= 0.0) originalValue else null
        }

        if (currentActiveFocus > 0.0) {
            view.focusBreakdownFocusText.text = getString(R.string.focus_edit_focus_label, fmt(currentActiveFocus))
            view.focusBreakdownExcludeBtn.visibility = View.VISIBLE
            view.focusBreakdownExcludeBtn.setText(R.string.focus_edit_exclude_action)
        } else if (currentTombstonedFocus > 0.0) {
            view.focusBreakdownFocusText.text = getString(R.string.focus_edit_focus_excluded_label, fmt(0.0), fmt(currentTombstonedFocus))
            view.focusBreakdownExcludeBtn.visibility = View.VISIBLE
            view.focusBreakdownExcludeBtn.setText(R.string.focus_edit_restore_action)
        } else {
            view.focusBreakdownFocusText.text = getString(R.string.focus_edit_focus_label, fmt(0.0))
            view.focusBreakdownExcludeBtn.visibility = View.GONE
        }

        if (parsedManual != null) {
            view.focusBreakdownManualText.text = getString(R.string.focus_edit_manual_label, fmt(parsedManual))
        } else {
            view.focusBreakdownManualText.text = getString(R.string.focus_edit_manual_none)
        }

        val total = (parsedManual ?: 0.0) + currentActiveFocus
        val isMin = parsedManual == null && currentActiveFocus > 0.0 && !hasCompleteCoverage
        view.focusBreakdownTotalText.text = if (isMin) {
            getString(R.string.focus_edit_total_min_label, fmt(total))
        } else {
            getString(R.string.focus_edit_total_label, fmt(total))
        }

        view.focusBreakdownHint.setText(
            if (currentActiveFocus == 0.0 && currentTombstonedFocus > 0.0)
                R.string.focus_edit_hint_excluded
            else
                R.string.focus_edit_hint
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        if (!dismissedViaSaveAction) {
            val currentNotes = view.notes.text.toString().trim()
            if (currentNotes != originalNotes) {
                onToggle(originalValue, currentNotes)
            }
        }
        onDismiss()
    }

    private fun fixDecimalSeparator(view: CheckmarkPopupBinding) {
        val separator = DecimalFormatSymbols.getInstance().decimalSeparator
        view.value.keyListener = DigitsKeyListener.getInstance("0123456789$separator")

        val currKeyboard = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        if (currKeyboard?.contains("swiftkey", ignoreCase = true) == true ||
            currKeyboard?.contains("samsung", ignoreCase = true) == true) {
            view.value.inputType = EditorInfo.TYPE_CLASS_TEXT
        }
    }

    fun save() {
        dismissedViaSaveAction = true
        var value = originalValue
        try {
            val numberFormat = NumberFormat.getInstance()
            val valueStr = view.value.text.toString()
            value = if (valueStr.isNotEmpty()) {
                numberFormat.parse(valueStr)!!.toDouble()
            } else {
                Entry.UNKNOWN.toDouble() / 1000
            }
        } catch (e: ParseException) {
            // NOP
        }
        val notes = view.notes.text.toString().trim()
        onToggle(value, notes)
        requireDialog().dismiss()
    }
}
