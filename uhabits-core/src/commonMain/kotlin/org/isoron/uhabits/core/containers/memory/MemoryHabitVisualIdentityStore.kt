/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.core.containers.memory

import org.isoron.uhabits.core.containers.HabitVisualIdentityStore
import org.isoron.uhabits.core.containers.IconRef

class MemoryHabitVisualIdentityStore : HabitVisualIdentityStore {
    private val identities = mutableMapOf<String, IconRef>()

    override fun get(habitUuid: String): IconRef? = identities[habitUuid]

    override fun getAll(): Map<String, IconRef> = identities.toMap()

    override fun set(habitUuid: String, iconRef: IconRef) {
        if (iconRef is IconRef.None) {
            identities.remove(habitUuid)
        } else {
            identities[habitUuid] = iconRef
        }
    }

    override fun remove(habitUuid: String) {
        identities.remove(habitUuid)
    }
}
