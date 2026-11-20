/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId

/**
 * Filter bottom sheet for selecting section scope and subtree mode in Unified Statistics.
 */
class UnifiedStatisticsFiltersBottomSheet : BottomSheetDialogFragment() {

    private var originDatasetId: String? = null
    private var selectedScopeType by mutableStateOf(SCOPE_ALL)
    private var selectedContainerId by mutableStateOf<String?>(null)
    private var selectedIncludeDescendants by mutableStateOf(true)
    private var containers by mutableStateOf(emptyList<UnifiedStatisticsScopeChoice>())
    private var failed by mutableStateOf(false)
    private var viewRequest = 0

    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

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
        val request = ++viewRequest
        containers = emptyList()
        failed = false

        owner.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var choices = emptyList<UnifiedStatisticsScopeChoice>()
            private var loadFailed = false

            override suspend fun doInBackground() {
                try {
                    val queries = requireNotNull(owner.organizationFacade.containerQueries)
                    val visited = mutableSetOf<ContainerId>()
                    choices = buildList {
                        fun collect(container: Container, parent: String) {
                            if (!visited.add(container.id)) return
                            val label = if (parent.isBlank()) container.name else "$parent / ${container.name}"
                            add(UnifiedStatisticsScopeChoice(container.id.value, label))
                            queries.children(container.id, includeArchived = true).forEach { collect(it, label) }
                        }
                        queries.roots(includeArchived = true).forEach { collect(it, "") }
                    }
                } catch (_: Exception) {
                    loadFailed = true
                }
            }

            override fun onPostExecute() {
                if (request != viewRequest || !isAdded || app.isDatasetRebuilding || app.component !== owner) return
                containers = choices
                failed = loadFailed
            }
        })

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(UHabitComposeColors.fromPalette(palette)) {
                    UnifiedStatisticsFiltersCompose(
                        type = selectedScopeType,
                        containerId = selectedContainerId,
                        includeDescendants = selectedIncludeDescendants,
                        containers = containers,
                        failed = failed,
                        onScope = { type, id ->
                            selectedScopeType = type
                            selectedContainerId = id
                        },
                        onIncludeDescendants = { selectedIncludeDescendants = it },
                        onReset = {
                            selectedScopeType = SCOPE_ALL
                            selectedContainerId = null
                            selectedIncludeDescendants = true
                        },
                        onApply = ::applySelection,
                    )
                }
            }
        }
    }

    private fun applySelection() {
        val app = requireContext().applicationContext as HabitsApplication
        if (app.isDatasetRebuilding || originDatasetId.isNullOrBlank() || originDatasetId != app.component.session.datasetId) {
            dismissAllowingStateLoss()
            return
        }
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
            putString(EXTRA_DATASET_ID, originDatasetId)
            putString(EXTRA_SCOPE_TYPE, selectedScopeType)
            putString(EXTRA_CONTAINER_ID, selectedContainerId)
            putBoolean(EXTRA_INCLUDE_DESCENDANTS, selectedIncludeDescendants)
        })
        dismiss()
    }

    override fun onDestroyView() {
        viewRequest++
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        val app = requireContext().applicationContext as HabitsApplication
        if (app.isDatasetRebuilding || originDatasetId.isNullOrBlank() || originDatasetId != app.component.session.datasetId) {
            dismissAllowingStateLoss()
            return
        }
        org.isoron.uhabits.activities.common.dialogs.CustomDialogs.styleBottomSheet(dialog, requireContext(), palette)
        (dialog as? BottomSheetDialog)?.let { sheetDialog ->
            sheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
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
