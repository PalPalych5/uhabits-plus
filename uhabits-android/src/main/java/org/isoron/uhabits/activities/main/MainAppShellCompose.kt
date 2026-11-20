/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.main

import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme

/**
 * Modern Compose-native application navigation shell for [MainActivity].
 * Coordinates adaptive wide-screen sidebar, statistics secondary toolbar,
 * and bottom navigation with the Android FragmentContainerView content host.
 */
@Composable
internal fun MainAppShell(
    currentDestination: MainDestination,
    sidebarDestination: MainDestination,
    bottomSelection: BottomWorkspaceSelection,
    isBottomNavVisible: Boolean,
    isNextArchitecture: Boolean,
    isWide: Boolean,
    onBottomDestination: (MainDestination) -> Unit,
    onSidebarDestination: (MainDestination) -> Unit,
    onSecondaryToolbarSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalUHabitComposeColors.current
    val showBottomBar = !isWide && isBottomNavVisible &&
        currentDestination != MainDestination.ARCHIVE &&
        currentDestination != MainDestination.SETTINGS

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(colors.background))
    ) {
        if (isWide) {
            // Adaptive Two-Pane Layout for Tablets & Large Screens (>= 600dp)
            Row(Modifier.fillMaxSize()) {
                if (currentDestination != MainDestination.TASKS) {
                    Box(
                        Modifier
                            .width(240.dp)
                            .fillMaxHeight()
                            .background(Color(colors.surface))
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        MainWorkspaceSidebar(
                            destination = sidebarDestination,
                            onDestination = onSidebarDestination
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (currentDestination == MainDestination.STATISTICS) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        ) {
                            StatisticsWorkspaceToolbar(onSettings = onSecondaryToolbarSettings)
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        FragmentContainerHost(colors)
                    }
                }
            }
        } else {
            // Standard Phone Single-Pane Navigation Shell
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    FragmentContainerHost(colors)
                }

                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(250)
                    ) + fadeIn(animationSpec = tween(250)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(220)
                    ) + fadeOut(animationSpec = tween(220)),
                ) {
                    BottomNavigationHost(
                        bottomSelection = bottomSelection,
                        isNext = isNextArchitecture,
                        colors = colors,
                        onDestination = onBottomDestination
                    )
                }
            }
        }
    }
}

@Composable
private fun FragmentContainerHost(
    colors: UHabitComposeColors,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            FragmentContainerView(context).apply {
                id = R.id.mainContent
                setBackgroundColor(colors.background)
            }
        },
        update = { view ->
            view.setBackgroundColor(colors.background)
        },
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun BottomNavigationHost(
    bottomSelection: BottomWorkspaceSelection,
    isNext: Boolean,
    colors: UHabitComposeColors,
    onDestination: (MainDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                id = R.id.bottomNavigationContainer
                setBackgroundColor(colors.background)
                val composeChild = ComposeView(context).apply {
                    id = R.id.bottomNavigation
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                }
                addView(
                    composeChild,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        },
        update = { container ->
            container.setBackgroundColor(colors.background)
            val composeChild = container.findViewById<ComposeView>(R.id.bottomNavigation)
            composeChild.setContent {
                UHabitComposeTheme(colors = colors) {
                    MainBottomNavigation(
                        selection = bottomSelection,
                        isNext = isNext,
                        onDestination = onDestination
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    )
}
