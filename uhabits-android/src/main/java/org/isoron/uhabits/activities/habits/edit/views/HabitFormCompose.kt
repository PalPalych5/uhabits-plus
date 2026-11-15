/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.edit.views

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.em
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.NumericalInputValidator
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.components.UHabitCardDivider
import org.isoron.uhabits.compose.components.UHabitCompactSwitch
import org.isoron.uhabits.compose.components.UHabitSectionCard
import org.isoron.uhabits.compose.components.UHabitSectionHeader
import org.isoron.uhabits.compose.components.UHabitTextField
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.MissingDataPolicy
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.utils.formatTime
import org.isoron.uhabits.utils.toFormattedString

/**
 * State holder for the Create / Edit Habit Compose form.
 */
class HabitFormState(
    initialHabitId: Long = -1L,
    initialHabitType: HabitType = HabitType.YES_NO,
    initialName: String = "",
    initialQuestion: String = "",
    initialNotes: String = "",
    initialUnit: String = "",
    initialTargetValue: Double = 1.0,
    initialTargetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
    initialMissingDataPolicy: MissingDataPolicy? = null,
    initialFreqNum: Int = 1,
    initialFreqDen: Int = 1,
    initialDayTier: DayTier = DayTier.NORMAL,
    initialDayTiersEnabled: Boolean = false,
    initialHabitSpheresEnabled: Boolean = false,
    initialColor: PaletteColor = PaletteColor(18),
    initialAndroidColor: Int = 0,
    initialIconRef: IconRef = IconRef.None,
    initialBlockId: Long? = null,
    initialContainerId: String? = null,
    initialTimerEnabled: Boolean = false,
    initialFocusSourceContainerId: String? = null,
    initialFocusIncludeDescendants: Boolean = true,
    initialReminderHour: Int = -1,
    initialReminderMin: Int = -1,
    initialReminderDays: WeekdayList = WeekdayList.EVERY_DAY,
    initialCreateArchived: Boolean = false,
    val isContainerLocal: Boolean = false,
    val focusCreditAvailable: Boolean = false,
    initialUnitTooltipDismissed: Boolean = false,
    val onDismissUnitTooltip: (() -> Unit)? = null
) {
    var habitId by mutableStateOf(initialHabitId)
    val isCreateMode: Boolean get() = habitId < 0
    var habitType by mutableStateOf(initialHabitType)

    var name by mutableStateOf(initialName)
    var nameErrorRes by mutableStateOf<Int?>(null)
    var nameError: String?
        get() = nameErrorRes?.let { "Error" }
        set(value) { nameErrorRes = if (value != null) R.string.validation_cannot_be_blank else null }

    var question by mutableStateOf(initialQuestion)
    var notes by mutableStateOf(initialNotes)

    var unit by mutableStateOf(initialUnit)
    companion object {
        fun formatTargetValue(value: Double): String {
            if (value <= 0.0) return "1"
            if (value % 1.0 == 0.0) {
                return value.toLong().toString()
            }
            val df = java.text.DecimalFormat("#.###", java.text.DecimalFormatSymbols.getInstance())
            df.isGroupingUsed = false
            return df.format(value)
        }
    }

    var targetValueString by mutableStateOf(formatTargetValue(initialTargetValue))
    var targetErrorRes by mutableStateOf<Int?>(null)
    var targetError: String?
        get() = targetErrorRes?.let { "Error" }
        set(value) { targetErrorRes = if (value != null) R.string.validation_number_out_of_range else null }
    var targetType by mutableStateOf(initialTargetType)
    var missingDataPolicy by mutableStateOf(initialMissingDataPolicy)

    var freqNum by mutableStateOf(initialFreqNum)
    var freqDen by mutableStateOf(initialFreqDen)
    var dayTier by mutableStateOf(initialDayTier)
    var dayTiersEnabled by mutableStateOf(initialDayTiersEnabled)
    var habitSpheresEnabled by mutableStateOf(initialHabitSpheresEnabled)

    var color by mutableStateOf(initialColor)
    var androidColor by mutableStateOf(initialAndroidColor)
    var iconRef by mutableStateOf(initialIconRef)

    var blockId by mutableStateOf(initialBlockId)
    var containerId by mutableStateOf(initialContainerId)
    var sectionDisplayName by mutableStateOf("")

    var timerEnabled by mutableStateOf(initialTimerEnabled)
    var focusSourceContainerId by mutableStateOf(initialFocusSourceContainerId)
    var focusSourceDisplayName by mutableStateOf("")
    var focusIncludeDescendants by mutableStateOf(initialFocusIncludeDescendants)

    var reminderHour by mutableStateOf(initialReminderHour)
    var reminderMin by mutableStateOf(initialReminderMin)
    var reminderDays by mutableStateOf(initialReminderDays)

    var createArchived by mutableStateOf(initialCreateArchived)
    var isSaving by mutableStateOf(false)

    var isUnitTooltipDismissed by mutableStateOf(initialUnitTooltipDismissed)
    val showUnitTooltip: Boolean
        get() = isCreateMode && habitType == HabitType.NUMERICAL && !isUnitTooltipDismissed

    fun dismissUnitTooltip() {
        if (!isUnitTooltipDismissed) {
            isUnitTooltipDismissed = true
            onDismissUnitTooltip?.invoke()
        }
    }

    val isMinuteUnit: Boolean get() = unit.isMinuteUnit()
    val isFocusCompatible: Boolean get() = habitType == HabitType.NUMERICAL && isMinuteUnit

    fun validate(): Boolean {
        var valid = true
        if (name.trim().isEmpty()) {
            nameErrorRes = R.string.validation_cannot_be_blank
            valid = false
        } else {
            nameErrorRes = null
        }
        if (habitType == HabitType.NUMERICAL) {
            when (NumericalInputValidator.validateTargetValue(targetValueString)) {
                is NumericalInputValidator.ValidationResult.Valid -> {
                    targetErrorRes = null
                }
                is NumericalInputValidator.ValidationResult.OutOfRange -> {
                    targetErrorRes = R.string.validation_number_out_of_range
                    valid = false
                }
                is NumericalInputValidator.ValidationResult.InvalidFormat -> {
                    targetErrorRes = if (targetValueString.trim().isEmpty()) {
                        R.string.validation_cannot_be_blank
                    } else {
                        R.string.validation_invalid_number_format
                    }
                    valid = false
                }
            }
        } else {
            targetErrorRes = null
        }
        return valid
    }

    fun hasChangesFrom(
        origName: String,
        origQuestion: String,
        origNotes: String,
        origUnit: String,
        origTarget: Double,
        origColor: PaletteColor,
        origFreqNum: Int,
        origFreqDen: Int,
        origMissingDataPolicy: MissingDataPolicy? = missingDataPolicy
    ): Boolean {
        if (name.trim() != origName.trim()) return true
        if (question.trim() != origQuestion.trim()) return true
        if (notes.trim() != origNotes.trim()) return true
        if (habitType == HabitType.NUMERICAL) {
            if (unit.trim() != origUnit.trim()) return true
            val parsed = when (val res = NumericalInputValidator.validateTargetValue(targetValueString)) {
                is NumericalInputValidator.ValidationResult.Valid -> res.value
                else -> 0.0
            }
            if (Math.abs(parsed - origTarget) > 0.0001) return true
            if (targetType == NumericalHabitType.AT_MOST) {
                val origEffective = origMissingDataPolicy ?: MissingDataPolicy.DEFAULT_ZERO
                val currEffective = missingDataPolicy ?: MissingDataPolicy.DEFAULT_ZERO
                if (origEffective != currEffective) return true
            }
        }
        if (color != origColor) return true
        if (freqNum != origFreqNum || freqDen != origFreqDen) return true
        return false
    }
}

