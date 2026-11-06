/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.callbacks.OnColorPickedCallback

/**
 * Reusable BottomSheetDialogFragment hosting the approved V4 Compose Color Picker [UHabitColorPickerContent].
 *
 * Provides a clean modal bottom-sheet host for Activities, Fragments, and other editing flows.
 */
class UHabitColorPickerBottomSheet : BottomSheetDialogFragment() {

    var onColorPicked: ((PaletteColor) -> Unit)? = null
    private var initialColor: PaletteColor? = null

    fun setListener(listener: OnColorPickedCallback) {
        this.onColorPicked = { listener.onColorPicked(it) }
    }

    companion object {
        private const val ARG_COLOR_INDEX = "arg_color_index"
        private const val ARG_COLOR_CSV = "arg_color_csv"

        fun newInstance(color: PaletteColor?): UHabitColorPickerBottomSheet {
            return UHabitColorPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    if (color != null) {
                        putInt(ARG_COLOR_INDEX, color.paletteIndex)
                        putString(ARG_COLOR_CSV, color.toCsvColor())
                    }
                }
                this.initialColor = color
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (initialColor == null) {
            val idx = arguments?.getInt(ARG_COLOR_INDEX, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (idx != Int.MIN_VALUE) {
                initialColor = PaletteColor(idx)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(AndroidColor.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)
        val colors = UHabitComposeColors.fromPalette(palette)

        return ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(colors) {
                    UHabitColorPickerContent(
                        selectedColor = initialColor,
                        onApply = { picked ->
                            onColorPicked?.invoke(picked)
                            dismiss()
                        },
                        onDismiss = {
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}
