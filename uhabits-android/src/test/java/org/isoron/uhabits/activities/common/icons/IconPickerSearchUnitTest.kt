/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPickerSearchUnitTest {

    @Test
    fun testVectorSearch_englishQuery_matchesExpectedIcons() {
        val results = IconPack.search("briefcase")
        assertTrue("Expected non-empty results for 'briefcase'", results.isNotEmpty())
        assertTrue(
            "Expected results to contain lucide:briefcase or related variants",
            results.any { it.key.contains("briefcase") || it.tags.any { tag -> tag.contains("briefcase") } }
        )
    }

    @Test
    fun testVectorSearch_russianQuery_matchesExpectedIcons() {
        val results = IconPack.search("работа")
        assertTrue("Expected non-empty results for Russian 'работа'", results.isNotEmpty())
        assertTrue(
            "Expected results to match work-related icons",
            results.any { it.labelRu.contains("Работа") || it.category == IconCategory.WORK || it.tags.contains("работа") }
        )
    }

    @Test
    fun testVectorSearch_nonexistentQuery_returnsEmpty() {
        val results = IconPack.search("xyz123nonsense")
        assertTrue("Expected empty results for nonexistent query", results.isEmpty())
    }

    @Test
    fun testVectorSearch_emptyQuery_returnsAllIcons() {
        val results = IconPack.search("")
        assertEquals("Empty query should return all icons", IconPack.ICONS.size, results.size)
    }

    @Test
    fun testVectorSearch_namespaceFilter() {
        val lucideOnly = IconPack.search("briefcase", namespace = IconNamespace.LUCIDE)
        assertTrue(lucideOnly.isNotEmpty())
        assertTrue(lucideOnly.all { it.namespace == IconNamespace.LUCIDE })

        val tablerOnly = IconPack.search("", namespace = IconNamespace.TABLER)
        assertTrue(tablerOnly.isNotEmpty())
        assertTrue(tablerOnly.all { it.namespace == IconNamespace.TABLER })
    }

    @Test
    fun testEmojiSearch_englishQuery_findsFire() {
        val results = EmojiCatalogData.search("fire")
        assertTrue("Expected results for 'fire' to contain 🔥", results.contains("🔥"))
    }

    @Test
    fun testEmojiSearch_russianQuery_findsFire() {
        val results = EmojiCatalogData.search("огонь")
        assertTrue("Expected results for 'огонь' to contain 🔥", results.contains("🔥"))
    }

    @Test
    fun testEmojiSearch_nonexistentQuery_returnsEmpty() {
        val results = EmojiCatalogData.search("xyz123nonsense")
        assertTrue("Expected empty results for nonexistent emoji query", results.isEmpty())
    }

    @Test
    fun testEmojiSearch_emptyQuery_returnsAll() {
        val all = EmojiCatalogData.search("")
        assertTrue("Empty emoji query should return full catalog", all.size >= 40)
        assertTrue(all.contains("🔥"))
        assertTrue(all.contains("🎓"))
    }

    @Test
    fun testCategorySwitching_returnsDistinctIcons() {
        val workIcons = IconPack.getCuratedIconsForCategory("WORK")
        val studyIcons = IconPack.getCuratedIconsForCategory("STUDY")
        val healthIcons = IconPack.getCuratedIconsForCategory("HEALTH")

        assertTrue("WORK category should have icons", workIcons.isNotEmpty())
        assertTrue("STUDY category should have icons", studyIcons.isNotEmpty())
        assertTrue("HEALTH category should have icons", healthIcons.isNotEmpty())

        assertFalse("WORK and STUDY should not be identical", workIcons == studyIcons)
        assertFalse("WORK and HEALTH should not be identical", workIcons == healthIcons)
    }
}
