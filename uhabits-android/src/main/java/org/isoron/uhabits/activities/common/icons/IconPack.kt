/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.common.icons

import androidx.annotation.DrawableRes
import org.isoron.uhabits.R

enum class IconNamespace(val prefix: String, val displayName: String, val licenseName: String) {
    LUCIDE("lucide", "Lucide", "ISC License"),
    TABLER("tabler", "Tabler", "MIT License");

    companion object {
        fun fromKey(key: String): IconNamespace? = when {
            key.startsWith("lucide:") -> LUCIDE
            key.startsWith("tabler:") -> TABLER
            else -> null
        }
    }
}

enum class IconCategory(val titleRu: String, val titleEn: String) {
    ALL("Все", "All"),
    ROUTINE("Режим", "Routine"),
    HEALTH("Здоровье", "Health"),
    SPORT("Спорт", "Sport"),
    FOOD("Еда", "Food"),
    STUDY("Учёба", "Study"),
    WORK("Работа", "Work"),
    CODE("Код", "Code"),
    MIND("Развитие", "Mind"),
    HOME("Дом", "Home"),
    FINANCE("Финансы", "Finance"),
    PEOPLE("Люди", "People"),
    ART("Творчество", "Art"),
    HOBBIES("Хобби", "Hobbies"),
    TRAVEL("Путешествия", "Travel"),
    NATURE("Природа", "Nature"),
    SLEEP("Сон", "Sleep")
}

data class IconEntry(
    val key: String,
    val legacyKey: String? = null,
    @DrawableRes val drawableRes: Int,
    val namespace: IconNamespace = IconNamespace.LUCIDE,
    val category: IconCategory,
    val labelRu: String,
    val labelEn: String,
    val tags: List<String>
)

object IconPack {

    val DEFAULT_CONTAINER_ICON_RES: Int = R.drawable.ic_tabler_folder
    val DEFAULT_HABIT_ICON_RES: Int = R.drawable.ic_cat_bookmark

    val CURATED_EMOJIS: List<String> = listOf(
        "🎓", "📚", "💻", "🧠", "💪", "🏃", "🏋️", "🧘",
        "🥗", "🍎", "☕", "🌙", "☀️", "⏰", "🏠", "💼",
        "💰", "🎨", "🎵", "✈️", "🌱", "🎯", "⭐", "🔥",
        "💧", "❤️", "🛡️", "💬", "🚗", "🚲", "⚽", "🏀",
        "🎮", "🎸", "🎹", "📷", "🎁", "💡", "🔑", "🚀"
    )

    val ICONS: List<IconEntry> get() = IconCatalogData.ALL_ICONS

    private val KEY_MAP: Map<String, IconEntry> by lazy {
        ICONS.associateBy { it.key }
    }

    private val LEGACY_KEY_MAP: Map<String, IconEntry> by lazy {
        ICONS.filter { it.legacyKey != null }.associateBy { it.legacyKey!! }
    }

    private val DRAWABLE_MAP: Map<Int, IconEntry> by lazy {
        ICONS.associateBy { it.drawableRes }
    }

    private val LEGACY_SYNONYMS: Map<String, String> = mapOf(
        "record_voice_over" to "lucide:mic",
        "directions_run"    to "lucide:activity",
        "face"              to "lucide:smile",
        "wb_sunny"          to "lucide:sun",
        "star"              to "lucide:star"
    )

    fun getIcon(key: String?): IconEntry? {
        if (key.isNullOrBlank()) return null
        val cleanKey = key.removePrefix("vector:").trim()

        // 1. Direct canonical lookup ("lucide:brain", "tabler:barbell")
        KEY_MAP[cleanKey]?.let { return it }

        // 2. Legacy alias check
        LEGACY_SYNONYMS[cleanKey]?.let { canonical ->
            KEY_MAP[canonical]?.let { return it }
        }

        // 3. Hyphen/underscore variants
        val hyphenated = cleanKey.replace('_', '-')
        KEY_MAP[hyphenated]?.let { return it }
        KEY_MAP["lucide:$hyphenated"]?.let { return it }

        // 4. Legacy 196 bare key lookup ("brain" -> IconEntry for lucide:brain)
        LEGACY_KEY_MAP[cleanKey]?.let { return it }
        LEGACY_KEY_MAP[hyphenated]?.let { return it }

        // 5. Implicit Lucide prefix fallback
        KEY_MAP["lucide:$cleanKey"]?.let { return it }

        return null
    }

    @DrawableRes
    fun getDrawableRes(key: String?, defaultRes: Int = DEFAULT_CONTAINER_ICON_RES): Int {
        return getIcon(key)?.drawableRes ?: defaultRes
    }

    fun search(
        query: String,
        category: IconCategory = IconCategory.ALL,
        namespace: IconNamespace? = null
    ): List<IconEntry> {
        val trimmed = query.trim().lowercase()
        return ICONS.filter { entry ->
            if (namespace != null && entry.namespace != namespace) return@filter false
            val matchesCategory = (category == IconCategory.ALL || entry.category == category)
            if (!matchesCategory) return@filter false
            if (trimmed.isEmpty()) return@filter true

            entry.labelRu.lowercase().contains(trimmed) ||
                entry.labelEn.lowercase().contains(trimmed) ||
                entry.key.lowercase().contains(trimmed) ||
                (entry.legacyKey != null && entry.legacyKey.lowercase().contains(trimmed)) ||
                entry.tags.any { it.lowercase().contains(trimmed) }
        }
    }
}
