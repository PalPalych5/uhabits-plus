/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.habits.edit

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.format.DateFormat
import android.view.MenuItem
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.android.datetimepicker.date.DatePickerDialog
import com.android.datetimepicker.time.RadialPickerLayout
import com.android.datetimepicker.time.TimePickerDialog
import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getFirstWeekdayNumberAccordingToLocale
import org.isoron.platform.time.getToday
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialog
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.activities.common.dialogs.FrequencyPickerDialog
import org.isoron.uhabits.activities.common.dialogs.WeekdayPickerDialog
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.EditHabitGoalCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.GoalApplyScope
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.isMinuteUnit
import org.isoron.uhabits.databinding.ActivityEditHabitBinding
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.formatTime
import org.isoron.uhabits.utils.StyledResources
import org.isoron.uhabits.utils.toFormattedString

fun formatFrequency(freqNum: Int, freqDen: Int, resources: Resources) = when {
    freqNum == 1 && (freqDen == 30 || freqDen == 31) -> resources.getString(R.string.every_month)
    freqDen == 30 || freqDen == 31 -> resources.getString(R.string.x_times_per_month, freqNum)
    freqNum == 1 && freqDen == 1 -> resources.getString(R.string.every_day)
    freqNum == 1 && freqDen == 7 -> resources.getString(R.string.every_week)
    freqNum == 1 && freqDen > 1 -> resources.getString(R.string.every_x_days, freqDen)
    freqDen == 7 -> resources.getString(R.string.x_times_per_week, freqNum)
    else -> resources.getString(R.string.x_times_per_y_days, freqNum, freqDen)
}

private const val DROPDOWN_ARROW_ANIMATION_MS = 160L

private class RotatingDropdownArrowDrawable(
    private val delegate: Drawable
) : Drawable() {
    var rotation: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val currentBounds = bounds
        val saveCount = canvas.save()
        canvas.rotate(rotation, currentBounds.exactCenterX(), currentBounds.exactCenterY())
        delegate.bounds = currentBounds
        delegate.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        delegate.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        delegate.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = delegate.intrinsicWidth

    override fun getIntrinsicHeight(): Int = delegate.intrinsicHeight
}

private class DropdownArrowHandle(
    private val arrow: RotatingDropdownArrowDrawable
) {
    private val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var animator: ObjectAnimator? = null

    fun open() = rotateTo(180f)

    fun close() = rotateTo(0f)

    private fun rotateTo(targetRotation: Float) {
        animator?.cancel()
        animator = ObjectAnimator.ofFloat(arrow, "rotation", arrow.rotation, targetRotation).apply {
            duration = DROPDOWN_ARROW_ANIMATION_MS
            interpolator = this@DropdownArrowHandle.interpolator
            start()
        }
    }
}

class EditHabitActivity : AppCompatActivity() {

    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var binding: ActivityEditHabitBinding
    private lateinit var commandRunner: CommandRunner

    var habitId = -1L
    lateinit var habitType: HabitType
    var unit = ""
    var color = PaletteColor(18)
    var androidColor = 0
    var freqNum = 1
    var freqDen = 1
    var reminderHour = -1
    var reminderMin = -1
    var reminderDays: WeekdayList = WeekdayList.EVERY_DAY
    var targetType = NumericalHabitType.AT_LEAST
    var dayTier = DayTier.NORMAL
    var timerEnabled = false
    var blockId: Long? = 7L
    private var hasIndividualColor = false
    private var createArchived = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val component = (application as HabitsApplication).component
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        binding = ActivityEditHabitBinding.inflate(layoutInflater)
        binding.root.applyRootViewInsets()
        binding.toolbar.applyToolbarInsets()
        setContentView(binding.root)

