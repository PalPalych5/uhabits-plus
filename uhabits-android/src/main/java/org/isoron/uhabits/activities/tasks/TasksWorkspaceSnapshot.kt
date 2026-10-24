package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.OrganizationStore
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository
import org.isoron.uhabits.core.userTasks.tags.TaskTag
import org.isoron.uhabits.core.userTasks.tags.TaskTagRepository

/** Detached immutable records and bounded derived indexes; no repository calls during rendering. */
internal class TasksWorkspaceSnapshot private constructor(
    val roots: List<UserTask>,
    val subtaskCounts: Map<TaskId, Pair<Int, Int>>,
    val containers: Map<ContainerId, Container>,
    val children: Map<ContainerId?, List<Container>>,
    val availableContainers: List<OrganizationalItem>,
    val pendingCounts: Map<ContainerId, Int>,
    val taskTags: Map<TaskId, List<TaskTag>>,
    val availableTags: List<TaskTag>,
) {
    // Only the selected subtree is needed by rendering. Retaining every transitive
    // closure takes quadratic space for a chain of containers.
    fun scope(id: ContainerId): Set<ContainerId> {
        if (id !in containers) return emptySet()
        val result = linkedSetOf<ContainerId>()
        val pending = ArrayDeque<ContainerId>()
        pending.addLast(id)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!result.add(current)) continue
            children[current].orEmpty().asReversed().forEach { pending.addLast(it.id) }
        }
        return result
    }

    companion object {
        fun load(tasks: UserTaskRepository, queries: ContainerQueries?, tags: TaskTagRepository? = null): TasksWorkspaceSnapshot {
            val containers = if (queries is OrganizationStore) queries.getAllContainers(includeDeleted = true) else {
                val result = mutableListOf<Container>()
                val seen = mutableSetOf<ContainerId>()
                val pending = ArrayDeque<Container>()
                queries?.roots()?.asReversed()?.forEach(pending::addLast)
                while (pending.isNotEmpty()) {
                    val container = pending.removeLast()
                    if (!seen.add(container.id)) continue
                    result.add(container)
                    queries?.children(container.id)?.asReversed()?.forEach(pending::addLast)
                }
                result
            }
            val records = tasks.getAll()
            return build(records, containers,
                tags?.getTaskTags().orEmpty(),
                tags?.getAll().orEmpty())
        }

        fun build(tasks: List<UserTask>, allContainers: List<Container>,
                  taskTags: Map<TaskId, List<TaskTag>> = emptyMap(), availableTags: List<TaskTag> = emptyList()): TasksWorkspaceSnapshot {
            val roots = tasks.filter { it.parentTaskId == null }
            val counts = mutableMapOf<TaskId, Pair<Int, Int>>()
            for (task in tasks) {
                val parent = task.parentTaskId ?: continue
                val old = counts[parent] ?: (0 to 0)
                counts[parent] = (old.first + if (task.isCompleted) 1 else 0) to (old.second + 1)
            }

            val rawChildren = allContainers.groupBy { it.parentId }
                .mapValues { (_, values) -> values.sortedWith(compareBy<Container> { it.siblingOrder }.thenBy { it.id.value }) }
            val active = linkedMapOf<ContainerId, Container>()
            val available = mutableListOf<OrganizationalItem>()
            val children = mutableMapOf<ContainerId?, MutableList<Container>>()
            val directCounts = roots.filter { !it.isCompleted && it.containerId != null }
                .groupingBy { it.containerId!! }.eachCount()
            val pending = mutableMapOf<ContainerId, Int>()

            val stack = ArrayDeque<Pair<Container, String>>()
            rawChildren[null].orEmpty().asReversed().forEach { stack.addLast(it to "") }
            while (stack.isNotEmpty()) {
                val (container, path) = stack.removeLast()
                if (container.isArchived || container.deletedAt != null || container.id in active) continue
                active[container.id] = container
                children.getOrPut(container.parentId) { mutableListOf() }.add(container)
                val currentPath = if (path.isEmpty()) container.name else "$path / ${container.name}"
                available.add(OrganizationalItem(container.id.value, container.name, container.color, container.icon,
                    currentPath, container.parentId == null))
                pending[container.id] = directCounts[container.id] ?: 0
                rawChildren[container.id].orEmpty().asReversed().forEach { stack.addLast(it to currentPath) }
            }
            // Reverse preorder visits each child before its parent, without recursion.
            for (container in active.values.toList().asReversed()) {
                val parent = container.parentId ?: continue
                pending[parent] = (pending[parent] ?: 0) + pending.getValue(container.id)
            }

            val immutableChildren = children.mapValues { it.value.toList() }

            return TasksWorkspaceSnapshot(
                roots = roots,
                subtaskCounts = counts,
                containers = active,
                children = immutableChildren,
                availableContainers = available,
                pendingCounts = pending,
                taskTags = taskTags,
                availableTags = availableTags,
            )
        }
    }
}
