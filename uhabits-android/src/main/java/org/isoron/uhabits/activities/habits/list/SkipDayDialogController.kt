/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list

import android.graphics.drawable.ColorDrawable
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.commands.BatchCreateRepetitionCommand
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import java.util.Locale

class SkipDayDialogController(
    private val fragment: Fragment,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    private val organizationFacade: HabitOrganizationFacade? = null
) {
    private val context get() = fragment.requireContext()

    fun show() {
        val today = getToday()
        val remainingHabits = habitList.filter { habit ->
            if (habit.isArchived) return@filter false
            val originalValue = habit.originalEntries.get(today).value
            if (originalValue != Entry.UNKNOWN) return@filter false

            val computedValue = habit.computedEntries.get(today).value
            computedValue != Entry.YES_MANUAL &&
                computedValue != Entry.YES_AUTO &&
                computedValue != Entry.SKIP
        }

        if (remainingHabits.isEmpty()) {
            CustomDialogs.showConfirmDialog(
                context = context,
                title = fragment.getString(R.string.skip_day_dialog_title),
                message = fragment.getString(R.string.skip_day_empty_message),
                isDestructive = false,
                positiveText = fragment.getString(android.R.string.ok)
            ) {}
            return
        }

        val isContainerLocal = organizationFacade?.mode == OrganizationAuthorityMode.CONTAINER_LOCAL
        val rootContainers = if (isContainerLocal) {
            organizationFacade!!.rootContainers().filter { root ->
                val habitUuidsInRoot = organizationFacade.getHabitUuidsForContainer(root.key, subtree = true)
                remainingHabits.any { h -> h.uuid != null && habitUuidsInRoot.contains(h.uuid) }
            }
        } else {
            emptyList()
        }
        val blocks = if (!isContainerLocal) {
            habitList.getBlocks().filter { block ->
                remainingHabits.any { it.blockId == block.id }
            }
        } else {
            emptyList()
        }
        val hasSphereless = if (isContainerLocal) {
            remainingHabits.any { h ->
                organizationFacade!!.getRootGroupKey(h.uuid ?: "", h.blockId) == null
            }
        } else {
            remainingHabits.any { it.blockId == null }
        }

        val sphereOptions = if (isContainerLocal) {
            rootContainers.map { root -> SphereOption(key = root.key, name = root.name) }
        } else {
            blocks.map { block -> SphereOption(key = block.id.toString(), name = block.name) }
        }

        val formatter = JavaLocalDateFormatter(Locale.getDefault())
        val formattedDate = formatter.longFormat(today)

        val dialog = MaterialAlertDialogBuilder(context, R.style.CustomTransparentDialogTheme).create()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val palette = MainTabsThemeBridge.resolve(context)
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    SkipDayDialogContent(
                        count = remainingHabits.size,
                        formattedDate = formattedDate,
                        sphereOptions = sphereOptions,
                        hasSphereless = hasSphereless,
                        colors = colors,
                        onCancel = { dialog.dismiss() },
                        onConfirm = { allHabitsSelected, selectedSphereKeys, skipSphereless, notes ->
                            val habitsToSkip = selectedHabits(
                                remainingHabits = remainingHabits,
                                radioAllChecked = allHabitsSelected,
                                checkedSphereKeys = selectedSphereKeys,
                                skipSphereless = skipSphereless,
                                isContainerLocal = isContainerLocal
                            )
                            if (habitsToSkip.isEmpty()) {
                                Toast.makeText(context, R.string.skip_day_empty_selection, Toast.LENGTH_SHORT).show()
                                return@SkipDayDialogContent
                            }
                            commandRunner.run(
                                BatchCreateRepetitionCommand(
                                    habitList = habitList,
                                    habits = habitsToSkip,
                                    date = today,
                                    value = Entry.SKIP,
                                    notes = notes
                                )
                            )
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
        dialog.show()
    }

    private fun selectedHabits(
        remainingHabits: List<Habit>,
        radioAllChecked: Boolean,
        checkedSphereKeys: Set<String>,
        skipSphereless: Boolean,
        isContainerLocal: Boolean
    ): List<Habit> {
        if (radioAllChecked) return remainingHabits
        if (isContainerLocal) {
            return remainingHabits.filter { habit ->
                val rootKey = organizationFacade!!.getRootGroupKey(habit.uuid ?: "", habit.blockId)?.toString()
                if (rootKey != null) checkedSphereKeys.contains(rootKey) else skipSphereless
            }
        } else {
            val checkedBlockIds = checkedSphereKeys.mapNotNull { it.toLongOrNull() }.toSet()
            return remainingHabits.filter { habit ->
                val blockId = habit.blockId
                if (blockId != null) checkedBlockIds.contains(blockId) else skipSphereless
            }
        }
    }
}

internal data class SphereOption(val key: String, val name: String)

@Composable
internal fun SkipDayDialogContent(
    count: Int,
    formattedDate: String,
    sphereOptions: List<SphereOption>,
    hasSphereless: Boolean,
    colors: UHabitComposeColors,
    onCancel: () -> Unit,
    onConfirm: (Boolean, Set<String>, Boolean, String) -> Unit
) {
    var allHabits by remember { mutableStateOf(true) }
    var selectedSpheres by remember { mutableStateOf(sphereOptions.map { it.key }.toSet()) }
    var skipSphereless by remember { mutableStateOf(true) }
    var noteText by remember { mutableStateOf("") }

    val hasGrouping = sphereOptions.isNotEmpty() || hasSphereless

    val habitCountText = if (Locale.getDefault().language == "ru") {
        val word = when {
            count % 10 == 1 && count % 100 != 11 -> "привычка"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "привычки"
            else -> "привычек"
        }
        "$count $word"
    } else {
        "$count " + if (count == 1) "habit" else "habits"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceColor,
        border = BorderStroke(1.dp, colors.borderColor),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.skip_day_dialog_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = colors.foregroundColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$formattedDate • $habitCountText",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = colors.secondaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.skip_day_dialog_impact),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.secondaryColor
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Radio 1: All habits
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { allHabits = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = allHabits,
                    onClick = { allHabits = true },
                    colors = RadioButtonDefaults.colors(selectedColor = colors.accentColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.skip_day_all),
                    color = colors.foregroundColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                )
            }

            // Radio 2: By sphere (if available)
            if (hasGrouping) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { allHabits = false }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !allHabits,
                        onClick = { allHabits = false },
                        colors = RadioButtonDefaults.colors(selectedColor = colors.accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.skip_day_by_sphere),
                        color = colors.foregroundColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                    )
                }

                // Checkbox list under "By sphere"
                if (!allHabits) {
                    Column(modifier = Modifier.padding(start = 32.dp, top = 2.dp, bottom = 4.dp)) {
                        sphereOptions.forEach { opt ->
                            val checked = selectedSpheres.contains(opt.key)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSpheres = if (checked) selectedSpheres - opt.key else selectedSpheres + opt.key
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedSpheres = if (isChecked) selectedSpheres + opt.key else selectedSpheres - opt.key
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = colors.accentColor, checkmarkColor = colors.surfaceColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(opt.name, color = colors.foregroundColor, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                            }
                        }
                        if (hasSphereless) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { skipSphereless = !skipSphereless }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = skipSphereless,
                                    onCheckedChange = { skipSphereless = it },
                                    colors = CheckboxDefaults.colors(checkedColor = colors.accentColor, checkmarkColor = colors.surfaceColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.skip_day_other_sphere), color = colors.foregroundColor, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.skip_day_note_hint),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = colors.secondaryColor
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentColor,
                    unfocusedBorderColor = colors.borderColor,
                    focusedTextColor = colors.foregroundColor,
                    unfocusedTextColor = colors.foregroundColor,
                    cursorColor = colors.accentColor
                )
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(android.R.string.cancel), color = colors.secondaryColor)
                }
                Spacer(modifier = Modifier.width(8.dp))
                val contrastWithWhite = androidx.core.graphics.ColorUtils.calculateContrast(
                    android.graphics.Color.WHITE,
                    colors.accentColor.toArgb()
                )
                val buttonTextColor = if (contrastWithWhite >= 4.5) Color.White else Color(0xFF191C20)
                Button(
                    onClick = {
                        onConfirm(allHabits, selectedSpheres, skipSphereless, noteText)
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentColor,
                        contentColor = buttonTextColor
                    )
                ) {
                    Text(stringResource(R.string.skip_day_confirm), color = buttonTextColor)
                }
            }
        }
    }
}
