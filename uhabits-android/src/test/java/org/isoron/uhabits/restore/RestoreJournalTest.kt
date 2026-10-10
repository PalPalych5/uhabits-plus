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
