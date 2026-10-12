package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.UserTask
import org.junit.Assert.*
import org.junit.Test

class TasksWorkspaceSnapshotTest {
    private fun container(id: String, parent: String? = null, archived: Boolean = false, deleted: Long? = null) =
        Container(ContainerId(id), parent?.let(::ContainerId), id, null, null, 0, archived, 0, 0, deleted, OrganizationRevision(1))
    private fun task(id: String, container: String? = null, parent: String? = null, completed: Boolean = false) =
        UserTask(TaskId(id), id, containerId = container?.let(::ContainerId), parentTaskId = parent?.let(::TaskId),
            status = if (completed) TaskStatus.COMPLETED else TaskStatus.PENDING, createdAt = 0, updatedAt = 0)

    @Test fun nestedTaskAndContainerCountsRetainExistingScopeSemantics() {
        val snapshot = TasksWorkspaceSnapshot.build(listOf(task("root", "a"), task("child-root", "b"),
            task("complete", "b", completed = true), task("sub", "a", "root"),
            task("sub-complete", "a", "root", true), task("inbox")),
            listOf(container("a"), container("b", "a"), container("c", "b")))
        assertEquals(setOf(ContainerId("a"), ContainerId("b"), ContainerId("c")), snapshot.scope(ContainerId("a")))
        assertEquals(2, snapshot.pendingCounts[ContainerId("a")])
        assertEquals(1, snapshot.pendingCounts[ContainerId("b")])
        assertEquals(1 to 2, snapshot.subtaskCounts[TaskId("root")])
        assertEquals(4, snapshot.roots.size)
        assertEquals("a / b / c", snapshot.availableContainers.last().path)
    }

    @Test fun archivedDeletedAndOrphanBranchesNeverLeakIntoFiltersOrNavigation() {
        val snapshot = TasksWorkspaceSnapshot.build(listOf(task("hidden", "leaf"), task("visible", "a")),
            listOf(container("a"), container("archived", "a", archived = true), container("leaf", "archived"),
                container("deleted", deleted = 1), container("orphan", "missing")))
        assertEquals(setOf(ContainerId("a")), snapshot.containers.keys)
        assertEquals(1, snapshot.pendingCounts[ContainerId("a")])
        assertTrue(snapshot.scope(ContainerId("leaf")).isEmpty())
        assertEquals(listOf("a"), snapshot.availableContainers.map { it.name })
    }

    @Test fun snapshotIndexesDoNotChangeWhenInputListsAreMutated() {
        val tasks = mutableListOf(task("one", "a"))
        val containers = mutableListOf(container("a"))
        val snapshot = TasksWorkspaceSnapshot.build(tasks, containers)
        tasks.clear(); containers.clear()
        assertEquals(1, snapshot.roots.size)
        assertEquals(1, snapshot.containers.size)
    }
}
