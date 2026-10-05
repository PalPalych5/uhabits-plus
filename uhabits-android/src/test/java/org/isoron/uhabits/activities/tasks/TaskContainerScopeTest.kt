package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.containers.ContainerId
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskContainerScopeTest {
    private val root = ContainerId("study")
    private val child = ContainerId("database")
    private val leaf = ContainerId("questions")
    private val sibling = ContainerId("speech")
    private val archived = ContainerId("archived-course")
    private val archivedChild = ContainerId("archived-questions")

    @Test fun selectedRootIsIncludedAlongsideDescendants() {
        assertEquals(setOf(root, child, leaf), TaskContainerScope.ids(root, setOf(child, leaf), setOf(root, child, leaf, sibling)))
    }

    @Test fun selectedDescendantIncludesItsOwnTasksAndChildren() {
        assertEquals(setOf(child, leaf), TaskContainerScope.ids(child, setOf(leaf), setOf(root, child, leaf)))
    }

    @Test fun leafScopeRetainsDirectTasksDespiteEmptySubtreeQuery() {
        assertEquals(setOf(leaf), TaskContainerScope.ids(leaf, emptySet(), setOf(root, child, leaf)))
    }

    @Test fun archivedBranchAndEffectivelyArchivedChildrenAreExcluded() {
        assertEquals(setOf(root, child), TaskContainerScope.ids(root, setOf(child, archived, archivedChild), setOf(root, child, sibling)))
        assertEquals(emptySet<ContainerId>(), TaskContainerScope.ids(archived, setOf(archivedChild), setOf(root, child, sibling)))
    }
}
