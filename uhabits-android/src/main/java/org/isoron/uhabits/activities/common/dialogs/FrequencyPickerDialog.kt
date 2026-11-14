/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.common.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

class FrequencyPickerDialog(
    var freqNumerator: Int,
    var freqDenominator: Int,
    var habitColor: Int? = null
) : AppCompatDialogFragment() {

    var onFrequencyPicked: (num: Int, den: Int) -> Unit = { _, _ -> }
    var onDismissCallback: () -> Unit = {}

    constructor() : this(1, 1, null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = MaterialAlertDialogBuilder(requireActivity(), R.style.CustomTransparentDialogTheme_NoAnimation).create()

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    FrequencyPickerContent(
                        initialNum = freqNumerator,
                        initialDen = freqDenominator,
                        habitColor = habitColor,
                        colors = colors,
                        onCancel = { dialog.dismiss() },
                        onSave = { num, den ->
                            onFrequencyPicked(num, den)
                            dialog.dismiss()
                        }
                    )
                }
            }
        }
        dialog.setView(composeView, 0, 0, 0, 0)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.decorView.setPadding(0, 0, 0, 0)
        }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback()
    }
}

private enum class FrequencyMode {
    EVERY_DAY,
    EVERY_X_DAYS,
    X_TIMES_PER_WEEK,
    X_TIMES_PER_MONTH,
    X_TIMES_PER_Y_DAYS
}

