/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.activities.common.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CustomDialogsComposeTest {

    @Test
    fun normalizeSelectedChoiceIndexHandlesBounds() {
        assertEquals(-1, normalizeSelectedChoiceIndex(-1, 3))
        assertEquals(0, normalizeSelectedChoiceIndex(0, 3))
        assertEquals(2, normalizeSelectedChoiceIndex(2, 3))
        assertEquals(-1, normalizeSelectedChoiceIndex(3, 3))
        assertEquals(-1, normalizeSelectedChoiceIndex(0, 0))
    }

    @Test
    fun resolveSimpleListRowsPairsOptionalSummaries() {
        val withoutSummaries = resolveSimpleListRows(listOf("One", "Two"), null)
        assertEquals(2, withoutSummaries.size)
        assertEquals("One", withoutSummaries[0].title)
        assertNull(withoutSummaries[0].summary)

        val withSummaries = resolveSimpleListRows(
            items = listOf("Backup A", "Backup B", "Backup C"),
            summaries = listOf("Local · 12 KB", "")
        )
        assertEquals("Local · 12 KB", withSummaries[0].summary)
        assertNull(withSummaries[1].summary)
        assertNull(withSummaries[2].summary)
    }

    @Test
    fun resolveSimpleListRowsPreservesOriginalCharSequences() {
        val title = StringBuilder("Backup")
        val summary = StringBuilder("Local")
        val row = resolveSimpleListRows(listOf(title), listOf(summary)).single()
        assertSame(title, row.title)
        assertSame(summary, row.summary)
    }
}
