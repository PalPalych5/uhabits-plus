/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.UHabitColorPickerContent
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.components.UHabitDialogShell
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.compose.components.UHabitTextField
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.models.PaletteColor

internal data class ManageBlockItemUi(
    val id: Long?,
    val name: String,
    val color: PaletteColor,
    val resolvedColorArgb: Int,
    val habitCount: Int,
    val position: Int,
)

internal enum class BlockEditorMode {
    NONE,
    ADD,
    EDIT,
}

internal data class BlockEditorState(
    val mode: BlockEditorMode = BlockEditorMode.NONE,
    val editingBlockId: Long? = null,
    val draftName: String = "",
    val selectedColor: PaletteColor = PaletteColor(8),
    val showNameError: Boolean = false,
    val showColorPicker: Boolean = false,
)

internal fun canDeleteBlock(blockId: Long?): Boolean = (blockId ?: 0L) > 7L

internal fun validateBlockName(rawName: String): String? =
    rawName.trim().takeIf { it.isNotEmpty() }

internal fun computeNextBlockPosition(blocks: List<HabitBlock>): Int =
    (blocks.maxOfOrNull { it.position } ?: -1) + 1

internal fun buildManageBlockItems(
    blocks: List<HabitBlock>,
    allHabits: List<Habit>,
    resolveColor: (PaletteColor) -> Int,
): List<ManageBlockItemUi> = blocks.map { block ->
    ManageBlockItemUi(
        id = block.id,
        name = block.name,
        color = block.color,
        resolvedColorArgb = resolveColor(block.color),
        habitCount = allHabits.count { it.blockId == block.id },
        position = block.position,
    )
}

