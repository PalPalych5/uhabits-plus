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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.getWeekdaySequence
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.WeekdayList
import java.util.Locale

class WeekdayPickerDialog(
    var habitColor: Int? = null
) : AppCompatDialogFragment() {
    private var selectedDays: BooleanArray? = null
    private var listener: OnWeekdaysPickedListener? = null
    var onDismissCallback: () -> Unit = {}

    constructor() : this(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            selectedDays = savedInstanceState.getBooleanArray(KEY_SELECTED_DAYS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBooleanArray(KEY_SELECTED_DAYS, selectedDays)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = MaterialAlertDialogBuilder(requireActivity(), R.style.CustomTransparentDialogTheme).create()
        val prefs = (requireActivity().applicationContext as HabitsApplication).component.preferences
        val firstWeekday = prefs.firstWeekday
        val formatter = JavaLocalDateFormatter(Locale.getDefault())
        val orderedDays = getWeekdaySequence(firstWeekday)
        val weekdayItems = orderedDays.map { d ->
            WeekdayItem(
                arrayIndex = (d.daysSinceSunday + 1) % 7,
                shortName = formatter.shortWeekdayName(d).trimEnd('.'),
                longName = formatter.longWeekdayName(d)
            )
        }
        val initialSelected = selectedDays ?: BooleanArray(7)

        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    WeekdayPickerContent(
                        initialDays = initialSelected,
                        items = weekdayItems,
                        habitColor = habitColor,
                        colors = colors,
                        onCancel = { dialog.dismiss() },
                        onConfirm = { updated ->
                            selectedDays = updated
                            listener?.onWeekdaysSet(WeekdayList(updated))
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

    fun setListener(listener: OnWeekdaysPickedListener?) {
        this.listener = listener
    }

    fun setSelectedDays(days: WeekdayList) {
        selectedDays = days.toArray()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback()
    }

    fun interface OnWeekdaysPickedListener {
        fun onWeekdaysSet(days: WeekdayList)
    }

    companion object {
        private const val KEY_SELECTED_DAYS = "selectedDays"
    }
}

internal data class WeekdayItem(
    val arrayIndex: Int,
    val shortName: String,
    val longName: String
)

@Composable
internal fun WeekdayPickerContent(
    initialDays: BooleanArray,
    items: List<WeekdayItem>,
    habitColor: Int? = null,
    colors: UHabitComposeColors,
    onCancel: () -> Unit,
    onConfirm: (BooleanArray) -> Unit
) {
    var state by remember { mutableStateOf(initialDays.copyOf()) }
    val activeAccent = habitColor?.let { Color(it) } ?: colors.accentColor
    val activeAccentArgb = activeAccent.toArgb()
    val contrastWithWhite = androidx.core.graphics.ColorUtils.calculateContrast(
        android.graphics.Color.WHITE,
        activeAccentArgb
    )
    val buttonTextColor = if (contrastWithWhite >= 4.5) Color.White else Color(0xFF191C20)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceColor,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = stringResource(R.string.select_weekdays),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.foregroundColor
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Two-row compact grid of 7 day chips (4 on row 1, 3 on row 2)
            val row1 = items.take(4)
            val row2 = items.drop(4)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row1.forEach { item ->
                        WeekdayChip(
                            item = item,
                            isSelected = state.getOrElse(item.arrayIndex) { false },
                            accentColor = activeAccent,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onToggle = {
                                val updated = state.copyOf()
                                updated[item.arrayIndex] = !updated[item.arrayIndex]
                                state = updated
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row2.forEach { item ->
                        WeekdayChip(
                            item = item,
                            isSelected = state.getOrElse(item.arrayIndex) { false },
                            accentColor = activeAccent,
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onToggle = {
                                val updated = state.copyOf()
                                updated[item.arrayIndex] = !updated[item.arrayIndex]
                                state = updated
                            }
                        )
                    }
                    // Spacer for optical balance if row 2 has 3 items vs 4 in row 1
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
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
                            onClick = { onConfirm(state) }
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
private fun WeekdayChip(
    item: WeekdayItem,
    isSelected: Boolean,
    accentColor: Color,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val chipShape = RoundedCornerShape(10.dp)
    val background = if (isSelected) accentColor.copy(alpha = 0.08f) else colors.surfaceColor
    val borderStroke = if (isSelected) BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)) else BorderStroke(1.dp, colors.borderColor)
    val textColor = if (isSelected) colors.foregroundColor else colors.secondaryColor
    val fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal

    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(chipShape)
            .background(background)
            .border(borderStroke, chipShape)
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Checkbox
                selected = isSelected
                contentDescription = item.longName
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = item.shortName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = fontWeight,
                textAlign = TextAlign.Center
            ),
            color = textColor,
            maxLines = 1
        )
    }
}
