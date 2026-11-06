/* Copyright (C) 2026 Loop Habit Tracker contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.activities.common.icons

import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedIconsTest {
    @Test
    fun testCuratedIconsResolve() {
        val missing = mutableListOf<String>()
        for (key in IconPack.CURATED_ICON_KEYS) {
            val icon = IconPack.getIcon(key)
            if (icon == null) {
                missing.add(key)
            }
        }
        assertTrue("Missing curated icons: $missing", missing.isEmpty())
        assertTrue(
            "Curated icons count must be between 60 and 130, got ${IconPack.CURATED_ICON_KEYS.size}",
            IconPack.CURATED_ICON_KEYS.size in 60..130
        )
    }

    @Test
    fun testCuratedCategoriesHaveIntentionalIcons() {
        val requiredCategories = listOf(
            "STUDY", "WORK", "HEALTH", "SPORT", "HOME",
            "FINANCE", "ART", "PEOPLE", "LEISURE", "TRAVEL"
        )
        for (cat in requiredCategories) {
            val icons = IconPack.getCuratedIconsForCategory(cat)
            assertTrue("Category $cat must have between 10 and 25 icons, got ${icons.size}", icons.size in 10..25)
        }
    }
}
