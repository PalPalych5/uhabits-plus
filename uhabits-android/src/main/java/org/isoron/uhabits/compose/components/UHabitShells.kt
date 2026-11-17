/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.LocalUHabitDesignTokens
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders

/**
 * Shared bottom-sheet container shell with 24dp top corners, optional drag handle,
 * and navigation bar inset padding.
 */
@Composable
fun UHabitBottomSheetShell(
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    showDragHandle: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetSurface = colors.dialogSurfaceColor
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(sheetSurface)
            .let { mod ->
                if (colors.isPureBlack) {
                    mod.border(
                        width = UHabitElevationAndBorders.hairlineBorderWidth,
                        color = colors.dialogBorderColor,
                        shape = shape
                    )
                } else {
                    mod
                }
            }
            .navigationBarsPadding()
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showDragHandle) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(colors.secondary).copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        content()
    }
}

/**
 * Shared modal dialog container shell with 16dp rounded corners and subtle dark/AMOLED border.
 */
@Composable
fun UHabitDialogShell(
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = LocalUHabitDesignTokens.current.shapes.lg
    val dialogSurface = colors.dialogSurfaceColor
    val borderMod = if (colors.isDark) {
        Modifier.border(
            width = UHabitElevationAndBorders.hairlineBorderWidth,
            color = colors.dialogBorderColor,
            shape = shape
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(dialogSurface)
            .then(borderMod)
            .padding(contentPadding),
        content = content
    )
}
