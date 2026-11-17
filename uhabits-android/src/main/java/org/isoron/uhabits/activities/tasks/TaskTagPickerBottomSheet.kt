package org.isoron.uhabits.activities.tasks

import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
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
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import org.isoron.uhabits.inject.HabitsApplicationComponent

/** Global tag management; assignment/filter selection is a draft until Apply. */
class TaskTagPickerBottomSheet : BottomSheetDialogFragment() {
    private lateinit var component: HabitsApplicationComponent
    private lateinit var rows: LinearLayout
    private lateinit var search: EditText
    private val selected = linkedSetOf<String>()
    private var applied = false
    private var query = ""
    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    private fun current() = isAdded && HabitsApplication.component === component &&
        arguments?.getString("datasetId") == component.session.datasetId && component.session.isNextArchitecture

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        component = HabitsApplication.component
        selected.addAll((state ?: arguments)?.getStringArrayList(RESULT_TAG_IDS).orEmpty())
        query = state?.getString("query").orEmpty()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = rounded(palette.dialogSurface, 24).apply {
                if (palette.isPureBlack) {
                    setStroke(dp(1).coerceAtLeast(1), palette.dialogBorder)
                }
            }
        }
        root.addView(label(getString(if (arguments?.getBoolean("filter") == true) R.string.task_tags_filter_desc else R.string.task_tags_label), 22f, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(label(getString(R.string.task_tag_picker_subtitle), 13f).apply {
            setTextColor(palette.onSurfaceVariant); setPadding(0, 0, 0, dp(8))
        })
        search = EditText(requireContext()).apply {
            hint = getString(R.string.task_tag_picker_search_hint); contentDescription = getString(R.string.task_tag_picker_search_desc)
            setSingleLine(); textSize = 15f; setTextColor(palette.onSurface)
            setHintTextColor(palette.onSurfaceVariant)
            background = rounded(palette.surfaceVariant, 8)
            setPadding(dp(12), 0, dp(12), 0); setText(query)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        rows = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(requireContext()).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(action(getString(R.string.task_tag_picker_clear_selection)) { selected.clear(); render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(action(getString(R.string.task_due_apply)) {
            if (current()) { applied = true; dismiss() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(footer)
        search.doAfterTextChanged { query = it?.toString().orEmpty(); render() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(dp(24) + bars.left, dp(12), dp(24) + bars.right, maxOf(bars.bottom, ime.bottom) + dp(12))
            insets
        }
        root.isFocusableInTouchMode = true; root.requestFocus()
        render()
        return root
    }

    private fun render() {
        if (!current() || !::rows.isInitialized) return
        rows.removeAllViews()
        val all = component.taskTagRepository.getAll()
        selected.retainAll(all.map { it.id }.toSet())
        val matches = all.filter { it.name.contains(query.trim(), ignoreCase = true) }
        for (tag in matches) {
            val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52) }
            val check = CheckBox(requireContext()).apply {
                text = tag.name; textSize = 15f; setTextColor(palette.onSurface)
                buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                isChecked = tag.id in selected; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                contentDescription = getString(R.string.task_tag_picker_tag_desc, tag.name)
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(tag.id) else selected.remove(tag.id) }
            }
            row.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(action("⋮") { anchor -> showActions(anchor, tag) }.apply {
                contentDescription = getString(R.string.task_tag_picker_actions_desc, tag.name)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            rows.addView(row)
        }
        if (matches.isEmpty()) rows.addView(label(getString(if (query.isBlank()) R.string.task_tag_picker_empty else R.string.task_tag_picker_not_found), 14f).apply {
            setTextColor(palette.onSurfaceVariant); setPadding(0, dp(20), 0, dp(16))
        })
        val createLabel = if (query.isBlank()) {
            getString(R.string.task_tag_picker_create_new)
        } else {
            getString(R.string.task_tag_picker_create_named, query.trim().take(40))
        }
        rows.addView(action(createLabel) {
            editName(null)
        })
    }

    private fun showActions(anchor: View, tag: TaskTag) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.task_tag_picker_rename).setOnMenuItemClickListener { editName(tag); true }
            menu.add(R.string.delete).setOnMenuItemClickListener {
                CustomDialogs.showConfirmDialog(
                    context = requireContext(),
                    title = getString(R.string.task_tag_picker_delete_title, tag.name),
                    message = getString(R.string.task_tag_picker_delete_message),
                    isDestructive = true,
                    positiveText = getString(R.string.delete)
                ) {
                    if (current()) try {
                        component.taskTagRepository.delete(tag.id); selected.remove(tag.id); render()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(requireContext(), R.string.task_tag_picker_delete_failed, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                true
            }
            show()
        }
    }

    private fun editName(tag: TaskTag?) {
        val dialog = CustomDialogs.showInputDialog(
            context = requireContext(),
            title = getString(if (tag == null) R.string.task_tag_picker_new_title else R.string.task_tag_picker_rename_title),
            initialValue = tag?.name ?: query.trim(),
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
                if (tag == null) selected.add(saved.id)
                search.setText(""); render(); dialog.dismiss()
            } catch (e: IllegalArgumentException) {
                inputLayout?.error = e.message ?: getString(R.string.task_tag_picker_duplicate_error)
            } catch (e: Exception) {
                inputLayout?.error = getString(R.string.task_tag_picker_save_failed)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.layoutParams.height = minOf(dp(580), resources.displayMetrics.heightPixels - dp(48))
            BottomSheetBehavior.from(it).apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
            ViewCompat.requestApplyInsets(requireView())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(RESULT_TAG_IDS, ArrayList(selected)); outState.putString("query", query)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (current()) parentFragmentManager.setFragmentResult(requireArguments().getString("resultKey")!!, Bundle().apply {
            putBoolean(RESULT_APPLIED, applied)
            putStringArrayList(RESULT_TAG_IDS, ArrayList(selected))
        })
        super.onDismiss(dialog)
    }

    private fun action(text: String, click: (View) -> Unit) = label(text, 14f, true).apply {
        minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setTextColor(palette.accent)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; isFocusable = true; setOnClickListener(click)
    }
    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(palette.onSurface); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = TaskThemeUtils.dpInt(requireContext(), value.toFloat())

    companion object {
        const val RESULT_TAG_IDS = "tagIds"
        const val RESULT_APPLIED = "applied"
        fun newInstance(ids: Set<String>, resultKey: String, datasetId: String, filter: Boolean = false) = TaskTagPickerBottomSheet().apply {
            arguments = Bundle().apply {
                putStringArrayList(RESULT_TAG_IDS, ArrayList(ids)); putString("resultKey", resultKey)
                putString("datasetId", datasetId); putBoolean("filter", filter)
            }
        }
    }
}
