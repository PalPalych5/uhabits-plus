/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.statistics

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.models.HabitBlock
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsFilterState
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsGoalTypeFilter
import org.isoron.uhabits.core.ui.screens.statistics.StatisticsHabitStatusFilter

class StatisticsFiltersBottomSheet : BottomSheetDialogFragment() {
    private var draftState by mutableStateOf(LegacyStatisticsFilterDraft())
    private var sphereOptions: List<LegacyFilterSphereOption> = emptyList()
    private var spheresEnabled: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Statistics_BottomSheetDialogTheme)
        spheresEnabled = arguments?.getBoolean(ARG_SPHERES_ENABLED, true) ?: true
        sphereOptions = readSphereOptions(requireArguments())
        restoreSelection(savedInstanceState ?: requireArguments())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val palette = remember { MainTabsThemeBridge.resolve(requireContext()) }
            val colors = remember(palette) {
                UHabitComposeColors.fromPalette(
                    palette = palette,
                    cardCornerRadiusDp = palette.statisticsCardCornerRadius
                )
            }
            UHabitComposeTheme(colors = colors) {
                LegacyStatisticsFiltersSheetContent(
                    draft = draftState,
                    sphereOptions = sphereOptions,
                    spheresEnabled = spheresEnabled,
                    onSelectSphere = { sphereId ->
                        draftState = draftState.copy(sphereId = sphereId)
                    },
                    onSelectStatus = { status ->
                        draftState = draftState.copy(habitStatus = status)
                    },
                    onSelectGoalType = { goalType ->
                        draftState = draftState.copy(goalType = goalType)
                    },
                    onReset = {
                        draftState = draftState.reset()
                    },
                    onApply = {
                        parentFragmentManager.setFragmentResult(
                            RESULT_KEY,
                            Bundle().also(::writeSelection)
                        )
                        dismiss()
                    },
                    colors = colors
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.statistics_filters_title))
    }

    override fun onStart() {
        super.onStart()
        val palette = MainTabsThemeBridge.resolve(requireContext())
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightNavigationBars = !palette.isDark
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        writeSelection(outState)
        super.onSaveInstanceState(outState)
    }

    private fun restoreSelection(source: Bundle) {
        draftState = LegacyStatisticsFilterDraft(
            sphereId = source.getLong(ARG_SPHERE_ID, NO_SPHERE_ID)
                .takeUnless { it == NO_SPHERE_ID },
            habitStatus = enumValueOrDefault(
                source.getString(ARG_STATUS),
                StatisticsHabitStatusFilter.ACTIVE
            ),
            goalType = enumValueOrDefault(
                source.getString(ARG_GOAL_TYPE),
                StatisticsGoalTypeFilter.ALL
            )
        )
    }

    private fun writeSelection(target: Bundle) {
        val sphereId = if (spheresEnabled) {
            resolveLegacySelectedSphereId(draftState.sphereId, sphereOptions)
        } else {
            draftState.sphereId
        }
        target.putLong(ARG_SPHERE_ID, sphereId ?: NO_SPHERE_ID)
        target.putString(ARG_STATUS, draftState.habitStatus.name)
        target.putString(ARG_GOAL_TYPE, draftState.goalType.name)
    }

    private fun readSphereOptions(source: Bundle): List<LegacyFilterSphereOption> {
        val ids = source.getLongArray(ARG_BLOCK_IDS) ?: LongArray(0)
        val names = source.getStringArrayList(ARG_BLOCK_NAMES) ?: emptyList<String>()
        return ids.indices.mapNotNull { index ->
            names.getOrNull(index)?.let { name -> LegacyFilterSphereOption(ids[index], name) }
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    companion object {
        const val RESULT_KEY = "statistics_filters_result"
        private const val TAG = "statistics_filters"
        private const val ARG_SPHERE_ID = "sphere_id"
        private const val ARG_STATUS = "habit_status"
        private const val ARG_GOAL_TYPE = "goal_type"
        private const val ARG_SPHERES_ENABLED = "spheres_enabled"
        private const val ARG_BLOCK_IDS = "block_ids"
        private const val ARG_BLOCK_NAMES = "block_names"
        private const val NO_SPHERE_ID = Long.MIN_VALUE

        fun show(
            fragmentManager: FragmentManager,
            initialState: StatisticsFilterState,
            blocks: List<HabitBlock>,
            spheresEnabled: Boolean = true
        ) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val selectableBlocks = sortSelectableFilterBlocks(blocks)
            StatisticsFiltersBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SPHERE_ID, initialState.sphereId ?: NO_SPHERE_ID)
                    putString(ARG_STATUS, initialState.habitStatus.name)
                    putString(ARG_GOAL_TYPE, initialState.goalType.name)
                    putBoolean(ARG_SPHERES_ENABLED, spheresEnabled)
                    putLongArray(ARG_BLOCK_IDS, selectableBlocks.map { it.id }.toLongArray())
                    putStringArrayList(ARG_BLOCK_NAMES, ArrayList(selectableBlocks.map { it.name }))
                }
            }.show(fragmentManager, TAG)
        }
    }
}
