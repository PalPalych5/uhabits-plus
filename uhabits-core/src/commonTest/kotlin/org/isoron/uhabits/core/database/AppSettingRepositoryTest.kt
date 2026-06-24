package org.isoron.uhabits.core.database

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppSettingRepositoryTest {
    @Test
    fun storesAndDeletesLongValues() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val repo = AppSettingRepository(db)
        assertNull(repo.getLong("global_stats_start_timestamp"))
        repo.putLong("global_stats_start_timestamp", 1234L)
        assertEquals(1234L, repo.getLong("global_stats_start_timestamp"))
        repo.putLong("global_stats_start_timestamp", null)
        assertNull(repo.getLong("global_stats_start_timestamp"))
        db.close()
    }
}
