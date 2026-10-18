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
                container("deleted", deleted = 1), container("deleted-leaf", "deleted"),
                container("orphan", "missing"), container("cycle-a", "cycle-b"), container("cycle-b", "cycle-a")))
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

    @Test fun deepChainDoesNotUseCallStackOrRetainAllDescendantSets() {
        val depth = 2000
        val snapshot = TasksWorkspaceSnapshot.build(listOf(task("leaf", "c${depth - 1}")),
            (0 until depth).map { container("c$it", if (it == 0) null else "c${it - 1}") })
        assertEquals(depth, snapshot.scope(ContainerId("c0")).size)
        assertEquals(setOf(ContainerId("c${depth - 1}")), snapshot.scope(ContainerId("c${depth - 1}")))
        assertTrue(snapshot.pendingCounts.values.all { it == 1 })
        // Each request owns its result; callers cannot corrupt a later scope.
        (snapshot.scope(ContainerId("c0")) as MutableSet).clear()
        assertEquals(depth, snapshot.scope(ContainerId("c0")).size)
    }

    @Test fun wideTreeMatchesIndependentDescendantOracleAndSnapshotsStayIsolated() {
        val containers = listOf(container("root")) +
            (0 until 1000).map { container("child$it", "root", archived = it % 10 == 0) }
        val tasks = (0 until 1000).map { task("task$it", "child$it", completed = it % 3 == 0) }
        val snapshot = TasksWorkspaceSnapshot.build(tasks, containers)
        val expected = containers.filterNot { it.isArchived }.map { it.id }.toSet()
        assertEquals(expected, snapshot.scope(ContainerId("root")))
        assertEquals(tasks.count { !it.isCompleted && it.containerId in expected },
            snapshot.pendingCounts[ContainerId("root")])
        val other = TasksWorkspaceSnapshot.build(emptyList(), emptyList())
        assertTrue(other.scope(ContainerId("root")).isEmpty())
        assertEquals(expected, snapshot.scope(ContainerId("root")))
    }

    @Test fun deeplyNestedContainersCorrectlyComputeScopesAndPendingCounts() {
        // Build 15-level deep container chain: c0 -> c1 -> c2 -> ... -> c14
        val depth = 15
        val containers = (0 until depth).map { i ->
            container(id = "c$i", parent = if (i == 0) null else "c${i - 1}")
        }
        // Task at the leaf: c14
        val tasks = listOf(
            task("leaf-task", container = "c${depth - 1}"),
            task("mid-task", container = "c7")
        )

        val snapshot = TasksWorkspaceSnapshot.build(tasks, containers)

        // c0 should have all c0..c14 in scope
        val c0Scope = snapshot.scope(ContainerId("c0"))
        assertEquals(depth, c0Scope.size)
        for (i in 0 until depth) {
            assertTrue(c0Scope.contains(ContainerId("c$i")))
        }

        // c0 pending count should include leaf-task and mid-task
        assertEquals(2, snapshot.pendingCounts[ContainerId("c0")])
        // c7 pending count should include leaf-task and mid-task
        assertEquals(2, snapshot.pendingCounts[ContainerId("c7")])
        // c8 pending count should only include leaf-task
        assertEquals(1, snapshot.pendingCounts[ContainerId("c8")])
        // c14 pending count should only include leaf-task
        assertEquals(1, snapshot.pendingCounts[ContainerId("c14")])

        // Integration deliberately retains baseline render-time item lookup.
        val containerItems = snapshot.availableContainers.associateBy { ContainerId(it.key) }
        assertEquals(depth, containerItems.size)
        for (i in 0 until depth) {
            val item = containerItems[ContainerId("c$i")]
            assertNotNull(item)
            assertEquals("c$i", item?.key)
        }
    }
}
