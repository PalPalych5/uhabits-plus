/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.icons

import org.isoron.uhabits.core.containers.IconRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {

    @Test
    fun testAllEmojisNonEmpty() {
        assertTrue(EmojiCatalogData.ALL_EMOJIS.isNotEmpty())
        assertTrue("Expected at least 50 curated emojis, got ${EmojiCatalogData.ALL_EMOJIS.size}",
            EmojiCatalogData.ALL_EMOJIS.size >= 50)
    }

    @Test
    fun testBilingualSearch() {
        val fireRu = EmojiCatalogData.search("огонь")
        assertTrue("Search 'огонь' should find 🔥", fireRu.contains("🔥"))

        val fireEn = EmojiCatalogData.search("fire")
        assertTrue("Search 'fire' should find 🔥", fireEn.contains("🔥"))

        val runRu = EmojiCatalogData.search("бег")
        assertTrue("Search 'бег' should find running emojis", runRu.any { it.contains("🏃") })

        val bookEn = EmojiCatalogData.search("book")
        assertTrue("Search 'book' should find 📚", bookEn.contains("📚"))

        val emptyQuery = EmojiCatalogData.search("")
        assertEquals(EmojiCatalogData.ALL_EMOJIS.size, emptyQuery.size)
    }

    @Test
    fun testMultiCodepointEmojiStorage() {
        val complexEmojis = listOf(
            "🏃‍♂️", // man running (man + ZWJ + male sign + variation)
            "🏋️‍♀️", // woman weightlifting
            "🧘",   // lotus
            "✨",   // sparkles
            "❤️"    // heart with variation selector
        )

        for (emoji in complexEmojis) {
            val ref = IconRef.Emoji(emoji)
            val storage = IconRef.toStorageString(ref)
            assertEquals("emoji:$emoji", storage)

            val parsed = IconRef.fromString(storage)
            assertTrue("Parsed ref must be Emoji, got $parsed for $emoji", parsed is IconRef.Emoji)
            assertEquals(emoji, (parsed as IconRef.Emoji).value)
        }
    }
}
