/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.isoron.uhabits.activities.statistics

import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.DayTier
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsGoalTypeFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter

import android.graphics.drawable.ColorDrawable

class StatisticsFiltersBottomSheet : BottomSheetDialogFragment() {
    private var selectedSphereId: Long? = null
    private var selectedStatus = StatisticsHabitStatusFilter.ACTIVE
    private var selectedGoalType = StatisticsGoalTypeFilter.ALL
    private var selectedTier: DayTier? = null
    private var sphereOptions: List<SphereOption> = emptyList()
    private val sphereChipValues = mutableMapOf<Int, Long?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Statistics_BottomSheetDialogTheme)
        sphereOptions = readSphereOptions(requireArguments())
        restoreSelection(savedInstanceState ?: requireArguments())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.statistics_filters_bottom_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.statistics_filters_title))
        applyInsets(view)

        setupSphereGroup(view.findViewById(R.id.sphereChipGroup))
        setupStatusGroup(view.findViewById(R.id.statusChipGroup))
        setupGoalTypeGroup(view.findViewById(R.id.goalTypeChipGroup))
        setupTierGroup(view.findViewById(R.id.tierChipGroup))
        styleSheet(view)

        view.findViewById<View>(R.id.resetFiltersButton).setOnClickListener {
            selectedSphereId = null
            selectedStatus = StatisticsHabitStatusFilter.ACTIVE
            selectedGoalType = StatisticsGoalTypeFilter.ALL
            selectedTier = null
            renderSelections(view)
        }
        view.findViewById<View>(R.id.applyFiltersButton).setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().also(::writeSelection))
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.let { sheet ->
                sheet.setBackgroundColor(Color.TRANSPARENT)
                sheet.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                sheet.elevation = 0f
                val maxHeight = (resources.displayMetrics.heightPixels * 0.75f).toInt()
                BottomSheetBehavior.from(sheet).apply {
                    this.maxHeight = maxHeight
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
    }

    private fun styleSheet(root: View) {
        val prefs = (requireContext().applicationContext as HabitsApplication).component.preferences
        val colors = SettingsThemePaletteResolver.resolve(requireContext(), prefs)
        (root as MaterialCardView).setCardBackgroundColor(colors.surface)
        root.findViewById<NestedScrollView>(R.id.statisticsFiltersScroll).setBackgroundColor(colors.surface)
        root.findViewById<View>(R.id.filtersActions).setBackgroundColor(colors.surface)
        val density = resources.displayMetrics.density
        val checked = intArrayOf(android.R.attr.state_checked)
        val normal = intArrayOf()
        val fill = ColorStateList(
            arrayOf(checked, normal),
            intArrayOf(ColorUtils.blendARGB(colors.surface, colors.accent, 0.18f), colors.surface)
        )
        val outline = ColorStateList(
            arrayOf(checked, normal),
            intArrayOf(colors.accent, colors.border)
        )
        listOf(R.id.sphereChipGroup, R.id.statusChipGroup, R.id.goalTypeChipGroup, R.id.tierChipGroup)
            .map { root.findViewById<ChipGroup>(it) }
            .forEach { group ->
                for (index in 0 until group.childCount) {
                    (group.getChildAt(index) as? Chip)?.apply {
                        chipBackgroundColor = fill
                        chipStrokeColor = outline
                        chipStrokeWidth = density
                        chipCornerRadius = 8f * density
                        setTextColor(colors.onSurface)
                        minimumHeight = (32f * density).toInt()
                        chipMinHeight = 32f * density
                        textSize = 12f
                    }
                }
            }
        val apply = root.findViewById<MaterialButton>(R.id.applyFiltersButton)
        apply.backgroundTintList = ColorStateList.valueOf(colors.accent)
        apply.setTextColor(if (ColorUtils.calculateContrast(Color.BLACK, colors.accent) >= 4.5) Color.BLACK else Color.WHITE)
        val reset = root.findViewById<MaterialButton>(R.id.resetFiltersButton)
        reset.backgroundTintList = ColorStateList.valueOf(colors.surface)
        reset.strokeColor = ColorStateList.valueOf(colors.border)
        reset.setTextColor(colors.onSurface)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        writeSelection(outState)
        super.onSaveInstanceState(outState)
    }

    private fun setupSphereGroup(group: ChipGroup) {
        group.removeAllViews()
        sphereChipValues.clear()
        addSphereChip(group, null, getString(R.string.reports_filter_all_spheres))
        sphereOptions.forEach { option ->
            addSphereChip(group, option.id, option.name)
        }
        checkSphere(group)
        group.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID) selectedSphereId = sphereChipValues[checkedId]
        }
    }

    private fun addSphereChip(group: ChipGroup, id: Long?, label: String) {
        val chip = LayoutInflater.from(group.context)
            .inflate(R.layout.statistics_filter_chip, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        sphereChipValues[chip.id] = id
        group.addView(chip)
    }

    private fun setupStatusGroup(group: ChipGroup) {
        checkStatus(group)
        group.setOnCheckedChangeListener { _, checkedId ->
            selectedStatus = when (checkedId) {
                R.id.statusAllChip -> StatisticsHabitStatusFilter.ALL
                R.id.statusArchivedChip -> StatisticsHabitStatusFilter.ARCHIVED
                else -> StatisticsHabitStatusFilter.ACTIVE
            }
        }
    }

    private fun setupGoalTypeGroup(group: ChipGroup) {
        checkGoalType(group)
        group.setOnCheckedChangeListener { _, checkedId ->
            selectedGoalType = when (checkedId) {
                R.id.goalTypeBooleanChip -> StatisticsGoalTypeFilter.YES_NO
                R.id.goalTypeNumericalChip -> StatisticsGoalTypeFilter.NUMERICAL
                else -> StatisticsGoalTypeFilter.ALL
            }
        }
    }

    private fun setupTierGroup(group: ChipGroup) {
        checkTier(group)
        group.setOnCheckedChangeListener { _, checkedId ->
            selectedTier = when (checkedId) {
                R.id.tierMinimumChip -> DayTier.MINIMUM
                R.id.tierNormalChip -> DayTier.NORMAL
                R.id.tierIdealChip -> DayTier.IDEAL
                R.id.tierOptionalChip -> DayTier.OPTIONAL
                else -> null
            }
        }
    }

    private fun renderSelections(root: View) {
        checkSphere(root.findViewById(R.id.sphereChipGroup))
        checkStatus(root.findViewById(R.id.statusChipGroup))
        checkGoalType(root.findViewById(R.id.goalTypeChipGroup))
        checkTier(root.findViewById(R.id.tierChipGroup))
    }

    private fun checkSphere(group: ChipGroup) {
        val chipId = sphereChipValues.entries
            .firstOrNull { it.value == selectedSphereId }
            ?.key
            ?: sphereChipValues.entries.first { it.value == null }.key
        group.check(chipId)
    }

    private fun checkStatus(group: ChipGroup) {
        group.check(
            when (selectedStatus) {
                StatisticsHabitStatusFilter.ALL -> R.id.statusAllChip
                StatisticsHabitStatusFilter.ACTIVE -> R.id.statusActiveChip
                StatisticsHabitStatusFilter.ARCHIVED -> R.id.statusArchivedChip
            }
        )
    }

    private fun checkGoalType(group: ChipGroup) {
        group.check(
            when (selectedGoalType) {
                StatisticsGoalTypeFilter.ALL -> R.id.goalTypeAllChip
                StatisticsGoalTypeFilter.YES_NO -> R.id.goalTypeBooleanChip
                StatisticsGoalTypeFilter.NUMERICAL -> R.id.goalTypeNumericalChip
            }
        )
    }

    private fun checkTier(group: ChipGroup) {
        group.check(
            when (selectedTier) {
                DayTier.MINIMUM -> R.id.tierMinimumChip
                DayTier.NORMAL -> R.id.tierNormalChip
                DayTier.IDEAL -> R.id.tierIdealChip
                DayTier.OPTIONAL -> R.id.tierOptionalChip
                null -> R.id.tierAllChip
            }
        )
    }


    private fun restoreSelection(source: Bundle) {
        selectedSphereId = source.getLong(ARG_SPHERE_ID, NO_SPHERE_ID)
            .takeUnless { it == NO_SPHERE_ID }
        selectedStatus = enumValueOrDefault(
            source.getString(ARG_STATUS),
            StatisticsHabitStatusFilter.ACTIVE
        )
        selectedGoalType = enumValueOrDefault(
            source.getString(ARG_GOAL_TYPE),
            StatisticsGoalTypeFilter.ALL
        )
        selectedTier = source.getString(ARG_TIER)?.let { tierName ->
            DayTier.entries.firstOrNull { it.name == tierName }
        }
    }

    private fun writeSelection(target: Bundle) {
        target.putLong(ARG_SPHERE_ID, selectedSphereId ?: NO_SPHERE_ID)
        target.putString(ARG_STATUS, selectedStatus.name)
        target.putString(ARG_GOAL_TYPE, selectedGoalType.name)
        target.putString(ARG_TIER, selectedTier?.name)
    }

    private fun readSphereOptions(source: Bundle): List<SphereOption> {
        val ids = source.getLongArray(ARG_BLOCK_IDS) ?: LongArray(0)
        val names = source.getStringArrayList(ARG_BLOCK_NAMES) ?: emptyList<String>()
        return ids.indices.mapNotNull { index ->
            names.getOrNull(index)?.let { name -> SphereOption(ids[index], name) }
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    private fun applyInsets(root: View) {
        val initialBottom = root.paddingBottom
        val initialStart = root.paddingLeft
        val initialEnd = root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialStart + systemBars.left,
                right = initialEnd + systemBars.right,
                bottom = initialBottom + systemBars.bottom
            )
            insets
        }
    }

    private data class SphereOption(val id: Long, val name: String)

    companion object {
        const val RESULT_KEY = "statistics_filters_result"
        private const val TAG = "statistics_filters"
        private const val ARG_SPHERE_ID = "sphere_id"
        private const val ARG_STATUS = "habit_status"
        private const val ARG_GOAL_TYPE = "goal_type"
        private const val ARG_TIER = "day_tier"
        private const val ARG_BLOCK_IDS = "block_ids"
        private const val ARG_BLOCK_NAMES = "block_names"
        private const val NO_SPHERE_ID = Long.MIN_VALUE

        fun show(
            fragmentManager: FragmentManager,
            initialState: StatisticsFilterState,
            blocks: List<HabitBlock>
        ) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val selectableBlocks = blocks
                .filter { it.id != null }
                .sortedWith(compareBy<HabitBlock> { it.position }.thenBy { it.name })
            StatisticsFiltersBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SPHERE_ID, initialState.sphereId ?: NO_SPHERE_ID)
                    putString(ARG_STATUS, initialState.habitStatus.name)
                    putString(ARG_GOAL_TYPE, initialState.goalType.name)
                    putString(ARG_TIER, initialState.tier?.name)
                    putLongArray(ARG_BLOCK_IDS, selectableBlocks.map { it.id!! }.toLongArray())
                    putStringArrayList(ARG_BLOCK_NAMES, ArrayList(selectableBlocks.map { it.name }))
                }
            }.show(fragmentManager, TAG)
        }
    }
}
