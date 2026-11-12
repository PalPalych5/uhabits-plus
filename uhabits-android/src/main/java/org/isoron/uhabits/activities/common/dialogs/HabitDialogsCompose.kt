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

/**
 * Modern Material 3 content for numerical habit editing dialog ([NumberDialog]).
 *
 * Faithfully recovers the compact, quiet, restrained visual language of Loop Habit Tracker:
 * - Crisp, compact dialog box (6dp rounded corners, subtle hairline border).
 * - Unboxed, quiet note field with centered placeholder/text.
 * - Hairline horizontal divider.
 * - Single 48dp tactile bottom row combining the numerical input cell with SAVE, SKIP, and ? actions.
 */
@Composable
fun NumberDialogContent(
    initialFormattedValue: String,
    habitUnit: String,
    habitColorArgb: Int,
    notes: String,
    onNotesChanged: (String) -> Unit,
    isSkipEnabled: Boolean,
    isUnknownEnabled: Boolean,
    activeFocus: Double,
    tombstonedFocus: Double,
    hasContributions: Boolean,
    hasManualEntry: Boolean,
    hasCompleteCoverage: Boolean,
    originalValue: Double,
    onExcludeContributions: () -> Unit,
    onRestoreContributions: () -> Unit,
    onSaveClicked: () -> Unit,
    onSkipClicked: () -> Unit,
    onUnknownClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onEditTextAttached: (EditText) -> Unit,
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
    var enteredText by remember { mutableStateOf(initialFormattedValue) }

    Column(
        modifier = modifier
            .widthIn(min = 250.dp, max = 310.dp)
            .clip(shape)
            .background(surfaceColor)
            .border(1.dp, borderColor, shape)
    ) {
        // Focus breakdown panel if contributions exist
        if (hasContributions) {
            FocusBreakdownPanel(
                enteredText = enteredText,
                habitUnit = habitUnit,
                activeFocus = activeFocus,
                tombstonedFocus = tombstonedFocus,
                hasManualEntry = hasManualEntry,
                hasCompleteCoverage = hasCompleteCoverage,
                originalValue = originalValue,
                onExcludeContributions = onExcludeContributions,
                onRestoreContributions = onRestoreContributions,
                colors = colors
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(dividerColor)
            )
        }

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

        // Action row: 48dp height with input cell, SAVE, SKIP, and ?
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Number input cell hosting actual EditText with id R.id.value
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 56.dp)
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        AppCompatEditText(ctx).apply {
                            id = R.id.value
                            background = null
                            gravity = Gravity.CENTER
                            textSize = 15f
                            setTextColor(colors.foreground)
                            setHintTextColor(neutralColor.copy(alpha = 0.5f).hashCode())
                            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL
                            val separator = DecimalFormatSymbols.getInstance().decimalSeparator
                            keyListener = DigitsKeyListener.getInstance("0123456789$separator")
                            setText(initialFormattedValue)
                            selectAll()
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                override fun afterTextChanged(s: Editable?) {
                                    enteredText = s?.toString() ?: ""
                                }
                            })
                            setOnKeyListener { _, keyCode, event ->
                                if (event.action == MotionEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                                    onSaveClicked()
                                    true
                                } else false
                            }
                            onEditTextAttached(this)
                            post {
                                requestFocusWithKeyboard()
                                selectAll()
                            }
                        }
                    }
                )
            }

            // Vertical divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(dividerColor)
            )

            val actionTextColor = Color(colors.foreground)
            val saveDesc = stringResource(R.string.dialog_action_save_description)
            val skipDesc = stringResource(R.string.dialog_action_skip_description)

            // Save button (Cell 2): measured text width with horizontal padding, restrained medium typography
            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .fillMaxHeight()
                    .clickable(
                        role = Role.Button,
                        onClick = onSaveClicked
                    )
                    .padding(horizontal = 10.dp)
                    .semantics { contentDescription = saveDesc },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.save).uppercase(Locale.getDefault()),
                    style = TextStyle(
                        color = actionTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Skip button (Cell 3, if enabled): measured text width with identical typography and color
            if (isSkipEnabled) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor)
                )
                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .fillMaxHeight()
                        .clickable(
                            role = Role.Button,
                            onClick = onSkipClicked
                        )
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = skipDesc },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.dialog_action_skip).uppercase(Locale.getDefault()),
                        style = TextStyle(
                            color = actionTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.4.sp
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Unknown / question button (Cell 4, if enabled)
            if (isUnknownEnabled) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor)
                )
                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .fillMaxHeight()
                        .clickable(
                            role = Role.Button,
                            onClick = onUnknownClicked
                        )
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = "Unknown" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.fa_question),
                        fontFamily = fontAwesome,
                        color = actionTextColor,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusBreakdownPanel(
    enteredText: String,
    habitUnit: String,
    activeFocus: Double,
    tombstonedFocus: Double,
    hasManualEntry: Boolean,
    hasCompleteCoverage: Boolean,
    originalValue: Double,
    onExcludeContributions: () -> Unit,
    onRestoreContributions: () -> Unit,
    colors: UHabitComposeColors
) {
    val exactFormat = remember { DecimalFormat("#.##") }
    fun fmt(v: Double) = "${exactFormat.format(v)} $habitUnit".trim()

    val parsedManual: Double? = if (enteredText.isNotBlank()) {
        when (val res = NumericalInputValidator.validateEntryInput(enteredText.trim())) {
            is NumericalInputValidator.ValidationResult.Valid -> if (res.value >= 0.0) res.value else null
            else -> null
        }
    } else {
        if (hasManualEntry && originalValue >= 0.0) originalValue else null
    }

    val total = (parsedManual ?: 0.0) + activeFocus
    val isMin = parsedManual == null && activeFocus > 0.0 && !hasCompleteCoverage
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_edit_breakdown_title),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.foregroundColor
            )
        )

        Spacer(Modifier.height(6.dp))

        // Focus Row with Exclude / Restore
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val focusText = if (activeFocus > 0.0) {
                stringResource(R.string.focus_edit_focus_label, fmt(activeFocus))
            } else if (tombstonedFocus > 0.0) {
                stringResource(R.string.focus_edit_focus_excluded_label, fmt(0.0), fmt(tombstonedFocus))
            } else {
                stringResource(R.string.focus_edit_focus_label, fmt(0.0))
            }

            Text(
                text = focusText,
                style = TextStyle(fontSize = 13.sp, color = colors.foregroundColor)
            )

            if (activeFocus > 0.0) {
                Text(
                    text = stringResource(R.string.focus_edit_exclude_action),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(colors.accent)
                    ),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onExcludeContributions)
                        .padding(4.dp)
                )
            } else if (tombstonedFocus > 0.0) {
                Text(
                    text = stringResource(R.string.focus_edit_restore_action),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(colors.accent)
                    ),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onRestoreContributions)
                        .padding(4.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Manual Row
        val manualText = if (parsedManual != null) {
            stringResource(R.string.focus_edit_manual_label, fmt(parsedManual))
        } else {
            stringResource(R.string.focus_edit_manual_none)
        }
        Text(
            text = manualText,
            style = TextStyle(fontSize = 13.sp, color = colors.foregroundColor)
        )

        Spacer(Modifier.height(4.dp))

        // Total Row
        val totalText = if (isMin) {
            stringResource(R.string.focus_edit_total_min_label, fmt(total))
        } else {
            stringResource(R.string.focus_edit_total_label, fmt(total))
        }
        Text(
            text = totalText,
            style = TextStyle(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = colors.foregroundColor
            )
        )

        Spacer(Modifier.height(6.dp))

        // Hint
        val hintText = if (activeFocus == 0.0 && tombstonedFocus > 0.0) {
            stringResource(R.string.focus_edit_hint_excluded)
        } else {
            stringResource(R.string.focus_edit_hint)
        }
        Text(
            text = hintText,
            style = TextStyle(fontSize = 11.sp, color = colors.secondaryColor)
        )
    }
}
