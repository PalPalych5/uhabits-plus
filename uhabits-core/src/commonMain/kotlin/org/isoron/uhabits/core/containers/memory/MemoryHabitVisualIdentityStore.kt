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
