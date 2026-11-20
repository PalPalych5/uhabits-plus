/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.main

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import kotlin.math.cos

private enum class WorkspaceItem(
    val destination: MainDestination,
    val title: Int,
    val icon: Int,
    val tag: String
) {
    Habits(MainDestination.HABITS, R.string.habits_title, R.drawable.ic_nav_habits, "btnNavHabits"),
    Tasks(MainDestination.TASKS, R.string.tasks_title, R.drawable.ic_nav_tasks, "btnNavTasks"),
    Statistics(MainDestination.STATISTICS, R.string.reports_title, R.drawable.ic_nav_reports, "btnNavReports"),
    Settings(MainDestination.SETTINGS, R.string.settings, R.drawable.ic_nav_settings, "btnNavSettings")
}

internal data class BottomWorkspaceSelection(
    val destination: MainDestination,
    val activeColor: Int,
    val inactiveColor: Int,
    val animate: Boolean
)

private val TabDeceleration = Easing { fraction ->
    val remaining = 1f - fraction
    1f - remaining * remaining
}
private val TabPressEasing = Easing { fraction ->
    ((1.0 - cos(fraction * Math.PI)) / 2.0).toFloat()
}

// Match the legacy TextView metrics used by the existing workspace chrome.
private val WorkspaceTextStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true))

@Composable
internal fun MainBottomNavigation(
    selection: BottomWorkspaceSelection,
    isNext: Boolean,
    onDestination: (MainDestination) -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    val items = if (isNext) {
        listOf(WorkspaceItem.Habits, WorkspaceItem.Tasks, WorkspaceItem.Statistics)
    } else {
        listOf(WorkspaceItem.Habits, WorkspaceItem.Statistics, WorkspaceItem.Settings)
    }
    Box(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.dividerColor))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEach { item ->
                val selected = item.destination == selection.destination.bottomItemDestination
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.98f else 1f,
                    animationSpec = tween(if (pressed) 80 else 150, easing = TabPressEasing)
                )
                val progress by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = if (selection.animate) tween(200, easing = TabDeceleration) else snap()
                )
                val minAlpha = if (isNext) 1f else 0.54f
                val tint = Color(ColorUtils.blendARGB(selection.inactiveColor, selection.activeColor, progress))
                val alpha = minAlpha + (1f - minAlpha) * progress
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .testTag(item.tag)
                        .selectable(
                            selected = selected,
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onDestination(item.destination) }
                        )
                        .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            scaleX = 1f + 0.06f * progress
                            scaleY = scaleX
                            this.alpha = alpha
                        }
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = stringResource(item.title),
                        style = WorkspaceTextStyle,
                        fontSize = 11.sp,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp).graphicsLayer { this.alpha = alpha }
                    )
                }
            }
        }
    }
}

@Composable
internal fun MainWorkspaceSidebar(
    destination: MainDestination,
    onDestination: (MainDestination) -> Unit
) {
    val colors = LocalUHabitComposeColors.current
    Column(
        Modifier.fillMaxSize().background(colors.surfaceColor)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, top = 24.dp, end = 12.dp, bottom = 16.dp)
            .selectableGroup()
    ) {
        Text(
            text = "UHabit",
            style = WorkspaceTextStyle,
            color = colors.foregroundColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)
        )
        WorkspaceItem.entries.forEach { item ->
            val selected = destination.bottomItemDestination == item.destination
            val tint = if (selected) colors.accentColor else colors.secondaryColor
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .selectable(selected, role = Role.Tab) { onDestination(item.destination) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(item.icon), null, Modifier.size(24.dp), tint = tint)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(item.title),
                    style = WorkspaceTextStyle,
                    color = tint,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
internal fun StatisticsWorkspaceToolbar(onSettings: () -> Unit) {
    val colors = LocalUHabitComposeColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(colors.backgroundColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.reports_title),
            style = WorkspaceTextStyle,
            color = colors.foregroundColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = onSettings),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_nav_settings),
                stringResource(R.string.settings),
                Modifier.size(24.dp),
                tint = colors.secondaryColor
            )
        }
    }
}
