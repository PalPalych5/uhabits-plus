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

package org.isoron.uhabits.core.containers

sealed interface IconRef {
    val key: String

    data class Vector(override val key: String) : IconRef
    data class Emoji(val value: String) : IconRef {
        override val key: String get() = value
    }
    data object None : IconRef {
        override val key: String get() = ""
    }

    companion object {
        fun fromString(str: String?): IconRef {
            if (str.isNullOrBlank()) return None
            val trimmed = str.trim()
            return when {
                trimmed.startsWith("vector:") -> {
                    val k = trimmed.removePrefix("vector:").trim()
                    if (k.isEmpty()) None else Vector(k)
                }
                trimmed.startsWith("emoji:") -> {
                    val e = trimmed.removePrefix("emoji:").trim()
                    if (e.isEmpty()) None else Emoji(e)
                }
                trimmed.length <= 4 && trimmed.any { it.isSurrogate() || it.code > 1000 } -> Emoji(trimmed)
                else -> Vector(trimmed)
            }
        }

        fun toStorageString(ref: IconRef): String? {
            return when (ref) {
                is Vector -> if (ref.key.isBlank()) null else "vector:${ref.key}"
                is Emoji -> if (ref.value.isBlank()) null else "emoji:${ref.value}"
                is None -> null
            }
        }
    }
}
