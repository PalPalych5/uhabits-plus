/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.IconRef

data class ContainerNavItem(
    val id: ContainerId,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val isCollapsed: Boolean,
    val count: Int,
    val iconRef: IconRef?,
    val color: Int?,
    val isSelected: Boolean
)

/**
 * Modern Compose-native navigation drawer and sidebar for the Tasks screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TasksNavigationCompose(
    isWide: Boolean,
    activeSmartView: TaskSmartView,
    smartViewsExpanded: Boolean,
    visibleSmartViews: List<TaskSmartView>,
    smartViewCounts: Map<TaskSmartView, Int>,
    containers: List<ContainerNavItem>,
    selectedContainerFilter: ContainerId?,
    localParentName: String?,
    colors: UHabitComposeColors = LocalUHabitComposeColors.current,
    modifier: Modifier = Modifier,
    onSelectSmartView: (TaskSmartView) -> Unit,
    onToggleSmartViewsExpanded: () -> Unit,
    onSelectContainer: (ContainerId) -> Unit,
    onToggleContainerCollapse: (String) -> Unit,
    onOpenContainerChildren: (ContainerId) -> Unit,
    onNavigateToParent: () -> Unit,
    onContainerLongClick: (ContainerId) -> Unit,
    onContainerMoreClick: (ContainerId) -> Unit,
    onManageHiddenSections: () -> Unit,
    onCreateContainer: () -> Unit,
    onOpenSettings: () -> Unit,
    onWideTabSelected: (String) -> Unit = {}
) {
    UHabitComposeTheme(colors = colors) {
        val themeColors = LocalUHabitComposeColors.current
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        val smartIcons = mapOf(
            TaskSmartView.TODAY to R.drawable.ic_cat_calendar,
            TaskSmartView.OVERDUE to R.drawable.ic_cat_flag,
            TaskSmartView.INBOX to R.drawable.ic_cat_folder_open,
            TaskSmartView.UPCOMING to R.drawable.ic_cat_calendar_clock,
            TaskSmartView.ALL to R.drawable.ic_cat_list_todo,
            TaskSmartView.COMPLETED to R.drawable.ic_tasks_completed
        )

        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .semantics { testTagsAsResourceId = true }
                .background(Color(themeColors.background))
        ) {
            val scrollModifier = if (maxHeight.value.isFinite()) {
                Modifier.verticalScroll(scrollState)
            } else {
                Modifier
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(scrollModifier)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
            if (isWide) {
                // Wide tablet navigation: Habits, Tasks, Statistics
                val tabs = listOf(
                    Triple("Habits", stringResource(R.string.task_nav_habits), R.drawable.ic_tasks_completed),
                    Triple("Tasks", stringResource(R.string.tasks_title), R.drawable.ic_cat_list_todo),
                    Triple("Statistics", stringResource(R.string.task_nav_statistics), R.drawable.ic_settings_statistics)
                )
                tabs.forEach { (key, name, icon) ->
                    NavigationRow(
                        label = name,
                        iconRes = icon,
                        isSelected = key == "Tasks",
                        colors = themeColors,
                        onClick = { onWideTabSelected(key) }
                    )
                }
                NavigationDivider(themeColors)
            } else {
                // Phone Drawer Header
                BasicText(
                    text = stringResource(R.string.tasks_title),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
                    style = TextStyle(
                        color = Color(themeColors.foreground),
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            // Smart Views
            visibleSmartViews.forEach { smart ->
                val count = smartViewCounts[smart] ?: 0
                val iconRes = smartIcons[smart] ?: R.drawable.ic_cat_list_todo
                val isSelected = selectedContainerFilter == null && activeSmartView == smart

                NavigationRow(
                    label = smart.label(context),
                    iconRes = iconRes,
                    count = if (smart == TaskSmartView.COMPLETED) null else count.takeIf { it > 0 },
                    isSelected = isSelected,
                    colors = themeColors,
                    onClick = { onSelectSmartView(smart) },
                    modifier = Modifier.testTag("nav_smart_${smart.name}")
                )
            }

            // More / Less Smart Views toggle
            val chevronRotation by animateFloatAsState(
                targetValue = if (smartViewsExpanded) 180f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "smartViewsChevron"
            )
            val toggleLabel = stringResource(
                if (smartViewsExpanded) R.string.task_nav_less_views else R.string.task_nav_more_views
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onToggleSmartViewsExpanded)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_chevron_down),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(themeColors.secondary)),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(
                    text = toggleLabel,
                    style = TextStyle(
                        color = Color(themeColors.secondary),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal
                    )
                )
            }

            NavigationDivider(themeColors)

            // Containers Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BasicText(
                    text = stringResource(R.string.containers_title).uppercase(),
                    modifier = Modifier.weight(1f),
                    style = TextStyle(
                        color = Color(themeColors.secondary),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Medium
                    )
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Manage hidden sections
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClick = onManageHiddenSections
                            )
                            .semantics {
                                this.role = Role.Button
                                this.contentDescription = context.getString(R.string.section_hidden_sections)
                            }
                            .testTag("manage_hidden_sections_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tabler_dots_vertical),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color(themeColors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Add section
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClick = onCreateContainer
                            )
                            .semantics {
                                this.role = Role.Button
                                this.contentDescription = context.getString(R.string.containers_create_section)
                            }
                            .testTag("add_section_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_tabler_plus),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color(themeColors.secondary)),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Local tree root navigation (Back to parent)
            if (localParentName != null) {
                NavigationRow(
                    label = localParentName,
                    iconRes = R.drawable.ic_tasks_parent,
                    colors = themeColors,
                    onClick = onNavigateToParent
                )
            }

            // Containers list or empty state
            if (containers.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onCreateContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_tabler_plus),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color(themeColors.accent)),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    BasicText(
                        text = stringResource(R.string.containers_create_section),
                        style = TextStyle(
                            color = Color(themeColors.accent),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Normal
                        )
                    )
                }
            } else {
                containers.forEach { item ->
                    ContainerRow(
                        item = item,
                        colors = themeColors,
                        onClick = { onSelectContainer(item.id) },
                        onLongClick = { onContainerLongClick(item.id) },
                        onToggleCollapse = { onToggleContainerCollapse(item.id.value) },
                        onOpenChildren = { onOpenContainerChildren(item.id) },
                        onMoreClick = { onContainerMoreClick(item.id) }
                    )
                }
            }

            NavigationDivider(themeColors)

            // Settings row
            NavigationRow(
                label = stringResource(R.string.settings),
                iconRes = R.drawable.ic_nav_settings,
                colors = themeColors,
                onClick = onOpenSettings,
                modifier = Modifier.testTag("nav_settings")
            )
        }
        }
    }
}

@Composable
private fun NavigationRow(
    label: String,
    iconRes: Int,
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier,
    count: Int? = null,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    val accentColor = Color(colors.accent)
    val bgModifier = if (isSelected) {
        Modifier.background(
            accentColor.copy(alpha = if (colors.isDark) 0.16f else 0.09f),
            RoundedCornerShape(8.dp)
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(bgModifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.role = Role.Button
                this.selected = isSelected
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (isSelected) accentColor else Color(colors.secondary)),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = label,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = if (isSelected) accentColor else Color(colors.foreground),
                fontSize = 14.sp,
                fontFamily = FontFamily.Default,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (count != null && count > 0) {
            BasicText(
                text = count.toString(),
                style = TextStyle(
                    color = Color(colors.secondary),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontFeatureSettings = "tnum"
                )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerRow(
    item: ContainerNavItem,
    colors: UHabitComposeColors,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleCollapse: () -> Unit,
    onOpenChildren: () -> Unit,
    onMoreClick: () -> Unit
) {
    val accentColor = Color(colors.accent)
    val itemColor = item.color?.let { Color(it) } ?: if (item.isSelected) accentColor else Color(colors.secondary)
    val isSelected = item.isSelected

    val bgModifier = if (isSelected) {
        Modifier.background(
            accentColor.copy(alpha = if (colors.isDark) 0.16f else 0.09f),
            RoundedCornerShape(8.dp)
        )
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(bgModifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics {
                this.role = Role.Button
                this.selected = isSelected
            }
            .padding(start = (8 + item.depth * 14).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Expand/collapse chevron or spacer
        if (item.hasChildren) {
            Box(
                modifier = Modifier
                    .size(32.dp, 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        if (item.depth >= 2) onOpenChildren() else onToggleCollapse()
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(
                        if (item.isCollapsed) R.drawable.ic_tabler_chevron_right else R.drawable.ic_tabler_chevron_down
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(colors.secondary)),
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(32.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Container Icon (Emoji, Vector, or default folder)
        when (val iconRef = item.iconRef) {
            is IconRef.Emoji -> {
                BasicText(
                    text = iconRef.value,
                    modifier = Modifier.size(20.dp),
                    style = TextStyle(fontSize = 15.sp, textAlign = TextAlign.Center)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            is IconRef.Vector -> {
                val res = IconPack.getDrawableRes(iconRef.key, R.drawable.ic_tabler_folder)
                Image(
                    painter = painterResource(res),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(itemColor),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            else -> {
                Image(
                    painter = painterResource(
                        if (item.depth == 0) R.drawable.ic_tabler_folder else R.drawable.ic_cat_list_todo
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(itemColor),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        // Container Name
        BasicText(
            text = item.name,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = if (isSelected) accentColor else Color(colors.foreground),
                fontSize = 14.sp,
                fontFamily = FontFamily.Default,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Pending count badge
        if (item.count > 0) {
            BasicText(
                text = item.count.toString(),
                modifier = Modifier.padding(horizontal = 6.dp),
                style = TextStyle(
                    color = Color(colors.secondary),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontFeatureSettings = "tnum"
                )
            )
        }

        // More options button (visible when row is selected)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onMoreClick),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_tabler_dots_vertical),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(accentColor),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun NavigationDivider(colors: UHabitComposeColors) {
    Spacer(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(colors.divider))
    )
}
