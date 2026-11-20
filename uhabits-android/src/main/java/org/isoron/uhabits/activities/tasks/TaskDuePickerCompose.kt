/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle.SHORT
import java.util.Locale

@Composable
internal fun TaskDuePickerContent(
    today: LocalDate,
    date: LocalDate?,
    month: YearMonth,
    locale: Locale,
    time: LocalTime?,
    is24Hour: Boolean,
    hourText: String,
    minuteText: String,
    zoneCaption: String,
    error: Int?,
    onDate: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onToggleTime: () -> Unit,
    onHour: (String) -> Unit,
    onMinute: (String) -> Unit,
    onTogglePeriod: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onApply: () -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    val onAccent = if (colors.isDark) Color(0xFF102638) else Color.White
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fontScale = LocalDensity.current.fontScale
    val largeText = fontScale > 1.3f

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(1).dp)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(colors.dialogSurfaceColor)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 24.dp)
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(colors.dividerColor, RoundedCornerShape(2.dp)))
            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                DueText(stringResource(R.string.task_due_title), 22, Modifier.weight(1f), medium = true)
                DueIconAction(
                    R.drawable.ic_close, stringResource(R.string.task_due_close),
                    Modifier.offset(x = 12.dp, y = (-2).dp).heightIn(min = 44.dp), onClose
                )
            }
            DueText(
                date?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
                    ?.replaceFirstChar { it.titlecase(locale) } ?: stringResource(R.string.task_due_none),
                17, Modifier.heightIn(min = 24.dp).wrapContentHeight(), medium = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val first = if (largeText) Modifier.weight(1f) else Modifier.width(84.dp)
                val second = if (largeText) Modifier.weight(1f) else Modifier.width(96.dp)
                DueShortcut(stringResource(R.string.task_due_today), date == today, first) {
                    onDate(today)
                }
                val tomorrow = today.plusDays(1)
                DueShortcut(stringResource(R.string.task_due_tomorrow), date == tomorrow, second) {
                    onDate(tomorrow)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DueText(
                    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) },
                    16, Modifier.weight(1f), medium = true
                )
                DueIconAction(
                    R.drawable.ic_chevron_left, stringResource(R.string.task_due_prev_month),
                    Modifier.offset(x = 9.dp).heightIn(min = 32.dp)
                ) { onMonth(month.minusMonths(1)) }
                DueIconAction(
                    R.drawable.ic_chevron_right, stringResource(R.string.task_due_next_month),
                    Modifier.offset(x = 12.dp).heightIn(min = 32.dp)
                ) { onMonth(month.plusMonths(1)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (day in DayOfWeek.entries) {
                    DueText(
                        day.getDisplayName(SHORT, locale).trimEnd('.').replaceFirstChar { it.titlecase(locale) },
                        11, Modifier.weight(1f).heightIn(min = 24.dp).wrapContentHeight(), colors.secondaryColor, center = true
                    )
                }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val weeks = (offset + month.lengthOfMonth() + 6) / 7
            val descriptionFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)
            repeat(weeks) { week ->
                Row {
                    repeat(7) { column ->
                        val number = week * 7 + column - offset + 1
                        val cell = Modifier.weight(1f).height(48.dp)
                        if (number !in 1..month.lengthOfMonth()) {
                            Spacer(cell)
                        } else {
                            val day = month.atDay(number)
                            val selected = day == date
                            val description = day.format(descriptionFormat) +
                                if (selected) stringResource(R.string.task_due_selected_suffix) else ""
                            Box(
                                cell.selectable(selected, role = Role.Button) { onDate(day) }
                                    .semantics { contentDescription = description },
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Box(
                                    Modifier.widthIn(max = 34.dp).fillMaxWidth().height(34.dp)
                                        .background(if (selected) colors.accentColor else Color.Transparent, RoundedCornerShape(17.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    DueText(number.toString(), 15, color = if (selected) onAccent else colors.foregroundColor,
                                        medium = selected || day == today, center = true, hideSemantics = true)
                                }
                                if (day == today && !selected) {
                                    Box(Modifier.align(Alignment.TopCenter).offset(y = 30.dp).size(3.dp)
                                        .background(colors.secondaryColor, RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                }
            }
            DueDivider()
            val timeLabel = stringResource(if (time == null) R.string.task_due_add_time else R.string.task_due_time_label)
            if (time == null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable(role = Role.Button, onClick = onToggleTime),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DueIcon(R.drawable.ic_settings_clock, 20)
                    DueText(timeLabel, 15, Modifier.weight(1f), colors.secondaryColor)
                    Box(Modifier.padding(end = 5.dp)) { DueIcon(R.drawable.ic_toolbar_add, 18) }
                }
            } else {
                val clockControls: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DueClockField(hourText, stringResource(R.string.task_due_hours_desc), onHour)
                        DueText(":", 18, Modifier.width(8.dp), colors.secondaryColor, center = true)
                        DueClockField(minuteText, stringResource(R.string.task_due_minutes_desc), onMinute)
                        if (!is24Hour) {
                            val period = time.format(DateTimeFormatter.ofPattern("a", locale))
                            Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onTogglePeriod),
                                contentAlignment = Alignment.Center) {
                                DueText(period, 13, color = colors.secondaryColor, center = true)
                            }
                        }
                        if (largeText) Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.widthIn(min = 68.dp, max = if (largeText) 88.dp else 76.dp)
                                .heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggleTime),
                            contentAlignment = Alignment.Center
                        ) {
                            DueText(stringResource(R.string.task_due_remove_time), 12, color = colors.secondaryColor, center = true)
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (largeText || maxWidth < (if (is24Hour) 260 else 324).dp) {
                        Column {
                            DueTimeLabel(timeLabel, Modifier.heightIn(min = 36.dp))
                            Box(Modifier.align(Alignment.End)) { clockControls() }
                        }
                    } else {
                        Row(Modifier.heightIn(min = 55.dp), verticalAlignment = Alignment.CenterVertically) {
                            DueTimeLabel(timeLabel, Modifier.weight(1f))
                            clockControls()
                        }
                    }
                }
                DueText(zoneCaption, 11, Modifier.heightIn(min = 24.dp).padding(start = 32.dp), colors.secondaryColor)
            }
            if (error != null) {
                DueText(
                    stringResource(error), 13,
                    Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    colors.secondaryColor
                )
            }
        }
        DueDivider()
        val clearButton: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier.widthIn(min = 96.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClear),
                contentAlignment = Alignment.CenterStart
            ) {
                DueText(stringResource(R.string.task_due_clear), 14, color = colors.secondaryColor)
            }
        }
        val applyButton: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier.heightIn(min = 48.dp)
                    .alpha(if (error == null) 1f else .45f)
                    .clickable(enabled = error == null, role = Role.Button) {
                        focus.clearFocus()
                        keyboard?.hide()
                        onApply()
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.widthIn(min = 132.dp).heightIn(min = 44.dp)
                    .background(colors.accentColor, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    DueText(stringResource(R.string.task_due_apply), 14, color = onAccent, medium = true, center = true)
                }
            }
        }
        val footer = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 13.dp)
        BoxWithConstraints {
            if (largeText && maxWidth < 320.dp) {
                Column(footer, horizontalAlignment = Alignment.End) {
                    clearButton(Modifier)
                    applyButton(Modifier)
                }
            } else {
                Row(footer, verticalAlignment = Alignment.CenterVertically) {
                    clearButton(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    applyButton(Modifier)
                }
            }
        }
    }
}

