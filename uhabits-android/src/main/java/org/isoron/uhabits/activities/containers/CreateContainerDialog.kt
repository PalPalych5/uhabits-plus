/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.containers

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.models.PaletteColor

class CreateContainerDialog : BottomSheetDialogFragment() {

    private fun sectionString(id: Int, vararg args: Any): String {
        val context = requireContext()
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(org.isoron.uhabits.activities.tasks.TaskDueDisplayFormatter.selectedLocale(context))
        }
        return context.createConfigurationContext(config).resources.getString(id, *args)
    }

    var containerToEdit: Container? = null
    var initialParentId: ContainerId? = null
    var initialParentName: String? = null
    var containerQueries: ContainerQueries? = null
    var requestKey: String? = null
    var onSaveWithIcon: ((name: String, parentId: ContainerId?, color: PaletteColor?, icon: IconRef) -> Unit)? = null
    var onSave: ((name: String, parentId: ContainerId?, color: PaletteColor?) -> Unit)? = null

    private var selectedParentId: ContainerId? = null
    private var selectedParentName: String? = null

    private val parentIdState = mutableStateOf<ContainerId?>(null)
    private val parentNameState = mutableStateOf<String?>(null)

    companion object {
        const val ARG_EDIT_ID = "arg_edit_id"
        const val ARG_EDIT_NAME = "arg_edit_name"
        const val ARG_EDIT_COLOR = "arg_edit_color"
        const val ARG_EDIT_ICON = "arg_edit_icon"
        const val ARG_EDIT_PARENT_ID = "arg_edit_parent_id"
        const val ARG_INITIAL_PARENT_ID = "arg_initial_parent_id"
        const val ARG_INITIAL_PARENT_NAME = "arg_initial_parent_name"
        const val ARG_REQUEST_KEY = "arg_request_key"
        const val ARG_SELECTED_PARENT_ID = "arg_selected_parent_id"

        const val RESULT_NAME = "result_name"
        const val RESULT_PARENT_ID = "result_parent_id"
        const val RESULT_COLOR = "result_color"
        const val RESULT_ICON = "result_icon"

        fun newInstance(
            containerToEdit: Container? = null,
            initialParentId: ContainerId? = null,
            initialParentName: String? = null,
            requestKey: String? = null
        ): CreateContainerDialog {
            return CreateContainerDialog().apply {
                arguments = Bundle().apply {
                    containerToEdit?.let {
                        putString(ARG_EDIT_ID, it.id.value)
                        putString(ARG_EDIT_NAME, it.name)
                        it.color?.let { c -> putInt(ARG_EDIT_COLOR, c.paletteIndex) }
                        putString(ARG_EDIT_ICON, it.icon)
                        it.parentId?.let { pid -> putString(ARG_EDIT_PARENT_ID, pid.value) }
                    }
                    initialParentId?.let { putString(ARG_INITIAL_PARENT_ID, it.value) }
                    initialParentName?.let { putString(ARG_INITIAL_PARENT_NAME, it) }
                    requestKey?.let { putString(ARG_REQUEST_KEY, it) }
                }
                this.containerToEdit = containerToEdit
                this.initialParentId = initialParentId
                this.initialParentName = initialParentName
                this.requestKey = requestKey
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bundle = savedInstanceState ?: arguments
        if (bundle != null) {
            val editId = bundle.getString(ARG_EDIT_ID)
            if (editId != null && containerToEdit == null) {
                val queries = containerQueries
                    ?: (requireActivity().application as? HabitsApplication)?.component?.organizationFacade?.containerQueries
                containerToEdit = queries?.find(ContainerId(editId))
            }
            if (initialParentId == null) {
                initialParentId = bundle.getString(ARG_INITIAL_PARENT_ID)?.let(::ContainerId)
            }
            if (initialParentName == null) {
                initialParentName = bundle.getString(ARG_INITIAL_PARENT_NAME)
            }
            requestKey = bundle.getString(ARG_REQUEST_KEY, requestKey)
            if (bundle.containsKey(ARG_SELECTED_PARENT_ID)) {
                selectedParentId = bundle.getString(ARG_SELECTED_PARENT_ID)?.let(::ContainerId)
            }
        }

        val initialPid = selectedParentId ?: containerToEdit?.parentId ?: initialParentId
        parentIdState.value = initialPid
        parentNameState.value = selectedParentName ?: resolveParentName(initialPid)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        containerToEdit?.let {
            outState.putString(ARG_EDIT_ID, it.id.value)
            outState.putString(ARG_EDIT_NAME, it.name)
            it.color?.let { c -> outState.putInt(ARG_EDIT_COLOR, c.paletteIndex) }
            outState.putString(ARG_EDIT_ICON, it.icon)
            it.parentId?.let { pid -> outState.putString(ARG_EDIT_PARENT_ID, pid.value) }
        }
        initialParentId?.let { outState.putString(ARG_INITIAL_PARENT_ID, it.value) }
        initialParentName?.let { outState.putString(ARG_INITIAL_PARENT_NAME, it) }
        requestKey?.let { outState.putString(ARG_REQUEST_KEY, it) }
        selectedParentId?.let { outState.putString(ARG_SELECTED_PARENT_ID, it.value) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                bottomSheet.elevation = 0f
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)
        val composeColors = UHabitComposeColors.fromPalette(palette)

        val edit = containerToEdit
        val initialName = edit?.name ?: ""
        val initialColor = edit?.color ?: PaletteColor(10)
        val initialIcon = IconRef.fromString(edit?.icon)

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UHabitComposeTheme(composeColors) {
                    UHabitContainerFormContent(
                        initialName = initialName,
                        initialParentId = parentIdState.value,
                        initialParentName = parentNameState.value,
                        initialColor = initialColor,
                        initialIcon = initialIcon,
                        isEdit = edit != null,
                        presentation = ContainerFormPresentation.FULL_WITH_PARENT_AND_SWATCHES,
                        onPickParent = { openLocationPicker() },
                        onDismiss = { dismiss() },
                        onSave = { name, parentId, color, icon ->
                            handleSave(name, parentIdState.value ?: parentId, color, icon)
                        }
                    )
                }
            }
        }
    }

    private fun openLocationPicker() {
        val queries = containerQueries
            ?: (requireActivity().application as? HabitsApplication)?.component?.organizationFacade?.containerQueries

        val picker = DrillDownMoveDialog().apply {
            itemName = sectionString(R.string.containers_parent)
            isParentPickerOnly = true
            movingContainerId = containerToEdit?.id
            this.containerQueries = queries
            onTargetSelected = { targetId ->
                selectedParentId = targetId
                val name = resolveParentName(targetId)
                selectedParentName = name
                parentIdState.value = targetId
                parentNameState.value = name
            }
        }
        picker.show(parentFragmentManager, "parent_picker")
    }

    private fun resolveParentName(parentId: ContainerId?): String {
        val queries = containerQueries
            ?: (requireActivity().application as? HabitsApplication)?.component?.organizationFacade?.containerQueries
        return parentId?.let { id ->
            queries?.find(id)?.name
        } ?: sectionString(R.string.containers_root_parent)
    }

    private var isSaving = false

    private fun handleSave(name: String, parentId: ContainerId?, color: PaletteColor?, icon: IconRef) {
        if (isSaving) return
        isSaving = true

        if (onSaveWithIcon != null) {
            onSaveWithIcon?.invoke(name, parentId, color, icon)
        } else {
            onSave?.invoke(name, parentId, color)
        }

        requestKey?.let { key ->
            parentFragmentManager.setFragmentResult(key, Bundle().apply {
                putString(RESULT_NAME, name)
                parentId?.let { putString(RESULT_PARENT_ID, it.value) }
                color?.let { putInt(RESULT_COLOR, it.paletteIndex) }
                putString(RESULT_ICON, IconRef.toStorageString(icon))
            })
        }
        dismiss()
    }
}
