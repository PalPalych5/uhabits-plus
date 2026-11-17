/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors

/**
 * Shared text input field primitive supporting optional label, character limit counter,
 * placeholder, multiline input, and inline validation error message.
 */
@Composable
fun UHabitTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    maxLength: Int? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    errorMessage: String? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = label,
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(colors.secondary)
                    )
                )
                if (maxLength != null) {
                    BasicText(
                        text = "${value.length}/$maxLength",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = Color(colors.secondary).copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }

        val border = if (errorMessage != null) {
            colors.dangerColor
        } else {
            Color(colors.border)
        }

        val fieldBg = Color(colors.surfaceVariant).copy(alpha = 0.35f)
        val fieldShape = RoundedCornerShape(10.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(fieldShape)
                .background(fieldBg)
                .border(1.dp, border, fieldShape)
                .padding(horizontal = 12.dp, vertical = if (singleLine) 10.dp else 8.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = {
                    if (maxLength == null || it.length <= maxLength) {
                        onValueChange(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
                minLines = minLines,
                textStyle = TextStyle(
                    fontSize = 14.5.sp,
                    color = Color(colors.foreground),
                    fontFamily = FontFamily.Default
                ),
                cursorBrush = SolidColor(Color(colors.accent)),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = keyboardType,
                    imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            BasicText(
                                text = placeholder,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = Color(colors.secondary).copy(alpha = 0.45f),
                                    fontFamily = FontFamily.Default
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }

        if (errorMessage != null) {
            BasicText(
                text = errorMessage,
                style = TextStyle(
                    fontSize = 11.5.sp,
                    color = colors.dangerColor,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.padding(start = 4.dp, top = 3.dp)
            )
        }
    }
}