@Composable
private fun DueTimeLabel(label: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DueIcon(R.drawable.ic_settings_clock, 20)
        DueText(label, 15)
    }
}

@Composable
private fun DueClockField(text: String, description: String, onText: (String) -> Unit) {
    val colors = LocalUHabitComposeColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    var focused by remember { mutableStateOf(false) }
    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    LaunchedEffect(focused) {
        if (focused) {
            // Let the tap that gives the field focus finish positioning the cursor.
            withFrameNanos { }
            field = value.copy(selection = TextRange(0, text.length))
        }
    }
    BasicTextField(
        value = value,
        onValueChange = {
            if (it.text.length <= 2 && it.text.all(Char::isDigit)) {
                field = it
                if (it.text != text) onText(it.text)
            }
        },
        modifier = Modifier.width(48.dp).heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description },
        textStyle = TextStyle(color = colors.foregroundColor, fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.accentColor),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).heightIn(min = 32.dp)
                    .background(colors.surfaceVariantColor, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    inner()
                }
            }
        }
    )
}

@Composable
private fun DueShortcut(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalUHabitComposeColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 48.dp).offset(y = (-6).dp).selectable(selected, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).then(
                if (selected) Modifier.background(colors.surfaceVariantColor, shape)
                else Modifier.border(1.dp, colors.borderColor, shape)
            ).padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            DueText(text, 14, color = if (selected) colors.foregroundColor else colors.secondaryColor, medium = selected, center = true)
        }
    }
}

@Composable
private fun DueIconAction(resource: Int, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.width(48.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        DueIcon(resource, 24)
    }
}

@Composable
private fun DueIcon(resource: Int, size: Int) {
    Icon(painterResource(resource), null, Modifier.size(size.dp), tint = LocalUHabitComposeColors.current.secondaryColor)
}

@Composable
private fun DueDivider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(LocalUHabitComposeColors.current.dividerColor))
}

@Composable
private fun DueText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = LocalUHabitComposeColors.current.foregroundColor,
    medium: Boolean = false,
    center: Boolean = false,
    hideSemantics: Boolean = false
) {
    Text(
        text,
        modifier.then(if (hideSemantics) Modifier.clearAndSetSemantics {} else Modifier),
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
            textAlign = if (center) TextAlign.Center else TextAlign.Start
        )
    )
}