/**
 * Callbacks for picker triggers and navigation.
 */
interface HabitFormActions {
    fun onSave()
    fun onCancel()
    fun onPickColor()
    fun onPickIcon()
    fun onPickSection()
    fun onPickFrequency()
    fun onPickTargetType()
    fun onPickMissingDataPolicy()
    fun onPickDayTier()
    fun onPickReminderTime()
    fun onPickReminderDays()
    fun onPickFocusSource()
}

// ============================================================================
// Main Screen Composable
// ============================================================================

@Composable
fun HabitFormScreen(
    state: HabitFormState,
    actions: HabitFormActions,
    modifier: Modifier = Modifier
) {
    val colors = LocalUHabitComposeColors.current
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    // Synchronize system status & navigation bar contrast
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !colors.isDark
                insetsController.isAppearanceLightNavigationBars = !colors.isDark
            }
        }
    }

    val screenBg = if (colors.isPureBlack) Color.Black else Color(colors.background)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // --- Minimalist Top Bar ---
        HabitFormTopBar(
            title = if (state.isCreateMode) stringResource(R.string.create_habit) else stringResource(R.string.edit_habit),
            habitAndroidColor = state.androidColor,
            isSaving = state.isSaving,
            canSave = !state.isSaving && state.name.trim().isNotEmpty(),
            colors = colors,
            onBack = actions::onCancel,
            onSave = {
                focusManager.clearFocus()
                if (state.validate()) {
                    actions.onSave()
                }
            }
        )

        // --- Scrollable Form Content (3 Consolidated Logical Tiers) ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = if (colors.isClassicSectionLayout) 0.dp else 16.dp,
                    vertical = if (colors.isClassicSectionLayout) 0.dp else 12.dp
                )
                .animateContentSize(tween(200)),
            verticalArrangement = Arrangement.spacedBy(if (colors.isClassicSectionLayout) 0.dp else 12.dp)
        ) {
            // Tier 1: Identity & Meaning (Live Avatar + Title + Question + Color/Icon chips)
            HabitIdentitySection(state = state, actions = actions, colors = colors)

            // Tier 2: Goal & Schedule (Type toggle in create, Target/Unit, Frequency, Reminder)
            HabitGoalScheduleSection(state = state, actions = actions, colors = colors)

            // Tier 3: Organization & Details (Sphere, Day Tier, Timer/Focus, Notes)
            HabitOrganizationDetailsSection(state = state, actions = actions, colors = colors)

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

// ============================================================================
// Top Bar
// ============================================================================

@Composable
private fun HabitFormTopBar(
    title: String,
    habitAndroidColor: Int,
    isSaving: Boolean,
    canSave: Boolean,
    colors: UHabitComposeColors,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    val topBarBg = if (colors.isPureBlack) Color.Black else Color(colors.background)
    val dividerColor = if (colors.isPureBlack) Color(0xFF1F1F1F) else Color(colors.divider).copy(alpha = 0.5f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(topBarBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button (>=48dp touch target)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onBack
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_chevron_left),
                    contentDescription = stringResource(android.R.string.cancel),
                    colorFilter = ColorFilter.tint(Color(colors.foreground)),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Screen title (adaptive wrapping without cutting off)
            BasicText(
                text = title,
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(colors.foreground),
                    fontFamily = FontFamily.Default
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Save Action Button - "Сохранить" only, clear enabled/disabled distinction
            val habitColor = Color(habitAndroidColor)
            val contrastWithWhite = androidx.core.graphics.ColorUtils.calculateContrast(
                android.graphics.Color.WHITE,
                habitAndroidColor
            )
            val saveBtnBg = if (canSave) habitColor else Color(colors.surfaceVariant).copy(alpha = 0.35f)
            val saveBtnText = if (canSave) {
                if (contrastWithWhite >= 4.5) Color.White else Color(0xFF191C20)
            } else {
                Color(colors.secondary).copy(alpha = 0.4f)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(saveBtnBg)
                    .clickable(
                        enabled = canSave,
                        role = Role.Button,
                        onClick = onSave
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = if (isSaving) "..." else stringResource(R.string.save),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = saveBtnText,
                        fontFamily = FontFamily.Default
                    )
                )
            }
        }

// Conspicuous toolbar divider removed for seamless surface transition
    }
}

