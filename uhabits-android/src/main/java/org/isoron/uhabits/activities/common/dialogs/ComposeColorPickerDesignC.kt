/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.isoron.uhabits.core.models.PaletteColor

/**
 * Backward compatibility wrapper delegating to the canonical production [UHabitColorPickerContent].
 */
@Composable
internal fun ComposeColorPickerDesignC(
    selectedColor: PaletteColor?,
    previewName: String,
    onApply: (PaletteColor) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UHabitColorPickerContent(
        selectedColor = selectedColor,
        onApply = onApply,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
