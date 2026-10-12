package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.ContainerQueries
import org.isoron.uhabits.core.containers.OrganizationStore
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskRepository

/** Detached immutable records and bounded derived indexes; no repository calls during rendering. */
internal class TasksWorkspaceSnapshot private constructor(
    val roots: List<UserTask>,
    val subtaskCounts: Map<TaskId, Pair<Int, Int>>,
    val containers: Map<ContainerId, Container>,
    val children: Map<ContainerId?, List<Container>>,
    val availableContainers: List<OrganizationalItem>,
    val pendingCounts: Map<ContainerId, Int>,
) {
    fun scope(id: ContainerId): Set<ContainerId> {
        if (id !in containers) return emptySet()
        val result = mutableSetOf<ContainerId>()
        fun visit(current: ContainerId) {
            if (!result.add(current)) return
            children[current].orEmpty().forEach { visit(it.id) }
        }
        visit(id)
        return result
    }

    companion object {
        fun load(tasks: UserTaskRepository, queries: ContainerQueries?): TasksWorkspaceSnapshot {
            val containers = if (queries is OrganizationStore) queries.getAllContainers(includeDeleted = true) else {
                val result = mutableListOf<Container>()
                val seen = mutableSetOf<ContainerId>()
                fun visit(container: Container) {
                    if (!seen.add(container.id)) return
                    result.add(container)
                    queries?.children(container.id)?.forEach(::visit)
                }
                queries?.roots()?.forEach(::visit)
                result
            }
            return build(tasks.getAll(), containers)
        }

        fun build(tasks: List<UserTask>, allContainers: List<Container>): TasksWorkspaceSnapshot {
            val roots = tasks.filter { it.parentTaskId == null }
            val counts = mutableMapOf<TaskId, Pair<Int, Int>>()
            for (task in tasks) {
                val parent = task.parentTaskId ?: continue
                val old = counts[parent] ?: (0 to 0)
                counts[parent] = (old.first + if (task.isCompleted) 1 else 0) to old.second + 1
            }
            val rawChildren = allContainers.groupBy { it.parentId }
                .mapValues { (_, values) -> values.sortedWith(compareBy<Container> { it.siblingOrder }.thenBy { it.id.value }) }
            val active = linkedMapOf<ContainerId, Container>()
            val available = mutableListOf<OrganizationalItem>()
            val children = mutableMapOf<ContainerId?, MutableList<Container>>()
            val directCounts = roots.filter { !it.isCompleted && it.containerId != null }.groupingBy { it.containerId!! }.eachCount()
            val pending = mutableMapOf<ContainerId, Int>()
            fun visit(container: Container, path: String): Int {
                if (container.isArchived || container.deletedAt != null || container.id in active) return 0
                active[container.id] = container
                children.getOrPut(container.parentId) { mutableListOf() }.add(container)
                val currentPath = if (path.isEmpty()) container.name else "$path / ${container.name}"
                available.add(OrganizationalItem(container.id.value, container.name, container.color, container.icon,
                    currentPath, container.parentId == null))
                var count = directCounts[container.id] ?: 0
                rawChildren[container.id].orEmpty().forEach { count += visit(it, currentPath) }
                pending[container.id] = count
                return count
            }
            rawChildren[null].orEmpty().forEach { visit(it, "") }
            return TasksWorkspaceSnapshot(roots.toList(), counts.toMap(), active.toMap(),
                children.mapValues { it.value.toList() }, available.toList(), pending.toMap())
        }
    }
}
