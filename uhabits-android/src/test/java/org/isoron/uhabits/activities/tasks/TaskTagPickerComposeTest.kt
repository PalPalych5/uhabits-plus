/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.tasks

import org.isoron.uhabits.core.userTasks.tags.TaskTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskTagPickerComposeTest {

    private val work = TaskTag(id = "tag-1", name = "Work")
    private val important = TaskTag(id = "tag-2", name = "Important")
    private val someday = TaskTag(id = "tag-3", name = "Someday")
    private val allTags = listOf(work, important, someday)

    @Test
    fun filterTaskTagsReturnsAllWhenQueryIsBlank() {
        assertEquals(allTags, filterTaskTags(allTags, ""))
        assertEquals(allTags, filterTaskTags(allTags, "   "))
    }

    @Test
    fun filterTaskTagsMatchesCaseInsensitivelyAndTrimsQuery() {
        assertEquals(listOf(important), filterTaskTags(allTags, "  PORT "))
        assertEquals(listOf(work), filterTaskTags(allTags, "work"))
        assertEquals(emptyList<TaskTag>(), filterTaskTags(allTags, "nonexistent"))
    }

    @Test
    fun formatCreateTagQueryReturnsNullForBlankAndCapsAtFortyChars() {
        assertNull(formatCreateTagQuery(""))
        assertNull(formatCreateTagQuery("   "))
        assertEquals("Urgent", formatCreateTagQuery("  Urgent  "))
        val longQuery = "a".repeat(55)
        assertEquals("a".repeat(40), formatCreateTagQuery(longQuery))
    }

    @Test
    fun toggleTagSelectionSupportsMultipleSelectionAndPreservesOrder() {
        val initial = setOf("tag-1")
        val afterAdd = toggleTagSelection(initial, "tag-2")
        assertEquals(setOf("tag-1", "tag-2"), afterAdd)

        val afterRemove = toggleTagSelection(afterAdd, "tag-1")
        assertEquals(setOf("tag-2"), afterRemove)
    }

    @Test
    fun searchFilteringDoesNotAlterSelectedTagIds() {
        val selected = setOf("tag-1", "tag-3")
        val filtered = filterTaskTags(allTags, "Important")
        assertEquals(listOf(important), filtered)
        // Selection state remains independent of filtered view
        assertEquals(setOf("tag-1", "tag-3"), selected)
    }
}
