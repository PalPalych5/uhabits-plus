/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.common.dialogs

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.uhabits.activities.settings.SettingsThemePalette

internal fun AlertDialog.showPlatformPicker(
    title: CharSequence,
    control: View,
    positiveText: CharSequence,
    neutralText: CharSequence? = null,
    onNeutral: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    val palette = CustomDialogs.resolvePalette(context)
    val isDatePicker = control is android.widget.DatePicker
    val composeView = ComposeView(context).apply {
        setContent {
            PlatformPickerContent(
                title = title,
                palette = palette,
                isDatePicker = isDatePicker,
                positiveText = positiveText,
                neutralText = neutralText,
                onNeutral = onNeutral?.let { action -> { action(); dismiss() } },
                onCancel = ::dismiss,
                onConfirm = { onConfirm(); dismiss() },
            ) {
                // The standard time/calendar control owns locale, accessibility and keyboard input.
                AndroidView(factory = { control }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    setView(composeView, 0, 0, 0, 0)
    show()
    window?.apply {
        setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        decorView.setPadding(0, 0, 0, 0)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformPickerContent(
    title: CharSequence,
    palette: SettingsThemePalette,
    isDatePicker: Boolean,
    positiveText: CharSequence,
    neutralText: CharSequence?,
    onNeutral: (() -> Unit)?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    picker: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val padding = if (isDatePicker) PaddingValues(16.dp, 14.dp, 16.dp, 10.dp) else PaddingValues(24.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Color(palette.dialogSurface))
            .border(1.dp, Color(palette.dialogBorder), shape)
            .verticalScroll(rememberScrollState())
            .padding(padding),
    ) {
        BasicText(
            title.toString(),
            modifier = Modifier.padding(bottom = if (isDatePicker) 8.dp else 16.dp).semantics { heading() },
            style = TextStyle(color = Color(palette.onSurface), fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
        picker()
        val actionHeight = if (isDatePicker) 40.dp else 48.dp
        FlowRow(
            Modifier.fillMaxWidth().padding(top = if (isDatePicker) 4.dp else 20.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isDatePicker) 6.dp else 8.dp, Alignment.End),
        ) {
            if (!neutralText.isNullOrBlank() && onNeutral != null) {
                PickerAction(neutralText, Color(palette.onSurfaceVariant), actionHeight, onNeutral)
            }
            PickerAction(stringResource(android.R.string.cancel), Color(palette.onSurfaceVariant), actionHeight, onCancel)
            PickerAction(positiveText, Color(palette.accent), actionHeight, onConfirm)
        }
    }
}

@Composable
private fun PickerAction(label: CharSequence, color: Color, height: Dp, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = height)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label.toString(), style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}
