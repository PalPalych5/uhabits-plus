package org.isoron.uhabits.backup

import androidx.preference.PreferenceManager
import org.isoron.uhabits.BaseAndroidTest
import org.junit.Test
import kotlin.test.assertEquals

class BackupStatusStoreTest : BaseAndroidTest() {
    @Test
    fun recordsSuccessAndFailure() {
        PreferenceManager.getDefaultSharedPreferences(targetContext).edit().clear().commit()
        val store = BackupStatusStore(targetContext)

        store.recordSuccess("file:///backup.db", 123L, 28, 1000L)
        store.recordFailure("boom", 2000L)

        val status = store.load()
        assertEquals(1000L, status.lastSuccessAt)
        assertEquals(2000L, status.lastFailureAt)
        assertEquals("file:///backup.db", status.lastBackupLocation)
        assertEquals(123L, status.lastBackupSizeBytes)
        assertEquals(28, status.lastBackupDatabaseVersion)
        assertEquals("boom", status.lastFailureReason)
    }
}
