/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.inject.HabitsApplicationComponent

/**
 * BottomSheetDialogFragment hosting the Compose container picker [TaskContainerPickerContent].
 *
 * Preserves the exact FragmentResult contract, dataset validation, insets management,
 * back navigation, and test hooks while rendering the UI in Jetpack Compose.
 */
class TaskContainerPickerBottomSheet : BottomSheetDialogFragment() {
    private lateinit var component: HabitsApplicationComponent
    private lateinit var composeView: ComposeView
    private var query = ""
    private var pathExpanded = false
    private var imeVisible = false
    private var searchReceiver: BroadcastReceiver? = null
    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    // Compose state observables
    private var queryState by mutableStateOf("")
    private var pathExpandedState by mutableStateOf(false)
    private var externalSearchCommand by mutableStateOf<ExternalSearchCommand?>(null)
    private var availableItemsState by mutableStateOf<List<OrganizationalItem>>(emptyList())
    private var containerPathsState by mutableStateOf<Map<String, List<Container>>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        component = (requireContext().applicationContext as HabitsApplication).component
        query = savedInstanceState?.getString("query").orEmpty()
        pathExpanded = savedInstanceState?.getBoolean("pathExpanded") ?: false
        queryState = query
        pathExpandedState = pathExpanded
    }

    private fun isCurrentDataset() = isAdded &&
        (requireContext().applicationContext as HabitsApplication).component === component &&
        arguments?.getString("datasetId") == component.session.datasetId && component.session.isNextArchitecture

    private fun reloadContainers() {
        if (!isCurrentDataset()) return
        val items = component.organizationFacade.availableContainers()
        val paths = items.associate { it.key to component.organizationFacade.containerPath(ContainerId(it.key)) }
        availableItemsState = items
        containerPathsState = paths
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        reloadContainers()
        composeView = ComposeView(requireContext()).apply {
            setPadding(dp(24), 0, dp(24), 0)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    TaskContainerPickerContent(
                        items = availableItemsState,
                        containerPaths = containerPathsState,
                        selectedContainerId = arguments?.getString(RESULT_CONTAINER_ID),
                        query = queryState,
                        onQueryChange = { next ->
                            query = next
                            queryState = next
                        },
                        pathExpanded = pathExpandedState,
                        onTogglePathExpanded = {
                            val next = !pathExpandedState
                            pathExpanded = next
                            pathExpandedState = next
                            updateSheetHeight()
                        },
                        onSelect = { item -> select(item) },
                        onClose = { dismiss() },
                        colors = colors,
                        externalSearchCommand = externalSearchCommand
                    )
                }
            }
        }
        return composeView
    }

    internal fun select(item: OrganizationalItem?) {
        if (!isCurrentDataset()) return
        if (item != null && component.organizationFacade.availableContainers().none { it.key == item.key }) {
            reloadContainers()
            return
        }
        parentFragmentManager.setFragmentResult(
            requireArguments().getString("resultKey")!!,
            Bundle().apply { putString(RESULT_CONTAINER_ID, item?.key) }
        )
        dismiss()
    }

    internal fun setSearchQuery(text: String) {
        query = text
        queryState = text
        externalSearchCommand = ExternalSearchCommand(text)
    }

    internal fun togglePathExpanded() {
        val next = !pathExpandedState
        pathExpanded = next
        pathExpandedState = next
        updateSheetHeight()
    }

    internal fun getAvailableItems(): List<OrganizationalItem> = availableItemsState

    private var lastInsets: WindowInsetsCompat? = null

    private fun updateSheetHeight() {
        val sheet = dialog as? BottomSheetDialog ?: return
        val surface = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val insets = lastInsets ?: return
        val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = maxOf(system.bottom, ime.bottom)
        if (::composeView.isInitialized) {
            composeView.setPadding(dp(24) + system.left, 0, dp(24) + system.right, bottomInset)
        }
        val available = resources.displayMetrics.heightPixels - system.top - dp(32)
        val contentHeight = dp((if (imeVisible) 220 else 588) + if (pathExpandedState) 96 else 0)
        val targetHeight = minOf(contentHeight + bottomInset, available)
        surface.translationY = 0f
        if (surface.layoutParams?.height != targetHeight) {
            surface.layoutParams = surface.layoutParams.apply { height = targetHeight }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), R.style.ThemeOverlay_UHabit_NativeSurfaceTaskPickerBottomSheet).apply {
            window?.let { window ->
                // The expanded picker can draw behind the status bar.
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !palette.isDark
            }
        }

    override fun onStart() {
        super.onStart()
        if (!isCurrentDataset()) { dismissAllowingStateLoss(); return }
        (dialog as? BottomSheetDialog)?.let { sheet ->
            val palette = palette
            CustomDialogs.styleBottomSheet(sheet, requireContext(), palette, cornerRadiusDp = 24f)
            sheet.window?.let { window ->
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightNavigationBars = !palette.isDark
            }
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
                lastInsets = insets
                imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                updateSheetHeight()
                insets
            }
            sheet.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (imeVisible) {
                        ViewCompat.getWindowInsetsController(composeView)?.hide(WindowInsetsCompat.Type.ime())
                    } else if (queryState.isNotEmpty()) {
                        query = ""
                        queryState = ""
                        externalSearchCommand = null
                    } else {
                        dismiss()
                    }
                }
            })
        }
        ViewCompat.requestApplyInsets(composeView)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val raw = intent?.getStringExtra("query").orEmpty()
                val text = raw.removeSurrounding("\"").removeSurrounding("'")
                setSearchQuery(text)
            }
        }
        searchReceiver = receiver
        ContextCompat.registerReceiver(
            requireContext(),
            receiver,
            IntentFilter("org.isoron.uhabits.SET_CONTAINER_SEARCH"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        searchReceiver?.let {
            try { requireContext().unregisterReceiver(it) } catch (_: Exception) {}
            searchReceiver = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("query", query)
        outState.putBoolean("pathExpanded", pathExpanded)
    }

    private fun dp(value: Int) = TaskThemeUtils.dpInt(requireContext(), value.toFloat())

    companion object {
        const val RESULT_CONTAINER_ID = "containerId"
        fun newInstance(selectedId: ContainerId?, resultKey: String, datasetId: String) = TaskContainerPickerBottomSheet().apply {
            arguments = Bundle().apply {
                putString(RESULT_CONTAINER_ID, selectedId?.value); putString("resultKey", resultKey); putString("datasetId", datasetId)
            }
        }
    }
}
