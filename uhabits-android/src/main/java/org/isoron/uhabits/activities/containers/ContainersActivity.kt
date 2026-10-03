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

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.getToday
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.common.dialogs.CustomColorBottomSheet
import org.isoron.uhabits.activities.common.dialogs.CustomDialogs
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.common.views.CompactPopupMenu
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.views.CheckmarkButtonViewFactory
import org.isoron.uhabits.activities.habits.list.views.CheckmarkPanelViewFactory
import org.isoron.uhabits.activities.habits.list.views.HabitCardView
import org.isoron.uhabits.activities.habits.list.views.NumberButtonViewFactory
import org.isoron.uhabits.activities.habits.list.views.NumberPanelViewFactory
import org.isoron.uhabits.core.containers.ArchiveContainer
import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.CreateContainer
import org.isoron.uhabits.core.containers.EditContainer
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.MoveContainer
import org.isoron.uhabits.core.containers.OrganizationResult
import org.isoron.uhabits.core.containers.UnarchiveContainer
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp

sealed interface NavDestination {
    data object BrowseRoot : NavDestination
    data class ContainerDetail(val containerId: ContainerId) : NavDestination
    data object Unassigned : NavDestination
    data object Archive : NavDestination
}

enum class HabitsScope { HERE, SUBTREE }

class ContainersActivity : AppCompatActivity(), ModelObservable.Listener {

    private lateinit var toolbar: Toolbar
    private lateinit var mainContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var themeSwitcher: AndroidThemeSwitcher

    private var currentDestination: NavDestination = NavDestination.BrowseRoot
    private val navStack = mutableListOf<NavDestination>()
    private var currentHabitsScope = HabitsScope.HERE

    private var isSearchActive = false
    private var searchQuery = ""
    private var isRendering = false

    private val app get() = application as HabitsApplication
    private val appComponent get() = app.component
    private val facade get() = appComponent.organizationFacade
    private val orgService get() = facade.organizationService
    private val cQueries get() = facade.containerQueries
    private val pQueries get() = facade.habitPlacementQueries
    private val habitList get() = appComponent.habitList
    private val prefs get() = appComponent.preferences

    private val activityComponent by lazy {
        HabitsActivityComponent::class.create(appComponent, this)
    }
    private val listHabitsBehavior by lazy {
        activityComponent.listHabitsBehavior
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, prefs)
        themeSwitcher.apply()
        BaseExceptionHandler.install(this)

        setContentView(R.layout.activity_containers)
        findViewById<View>(R.id.coordinatorLayout).applyRootViewInsets()

        toolbar = findViewById(R.id.toolbar)
        toolbar.applyToolbarInsets()
        mainContainer = findViewById(R.id.mainContainer)
        emptyView = findViewById(R.id.emptyView)
        fabAdd = findViewById(R.id.fabAdd)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val palette = MainTabsThemeBridge.resolve(this)
        findViewById<View>(R.id.coordinatorLayout).setBackgroundColor(palette.background)
        toolbar.setTitleTextColor(palette.onSurface)
        toolbar.setSubtitleTextColor(palette.onSurfaceVariant)

        toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        fabAdd.setOnClickListener {
            showCreateContainerDialog(null)
        }

        val initialContainerId = intent.getStringExtra(EXTRA_CONTAINER_ID)?.let { ContainerId(it) }
        if (initialContainerId != null) {
            currentDestination = NavDestination.ContainerDetail(initialContainerId)
        }

