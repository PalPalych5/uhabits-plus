/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
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
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.Entry
import java.text.DecimalFormat

class NumberDialog : AppCompatDialogFragment() {

    var onToggle: (Double, String) -> Unit = { _, _ -> }
    var onDismiss: () -> Unit = {}

    private var dismissedViaSaveAction = false
    private var originalNotes: String = ""
    private var currentNotes: String = ""
    private var originalValue: Double = 0.0
    private var originalRawValue: Int = Entry.UNKNOWN

    private var initialManualMillis: Int = 0
    private var initialEffectiveMillis: Int = 0
    private var hasEffectiveTotal: Boolean = false
    private var validationErrorState by mutableStateOf<String?>(null)

    internal var valueView: EditText? = null

    companion object CompanionFormat {
        sealed class SaveResolution {
            data class Success(val totalValue: Double) : SaveResolution()
        }

        internal fun formatMillisForInput(millis: Int): String {
            if (millis <= 0) return "0"
            return DecimalFormat("#.###").format(millis / 1000.0)
        }

        internal fun initialDisplayMillis(
            initialManualMillis: Int,
            effectiveMillis: Int? = null,
            activeFocusMillis: Int = 0,
            hasContributions: Boolean = false
        ): Int = effectiveMillis ?: if (hasContributions) initialManualMillis + activeFocusMillis else initialManualMillis

        internal fun resolveManualSave(
            enteredTotalMillis: Int,
        ): SaveResolution = SaveResolution.Success(enteredTotalMillis.coerceAtLeast(0) / 1000.0)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appComponent = (requireActivity().application as HabitsApplication).component
        val prefs = appComponent.preferences
        val dialog = Dialog(requireContext(), R.style.CustomTransparentDialogTheme)

        val color = arguments?.getInt("color", 0) ?: 0
        originalNotes = requireArguments().getString("notes", "") ?: ""
        currentNotes = originalNotes
        originalValue = requireArguments().getDouble("value", 0.0)
        originalRawValue = arguments?.getInt("originalRawValue", Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?: runCatching { Entry.toScaledValue(originalValue) }.getOrDefault(Entry.UNKNOWN)

        initialManualMillis = if (originalRawValue != Entry.UNKNOWN && originalRawValue != Entry.SKIP && originalRawValue >= 0) {
            originalRawValue
        } else 0
        val effectiveMillisArg = arguments?.getInt("effectiveMillis", Int.MIN_VALUE) ?: Int.MIN_VALUE
        hasEffectiveTotal = (arguments?.getBoolean("hasEffectiveTotal", false) ?: false) && effectiveMillisArg != Int.MIN_VALUE
        val focusMillisFallback = (arguments?.getInt("focusMillis", 0) ?: 0).coerceAtLeast(0)

        val initialDisplayMillis = CompanionFormat.initialDisplayMillis(
            initialManualMillis = initialManualMillis,
            effectiveMillis = if (hasEffectiveTotal) effectiveMillisArg.coerceAtLeast(0) else null,
            activeFocusMillis = focusMillisFallback,
            hasContributions = focusMillisFallback > 0
        )
        initialEffectiveMillis = initialDisplayMillis
        val initialFormatted = formatMillisForInput(initialDisplayMillis)

        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)
        val colors = UHabitComposeColors.fromPalette(palette)

        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors = colors) {
                    var notesState by remember { mutableStateOf(originalNotes) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                dialog.dismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // Do not dismiss when clicking inside the card
                            }
                        ) {
                            NumberDialogContent(
                                initialFormattedValue = initialFormatted,
                                habitColorArgb = color,
                                notes = notesState,
                                onNotesChanged = {
                                    notesState = it
                                    currentNotes = it
                                },
                                isSkipEnabled = prefs.isSkipEnabled,
                                isUnknownEnabled = prefs.areQuestionMarksEnabled,
                                validationError = validationErrorState,
                                onValueTextChanged = {
                                    if (validationErrorState != null) {
                                        validationErrorState = null
                                    }
                                },
                                onSaveClicked = { save() },
                                onSkipClicked = {
                                    dismissedViaSaveAction = true
                                    onToggle(Entry.SKIP.toDouble() / 1000.0, currentNotes.trim())
                                    dialog.dismiss()
                                },
                                onUnknownClicked = {
                                    dismissedViaSaveAction = true
                                    onToggle(Entry.UNKNOWN.toDouble() / 1000.0, currentNotes.trim())
                                    dialog.dismiss()
                                },
                                onCancelClicked = {
                                    dialog.dismiss()
                                },
                                onEditTextAttached = { editText ->
                                    valueView = editText
                                },
                                colors = colors
                            )
                        }
                    }
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        if (!dismissedViaSaveAction) {
            val currentNotesTrimmed = currentNotes.trim()
            if (currentNotesTrimmed != originalNotes.trim()) {
                try {
                    val preservedValue = if (hasEffectiveTotal && originalRawValue != Entry.SKIP) {
                        initialEffectiveMillis / 1000.0
                    } else {
                        originalValue
                    }
                    onToggle(preservedValue, currentNotesTrimmed)
                } catch (e: Exception) {
                    // Pre-existing accepted data or dismissal must not throw on note update
                }
            }
        }
        onDismiss()
    }

    fun save() {
        val valueStr = valueView?.text?.toString() ?: ""
        when (val result = NumericalInputValidator.validateEntryInput(valueStr)) {
            is NumericalInputValidator.ValidationResult.Valid -> {
                validationErrorState = null
                dismissedViaSaveAction = true
                onToggle(result.value, currentNotes.trim())
                requireDialog().dismiss()
            }
            is NumericalInputValidator.ValidationResult.OutOfRange -> {
                val msg = getString(R.string.validation_number_out_of_range)
                validationErrorState = msg
                valueView?.error = null
                valueView?.requestFocus()
            }
            is NumericalInputValidator.ValidationResult.InvalidFormat -> {
                val msg = getString(R.string.validation_invalid_number_format)
                validationErrorState = msg
                valueView?.error = null
                valueView?.requestFocus()
            }
        }
    }
}
