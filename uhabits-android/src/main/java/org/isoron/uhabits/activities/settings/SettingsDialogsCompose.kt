package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.DayTier

@Composable
internal fun DayTierSortOrderDialogContent(
    colors: UHabitComposeColors,
    initialOrder: List<DayTier>,
    dayTierLabel: (DayTier) -> String,
    onOrderChanged: (List<DayTier>) -> Unit,
) {
    UHabitComposeTheme(colors) {
        var order by remember(initialOrder) { mutableStateOf(initialOrder) }
        val moveUpText = stringResource(R.string.move_up)
        val moveDownText = stringResource(R.string.move_down)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            order.forEachIndexed { index, tier ->
                val label = dayTierLabel(tier)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}. $label",
                        color = Color(colors.foreground),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    DayTierMoveButton(
                        text = moveUpText,
                        contentDescription = "$moveUpText: $label",
                        enabled = index > 0,
                        colors = colors,
                        onClick = {
                            if (index > 0) {
                                val updated = order.toMutableList().also {
                                    java.util.Collections.swap(it, index, index - 1)
                                }
                                order = updated
                                onOrderChanged(updated)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    DayTierMoveButton(
                        text = moveDownText,
                        contentDescription = "$moveDownText: $label",
                        enabled = index < order.lastIndex,
                        colors = colors,
                        onClick = {
                            if (index < order.lastIndex) {
                                val updated = order.toMutableList().also {
                                    java.util.Collections.swap(it, index, index + 1)
                                }
                                order = updated
                                onOrderChanged(updated)
                            }
                        },
                    )
                }
                if (index < order.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(colors.divider)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTierMoveButton(
    text: String,
    contentDescription: String,
    enabled: Boolean,
    colors: UHabitComposeColors,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (enabled) Color(colors.border) else Color(colors.divider)
    val textColor = if (enabled) {
        Color(colors.accent)
    } else {
        Color(colors.secondary).copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .semantics {
                this.contentDescription = contentDescription
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
internal fun StatisticsStartDateDialogContent(
    colors: UHabitComposeColors,
    options: List<String>,
    selectedIndex: Int = -1,
    onOptionSelected: (Int) -> Unit,
) {
    UHabitComposeTheme(colors) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) Color(colors.accent).copy(alpha = 0.12f) else Color.Transparent
                        )
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = Color(colors.accent).copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(role = Role.RadioButton) { onOptionSelected(index) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(
                                width = 1.75.dp,
                                color = if (isSelected) Color(colors.accent) else Color(colors.secondary),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(Color(colors.accent))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = option,
                        color = Color(colors.foreground),
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal data class SettingsChoiceOption(
    val title: String,
    val subtitle: String? = null,
)

internal object SettingsDialogLayoutMath {
    fun maxListHeightDp(screenHeightDp: Int, fontScale: Float = 1f): Int {
        val chromePenaltyDp = if (fontScale >= 1.5f) 265 else 215
        val available = (screenHeightDp - chromePenaltyDp).coerceAtLeast(112)
        val proportional = (screenHeightDp * 0.52f).toInt().coerceIn(112, 360)
        return minOf(proportional, available).coerceIn(112, 360)
    }
}

internal object PomodoroDurationSpec {
    const val MIN_MINUTES = 1
    const val MAX_FOCUS_MINUTES = 180
    const val MAX_BREAK_MINUTES = 60

    private val focusPresets = listOf(15, 25, 45, 60, 90)
    private val breakPresets = listOf(5, 10, 15, 20, 30)

    fun maxMinutes(isBreak: Boolean): Int =
        if (isBreak) MAX_BREAK_MINUTES else MAX_FOCUS_MINUTES

    fun presets(isBreak: Boolean): List<Int> =
        if (isBreak) breakPresets else focusPresets

    fun sanitizeInput(raw: String): String =
        raw.filter(Char::isDigit).take(3)

    fun parseValidMinutes(raw: String, isBreak: Boolean): Int? {
        val minutes = raw.trim().toIntOrNull() ?: return null
        return minutes.takeIf { it in MIN_MINUTES..maxMinutes(isBreak) }
    }
}

@Composable
internal fun SettingsSingleChoiceDialogContent(
    colors: UHabitComposeColors,
    options: List<SettingsChoiceOption>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val maxListHeight = SettingsDialogLayoutMath
        .maxListHeightDp(configuration.screenHeightDp, configuration.fontScale)
        .dp
    UHabitComposeTheme(colors) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxListHeight)
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(vertical = 2.dp),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onOptionSelected(index) },
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(
                                width = 1.75.dp,
                                color = if (isSelected) Color(colors.accent) else Color(colors.secondary),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(colors.accent)),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.title,
                            color = Color(colors.foreground),
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        )
                        if (!option.subtitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = option.subtitle,
                                color = Color(colors.secondary),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PomodoroDurationDialogContent(
    colors: UHabitComposeColors,
    initialValue: String,
    isBreak: Boolean,
    rangeHint: String,
    formatPresetLabel: (Int) -> String,
    onValueChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val maxListHeight = SettingsDialogLayoutMath
        .maxListHeightDp(configuration.screenHeightDp, configuration.fontScale)
        .dp
    val presets = PomodoroDurationSpec.presets(isBreak)
    val sanitizedInitial = PomodoroDurationSpec.sanitizeInput(initialValue)
    var fieldValue by remember(sanitizedInitial) {
        mutableStateOf(
            TextFieldValue(
                text = sanitizedInitial,
                selection = TextRange(sanitizedInitial.length),
            )
        )
    }
    var isFocused by remember { mutableStateOf(false) }
    val parsed = PomodoroDurationSpec.parseValidMinutes(fieldValue.text, isBreak)
    val isError = fieldValue.text.isNotEmpty() && parsed == null
    val borderColor = when {
        isError -> Color(0xFFD32F2F)
        isFocused -> Color(colors.accent)
        else -> Color(colors.border)
    }

    UHabitComposeTheme(colors) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxListHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(colors.background))
                    .border(
                        width = if (isFocused || isError) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = rangeHint,
                        color = Color(colors.secondary),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                    )
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { next ->
                        val cleaned = PomodoroDurationSpec.sanitizeInput(next.text)
                        val cursor = next.selection.end.coerceIn(0, cleaned.length)
                        fieldValue = TextFieldValue(
                            text = cleaned,
                            selection = TextRange(cursor),
                        )
                        onValueChanged(cleaned)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(colors.foreground),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(Color(colors.accent)),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = rangeHint,
                color = if (isError) Color(0xFFD32F2F) else Color(colors.secondary),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    val label = formatPresetLabel(preset)
                    val isSelected = parsed == preset
                    val chipShape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(chipShape)
                            .background(
                                if (isSelected) {
                                    Color(colors.accent).copy(alpha = 0.14f)
                                } else {
                                    Color(colors.background)
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) Color(colors.accent) else Color(colors.border),
                                shape = chipShape,
                            )
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    val updated = preset.toString()
                                    fieldValue = TextFieldValue(
                                        text = updated,
                                        selection = TextRange(updated.length),
                                    )
                                    onValueChanged(updated)
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(colors.accent) else Color(colors.foreground),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
