/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.tags.TaskTag

internal enum class TaskListDisclosure { OVERDUE, COMPLETED }

internal sealed interface TaskListItem {
    val key: String

    data class Header(
        override val key: String,
        val title: String,
        val expanded: Boolean = true,
        val disclosure: TaskListDisclosure? = null,
    ) : TaskListItem

    data class Row(val task: UserTask, val hideContainer: Boolean) : TaskListItem {
        override val key: String get() = "task:${task.id.value}"
    }
}

internal fun TaskListSections.toListItems(
    overdueTitle: String,
    activeTitle: String,
    completedTitle: String,
    overdueExpanded: Boolean,
    completedExpanded: Boolean,
    hideContainer: Boolean,
): List<TaskListItem> = buildList {
    if (overdue.isNotEmpty()) {
        add(TaskListItem.Header("header:overdue", overdueTitle, overdueExpanded, TaskListDisclosure.OVERDUE))
        if (overdueExpanded) overdue.forEach { add(TaskListItem.Row(it, hideContainer)) }
    }
    if (groups.isEmpty()) {
        if (overdue.isNotEmpty() && active.isNotEmpty()) add(TaskListItem.Header("header:active", activeTitle))
        active.forEach { add(TaskListItem.Row(it, hideContainer)) }
    } else {
        groups.forEach { group ->
            add(TaskListItem.Header("group:${group.key}", group.title))
            group.tasks.forEach { add(TaskListItem.Row(it, hideContainer = true)) }
        }
    }
    if (completed.isNotEmpty()) {
        add(TaskListItem.Header("header:completed", completedTitle, completedExpanded, TaskListDisclosure.COMPLETED))
        if (completedExpanded) completed.forEach { add(TaskListItem.Row(it, hideContainer)) }
    }
}

internal data class TasksListContent(
    val items: List<TaskListItem>,
    val today: LocalDate,
    val nowMillis: Long,
    val subtaskCounts: Map<TaskId, Pair<Int, Int>>,
    val containers: Map<ContainerId, OrganizationalItem>,
    val tags: Map<TaskId, List<TaskTag>>,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TasksListCompose(
    content: TasksListContent,
    state: LazyListState,
    selectedIds: Set<TaskId>,
    onDisclosure: (TaskListDisclosure) -> Unit,
    onClick: (UserTask) -> Unit,
    onComplete: (UserTask) -> Unit,
    onSelect: (UserTask) -> Unit,
    onQuickMenu: (UserTask) -> Unit,
    onChangeDue: (UserTask) -> Unit,
    onMoveContainer: (UserTask) -> Unit,
    onSwipe: (UserTask, TaskSwipeAction) -> Unit,
) {
    val colors = LocalUHabitComposeColors.current
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The weighted Android host can measure once without its final height.
        if (!constraints.hasBoundedHeight) return@BoxWithConstraints
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.backgroundColor)
                .semantics { testTagsAsResourceId = true }
                .testTag("tasksList"),
            contentPadding = PaddingValues(top = 2.dp, bottom = 64.dp),
        ) {
            items(
                items = content.items,
                key = { it.key },
                contentType = { if (it is TaskListItem.Row) "task" else "header" },
            ) { item ->
                when (item) {
                    is TaskListItem.Header -> TaskSectionHeaderCompose(
                        title = item.title,
                        expanded = item.expanded,
                        modifier = Modifier.testTag(item.key),
                        onToggle = item.disclosure?.let { disclosure -> { onDisclosure(disclosure) } },
                    )
                    is TaskListItem.Row -> {
                        val task = item.task
                        val container = task.containerId?.let(content.containers::get)
                        val containerName = if (item.hideContainer) null else {
                            container?.name ?: if (task.isCompleted) null else context.getString(R.string.task_smart_inbox)
                        }
                        SwipeableTask(
                            enabled = selectedIds.isEmpty(),
                            onSwipe = { onSwipe(task, it) },
                        ) {
                            TaskRowCompose(
                                task = task,
                                isCompleted = task.isCompleted,
                                isSelected = task.id in selectedIds,
                                selectionActive = selectedIds.isNotEmpty(),
                                subtaskCount = content.subtaskCounts[task.id],
                                containerName = containerName,
                                containerColor = container?.color?.let {
                                    TaskThemeUtils.resolvePaletteColor(context, it, colors.accent)
                                },
                                tags = content.tags[task.id].orEmpty(),
                                today = content.today,
                                nowMillis = content.nowMillis,
                                modifier = Modifier.testTag(item.key),
                                onTaskClicked = { onClick(task) },
                                onToggleComplete = { onComplete(task) },
                                onTaskLongClicked = { onSelect(task) },
                                onShowContextMenu = { onQuickMenu(task) },
                                onChangeDue = { onChangeDue(task) },
                                onMoveContainer = { onMoveContainer(task) },
                            )
                        }
                    }
                }
            }
        }
    }
}
