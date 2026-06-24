package org.isoron.uhabits.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileNamePolicyTest {
    @Test
    fun matchesExpectedBackupPattern() {
        assertTrue(BackupFileNamePolicy.isBackupFile("Loop Habits Backup 2026-06-24 120000.db"))
        assertFalse(BackupFileNamePolicy.isBackupFile("notes.txt"))
        assertFalse(BackupFileNamePolicy.isBackupFile("Loop Habits Backup 2026-06-24 120000.zip"))
    }
}