        // Pre-warm behavior before attaching listener so component setup doesn't trigger re-entrant onModelChange
        val behavior = listHabitsBehavior
        habitList.observable.addListener(this)
        renderCurrentDestination()
    }

    override fun onDestroy() {
        habitList.observable.removeListener(this)
        super.onDestroy()
    }

    override fun onModelChange() {
        runOnUiThread {
            if (!isRendering && !isFinishing && !isDestroyed) {
                renderCurrentDestination()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.containers_menu, menu)
        val searchContainer = menu.findItem(R.id.actionSearchContainer)
        val searchIcon = menu.findItem(R.id.actionSearch)

        searchContainer?.isVisible = isSearchActive
        searchIcon?.isVisible = !isSearchActive

        val searchView = searchContainer?.actionView as? SearchView
        searchView?.apply {
            queryHint = getString(R.string.containers_search_hint)
            isIconified = false
            setQuery(searchQuery, false)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText.orEmpty().trim()
                    renderCurrentDestination()
                    return true
                }
            })
            setOnCloseListener {
                closeSearch()
                true
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isDetail = currentDestination is NavDestination.ContainerDetail
        val isArchived = (currentDestination as? NavDestination.ContainerDetail)?.let {
            cQueries?.find(it.containerId)?.isArchived == true || cQueries?.isEffectivelyArchived(it.containerId) == true
        } ?: false

        menu.findItem(R.id.actionSearchContainer)?.isVisible = isSearchActive
        menu.findItem(R.id.actionSearch)?.isVisible = !isSearchActive
        menu.findItem(R.id.actionRename)?.isVisible = isDetail && !isArchived && !isSearchActive
        menu.findItem(R.id.actionChangeColor)?.isVisible = isDetail && !isArchived && !isSearchActive
        menu.findItem(R.id.actionMove)?.isVisible = isDetail && !isArchived && !isSearchActive
        menu.findItem(R.id.actionArchive)?.isVisible = isDetail && !isArchived && !isSearchActive
        menu.findItem(R.id.actionRestore)?.isVisible = isDetail && isArchived && !isSearchActive

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val dest = currentDestination as? NavDestination.ContainerDetail
        val containerId = dest?.containerId
        val container = containerId?.let { cQueries?.find(it) }

        when (item.itemId) {
            android.R.id.home -> {
                handleBackNavigation()
                return true
            }
            R.id.actionRename -> {
                container?.let { showRenameDialog(it) }
                return true
            }
            R.id.actionChangeColor -> {
                container?.let { showChangeColorDialog(it) }
                return true
            }
            R.id.actionMove -> {
                container?.let { showMoveContainerDialog(it) }
                return true
            }
            R.id.actionArchive -> {
                container?.let { showArchiveConfirmDialog(it) }
                return true
            }
            R.id.actionRestore -> {
                container?.let { restoreContainer(it) }
                return true
            }
            R.id.actionSearch -> {
                isSearchActive = true
                invalidateOptionsMenu()
                renderCurrentDestination()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun closeSearch() {
        isSearchActive = false
        searchQuery = ""
        invalidateOptionsMenu()
        renderCurrentDestination()
    }

    private fun handleBackNavigation() {
        if (isSearchActive) {
            closeSearch()
            return
        }
        if (navStack.isNotEmpty()) {
            currentDestination = navStack.removeAt(navStack.size - 1)
            renderCurrentDestination()
        } else {
            finish()
        }
    }

    private fun navigateTo(dest: NavDestination) {
        navStack.add(currentDestination)
        currentDestination = dest
        renderCurrentDestination()
    }

    private fun setToolbarTitleClickListener(listener: View.OnClickListener?) {
        toolbar.setOnClickListener(listener)
        toolbar.post {
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is TextView) {
                    child.setOnClickListener(listener)
                }
            }
        }
    }

    private fun renderCurrentDestination() {
        if (isRendering) return
        isRendering = true
        try {
            invalidateOptionsMenu()
            mainContainer.removeAllViews()
            emptyView.visibility = View.GONE

            if (isSearchActive && searchQuery.isNotBlank()) {
                renderSearch(searchQuery)
                return
            }

            when (val dest = currentDestination) {
                NavDestination.BrowseRoot -> renderBrowseRoot()
                is NavDestination.ContainerDetail -> renderContainerDetail(dest.containerId)
                NavDestination.Unassigned -> renderUnassigned()
                NavDestination.Archive -> renderArchive()
            }
        } finally {
            isRendering = false
        }
    }

    // ========================================================================
    // 1. Browse Root
    // ========================================================================

    private fun renderBrowseRoot() {
        toolbar.title = getString(R.string.containers_title)
        toolbar.subtitle = null
        setToolbarTitleClickListener(null)
        fabAdd.visibility = View.VISIBLE

        val queries = cQueries
        val pQ = pQueries
        val palette = MainTabsThemeBridge.resolve(this)

        val roots = queries?.roots(includeArchived = false) ?: emptyList()

        for (root in roots) {
            val directCount = queries?.children(root.id, includeArchived = false)?.size ?: 0
            val habitsInside = pQ?.subtreeHabits(root.id)?.size ?: 0

            val subtitleText = if (directCount > 0) {
                getString(R.string.containers_count_composite, directCount, habitsInside)
            } else {
                getString(R.string.containers_count_habits_inside, habitsInside)
            }

            val row = buildNavRow(
                title = root.name,
                subtitle = subtitleText,
                color = root.color,
                chevron = "›",
                palette = palette,
                onClick = {
                    navigateTo(NavDestination.ContainerDetail(root.id))
                },
                onLongClick = { view ->
                    showContainerPopupMenu(view, root)
                }
            )
            mainContainer.addView(row)
        }

        // Section «СИСТЕМНЫЕ»
        val sysHeader = buildSectionHeader(getString(R.string.containers_system_section), palette)
        mainContainer.addView(sysHeader)

        // Row «Без раздела»
        val allHabitsList = habitList.toList()
        val unassignedHabitsCount = allHabitsList.count { h ->
            pQ?.current(HabitRef(h.uuid ?: ""))?.containerId == null
        }
        val unassignedSub = if (unassignedHabitsCount > 0) {
            getString(R.string.containers_unassigned_summary, unassignedHabitsCount)
        } else {
            getString(R.string.containers_unassigned_empty)
        }
        val unassignedRow = buildNavRow(
            title = getString(R.string.containers_unassigned),
            subtitle = unassignedSub,
            color = null,
            chevron = "›",
            palette = palette,
            onClick = {
                navigateTo(NavDestination.Unassigned)
            }
        )
        mainContainer.addView(unassignedRow)

        // Row «Архив разделов»
        val allContainers = queries?.let { q ->
            // Scan containers to count top-level archive roots
            val rootsArchived = q.roots(includeArchived = true).filter { it.isArchived }
            val nonRoots = queries.roots(includeArchived = true).flatMap { q.children(it.id, includeArchived = true) }
            val archiveRoots = (rootsArchived + nonRoots.filter { it.isArchived && q.find(it.parentId!!)?.isArchived == false }).distinctBy { it.id }
            archiveRoots.size
        } ?: 0

        val archiveTitle = if (allContainers > 0) {
            getString(R.string.containers_archive_count, allContainers)
        } else {
            getString(R.string.containers_archive)
        }
        val archiveRow = buildNavRow(
            title = archiveTitle,
            subtitle = if (allContainers > 0) null else getString(R.string.containers_archive_empty),
            color = null,
            chevron = "›",
            palette = palette,
            onClick = {
                navigateTo(NavDestination.Archive)
            }
        )
        mainContainer.addView(archiveRow)
    }

    // ========================================================================
    // 2. Container Detail
    // ========================================================================

    private fun renderContainerDetail(containerId: ContainerId) {
        val queries = cQueries
        val pQ = pQueries
        val palette = MainTabsThemeBridge.resolve(this)
        fabAdd.visibility = View.GONE

        val container = queries?.find(containerId)
        if (container == null) {
            Toast.makeText(this, "Раздел не найден", Toast.LENGTH_SHORT).show()
            handleBackNavigation()
            return
        }

        toolbar.title = container.name

        // Breadcrumb
        val pathNodes = queries.path(containerId).nodes
        val pathNames = pathNodes.mapNotNull { queries.find(it)?.name }

        val breadcrumbText = if (pathNames.size <= 1) {
            getString(R.string.containers_root_parent)
        } else if (pathNames.size == 2) {
            pathNames.first()
        } else {
            "${pathNames.first()} / … / ${pathNames[pathNames.size - 2]}"
        }
        toolbar.subtitle = "$breadcrumbText ▾"

        setToolbarTitleClickListener {
            // Open Ancestors Bottom Sheet
            val ancestorItems = mutableListOf<AncestorItem>()
            ancestorItems.add(AncestorItem(null, getString(R.string.containers_root_parent)))
            for (nodeId in pathNodes) {
                val nodeName = queries.find(nodeId)?.name.orEmpty()
                ancestorItems.add(AncestorItem(nodeId, nodeName, isCurrent = nodeId == containerId))
            }
            val sheet = AncestorsBottomSheetDialog().apply {
                items = ancestorItems
                onSelectAncestor = { selectedId ->
                    if (selectedId == null) {
                        navigateTo(NavDestination.BrowseRoot)
                    } else if (selectedId != containerId) {
                        navigateTo(NavDestination.ContainerDetail(selectedId))
                    }
                }
            }
            sheet.show(supportFragmentManager, "ancestors_sheet")
        }

        val isEffectivelyArchived = container.isArchived || queries.isEffectivelyArchived(containerId)
        if (isEffectivelyArchived) {
            val banner = buildArchivedBanner(palette) {
                restoreContainer(container)
            }
            mainContainer.addView(banner)
        }

        // Section: ПОДРАЗДЕЛЫ
        val subHeader = buildSubsectionsHeader(palette, isArchived = isEffectivelyArchived) {
            showCreateContainerDialog(container.id)
        }
        mainContainer.addView(subHeader)

        val children = queries.children(containerId, includeArchived = false)
        if (children.isEmpty()) {
            val noSub = buildSubtleText(getString(R.string.containers_no_subsections), palette)
            mainContainer.addView(noSub)
        } else {
            for (child in children) {
                val subCount = queries.children(child.id, includeArchived = false).size
                val subText = if (subCount > 0) getString(R.string.containers_count_subsections, subCount) else null
                val childRow = buildNavRow(
                    title = child.name,
                    subtitle = subText,
                    color = child.color,
                    chevron = "›",
                    palette = palette,
                    onClick = {
                        navigateTo(NavDestination.ContainerDetail(child.id))
                    },
                    onLongClick = { view ->
                        showContainerPopupMenu(view, child)
                    }
                )
                mainContainer.addView(childRow)
            }
        }

        // Section: ПРИВЫЧКИ
        val habitHeader = buildHabitsHeader(palette, currentHabitsScope, isArchived = isEffectivelyArchived,
            onScopeToggle = {
                currentHabitsScope = if (currentHabitsScope == HabitsScope.HERE) {
                    HabitsScope.SUBTREE
                } else {
                    HabitsScope.HERE
                }
                renderCurrentDestination()
            },
            onAddHabit = {
                showCreateHabitDialog(containerId)
            }
        )
        mainContainer.addView(habitHeader)

        val habitRefs = if (currentHabitsScope == HabitsScope.HERE) {
            pQ?.directHabits(containerId) ?: emptyList()
        } else {
            pQ?.subtreeHabits(containerId)?.toList() ?: emptyList()
        }

        val habits = habitRefs.mapNotNull { ref -> habitList.getByUUID(ref.uuid) }
        if (habits.isEmpty()) {
            val noHabits = buildSubtleText(getString(R.string.containers_no_habits), palette)
            mainContainer.addView(noHabits)
        } else {
            for (habit in habits) {
                val card = buildHabitCard(habit)
                mainContainer.addView(card)
            }
        }
    }

    // ========================================================================
    // 3. Unassigned
    // ========================================================================

    private fun renderUnassigned() {
        toolbar.title = getString(R.string.containers_unassigned)
        toolbar.subtitle = getString(R.string.containers_system_section)
        setToolbarTitleClickListener(null)
        fabAdd.visibility = View.GONE

        val pQ = pQueries
        val allHabitsList = habitList.toList()
        val unassignedHabits = allHabitsList.filter { h ->
            pQ?.current(HabitRef(h.uuid ?: ""))?.containerId == null
        }

        if (unassignedHabits.isEmpty()) {
            emptyView.text = getString(R.string.containers_unassigned_empty)
            emptyView.visibility = View.VISIBLE
            return
        }

        for (habit in unassignedHabits) {
            val card = buildHabitCard(habit)
            mainContainer.addView(card)
        }
    }

    // ========================================================================
    // 4. Archive
    // ========================================================================

    private fun renderArchive() {
        toolbar.title = getString(R.string.containers_archive)
        toolbar.subtitle = null
        setToolbarTitleClickListener(null)
        fabAdd.visibility = View.GONE

        val queries = cQueries
        val palette = MainTabsThemeBridge.resolve(this)

        val allContainers = queries?.roots(includeArchived = true) ?: emptyList()
        val nonRoots = queries?.let { q ->
            allContainers.flatMap { root -> q.children(root.id, includeArchived = true) }
        } ?: emptyList()

        val allCandidates = (allContainers + nonRoots).distinctBy { it.id }

        // Top-level archive roots: container.isArchived == true AND none of its ancestors are archived
        val archiveRoots = allCandidates.filter { container ->
            if (!container.isArchived) return@filter false
            val ancestors = queries?.path(container.id)?.nodes?.dropLast(1) ?: emptyList()
            ancestors.none { ancestorId -> queries?.find(ancestorId)?.isArchived == true }
        }

        if (archiveRoots.isEmpty()) {
            emptyView.text = getString(R.string.containers_archive_empty)
            emptyView.visibility = View.VISIBLE
            return
        }

        for (archived in archiveRoots) {
            val pathNames = queries?.path(archived.id)?.nodes?.mapNotNull { queries.find(it)?.name } ?: emptyList()
            val row = buildArchiveRow(
                container = archived,
                pathText = pathNames.joinToString(" / "),
                palette = palette,
                onRestore = {
                    restoreContainer(archived)
                },
                onClick = {
                    navigateTo(NavDestination.ContainerDetail(archived.id))
                }
            )
            mainContainer.addView(row)
        }
    }

    // ========================================================================
    // 5. Unified Search
    // ========================================================================

    private fun renderSearch(query: String) {
        toolbar.title = getString(R.string.search)
        toolbar.subtitle = null
        setToolbarTitleClickListener(null)
        fabAdd.visibility = View.GONE

        val queries = cQueries
        val pQ = pQueries
        val palette = MainTabsThemeBridge.resolve(this)

        val containerHits = queries?.search(query) ?: emptyList()
        val habitHits = habitList.filter { it.name.contains(query, ignoreCase = true) }

        if (containerHits.isEmpty() && habitHits.isEmpty()) {
            emptyView.text = getString(R.string.containers_search_no_results)
            emptyView.visibility = View.VISIBLE
            return
        }

        if (containerHits.isNotEmpty()) {
            val secHeader = buildSectionHeader(getString(R.string.containers_search_sections_header), palette)
            mainContainer.addView(secHeader)

            for (hit in containerHits) {
                val pathNames = hit.path.nodes.mapNotNull { queries?.find(it)?.name }
                val row = buildNavRow(
                    title = hit.container.name,
                    subtitle = pathNames.joinToString(" / "),
                    color = hit.container.color,
                    chevron = "›",
                    palette = palette,
                    onClick = {
                        isSearchActive = false
                        searchQuery = ""
                        navigateTo(NavDestination.ContainerDetail(hit.container.id))
                    }
                )
                mainContainer.addView(row)
            }
        }

        if (habitHits.isNotEmpty()) {
            val secHeader = buildSectionHeader(getString(R.string.containers_search_habits_header), palette)
            mainContainer.addView(secHeader)

            for (habit in habitHits) {
                val placement = pQ?.current(HabitRef(habit.uuid ?: ""))
                val containerPathText = placement?.containerId?.let { cId ->
                    val names = queries?.path(cId)?.nodes?.mapNotNull { queries.find(it)?.name }
                    names?.joinToString(" / ")
                } ?: getString(R.string.containers_unassigned)

                val habitRow = buildNavRow(
                    title = habit.name,
                    subtitle = containerPathText,
                    color = habit.color,
                    chevron = "›",
                    palette = palette,
                    onClick = {
                        listHabitsBehavior.onClickHabit(habit)
                    }
                )
                mainContainer.addView(habitRow)
            }
        }
    }

    // ========================================================================
    // Dialogs & Actions
    // ========================================================================

    private fun showCreateContainerDialog(parentId: ContainerId?) {
        val parentName = parentId?.let { cQueries?.find(it)?.name }
        val dialog = CreateContainerDialog().apply {
            initialParentId = parentId
            initialParentName = parentName
            containerQueries = cQueries
            onSave = { name, selectedParentId, color ->
                val service = orgService
                if (service != null) {
                    val res = service.create(
                        CreateContainer(
                            name = name,
                            parentId = selectedParentId,
                            color = color,
                            expectedRevision = service.currentRevision()
                        )
                    )
                    if (res.isSuccess) {
                        renderCurrentDestination()
                    } else {
                        Toast.makeText(this@ContainersActivity, "Ошибка создания: ${(res as OrganizationResult.Failure).error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show(supportFragmentManager, "create_container")
    }

    private fun showCreateHabitDialog(containerId: ContainerId) {
        val dialog = HabitTypeDialog.newInstance()
        dialog.show(supportFragmentManager, "habit_type")
        // After creation, placeNewHabit is handled or placement occurs in normal flow
    }

    private fun showRenameDialog(container: Container) {
        val palette = MainTabsThemeBridge.resolve(this)
        val input = EditText(this).apply {
            setText(container.name)
            setSelection(container.name.length)
            setTextColor(palette.onSurface)
        }
        val layout = LinearLayout(this).apply {
            setPadding(dp(20f).toInt(), dp(10f).toInt(), dp(20f).toInt(), dp(10f).toInt())
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.containers_rename))
            .setView(layout)
            .setPositiveButton(getString(R.string.containers_create)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    val service = orgService ?: return@setPositiveButton
                    val res = service.edit(
                        EditContainer(
                            id = container.id,
                            name = newName,
                            color = container.color,
                            icon = container.icon,
                            expectedRevision = service.currentRevision()
                        )
                    )
                    if (res.isSuccess) {
                        renderCurrentDestination()
                    } else {
                        Toast.makeText(this, "Ошибка переименования", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.containers_cancel), null)
            .show()
    }

    private fun showChangeColorDialog(container: Container) {
        val sheet = CustomColorBottomSheet.newInstance(
            color = themeSwitcher.currentTheme.color(container.color ?: PaletteColor(0)).toInt(),
            theme = prefs.theme
        )
        sheet.show(supportFragmentManager, "color_picker")
    }

    private fun showMoveContainerDialog(container: Container) {
        val dialog = DrillDownMoveDialog().apply {
            itemName = container.name
            movingContainerId = container.id
            containerQueries = cQueries
            onTargetSelected = { targetParentId ->
                val service = orgService
                if (service != null) {
                    val res = service.move(
                        MoveContainer(
                            id = container.id,
                            newParentId = targetParentId,
                            expectedRevision = service.currentRevision()
                        )
                    )
                    if (res.isSuccess) {
                        renderCurrentDestination()
                    } else {
                        Toast.makeText(this@ContainersActivity, "Ошибка перемещения: ${(res as OrganizationResult.Failure).error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show(supportFragmentManager, "move_container")
    }

    private fun showArchiveConfirmDialog(container: Container) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.containers_archive_confirm_title))
            .setMessage(getString(R.string.containers_archive_confirm_message))
            .setPositiveButton(getString(R.string.containers_to_archive)) { _, _ ->
                val service = orgService ?: return@setPositiveButton
                val res = service.archive(
                    ArchiveContainer(
                        id = container.id,
                        expectedRevision = service.currentRevision()
                    )
                )
                if (res.isSuccess) {
                    if (currentDestination is NavDestination.ContainerDetail &&
                        (currentDestination as NavDestination.ContainerDetail).containerId == container.id) {
                        handleBackNavigation()
                    } else {
                        renderCurrentDestination()
                    }
                } else {
                    Toast.makeText(this, "Ошибка архивации", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.containers_cancel), null)
            .show()
    }

    private fun restoreContainer(container: Container) {
        val service = orgService ?: return
        // Requirement 2: Do NOT auto-reparent to root if parent is missing.
        if (container.parentId != null) {
            val parent = cQueries?.find(container.parentId!!)
            if (parent == null || parent.deletedAt != null) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.containers_restore_confirm_title))
                    .setMessage(getString(R.string.containers_restore_parent_missing))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
        }

        val res = service.unarchive(
            UnarchiveContainer(
                id = container.id,
                expectedRevision = service.currentRevision()
            )
        )
        if (res.isSuccess) {
            renderCurrentDestination()
        } else {
            Toast.makeText(this, "Ошибка восстановления", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoveHabitDialog(habit: Habit) {
        val dialog = DrillDownMoveDialog().apply {
            itemName = habit.name
            containerQueries = cQueries
            onTargetSelected = { targetContainerId ->
                val res = facade.placeHabit(habit.uuid ?: "", targetContainerId)
                if (res.isSuccess) {
                    renderCurrentDestination()
                } else {
                    Toast.makeText(this@ContainersActivity, "Ошибка перемещения привычки", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show(supportFragmentManager, "move_habit")
    }

    private fun showContainerPopupMenu(anchor: View, container: Container) {
        val entries = listOf(
            CompactPopupMenu.Entry.Item(1, getString(R.string.containers_rename)),
            CompactPopupMenu.Entry.Item(2, getString(R.string.containers_move)),
            CompactPopupMenu.Entry.Item(3, getString(R.string.containers_to_archive), destructive = true)
        )
        CompactPopupMenu.show(anchor, entries) { id ->
            when (id) {
                1 -> showRenameDialog(container)
                2 -> showMoveContainerDialog(container)
                3 -> showArchiveConfirmDialog(container)
            }
        }
    }

    private fun showHabitPopupMenu(anchor: View, habit: Habit) {
        val entries = listOf(
            CompactPopupMenu.Entry.Item(1, getString(R.string.containers_move_habit))
        )
        CompactPopupMenu.show(anchor, entries) { id ->
            if (id == 1) {
                showMoveHabitDialog(habit)
            }
        }
    }

    // ========================================================================
    // UI Builder Helpers
    // ========================================================================

    private fun buildHabitCard(habit: Habit): View {
        val checkmarkButtonFactory = CheckmarkButtonViewFactory(this, prefs)
        val checkmarkPanelFactory = CheckmarkPanelViewFactory(this, prefs, checkmarkButtonFactory)
        val numberButtonFactory = NumberButtonViewFactory(this, prefs)
        val numberPanelFactory = NumberPanelViewFactory(this, prefs, numberButtonFactory)
        val card = HabitCardView(this, checkmarkPanelFactory, numberPanelFactory, listHabitsBehavior, prefs)

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

        card.setOnLongClickListener {
            showHabitPopupMenu(card, habit)
            true
        }

        return card
    }

    private fun buildNavRow(
        title: String,
        subtitle: String?,
        color: PaletteColor?,
        chevron: String?,
        palette: SettingsThemePalette,
        onClick: () -> Unit,
        onLongClick: ((View) -> Unit)? = null
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            val padY = dp(12f).toInt()
            val padX = dp(16f).toInt()
            setPadding(padX, padY, padX, padY)

            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(palette.surface)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener {
                    onLongClick(it)
                    true
                }
            }
        }

        if (color != null) {
            val dot = View(this).apply {
                val size = dp(10f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, dp(12f).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(currentTheme().color(color).toInt())
                }
            }
            row.addView(dot)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(palette.onSurface)
            textSize = 15f
            setTypeface(typeface, Typeface.NORMAL)
        }
        textCol.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val subView = TextView(this).apply {
                text = subtitle
                setTextColor(palette.onSurfaceVariant)
                textSize = 12f
            }
            textCol.addView(subView)
        }
        row.addView(textCol)

        if (!chevron.isNullOrBlank()) {
            val chView = TextView(this).apply {
                text = chevron
                textSize = 18f
                setTextColor(palette.onSurfaceVariant)
            }
            row.addView(chView)
        }

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16f).toInt(), 0, dp(16f).toInt(), dp(8f).toInt())
        }
        return row.apply { layoutParams = lp }
    }

    private fun buildArchiveRow(
        container: Container,
        pathText: String,
        palette: SettingsThemePalette,
        onRestore: () -> Unit,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            val padY = dp(10f).toInt()
            val padX = dp(16f).toInt()
            setPadding(padX, padY, padX, padY)

            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(palette.surface)
            }
            setOnClickListener { onClick() }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = container.name
            setTextColor(palette.onSurface)
            textSize = 15f
        }
        textCol.addView(titleView)

        val subView = TextView(this).apply {
            text = pathText
            setTextColor(palette.onSurfaceVariant)
            textSize = 12f
        }
        textCol.addView(subView)
        row.addView(textCol)

        val restoreBtn = Button(this).apply {
            text = getString(R.string.containers_restore)
            textSize = 12f
            setTextColor(palette.accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onRestore() }
        }
        row.addView(restoreBtn)

        val chevron = TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(palette.onSurfaceVariant)
        }
        row.addView(chevron)

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16f).toInt(), 0, dp(16f).toInt(), dp(8f).toInt())
        }
        return row.apply { layoutParams = lp }
    }

    private fun buildSectionHeader(text: String, palette: SettingsThemePalette): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(palette.onSurfaceVariant)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            val padX = dp(20f).toInt()
            val padTop = dp(16f).toInt()
            val padBottom = dp(6f).toInt()
            setPadding(padX, padTop, padX, padBottom)
        }
    }

    private fun buildSubsectionsHeader(
        palette: SettingsThemePalette,
        isArchived: Boolean,
        onAdd: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(16f).toInt(), dp(6f).toInt())
        }
        val label = TextView(this).apply {
            text = getString(R.string.containers_subsections)
            setTextColor(palette.onSurfaceVariant)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        row.addView(label)

        if (!isArchived) {
            val addBtn = Button(this).apply {
                text = "+"
                textSize = 18f
                setTextColor(palette.accent)
                setBackgroundColor(Color.TRANSPARENT)
                val pad = dp(4f).toInt()
                setPadding(pad, 0, pad, 0)
                setOnClickListener { onAdd() }
            }
            row.addView(addBtn)
        }
        return row
    }

    private fun buildHabitsHeader(
        palette: SettingsThemePalette,
        scope: HabitsScope,
        isArchived: Boolean,
        onScopeToggle: () -> Unit,
        onAddHabit: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f).toInt(), dp(20f).toInt(), dp(16f).toInt(), dp(6f).toInt())
        }
        val label = TextView(this).apply {
            text = getString(R.string.containers_habits)
            setTextColor(palette.onSurfaceVariant)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        row.addView(label)

        val scopeBtn = Button(this).apply {
            text = if (scope == HabitsScope.HERE) {
                "${getString(R.string.containers_scope_here)} ▾"
            } else {
                "${getString(R.string.containers_scope_subtree)} ▾"
            }
            textSize = 12f
            setTextColor(palette.accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onScopeToggle() }
        }
        row.addView(scopeBtn)

        if (!isArchived) {
            val addBtn = Button(this).apply {
                text = "+"
                textSize = 18f
                setTextColor(palette.accent)
                setBackgroundColor(Color.TRANSPARENT)
                val pad = dp(4f).toInt()
                setPadding(pad, 0, pad, 0)
                setOnClickListener { onAddHabit() }
            }
            row.addView(addBtn)
        }
        return row
    }

    private fun buildArchivedBanner(
        palette: SettingsThemePalette,
        onRestore: () -> Unit
    ): View {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padY = dp(10f).toInt()
            val padX = dp(16f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                setColor(palette.surfaceVariant)
                cornerRadius = dp(8f)
                setStroke(dp(1f).toInt(), palette.border)
            }
        }
        val text = TextView(this).apply {
            this.text = getString(R.string.containers_archived_banner)
            setTextColor(palette.onSurface)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        banner.addView(text)

        val btn = Button(this).apply {
            this.text = getString(R.string.containers_restore)
            textSize = 12f
            setTextColor(palette.accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onRestore() }
        }
        banner.addView(btn)

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(6f).toInt())
        }
        return banner.apply { layoutParams = lp }
    }

    private fun buildSubtleText(text: String, palette: SettingsThemePalette): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(palette.onSurfaceVariant)
            textSize = 13f
            val padX = dp(20f).toInt()
            val padY = dp(12f).toInt()
            setPadding(padX, padY, padX, padY)
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)

    companion object {
        const val EXTRA_CONTAINER_ID = "extra_container_id"

        fun intent(context: Context, containerId: ContainerId? = null): Intent {
            return Intent(context, ContainersActivity::class.java).apply {
                containerId?.let { putExtra(EXTRA_CONTAINER_ID, it.value) }
            }
        }
    }
}