@Composable
internal fun ManageBlocksComposeScreen(
    colors: UHabitComposeColors,
    blocks: List<ManageBlockItemUi>,
    canEdit: Boolean,
    editorState: BlockEditorState,
    resolveColor: (PaletteColor) -> Int,
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onBlockClick: (ManageBlockItemUi) -> Unit,
    onNameChange: (String) -> Unit,
    onOpenColorPicker: () -> Unit,
    onDismissColorPicker: () -> Unit,
    onColorPicked: (PaletteColor) -> Unit,
    onSaveDialog: () -> Unit,
    onDeleteDialog: () -> Unit,
    onDismissDialog: () -> Unit,
) {
    UHabitComposeTheme(colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.backgroundColor),
        ) {
            ManageBlocksTopBar(
                colors = colors,
                onBack = onBack,
                onAddClick = onAddClick.takeIf { canEdit },
            )

            if (colors.isClassicSectionLayout) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.resolveDividerColor()),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (blocks.isEmpty()) {
                    ManageBlocksEmptyState(
                        colors = colors,
                        onAddClick = onAddClick.takeIf { canEdit },
                    )
                } else {
                    val outerHorizontal = if (colors.isClassicSectionLayout) 0.dp else 16.dp
                    val outerVertical = if (colors.isClassicSectionLayout) 0.dp else 16.dp

                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(
                            start = outerHorizontal,
                            end = outerHorizontal,
                            top = outerVertical,
                            bottom = outerVertical + 16.dp,
                        ),
                    ) {
                        item(key = "blocks_card") {
                            UHabitSectionCard(
                                colors = colors,
                                cornerRadiusDp = colors.habitsCardCornerRadiusDp,
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                blocks.forEachIndexed { index, item ->
                                    ManageBlockRow(
                                        item = item,
                                        colors = colors,
                                        onClick = if (canEdit) ({ onBlockClick(item) }) else null,
                                    )
                                    if (index < blocks.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 56.dp, end = 16.dp)
                                                .height(1.dp)
                                                .background(colors.resolveDividerColor()),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (canEdit && editorState.mode != BlockEditorMode.NONE) {
            BlockEditorDialog(
                colors = colors,
                editorState = editorState,
                resolveColor = resolveColor,
                onNameChange = onNameChange,
                onOpenColorPicker = onOpenColorPicker,
                onSave = onSaveDialog,
                onDelete = onDeleteDialog,
                onDismiss = onDismissDialog,
            )
        }

        if (canEdit && editorState.mode != BlockEditorMode.NONE && editorState.showColorPicker) {
            BlockColorPickerDialog(
                colors = colors,
                selectedColor = editorState.selectedColor,
                onApply = onColorPicked,
                onDismiss = onDismissColorPicker,
            )
        }
    }
}

@Composable
private fun ManageBlocksTopBar(
    colors: UHabitComposeColors,
    onBack: () -> Unit,
    onAddClick: (() -> Unit)?,
) {
    val backDesc = stringResource(R.string.settings_back)
    val addDesc = stringResource(R.string.add_block)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundColor)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backDesc },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_settings_back),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.foregroundColor),
                modifier = Modifier.size(24.dp),
            )
        }

        BasicText(
            text = stringResource(R.string.manage_blocks),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { heading() },
            style = TextStyle(
                color = colors.foregroundColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
        )

        if (onAddClick != null) Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAddClick)
                .semantics { contentDescription = addDesc },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_action_add_dark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.foregroundColor),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ManageBlocksEmptyState(
    colors: UHabitComposeColors,
    onAddClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = stringResource(R.string.manage_blocks_empty_state),
            style = TextStyle(
                color = colors.secondaryColor,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            ),
        )
        if (onAddClick == null) return@Column
        Spacer(Modifier.height(16.dp))
        val buttonShape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(buttonShape)
                .border(1.dp, colors.borderColor, buttonShape)
                .clickable(role = Role.Button, onClick = onAddClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = stringResource(R.string.add_block),
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun ManageBlockRow(
    item: ManageBlockItemUi,
    colors: UHabitComposeColors,
    onClick: (() -> Unit)?,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(item.resolvedColorArgb)),
        )

        Column(
            modifier = Modifier.weight(1f),
        ) {
            BasicText(
                text = item.name,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 16.sp,
                ),
            )
            if (item.habitCount > 0) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = stringResource(R.string.manage_blocks_habit_count, item.habitCount),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 13.sp,
                    ),
                )
            }
        }

        if (onClick != null) Image(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.secondaryColor),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .size(16.dp)
                .graphicsLayer { if (isRtl) scaleX = -1f },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockEditorDialog(
    colors: UHabitComposeColors,
    editorState: BlockEditorState,
    resolveColor: (PaletteColor) -> Int,
    onNameChange: (String) -> Unit,
    onOpenColorPicker: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val isEdit = editorState.mode == BlockEditorMode.EDIT
    val titleRes = if (isEdit) R.string.edit_block else R.string.add_block
    val showDelete = isEdit && canDeleteBlock(editorState.editingBlockId)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp)
                .widthIn(max = 480.dp),
        ) {
            UHabitDialogShell(
                colors = colors,
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    BasicText(
                        text = stringResource(titleRes),
                        modifier = Modifier.semantics { heading() },
                        style = TextStyle(
                            color = colors.foregroundColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )

                    Spacer(Modifier.height(14.dp))

                    UHabitTextField(
                        label = "",
                        value = editorState.draftName,
                        placeholder = stringResource(R.string.block_name),
                        onValueChange = onNameChange,
                        colors = colors,
                        singleLine = true,
                        errorMessage = if (editorState.showNameError) {
                            stringResource(R.string.block_name_validation)
                        } else {
                            null
                        },
                    )

                    Spacer(Modifier.height(14.dp))

                    val rowShape = RoundedCornerShape(8.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(rowShape)
                            .background(colors.backgroundColor)
                            .border(1.dp, colors.borderColor, rowShape)
                            .clickable(role = Role.Button, onClick = onOpenColorPicker)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = stringResource(R.string.color),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                color = colors.foregroundColor,
                                fontSize = 16.sp,
                            ),
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(resolveColor(editorState.selectedColor))),
                        )
                        Spacer(Modifier.width(8.dp))
                        Image(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colors.secondaryColor),
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { if (isRtl) scaleX = -1f },
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showDelete) {
                            BlockDialogActionTextButton(
                                text = stringResource(R.string.delete),
                                textColor = colors.dangerColor,
                                fontWeight = FontWeight.Medium,
                                onClick = onDelete,
                            )
                        }
                        BlockDialogActionTextButton(
                            text = stringResource(android.R.string.cancel),
                            textColor = colors.secondaryColor,
                            fontWeight = FontWeight.Medium,
                            onClick = onDismiss,
                        )
                        BlockDialogActionTextButton(
                            text = stringResource(R.string.save),
                            textColor = colors.accentColor,
                            fontWeight = FontWeight.SemiBold,
                            onClick = onSave,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockDialogActionTextButton(
    text: String,
    textColor: Color,
    fontWeight: FontWeight,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = textColor,
                fontSize = 14.sp,
                fontWeight = fontWeight,
            ),
        )
    }
}

@Composable
private fun BlockColorPickerDialog(
    colors: UHabitComposeColors,
    selectedColor: PaletteColor,
    onApply: (PaletteColor) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        UHabitComposeTheme(colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                ) {
                    UHabitColorPickerContent(
                        selectedColor = selectedColor,
                        onApply = onApply,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}
