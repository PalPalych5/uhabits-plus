/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.isoron.uhabits.activities.habits.edit

import android.content.res.Resources
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.dialogs.FrequencyPickerDialog
import org.isoron.uhabits.activities.common.dialogs.NumericalInputValidator
import org.isoron.uhabits.activities.common.dialogs.UHabitColorPickerBottomSheet
import org.isoron.uhabits.activities.common.dialogs.WeekdayPickerDialog
import org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.edit.views.HabitFormActions
import org.isoron.uhabits.activities.habits.edit.views.HabitFormScreen
import org.isoron.uhabits.activities.habits.edit.views.HabitFormState
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.EditHabitGoalCommand
import org.isoron.uhabits.core.commands.GoalApplyScope
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.core.rules.HabitTimeValueConverter
import org.isoron.uhabits.databinding.ActivityEditHabitBinding
import org.isoron.uhabits.utils.dismissCurrentAndShow

fun formatFrequency(freqNum: Int, freqDen: Int, resources: Resources): String = when {
    freqNum == 1 && (freqDen == 30 || freqDen == 31) -> resources.getString(R.string.every_month)
    freqDen == 30 || freqDen == 31 -> resources.getString(R.string.x_times_per_month, freqNum)
    freqNum == 1 && freqDen == 1 -> resources.getString(R.string.every_day)
    freqNum == 1 && freqDen == 7 -> resources.getString(R.string.every_week)
    freqNum == 1 && freqDen > 1 -> resources.getString(R.string.every_x_days, freqDen)
    freqDen == 7 -> resources.getString(R.string.x_times_per_week, freqNum)
    else -> resources.getString(R.string.x_times_per_y_days, freqNum, freqDen)
}

class EditHabitActivity : AppCompatActivity(), HabitFormActions {

    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var binding: ActivityEditHabitBinding
    lateinit var formState: HabitFormState

