/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import org.isoron.uhabits.inject.HabitsApplicationComponent

/** Global tag management; assignment/filter selection is a draft until Apply. */
class TaskTagPickerBottomSheet : BottomSheetDialogFragment() {
    private lateinit var component: HabitsApplicationComponent
    private var applied = false
    private var tagsState by mutableStateOf<List<TaskTag>>(emptyList())
    private var selectedIdsState by mutableStateOf<Set<String>>(emptySet())
    private var queryState by mutableStateOf("")
    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    private fun current() = isAdded && HabitsApplication.component === component &&
        arguments?.getString("datasetId") == component.session.datasetId && component.session.isNextArchitecture

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        component = HabitsApplication.component
        selectedIdsState = LinkedHashSet((state ?: arguments)?.getStringArrayList(RESULT_TAG_IDS).orEmpty())
        queryState = state?.getString("query").orEmpty()
    }

    private fun reloadTags() {
        if (!current()) return
        val all = component.taskTagRepository.getAll()
        val validIds = all.mapTo(HashSet()) { it.id }
        tagsState = all
        selectedIdsState = selectedIdsState.filterTo(LinkedHashSet()) { it in validIds }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        reloadTags()
        val isFilter = arguments?.getBoolean("filter") == true
        return ComposeView(requireContext()).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(dp(24) + bars.left, dp(12), dp(24) + bars.right, maxOf(bars.bottom, ime.bottom) + dp(12))
                insets
            }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = UHabitComposeColors.fromPalette(palette)
                UHabitComposeTheme(colors = colors) {
                    TaskTagPickerContent(
                        title = getString(if (isFilter) R.string.task_tags_filter_desc else R.string.task_tags_label),
                        tags = tagsState,
                        selectedIds = selectedIdsState,
                        query = queryState,
                        onQueryChange = { queryState = it },
                        onToggleTag = { id ->
                            selectedIdsState = toggleTagSelection(selectedIdsState, id)
                        },
                        onRenameTag = { tag -> editName(tag) },
                        onDeleteTag = { tag -> confirmDelete(tag) },
                        onCreateTag = { editName(null) },
                        onClearSelection = { selectedIdsState = emptySet() },
                        onApply = {
                            if (current()) {
                                applied = true
                                dismiss()
                            }
                        },
                        colors = colors
                    )
                }
            }
        }
    }

    private fun confirmDelete(tag: TaskTag) {
        CustomDialogs.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.task_tag_picker_delete_title, tag.name),
            message = getString(R.string.task_tag_picker_delete_message),
            isDestructive = true,
            positiveText = getString(R.string.delete)
        ) {
            if (current()) try {
                component.taskTagRepository.delete(tag.id)
                selectedIdsState = selectedIdsState.filterTo(LinkedHashSet()) { it != tag.id }
                reloadTags()
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), R.string.task_tag_picker_delete_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun editName(tag: TaskTag?) {
        val dialog = CustomDialogs.showInputDialog(
            context = requireContext(),
            title = getString(if (tag == null) R.string.task_tag_picker_new_title else R.string.task_tag_picker_rename_title),
            initialValue = tag?.name ?: queryState.trim(),
            hint = getString(R.string.task_tag_picker_name_hint),
            positiveText = getString(R.string.save)
        ) {}
        val inputLayout = dialog.findViewById<TextInputLayout>(R.id.input_layout)
        val input = dialog.findViewById<TextInputEditText>(R.id.input_edit_text)
        input?.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            setSelectAllOnFocus(true)
            doAfterTextChanged { inputLayout?.error = null }
        }
        dialog.findViewById<Button>(R.id.button_positive)?.setOnClickListener {
            if (!current()) { dialog.dismiss(); return@setOnClickListener }
            try {
                val name = input?.text?.toString()?.trim().orEmpty()
                require(name.isNotEmpty()) { getString(R.string.task_tag_picker_name_required) }
                val saved = if (tag == null) component.taskTagRepository.create(name)
                    else component.taskTagRepository.rename(tag.id, name)
                if (tag == null) {
                    selectedIdsState = LinkedHashSet(selectedIdsState).apply { add(saved.id) }
                }
                queryState = ""
                reloadTags()
                dialog.dismiss()
            } catch (e: IllegalArgumentException) {
                inputLayout?.error = e.message ?: getString(R.string.task_tag_picker_duplicate_error)
            } catch (e: Exception) {
                inputLayout?.error = getString(R.string.task_tag_picker_save_failed)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), R.style.ThemeOverlay_UHabit_NativeSurfaceTaskPickerBottomSheet)

    override fun onStart() {
        super.onStart()
        val sheet = dialog as? BottomSheetDialog ?: return
        val palette = palette
        CustomDialogs.styleBottomSheet(sheet, requireContext(), palette, cornerRadiusDp = 24f)
        sheet.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightNavigationBars = !palette.isDark
        }
        val surface = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        surface.layoutParams.height = minOf(dp(580), resources.displayMetrics.heightPixels - dp(48))
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        ViewCompat.requestApplyInsets(requireView())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(RESULT_TAG_IDS, ArrayList(selectedIdsState))
        outState.putString("query", queryState)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (current()) parentFragmentManager.setFragmentResult(requireArguments().getString("resultKey")!!, Bundle().apply {
            putBoolean(RESULT_APPLIED, applied)
            putStringArrayList(RESULT_TAG_IDS, ArrayList(selectedIdsState))
        })
        super.onDismiss(dialog)
    }

    private fun dp(value: Int) = TaskThemeUtils.dpInt(requireContext(), value.toFloat())

    companion object {
        const val RESULT_TAG_IDS = "tagIds"
        const val RESULT_APPLIED = "applied"
        fun newInstance(ids: Set<String>, resultKey: String, datasetId: String, filter: Boolean = false) = TaskTagPickerBottomSheet().apply {
            arguments = Bundle().apply {
                putStringArrayList(RESULT_TAG_IDS, ArrayList(ids))
                putString("resultKey", resultKey)
                putString("datasetId", datasetId)
                putBoolean("filter", filter)
            }
        }
    }
}
