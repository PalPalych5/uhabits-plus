/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.isoron.uhabits.activities.common.dialogs

import android.text.Editable
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.platform.gui.AndroidDataView
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.compose.components.UHabitActionChip
import org.isoron.uhabits.compose.components.UHabitDialogShell
import org.isoron.uhabits.compose.components.UHabitTextField
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL
import org.isoron.uhabits.utils.InterfaceUtils
import org.isoron.uhabits.utils.requestFocusWithKeyboard
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Modern Material 3 content for binary habit check-in dialog ([CheckmarkDialog]).
 *
 * Faithfully recovers the compact, quiet, restrained visual language of Loop Habit Tracker:
 * - Crisp, compact dialog box (6dp rounded corners, subtle hairline border).
 * - Unboxed, quiet note field with centered placeholder/text.
 * - Hairline horizontal divider.
 * - Single 48dp tactile action row with equal-width cells and hairline vertical dividers.
 */
@Composable
fun CheckmarkDialogContent(
    notes: String,
    onNotesChanged: (String) -> Unit,
    onSaveNotesAndDismiss: () -> Unit,
    habitColorArgb: Int,
    isSkipEnabled: Boolean,
    isUnknownEnabled: Boolean,
    onActionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current
) {
    val context = LocalContext.current
    val fontAwesome = remember(context) {
        InterfaceUtils.getFontAwesome(context)?.let { FontFamily(it) } ?: FontFamily.Default
    }
    val habitColor = if (habitColorArgb != 0) Color(habitColorArgb) else Color(colors.accent)
    val neutralColor = colors.secondaryColor
    val shape = RoundedCornerShape(5.dp)

    val surfaceColor = if (colors.isPureBlack) {
        Color(UHabitElevationAndBorders.AMOLED_BORDERED_CARD_SURFACE_ARGB)
    } else {
        Color(colors.surface)
    }
    val borderColor = colors.resolveSectionBorderColor()
    val dividerColor = colors.resolveDividerColor()

    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 260.dp)
            .clip(shape)
            .background(surfaceColor)
            .border(1.dp, borderColor, shape)
    ) {
        // Notes area: unboxed, clean, centered placeholder/hint, matching original
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp, max = 74.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = notes,
                onValueChange = onNotesChanged,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = Color(colors.foreground),
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(habitColor),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (notes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.notes),
                                style = TextStyle(
                                    color = neutralColor.copy(alpha = 0.6f),
                                    fontSize = 13.5.sp,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }

        // Horizontal hairline divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor)
        )

        // Action row: 48dp height, equal width cells separated by vertical hairline dividers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkmark (YES)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        role = Role.Button,
                        onClick = { onActionSelected(YES_MANUAL) }
                    )
                    .semantics { contentDescription = "Check" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.fa_check),
                    fontFamily = fontAwesome,
                    color = habitColor,
                    fontSize = 12.5.sp
                )
            }

            // Skip (SKIP)
            if (isSkipEnabled) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            role = Role.Button,
                            onClick = { onActionSelected(SKIP) }
                        )
                        .semantics { contentDescription = "Skip" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.fa_skipped),
                        fontFamily = fontAwesome,
                        color = habitColor,
                        fontSize = 12.5.sp
                    )
                }
            }

            // Cross / No (NO)
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerColor)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        role = Role.Button,
                        onClick = { onActionSelected(NO) }
                    )
                    .semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.fa_times),
                    fontFamily = fontAwesome,
                    color = neutralColor,
                    fontSize = 12.sp
                )
            }

            // Unknown / Question (UNKNOWN)
            if (isUnknownEnabled) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            role = Role.Button,
                            onClick = { onActionSelected(UNKNOWN) }
                        )
                        .semantics { contentDescription = "Unknown" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.fa_question),
                        fontFamily = fontAwesome,
                        color = neutralColor,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

