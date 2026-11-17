/* SPDX-License-Identifier: GPL-3.0-or-later */

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
                trimmed.any { it.isSurrogate() || it.code > 1000 } -> Emoji(trimmed)
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
