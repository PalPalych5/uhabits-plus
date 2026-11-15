/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.list

import android.graphics.drawable.ColorDrawable
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalLayoutApi::class)
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
    var noteFocused by remember { mutableStateOf(false) }

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
        color = colors.dialogSurfaceColor,
        border = BorderStroke(1.dp, colors.dialogBorderColor),
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.skip_day_dialog_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.foregroundColor,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$formattedDate • $habitCountText",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.skip_day_dialog_impact),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                ),
                color = colors.secondaryColor.copy(alpha = 0.9f),
            )

            if (hasGrouping) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariantColor.copy(alpha = 0.65f))
                        .border(1.dp, colors.borderColor, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SkipScopeSegment(
                        label = stringResource(R.string.skip_day_all),
                        selected = allHabits,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { allHabits = true },
                    )
                    SkipScopeSegment(
                        label = stringResource(R.string.skip_day_by_sphere),
                        selected = !allHabits,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onClick = { allHabits = false },
                    )
                }

                if (!allHabits) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 176.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sphereOptions.forEach { opt ->
                                val checked = selectedSpheres.contains(opt.key)
                                SkipSphereChip(
                                    label = opt.name,
                                    checked = checked,
                                    colors = colors,
                                    onToggle = {
                                        selectedSpheres = if (checked) {
                                            selectedSpheres - opt.key
                                        } else {
                                            selectedSpheres + opt.key
                                        }
                                    },
                                )
                            }
                            if (hasSphereless) {
                                SkipSphereChip(
                                    label = stringResource(R.string.skip_day_other_sphere),
                                    checked = skipSphereless,
                                    colors = colors,
                                    onToggle = { skipSphereless = !skipSphereless },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariantColor.copy(alpha = 0.35f))
                    .border(
                        width = if (noteFocused) 1.5.dp else 1.dp,
                        color = if (noteFocused) colors.accentColor else colors.borderColor,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (noteText.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skip_day_note_hint),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = colors.secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        color = colors.foregroundColor,
                    ),
                    cursorBrush = SolidColor(colors.accentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { noteFocused = it.isFocused },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        color = colors.secondaryColor,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                val contrastWithWhite = androidx.core.graphics.ColorUtils.calculateContrast(
                    android.graphics.Color.WHITE,
                    colors.accentColor.toArgb()
                )
                val buttonTextColor = if (contrastWithWhite >= 4.5) Color.White else Color(0xFF191C20)
                Button(
                    onClick = {
                        onConfirm(allHabits, selectedSpheres, skipSphereless, noteText)
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentColor,
                        contentColor = buttonTextColor
                    )
                ) {
                    Text(
                        text = stringResource(R.string.skip_day_confirm),
                        color = buttonTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkipScopeSegment(
    label: String,
    selected: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            !selected -> Color.Transparent
            colors.isPureBlack -> colors.surfaceVariantColor
            else -> colors.dialogSurfaceColor
        },
        border = if (selected) BorderStroke(1.dp, colors.accentColor.copy(alpha = 0.5f)) else null,
        shadowElevation = 0.dp,
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) colors.foregroundColor else colors.secondaryColor,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SkipSphereChip(
    label: String,
    checked: Boolean,
    colors: UHabitComposeColors,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (checked) {
            colors.accentColor.copy(alpha = 0.16f)
        } else {
            colors.surfaceVariantColor.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = if (checked) 1.5.dp else 1.dp,
            color = if (checked) colors.accentColor else colors.borderColor,
        ),
        shadowElevation = 0.dp,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (checked) colors.foregroundColor else colors.secondaryColor,
                fontSize = 12.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
