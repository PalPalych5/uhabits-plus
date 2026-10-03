/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.containers

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.utils.currentTheme

class DrillDownMoveDialog : BottomSheetDialogFragment() {

    var itemName: String = ""
    var movingContainerId: ContainerId? = null
    var isParentPickerOnly: Boolean = false
    var containerQueries: ContainerQueries? = null
    var onTargetSelected: ((ContainerId?) -> Unit)? = null

    private var currentParentId: ContainerId? = null
    private val navigationHistory = mutableListOf<ContainerId?>()
    private val disabledSubtreeIds = mutableSetOf<ContainerId>()

    private lateinit var breadcrumbText: TextView
    private lateinit var btnSelectCurrent: Button
    private lateinit var btnSelectRoot: Button
    private lateinit var btnBack: Button
    private lateinit var itemsContainer: LinearLayout
    private lateinit var searchInput: EditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val palette = MainTabsThemeBridge.resolve(context)
        val queries = containerQueries

        if (movingContainerId != null && queries != null) {
            disabledSubtreeIds.add(movingContainerId!!)
            disabledSubtreeIds.addAll(queries.subtreeIds(movingContainerId!!))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }

        val titleView = TextView(context).apply {
            text = if (isParentPickerOnly) {
                getString(R.string.containers_parent)
            } else {
                getString(R.string.containers_move_picker_title, itemName)
            }
            setTextColor(palette.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12f).toInt())
        }
        root.addView(titleView)

        searchInput = EditText(context).apply {
            hint = getString(R.string.containers_move_picker_search)
            setHintTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.6f))
            setTextColor(palette.onSurface)
            textSize = 14f
            val padY = dp(10f).toInt()
            val padX = dp(12f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(8f)
                setStroke(dp(1f).toInt(), palette.border)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshContent(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(searchInput)

        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f).toInt(), 0, dp(8f).toInt())
        }

        btnBack = Button(context).apply {
            text = "‹"
            textSize = 18f
            setTextColor(palette.accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (navigationHistory.isNotEmpty()) {
                    currentParentId = navigationHistory.removeAt(navigationHistory.size - 1)
                    refreshContent(searchInput.text.toString())
                }
            }
        }
        navBar.addView(btnBack)

        breadcrumbText = TextView(context).apply {
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
            setTypeface(typeface, Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        navBar.addView(breadcrumbText)
        root.addView(navBar)

        val actionButtons = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f).toInt(), 0, dp(12f).toInt())
        }

        btnSelectCurrent = Button(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = dp(8f)
            }
            setOnClickListener {
                confirmSelection(currentParentId)
            }
        }
        actionButtons.addView(btnSelectCurrent)

        btnSelectRoot = Button(context).apply {
            text = getString(R.string.containers_move_to_root)
            setTextColor(palette.onSurface)
            textSize = 13f
            val bg = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(8f)
            }
            background = bg
            setOnClickListener {
                confirmSelection(null)
            }
        }
        val rootLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, dp(6f).toInt(), 0, 0)
        }
        actionButtons.addView(btnSelectRoot, rootLp)
        root.addView(actionButtons)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(260f).toInt())
            isVerticalScrollBarEnabled = false
        }
        itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(itemsContainer)
        root.addView(scrollView)

        refreshContent("")

        return root
    }

    private fun confirmSelection(targetId: ContainerId?) {
        dismiss()
        onTargetSelected?.invoke(targetId)
    }

    private fun refreshContent(query: String) {
        val context = context ?: return
        val palette = MainTabsThemeBridge.resolve(context)
        val queries = containerQueries ?: return

        itemsContainer.removeAllViews()

        if (query.isNotBlank()) {
            btnBack.visibility = View.GONE
            breadcrumbText.text = getString(R.string.search)
            btnSelectCurrent.visibility = View.GONE
            btnSelectRoot.visibility = View.GONE

            val hits = queries.search(query)
            if (hits.isEmpty()) {
                val empty = TextView(context).apply {
                    text = getString(R.string.containers_search_no_results)
                    setTextColor(palette.onSurfaceVariant)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24f).toInt(), 0, 0)
                }
                itemsContainer.addView(empty)
                return
            }

            for (hit in hits) {
                val container = hit.container
                val pathNames = hit.path.nodes.mapNotNull { queries.find(it)?.name }
                val isSelfOrDescendant = disabledSubtreeIds.contains(container.id)

                val row = buildContainerRow(
                    container = container,
                    subtitle = pathNames.joinToString(" / "),
                    isDisabled = isSelfOrDescendant,
                    palette = palette,
                    onRowClicked = {
                        if (!isSelfOrDescendant) {
                            confirmSelection(container.id)
                        }
                    }
                )
                itemsContainer.addView(row)
            }
            return
        }

        // Drill-down mode
        btnBack.visibility = if (navigationHistory.isNotEmpty()) View.VISIBLE else View.GONE

        val currentContainer = currentParentId?.let { queries.find(it) }
        val currentName = currentContainer?.name ?: getString(R.string.containers_root_parent)
        breadcrumbText.text = currentName

        val isCurrentDisabled = currentParentId != null && disabledSubtreeIds.contains(currentParentId!!)
        btnSelectCurrent.isEnabled = !isCurrentDisabled
        btnSelectCurrent.text = if (isCurrentDisabled) {
            getString(R.string.containers_move_cycle_error)
        } else {
            getString(R.string.containers_move_to_current, currentName)
        }

        btnSelectCurrent.visibility = if (currentParentId != null) View.VISIBLE else View.GONE
        btnSelectRoot.visibility = if (currentParentId != null) View.VISIBLE else View.VISIBLE

        val children = if (currentParentId == null) {
            queries.roots(includeArchived = false)
        } else {
            queries.children(currentParentId!!, includeArchived = false)
        }

        if (children.isEmpty()) {
            val empty = TextView(context).apply {
                text = getString(R.string.containers_no_subsections)
                setTextColor(palette.onSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(0, dp(24f).toInt(), 0, 0)
            }
            itemsContainer.addView(empty)
            return
        }

        for (child in children) {
            val isSelfOrDescendant = disabledSubtreeIds.contains(child.id)
            val subCount = queries.children(child.id, includeArchived = false).size
            val subText = if (isSelfOrDescendant) {
                getString(R.string.containers_move_cycle_error)
            } else if (subCount > 0) {
                getString(R.string.containers_count_subsections, subCount)
            } else {
                null
            }

            val row = buildContainerRow(
                container = child,
                subtitle = subText,
                isDisabled = isSelfOrDescendant,
                palette = palette,
                onRowClicked = {
                    if (!isSelfOrDescendant) {
                        navigationHistory.add(currentParentId)
                        currentParentId = child.id
                        refreshContent("")
                    }
                }
            )
            itemsContainer.addView(row)
        }
    }

    private fun buildContainerRow(
        container: Container,
        subtitle: String?,
        isDisabled: Boolean,
        palette: SettingsThemePalette,
        onRowClicked: () -> Unit
    ): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            val padY = dp(10f).toInt()
            val padX = dp(12f).toInt()
            setPadding(padX, padY, padX, padY)
            alpha = if (isDisabled) 0.45f else 1.0f

            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(palette.surface)
            }

            if (!isDisabled) {
                setOnClickListener { onRowClicked() }
            }
        }

        val dot = View(context).apply {
            val size = dp(10f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, dp(12f).toInt(), 0)
            }
            val colorVal = container.color?.let {
                currentTheme().color(it).toInt()
            } ?: palette.accent
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorVal)
            }
        }
        row.addView(dot)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val nameView = TextView(context).apply {
            text = container.name
            setTextColor(if (isDisabled) palette.onSurfaceVariant else palette.onSurface)
            textSize = 15f
            setTypeface(typeface, Typeface.NORMAL)
        }
        textCol.addView(nameView)

        if (!subtitle.isNullOrBlank()) {
            val subView = TextView(context).apply {
                text = subtitle
                setTextColor(palette.onSurfaceVariant)
                textSize = 12f
            }
            textCol.addView(subView)
        }
        row.addView(textCol)

        if (!isDisabled) {
            val chevron = TextView(context).apply {
                text = "›"
                textSize = 18f
                setTextColor(palette.onSurfaceVariant)
            }
            row.addView(chevron)
        }

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(6f).toInt())
        }
        return row.apply { layoutParams = lp }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
}
