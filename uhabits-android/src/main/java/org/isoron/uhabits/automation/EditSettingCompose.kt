/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitActionChip
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList

internal fun mapAutomationSpinnerPositionToAction(isNumerical: Boolean, itemPosition: Int): Int {
    return if (isNumerical) {
        when (itemPosition) {
            0 -> ACTION_INCREMENT
            else -> ACTION_DECREMENT
        }
    } else {
        when (itemPosition) {
            0 -> ACTION_CHECK
            1 -> ACTION_UNCHECK
            else -> ACTION_TOGGLE
        }
    }
}

internal fun mapAutomationActionToSpinnerPosition(action: Int): Int {
    return when (action) {
        ACTION_CHECK -> 0
        ACTION_UNCHECK -> 1
        ACTION_TOGGLE -> 2
        ACTION_INCREMENT -> 0
        ACTION_DECREMENT -> 1
        else -> 0
    }
}

internal fun resolveInitialAutomationSelection(
    habits: List<Habit>,
    args: SettingUtils.Arguments?,
): Pair<Int, Int> {
    if (habits.isEmpty()) return 0 to 0
    val habitIndex = args?.habit?.let { target ->
        habits.indexOfFirst { it.id == target.id || it === target }.takeIf { it >= 0 }
    } ?: 0
    val actionIndex = args?.let { mapAutomationActionToSpinnerPosition(it.action) } ?: 0
    return habitIndex to actionIndex
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EditSettingComposeScreen(
    habitList: HabitList,
    args: SettingUtils.Arguments?,
    onSave: (habit: Habit, action: Int) -> Unit,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
) {
    val habits = remember(habitList) {
        List(habitList.size()) { index -> habitList.getByPosition(index) }
    }
    val initialSelection = remember(habits, args) {
        resolveInitialAutomationSelection(habits, args)
    }
    var selectedHabitIndex by rememberSaveable { mutableIntStateOf(initialSelection.first) }
    var selectedActionIndex by rememberSaveable { mutableIntStateOf(initialSelection.second) }

    val safeHabitIndex = if (habits.isEmpty()) 0 else selectedHabitIndex.coerceIn(0, habits.lastIndex)
    val selectedHabit = habits.getOrNull(safeHabitIndex)
    val isNumerical = selectedHabit?.isNumerical == true
    val actions = stringArrayResource(
        if (isNumerical) R.array.actions_numerical else R.array.actions_yes_no
    ).toList()
    val safeActionIndex = if (actions.isEmpty()) 0 else selectedActionIndex.coerceIn(0, actions.lastIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .semantics { testTagsAsResourceId = true }
            .testTag("automation_edit_root")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.resolveSectionSurfaceColor())
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.foregroundColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            UHabitActionChip(
                text = stringResource(R.string.save),
                isSelected = selectedHabit != null,
                onClick = {
                    val habit = selectedHabit ?: return@UHabitActionChip
                    val action = mapAutomationSpinnerPositionToAction(
                        isNumerical = habit.isNumerical,
                        itemPosition = safeActionIndex,
                    )
                    onSave(habit, action)
                },
                modifier = Modifier.testTag("buttonSave"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                UHabitSectionCard(
                    modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(),
                ) {
                    AutomationDropdownField(
                        label = stringResource(R.string.habit),
                        options = habits.map { it.name },
                        selectedIndex = safeHabitIndex,
                        tag = "habitSpinner",
                        colors = colors,
                        onSelect = { index ->
                            selectedHabitIndex = index
                            val nextNumerical = habits.getOrNull(index)?.isNumerical == true
                            if (nextNumerical != isNumerical) {
                                selectedActionIndex = 0
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    AutomationDropdownField(
                        label = stringResource(R.string.action),
                        options = actions,
                        selectedIndex = safeActionIndex,
                        tag = "actionSpinner",
                        colors = colors,
                        onSelect = { index -> selectedActionIndex = index },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationDropdownField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    tag: String,
    colors: UHabitComposeColors,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentText = options.getOrNull(selectedIndex).orEmpty()
    val shape = RoundedCornerShape(10.dp)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.accentColor,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(colors.resolveRowSurfaceColor(isSelected = false), shape)
                    .border(1.dp, colors.resolveRowBorderColor(isSelected = false), shape)
                    .testTag(tag)
                    .clickable(
                        enabled = options.isNotEmpty(),
                        role = Role.DropdownList,
                        onClick = { expanded = true },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = currentText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = colors.foregroundColor,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_tabler_chevron_down),
                    contentDescription = null,
                    tint = colors.secondaryColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = colors.dialogSurfaceColor,
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (index == selectedIndex) colors.accentColor else colors.foregroundColor,
                                fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}