// ============================================================================
// Form Card Container
// ============================================================================

@Composable
fun HabitFormCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    UHabitSectionCard(
        modifier = modifier,
        content = content
    )
}

@Composable
private fun SectionHeader(
    title: String,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier
) {
    UHabitSectionHeader(
        title = title,
        colors = colors,
        fontSize = 14.sp,
        modifier = modifier
    )
}

// ============================================================================
// Tier 1: Identity & Meaning (Coherent Composition)
// ============================================================================

@Composable
private fun HabitIdentitySection(
    state: HabitFormState,
    actions: HabitFormActions,
    colors: UHabitComposeColors
) {
    var isDescriptionExpanded by remember { mutableStateOf(state.notes.isNotBlank()) }

    HabitFormCard {
        // 1. Habit Name (Full-width, persistent restrained label)
        HabitTextInputField(
            label = stringResource(R.string.name),
            value = state.name,
            placeholder = if (state.habitType == HabitType.YES_NO) {
                stringResource(R.string.yes_or_no_short_example)
            } else {
                stringResource(R.string.measurable_short_example)
            },
            maxLength = 50,
            errorMessage = state.nameErrorRes?.let { stringResource(it) },
            colors = colors,
            onValueChange = {
                state.name = it
                if (it.isNotBlank()) state.nameErrorRes = null
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Habit Question (Full-width, persistent restrained label)
        HabitTextInputField(
            label = stringResource(R.string.question),
            value = state.question,
            placeholder = if (state.habitType == HabitType.YES_NO) {
                stringResource(R.string.example_question_boolean)
            } else {
                stringResource(R.string.measurable_question_example)
            },
            colors = colors,
            onValueChange = { state.question = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Compact Color & Icon Selectors (Responsive, non-rigid row)
        val isRu = java.util.Locale.getDefault().language == "ru"
        val hasIcon = state.iconRef !is IconRef.None
        val iconLabel = when (val ref = state.iconRef) {
            is IconRef.Vector -> IconPack.getIcon(ref.key)?.labelRu ?: ref.key
            is IconRef.Emoji -> ref.value
            IconRef.None -> if (isRu) "Значок: не выбран" else "Icon: none"
        }

        val fontScale = androidx.compose.ui.platform.LocalConfiguration.current.fontScale
        if (fontScale >= 1.6f) {
            // High font scale: stack into vertical rows so text never truncates
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UHabitFormSelector(
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(state.androidColor))
                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        )
                    },
                    label = stringResource(R.string.color),
                    colors = colors,
                    modifier = Modifier.wrapContentWidth(),
                    onClick = actions::onPickColor
                )

                UHabitFormSelector(
                    icon = if (hasIcon) {
                        {
                            when (val ref = state.iconRef) {
                                is IconRef.Vector -> {
                                    IconPack.getIcon(ref.key)?.let { icon ->
                                        Image(
                                            painter = painterResource(icon.drawableRes),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(Color(colors.foreground)),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                is IconRef.Emoji -> {
                                    BasicText(text = ref.value, style = TextStyle(fontSize = 14.sp))
                                }
                                IconRef.None -> {}
                            }
                        }
                    } else null,
                    label = iconLabel,
                    colors = colors,
                    modifier = Modifier.wrapContentWidth(),
                    onClick = actions::onPickIcon
                )
            }
        } else {
            // Standard layout: both selectors compact, content-sized, left-aligned
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UHabitFormSelector(
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(state.androidColor))
                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        )
                    },
                    label = stringResource(R.string.color),
                    colors = colors,
                    modifier = Modifier.wrapContentWidth(),
                    onClick = actions::onPickColor
                )

                UHabitFormSelector(
                    icon = if (hasIcon) {
                        {
                            when (val ref = state.iconRef) {
                                is IconRef.Vector -> {
                                    IconPack.getIcon(ref.key)?.let { icon ->
                                        Image(
                                            painter = painterResource(icon.drawableRes),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(Color(colors.foreground)),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                is IconRef.Emoji -> {
                                    BasicText(text = ref.value, style = TextStyle(fontSize = 14.sp))
                                }
                                IconRef.None -> {}
                            }
                        }
                    } else null,
                    label = iconLabel,
                    colors = colors,
                    modifier = Modifier.wrapContentWidth(),
                    onClick = actions::onPickIcon
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. Habit Description (Integrated in Identity; setting-style row when empty, full input when populated)
        val descriptionLabel = if (isRu) {
            "Описание"
        } else {
            "Description"
        }

        if (isDescriptionExpanded || state.notes.isNotBlank()) {
            HabitTextInputField(
                label = descriptionLabel,
                value = state.notes,
                placeholder = stringResource(R.string.habit_notes_placeholder),
                singleLine = false,
                minLines = 2,
                colors = colors,
                onValueChange = { state.notes = it }
            )
        } else {
            // Cohesive setting-style row: "Описание" on left, "Добавить >" on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { isDescriptionExpanded = true }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BasicText(
                    text = descriptionLabel,
                    style = TextStyle(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(colors.secondary),
                        fontFamily = FontFamily.Default
                    )
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BasicText(
                        text = if (isRu) "Добавить" else "Add",
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(colors.secondary),
                            fontFamily = FontFamily.Default
                        )
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.6f)),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Tier 2: Goal & Schedule
// ============================================================================

@Composable
private fun HabitGoalScheduleSection(
    state: HabitFormState,
    actions: HabitFormActions,
    colors: UHabitComposeColors
) {
    val context = LocalContext.current
    val habitColor = Color(state.androidColor)

    HabitFormCard {
        SectionHeader(
            title = stringResource(R.string.habit_form_section_target),
            colors = colors
        )

        // In Create mode: compact segmented pill toggle «Да / Нет» vs «Числовая»
        if (state.isCreateMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(colors.surfaceVariant).copy(alpha = 0.35f))
                    .padding(3.dp)
            ) {
                val isBinary = state.habitType == HabitType.YES_NO

                CompactSegmentedPill(
                    text = stringResource(R.string.habit_type_yes_no_title),
                    selected = isBinary,
                    colors = colors,
                    habitAndroidColor = state.androidColor,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        state.habitType = HabitType.YES_NO
                    }
                )

                CompactSegmentedPill(
                    text = stringResource(R.string.habit_type_numerical_title),
                    selected = !isBinary,
                    colors = colors,
                    habitAndroidColor = state.androidColor,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        state.habitType = HabitType.NUMERICAL
                    }
                )
            }
        }
        // In Edit mode: type is locked and self-evident from fields below.
        // Product Owner requirement #3: No redundant type badge in edit mode.

        // Numerical Goal Fields
        if (state.habitType == HabitType.NUMERICAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Target Value: weight 1.0f
                HabitTextInputField(
                    label = stringResource(R.string.target),
                    value = state.targetValueString,
                    placeholder = "1.0",
                    keyboardType = KeyboardType.Decimal,
                    errorMessage = state.targetErrorRes?.let { stringResource(it) },
                    colors = colors,
                    modifier = Modifier.weight(1.0f),
                    onValueChange = {
                        state.targetValueString = it
                        if (it.isNotBlank()) state.targetErrorRes = null
                    }
                )

                // Unit: weight 1.35f
                HabitTextInputField(
                    label = stringResource(R.string.unit),
                    value = state.unit,
                    placeholder = stringResource(R.string.habit_unit_placeholder),
                    colors = colors,
                    modifier = Modifier.weight(1.35f),
                    onValueChange = { state.unit = it }
                )
            }

            // One-time contextual tooltip for unit discoverability (only in Create mode for Numerical habits)
            if (state.showUnitTooltip) {
                UnitOnboardingTooltip(
                    colors = colors,
                    onDismiss = { state.dismissUnitTooltip() }
                )
            }

            // Target Direction selector: responsive layout preventing narrow wrapping
            val isAtLeast = state.targetType == NumericalHabitType.AT_LEAST
            val fontScale = LocalConfiguration.current.fontScale

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.target_type_label),
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(colors.secondary)
                    ),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                if (fontScale >= 1.5f) {
                    // Large font scale: stack options vertically to give each choice 100% width
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TargetDirectionOption(
                            text = stringResource(R.string.target_type_at_least),
                            selected = isAtLeast,
                            colors = colors,
                            habitAndroidColor = state.androidColor,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { state.targetType = NumericalHabitType.AT_LEAST }
                        )

                        TargetDirectionOption(
                            text = stringResource(R.string.target_type_at_most),
                            selected = !isAtLeast,
                            colors = colors,
                            habitAndroidColor = state.androidColor,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { state.targetType = NumericalHabitType.AT_MOST }
                        )
                    }
                } else {
                    // Standard & narrow widths: equal-split segmented container across full width
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(colors.surfaceVariant).copy(alpha = 0.35f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TargetDirectionSegment(
                            text = stringResource(R.string.target_type_at_least),
                            selected = isAtLeast,
                            colors = colors,
                            habitAndroidColor = state.androidColor,
                            modifier = Modifier.weight(1f),
                            onClick = { state.targetType = NumericalHabitType.AT_LEAST }
                        )

                        TargetDirectionSegment(
                            text = stringResource(R.string.target_type_at_most),
                            selected = !isAtLeast,
                            colors = colors,
                            habitAndroidColor = state.androidColor,
                            modifier = Modifier.weight(1f),
                            onClick = { state.targetType = NumericalHabitType.AT_MOST }
                        )
                    }
                }
            }

            SettingDivider(colors)
        }

        // Frequency row
        val frequencyLabel = if (state.habitType == HabitType.YES_NO) {
            org.isoron.uhabits.activities.habits.edit.formatFrequency(state.freqNum, state.freqDen, context.resources)
        } else {
            when (state.freqDen) {
                1 -> context.getString(R.string.every_day)
                7 -> context.getString(R.string.every_week)
                30 -> context.getString(R.string.every_month)
                else -> "${state.freqNum}/${state.freqDen}"
            }
        }

        HabitSettingRow(
            iconRes = R.drawable.ic_cat_calendar,
            title = stringResource(R.string.frequency),
            value = frequencyLabel,
            colors = colors,
            onClick = actions::onPickFrequency
        )

        if (state.habitType == HabitType.NUMERICAL && state.targetType == NumericalHabitType.AT_MOST) {
            SettingDivider(colors)
            val policyLabel = when (state.missingDataPolicy ?: MissingDataPolicy.DEFAULT_ZERO) {
                MissingDataPolicy.DEFAULT_ZERO -> stringResource(R.string.missing_entries_count_as_zero)
                MissingDataPolicy.UNKNOWN -> stringResource(R.string.missing_entries_unknown)
            }
            HabitSettingRow(
                iconRes = R.drawable.ic_cat_calendar_days,
                title = stringResource(R.string.missing_entries_policy_label),
                value = policyLabel,
                colors = colors,
                onClick = actions::onPickMissingDataPolicy
            )
        }

        SettingDivider(colors)

        // Reminder Time
        val reminderTimeText = if (state.reminderHour >= 0) {
            formatTime(context, state.reminderHour, state.reminderMin).orEmpty()
        } else {
            stringResource(R.string.reminder_off)
        }

        HabitSettingRow(
            iconRes = R.drawable.ic_cat_bell,
            title = stringResource(R.string.reminder),
            value = reminderTimeText,
            colors = colors,
            onClick = actions::onPickReminderTime
        )

        // Reminder Days (if reminder active)
        if (state.reminderHour >= 0) {
            SettingDivider(colors)

            HabitSettingRow(
                iconRes = R.drawable.ic_cat_clock,
                title = stringResource(R.string.reminder_days),
                value = state.reminderDays.toFormattedString(context),
                colors = colors,
                onClick = actions::onPickReminderDays
            )
        }
    }
}

