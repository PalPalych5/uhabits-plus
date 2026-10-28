/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.statistics

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.utils.InterfaceUtils

/**
 * Filter bottom sheet for selecting section scope and subtree mode in Unified Statistics.
 */
class UnifiedStatisticsFiltersBottomSheet : BottomSheetDialogFragment() {

    private var originDatasetId: String? = null
    private var selectedScopeType: String = SCOPE_ALL
    private var selectedContainerId: String? = null
    private var selectedIncludeDescendants: Boolean = true

    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    private fun dp(value: Float): Int = InterfaceUtils.dpToPixels(requireContext(), value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Statistics_BottomSheetDialogTheme)
        val args = savedInstanceState ?: arguments
        if (args != null) {
            originDatasetId = args.getString(ARG_DATASET_ID)
            selectedScopeType = args.getString(ARG_SCOPE_TYPE, SCOPE_ALL)
            selectedContainerId = args.getString(ARG_CONTAINER_ID)
            selectedIncludeDescendants = args.getBoolean(ARG_INCLUDE_DESCENDANTS, true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val app = context.applicationContext as HabitsApplication
        val owner = app.component
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Title
        val titleView = TextView(context).apply {
            text = getString(R.string.unified_statistics_filters_title)
            textSize = 18f
            setTextColor(palette.onSurface)
            setPadding(0, 0, 0, dp(12f))
        }
        root.addView(titleView)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        // Radio: All sections
        val rbAll = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.unified_statistics_scope_all)
            setTextColor(palette.onSurface)
            buttonTintList = ColorStateList.valueOf(palette.accent)
            minHeight = dp(48f)
            isChecked = selectedScopeType == SCOPE_ALL
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedScopeType = SCOPE_ALL
                    selectedContainerId = null
                }
            }
        }
        radioGroup.addView(rbAll)

        // Radio: Unassigned
        val rbUnassigned = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.unified_statistics_scope_unassigned)
            setTextColor(palette.onSurface)
            buttonTintList = ColorStateList.valueOf(palette.accent)
            minHeight = dp(48f)
            isChecked = selectedScopeType == SCOPE_UNASSIGNED
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedScopeType = SCOPE_UNASSIGNED
                    selectedContainerId = null
                }
            }
        }
        radioGroup.addView(rbUnassigned)

        fun renderContainers(allContainers: List<org.isoron.uhabits.core.containers.Container>) {
          allContainers.forEach { c ->
            val rbContainer = RadioButton(context).apply {
                id = View.generateViewId()
                text = c.name
                setTextColor(palette.onSurface)
                buttonTintList = ColorStateList.valueOf(palette.accent)
                minHeight = dp(48f)
                isChecked = selectedScopeType == SCOPE_CONTAINER && selectedContainerId == c.id.value
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedScopeType = SCOPE_CONTAINER
                        selectedContainerId = c.id.value
                    }
                }
            }
            radioGroup.addView(rbContainer)
        }

        }

        owner.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var containers = emptyList<org.isoron.uhabits.core.containers.Container>()
            private var error: Exception? = null
            override suspend fun doInBackground() {
                try {
                    val queries = requireNotNull(owner.organizationFacade.containerQueries)
                    val visited = mutableSetOf<org.isoron.uhabits.core.containers.ContainerId>()
                    val found = mutableListOf<org.isoron.uhabits.core.containers.Container>()
                    fun collect(c: org.isoron.uhabits.core.containers.Container, parent: String) {
                        if (!visited.add(c.id)) return
                        val label = if (parent.isBlank()) c.name else "$parent / ${c.name}"
                        found.add(c.copy(name = label))
                        queries.children(c.id, includeArchived = true).forEach { collect(it, label) }
                    }
                    queries.roots(includeArchived = true).forEach { collect(it, "") }
                    containers = found
                } catch (e: Exception) { error = e }
            }
            override fun onPostExecute() {
                if (!isAdded || app.isDatasetRebuilding || app.component !== owner) return
                if (error == null) renderContainers(containers)
                else contentLayout.addView(TextView(context).apply {
                    text = getString(R.string.unified_statistics_state_failed)
                    setTextColor(palette.onSurface)
                })
            }
        })

        contentLayout.addView(radioGroup)

        // Subtree vs HERE toggle
        val modeHeader = TextView(context).apply {
            text = getString(R.string.unified_statistics_scope_mode_subtree) + " / " +
                getString(R.string.unified_statistics_scope_mode_here)
            textSize = 14f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(12f), 0, dp(6f))
        }
        contentLayout.addView(modeHeader)

        val modeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        val rbSubtree = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.unified_statistics_scope_mode_subtree)
            setTextColor(palette.onSurface)
            buttonTintList = ColorStateList.valueOf(palette.accent)
            minHeight = dp(48f)
            isChecked = selectedIncludeDescendants
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIncludeDescendants = true
            }
        }
        modeGroup.addView(rbSubtree)

        val rbHere = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.unified_statistics_scope_mode_here)
            setTextColor(palette.onSurface)
            buttonTintList = ColorStateList.valueOf(palette.accent)
            minHeight = dp(48f)
            isChecked = !selectedIncludeDescendants
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIncludeDescendants = false
            }
        }
        modeGroup.addView(rbHere)

        contentLayout.addView(modeGroup)
        scrollView.addView(contentLayout)
        root.addView(scrollView)

        // Action buttons (Reset, Apply)
        val buttonBar = LinearLayout(context).apply {
            orientation = if (resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16f), 0, 0)
        }

        val btnReset = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = getString(R.string.unified_statistics_filters_reset)
            setTextColor(palette.onSurfaceVariant)
            minHeight = dp(48f)
            setOnClickListener {
                selectedScopeType = SCOPE_ALL
                selectedContainerId = null
                selectedIncludeDescendants = true
                rbAll.isChecked = true
                rbSubtree.isChecked = true
            }
        }
        buttonBar.addView(btnReset)

        val btnApply = MaterialButton(context).apply {
            text = getString(R.string.unified_statistics_filters_apply)
            setBackgroundColor(palette.accent)
            setTextColor(if (palette.isDark) Color.BLACK else Color.WHITE)
            minHeight = dp(48f)
            setOnClickListener {
                val currentApp = requireContext().applicationContext as HabitsApplication
                if (currentApp.isDatasetRebuilding || originDatasetId.isNullOrBlank() || originDatasetId != currentApp.component.session.datasetId) {
                    dismissAllowingStateLoss()
                    return@setOnClickListener
                }
                val bundle = Bundle().apply {
                    putString(EXTRA_DATASET_ID, originDatasetId)
                    putString(EXTRA_SCOPE_TYPE, selectedScopeType)
                    putString(EXTRA_CONTAINER_ID, selectedContainerId)
                    putBoolean(EXTRA_INCLUDE_DESCENDANTS, selectedIncludeDescendants)
                }
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundle)
                dismiss()
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(8f)
        }
        buttonBar.addView(btnApply, lp)

        root.addView(buttonBar)
        return root
    }

    override fun onStart() {
        super.onStart()
        val app = requireContext().applicationContext as HabitsApplication
        if (app.isDatasetRebuilding || originDatasetId.isNullOrBlank() || originDatasetId != app.component.session.datasetId) {
            dismissAllowingStateLoss()
            return
        }
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        (dialog as? BottomSheetDialog)?.let { sheetDialog ->
            sheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = GradientDrawable().apply {
                    setColor(palette.surface)
                    val r = dp(16f).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    if (palette.isPureBlack) {
                        setStroke(dp(1f), palette.border)
                    }
                }
                BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_DATASET_ID, originDatasetId)
        outState.putString(ARG_SCOPE_TYPE, selectedScopeType)
        outState.putString(ARG_CONTAINER_ID, selectedContainerId)
        outState.putBoolean(ARG_INCLUDE_DESCENDANTS, selectedIncludeDescendants)
    }

    companion object {
        const val TAG = "UnifiedStatisticsFiltersBottomSheet"
        const val RESULT_KEY = "unified_statistics_filter_result"

        const val SCOPE_ALL = "ALL"
        const val SCOPE_UNASSIGNED = "UNASSIGNED"
        const val SCOPE_CONTAINER = "CONTAINER"

        const val ARG_DATASET_ID = "arg_dataset_id"
        const val ARG_SCOPE_TYPE = "arg_scope_type"
        const val ARG_CONTAINER_ID = "arg_container_id"
        const val ARG_INCLUDE_DESCENDANTS = "arg_include_descendants"

        const val EXTRA_DATASET_ID = "dataset_id"
        const val EXTRA_SCOPE_TYPE = "scope_type"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_INCLUDE_DESCENDANTS = "include_descendants"

        fun show(
            fragmentManager: FragmentManager,
            currentScope: UnifiedScope,
            datasetId: String? = null,
        ) {
            val sheet = UnifiedStatisticsFiltersBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_DATASET_ID, datasetId)
                    when (currentScope) {
                        is UnifiedScope.All -> {
                            putString(ARG_SCOPE_TYPE, SCOPE_ALL)
                        }
                        is UnifiedScope.Unassigned -> {
                            putString(ARG_SCOPE_TYPE, SCOPE_UNASSIGNED)
                        }
                        is UnifiedScope.Container -> {
                            putString(ARG_SCOPE_TYPE, SCOPE_CONTAINER)
                            putString(ARG_CONTAINER_ID, currentScope.id.value)
                            putBoolean(ARG_INCLUDE_DESCENDANTS, currentScope.includeDescendants)
                        }
                    }
                }
            }
            sheet.show(fragmentManager, TAG)
        }
    }
}
