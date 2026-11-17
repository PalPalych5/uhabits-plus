/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.restore

import org.isoron.uhabits.backup.RestoreJournal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRecordAndReadState() {
        val dir = tempFolder.root
        val journal = RestoreJournal(dir, "uhabits.db")

        assertNull(journal.readState())
        assertFalse(journal.exists)

        journal.recordState(RestoreJournal.State.PREPARING, 29, "/path/backup.db")
        assertTrue(journal.exists)

        val record1 = journal.readState()
        assertNotNull(record1)
        assertEquals(RestoreJournal.State.PREPARING, record1!!.state)
        assertEquals(29, record1.targetVersion)
        assertEquals("/path/backup.db", record1.sourceBackupPath)

        journal.recordState(RestoreJournal.State.SWAPPING, 29, "/path/backup.db")
        val record2 = journal.readState()
        assertEquals(RestoreJournal.State.SWAPPING, record2!!.state)

        journal.recordState(RestoreJournal.State.APPLIED, 29, "/path/backup.db")
        val record3 = journal.readState()
        assertEquals(RestoreJournal.State.APPLIED, record3!!.state)

        journal.clear()
        assertNull(journal.readState())
        assertFalse(journal.exists)
    }

    @Test
    fun testCorruptedJournalFailsSafeToSwapping() {
        val dir = tempFolder.root
        val journal = RestoreJournal(dir, "uhabits.db")
        journal.journalFile.writeText("corrupted-garbage-content")

        val state = journal.readState()
        assertNotNull(state)
        // Corrupted journal defaults to safe rollback state SWAPPING
        assertEquals(RestoreJournal.State.SWAPPING, state!!.state)
    }

    @Test
    fun testEmptyFileReturnsNull() {
        val dir = tempFolder.root
        val journal = RestoreJournal(dir, "uhabits.db")
        journal.journalFile.writeText("")

        assertNull(journal.readState())
    }

    @Test
    fun testClearOnNonExistentFileDoesNotThrow() {
        val dir = tempFolder.root
        val journal = RestoreJournal(dir, "uhabits.db")
        assertFalse(journal.exists)
        journal.clear()
        assertFalse(journal.exists)
    }
}