// ============================================================================
// Tier 3: Organization & Details
// ============================================================================

@Composable
private fun HabitOrganizationDetailsSection(
    state: HabitFormState,
    actions: HabitFormActions,
    colors: UHabitComposeColors
) {
    HabitFormCard {
        // Contextually accurate heading: "Организация и фокус" / "Организация и таймер" / "Организация"
        val isRuLang = java.util.Locale.getDefault().language == "ru"
        val sectionTitle = if (state.isMinuteUnit && state.focusCreditAvailable) {
            stringResource(R.string.habit_form_section_organization)
        } else if (state.isMinuteUnit) {
            if (isRuLang) "Организация и время" else "Organization & Time"
        } else {
            stringResource(R.string.organization)
        }
        SectionHeader(
            title = sectionTitle,
            colors = colors
        )

        // Section / Container Row
        HabitSettingRow(
            iconRes = R.drawable.ic_cat_folder,
            title = stringResource(R.string.habit_block),
            value = state.sectionDisplayName.ifEmpty { stringResource(R.string.habit_block_unassigned) },
            colors = colors,
            onClick = actions::onPickSection
        )

        // Day Tier (if enabled)
        if (state.dayTiersEnabled) {
            SettingDivider(colors)

            val tierLabel = stringResource(
                when (state.dayTier) {
                    DayTier.MINIMUM -> R.string.day_tier_minimum
                    DayTier.NORMAL -> R.string.day_tier_normal
                    DayTier.IDEAL -> R.string.day_tier_ideal
                    DayTier.OPTIONAL -> R.string.day_tier_optional
                }
            )

            HabitSettingRow(
                iconRes = R.drawable.ic_cat_star,
                title = stringResource(R.string.day_tier),
                value = tierLabel,
                colors = colors,
                onClick = actions::onPickDayTier
            )
        }

        // Timer and Focus Configuration (only for minute units)
        if (state.isMinuteUnit) {
            SettingDivider(colors)

            HabitSwitchRow(
                iconRes = R.drawable.ic_cat_timer,
                title = stringResource(R.string.habit_timer),
                subtitle = stringResource(R.string.timer_enabled),
                checked = state.timerEnabled,
                colors = colors,
                onCheckedChange = { state.timerEnabled = it }
            )

            // Focus Credit Integration
            if (state.focusCreditAvailable) {
                SettingDivider(colors)

                HabitSettingRow(
                    iconRes = R.drawable.ic_cat_focus,
                    title = stringResource(R.string.focus_credit_settings),
                    value = state.focusSourceDisplayName.ifEmpty { stringResource(R.string.focus_credit_off) },
                    colors = colors,
                    onClick = actions::onPickFocusSource
                )

                if (state.focusSourceContainerId != null) {
                    SettingDivider(colors)

                    HabitSwitchRow(
                        iconRes = null,
                        title = stringResource(R.string.focus_credit_descendants),
                        subtitle = stringResource(R.string.focus_credit_descendants_summary),
                        checked = state.focusIncludeDescendants,
                        colors = colors,
                        onCheckedChange = { state.focusIncludeDescendants = it }
                    )
                }
            }
        }

// Description is now part of Identity area
    }
}