@Composable
internal fun FrequencyPickerContent(
    initialNum: Int,
    initialDen: Int,
    habitColor: Int? = null,
    colors: UHabitComposeColors,
    onCancel: () -> Unit,
    onSave: (Int, Int) -> Unit
) {
    val activeAccent = habitColor?.let { Color(it) } ?: colors.accentColor
    val activeAccentArgb = activeAccent.toArgb()
    val contrastWithWhite = androidx.core.graphics.ColorUtils.calculateContrast(
        android.graphics.Color.WHITE,
        activeAccentArgb
    )
    val buttonTextColor = if (contrastWithWhite >= 4.5) Color.White else Color(0xFF191C20)

    val initialMode = when {
        initialNum == 1 && initialDen == 1 -> FrequencyMode.EVERY_DAY
        initialNum == 1 && initialDen > 1 -> FrequencyMode.EVERY_X_DAYS
        initialDen == 7 -> FrequencyMode.X_TIMES_PER_WEEK
        initialDen == 30 -> FrequencyMode.X_TIMES_PER_MONTH
        else -> FrequencyMode.X_TIMES_PER_Y_DAYS
    }

    var selectedMode by remember { mutableStateOf(initialMode) }
    var everyXDaysText by remember { mutableStateOf(if (initialDen > 1 && initialNum == 1) initialDen.toString() else "2") }
    var xPerWeekText by remember { mutableStateOf(if (initialDen == 7) initialNum.toString() else "3") }
    var xPerMonthText by remember { mutableStateOf(if (initialDen == 30) initialNum.toString() else "10") }
    var xPerYDaysXText by remember { mutableStateOf(if (initialDen != 1 && initialDen != 7 && initialDen != 30 && initialNum != 1) initialNum.toString() else "2") }
    var xPerYDaysYText by remember { mutableStateOf(if (initialDen != 1 && initialDen != 7 && initialDen != 30 && initialNum != 1) initialDen.toString() else "5") }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceColor,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.frequency),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.foregroundColor
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 1. Every day
                FrequencyOptionRow(
                    selected = selectedMode == FrequencyMode.EVERY_DAY,
                    onClick = { selectedMode = FrequencyMode.EVERY_DAY },
                    accentColor = activeAccent,
                    colors = colors
                ) {
                    Text(stringResource(R.string.every_day), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                }

                // 2. Every X days
                FrequencyOptionRow(
                    selected = selectedMode == FrequencyMode.EVERY_X_DAYS,
                    onClick = { selectedMode = FrequencyMode.EVERY_X_DAYS },
                    accentColor = activeAccent,
                    colors = colors
                ) {
                    val template = stringResource(R.string.every_x_days)
                    val parts = template.split("%d")
                    Text(parts.getOrElse(0) { "" }.trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(6.dp))
                    CompactNumberField(
                        value = everyXDaysText,
                        onValueChange = {
                            everyXDaysText = it
                            selectedMode = FrequencyMode.EVERY_X_DAYS
                        },
                        accentColor = activeAccent,
                        colors = colors
                    )
                    if (parts.size > 1 && parts[1].isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(parts[1].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // 3. X times per week
                FrequencyOptionRow(
                    selected = selectedMode == FrequencyMode.X_TIMES_PER_WEEK,
                    onClick = { selectedMode = FrequencyMode.X_TIMES_PER_WEEK },
                    accentColor = activeAccent,
                    colors = colors
                ) {
                    val template = stringResource(R.string.x_times_per_week)
                    val parts = template.split("%d")
                    if (parts.getOrElse(0) { "" }.isNotBlank()) {
                        Text(parts[0].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    CompactNumberField(
                        value = xPerWeekText,
                        onValueChange = {
                            xPerWeekText = it
                            selectedMode = FrequencyMode.X_TIMES_PER_WEEK
                        },
                        accentColor = activeAccent,
                        colors = colors
                    )
                    if (parts.size > 1 && parts[1].isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(parts[1].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // 4. X times per month
                FrequencyOptionRow(
                    selected = selectedMode == FrequencyMode.X_TIMES_PER_MONTH,
                    onClick = { selectedMode = FrequencyMode.X_TIMES_PER_MONTH },
                    accentColor = activeAccent,
                    colors = colors
                ) {
                    val template = stringResource(R.string.x_times_per_month)
                    val parts = template.split("%d")
                    if (parts.getOrElse(0) { "" }.isNotBlank()) {
                        Text(parts[0].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    CompactNumberField(
                        value = xPerMonthText,
                        onValueChange = {
                            xPerMonthText = it
                            selectedMode = FrequencyMode.X_TIMES_PER_MONTH
                        },
                        accentColor = activeAccent,
                        colors = colors
                    )
                    if (parts.size > 1 && parts[1].isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(parts[1].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // 5. X times per Y days
                FrequencyOptionRow(
                    selected = selectedMode == FrequencyMode.X_TIMES_PER_Y_DAYS,
                    onClick = { selectedMode = FrequencyMode.X_TIMES_PER_Y_DAYS },
                    accentColor = activeAccent,
                    colors = colors
                ) {
                    val template = stringResource(R.string.x_times_per_y_days)
                    val parts = template.split("%d")
                    if (parts.getOrElse(0) { "" }.isNotBlank()) {
                        Text(parts[0].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    CompactNumberField(
                        value = xPerYDaysXText,
                        onValueChange = {
                            xPerYDaysXText = it
                            selectedMode = FrequencyMode.X_TIMES_PER_Y_DAYS
                        },
                        accentColor = activeAccent,
                        colors = colors
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(parts.getOrElse(1) { "times in" }.trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyMedium, softWrap = false)
                    Spacer(modifier = Modifier.width(6.dp))
                    CompactNumberField(
                        value = xPerYDaysYText,
                        onValueChange = {
                            xPerYDaysYText = it
                            selectedMode = FrequencyMode.X_TIMES_PER_Y_DAYS
                        },
                        accentColor = activeAccent,
                        colors = colors
                    )
                    if (parts.size > 2 && parts[2].isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(parts[2].trim(), color = colors.foregroundColor, style = MaterialTheme.typography.bodyMedium, softWrap = false)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = colors.secondaryColor
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(activeAccent)
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                var num = 1
                                var den = 1
                                when (selectedMode) {
                                    FrequencyMode.EVERY_DAY -> {
                                        num = 1
                                        den = 1
                                    }
                                    FrequencyMode.EVERY_X_DAYS -> {
                                        num = 1
                                        den = everyXDaysText.toIntOrNull() ?: 1
                                    }
                                    FrequencyMode.X_TIMES_PER_WEEK -> {
                                        num = xPerWeekText.toIntOrNull() ?: 1
                                        den = 7
                                    }
                                    FrequencyMode.X_TIMES_PER_MONTH -> {
                                        num = xPerMonthText.toIntOrNull() ?: 1
                                        den = 30
                                    }
                                    FrequencyMode.X_TIMES_PER_Y_DAYS -> {
                                        num = xPerYDaysXText.toIntOrNull() ?: 1
                                        den = xPerYDaysYText.toIntOrNull() ?: 1
                                    }
                                }
                                if (num >= den || num < 1) {
                                    num = 1
                                    den = 1
                                }
                                onSave(num, den)
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = stringResource(R.string.save),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = buttonTextColor,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FrequencyOptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
    colors: UHabitComposeColors,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = accentColor,
                unselectedColor = colors.secondaryColor
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        content()
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    colors: UHabitComposeColors
) {
    BasicTextField(
        value = value,
        onValueChange = { if (it.all { ch -> ch.isDigit() } && it.length <= 4) onValueChange(it) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = colors.foregroundColor,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(accentColor),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(34.dp)
                    .border(
                        1.dp,
                        if (value.isNotEmpty()) accentColor else colors.borderColor,
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                innerTextField()
            }
        }
    )
}
