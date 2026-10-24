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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.HabitsApplication
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
            background = rounded(palette.surface, 24)
        }
        root.addView(label(if (arguments?.getBoolean("filter") == true) "Фильтр по тегам" else "Теги", 22f, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(label("Общие для задач во всех разделах", 13f).apply {
            setTextColor(palette.onSurfaceVariant); setPadding(0, 0, 0, dp(8))
        })
        search = EditText(requireContext()).apply {
            hint = "Найти или создать тег"; contentDescription = "Поиск тегов"
            setSingleLine(); textSize = 15f; setTextColor(palette.onSurface)
            setHintTextColor(palette.onSurfaceVariant)
            background = rounded(palette.surfaceVariant, 8)
            setPadding(dp(12), 0, dp(12), 0); setText(query)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        rows = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(requireContext()).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(action("Сбросить выбор") { selected.clear(); render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(action("Применить") {
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
                contentDescription = "Тег: ${tag.name}"
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(tag.id) else selected.remove(tag.id) }
            }
            row.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(action("⋮") { anchor -> showActions(anchor, tag) }.apply {
                contentDescription = "Действия с тегом ${tag.name}"
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            rows.addView(row)
        }
        if (matches.isEmpty()) rows.addView(label(if (query.isBlank()) "Тегов пока нет" else "Теги не найдены", 14f).apply {
            setTextColor(palette.onSurfaceVariant); setPadding(0, dp(20), 0, dp(16))
        })
        rows.addView(action(if (query.isBlank()) "+ Создать тег" else "+ Создать «${query.trim().take(40)}»") {
            editName(null)
        })
    }

    private fun showActions(anchor: View, tag: TaskTag) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Переименовать").setOnMenuItemClickListener { editName(tag); true }
            menu.add("Удалить").setOnMenuItemClickListener {
                AlertDialog.Builder(requireContext()).setTitle("Удалить тег «${tag.name}»?")
                    .setMessage("Тег будет снят со всех задач. Задачи сохранятся.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                        if (current()) try {
                            component.taskTagRepository.delete(tag.id); selected.remove(tag.id); render()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(requireContext(), "Не удалось удалить тег", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.show()
                true
            }
            show()
        }
    }

    private fun editName(tag: TaskTag?) {
        val input = EditText(requireContext()).apply {
            setSingleLine(); hint = "Название тега"; setText(tag?.name ?: query.trim())
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle(if (tag == null) "Новый тег" else "Название тега")
            .setView(input).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!current()) { dialog.dismiss(); return@setOnClickListener }
                try {
                    val name = input.text.toString().trim()
                    require(name.isNotEmpty()) { "Введите название тега" }
                    val saved = if (tag == null) component.taskTagRepository.create(name)
                        else component.taskTagRepository.rename(tag.id, name)
                    if (tag == null) selected.add(saved.id)
                    search.setText(""); render(); dialog.dismiss()
                } catch (e: IllegalArgumentException) { input.error = e.message ?: "Такой тег уже существует" }
                catch (e: Exception) { input.error = "Не удалось сохранить тег" }
            }
        }
        dialog.show()
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