    // Public properties preserved for activity contract and test compatibility
    var habitId: Long = -1L
        get() = if (::formState.isInitialized) formState.habitId else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.habitId = value
        }

    var habitType: HabitType = HabitType.YES_NO
        get() = if (::formState.isInitialized) formState.habitType else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.habitType = value
        }
    var unit: String = ""
        get() = if (::formState.isInitialized) formState.unit else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.unit = value
        }

    var color: PaletteColor = PaletteColor(18)
        get() = if (::formState.isInitialized) formState.color else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.color = value
        }

    var androidColor: Int = 0
    var freqNum: Int = 1
        get() = if (::formState.isInitialized) formState.freqNum else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.freqNum = value
        }

    var freqDen: Int = 1
        get() = if (::formState.isInitialized) formState.freqDen else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.freqDen = value
        }

    var reminderHour: Int = -1
        get() = if (::formState.isInitialized) formState.reminderHour else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.reminderHour = value
        }

    var reminderMin: Int = -1
        get() = if (::formState.isInitialized) formState.reminderMin else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.reminderMin = value
        }

    var reminderDays: WeekdayList = WeekdayList.EVERY_DAY
        get() = if (::formState.isInitialized) formState.reminderDays else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.reminderDays = value
        }

    var targetType: NumericalHabitType = NumericalHabitType.AT_LEAST
        get() = if (::formState.isInitialized) formState.targetType else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.targetType = value
        }

    var dayTier: DayTier = DayTier.NORMAL
        get() = if (::formState.isInitialized) formState.dayTier else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.dayTier = value
        }

    var timerEnabled: Boolean = false
        get() = if (::formState.isInitialized) formState.timerEnabled else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.timerEnabled = value
        }

    var blockId: Long? = 7L
        get() = if (::formState.isInitialized) formState.blockId else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.blockId = value
        }

    var containerId: String? = null
        get() = if (::formState.isInitialized) formState.containerId else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.containerId = value
        }

    var focusSourceContainerId: String? = null
        get() = if (::formState.isInitialized) formState.focusSourceContainerId else field
        set(value) {
            field = value
            if (::formState.isInitialized) {
                formState.focusSourceContainerId = value
                updateFocusSourceDisplayName()
            }
        }

    var focusIncludeDescendants: Boolean = true
        get() = if (::formState.isInitialized) formState.focusIncludeDescendants else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.focusIncludeDescendants = value
        }

    var iconRef: IconRef = IconRef.None
        get() = if (::formState.isInitialized) formState.iconRef else field
        set(value) {
            field = value
            if (::formState.isInitialized) formState.iconRef = value
        }

    private var hasIndividualColor = false
    private var createArchived = false

    // Snapshot of original habit values to detect unsaved changes
    private var origName = ""
    private var origQuestion = ""
    private var origNotes = ""
    private var origUnit = ""
    private var origTarget = 1.0
    private var origColor = PaletteColor(18)
    private var origFreqNum = 1
    private var origFreqDen = 1

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val component = (application as HabitsApplication).component
        val facade = component.organizationFacade
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        // Configure edge-to-edge system bars and synchronize status bar icon contrast
        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !palette.isDark
        insetsController.isAppearanceLightNavigationBars = !palette.isDark

        binding = ActivityEditHabitBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackOrCancel()
            }
        })

        var initName = ""
        var initQuestion = ""
        var initNotes = ""
        var initUnit = ""
        var initTargetValue = 1.0

        if (intent.hasExtra("habitId")) {
            habitId = intent.getLongExtra("habitId", -1)
            val habit = component.habitList.getById(habitId)!!
            habitType = habit.type
            color = habit.color
            freqNum = habit.frequency.numerator
            freqDen = habit.frequency.denominator
            targetType = habit.targetType
            dayTier = habit.dayTier
            timerEnabled = habit.timerEnabled
            blockId = habit.blockId
            if (facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
                val placement = facade.getPlacementInfo(habit.uuid ?: "", habit.blockId)
                containerId = placement.containerId?.value
                iconRef = facade.getHabitVisualIdentity(habit.uuid ?: "")?.iconRef ?: IconRef.None
            }
            hasIndividualColor = true
            component.focusCreditController?.configuration?.current(HabitRef(habit.uuid!!))?.let {
                focusSourceContainerId = it.sourceContainerId.value
                focusIncludeDescendants = it.includeDescendants
            }
            habit.reminder?.let {
                reminderHour = it.hour
                reminderMin = it.minute
                reminderDays = it.days
            }
            initName = habit.name
            initQuestion = habit.question
            initNotes = habit.description
            initUnit = habit.unit
            initTargetValue = habit.targetValue

            origName = habit.name
            origQuestion = habit.question
            origNotes = habit.description
            origUnit = habit.unit
            origTarget = habit.targetValue
            origColor = habit.color
            origFreqNum = freqNum
            origFreqDen = freqDen
        } else {
            habitType = HabitType.fromInt(intent.getIntExtra("habitType", HabitType.YES_NO.value))
            createArchived = intent.getBooleanExtra("createArchived", false)
            timerEnabled = habitType == HabitType.NUMERICAL
            if (facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
                blockId = null
                containerId = null
                iconRef = IconRef.None
            }
        }

        if (state != null) {
            habitId = state.getLong("habitId")
            habitType = HabitType.fromInt(state.getInt("habitType"))
            color = PaletteColor(state.getInt("paletteColor"))
            freqNum = state.getInt("freqNum")
            freqDen = state.getInt("freqDen")
            reminderHour = state.getInt("reminderHour")
            reminderMin = state.getInt("reminderMin")
            reminderDays = WeekdayList(state.getInt("reminderDays"))
            dayTier = DayTier.fromString(state.getString("dayTier", DayTier.NORMAL.name))
            timerEnabled = state.getBoolean("timerEnabled")
            val savedBlockId = state.getLong("blockId", -1L)
            blockId = if (savedBlockId == -1L) null else savedBlockId
            containerId = state.getString("containerId")
            focusSourceContainerId = state.getString("focusSourceContainerId")
            focusIncludeDescendants = state.getBoolean("focusIncludeDescendants", true)
            iconRef = IconRef.fromString(state.getString("iconRef"))
            hasIndividualColor = state.getBoolean("hasIndividualColor", habitId >= 0)
            createArchived = state.getBoolean("createArchived", false)
        } else if (habitId < 0) {
            color = defaultColorForCurrentBlock()
        }

        updateAndroidColor()

        // Build and initialize FormState
        formState = HabitFormState(
            initialHabitId = habitId,
            initialHabitType = habitType,
            initialName = initName,
            initialQuestion = initQuestion,
            initialNotes = initNotes,
            initialUnit = initUnit,
            initialTargetValue = initTargetValue,
            initialTargetType = targetType,
            initialFreqNum = freqNum,
            initialFreqDen = freqDen,
            initialDayTier = dayTier,
            initialDayTiersEnabled = component.preferences.isDayTiersEnabled,
            initialHabitSpheresEnabled = component.preferences.isHabitSpheresEnabled,
            initialColor = color,
            initialAndroidColor = androidColor,
            initialIconRef = iconRef,
            initialBlockId = blockId,
            initialContainerId = containerId,
            initialTimerEnabled = timerEnabled,
            initialFocusSourceContainerId = focusSourceContainerId,
            initialFocusIncludeDescendants = focusIncludeDescendants,
            initialReminderHour = reminderHour,
            initialReminderMin = reminderMin,
            initialReminderDays = reminderDays,
            initialCreateArchived = createArchived,
            isContainerLocal = facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL,
            focusCreditAvailable = component.focusCreditController != null,
            initialUnitTooltipDismissed = component.preferences.isNumericalUnitTooltipShown,
            onDismissUnitTooltip = {
                component.preferences.isNumericalUnitTooltipShown = true
            }
        )

        updateSectionDisplayName()
        updateFocusSourceDisplayName()

        // Set up Native Compose Screen
        binding.composeFormView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = MainTabsThemeBridge.resolve(this@EditHabitActivity)
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors) {
                    HabitFormScreen(
                        state = formState,
                        actions = this@EditHabitActivity
                    )
                }
            }
        }

        // Connect Compatibility Layer text watchers and listeners for instrumentation tests
        setupCompatLayer()
        updateFocusConfigurationVisibility()

        // Restore active color picker dialog if needed
        val configureColorPicker: (UHabitColorPickerBottomSheet) -> Unit = { picker ->
            picker.onColorPicked = { paletteColor ->
                this.color = paletteColor
                formState.color = paletteColor
                hasIndividualColor = HabitColorDefaults.isIndividual(
                    paletteColor,
                    blockId,
                    currentBlocks()
                )
                updateAndroidColor()
            }
        }
        (supportFragmentManager.findFragmentByTag("colorPicker") as? UHabitColorPickerBottomSheet)?.let(configureColorPicker)
        if (state?.getBoolean("colorPickerOpen") == true) {
            (supportFragmentManager.findFragmentByTag("colorPicker") as? UHabitColorPickerBottomSheet)?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
            }
            val restoredPicker = UHabitColorPickerBottomSheet.newInstance(color)
            configureColorPicker(restoredPicker)
            restoredPicker.show(supportFragmentManager, "colorPicker")
        }

        // Dismiss leftover dialog fragments on recreation
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is DialogFragment && fragment !is UHabitColorPickerBottomSheet) {
                fragment.dismiss()
            }
        }
    }

    private fun setupCompatLayer() {
        binding.buttonSave.setOnClickListener {
            if (formState.validate()) save()
        }
        binding.unitInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                formState.unit = text
                updateTimerVisibility()
                updateFocusConfigurationVisibility()
            }
        })
        binding.nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                formState.name = s?.toString().orEmpty()
            }
        })
        binding.targetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                formState.targetValueString = s?.toString().orEmpty()
            }
        })
        binding.colorButton.setOnClickListener { onPickColor() }
        binding.booleanFrequencyPicker.setOnClickListener { onPickFrequency() }
        binding.reminderTimePicker.setOnClickListener { onPickReminderTime() }
        binding.reminderDatePicker.setOnClickListener { onPickReminderDays() }
        binding.focusDescendantsSwitch.setOnCheckedChangeListener { _, isChecked ->
            focusIncludeDescendants = isChecked
            formState.focusIncludeDescendants = isChecked
        }
    }

    private fun updateAndroidColor() {
        androidColor = themeSwitcher.currentTheme.color(color).toInt()
        if (::formState.isInitialized) {
            formState.androidColor = androidColor
        }
    }

    private fun updateSectionDisplayName() {
        val component = (application as HabitsApplication).component
        val facade = component.organizationFacade
        val name = if (facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            if (containerId != null) {
                val current = facade.availableContainers().firstOrNull { it.key == containerId }
                current?.path ?: current?.name ?: getString(R.string.habit_block_unassigned)
            } else {
                getString(R.string.habit_block_unassigned)
            }
        } else {
            val blocks = component.habitList.getBlocks()
            val currentBlock = blocks.firstOrNull { it.id == blockId }
            currentBlock?.name ?: getString(R.string.habit_block_unassigned)
        }
        formState.sectionDisplayName = name
    }

    private fun updateFocusSourceDisplayName() {
        val facade = (application as HabitsApplication).component.organizationFacade
        val source = facade.availableContainers().firstOrNull { it.key == focusSourceContainerId }
        val name = when {
            focusSourceContainerId == null -> getString(R.string.focus_credit_off)
            source != null -> source.path ?: source.name
            else -> getString(R.string.focus_credit_source_missing)
        }
        formState.focusSourceDisplayName = name
    }

    private fun updateTimerVisibility() {
        val isMinute = formState.unit.isMinuteUnit()
        binding.timerEnabledOuterBox.visibility = if (isMinute) View.VISIBLE else View.GONE
    }

    private fun updateFocusConfigurationVisibility() {
        val experimental = (application as HabitsApplication).component.focusCreditController != null
        val visible = experimental && isFocusCompatible()
        binding.focusCreditOuterBox.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun isFocusCompatible(): Boolean = runCatching {
        HabitTimeValueConverter.validateTargetHabit(
            formState.habitType == HabitType.NUMERICAL, formState.unit
        )
    }.isSuccess

    // ========================================================================
    // HabitFormActions Implementation
    // ========================================================================

    override fun onSave() {
        if (formState.validate()) {
            save()
        }
    }

    override fun onCancel() {
        handleBackOrCancel()
    }

    override fun onPickColor() {
        val picker = UHabitColorPickerBottomSheet.newInstance(color)
        picker.onColorPicked = { paletteColor ->
            this.color = paletteColor
            formState.color = paletteColor
            hasIndividualColor = HabitColorDefaults.isIndividual(
                paletteColor,
                blockId,
                currentBlocks()
            )
            updateAndroidColor()
        }
        picker.show(supportFragmentManager, "colorPicker")
    }

    override fun onPickIcon() {
        val picker = IconPickerBottomSheet().apply {
            previewColor = androidColor
            initialIconRef = iconRef
            onIconSelected = { picked ->
                iconRef = picked
                formState.iconRef = picked
            }
        }
        picker.show(supportFragmentManager, "habit_icon_picker")
    }

    override fun onPickSection() {
        val component = (application as HabitsApplication).component
        val facade = component.organizationFacade
        if (facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            val containers = facade.availableContainers()
            val items = mutableListOf<String>()
            items.add(getString(R.string.habit_block_unassigned))
            items.addAll(containers.map { it.path ?: it.name })

            val currentIdx = if (containerId == null) 0 else {
                val idx = containers.indexOfFirst { it.key == containerId }
                if (idx >= 0) idx + 1 else 0
            }

            CustomDialogs.showSingleChoiceDialog(
                context = this,
                title = getString(R.string.habit_block),
                options = items,
                selectedIndex = currentIdx
            ) { which ->
                containerId = if (which == 0) null else containers[which - 1].key
                formState.containerId = containerId
                updateSectionDisplayName()
                updateAndroidColor()
            }
        } else {
            val blocks = component.habitList.getBlocks()
            val items = blocks.map { it.name }

            CustomDialogs.showSingleChoiceDialog(
                context = this,
                title = getString(R.string.habit_block),
                options = items,
                selectedIndex = blocks.indexOfFirst { it.id == blockId },
                neutralText = getString(R.string.manage_blocks),
                onNeutral = {
                    startActivity(android.content.Intent(this, org.isoron.uhabits.activities.blocks.ManageBlocksActivity::class.java))
                }
            ) { which ->
                val selectedBlock = blocks[which]
                color = HabitColorDefaults.afterBlockChange(
                    currentColor = color,
                    newBlockId = selectedBlock.id,
                    hasIndividualColor = hasIndividualColor,
                    blocks = blocks
                )
                blockId = selectedBlock.id
                formState.color = color
                formState.blockId = blockId
                updateSectionDisplayName()
                updateAndroidColor()
            }
        }
    }

    override fun onPickFrequency() {
        if (formState.habitType == HabitType.YES_NO) {
            val picker = FrequencyPickerDialog(formState.freqNum, formState.freqDen)
            picker.onFrequencyPicked = { num, den ->
                freqNum = num
                freqDen = den
                formState.freqNum = num
                formState.freqDen = den
            }
            picker.dismissCurrentAndShow(supportFragmentManager, "frequencyPicker")
        } else {
            val options = listOf(
                getString(R.string.every_day),
                getString(R.string.every_week),
                getString(R.string.every_month)
            )
            val currentIdx = when (formState.freqDen) {
                7 -> 1
                30 -> 2
                else -> 0
            }
            CustomDialogs.showSingleChoiceDialog(
                context = this,
                title = getString(R.string.frequency),
                options = options,
                selectedIndex = currentIdx
            ) { which ->
                val den = when (which) {
                    1 -> 7
                    2 -> 30
                    else -> 1
                }
                freqDen = den
                formState.freqDen = den
            }
        }
    }

    override fun onPickTargetType() {
        val options = listOf(
            getString(R.string.target_type_at_least),
            getString(R.string.target_type_at_most)
        )
        CustomDialogs.showSingleChoiceDialog(
            context = this,
            title = getString(R.string.target_type),
            options = options,
            selectedIndex = if (targetType == NumericalHabitType.AT_LEAST) 0 else 1
        ) { which ->
            targetType = if (which == 0) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
            formState.targetType = targetType
        }
    }

    override fun onPickDayTier() {
        val tiers = DayTier.entries
        val labels = tiers.map { getString(it.labelResId) }
        CustomDialogs.showSingleChoiceDialog(
            context = this,
            title = getString(R.string.day_tier),
            options = labels,
            selectedIndex = tiers.indexOf(dayTier)
        ) { which ->
            dayTier = tiers[which]
            formState.dayTier = dayTier
        }
    }

    private val DayTier.labelResId: Int
        get() = when (this) {
            DayTier.MINIMUM -> R.string.day_tier_minimum
            DayTier.NORMAL -> R.string.day_tier_normal
            DayTier.IDEAL -> R.string.day_tier_ideal
            DayTier.OPTIONAL -> R.string.day_tier_optional
        }

    override fun onPickReminderTime() {
        val currentHour = if (reminderHour >= 0) reminderHour else 8
        val currentMin = if (reminderMin >= 0) reminderMin else 0
        val is24HourMode = DateFormat.is24HourFormat(this)
        CustomDialogs.showTimePickerDialog(
            context = this,
            title = getString(R.string.reminder),
            initialHour = currentHour,
            initialMinute = currentMin,
            is24Hour = is24HourMode,
            neutralText = getString(R.string.clear),
            onNeutral = {
                reminderHour = -1
                reminderMin = -1
                reminderDays = WeekdayList.EVERY_DAY
                formState.reminderHour = -1
                formState.reminderMin = -1
                formState.reminderDays = WeekdayList.EVERY_DAY
            },
            onConfirm = { hourOfDay, minute ->
                reminderHour = hourOfDay
                reminderMin = minute
                formState.reminderHour = hourOfDay
                formState.reminderMin = minute
            }
        )
    }

    override fun onPickReminderDays() {
        val dialog = WeekdayPickerDialog()
        dialog.setListener { days: WeekdayList ->
            val updated = if (days.isEmpty) WeekdayList.EVERY_DAY else days
            reminderDays = updated
            formState.reminderDays = updated
        }
        dialog.setSelectedDays(reminderDays)
        dialog.dismissCurrentAndShow(supportFragmentManager, "dayPicker")
    }

    override fun onPickFocusSource() {
        val facade = (application as HabitsApplication).component.organizationFacade
        val containers = facade.availableContainers()
        val options = listOf(getString(R.string.focus_credit_off)) + containers.map { it.path ?: it.name }
        val currentIdx = containers.indexOfFirst { it.key == focusSourceContainerId } + 1

        CustomDialogs.showSingleChoiceDialog(
            context = this,
            title = getString(R.string.focus_credit_source),
            options = options,
            selectedIndex = currentIdx
        ) { which ->
            focusSourceContainerId = if (which == 0) null else containers[which - 1].key
            formState.focusSourceContainerId = focusSourceContainerId
            updateFocusSourceDisplayName()
        }
    }

    // ========================================================================
    // Save Logic
    // ========================================================================

    private fun save() {
        val component = (application as HabitsApplication).component
        val habit = component.modelFactory.buildHabit()

        var original: Habit? = null
        if (habitId >= 0) {
            original = component.habitList.getById(habitId)!!
            habit.copyFrom(original)
        }

        habit.name = formState.name.trim()
        habit.question = formState.question.trim()
        habit.description = formState.notes.trim()
        habit.color = formState.color
        if (formState.reminderHour >= 0) {
            habit.reminder = Reminder(formState.reminderHour, formState.reminderMin, formState.reminderDays)
        } else {
            habit.reminder = null
        }

        habit.frequency = Frequency(formState.freqNum, formState.freqDen)
        habit.dayTier = formState.dayTier
        val facade = component.organizationFacade
        if (facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL) {
            habit.blockId = null
        } else {
            habit.blockId = formState.blockId
        }

        if (formState.habitType == HabitType.NUMERICAL) {
            val parsedTarget = when (val res = NumericalInputValidator.validateTargetValue(formState.targetValueString)) {
                is NumericalInputValidator.ValidationResult.Valid -> res.value
                else -> 1.0
            }
            habit.targetValue = parsedTarget
            habit.targetType = formState.targetType
            habit.unit = formState.unit.trim()
            habit.timerEnabled = formState.timerEnabled && habit.unit.isMinuteUnit()
        } else {
            habit.timerEnabled = false
        }
        habit.type = formState.habitType
        if (habitId < 0 && formState.createArchived) {
            habit.isArchived = true
        }

        if (habitId >= 0 && original != null && didGoalBundleChange(original, habit)) {
            showGoalChangeDialog(original = original, modified = habit)
            return
        }

        val isContainerLocal = facade.mode == OrganizationAuthorityMode.CONTAINER_LOCAL
        val targetContainer = formState.containerId?.let { ContainerId(it) }
        val origContainerId = if (habitId >= 0 && original != null && isContainerLocal) {
            facade.getPlacementInfo(original.uuid ?: "", original.blockId).containerId?.value
        } else null
        val containerChanged = isContainerLocal && (formState.containerId != origContainerId)

        runSaveCommand(
            if (habitId >= 0) {
                EditHabitCommand(
                    habitList = component.habitList,
                    habitId = habitId,
                    modified = habit,
                    targetContainerId = targetContainer,
                    changeContainer = containerChanged,
                    organizationFacade = facade,
                    visualIdentity = formState.iconRef
                )
            } else {
                CreateHabitCommand(
                    modelFactory = component.modelFactory,
                    habitList = component.habitList,
                    model = habit,
                    initialContainerId = targetContainer,
                    organizationFacade = facade,
                    initialVisualIdentity = formState.iconRef
                )
            }
        )
    }

    private fun didGoalBundleChange(original: Habit, modified: Habit): Boolean {
        return original.frequency != modified.frequency ||
            original.targetType != modified.targetType ||
            original.targetValue != modified.targetValue ||
            original.unit != modified.unit
    }

    private fun showGoalChangeDialog(original: Habit, modified: Habit) {
        val options = listOf(
            getString(R.string.apply_from_today),
            getString(R.string.apply_from_selected_date),
            getString(R.string.apply_to_entire_history)
        )
        CustomDialogs.showSingleChoiceDialog(
            context = this,
            title = getString(R.string.goal_change_scope_title),
            options = options,
            selectedIndex = -1
        ) { which ->
            when (which) {
                0 -> runSaveCommand(
                    EditHabitGoalCommand(
                        (application as HabitsApplication).component.habitList,
                        habitId,
                        modified,
                        GoalApplyScope.FROM_DATE,
                        getToday()
                    )
                )
                1 -> showGoalDatePicker { date ->
                    date ?: return@showGoalDatePicker
                    runSaveCommand(
                        EditHabitGoalCommand(
                            (application as HabitsApplication).component.habitList,
                            habitId,
                            modified,
                            GoalApplyScope.FROM_DATE,
                            date
                        )
                    )
                }
                else -> runSaveCommand(
                    EditHabitGoalCommand(
                        (application as HabitsApplication).component.habitList,
                        habitId,
                        modified,
                        GoalApplyScope.ENTIRE_HISTORY,
                        original.normalizedGoalHistory().first().effectiveDate
                    )
                )
            }
        }
    }

    private fun showGoalDatePicker(callback: (LocalDate?) -> Unit) {
        val today = getToday()
        CustomDialogs.showDatePickerDialog(
            context = this,
            title = getString(R.string.select_date),
            initialDate = today
        ) { date ->
            callback(date)
        }
    }

    private fun runSaveCommand(command: org.isoron.uhabits.core.commands.Command) {
        val component = (application as HabitsApplication).component
        val controller = component.focusCreditController
        formState.isSaving = true
        binding.buttonSave.isEnabled = false

        if (controller == null) {
            component.commandRunner.run(command)
            finish()
            return
        }

        val selectedSource = if (isFocusCompatible()) formState.focusSourceContainerId?.let(::ContainerId) else null
        val selectedDescendants = formState.focusIncludeDescendants

        component.commandRunner.addListener(object : CommandRunner.Listener {
            override fun onCommandFinished(completed: org.isoron.uhabits.core.commands.Command) {
                if (completed !== command) return
                component.commandRunner.removeListener(this)
                if ((application as HabitsApplication).component !== component) {
                    finish()
                    return
                }
                val habit = when (command) {
                    is CreateHabitCommand -> component.habitList.getByUUID(command.model.uuid)
                    else -> component.habitList.getById(habitId)
                }
                if (habit != null && command is CreateHabitCommand) {
                    habitId = requireNotNull(habit.id)
                }
                try {
                    if (habit != null) {
                        controller.configure(habit, selectedSource, selectedDescendants)
                    }
                    finish()
                } catch (error: Exception) {
                    if (isFinishing || isDestroyed) return
                    formState.isSaving = false
                    binding.buttonSave.isEnabled = true
                    AlertDialog.Builder(this@EditHabitActivity)
                        .setMessage(R.string.focus_credit_save_failed)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        })
        component.commandRunner.run(command)
    }

    private fun handleBackOrCancel() {
        if (::formState.isInitialized && formState.showUnitTooltip) {
            formState.dismissUnitTooltip()
            return
        }
        if (::formState.isInitialized && !formState.isUnitTooltipDismissed) {
            formState.dismissUnitTooltip()
        }
        if (habitId >= 0 && formState.hasChangesFrom(origName, origQuestion, origNotes, origUnit, origTarget, origColor, origFreqNum, origFreqDen)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.discard_changes)
                .setMessage(R.string.discard_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> finish() }
                .setNegativeButton(R.string.keep_editing, null)
                .show()
        } else if (habitId < 0 && (formState.name.isNotBlank() || formState.question.isNotBlank() || formState.notes.isNotBlank())) {
            AlertDialog.Builder(this)
                .setTitle(R.string.discard_changes)
                .setMessage(R.string.discard_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> finish() }
                .setNegativeButton(R.string.keep_editing, null)
                .show()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackOrCancel()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleBackOrCancel()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSectionDisplayName()
        updateFocusSourceDisplayName()
        updateFocusConfigurationVisibility()
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        val colorPicker = supportFragmentManager.findFragmentByTag("colorPicker") as? UHabitColorPickerBottomSheet
        with(state) {
            putLong("habitId", habitId)
            putInt("habitType", formState.habitType.value)
            putInt("paletteColor", color.paletteIndex)
            putInt("androidColor", androidColor)
            putInt("freqNum", freqNum)
            putInt("freqDen", freqDen)
            putInt("reminderHour", reminderHour)
            putInt("reminderMin", reminderMin)
            putInt("reminderDays", reminderDays.toInteger())
            putString("dayTier", dayTier.name)
            putBoolean("timerEnabled", formState.timerEnabled)
            putLong("blockId", blockId ?: -1L)
            containerId?.let { putString("containerId", it) }
            putString("focusSourceContainerId", focusSourceContainerId)
            putBoolean("focusIncludeDescendants", focusIncludeDescendants)
            putString("iconRef", IconRef.toStorageString(iconRef))
            putBoolean("hasIndividualColor", hasIndividualColor)
            putBoolean("createArchived", createArchived)
            putBoolean("colorPickerOpen", colorPicker?.isAdded == true)
        }
    }

    private fun currentBlocks() =
        (application as HabitsApplication).component.habitList.getBlocks()

    private fun defaultColorForCurrentBlock(): PaletteColor =
        HabitColorDefaults.forBlock(blockId, currentBlocks())
}