// ============================================================================
// Reusable UI Building Blocks
// ============================================================================

@Composable
private fun CompactSegmentedPill(
    text: String,
    selected: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    habitAndroidColor: Int = 0,
    onClick: () -> Unit
) {
    val activeBg = if (colors.isDark) {
        if (habitAndroidColor != 0) {
            Color(androidx.core.graphics.ColorUtils.blendARGB(colors.surfaceVariant, habitAndroidColor, 0.22f))
        } else {
            Color(0xFF323842)
        }
    } else {
        if (habitAndroidColor != 0) {
            Color(androidx.core.graphics.ColorUtils.blendARGB(android.graphics.Color.WHITE, habitAndroidColor, 0.10f))
        } else {
            Color.White
        }
    }
    val activeTextColor = if (colors.isDark) Color.White else Color(0xFF191C20)
    val bg = if (selected) activeBg else Color.Transparent
    val textColor = if (selected) activeTextColor else Color(colors.secondary)
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun TargetDirectionSegment(
    text: String,
    selected: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    habitAndroidColor: Int = 0,
    onClick: () -> Unit
) {
    val activeBg = if (colors.isDark) {
        if (habitAndroidColor != 0) {
            Color(androidx.core.graphics.ColorUtils.blendARGB(colors.surfaceVariant, habitAndroidColor, 0.22f))
        } else {
            Color(0xFF323842)
        }
    } else {
        if (habitAndroidColor != 0) {
            Color(androidx.core.graphics.ColorUtils.blendARGB(android.graphics.Color.WHITE, habitAndroidColor, 0.10f))
        } else {
            Color.White
        }
    }
    val activeTextColor = if (colors.isDark) Color.White else Color(0xFF191C20)
    val bg = if (selected) activeBg else Color.Transparent
    val textColor = if (selected) activeTextColor else Color(colors.secondary)
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
                textAlign = TextAlign.Center
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TargetDirectionOption(
    text: String,
    selected: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    habitAndroidColor: Int = 0,
    onClick: () -> Unit
) {
    val activeBg = if (colors.isDark) {
        if (habitAndroidColor != 0) {
            Color(androidx.core.graphics.ColorUtils.blendARGB(colors.surfaceVariant, habitAndroidColor, 0.22f))
        } else {
            Color(0xFF323842)
        }
    } else {
        if (habitAndroidColor != 0) {
            Color(androidx.core.graphics.ColorUtils.blendARGB(android.graphics.Color.WHITE, habitAndroidColor, 0.10f))
        } else {
            Color.White
        }
    }
    val activeTextColor = if (colors.isDark) Color.White else Color(0xFF191C20)
    val bg = if (selected) activeBg else Color(colors.surfaceVariant).copy(alpha = 0.25f)
    val textColor = if (selected) activeTextColor else Color(colors.foreground)
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun UHabitFormSelector(
    icon: (@Composable () -> Unit)?,
    label: String,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Soft recessed surface without harsh outline dominance
    val bg = Color(colors.surfaceVariant).copy(alpha = if (colors.isDark) 0.25f else 0.40f)
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
        }
        BasicText(
            text = label,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(colors.foreground),
                fontFamily = FontFamily.Default
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UHabitFormChip(
    icon: @Composable () -> Unit,
    text: String,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = Color(colors.surfaceVariant).copy(alpha = if (colors.isDark) 0.22f else 0.4f)
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(0.5.dp, Color(colors.border).copy(alpha = if (colors.isDark) 0.18f else 0.25f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color(colors.foreground)
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UnitOnboardingTooltip(
    colors: UHabitComposeColors,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (colors.isPureBlack) Color(0xFF161618)
                else Color(colors.surfaceVariant).copy(alpha = if (colors.isDark) 0.50f else 0.65f)
            )
            .border(
                1.dp,
                Color(colors.divider).copy(alpha = if (colors.isDark) 0.5f else 0.4f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        val accentColor = if (colors.isDark) Color(0xFF8AB4F8) else Color(colors.accent)
        val buttonBg = if (colors.isDark) Color(0xFF233044) else Color(colors.accent).copy(alpha = 0.12f)
        val buttonBorder = if (colors.isDark) Color(0xFF384A6E) else Color(colors.accent).copy(alpha = 0.25f)
        val buttonTextColor = if (colors.isDark) Color(0xFF93B9FF) else Color(colors.accent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_cat_timer),
                contentDescription = null,
                colorFilter = ColorFilter.tint(accentColor),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = stringResource(R.string.unit_tooltip_title),
                style = TextStyle(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(colors.foreground)
                ),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        BasicText(
            text = stringResource(R.string.unit_tooltip_body),
            style = TextStyle(
                fontSize = 12.5.sp,
                color = Color(colors.secondary),
                lineHeight = 1.35.em
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(buttonBg)
                    .border(1.dp, buttonBorder, RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.unit_tooltip_action),
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = buttonTextColor
                    )
                )
            }
        }
    }
}

@Composable
private fun HabitTextInputField(
    label: String,
    value: String,
    placeholder: String,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    maxLength: Int? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    errorMessage: String? = null,
    onValueChange: (String) -> Unit
) {
    val fieldShape = RoundedCornerShape(8.dp)
    val fieldBg = Color(colors.surfaceVariant).copy(alpha = if (colors.isDark) 0.25f else 0.40f)
    val borderModifier = if (errorMessage != null) {
        Modifier.border(1.dp, colors.dangerColor, fieldShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(fieldShape)
            .background(fieldBg)
            .then(borderModifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column {
            if (label.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = label,
                        style = TextStyle(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(colors.secondary).copy(alpha = 0.85f),
                            fontFamily = FontFamily.Default
                        )
                    )
                    if (maxLength != null && value.isNotEmpty()) {
                        BasicText(
                            text = "${value.length}/$maxLength",
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = Color(colors.secondary).copy(alpha = 0.6f)
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    BasicText(
                        text = placeholder,
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            color = Color(colors.secondary).copy(alpha = 0.45f),
                            fontFamily = FontFamily.Default
                        ),
                        maxLines = if (singleLine) 1 else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = Color(colors.foreground),
                        fontFamily = FontFamily.Default
                    ),
                    singleLine = singleLine,
                    minLines = minLines,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(colors.accent)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HabitSettingRow(
    @DrawableRes iconRes: Int,
    title: String,
    value: String,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(colors.surfaceVariant).copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary)),
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        BasicText(
            text = title,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(colors.foreground)
            ),
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            BasicText(
                text = value,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(colors.secondary)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary).copy(alpha = 0.6f)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun HabitSwitchRow(
    @DrawableRes iconRes: Int?,
    title: String,
    subtitle: String,
    checked: Boolean,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(colors.surfaceVariant).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(colors.foreground)
                )
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                BasicText(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 11.5.sp,
                        color = Color(colors.secondary)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        CustomCompactSwitch(
            checked = checked,
            colors = colors,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun CustomCompactSwitch(
    checked: Boolean,
    colors: UHabitComposeColors,
    onCheckedChange: (Boolean) -> Unit
) {
    UHabitCompactSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = colors
    )
}

@Composable
private fun SettingDivider(
    colors: UHabitComposeColors,
    startIndent: androidx.compose.ui.unit.Dp = 36.dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(colors.resolveDividerColor().copy(alpha = if (colors.isDark) 0.20f else 0.25f))
    )
}
