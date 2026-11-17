/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.isoron.uhabits.core.models

data class HabitBlock(
    val id: Long? = null,
    var name: String,
    var color: PaletteColor,
    var icon: String? = null,
    var position: Int = 0,
    var isArchived: Boolean = false
)
