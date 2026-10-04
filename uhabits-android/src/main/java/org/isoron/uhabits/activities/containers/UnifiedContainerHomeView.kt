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

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentManager
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.activities.common.icons.IconPickerBottomSheet
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonViewFactory
import org.isoron.uhabits.activities.habits.list.views.CheckmarkPanelViewFactory
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.NumberButtonViewFactory
import org.isoron.uhabits.activities.habits.list.views.NumberPanelViewFactory
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.IconRef
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp

class UnifiedContainerHomeView(
    context: Context,
    val fragmentManager: FragmentManager,
    val facade: HabitOrganizationFacade,
    val habitList: HabitList,
    val listHabitsBehavior: ListHabitsBehavior,
    val prefs: Preferences,
    val onHabitClicked: (Habit) -> Unit
) : FrameLayout(context), ModelObservable.Listener {

    private fun getString(resId: Int): String = context.getString(resId)
    private fun getString(resId: Int, vararg formatArgs: Any): String = context.getString(resId, *formatArgs)

    private val palette get() = MainTabsThemeBridge.resolve(context)

    var focusedContainerId: ContainerId? = null
    val expandedContainerIds = mutableSetOf<ContainerId>()
    var scopeMode: ScopeMode = ScopeMode.HERE
    var searchQuery: String = ""
    var isArchiveOpen: Boolean = false

    var onFocusedContainerChanged: ((ContainerId?) -> Unit)? = null

    enum class ScopeMode { HERE, SUBTREE }

    private val scrollView: NestedScrollView
    private val contentLayout: LinearLayout

    init {
        setBackgroundColor(palette.background)
        scrollView = NestedScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }

        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
            val padX = dp(16f).toInt()
            val padY = dp(12f).toInt()
            setPadding(padX, padY, padX, dp(32f).toInt())
        }

        scrollView.addView(contentLayout, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(scrollView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        habitList.observable.addListener(this)
    }

    override fun onDetachedFromWindow() {
        habitList.observable.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onModelChange() {
        post { render() }
    }

    fun focusOn(containerId: ContainerId?, forward: Boolean = true) {
        focusedContainerId = containerId
        isArchiveOpen = false
        onFocusedContainerChanged?.invoke(containerId)
        animateTransition(forward)
    }

    fun handleBack(): Boolean {
        if (searchQuery.isNotBlank()) {
            searchQuery = ""
            animateTransition(forward = false)
            return true
        }
        if (isArchiveOpen) {
            isArchiveOpen = false
            animateTransition(forward = false)
            return true
        }
        val current = focusedContainerId
        if (current != null) {
            val path = facade.containerPath(current)
            if (path.size > 1) {
                focusOn(path[path.size - 2].id, forward = false)
            } else {
                focusOn(null, forward = false)
            }
            return true
        }
        return false
    }

    fun setSearch(query: String) {
        searchQuery = query.trim()
        render()
    }

    private fun animateTransition(forward: Boolean = true) {
        contentLayout.animate().cancel()
        contentLayout.alpha = 0.3f
        contentLayout.translationY = if (forward) dp(12f) else -dp(12f)
        contentLayout.scaleX = 0.995f
        contentLayout.scaleY = 0.995f
        render()
        contentLayout.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    fun render() {
        contentLayout.removeAllViews()

        if (searchQuery.isNotBlank()) {
            renderSearch()
            return
        }

        if (isArchiveOpen) {
            renderArchive()
            return
        }

        val focused = focusedContainerId
        if (focused != null) {
            renderFocused(focused)
        } else {
            renderRootHome()
        }
    }

    // ========================================================================
    // Root Home Rendering
    // ========================================================================

    private fun renderRootHome() {
        // 1. Header Bar: "Разделы" + "+ Раздел" button
        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14f).toInt())
        }

        val titleView = TextView(context).apply {
            text = getString(R.string.containers_title)
            setTextColor(palette.onSurface)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        headerBar.addView(titleView)

        val addSectionBtn = buildPillButton(
            text = "+ " + getString(R.string.containers_create_section),
            isPrimary = true
        ) {
            showCreateContainerDialog(parentId = null)
        }
        headerBar.addView(addSectionBtn)
        contentLayout.addView(headerBar)

        // 2. Root Containers List (Living Context, ZERO CARDS!)
        val roots = facade.rootContainerModels()
        if (roots.isEmpty()) {
            val emptyCard = buildEmptyCard(
                title = getString(R.string.containers_title),
                subtitle = getString(R.string.containers_no_subsections),
                actionText = getString(R.string.containers_create_section)
            ) {
                showCreateContainerDialog(parentId = null)
            }
            contentLayout.addView(emptyCard)
        } else {
            for (root in roots) {
                val isExpanded = expandedContainerIds.contains(root.id)
                val item = buildRootContainerItem(root, isExpanded)
                contentLayout.addView(item)
            }
        }

        // 3. System Section: "Без раздела"
        val allHabits = habitList.toList()
        val unassignedHabits = allHabits.filter { h ->
            val placement = facade.getPlacementInfo(h.uuid ?: "", h.blockId)
            placement.containerId == null
        }

        if (unassignedHabits.isNotEmpty()) {
            contentLayout.addView(buildSectionHeader(
                title = getString(R.string.containers_unassigned),
                count = unassignedHabits.size
            ))

            for (habit in unassignedHabits) {
                val card = buildHabitCard(habit)
                contentLayout.addView(card)
            }
        }

        // 4. Archive Section Entry
        val archivedRoots = facade.archivedRoots()
        if (archivedRoots.isNotEmpty()) {
            contentLayout.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(12f).toInt())
            })

            val archiveRow = buildNavRowOpen(
                title = getString(R.string.containers_archive),
                subtitle = getString(R.string.containers_archive_count, archivedRoots.size),
                color = null,
                iconRef = IconRef.Vector("archive"),
                chevron = "›",
                isMuted = true
            ) {
                isArchiveOpen = true
                animateTransition(forward = true)
            }
            contentLayout.addView(archiveRow)
        }
    }

    private fun buildRootContainerItem(root: Container, isExpanded: Boolean): View {
        if (!isExpanded) {
            return buildRootHeaderRow(root, isExpanded = false).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, dp(4f).toInt())
            }
        }

        // Expanded: FrameLayout wrapper with single 2dp accent rail spanning from header through expanded items
        val activeColor = root.color?.let { currentTheme().color(it).toInt() } ?: palette.accent
        val wrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8f).toInt())
            }
        }

        val rail = View(context).apply {
            background = GradientDrawable().apply {
                setColor(activeColor)
                cornerRadius = dp(1f)
            }
            layoutParams = FrameLayout.LayoutParams(dp(2f).toInt(), MATCH_PARENT).apply {
                topMargin = dp(4f).toInt()
                bottomMargin = dp(4f).toInt()
                gravity = Gravity.START
            }
        }
        wrapper.addView(rail)

        val contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(8f).toInt()
            }
        }

        // Header row
        val headerRow = buildRootHeaderRow(root, isExpanded = true)
        contentCol.addView(headerRow)

        // Expanded Body (direct subcontainers + direct habits) - NO secondary tree border line!
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f).toInt(), dp(2f).toInt(), 0, dp(4f).toInt())
        }

        val children = facade.childrenOf(root.id)
        for (child in children) {
            val subOverview = facade.containerOverview(child.id)
            val countText = getString(R.string.containers_count_habits_inside, subOverview?.totalHabitCount ?: 0)
            val subRow = buildNavRowOpen(
                title = child.name,
                subtitle = countText,
                color = child.color,
                iconRef = IconRef.fromString(child.icon),
                chevron = "›",
                isSmall = true
            ) {
                focusOn(child.id, forward = true)
            }
            body.addView(subRow)
        }

        val directHabitUuids = facade.getHabitUuidsForContainer(root.id.value, subtree = false)
        val habits = habitList.toList().filter { directHabitUuids.contains(it.uuid) }
        for (habit in habits) {
            val card = buildHabitCard(habit)
            body.addView(card)
        }

        contentCol.addView(body)
        wrapper.addView(contentCol)
        return wrapper
    }

    private fun buildRootHeaderRow(
        container: Container,
        isExpanded: Boolean
    ): View {
        val overview = facade.containerOverview(container.id)
        val directSubCount = overview?.directChildrenCount ?: 0
        val habitCount = overview?.totalHabitCount ?: 0
        val hasExpandableContent = directSubCount > 0 || (overview?.directHabitCount ?: 0) > 0
        val activeColor = container.color?.let { currentTheme().color(it).toInt() } ?: palette.accent
        val iconRef = IconRef.fromString(container.icon)
        val iconEntry = (iconRef as? IconRef.Vector)?.let { IconPack.getIcon(it.key) }
        val hasIcon = when (iconRef) {
            is IconRef.Vector -> iconEntry != null
            is IconRef.Emoji -> iconRef.value.isNotBlank()
            else -> false
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8f)
            }
            setOnClickListener {
                focusOn(container.id, forward = true)
            }
            setOnLongClickListener {
                showContainerPopupMenu(this, container)
                true
            }
        }

        // Left Disclosure Button
        if (hasExpandableContent) {
            val chevronBtn = TextView(context).apply {
                text = if (isExpanded) "▼" else "▶"
                textSize = 11f
                setTextColor(palette.onSurfaceVariant)
                gravity = Gravity.CENTER
                val size = dp(28f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, dp(2f).toInt(), 0)
                }
                setOnClickListener {
                    if (isExpanded) {
                        expandedContainerIds.remove(container.id)
                    } else {
                        expandedContainerIds.add(container.id)
                    }
                    render()
                }
            }
            row.addView(chevronBtn)
        } else {
            val emptySpace = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28f).toInt(), 1)
            }
            row.addView(emptySpace)
        }

        // Icon Tile or Color Accent Dot (No forced folder fallback!)
        if (hasIcon) {
            val tile = buildIconTile(
                iconRef = iconRef,
                color = container.color,
                sizeDp = 36f,
                cornerDp = 9f
            )
            row.addView(tile)
        } else {
            val colorDot = View(context).apply {
                val dotSize = dp(8f).toInt()
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dp(4f).toInt(), 0, dp(8f).toInt(), 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(activeColor)
                }
            }
            row.addView(colorDot)
        }

        // Text Column
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        val titleText = TextView(context).apply {
            text = container.name
            setTextColor(palette.onSurface)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        val subText = TextView(context).apply {
            text = if (directSubCount > 0) {
                getString(R.string.containers_count_composite, directSubCount, habitCount)
            } else {
                getString(R.string.containers_count_habits_inside, habitCount)
            }
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            maxLines = 1
        }
        textCol.addView(titleText)
        textCol.addView(subText)
        row.addView(textCol)

        // Right Drill-Down Chevron
        val drillChevron = TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(palette.onSurfaceVariant)
        }
        row.addView(drillChevron)

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return row.apply { layoutParams = lp }
    }

    // ========================================================================
    // Focused Container Rendering
    // ========================================================================

    private fun renderFocused(containerId: ContainerId) {
        val overview = facade.containerOverview(containerId)
        val container = overview?.container ?: run {
            focusOn(null, forward = false)
            return
        }

        val activeColor = container.color?.let { currentTheme().color(it).toInt() } ?: palette.accent

        // 1. Navigation / Context Header (Zero Cards! Title and Scope inside App Bar)
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12f).toInt())
        }

        val backBtn = TextView(context).apply {
            text = "←"
            textSize = 22f
            setTextColor(palette.onSurface)
            gravity = Gravity.CENTER
            val size = dp(36f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, dp(8f).toInt(), 0)
            }
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
                cornerRadius = dp(18f)
            }
            setOnClickListener { handleBack() }
        }
        navRow.addView(backBtn)

        val iconRef = IconRef.fromString(container.icon)
        val iconEntry = (iconRef as? IconRef.Vector)?.let { IconPack.getIcon(it.key) }
        val hasIcon = when (iconRef) {
            is IconRef.Vector -> iconEntry != null
            is IconRef.Emoji -> iconRef.value.isNotBlank()
            else -> false
        }
        if (hasIcon) {
            val iconTile = buildIconTile(
                iconRef = iconRef,
                color = container.color,
                sizeDp = 36f,
                cornerDp = 9f
            ).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(8f).toInt(), 0)
            }
            navRow.addView(iconTile)
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val path = facade.containerPath(container.id)
        if (path.size > 1) {
            val pathText = path.dropLast(1).joinToString(" / ") { it.name }
            val pathView = TextView(context).apply {
                text = pathText
                setTextColor(palette.onSurfaceVariant)
                textSize = 11f
                maxLines = 1
                setOnClickListener {
                    focusOn(path[path.size - 2].id, forward = false)
                }
            }
            titleCol.addView(pathView)
        }

        val titleView = TextView(context).apply {
            text = container.name
            setTextColor(palette.onSurface)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        titleCol.addView(titleView)

        // Subtitle Scope Toggle: "Здесь X · Внутри Y"
        val scopeSubtitle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2f).toInt(), 0, 0)
        }

        val isHere = (scopeMode == ScopeMode.HERE)
        val hereText = TextView(context).apply {
            text = getString(R.string.containers_scope_here_count, overview.directHabitCount)
            textSize = 12f
            setTypeface(typeface, if (isHere) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isHere) activeColor else palette.onSurfaceVariant)
            setOnClickListener {
                if (scopeMode != ScopeMode.HERE) {
                    scopeMode = ScopeMode.HERE
                    render()
                }
            }
        }
        val dotSep = TextView(context).apply {
            text = " · "
            textSize = 12f
            setTextColor(palette.onSurfaceVariant)
        }
        val subtreeText = TextView(context).apply {
            text = getString(R.string.containers_scope_inside, overview.totalHabitCount)
            textSize = 12f
            setTypeface(typeface, if (!isHere) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (!isHere) activeColor else palette.onSurfaceVariant)
            setOnClickListener {
                if (scopeMode != ScopeMode.SUBTREE) {
                    scopeMode = ScopeMode.SUBTREE
                    render()
                }
            }
        }
        scopeSubtitle.addView(hereText)
        scopeSubtitle.addView(dotSep)
        scopeSubtitle.addView(subtreeText)
        titleCol.addView(scopeSubtitle)

        navRow.addView(titleCol)

        val overflowBtn = TextView(context).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(palette.onSurface)
            gravity = Gravity.CENTER
            val size = dp(36f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
                cornerRadius = dp(18f)
            }
            setOnClickListener {
                showContainerPopupMenu(this, container)
            }
        }
        navRow.addView(overflowBtn)
        contentLayout.addView(navRow)

        // 2. Subsections (if any)
        val subcontainers = facade.childrenOf(container.id)
        if (subcontainers.isNotEmpty()) {
            val subHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10f).toInt(), 0, dp(4f).toInt())
            }
            val subTitle = TextView(context).apply {
                text = getString(R.string.containers_subsections) + " (${subcontainers.size})"
                setTextColor(palette.onSurfaceVariant)
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            subHeader.addView(subTitle)

            val addSubBtn = buildPillButton(
                text = "+ " + getString(R.string.containers_create_subsection),
                isPrimary = false
            ) {
                showCreateContainerDialog(parentId = container.id)
            }
            subHeader.addView(addSubBtn)
            contentLayout.addView(subHeader)

            for (sub in subcontainers) {
                val subOverview = facade.containerOverview(sub.id)
                val countText = getString(R.string.containers_count_habits_inside, subOverview?.totalHabitCount ?: 0)
                val row = buildNavRowOpen(
                    title = sub.name,
                    subtitle = countText,
                    color = sub.color,
                    iconRef = IconRef.fromString(sub.icon),
                    chevron = "›"
                ) {
                    focusOn(sub.id, forward = true)
                }
                contentLayout.addView(row)
            }
        }

        // 3. Habits Section
        val habitUuids = facade.getHabitUuidsForContainer(
            containerKey = container.id.value,
            subtree = (scopeMode == ScopeMode.SUBTREE)
        )
        val habits = habitList.toList().filter { habitUuids.contains(it.uuid) }

        val habitsHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
        }
        val habitsTitle = TextView(context).apply {
            val titleStr = if (scopeMode == ScopeMode.SUBTREE) {
                getString(R.string.containers_habits_all)
            } else {
                getString(R.string.containers_habits_direct)
            }
            text = "$titleStr (${habits.size})"
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        habitsHeader.addView(habitsTitle)

        val addHabitBtn = buildPillButton(
            text = "+ " + getString(R.string.habit),
            isPrimary = false
        ) {
            val dialog = HabitTypeDialog.newInstance()
            dialog.show(fragmentManager, "habit_type")
        }
        habitsHeader.addView(addHabitBtn)
        contentLayout.addView(habitsHeader)

        if (habits.isEmpty()) {
            val emptyHabits = TextView(context).apply {
                text = getString(R.string.containers_no_habits)
                setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.7f))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(16f).toInt(), 0, dp(16f).toInt())
            }
            contentLayout.addView(emptyHabits)
        } else {
            for (habit in habits) {
                val card = buildHabitCard(habit)
                contentLayout.addView(card)
            }
        }
    }

    // ========================================================================
    // Archive View Rendering
    // ========================================================================

    private fun renderArchive() {
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12f).toInt())
        }

        val backBtn = TextView(context).apply {
            text = "←"
            textSize = 22f
            setTextColor(palette.onSurface)
            gravity = Gravity.CENTER
            val size = dp(36f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, dp(8f).toInt(), 0)
            }
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.08f))
                cornerRadius = dp(18f)
            }
            setOnClickListener { handleBack() }
        }
        navRow.addView(backBtn)

        val titleView = TextView(context).apply {
            text = getString(R.string.containers_archive)
            setTextColor(palette.onSurface)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        navRow.addView(titleView)
        contentLayout.addView(navRow)

        // Banner
        val banner = TextView(context).apply {
            text = getString(R.string.containers_archive_confirm_message)
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.06f))
                cornerRadius = dp(8f)
            }
        }
        contentLayout.addView(banner)

        val archivedRoots = facade.archivedRoots()
        if (archivedRoots.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = getString(R.string.containers_archive_empty)
                setTextColor(palette.onSurfaceVariant)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(32f).toInt(), 0, dp(16f).toInt())
            }
            contentLayout.addView(emptyView)
        } else {
            contentLayout.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(12f).toInt())
            })
            for (container in archivedRoots) {
                val card = buildArchiveRowOpen(container)
                contentLayout.addView(card)
            }
        }
    }

    private fun buildArchiveRowOpen(container: Container): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8f)
            }
        }

        val iconRef = IconRef.fromString(container.icon)
        val iconEntry = (iconRef as? IconRef.Vector)?.let { IconPack.getIcon(it.key) }
        val hasIcon = when (iconRef) {
            is IconRef.Vector -> iconEntry != null
            is IconRef.Emoji -> iconRef.value.isNotBlank()
            else -> false
        }
        if (hasIcon) {
            val iconTile = buildIconTile(
                iconRef = iconRef,
                color = null,
                sizeDp = 34f,
                cornerDp = 8f,
                isArchived = true
            )
            row.addView(iconTile)
        } else {
            val dot = View(context).apply {
                val s = dp(6f).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    setMargins(dp(2f).toInt(), 0, dp(6f).toInt(), 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.onSurfaceVariant)
                }
            }
            row.addView(dot)
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        val titleText = TextView(context).apply {
            text = container.name
            setTextColor(palette.onSurfaceVariant)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        textCol.addView(titleText)
        row.addView(textCol)

        val restoreBtn = buildPillButton(
            text = getString(R.string.containers_restore),
            isPrimary = true
        ) {
            val res = facade.unarchiveContainer(container.id)
            if (res.isSuccess) {
                render()
            } else {
                Toast.makeText(context, getString(R.string.containers_restore_parent_missing), Toast.LENGTH_LONG).show()
            }
        }
        row.addView(restoreBtn)

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(4f).toInt())
        }
        return row.apply { layoutParams = lp }
    }

    // ========================================================================
    // Search Rendering
    // ========================================================================

    private fun renderSearch() {
        val q = searchQuery.lowercase()
        val allContainers = facade.availableContainers().filter {
            it.name.lowercase().contains(q) || it.path?.lowercase()?.contains(q) == true
        }

        val allHabits = habitList.toList().filter {
            it.name.lowercase().contains(q)
        }

        contentLayout.addView(buildSectionHeader(getString(R.string.containers_search_sections_header), allContainers.size))
        if (allContainers.isEmpty()) {
            val noSec = TextView(context).apply {
                text = getString(R.string.containers_search_no_results)
                setTextColor(palette.onSurfaceVariant)
                textSize = 13f
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
            }
            contentLayout.addView(noSec)
        } else {
            for (c in allContainers) {
                val row = buildNavRowOpen(
                    title = c.name,
                    subtitle = c.path ?: c.name,
                    color = c.color,
                    iconRef = IconRef.fromString(c.icon),
                    chevron = "›"
                ) {
                    searchQuery = ""
                    focusOn(ContainerId(c.key), forward = true)
                }
                contentLayout.addView(row)
            }
        }

        contentLayout.addView(buildSectionHeader(getString(R.string.containers_search_habits_header), allHabits.size))
        if (allHabits.isEmpty()) {
            val noHab = TextView(context).apply {
                text = getString(R.string.containers_search_no_results)
                setTextColor(palette.onSurfaceVariant)
                textSize = 13f
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
            }
            contentLayout.addView(noHab)
        } else {
            for (h in allHabits) {
                val card = buildHabitCard(h)
                contentLayout.addView(card)
            }
        }
    }

    // ========================================================================
    // UI Helpers & Component Builders
    // ========================================================================

    private fun buildIconTile(
        iconRef: IconRef?,
        color: PaletteColor?,
        sizeDp: Float,
        cornerDp: Float,
        isArchived: Boolean = false
    ): View {
        val size = dp(sizeDp).toInt()
        val tile = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        val activeColor = if (isArchived) {
            palette.onSurfaceVariant
        } else {
            color?.let { currentTheme().color(it).toInt() } ?: palette.accent
        }

        tile.background = GradientDrawable().apply {
            setColor(MainTabsThemeBridge.withAlpha(activeColor, 0.14f))
            cornerRadius = dp(cornerDp)
        }

        when (iconRef) {
            is IconRef.Vector -> {
                val iconEntry = IconPack.getIcon(iconRef.key)
                if (iconEntry != null) {
                    val d = try {
                        AppCompatResources.getDrawable(context, iconEntry.drawableRes)
                    } catch (e: Exception) {
                        null
                    }
                    if (d != null) {
                        val iv = ImageView(context).apply {
                            val iconInnerSize = dp(sizeDp * 0.55f).toInt()
                            layoutParams = FrameLayout.LayoutParams(iconInnerSize, iconInnerSize, Gravity.CENTER)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(d)
                            setColorFilter(activeColor)
                        }
                        tile.addView(iv)
                    }
                }
            }
            is IconRef.Emoji -> {
                val tv = TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
                    text = iconRef.value
                    textSize = sizeDp * 0.42f
                }
                tile.addView(tv)
            }
            else -> {
                // No generic fallback icon!
            }
        }

        return tile
    }

    private fun buildNavRowOpen(
        title: String,
        subtitle: String?,
        color: PaletteColor?,
        iconRef: IconRef?,
        chevron: String? = "›",
        isSmall: Boolean = false,
        isMuted: Boolean = false,
        onClick: () -> Unit
    ): View {
        val activeColor = if (isMuted) {
            palette.onSurfaceVariant
        } else {
            color?.let { currentTheme().color(it).toInt() } ?: palette.accent
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padY = if (isSmall) dp(6f).toInt() else dp(8f).toInt()
            val padX = dp(6f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8f)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconEntry = (iconRef as? IconRef.Vector)?.let { IconPack.getIcon(it.key) }
        val hasIcon = when (iconRef) {
            is IconRef.Vector -> iconEntry != null
            is IconRef.Emoji -> iconRef.value.isNotBlank()
            else -> false
        }
        if (hasIcon) {
            val tile = buildIconTile(
                iconRef = iconRef,
                color = color,
                sizeDp = if (isSmall) 30f else 34f,
                cornerDp = if (isSmall) 7f else 8f,
                isArchived = isMuted
            )
            row.addView(tile)
        } else {
            val colorDot = View(context).apply {
                val dotSize = dp(6f).toInt()
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dp(2f).toInt(), 0, dp(6f).toInt(), 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(activeColor)
                }
            }
            row.addView(colorDot)
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        val titleText = TextView(context).apply {
            text = title
            setTextColor(if (isMuted) palette.onSurfaceVariant else palette.onSurface)
            textSize = if (isSmall) 13.5f else 14.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        textCol.addView(titleText)

        if (!subtitle.isNullOrBlank()) {
            val subText = TextView(context).apply {
                text = subtitle
                setTextColor(palette.onSurfaceVariant)
                textSize = 11.5f
                maxLines = 1
            }
            textCol.addView(subText)
        }
        row.addView(textCol)

        if (!chevron.isNullOrBlank()) {
            val chView = TextView(context).apply {
                text = chevron
                textSize = 18f
                setTextColor(palette.onSurfaceVariant)
            }
            row.addView(chView)
        }

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(2f).toInt())
        }
        return row.apply { layoutParams = lp }
    }

    private fun buildHabitCard(habit: Habit): View {
        val checkmarkButtonFactory = CheckmarkButtonViewFactory(context, prefs)
        val checkmarkPanelFactory = CheckmarkPanelViewFactory(context, prefs, checkmarkButtonFactory)
        val numberButtonFactory = NumberButtonViewFactory(context, prefs)
        val numberPanelFactory = NumberPanelViewFactory(context, prefs, numberButtonFactory)
        val card = HabitCardView(context, checkmarkPanelFactory, numberPanelFactory, listHabitsBehavior, prefs)

        val today = getToday()
        val buttonCount = 5
        val checkmarks = IntArray(buttonCount)
        val notes = Array(buttonCount) { "" }
        for (i in 0 until buttonCount) {
            val date = today.minus(i)
            val entry = habit.computedEntries.get(date)
            checkmarks[i] = entry.value
            notes[i] = entry.notes
        }

        card.habit = habit
        card.values = checkmarks
        card.buttonCount = buttonCount
        card.dataOffset = 0
        card.score = habit.scores[today].value
        val goal = habit.goalAt(today)
        card.unit = goal.unit
        card.threshold = goal.targetValue / goal.frequency.denominator
        card.notes = notes

        val visualIdentity = facade.getHabitVisualIdentity(habit.uuid ?: "")
        card.iconRef = visualIdentity?.iconRef

        card.setOnClickListener {
            onHabitClicked(habit)
        }
        card.setOnLongClickListener {
            showHabitPopupMenu(card, habit)
            true
        }

        return card
    }

    private fun buildPillButton(text: String, isPrimary: Boolean, onClick: () -> Unit): View {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            val padX = dp(12f).toInt()
            val padY = dp(6f).toInt()
            setPadding(padX, padY, padX, padY)
            if (isPrimary) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(palette.accent)
                    cornerRadius = dp(14f)
                }
            } else {
                setTextColor(palette.accent)
                background = GradientDrawable().apply {
                    setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.14f))
                    cornerRadius = dp(14f)
                }
            }
            setOnClickListener { onClick() }
        }
    }


    private fun buildSectionHeader(title: String, count: Int? = null): View {
        val text = if (count != null) "$title ($count)" else title
        return TextView(context).apply {
            this.text = text
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14f).toInt(), 0, dp(6f).toInt())
        }
    }

    private fun buildEmptyCard(title: String, subtitle: String, actionText: String, onAction: () -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20f).toInt(), dp(24f).toInt(), dp(20f).toInt(), dp(24f).toInt())
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12f)
                setStroke(dp(1f).toInt(), palette.border)
            }
        }
        val tView = TextView(context).apply {
            text = title
            setTextColor(palette.onSurface)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val sView = TextView(context).apply {
            text = subtitle
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
            setPadding(0, dp(4f).toInt(), 0, dp(14f).toInt())
        }
        val actBtn = buildPillButton(actionText, isPrimary = true, onAction)

        card.addView(tView)
        card.addView(sView)
        card.addView(actBtn)

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12f).toInt())
        }
        return card.apply { layoutParams = lp }
    }

    // ========================================================================
    // Dialogs & Actions
    // ========================================================================

    private fun showCreateContainerDialog(parentId: ContainerId?) {
        val parentName = parentId?.let { id ->
            facade.availableContainers().firstOrNull { it.key == id.value }?.name
        }
        val dialog = CreateContainerDialog().apply {
            initialParentId = parentId
            initialParentName = parentName
            onSaveWithIcon = { name, selectedParentId, color, icon ->
                val res = facade.createContainer(
                    name = name,
                    parentId = selectedParentId,
                    color = color,
                    icon = IconRef.toStorageString(icon)
                )
                if (res.isSuccess) {
                    render()
                } else {
                    Toast.makeText(context, "Ошибка создания раздела", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show(fragmentManager, "create_container")
    }

    private fun showEditContainerDialog(container: Container) {
        val dialog = CreateContainerDialog().apply {
            containerToEdit = container
            onSaveWithIcon = { name, selectedParentId, color, icon ->
                val editRes = facade.editContainer(
                    id = container.id,
                    name = name,
                    color = color,
                    icon = IconRef.toStorageString(icon)
                )
                if (selectedParentId != container.parentId) {
                    facade.moveContainer(container.id, selectedParentId)
                }
                if (editRes.isSuccess) {
                    render()
                } else {
                    Toast.makeText(context, "Ошибка сохранения", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show(fragmentManager, "edit_container")
    }

    private fun showMoveContainerDialog(container: Container) {
        val dialog = DrillDownMoveDialog().apply {
            itemName = container.name
            movingContainerId = container.id
            isParentPickerOnly = false
            onTargetSelected = { newParentId ->
                val res = facade.moveContainer(container.id, newParentId)
                if (res.isSuccess) {
                    render()
                } else {
                    Toast.makeText(context, getString(R.string.containers_move_cycle_error), Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show(fragmentManager, "move_container")
    }

    private fun showArchiveConfirmDialog(container: Container) {
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.containers_archive_confirm_title))
            .setMessage(getString(R.string.containers_archive_confirm_message))
            .setPositiveButton(getString(R.string.containers_to_archive)) { _, _ ->
                val res = facade.archiveContainer(container.id)
                if (res.isSuccess) {
                    if (focusedContainerId == container.id) {
                        handleBack()
                    } else {
                        render()
                    }
                }
            }
            .setNegativeButton(getString(R.string.containers_cancel), null)
            .show()
    }

    private fun showContainerPopupMenu(anchor: View, container: Container) {
        val entries = listOf(
            CompactPopupMenu.Entry.Item(1, getString(R.string.containers_edit_section)),
            CompactPopupMenu.Entry.Item(2, getString(R.string.containers_move)),
            CompactPopupMenu.Entry.Item(3, getString(R.string.containers_to_archive), destructive = true)
        )
        CompactPopupMenu.show(anchor, entries) { id ->
            when (id) {
                1 -> showEditContainerDialog(container)
                2 -> showMoveContainerDialog(container)
                3 -> showArchiveConfirmDialog(container)
            }
        }
    }

    private fun showHabitPopupMenu(anchor: View, habit: Habit) {
        val entries = listOf(
            CompactPopupMenu.Entry.Item(1, getString(R.string.containers_choose_icon)),
            CompactPopupMenu.Entry.Item(2, getString(R.string.containers_move_habit))
        )
        CompactPopupMenu.show(anchor, entries) { id ->
            when (id) {
                1 -> {
                    val habitUuid = habit.uuid ?: return@show
                    val currentIdentity = facade.getHabitVisualIdentity(habitUuid)
                    val picker = IconPickerBottomSheet().apply {
                        previewColor = currentTheme().color(habit.color).toInt()
                        initialIconRef = currentIdentity?.iconRef ?: IconRef.None
                        onIconSelected = { picked ->
                            if (picked is IconRef.None) {
                                facade.removeHabitVisualIdentity(habitUuid)
                            } else {
                                facade.setHabitVisualIdentity(habitUuid, picked)
                            }
                            render()
                        }
                    }
                    picker.show(fragmentManager, "habit_icon_picker")
                }
                2 -> {
                    val habitUuid = habit.uuid ?: return@show
                    val dialog = DrillDownMoveDialog().apply {
                        itemName = habit.name
                        isParentPickerOnly = false
                        onTargetSelected = { targetContainerId ->
                            val res = facade.placeHabit(habitUuid, targetContainerId)
                            if (res.isSuccess) {
                                render()
                            } else {
                                Toast.makeText(context, "Ошибка перемещения привычки", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    dialog.show(fragmentManager, "move_habit")
                }
            }
        }
    }
}