        if (intent.hasExtra("habitId")) {
            binding.toolbar.title = getString(R.string.edit_habit)
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
            hasIndividualColor = true
            habit.reminder?.let {
                reminderHour = it.hour
                reminderMin = it.minute
                reminderDays = it.days
            }
            binding.nameInput.setText(habit.name)
            binding.questionInput.setText(habit.question)
            binding.notesInput.setText(habit.description)
            binding.unitInput.setText(habit.unit)
            binding.targetInput.setText(habit.targetValue.toString())
        } else {
            habitType = HabitType.fromInt(intent.getIntExtra("habitType", HabitType.YES_NO.value))
            createArchived = intent.getBooleanExtra("createArchived", false)
            timerEnabled = habitType == HabitType.NUMERICAL
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
            hasIndividualColor = state.getBoolean("hasIndividualColor", habitId >= 0)
            createArchived = state.getBoolean("createArchived", false)
        } else if (habitId < 0) {
            color = defaultColorForCurrentBlock()
        }

        updateColors()

        if (!component.preferences.isDayTiersEnabled) {
            binding.dayTierOuterBox.visibility = View.GONE
        }
        if (!component.preferences.isHabitSpheresEnabled) {
            binding.habitBlockOuterBox.visibility = View.GONE
        }

        when (habitType) {
            HabitType.YES_NO -> {
                binding.unitOuterBox.visibility = View.GONE
                binding.targetOuterBox.visibility = View.GONE
                binding.targetTypeOuterBox.visibility = View.GONE
                binding.timerEnabledOuterBox.visibility = View.GONE
            }
            HabitType.NUMERICAL -> {
                binding.nameInput.hint = getString(R.string.measurable_short_example)
                binding.questionInput.hint = getString(R.string.measurable_question_example)
                binding.frequencyOuterBox.visibility = View.GONE

                binding.unitInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        updateTimerVisibility()
                    }
                })
                updateTimerVisibility()
            }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.elevation = 10.0f

        val colorPickerDialogFactory = ColorPickerDialogFactory(this)
        val configureColorPicker: (ColorPickerDialog) -> Unit = { picker ->
            picker.setListener { paletteColor ->
                this.color = paletteColor
                hasIndividualColor = HabitColorDefaults.isIndividual(
                    paletteColor,
                    blockId,
                    currentBlocks()
                )
                updateColors()
            }
        }
        (supportFragmentManager.findFragmentByTag("colorPicker") as? ColorPickerDialog)
            ?.let(configureColorPicker)
        if (state?.getBoolean("colorPickerOpen") == true) {
            (supportFragmentManager.findFragmentByTag("colorPicker") as? ColorPickerDialog)?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
            }
            val previewName = binding.nameInput.text.toString().trim()
                .ifBlank { getString(R.string.color_picker_habit_preview_fallback) }
            val restoredPicker = colorPickerDialogFactory.create(
                color,
                themeSwitcher.currentTheme,
                previewName,
                defaultColorForCurrentBlock()
            )
            restoredPicker.restoreDraftColor(state.getInt("colorPickerDraftColor"))
            configureColorPicker(restoredPicker)
            restoredPicker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
        }
        binding.colorButton.setOnClickListener {
            val previewName = binding.nameInput.text.toString().trim()
                .ifBlank { getString(R.string.color_picker_habit_preview_fallback) }
            val picker = colorPickerDialogFactory.create(
                color,
                themeSwitcher.currentTheme,
                previewName,
                defaultColorForCurrentBlock()
            )
            configureColorPicker(picker)
            picker.dismissCurrentAndShow(supportFragmentManager, "colorPicker")
        }

        val booleanFrequencyArrow = configureDropdownArrow(binding.booleanFrequencyPicker)
        val numericalFrequencyArrow = configureDropdownArrow(binding.numericalFrequencyPicker)
        val targetTypeArrow = configureDropdownArrow(binding.targetTypePicker)
        val dayTierArrow = configureDropdownArrow(binding.dayTierPicker)
        val habitBlockArrow = configureDropdownArrow(binding.habitBlockPicker)
        val reminderTimeArrow = configureDropdownArrow(binding.reminderTimePicker)
        val reminderDateArrow = configureDropdownArrow(binding.reminderDatePicker)

        populateFrequency()
        binding.booleanFrequencyPicker.setOnClickListener {
            booleanFrequencyArrow.open()
            val picker = FrequencyPickerDialog(freqNum, freqDen)
            picker.onDismissCallback = { booleanFrequencyArrow.close() }
            picker.onFrequencyPicked = { num, den ->
                freqNum = num
                freqDen = den
                populateFrequency()
            }
            picker.dismissCurrentAndShow(supportFragmentManager, "frequencyPicker")
        }

        populateTargetType()
        binding.targetTypePicker.setOnClickListener {
            targetTypeArrow.open()
            val options = listOf(
                getString(R.string.target_type_at_least),
                getString(R.string.target_type_at_most)
            )
            val dialog = CustomDialogs.showSingleChoiceDialog(
                context = this,
                title = getString(R.string.target_type),
                options = options,
                selectedIndex = if (targetType == NumericalHabitType.AT_LEAST) 0 else 1
            ) { which ->
                targetType = when (which) {
                    0 -> NumericalHabitType.AT_LEAST
                    else -> NumericalHabitType.AT_MOST
                }
                populateTargetType()
            }
            dialog.setOnDismissListener { targetTypeArrow.close() }
        }

        populateDayTier()
        binding.dayTierPicker.setOnClickListener {
            dayTierArrow.open()
            val tiers = DayTier.entries
            val labels = tiers.map { getString(it.labelResId) }
            val dialog = CustomDialogs.showSingleChoiceDialog(
                context = this,
                title = getString(R.string.day_tier),
                options = labels,
                selectedIndex = tiers.indexOf(dayTier)
            ) { which ->
                dayTier = tiers[which]
                populateDayTier()
            }
            dialog.setOnDismissListener { dayTierArrow.close() }
        }
        binding.timerEnabledSwitch.isChecked = timerEnabled

        binding.habitBlockPicker.setOnClickListener {
            habitBlockArrow.open()
            val component = (application as HabitsApplication).component
            val blocks = component.habitList.getBlocks()
            val items = blocks.map { getBlockDisplayName(it) }

            val dialog = CustomDialogs.showSingleChoiceDialog(
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
                populateHabitBlock()
                updateColors()
            }
            dialog.setOnDismissListener { habitBlockArrow.close() }
        }

        binding.numericalFrequencyPicker.setOnClickListener {
            numericalFrequencyArrow.open()
            val options = listOf(
                getString(R.string.every_day),
                getString(R.string.every_week),
                getString(R.string.every_month)
            )
            val currentIdx = when (freqDen) {
                7 -> 1
                30 -> 2
                else -> 0
            }
            val dialog = CustomDialogs.showSingleChoiceDialog(
                context = this,
                title = getString(R.string.frequency),
                options = options,
                selectedIndex = currentIdx
            ) { which ->
                freqDen = when (which) {
                    1 -> 7
                    2 -> 30
                    else -> 1
                }
                populateFrequency()
            }
            dialog.setOnDismissListener { numericalFrequencyArrow.close() }
        }

        populateReminder()
        binding.reminderTimePicker.setOnClickListener {
            reminderTimeArrow.open()
            val currentHour = if (reminderHour >= 0) reminderHour else 8
            val currentMin = if (reminderMin >= 0) reminderMin else 0
            val is24HourMode = DateFormat.is24HourFormat(this)
            val dialog = TimePickerDialog.newInstance(
                object : TimePickerDialog.OnTimeSetListener {
                    override fun onTimeSet(view: RadialPickerLayout?, hourOfDay: Int, minute: Int) {
                        reminderHour = hourOfDay
                        reminderMin = minute
                        populateReminder()
                    }

                    override fun onTimeCleared(view: RadialPickerLayout?) {
                        reminderHour = -1
                        reminderMin = -1
                        reminderDays = WeekdayList.EVERY_DAY
                        populateReminder()
                    }
                },
                currentHour,
                currentMin,
                is24HourMode,
                androidColor
            )
            dialog.setDismissListener { reminderTimeArrow.close() }
            dialog.dismissCurrentAndShow(supportFragmentManager, "timePicker")
        }

        binding.reminderDatePicker.setOnClickListener {
            reminderDateArrow.open()
            val dialog = WeekdayPickerDialog()
            dialog.onDismissCallback = { reminderDateArrow.close() }

            dialog.setListener { days: WeekdayList ->
                reminderDays = days
                if (reminderDays.isEmpty) reminderDays = WeekdayList.EVERY_DAY
                populateReminder()
            }
            dialog.setSelectedDays(reminderDays)
            dialog.dismissCurrentAndShow(supportFragmentManager, "dayPicker")
        }

        binding.buttonSave.setOnClickListener {
            if (validate()) save()
        }

        for (fragment in supportFragmentManager.fragments) {
            (fragment as DialogFragment).dismiss()
        }
    }

    private fun save() {
        val component = (application as HabitsApplication).component
        val habit = component.modelFactory.buildHabit()

        var original: Habit? = null
        if (habitId >= 0) {
            original = component.habitList.getById(habitId)!!
            habit.copyFrom(original)
        }

        habit.name = binding.nameInput.text.trim().toString()
        habit.question = binding.questionInput.text.trim().toString()
        habit.description = binding.notesInput.text.trim().toString()
        habit.color = color
        if (reminderHour >= 0) {
            habit.reminder = Reminder(reminderHour, reminderMin, reminderDays)
        } else {
            habit.reminder = null
        }

        habit.frequency = Frequency(freqNum, freqDen)
        habit.dayTier = dayTier
        habit.blockId = blockId
        if (habitType == HabitType.NUMERICAL) {
            habit.targetValue = binding.targetInput.text.toString().toDouble()
            habit.targetType = targetType
            habit.unit = binding.unitInput.text.trim().toString()
            habit.timerEnabled = binding.timerEnabledSwitch.isChecked && habit.unit.isMinuteUnit()
        } else {
            habit.timerEnabled = false
        }
        habit.type = habitType
        if (habitId < 0 && createArchived) {
            habit.isArchived = true
        }

        if (habitId >= 0 && original != null && didGoalBundleChange(original, habit)) {
            showGoalChangeDialog(
                original = original,
                modified = habit
            )
            return
        }

        runSaveCommand(
            if (habitId >= 0) {
                EditHabitCommand(component.habitList, habitId, habit)
            } else {
                CreateHabitCommand(component.modelFactory, component.habitList, habit)
            }
        )
    }

    private fun validate(): Boolean {
        var isValid = true
        if (binding.nameInput.text.isEmpty()) {
            binding.nameInput.error = getFormattedValidationError(R.string.validation_cannot_be_blank)
            isValid = false
        }
        if (habitType == HabitType.NUMERICAL) {
            if (binding.targetInput.text.isEmpty()) {
                binding.targetInput.error = getString(R.string.validation_cannot_be_blank)
                isValid = false
            }
        }
        return isValid
    }

    private fun populateReminder() {
        if (reminderHour < 0) {
            binding.reminderTimePicker.text = getString(R.string.reminder_off)
            binding.reminderDatePicker.visibility = View.GONE
            binding.reminderDivider.visibility = View.GONE
        } else {
            val time = formatTime(this, reminderHour, reminderMin)
            binding.reminderTimePicker.text = time
            binding.reminderDatePicker.visibility = View.VISIBLE
            binding.reminderDivider.visibility = View.VISIBLE
            binding.reminderDatePicker.text = reminderDays.toFormattedString(this)
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun populateFrequency() {
        binding.booleanFrequencyPicker.text = formatFrequency(freqNum, freqDen, resources)
        binding.numericalFrequencyPicker.text = when (freqDen) {
            1 -> getString(R.string.every_day)
            7 -> getString(R.string.every_week)
            30 -> getString(R.string.every_month)
            else -> "$freqNum/$freqDen"
        }
    }

    private fun populateTargetType() {
        binding.targetTypePicker.text = when (targetType) {
            NumericalHabitType.AT_MOST -> getString(R.string.target_type_at_most)
            else -> getString(R.string.target_type_at_least)
        }
    }

    private fun populateDayTier() {
        binding.dayTierPicker.text = getString(dayTier.labelResId)
    }

    private val DayTier.labelResId: Int
        get() = when (this) {
            DayTier.MINIMUM -> R.string.day_tier_minimum
            DayTier.NORMAL -> R.string.day_tier_normal
            DayTier.IDEAL -> R.string.day_tier_ideal
            DayTier.OPTIONAL -> R.string.day_tier_optional
        }

    private fun updateColors() {
        androidColor = themeSwitcher.currentTheme.color(color).toInt()
        binding.colorButton.backgroundTintList = ColorStateList.valueOf(androidColor)
        val res = StyledResources(this)
        window.statusBarColor = res.getColor(R.attr.colorPrimaryDark)
        binding.toolbar.setBackgroundColor(res.getColor(R.attr.colorPrimary))
    }

    private fun getFormattedValidationError(@StringRes resId: Int): Spanned {
        val html = "<font color=#FFFFFF>${getString(resId)}</font>"
        return Html.fromHtml(html)
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        val colorPicker = supportFragmentManager.findFragmentByTag("colorPicker") as? ColorPickerDialog
        with(state) {
            putLong("habitId", habitId)
            putInt("habitType", habitType.value)
            putInt("paletteColor", color.paletteIndex)
            putInt("androidColor", androidColor)
            putInt("freqNum", freqNum)
            putInt("freqDen", freqDen)
            putInt("reminderHour", reminderHour)
            putInt("reminderMin", reminderMin)
            putInt("reminderDays", reminderDays.toInteger())
            putString("dayTier", dayTier.name)
            putBoolean("timerEnabled", binding.timerEnabledSwitch.isChecked)
            putLong("blockId", blockId ?: -1L)
            putBoolean("hasIndividualColor", hasIndividualColor)
            putBoolean("createArchived", createArchived)
            putBoolean("colorPickerOpen", colorPicker?.isAdded == true)
            colorPicker?.let {
                putInt("colorPickerDraftColor", it.snapshotDraftColor())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        populateHabitBlock()
    }

    private fun populateHabitBlock() {
        val component = (application as HabitsApplication).component
        val blocks = component.habitList.getBlocks()
        val currentBlock = blocks.firstOrNull { it.id == blockId }
        binding.habitBlockPicker.text = currentBlock?.let { getBlockDisplayName(it) } ?: getString(R.string.habit_block_unassigned)
    }

    private fun currentBlocks() =
        (application as HabitsApplication).component.habitList.getBlocks()

    private fun defaultColorForCurrentBlock(): PaletteColor =
        HabitColorDefaults.forBlock(blockId, currentBlocks())

    private fun getBlockDisplayName(block: org.isoron.uhabits.core.models.HabitBlock): String {
        return if (block.id in 1L..7L) {
            when (block.id) {
                1L -> getString(R.string.today_section_intellect)
                2L -> getString(R.string.today_section_speech)
                3L -> getString(R.string.today_section_body)
                4L -> getString(R.string.today_section_care)
                5L -> getString(R.string.today_section_routine)
                6L -> getString(R.string.today_section_limits)
                7L -> getString(R.string.today_section_other)
                else -> block.name
            }
        } else {
            block.name
        }
    }

    private fun updateTimerVisibility() {
        val isMinute = binding.unitInput.text.toString().isMinuteUnit()
        binding.timerEnabledOuterBox.visibility = if (isMinute) View.VISIBLE else View.GONE
    }

    private fun configureDropdownArrow(field: TextView): DropdownArrowHandle {
        val base = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_drop_down_dark)!!
            .mutate()
        DrawableCompat.setTint(base, StyledResources(this).getColor(R.attr.contrast60))
        val arrow = RotatingDropdownArrowDrawable(base)
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        return DropdownArrowHandle(arrow)
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
        (application as HabitsApplication).component.commandRunner.run(command)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
