/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers

data class HabitVisualIdentity(
    val habitUuid: String,
    val iconRef: IconRef
)

interface HabitVisualIdentityStore {
    fun get(habitUuid: String): IconRef?
    fun getAll(): Map<String, IconRef>
    fun set(habitUuid: String, iconRef: IconRef)
    fun remove(habitUuid: String)
}
