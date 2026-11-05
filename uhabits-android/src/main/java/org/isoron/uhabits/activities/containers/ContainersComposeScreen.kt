/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.containers

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialog
import org.isoron.uhabits.activities.common.dialogs.ComposeColorPickerDesignC
import org.isoron.uhabits.activities.common.dialogs.ColorPickerBackHandler
import org.isoron.uhabits.activities.common.dialogs.UHabitColorPickerContent
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.activities.common.icons.UHabitIconPickerContent
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.*
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.PaletteColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import org.isoron.uhabits.utils.toFixedAndroidColor

sealed interface ContainersNavDestination {
    data object BrowseRoot : ContainersNavDestination
    data class ContainerDetail(val containerId: ContainerId) : ContainersNavDestination
    data object Unassigned : ContainersNavDestination
    data object Archive : ContainersNavDestination
}

enum class ContainerHabitsScope { HERE, SUBTREE }

@Composable
fun ContainersComposeScreen(
    facade: HabitOrganizationFacade,
    habitList: HabitList,
    colors: UHabitComposeColors,
    initialContainerId: ContainerId? = null,
    onBackFinish: () -> Unit,
    onOpenStatistics: (ContainerId) -> Unit,
    onAddHabitInContainer: (ContainerId?) -> Unit,
    onOpenHabitDetail: (Habit) -> Unit,
    previewComposeColorPicker: Boolean = true,
) {
    UHabitComposeTheme(colors) {
        val cQueries = facade.containerQueries
        val pQueries = facade.habitPlacementQueries
        val orgService = facade.organizationService

        var navDestination by remember {
            mutableStateOf<ContainersNavDestination>(
                if (initialContainerId != null) ContainersNavDestination.ContainerDetail(initialContainerId)
                else ContainersNavDestination.BrowseRoot
            )
        }
        val navStack = remember { mutableStateListOf<ContainersNavDestination>() }

        fun navigateTo(dest: ContainersNavDestination) {
            navStack.add(navDestination)
            navDestination = dest
        }

        fun handleBack() {
            if (navStack.isNotEmpty()) {
                navDestination = navStack.removeAt(navStack.size - 1)
            } else {
                onBackFinish()
            }
        }

        // Active Dialog States
        var showCreateEditDialog by rememberSaveable { mutableStateOf(false) }
        var editingContainerKey by rememberSaveable { mutableStateOf<String?>(null) }
        var createParentKey by rememberSaveable { mutableStateOf<String?>(null) }
        val editingContainer = editingContainerKey?.let { cQueries?.find(ContainerId(it)) }
        val createParentId = createParentKey?.let(::ContainerId)

        var showMoveDialog by remember { mutableStateOf(false) }
        var movingContainer by remember { mutableStateOf<Container?>(null) }

        var showAncestorsSheet by remember { mutableStateOf(false) }
        var showAssignHabitSheet by remember { mutableStateOf<Habit?>(null) }
        var confirmArchiveContainer by remember { mutableStateOf<Container?>(null) }

        // Data refresh counter
        var refreshTrigger by remember { mutableStateOf(0) }
        fun refresh() { refreshTrigger++ }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(colors.background))
        ) {
            when (val dest = navDestination) {
                is ContainersNavDestination.BrowseRoot -> {
                    BrowseRootScreen(
                        facade = facade,
                        habitList = habitList,
                        colors = colors,
                        refreshTrigger = refreshTrigger,
                        onBack = { handleBack() },
                        onNavigate = { navigateTo(it) },
                        onCreateContainer = {
                            editingContainerKey = null
                            createParentKey = null
                            showCreateEditDialog = true
                        },
                        onEditContainer = { container ->
                            editingContainerKey = container.id.value
                            createParentKey = container.parentId?.value
                            showCreateEditDialog = true
                        },
                        onMoveContainer = { container ->
                            movingContainer = container
                            showMoveDialog = true
                        },
                        onArchiveContainer = { container ->
                            confirmArchiveContainer = container
                        }
                    )
                }
                is ContainersNavDestination.ContainerDetail -> {
                    ContainerDetailScreen(
                        containerId = dest.containerId,
                        facade = facade,
                        habitList = habitList,
                        colors = colors,
                        refreshTrigger = refreshTrigger,
                        onBack = { handleBack() },
                        onNavigate = { navigateTo(it) },
                        onOpenAncestors = { showAncestorsSheet = true },
                        onOpenStatistics = { onOpenStatistics(dest.containerId) },
                        onAddSubsection = {
                            editingContainerKey = null
                            createParentKey = dest.containerId.value
                            showCreateEditDialog = true
                        },
                        onEditContainer = { container ->
                            editingContainerKey = container.id.value
                            createParentKey = container.parentId?.value
                            showCreateEditDialog = true
                        },
                        onMoveContainer = { container ->
                            movingContainer = container
                            showMoveDialog = true
                        },
                        onArchiveContainer = { container ->
                            confirmArchiveContainer = container
                        },
                        onRestoreContainer = { container ->
                            facade.unarchiveContainer(container.id)
                            refresh()
                        },
                        onAddHabit = { onAddHabitInContainer(dest.containerId) },
                        onOpenHabit = onOpenHabitDetail
                    )
                }
                is ContainersNavDestination.Unassigned -> {
                    UnassignedScreen(
                        facade = facade,
                        habitList = habitList,
                        colors = colors,
                        refreshTrigger = refreshTrigger,
                        onBack = { handleBack() },
                        onAssignHabit = { habit ->
                            showAssignHabitSheet = habit
                        },
                        onOpenHabit = onOpenHabitDetail
                    )
                }
                is ContainersNavDestination.Archive -> {
                    ArchiveScreen(
                        facade = facade,
                        colors = colors,
                        refreshTrigger = refreshTrigger,
                        onBack = { handleBack() },
                        onRestoreContainer = { container ->
                            facade.unarchiveContainer(container.id)
                            refresh()
                        }
                    )
                }
            }

            // --- DIALOGS & BOTTOM SHEETS ---

            if (showCreateEditDialog) {
                CreateEditContainerBottomSheet(
                    containerToEdit = editingContainer,
                    initialParentId = createParentId,
                    cQueries = cQueries,
                    colors = colors,
                    previewComposeColorPicker = previewComposeColorPicker,
                    onDismiss = { showCreateEditDialog = false },
                    onSave = { name, parentId, color, icon ->
                        val iconStr = IconRef.toStorageString(icon)
                        if (editingContainer != null) {
                            facade.editContainer(
                                id = editingContainer!!.id,
                                name = name,
                                color = color,
                                icon = iconStr
                            )
                        } else {
                            facade.createContainer(
                                name = name,
                                parentId = parentId,
                                color = color,
                                icon = iconStr
                            )
                        }
                        showCreateEditDialog = false
                        refresh()
                    }
                )
            }

            if (showMoveDialog && movingContainer != null) {
                MoveContainerBottomSheet(
                    container = movingContainer!!,
                    cQueries = cQueries,
                    colors = colors,
                    onDismiss = { showMoveDialog = false },
                    onMoveTo = { newParentId ->
                        facade.moveContainer(
                            id = movingContainer!!.id,
                            newParentId = newParentId
                        )
                        showMoveDialog = false
                        refresh()
                    }
                )
            }

            if (showAncestorsSheet && navDestination is ContainersNavDestination.ContainerDetail) {
                val detailId = (navDestination as ContainersNavDestination.ContainerDetail).containerId
                AncestorsBottomSheet(
                    currentContainerId = detailId,
                    cQueries = cQueries,
                    colors = colors,
                    onDismiss = { showAncestorsSheet = false },
                    onSelectAncestor = { selectedId ->
                        showAncestorsSheet = false
                        if (selectedId == null) {
                            navDestination = ContainersNavDestination.BrowseRoot
                        } else if (selectedId != detailId) {
                            navigateTo(ContainersNavDestination.ContainerDetail(selectedId))
                        }
                    }
                )
            }

            if (showAssignHabitSheet != null) {
                AssignHabitBottomSheet(
                    habit = showAssignHabitSheet!!,
                    facade = facade,
                    colors = colors,
                    onDismiss = { showAssignHabitSheet = null },
                    onSelectContainer = { containerId ->
                        facade.placeHabit(showAssignHabitSheet!!.uuid!!, containerId)
                        showAssignHabitSheet = null
                        refresh()
                    }
                )
            }

            if (confirmArchiveContainer != null) {
                ArchiveConfirmDialog(
                    container = confirmArchiveContainer!!,
                    colors = colors,
                    onDismiss = { confirmArchiveContainer = null },
                    onConfirmArchive = {
                        facade.archiveContainer(confirmArchiveContainer!!.id)
                        confirmArchiveContainer = null
                        if (navDestination is ContainersNavDestination.ContainerDetail &&
                            (navDestination as ContainersNavDestination.ContainerDetail).containerId == confirmArchiveContainer!!.id
                        ) {
                            handleBack()
                        }
                        refresh()
                    }
                )
            }
        }
    }
}

