package org.isoron.uhabits.activities.tasks

import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.inject.HabitsApplicationComponent

/** Read-only tree: only an explicit row tap returns a value. */
class TaskContainerPickerBottomSheet : BottomSheetDialogFragment() {
    private lateinit var component: HabitsApplicationComponent
    private lateinit var search: EditText
    private lateinit var rows: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var root: LinearLayout
    private var query = ""
    private var pathExpanded = false
    private var imeVisible = false
    private var searchReceiver: android.content.BroadcastReceiver? = null
    private val palette get() = MainTabsThemeBridge.resolve(requireContext())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        component = (requireContext().applicationContext as HabitsApplication).component
        query = savedInstanceState?.getString("query").orEmpty()
        pathExpanded = savedInstanceState?.getBoolean("pathExpanded") ?: false
    }

    private fun isCurrentDataset() = isAdded &&
        (requireContext().applicationContext as HabitsApplication).component === component &&
        arguments?.getString("datasetId") == component.session.datasetId && component.session.isNextArchitecture

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(palette.surface, 24)
            isFocusableInTouchMode = true
        }
        root.addView(FrameLayout(requireContext()).apply {
            addView(View(context).apply { background = rounded(palette.divider, 4) },
                FrameLayout.LayoutParams(dp(36), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
            addView(label("Раздел", 22f, palette.onSurface, true).apply { gravity = Gravity.CENTER_VERTICAL },
                FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
            val closeAction = FrameLayout(context).apply {
                isClickable = true; isFocusable = true; contentDescription = "Закрыть выбор раздела"
                addView(android.widget.ImageView(context).apply {
                    setImageResource(org.isoron.uhabits.R.drawable.ic_close)
                    imageTintList = android.content.res.ColorStateList.valueOf(palette.onSurfaceVariant)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                setOnClickListener { dismiss() }
            }
            addView(closeAction, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(-12)
            })
        }, LinearLayout.LayoutParams(-1, dp(56)))
        val searchFrame = FrameLayout(requireContext())
        searchFrame.addView(View(requireContext()).apply { background = rounded(palette.surfaceVariant, 8) },
            FrameLayout.LayoutParams(-1, dp(36), Gravity.CENTER_VERTICAL))
        searchFrame.addView(PickerIcon("search", palette.onSurfaceVariant),
            FrameLayout.LayoutParams(dp(38), -1, Gravity.START))
        search = EditText(requireContext()).apply {
            hint = "Найти раздел"
            contentDescription = "Поиск раздела по названию"
            setSingleLine(true)
            textSize = 15f
            includeFontPadding = false
            setPadding(0, 0, dp(4), 0)
            background = null
            setTextColor(palette.onSurface)
            setHintTextColor(palette.onSurfaceVariant)
            setText(query)
        }
        searchFrame.addView(search, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(38); rightMargin = dp(44) })
        val clear = PickerIcon("close_small", palette.onSurfaceVariant).apply {
            contentDescription = "Очистить поиск"
            visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { search.setText("") }
        }
        searchFrame.addView(clear, FrameLayout.LayoutParams(dp(48), -1, Gravity.END))
        val focusLine = View(requireContext()).apply { setBackgroundColor(palette.accent); visibility = View.GONE }
        searchFrame.addView(focusLine, FrameLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(41); leftMargin = dp(8); rightMargin = dp(8)
        })
        search.setOnFocusChangeListener { _, focused -> focusLine.visibility = if (focused) View.VISIBLE else View.GONE }
        root.addView(searchFrame, LinearLayout.LayoutParams(-1, dp(48)))
        rows = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        root.addView(ScrollView(requireContext()).apply { addView(rows); isFillViewport = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                if (isCurrentDataset()) renderRows()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        root.requestFocus()
        if (isCurrentDataset()) renderRows()
        return root
    }

    private fun renderRows() {
        if (!isCurrentDataset()) return
        rows.removeAllViews()
        val selected = arguments?.getString(RESULT_CONTAINER_ID)
        val items = component.organizationFacade.availableContainers()
        val paths = items.associate { it.key to component.organizationFacade.containerPath(ContainerId(it.key)) }
        val searching = query.isNotBlank()
        if (!searching) addRow(null, "Входящие", null, 0, selected == null, false)
        val matching = items.filter { it.name.contains(query.trim(), ignoreCase = true) }
        for (item in matching) {
            val path = paths[item.key].orEmpty()
            val parentPath = path.dropLast(1).joinToString(" / ") { it.name }
            val hasChildren = paths.values.any { candidate -> candidate.dropLast(1).lastOrNull()?.id?.value == item.key }
            addRow(item, item.name, parentPath.takeIf { searching && it.isNotEmpty() },
                if (searching) 0 else (path.size - 1).coerceAtLeast(0), selected == item.key, hasChildren && !searching)
        }
        if (matching.isEmpty()) rows.addView(label(if (searching) "Разделы не найдены" else "Разделов пока нет", 15f, palette.onSurfaceVariant).apply {
            setPadding(0, dp(12), 0, dp(12)); minimumHeight = dp(48)
        })
        renderFooter(items.firstOrNull { it.key == selected })
    }

    private fun addRow(item: OrganizationalItem?, title: String, path: String?, depth: Int, selected: Boolean, parent: Boolean) {
        val searching = query.isNotBlank()
        val indent = if (searching) 0 else 24 + 12 * depth.coerceAtMost(2)
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (!searching && selected && depth >= 3) 64 else if (path != null) 52 else 48)
            isClickable = true; isFocusable = true
            contentDescription = title + if (selected) ", выбран" else ""
            setOnClickListener { select(item) }
        }
        if (!searching) row.addView(FrameLayout(requireContext()).apply {
            if (parent || item == null || depth >= 3) addView(PickerIcon(when {
                item == null -> "inbox"
                parent -> "chevron"
                else -> "branch"
            }, if (depth >= 3 && !parent) palette.border else palette.onSurfaceVariant), FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(indent), dp(48)))
        val text = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        }
        text.addView(label(title, 15f, palette.onSurface, selected || searching).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; minHeight = dp(20)
        })
        if (path != null) text.addView(label(path, 12f, palette.onSurfaceVariant).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0)
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(PickerIcon(if (selected) "check" else "", palette.accent),
            LinearLayout.LayoutParams(dp(24), dp(48)))
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderFooter(selected: OrganizationalItem?) {
        footer.removeAllViews()
        footer.addView(View(requireContext()).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        val searching = query.isNotBlank()
        val fullPath = selected?.let { component.organizationFacade.containerPath(ContainerId(it.key)).joinToString(" / ") { it.name } }.orEmpty()
        val line = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        if (searching) line.addView(PickerIcon("check", palette.accent), LinearLayout.LayoutParams(dp(24), dp(48)))
        line.addView(label(if (searching) selected?.name ?: "Входящие" else fullPath.ifEmpty { "Входящие" }, 12f, palette.onSurfaceVariant).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected != null) line.addView(label(if (pathExpanded) "Скрыть путь" else if (searching) "Путь" else "Показать путь", 12f, palette.onSurfaceVariant, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minimumWidth = dp(if (searching) 48 else 108); minimumHeight = dp(48)
            contentDescription = if (pathExpanded) "Скрыть полный путь" else "Показать полный путь"
            isFocusable = true
            setOnClickListener { pathExpanded = !pathExpanded; renderFooter(selected); updateSheetHeight() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        footer.addView(line, LinearLayout.LayoutParams(-1, dp(if (searching) 48 else 55)))
        if (pathExpanded && selected != null) footer.addView(ScrollView(requireContext()).apply {
            addView(label(fullPath, 12f, palette.onSurfaceVariant).apply { setPadding(0, 0, 0, dp(12)) })
        }, LinearLayout.LayoutParams(-1, dp(96)))
    }

    private fun select(item: OrganizationalItem?) {
        if (!isCurrentDataset()) return
        if (item != null && component.organizationFacade.availableContainers().none { it.key == item.key }) { renderRows(); return }
        parentFragmentManager.setFragmentResult(requireArguments().getString("resultKey")!!,
            Bundle().apply { putString(RESULT_CONTAINER_ID, item?.key) })
        dismiss()
    }

    private var lastInsets: WindowInsetsCompat? = null

    private fun updateSheetHeight() {
        val sheet = dialog as? BottomSheetDialog ?: return
        val surface = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val insets = lastInsets ?: return
        val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = maxOf(system.bottom, ime.bottom)
        root.setPadding(dp(24) + system.left, 0, dp(24) + system.right, bottomInset)
        val available = resources.displayMetrics.heightPixels - system.top - dp(32)
        val contentHeight = dp((if (imeVisible) 220 else 588) + if (pathExpanded) 96 else 0)
        val targetHeight = minOf(contentHeight + bottomInset, available)
        surface.translationY = 0f
        if (surface.layoutParams?.height != targetHeight) {
            surface.layoutParams = surface.layoutParams.apply { height = targetHeight }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isCurrentDataset()) { dismissAllowingStateLoss(); return }
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val surface = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            surface?.background = rounded(palette.surface, 24)
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                lastInsets = insets
                imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                updateSheetHeight()
                insets
            }
            sheet.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (imeVisible) ViewCompat.getWindowInsetsController(root)?.hide(WindowInsetsCompat.Type.ime())
                    else if (query.isNotEmpty()) search.setText("") else dismiss()
                }
            })
        }
        ViewCompat.requestApplyInsets(root)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                intent?.getStringExtra("query")?.let { text ->
                    search.setText(text)
                    search.setSelection(text.length)
                    search.requestFocus()
                    val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        searchReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(),
            receiver,
            android.content.IntentFilter("org.isoron.uhabits.SET_CONTAINER_SEARCH"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
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

    private fun label(value: String, size: Float, color: Int, medium: Boolean = false) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        typeface = Typeface.create("sans-serif" + if (medium) "-medium" else "", Typeface.NORMAL)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = TaskThemeUtils.dpInt(requireContext(), value.toFloat())

    /** Small stroke icons share the application's colors; their surrounding rows own touch targets. */
    private inner class PickerIcon(private val kind: String, color: Int) : View(requireContext()) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            canvas.save()
            canvas.scale(density, density)
            val cx = width / density / 2f
            val cy = height / density / 2f
            stroke.strokeWidth = 2f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, stroke)
            when (kind) {
                "close_small" -> { val s = 4.5f; line(cx - s, cy - s, cx + s, cy + s); line(cx + s, cy - s, cx - s, cy + s) }
                "close" -> { val s = 6f; line(cx - s, cy - s, cx + s, cy + s); line(cx + s, cy - s, cx - s, cy + s) }
                "search" -> { canvas.drawCircle(cx - 1, cy - 1, 4f, stroke); line(cx + 2, cy + 2, cx + 6, cy + 6) }
                "check" -> { line(cx - 5, cy, cx - 1, cy + 4); line(cx - 1, cy + 4, cx + 6, cy - 4) }
                "chevron" -> { val x = width / density - 18; line(x - 3, cy - 1, x, cy + 2); line(x, cy + 2, x + 3, cy - 1) }
                "branch" -> { stroke.strokeWidth = 1f; val x = width / density - 20; line(x, 0f, x, cy); line(x, cy, x + 12, cy) }
                "inbox" -> {
                    val x = 10f
                    canvas.drawPath(Path().apply {
                        moveTo(x - 7, cy + 4); lineTo(x - 7, cy); lineTo(x - 3, cy - 5)
                        lineTo(x + 3, cy - 5); lineTo(x + 7, cy); lineTo(x + 7, cy + 4); close()
                    }, stroke)
                    line(x - 7, cy, x - 2, cy); line(x - 2, cy, x - 1, cy + 2)
                    line(x - 1, cy + 2, x + 1, cy + 2); line(x + 1, cy + 2, x + 2, cy); line(x + 2, cy, x + 7, cy)
                }
            }
            canvas.restore()
        }
    }

    companion object {
        const val RESULT_CONTAINER_ID = "containerId"
        fun newInstance(selectedId: ContainerId?, resultKey: String, datasetId: String) = TaskContainerPickerBottomSheet().apply {
            arguments = Bundle().apply {
                putString(RESULT_CONTAINER_ID, selectedId?.value); putString("resultKey", resultKey); putString("datasetId", datasetId)
            }
        }
    }
}
