/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TasksWorkspaceCompose(
    wide: Boolean,
    drawerOpen: Boolean,
    calendarVisible: Boolean,
    onCloseDrawer: () -> Unit,
    onQuickAddAnchor: (View?) -> Unit,
    navigation: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    listInfo: @Composable () -> Unit,
    list: @Composable () -> Unit,
    calendar: @Composable () -> Unit,
    quickAdd: @Composable () -> Unit,
    dialogs: @Composable () -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val focusManager = LocalFocusManager.current
    val scrimFocus = remember { FocusRequester() }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            focusManager.clearFocus(force = true)
            scrimFocus.requestFocus()
        }
    }

    Box(Modifier.fillMaxSize().background(colors.backgroundColor).semantics { testTagsAsResourceId = true }) {
        Row(
            Modifier
                .fillMaxSize()
                .then(if (drawerOpen) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)
                .focusProperties { onEnter = { if (drawerOpen) cancelFocusChange() } }
                .focusGroup(),
        ) {
            if (wide) {
                Box(
                    Modifier.width(280.dp).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),
                ) {
                    navigation()
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                val bottomInsets = if (wide) {
                    Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                } else {
                    Modifier
                }
                Column(Modifier.widthIn(max = 840.dp).fillMaxSize().then(bottomInsets)) {
                    topBar()
                    if (!calendarVisible) listInfo()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (!calendarVisible) list()
                        RetainedWorkspaceSlot(calendarVisible, Modifier.testTag("tasksCalendar"), calendar)
                    }
                    Box(Modifier.fillMaxWidth().testTag("tasksQuickAdd")) {
                        // The platform Snackbar uses this View's measured top when positioning Undo.
                        AndroidView(
                            factory = { View(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                            modifier = Modifier.matchParentSize(),
                            onReset = null,
                            onRelease = { onQuickAddAnchor(null) },
                            update = { onQuickAddAnchor(it) },
                        )
                        quickAdd()
                    }
                }
            }
        }
        if (!wide) {
            if (drawerOpen) {
                val closeDescription = stringResource(R.string.task_nav_close_desc)
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 120 / 255f))
                        .focusRequester(scrimFocus)
                        .clickable(role = Role.Button, onClick = onCloseDrawer)
                        .semantics { contentDescription = closeDescription },
                )
            }
            RetainedWorkspaceSlot(
                visible = drawerOpen,
                modifier = Modifier.width(300.dp).fillMaxHeight(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(colors.backgroundColor)
                        // Blank space within the drawer belongs to the drawer, not the scrim.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),
                ) {
                    navigation()
                }
            }
        }
        dialogs()
    }
}

// Keep scroll state and calendar effects alive across visibility changes. Only placed
// slots participate in drawing, hit testing, focus search and accessibility.
@Composable
private fun RetainedWorkspaceSlot(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = { Box(Modifier.fillMaxSize(), propagateMinConstraints = true) { content() } }, modifier = modifier) {
        measurables, constraints ->
        if (visible) {
            val placeable = measurables.single().measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        } else {
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }
}