// ========================================================================
// 1. BrowseRoot Screen
// ========================================================================

@Composable
private fun BrowseRootScreen(
    facade: HabitOrganizationFacade,
    habitList: HabitList,
    colors: UHabitComposeColors,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onNavigate: (ContainersNavDestination) -> Unit,
    onCreateContainer: () -> Unit,
    onEditContainer: (Container) -> Unit,
    onMoveContainer: (Container) -> Unit,
    onArchiveContainer: (Container) -> Unit
) {
    val cQueries = facade.containerQueries
    val pQueries = facade.habitPlacementQueries

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val roots = remember(refreshTrigger, searchQuery) {
        val allRoots = cQueries?.roots(includeArchived = false) ?: emptyList()
        if (searchQuery.isBlank()) allRoots
        else allRoots.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    val unassignedCount = remember(refreshTrigger) {
        habitList.count { h -> pQueries?.current(HabitRef(h.uuid ?: ""))?.containerId == null }
    }

    val archiveCount = remember(refreshTrigger) {
        val rootsArchived = cQueries?.roots(includeArchived = true)?.filter { it.isArchived } ?: emptyList()
        val nonRoots = cQueries?.roots(includeArchived = true)?.flatMap { cQueries?.children(it.id, includeArchived = true).orEmpty() } ?: emptyList()
        val archiveRoots = (rootsArchived + nonRoots.filter { it.isArchived && cQueries?.find(it.parentId ?: return@filter false)?.isArchived == false }).distinctBy { it.id }
        archiveRoots.size
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // App Bar
        TopNavBar(
            title = stringResource(R.string.containers_title),
            subtitle = null,
            colors = colors,
            onBack = onBack,
            isSearchActive = isSearchActive,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onToggleSearch = {
                isSearchActive = !isSearchActive
                if (!isSearchActive) searchQuery = ""
            }
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Grouped active spheres container
            if (roots.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(colors.surface))
                            .border(0.5.dp, Color(colors.divider).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        roots.forEachIndexed { index, root ->
                            val directChildren = cQueries?.children(root.id, includeArchived = false)?.size ?: 0
                            val habitsInside = pQueries?.subtreeHabits(root.id)?.size ?: 0
                            val subText = if (directChildren > 0) {
                                pluralStringResource(R.plurals.containers_subsections_count, directChildren, directChildren) +
                                    " · " + pluralStringResource(R.plurals.containers_habits_count, habitsInside, habitsInside)
                            } else {
                                pluralStringResource(R.plurals.containers_habits_count, habitsInside, habitsInside)
                            }

                            val rootColor = remember(root.color) {
                                if (root.color != null) {
                                    Color(android.graphics.Color.parseColor(root.color!!.toCsvColor()))
                                } else {
                                    Color(colors.accent)
                                }
                            }

                            ContainerCardRow(
                                name = root.name,
                                subtitle = subText,
                                icon = IconRef.fromString(root.icon),
                                tintColor = rootColor,
                                colors = colors,
                                onClick = { onNavigate(ContainersNavDestination.ContainerDetail(root.id)) }
                            )

                            if (index < roots.lastIndex) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 58.dp)
                                        .height(0.5.dp)
                                        .background(Color(colors.divider).copy(alpha = 0.5f))
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = if (searchQuery.isNotEmpty()) stringResource(R.string.containers_nothing_found)
                                   else stringResource(R.string.containers_no_active_spheres),
                            style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp)
                        )
                    }
                }
            }

            // System section: Системные
            item {
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = stringResource(R.string.containers_system_section),
                    style = TextStyle(
                        color = Color(colors.secondary),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(colors.surface))
                        .border(0.5.dp, Color(colors.divider).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                ) {
                    val unassignedSub = if (unassignedCount > 0) {
                        pluralStringResource(R.plurals.containers_habits_count, unassignedCount, unassignedCount) + " " + stringResource(R.string.containers_unassigned_habits_suffix)
                    } else {
                        stringResource(R.string.containers_all_habits_placed)
                    }
                    SystemCardRow(
                        title = stringResource(R.string.containers_unassigned),
                        subtitle = unassignedSub,
                        iconRes = R.drawable.ic_tabler_folder,
                        colors = colors,
                        onClick = { onNavigate(ContainersNavDestination.Unassigned) }
                    )

                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 54.dp)
                            .height(0.5.dp)
                            .background(Color(colors.divider).copy(alpha = 0.5f))
                    )

                    val archiveSub = if (archiveCount > 0) {
                        "$archiveCount в архиве"
                    } else {
                        stringResource(R.string.containers_archive_empty_subtitle)
                    }
                    SystemCardRow(
                        title = stringResource(R.string.containers_archive),
                        subtitle = archiveSub,
                        iconRes = R.drawable.ic_l_archive,
                        colors = colors,
                        onClick = { onNavigate(ContainersNavDestination.Archive) }
                    )
                }
            }
        }

        // Bottom Add Sphere Button: compact centered Extended FAB pill with navigationBarsPadding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(colors.accent))
                    .clickable { onCreateContainer() }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText("+", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                BasicText(
                    stringResource(R.string.containers_add_sphere_action),
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

// ========================================================================
// 2. ContainerDetail Screen
// ========================================================================

@Composable
private fun ContainerDetailScreen(
    containerId: ContainerId,
    facade: HabitOrganizationFacade,
    habitList: HabitList,
    colors: UHabitComposeColors,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onNavigate: (ContainersNavDestination) -> Unit,
    onOpenAncestors: () -> Unit,
    onOpenStatistics: () -> Unit,
    onAddSubsection: () -> Unit,
    onEditContainer: (Container) -> Unit,
    onMoveContainer: (Container) -> Unit,
    onArchiveContainer: (Container) -> Unit,
    onRestoreContainer: (Container) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (Habit) -> Unit
) {
    val cQueries = facade.containerQueries
    val pQueries = facade.habitPlacementQueries

    val container = remember(containerId, refreshTrigger) { cQueries?.find(containerId) }
    if (container == null) {
        onBack()
        return
    }

    val isEffectivelyArchived = container.isArchived || (cQueries?.isEffectivelyArchived(containerId) == true)
    var habitsScope by remember { mutableStateOf(ContainerHabitsScope.HERE) }

    // Breadcrumb text
    val breadcrumb = remember(containerId, refreshTrigger) {
        val nodes = cQueries?.path(containerId)?.nodes ?: emptyList()
        val names = nodes.mapNotNull { cQueries?.find(it)?.name }
        when {
            names.size <= 1 -> "Главная"
            names.size == 2 -> names.first()
            else -> "${names.first()} / … / ${names[names.size - 2]}"
        }
    }

    val children = remember(containerId, refreshTrigger) {
        cQueries?.children(containerId, includeArchived = false) ?: emptyList()
    }

    val containerHabits = remember(containerId, habitsScope, refreshTrigger) {
        val uuids: Set<String> = if (habitsScope == ContainerHabitsScope.HERE) {
            pQueries?.directHabits(containerId)?.map { it.uuid }?.toSet().orEmpty()
        } else {
            pQueries?.subtreeHabits(containerId)?.map { it.uuid }?.toSet().orEmpty()
        }
        habitList.filter { h -> uuids.contains(h.uuid) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with Breadcrumbs & statusBarsPadding
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(colors.surface))
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("‹", style = TextStyle(color = Color(colors.foreground), fontSize = 26.sp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenAncestors() }
                        .padding(horizontal = 8.dp)
                ) {
                    BasicText(
                        text = container.name,
                        style = TextStyle(color = Color(colors.foreground), fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BasicText(
                        text = "$breadcrumb ▾",
                        style = TextStyle(color = Color(colors.accent), fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Actions row: Stats, Edit, Move, Archive
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onOpenStatistics() },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_l_chart_bar),
                            contentDescription = "Статистика",
                            colorFilter = ColorFilter.tint(Color(colors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onEditContainer(container) },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tabler_pencil),
                            contentDescription = "Изменить",
                            colorFilter = ColorFilter.tint(Color(colors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onMoveContainer(container) },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tabler_folder),
                            contentDescription = "Переместить",
                            colorFilter = ColorFilter.tint(Color(colors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (isEffectivelyArchived) onRestoreContainer(container)
                                else onArchiveContainer(container)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(if (isEffectivelyArchived) R.drawable.ic_settings_back else R.drawable.ic_l_archive),
                            contentDescription = if (isEffectivelyArchived) "Восстановить" else "В архив",
                            colorFilter = ColorFilter.tint(if (isEffectivelyArchived) Color(colors.accent) else Color(colors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Archive Banner
        if (isEffectivelyArchived) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(colors.foreground).copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    stringResource(R.string.containers_archived_banner),
                    style = TextStyle(color = Color(colors.secondary), fontSize = 13.sp)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(colors.accent).copy(alpha = 0.2f))
                        .clickable { onRestoreContainer(container) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        stringResource(R.string.containers_restore),
                        style = TextStyle(color = Color(colors.accent), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Section: Подразделы
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = stringResource(R.string.containers_subsections) + " (${children.size})",
                        style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    )

                    if (!isEffectivelyArchived) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(colors.accent).copy(alpha = 0.12f))
                                .clickable { onAddSubsection() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            BasicText(
                                "+ " + stringResource(R.string.containers_add_short_subsection),
                                style = TextStyle(color = Color(colors.accent), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            if (children.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(colors.surface))
                            .border(0.5.dp, Color(colors.divider).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        children.forEachIndexed { index, child ->
                            val direct = cQueries?.children(child.id, includeArchived = false)?.size ?: 0
                            val inside = pQueries?.subtreeHabits(child.id)?.size ?: 0
                            val subText = if (direct > 0) {
                                pluralStringResource(R.plurals.containers_subsections_count, direct, direct) +
                                    " · " + pluralStringResource(R.plurals.containers_habits_count, inside, inside)
                            } else {
                                pluralStringResource(R.plurals.containers_habits_count, inside, inside)
                            }
                            val childColor = remember(child.color) {
                                if (child.color != null) {
                                    Color(android.graphics.Color.parseColor(child.color!!.toCsvColor()))
                                } else {
                                    Color(colors.accent)
                                }
                            }

                            ContainerCardRow(
                                name = child.name,
                                subtitle = subText,
                                icon = IconRef.fromString(child.icon),
                                tintColor = childColor,
                                colors = colors,
                                onClick = { onNavigate(ContainersNavDestination.ContainerDetail(child.id)) }
                            )

                            if (index < children.lastIndex) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 58.dp)
                                        .height(0.5.dp)
                                        .background(Color(colors.divider).copy(alpha = 0.5f))
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    BasicText(
                        text = stringResource(R.string.containers_no_subsections),
                        style = TextStyle(color = Color(colors.secondary), fontSize = 13.sp),
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }

            // Section: Привычки
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = stringResource(R.string.containers_habits) + " (${containerHabits.size})",
                        style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    )

                    // Scope toggle: HERE vs SUBTREE
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(colors.surfaceVariant).copy(alpha = 0.6f))
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isHere = habitsScope == ContainerHabitsScope.HERE
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isHere) Color(colors.accent) else Color.Transparent)
                                .clickable { habitsScope = ContainerHabitsScope.HERE }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                stringResource(R.string.containers_scope_here),
                                style = TextStyle(
                                    color = if (isHere) Color.White else Color(colors.secondary),
                                    fontSize = 11.sp,
                                    fontWeight = if (isHere) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (!isHere) Color(colors.accent) else Color.Transparent)
                                .clickable { habitsScope = ContainerHabitsScope.SUBTREE }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                stringResource(R.string.containers_scope_subtree),
                                style = TextStyle(
                                    color = if (!isHere) Color.White else Color(colors.secondary),
                                    fontSize = 11.sp,
                                    fontWeight = if (!isHere) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }

            if (containerHabits.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(colors.surface))
                            .border(0.5.dp, Color(colors.divider).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        containerHabits.forEachIndexed { index, habit ->
                            HabitCardRow(
                                habit = habit,
                                colors = colors,
                                onClick = { onOpenHabit(habit) }
                            )

                            if (index < containerHabits.lastIndex) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 36.dp)
                                        .height(0.5.dp)
                                        .background(Color(colors.divider).copy(alpha = 0.5f))
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    BasicText(
                        text = stringResource(R.string.containers_no_habits),
                        style = TextStyle(color = Color(colors.secondary), fontSize = 13.sp),
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }

        // Add Habit Button: compact centered Extended FAB pill with navigationBarsPadding
        if (!isEffectivelyArchived) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(colors.accent))
                        .clickable { onAddHabit() }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText("+", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        "Добавить привычку",
                        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

// ========================================================================
// 3. Unassigned Screen
// ========================================================================

@Composable
private fun UnassignedScreen(
    facade: HabitOrganizationFacade,
    habitList: HabitList,
    colors: UHabitComposeColors,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onAssignHabit: (Habit) -> Unit,
    onOpenHabit: (Habit) -> Unit
) {
    val pQueries = facade.habitPlacementQueries

    val unassignedHabits = remember(refreshTrigger) {
        habitList.filter { h -> pQueries?.current(HabitRef(h.uuid ?: ""))?.containerId == null }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopNavBar(
            title = stringResource(R.string.containers_unassigned),
            subtitle = if (unassignedHabits.isNotEmpty()) {
                pluralStringResource(R.plurals.containers_habits_count, unassignedHabits.size, unassignedHabits.size) + " " + stringResource(R.string.containers_unassigned_habits_suffix)
            } else null,
            colors = colors,
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (unassignedHabits.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(colors.surface))
                            .border(0.5.dp, Color(colors.divider).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        unassignedHabits.forEachIndexed { index, habit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenHabit(habit) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    BasicText(
                                        habit.name,
                                        style = TextStyle(color = Color(colors.foreground), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    BasicText(
                                        stringResource(R.string.containers_unassigned),
                                        style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(colors.accent).copy(alpha = 0.12f))
                                        .clickable { onAssignHabit(habit) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    BasicText(
                                        stringResource(R.string.containers_assign),
                                        style = TextStyle(color = Color(colors.accent), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            if (index < unassignedHabits.lastIndex) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp)
                                        .height(0.5.dp)
                                        .background(Color(colors.divider).copy(alpha = 0.5f))
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            stringResource(R.string.containers_all_habits_placed),
                            style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp)
                        )
                    }
                }
            }
        }
    }
}

// ========================================================================
// 4. Archive Screen
// ========================================================================

@Composable
private fun ArchiveScreen(
    facade: HabitOrganizationFacade,
    colors: UHabitComposeColors,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onRestoreContainer: (Container) -> Unit
) {
    val cQueries = facade.containerQueries

    val archivedContainers = remember(refreshTrigger) {
        val all = cQueries?.roots(includeArchived = true).orEmpty() +
            (cQueries?.roots(includeArchived = true)?.flatMap { cQueries.children(it.id, includeArchived = true) } ?: emptyList())
        all.filter { it.isArchived }.distinctBy { it.id }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopNavBar(
            title = stringResource(R.string.containers_archive),
            subtitle = if (archivedContainers.isNotEmpty()) {
                pluralStringResource(R.plurals.containers_sections_count, archivedContainers.size, archivedContainers.size) + " " + stringResource(R.string.containers_in_archive_suffix)
            } else null,
            colors = colors,
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (archivedContainers.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(colors.surface))
                            .border(0.5.dp, Color(colors.divider).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        archivedContainers.forEachIndexed { index, container ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    BasicText(
                                        container.name,
                                        style = TextStyle(color = Color(colors.foreground), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    BasicText(
                                        stringResource(R.string.containers_in_archive),
                                        style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(colors.accent).copy(alpha = 0.12f))
                                        .clickable { onRestoreContainer(container) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    BasicText(
                                        stringResource(R.string.containers_restore),
                                        style = TextStyle(color = Color(colors.accent), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            if (index < archivedContainers.lastIndex) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp)
                                        .height(0.5.dp)
                                        .background(Color(colors.divider).copy(alpha = 0.5f))
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            stringResource(R.string.containers_archive_empty_subtitle),
                            style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp)
                        )
                    }
                }
            }
        }
    }
}

// ========================================================================
// Reusable UI Components
// ========================================================================

@Composable
private fun TopNavBar(
    title: String,
    subtitle: String?,
    colors: UHabitComposeColors,
    onBack: () -> Unit,
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onToggleSearch: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(colors.surface))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                BasicText("‹", style = TextStyle(color = Color(colors.foreground), fontSize = 26.sp))
            }

            if (isSearchActive) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    textStyle = TextStyle(color = Color(colors.foreground), fontSize = 16.sp),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                BasicText(
                                    stringResource(R.string.containers_search_hint),
                                    style = TextStyle(color = Color(colors.secondary), fontSize = 15.sp)
                                )
                            }
                            inner()
                        }
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    BasicText(
                        title,
                        style = TextStyle(color = Color(colors.foreground), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        BasicText(
                            subtitle,
                            style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (onToggleSearch != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onToggleSearch() },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(if (isSearchActive) R.drawable.ic_tabler_x else R.drawable.ic_tabler_search),
                        contentDescription = stringResource(R.string.containers_search_action),
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerCardRow(
    name: String,
    subtitle: String,
    icon: IconRef?,
    tintColor: Color,
    colors: UHabitComposeColors,
    onClick: () -> Unit,
    showChevron: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Refined 32dp Icon indicator with soft tint (no harsh heavy outline)
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tintColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            when (icon) {
                is IconRef.Emoji -> {
                    BasicText(icon.value, style = TextStyle(fontSize = 16.sp))
                }
                is IconRef.Vector -> {
                    Image(
                        painter = painterResource(IconPack.getDrawableRes(icon.key, R.drawable.ic_tabler_folder)),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tintColor),
                        modifier = Modifier.size(18.dp)
                    )
                }
                else -> {
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_folder),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tintColor),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            BasicText(
                name,
                style = TextStyle(color = Color(colors.foreground), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    subtitle,
                    style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showChevron) {
            BasicText("›", style = TextStyle(color = Color(colors.secondary), fontSize = 18.sp))
        }
    }
}

@Composable
private fun SystemCardRow(
    title: String,
    subtitle: String,
    iconRes: Int,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(colors.foreground).copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(colors.secondary)),
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                style = TextStyle(color = Color(colors.foreground), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                BasicText(
                    subtitle,
                    style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        BasicText("›", style = TextStyle(color = Color(colors.secondary), fontSize = 18.sp))
    }
}

@Composable
private fun HabitCardRow(
    habit: Habit,
    colors: UHabitComposeColors,
    onClick: () -> Unit
) {
    val habitColor = remember(habit.color) {
        Color(android.graphics.Color.parseColor(habit.color.toCsvColor()))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(habitColor)
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            BasicText(
                habit.name,
                style = TextStyle(color = Color(colors.foreground), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ========================================================================
// Dialogs & Sheets
// ========================================================================

@Composable
private fun CreateEditContainerBottomSheet(
    containerToEdit: Container?,
    initialParentId: ContainerId?,
    cQueries: ContainerQueries?,
    colors: UHabitComposeColors,
    previewComposeColorPicker: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, parentId: ContainerId?, color: PaletteColor?, icon: IconRef) -> Unit
) {
    var name by rememberSaveable(containerToEdit?.id) { mutableStateOf(containerToEdit?.name ?: "") }
    var colorIndex by rememberSaveable(containerToEdit?.id) { mutableStateOf(containerToEdit?.color?.paletteIndex) }
    var iconStorage by rememberSaveable(containerToEdit?.id) { mutableStateOf(containerToEdit?.icon) }
    val selectedColor = colorIndex?.let(::PaletteColor)
    val selectedIcon = IconRef.fromString(iconStorage)
    val selectedParentId = containerToEdit?.parentId ?: initialParentId

    var isIconPickerOpen by remember { mutableStateOf(false) }
    var showComposeColorPicker by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as FragmentActivity
    ColorPickerBackHandler(enabled = showComposeColorPicker) {
        showComposeColorPicker = false
    }
    ColorPickerBackHandler(enabled = !showComposeColorPicker && !isIconPickerOpen) {
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)).clickable {
            if (showComposeColorPicker) showComposeColorPicker = false else onDismiss()
        })
        if (showComposeColorPicker) {
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding()
                .heightIn(max = maxHeight).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(colors.surface))) {
                UHabitColorPickerContent(
                    selectedColor = selectedColor,

                    onApply = {
                        colorIndex = it.paletteIndex
                        showComposeColorPicker = false
                    },
                    onDismiss = { showComposeColorPicker = false },
                )
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(colors.surface))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = if (containerToEdit != null) "Изменить раздел" else "Новый раздел",
                    style = TextStyle(color = Color(colors.foreground), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                )

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .padding(4.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_x),
                        contentDescription = "Закрыть",
                        colorFilter = ColorFilter.tint(Color(colors.secondary)),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Name input
            BasicText("Название раздела", style = TextStyle(color = Color(colors.secondary), fontSize = 12.sp))
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(colors.surfaceVariant))
                    .border(1.dp, Color(colors.divider), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Поле ввода названия" },
                    textStyle = TextStyle(color = Color(colors.foreground), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) BasicText("Например: Здоровье, Работа...", style = TextStyle(color = Color(colors.secondary), fontSize = 15.sp))
                            inner()
                        }
                    }
                )
            }

            Spacer(Modifier.height(14.dp))

            // Color & Icon controls
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Color Picker button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(colors.surfaceVariant))
                        .border(1.dp, Color(colors.divider), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Выбор цвета" }
                        .clickable {
                            val manager = activity.supportFragmentManager
                            if (previewComposeColorPicker) {
                                showComposeColorPicker = true
                            } else if (manager.findFragmentByTag("compose_container_color_picker") == null && !manager.isStateSaved) {
                                val color = selectedColor?.toFixedAndroidColor() ?: colors.accent
                                val theme = when {
                                    colors.isPureBlack -> ColorPickerDialog.THEME_AMOLED
                                    colors.isDark -> ColorPickerDialog.THEME_DARK
                                    else -> ColorPickerDialog.THEME_LIGHT
                                }
                                ColorPickerDialog.newInstance(color, color, name.trim(), theme).apply {
                                    setListener { colorIndex = it.paletteIndex }
                                    show(manager, "compose_container_color_picker")
                                }
                            }
                        }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (selectedColor != null) Color(android.graphics.Color.parseColor(selectedColor!!.toCsvColor())) else Color(colors.accent))
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicText("Выбрать цвет", style = TextStyle(color = Color(colors.foreground), fontSize = 13.sp))
                    }
                }

                // Icon Picker button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(colors.surfaceVariant))
                        .border(1.dp, Color(colors.divider), RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Выбор значка" }
                        .clickable { isIconPickerOpen = true }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (val icon = selectedIcon) {
                            is IconRef.Emoji -> BasicText(icon.value, style = TextStyle(fontSize = 16.sp))
                            is IconRef.Vector -> Image(painter = painterResource(IconPack.getDrawableRes(icon.key)), contentDescription = null,
                                colorFilter = ColorFilter.tint(Color(colors.secondary)), modifier = Modifier.size(16.dp))
                            else -> Image(painter = painterResource(R.drawable.ic_tabler_folder), contentDescription = null,
                                colorFilter = ColorFilter.tint(Color(colors.secondary)), modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        BasicText(if (selectedIcon is IconRef.None) "Значок" else "Выбран", style = TextStyle(color = Color(colors.foreground), fontSize = 13.sp))
                    }
                }
            }

            if (isIconPickerOpen) {
                Dialog(onDismissRequest = { isIconPickerOpen = false }) {
                    Column(Modifier.fillMaxWidth().imePadding().clip(RoundedCornerShape(16.dp)).background(Color(colors.surface))) {
                        UHabitIconPickerContent(
                            selectedIcon = selectedIcon,
                            tintColor = colors.accent,
                            onIconSelected = {
                                iconStorage = IconRef.toStorageString(it)
                                isIconPickerOpen = false
                            }
                        )
                        BasicText("Отмена", modifier = Modifier.align(Alignment.End).clickable { isIconPickerOpen = false }.padding(16.dp),
                            style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Action buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(colors.surfaceVariant))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("Отмена", style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (name.trim().isNotEmpty()) Color(colors.accent) else Color(colors.accent).copy(alpha = 0.4f))
                        .clickable(enabled = name.trim().isNotEmpty()) {
                            onSave(name.trim(), selectedParentId, selectedColor, selectedIcon)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("Сохранить", style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
        }
    }
}

@Composable
private fun MoveContainerBottomSheet(
    container: Container,
    cQueries: ContainerQueries?,
    colors: UHabitComposeColors,
    onDismiss: () -> Unit,
    onMoveTo: (ContainerId?) -> Unit
) {
    val roots = remember { cQueries?.roots(includeArchived = false)?.filter { it.id != container.id }.orEmpty() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(colors.surface))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            BasicText("Переместить «${container.name}»", style = TextStyle(color = Color(colors.foreground), fontSize = 17.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(14.dp))

            // Option: Move to Root
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(colors.surfaceVariant))
                    .clickable { onMoveTo(null) }
                    .padding(12.dp)
            ) {
                BasicText("Сделать корневой сферой (Главный уровень)", style = TextStyle(color = Color(colors.accent), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }

            Spacer(Modifier.height(10.dp))

            // Option: Move inside another container
            roots.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onMoveTo(candidate.id) }
                        .padding(12.dp)
                ) {
                    BasicText("Внутрь «${candidate.name}»", style = TextStyle(color = Color(colors.foreground), fontSize = 14.sp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(colors.surfaceVariant))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                BasicText("Отмена", style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun AncestorsBottomSheet(
    currentContainerId: ContainerId,
    cQueries: ContainerQueries?,
    colors: UHabitComposeColors,
    onDismiss: () -> Unit,
    onSelectAncestor: (ContainerId?) -> Unit
) {
    val pathNodes = remember(currentContainerId) {
        cQueries?.path(currentContainerId)?.nodes.orEmpty()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(colors.surface))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            BasicText("Иерархия разделов", style = TextStyle(color = Color(colors.foreground), fontSize = 17.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(12.dp))

            // Root option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectAncestor(null) }
                    .padding(12.dp)
            ) {
                BasicText("Главная (Все разделы)", style = TextStyle(color = Color(colors.accent), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }

            pathNodes.forEachIndexed { idx, nodeId ->
                val nodeName = cQueries?.find(nodeId)?.name.orEmpty()
                val isCurrent = nodeId == currentContainerId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isCurrent) { onSelectAncestor(nodeId) }
                        .padding(start = (12 + idx * 12).dp, top = 8.dp, bottom = 8.dp, end = 12.dp)
                ) {
                    BasicText(
                        text = if (isCurrent) "• $nodeName (Текущий)" else "› $nodeName",
                        style = TextStyle(
                            color = if (isCurrent) Color(colors.foreground) else Color(colors.secondary),
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(colors.surfaceVariant))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                BasicText("Закрыть", style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun AssignHabitBottomSheet(
    habit: Habit,
    facade: HabitOrganizationFacade,
    colors: UHabitComposeColors,
    onDismiss: () -> Unit,
    onSelectContainer: (ContainerId) -> Unit
) {
    val roots = remember { facade.containerQueries?.roots(includeArchived = false).orEmpty() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(colors.surface))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            BasicText("Назначить «${habit.name}» в раздел", style = TextStyle(color = Color(colors.foreground), fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(12.dp))

            roots.forEach { container ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectContainer(container.id) }
                        .padding(12.dp)
                ) {
                    BasicText(container.name, style = TextStyle(color = Color(colors.foreground), fontSize = 15.sp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(colors.surfaceVariant))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                BasicText("Отмена", style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun ArchiveConfirmDialog(
    container: Container,
    colors: UHabitComposeColors,
    onDismiss: () -> Unit,
    onConfirmArchive: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(colors.surface))
                .clickable(enabled = false) {}
                .padding(20.dp)
        ) {
            BasicText("Переместить в архив?", style = TextStyle(color = Color(colors.foreground), fontSize = 17.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = "Раздел «${container.name}» и его подразделы будут скрыты из активного списка. Вы сможете восстановить их в любой момент из Архива.",
                style = TextStyle(color = Color(colors.secondary), fontSize = 14.sp)
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(colors.surfaceVariant))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("Отмена", style = TextStyle(color = Color(colors.secondary), fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(colors.accent))
                        .clickable { onConfirmArchive() },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText("В архив", style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}
