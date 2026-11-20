/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.containers

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.ColorPickerBackHandler
import org.isoron.uhabits.activities.common.dialogs.UHabitColorPickerContent
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.activities.common.icons.UHabitIconPickerContent
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitElevationAndBorders
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.utils.currentTheme

enum class ContainerFormPresentation {
    /** Full layout for CreateContainerDialog with the parent row and 20 swatches. */
    FULL_WITH_PARENT_AND_SWATCHES,

    /** Compact layout for ContainersComposeScreen with dedicated action buttons */
    COMPACT_SPLIT_BUTTONS
}

private enum class ContainerSubScreen {
    FORM,
    COLOR_PICKER,
    ICON_PICKER
}

@Composable
fun UHabitContainerFormContent(
    initialName: String = "",
    initialParentId: ContainerId? = null,
    initialParentName: String? = null,
    initialColor: PaletteColor? = null,
    initialIcon: IconRef = IconRef.None,
    isEdit: Boolean = false,
    presentation: ContainerFormPresentation = ContainerFormPresentation.FULL_WITH_PARENT_AND_SWATCHES,
    onPickParent: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, parentId: ContainerId?, color: PaletteColor?, icon: IconRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var name by rememberSaveable { mutableStateOf(initialName) }
    var nameError by rememberSaveable { mutableStateOf(false) }

    var selectedColorIndex by rememberSaveable { mutableStateOf(initialColor?.paletteIndex ?: 10) }
    val selectedColor = remember(selectedColorIndex) { PaletteColor(selectedColorIndex) }

    var selectedIconStorage by rememberSaveable { mutableStateOf(IconRef.toStorageString(initialIcon)) }
    val selectedIcon = remember(selectedIconStorage) { IconRef.fromString(selectedIconStorage) }

    var currentScreen by rememberSaveable { mutableStateOf(ContainerSubScreen.FORM) }

    // Intercept back navigation when on sub-screen
    ColorPickerBackHandler(enabled = currentScreen != ContainerSubScreen.FORM) {
        currentScreen = ContainerSubScreen.FORM
    }

    // Color resolution
    val resolvedColorInt = remember(selectedColor) {
        try {
            android.graphics.Color.parseColor(selectedColor.toCsvColor())
        } catch (_: Throwable) {
            colors.accent
        }
    }
    val activeComposeColor = Color(resolvedColorInt)

    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val borderModifier = if (colors.isPureBlack) {
        Modifier.border(
            width = UHabitElevationAndBorders.hairlineBorderWidth,
            color = colors.dialogBorderColor,
            shape = sheetShape
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(sheetShape)
            .background(colors.dialogSurfaceColor)
            .then(borderModifier)
            .navigationBarsPadding()
            .imePadding()
    ) {
        when (currentScreen) {
            ContainerSubScreen.FORM -> {
                FormView(
                    name = name,
                    onNameChange = {
                        name = it
                        if (it.isNotBlank()) nameError = false
                    },
                    nameError = nameError,
                    selectedColor = selectedColor,
                    onColorSelect = { selectedColorIndex = it.paletteIndex },
                    activeComposeColor = activeComposeColor,
                    selectedIcon = selectedIcon,
                    parentName = initialParentName,
                    hasParent = initialParentId != null,
                    isEdit = isEdit,
                    presentation = presentation,
                    onOpenIconPicker = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        currentScreen = ContainerSubScreen.ICON_PICKER
                    },
                    onOpenColorPicker = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        currentScreen = ContainerSubScreen.COLOR_PICKER
                    },
                    onPickParent = onPickParent,
                    onDismiss = onDismiss,
                    onSave = {
                        val trimmed = name.trim()
                        if (trimmed.isBlank()) {
                            nameError = true
                        } else {
                            onSave(trimmed, initialParentId, selectedColor, selectedIcon)
                        }
                    }
                )
            }

            ContainerSubScreen.COLOR_PICKER -> {
                val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                ) {
                    UHabitColorPickerContent(
                        selectedColor = selectedColor,
                        onApply = { picked ->
                            selectedColorIndex = picked.paletteIndex
                            currentScreen = ContainerSubScreen.FORM
                        },
                        onDismiss = {
                            currentScreen = ContainerSubScreen.FORM
                        }
                    )
                }
            }

            ContainerSubScreen.ICON_PICKER -> {
                val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                ) {
                    UHabitIconPickerContent(
                        selectedIcon = selectedIcon,
                        tintColor = resolvedColorInt,
                        showHeader = true,
                        isBottomSheetPresentation = true,
                        showDefaultFolderBar = true,
                        onIconSelected = { picked ->
                            selectedIconStorage = IconRef.toStorageString(picked)
                            currentScreen = ContainerSubScreen.FORM
                        },
                        onDismiss = {
                            currentScreen = ContainerSubScreen.FORM
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormView(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    selectedColor: PaletteColor,
    onColorSelect: (PaletteColor) -> Unit,
    activeComposeColor: Color,
    selectedIcon: IconRef,
    parentName: String?,
    hasParent: Boolean,
    isEdit: Boolean,
    presentation: ContainerFormPresentation,
    onOpenIconPicker: () -> Unit,
    onOpenColorPicker: () -> Unit,
    onPickParent: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (name.isEmpty()) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Drag handle
        if (presentation == ContainerFormPresentation.FULL_WITH_PARENT_AND_SWATCHES) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.secondaryColor.copy(alpha = 0.35f))
                )
            }
        }

        // Title Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = when {
                isEdit -> stringResource(R.string.containers_edit_section)
                hasParent -> stringResource(R.string.containers_create_subsection)
                else -> stringResource(R.string.containers_create_section)
            }
            BasicText(
                text = title,
                style = TextStyle(
                    color = colors.foregroundColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            if (presentation == ContainerFormPresentation.COMPACT_SPLIT_BUTTONS) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .padding(4.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_x),
                        contentDescription = stringResource(R.string.containers_cancel),
                        colorFilter = ColorFilter.tint(colors.secondaryColor),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Layout: FULL (Icon next to name input) vs COMPACT_SPLIT_BUTTONS
        if (presentation == ContainerFormPresentation.FULL_WITH_PARENT_AND_SWATCHES) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Icon Tile (46x46dp)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(activeComposeColor.copy(alpha = 0.16f))
                        .border(1.5.dp, activeComposeColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable { onOpenIconPicker() }
                        .semantics { contentDescription = "Выбор значка" },
                    contentAlignment = Alignment.Center
                ) {
                    when (selectedIcon) {
                        is IconRef.Emoji -> {
                            BasicText(
                                text = selectedIcon.value,
                                style = TextStyle(fontSize = 20.sp)
                            )
                        }
                        is IconRef.Vector -> {
                            val res = IconPack.getDrawableRes(selectedIcon.key, IconPack.DEFAULT_CONTAINER_ICON_RES)
                            Image(
                                painter = painterResource(res),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(activeComposeColor),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconRef.None -> {
                            Image(
                                painter = painterResource(IconPack.DEFAULT_CONTAINER_ICON_RES),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(activeComposeColor),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Name input container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.foregroundColor.copy(alpha = 0.05f))
                        .border(1.dp, colors.foregroundColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = onNameChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = "Поле ввода названия" },
                        textStyle = TextStyle(
                            color = colors.foregroundColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(activeComposeColor),
                        decorationBox = { inner ->
                            Box {
                                if (name.isEmpty()) {
                                    BasicText(
                                        text = stringResource(R.string.containers_name),
                                        style = TextStyle(
                                            color = colors.secondaryColor.copy(alpha = 0.55f),
                                            fontSize = 15.sp
                                        )
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
            }

            if (nameError) {
                BasicText(
                    text = "Название не может быть пустым",
                    style = TextStyle(color = Color(0xFFE53935), fontSize = 12.sp),
                    modifier = Modifier.padding(start = 58.dp, bottom = 6.dp)
                )
            }

            // Location / Parent row
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.foregroundColor.copy(alpha = 0.04f))
                    .clickable(enabled = onPickParent != null) { onPickParent?.invoke() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Выбор родительского раздела" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_hierarchy_2),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.secondaryColor),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = stringResource(R.string.section_location_label),
                        style = TextStyle(
                            color = colors.secondaryColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        text = parentName ?: stringResource(R.string.containers_root_parent),
                        style = TextStyle(
                            color = colors.foregroundColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        softWrap = true,
                        maxLines = 3
                    )
                }
                if (onPickParent != null) {
                    Spacer(Modifier.width(6.dp))
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_chevron_right),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colors.secondaryColor),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Color Section Header
            BasicText(
                text = stringResource(R.string.section_color),
                style = TextStyle(
                    color = colors.secondaryColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Horizontal color strip: Custom button + 20 swatches
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Custom Color Button
                val isCustom = selectedColor.paletteIndex !in 0..19
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onOpenColorPicker() }
                        .semantics { contentDescription = "Пользовательский цвет" },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isCustom) activeComposeColor else colors.foregroundColor.copy(alpha = 0.08f))
                            .border(
                                width = if (isCustom) 2.dp else 1.dp,
                                color = if (isCustom) colors.foregroundColor else colors.foregroundColor.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tabler_palette),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(if (isCustom) Color.White else colors.secondaryColor),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 20 Palette Presets
                for (i in 0 until 20) {
                    val pColor = PaletteColor(i)
                    val swatchArgb = try {
                        android.graphics.Color.parseColor(pColor.toCsvColor())
                    } catch (_: Throwable) {
                        colors.accent
                    }
                    val isSelected = selectedColor.paletteIndex == i

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { onColorSelect(pColor) }
                            .semantics { contentDescription = "Цвет ${i + 1}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(swatchArgb))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, colors.foregroundColor, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        } else {
            // COMPACT_SPLIT_BUTTONS layout for ContainersComposeScreen
            BasicText(
                text = "Название раздела",
                style = TextStyle(color = colors.secondaryColor, fontSize = 12.sp)
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceVariantColor)
                    .border(1.dp, colors.dividerColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = "Поле ввода названия" },
                    textStyle = TextStyle(
                        color = colors.foregroundColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(activeComposeColor),
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                BasicText(
                                    text = "Например: Здоровье, Работа...",
                                    style = TextStyle(color = colors.secondaryColor, fontSize = 15.sp)
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            if (nameError) {
                BasicText(
                    text = "Название не может быть пустым",
                    style = TextStyle(color = Color(0xFFE53935), fontSize = 12.sp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Split action buttons: Color Picker button + Icon Picker button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Color Picker button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariantColor)
                        .border(1.dp, colors.dividerColor, RoundedCornerShape(8.dp))
                        .clickable { onOpenColorPicker() }
                        .padding(10.dp)
                        .semantics { contentDescription = "Выбор цвета" },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(activeComposeColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            text = "Выбрать цвет",
                            style = TextStyle(color = colors.foregroundColor, fontSize = 13.sp)
                        )
                    }
                }

                // Icon Picker button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariantColor)
                        .border(1.dp, colors.dividerColor, RoundedCornerShape(8.dp))
                        .clickable { onOpenIconPicker() }
                        .padding(10.dp)
                        .semantics { contentDescription = "Выбор значка" },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (selectedIcon) {
                            is IconRef.Emoji -> {
                                BasicText(selectedIcon.value, style = TextStyle(fontSize = 16.sp))
                            }
                            is IconRef.Vector -> {
                                val res = IconPack.getDrawableRes(selectedIcon.key, IconPack.DEFAULT_CONTAINER_ICON_RES)
                                Image(
                                    painter = painterResource(res),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colors.secondaryColor),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconRef.None -> {
                                Image(
                                    painter = painterResource(R.drawable.ic_tabler_folder),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colors.secondaryColor),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            text = if (selectedIcon is IconRef.None) "Значок" else "Выбран",
                            style = TextStyle(color = colors.foregroundColor, fontSize = 13.sp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
        }

        // Action Buttons: Cancel + Save/Create
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Отмена" },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.containers_cancel),
                    style = TextStyle(
                        color = colors.secondaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentColor)
                    .clickable { onSave() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .semantics { contentDescription = if (isEdit) "Сохранить" else "Создать" },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = if (isEdit) stringResource(R.string.containers_save) else stringResource(R.string.containers_create),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}
