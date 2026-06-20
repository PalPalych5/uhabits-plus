package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HabitExtensionRepositoryTest {
    @Test
    fun upsertsReadsAndDeletesMetadata() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val habits = HabitRepository(db)
        val extensions = HabitExtensionRepository(db)
        val habitId = habits.insert(HabitData(name = "Reading"))

        extensions.upsert(HabitExtensionData(habitId, "MINIMUM", true))
        assertEquals(HabitExtensionData(habitId, "MINIMUM", true), extensions.findByHabitId(habitId))

        extensions.upsert(HabitExtensionData(habitId, "IDEAL", false))
        assertEquals(HabitExtensionData(habitId, "IDEAL", false), extensions.findByHabitId(habitId))

        extensions.delete(habitId)
        assertNull(extensions.findByHabitId(habitId))
        db.close()
    }
}
