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

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.memory.MemoryHabitVisualIdentityStore
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteHabitVisualIdentityStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisualIdentityTest {

    @Test
    fun `IconRef parse and serialize contracts`() {
        assertEquals(IconRef.None, IconRef.fromString(null))
        assertEquals(IconRef.None, IconRef.fromString(""))
        assertEquals(IconRef.None, IconRef.fromString("   "))

        val vector = IconRef.fromString("vector:graduation-cap")
        assertTrue(vector is IconRef.Vector)
        assertEquals("graduation-cap", vector.key)
        assertEquals("vector:graduation-cap", IconRef.toStorageString(vector))

        val emoji = IconRef.fromString("emoji:🎓")
        assertTrue(emoji is IconRef.Emoji)
        assertEquals("🎓", emoji.value)
        assertEquals("emoji:🎓", IconRef.toStorageString(emoji))

        // Raw emoji heuristic
        val rawEmoji = IconRef.fromString("🎓")
        assertTrue(rawEmoji is IconRef.Emoji)
        assertEquals("🎓", rawEmoji.value)

        // Raw vector key
        val rawKey = IconRef.fromString("dumbbell")
        assertTrue(rawKey is IconRef.Vector)
        assertEquals("dumbbell", rawKey.key)

        assertNull(IconRef.toStorageString(IconRef.None))
    }

    @Test
    fun `MemoryHabitVisualIdentityStore set get and remove`() {
        val store = MemoryHabitVisualIdentityStore()
        val habitUuid = "habit-uuid-1"

        assertNull(store.get(habitUuid))

        store.set(habitUuid, IconRef.Vector("book"))
        assertEquals(IconRef.Vector("book"), store.get(habitUuid))

        store.set(habitUuid, IconRef.Emoji("📚"))
        assertEquals(IconRef.Emoji("📚"), store.get(habitUuid))

        store.set(habitUuid, IconRef.None)
        assertNull(store.get(habitUuid))
    }

    @Test
    fun `SQLiteHabitVisualIdentityStore roundtrip in isolated database`() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)

        val store = SQLiteHabitVisualIdentityStore(db)
        val habit1 = "habit-uuid-alpha"
        val habit2 = "habit-uuid-beta"

        store.set(habit1, IconRef.Vector("heart"))
        store.set(habit2, IconRef.Emoji("💪"))

        assertEquals(IconRef.Vector("heart"), store.get(habit1))
        assertEquals(IconRef.Emoji("💪"), store.get(habit2))

        val all = store.getAll()
        assertEquals(2, all.size)
        assertEquals(IconRef.Vector("heart"), all[habit1])
        assertEquals(IconRef.Emoji("💪"), all[habit2])

        // Update
        store.set(habit1, IconRef.Vector("activity"))
        assertEquals(IconRef.Vector("activity"), store.get(habit1))

        // Remove
        store.remove(habit1)
        assertNull(store.get(habit1))
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun `Facade provides visual identity in CONTAINER_LOCAL mode`() {
        val visualStore = MemoryHabitVisualIdentityStore()
        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            visualIdentityStore = visualStore
        )

        facade.setHabitVisualIdentity("uuid-1", IconRef.Vector("atom"))
        val identity = facade.getHabitVisualIdentity("uuid-1")
        assertNotNull(identity)
        assertEquals("uuid-1", identity.habitUuid)
        assertEquals(IconRef.Vector("atom"), identity.iconRef)

        facade.removeHabitVisualIdentity("uuid-1")
        assertNull(facade.getHabitVisualIdentity("uuid-1"))
    }

    @Test
    fun `Facade visual identity is safe no-op in LEGACY mode`() {
        val facade = HabitOrganizationFacadeImpl(
            mode = OrganizationAuthorityMode.LEGACY
        )

        assertNull(facade.getHabitVisualIdentity("uuid-1"))
        facade.setHabitVisualIdentity("uuid-1", IconRef.Vector("book"))
        assertNull(facade.getHabitVisualIdentity("uuid-1"))
    }
}
