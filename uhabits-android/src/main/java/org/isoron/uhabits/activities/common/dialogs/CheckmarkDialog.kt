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
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
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

class CheckmarkDialog : AppCompatDialogFragment() {
    var onToggle: (Int, String) -> Unit = { _, _ -> }
    var onDismiss: () -> Unit = {}

    private var dismissedViaSaveAction = false
    private var originalNotes: String = ""
    private var currentNotes: String = ""
    private var originalValue: Int = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appComponent = (requireActivity().application as HabitsApplication).component
        val prefs = appComponent.preferences
        val color = requireArguments().getInt("color")
        originalNotes = requireArguments().getString("notes", "") ?: ""
        originalValue = requireArguments().getInt("value")
        currentNotes = originalNotes

        val palette = SettingsThemePaletteResolver.resolve(requireContext(), prefs)
        val colors = UHabitComposeColors.fromPalette(palette)

        val dialog = Dialog(requireContext(), R.style.CustomTransparentDialogTheme)
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
                            CheckmarkDialogContent(
                                notes = notesState,
                                onNotesChanged = {
                                    notesState = it
                                    currentNotes = it
                                },
                                onSaveNotesAndDismiss = {
                                    dismissedViaSaveAction = true
                                    onToggle(originalValue, currentNotes.trim())
                                    dialog.dismiss()
                                },
                                habitColorArgb = color,
                                isSkipEnabled = prefs.isSkipEnabled,
                                isUnknownEnabled = prefs.areQuestionMarksEnabled,
                                onActionSelected = { actionValue ->
                                    dismissedViaSaveAction = true
                                    onToggle(actionValue, currentNotes.trim())
                                    dialog.dismiss()
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
                onToggle(originalValue, currentNotesTrimmed)
            }
        }
        onDismiss()
    }
}
