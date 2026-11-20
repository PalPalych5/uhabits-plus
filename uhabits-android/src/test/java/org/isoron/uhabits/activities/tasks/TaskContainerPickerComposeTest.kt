/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.containers.Container
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.OrganizationRevision
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContainerPickerComposeTest {

    private fun container(id: String, parentId: String? = null, name: String = id): Container =
        Container(
            id = ContainerId(id),
            parentId = parentId?.let(::ContainerId),
            name = name,
            color = null,
            icon = null,
            siblingOrder = 0,
            isArchived = false,
            createdAt = 0L,
            updatedAt = 0L,
            deletedAt = null,
            revision = OrganizationRevision(1)
        )

    private val rootSphere = OrganizationalItem(key = "work", name = "Work", color = null)
    private val subProject = OrganizationalItem(key = "app-dev", name = "App Development", color = null)
    private val subModule = OrganizationalItem(key = "feature-tasks", name = "Tasks Feature", color = null)
    private val deepLeaf = OrganizationalItem(key = "compose-migration", name = "Compose Migration", color = null)
    private val studySphere = OrganizationalItem(key = "study", name = "Study", color = null)

    private val containerPaths = mapOf(
        "work" to listOf(
            container("work", null, "Work")
        ),
        "app-dev" to listOf(
            container("work", null, "Work"),
            container("app-dev", "work", "App Development")
        ),
        "feature-tasks" to listOf(
            container("work", null, "Work"),
            container("app-dev", "work", "App Development"),
            container("feature-tasks", "app-dev", "Tasks Feature")
        ),
        "compose-migration" to listOf(
            container("work", null, "Work"),
            container("app-dev", "work", "App Development"),
            container("feature-tasks", "app-dev", "Tasks Feature"),
            container("compose-migration", "feature-tasks", "Compose Migration")
        ),
        "study" to listOf(
            container("study", null, "Study")
        )
    )

    private val allItems = listOf(rootSphere, subProject, subModule, deepLeaf, studySphere)

    @Test
    fun inboxRowIsPresentWhenNotSearchingAndSelectedWhenSelectedIdIsNull() {
        val rows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = null,
            query = "",
            inboxTitle = "Inbox"
        )

        assertEquals(6, rows.size)
        val inbox = rows.first()
        assertNull(inbox.item)
        assertEquals("Inbox", inbox.title)
        assertNull(inbox.pathSubtitle)
        assertEquals(0, inbox.depth)
        assertTrue("Inbox should be selected when selectedId is null", inbox.isSelected)
        assertFalse(inbox.hasChildren)
        assertFalse(inbox.isParent)
    }

    @Test
    fun hierarchyDepthAndParentChevronAreComputedCorrectly() {
        val rows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = "feature-tasks",
            query = "   ", // blank query still behaves as non-searching
            inboxTitle = "Inbox"
        )

        val workRow = rows.first { it.item?.key == "work" }
        assertEquals(0, workRow.depth)
        assertTrue("Work has child app-dev, so hasChildren must be true", workRow.hasChildren)
        assertTrue("isParent must be true when hasChildren and not searching", workRow.isParent)
        assertFalse(workRow.isSelected)

        val appDevRow = rows.first { it.item?.key == "app-dev" }
        assertEquals(1, appDevRow.depth)
        assertTrue(appDevRow.hasChildren)
        assertTrue(appDevRow.isParent)

        val tasksRow = rows.first { it.item?.key == "feature-tasks" }
        assertEquals(2, tasksRow.depth)
        assertTrue(tasksRow.hasChildren)
        assertTrue("feature-tasks is selected", tasksRow.isSelected)

        val deepRow = rows.first { it.item?.key == "compose-migration" }
        assertEquals(3, deepRow.depth)
        assertFalse(deepRow.hasChildren)
        assertFalse(deepRow.isParent)

        val studyRow = rows.first { it.item?.key == "study" }
        assertEquals(0, studyRow.depth)
        assertFalse("Study has no children", studyRow.hasChildren)
        assertFalse(studyRow.isParent)
    }

    @Test
    fun searchHidesInboxFiltersCaseInsensitiveAndProvidesParentPath() {
        val rows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = "app-dev",
            query = "dev",
            inboxTitle = "Inbox"
        )

        assertEquals(1, rows.size)
        val devRow = rows.single()
        assertEquals("app-dev", devRow.item?.key)
        assertEquals("App Development", devRow.title)
        assertEquals("Work", devRow.pathSubtitle)
        assertEquals(0, devRow.depth)
        assertTrue(devRow.isSelected)
        assertFalse("Parent chevron hidden during search", devRow.isParent)
    }

    @Test
    fun searchMultipleLevelsDeepProvidesCompleteParentBreadcrumbs() {
        val rows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = null,
            query = "migration",
            inboxTitle = "Inbox"
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("compose-migration", row.item?.key)
        assertEquals("Work / App Development / Tasks Feature", row.pathSubtitle)
        assertEquals(0, row.depth)
    }

    @Test
    fun searchReturnsEmptyWhenNoMatch() {
        val rows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = null,
            query = "non-existent-container",
            inboxTitle = "Inbox"
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun searchResetRestoresAllContainersAndInbox() {
        // 1. Searching for non-existent item yields empty list
        val filteredRows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = null,
            query = "xyzNonExistent",
            inboxTitle = "Inbox"
        )
        assertTrue(filteredRows.isEmpty())

        // 2. Clearing query back to "" restores all 5 containers + 1 inbox row = 6 rows
        val restoredRows = buildContainerPickerRows(
            items = allItems,
            paths = containerPaths,
            selectedId = null,
            query = "",
            inboxTitle = "Inbox"
        )
        assertEquals(6, restoredRows.size)
        assertNull("First row must be Inbox", restoredRows.first().item)
        assertTrue("Inbox must be selected when selectedId is null", restoredRows.first().isSelected)
    }

    @Test
    fun externalSearchCommandProducesDistinctTokensForRepeatedClears() {
        val cmd1 = ExternalSearchCommand("")
        val cmd2 = ExternalSearchCommand("")
        assertEquals("", cmd1.query)
        assertEquals("", cmd2.query)
        assertTrue("Repeated clear commands must have distinct tokens to trigger LaunchedEffect", cmd1.token != cmd2.token)
    }
}
