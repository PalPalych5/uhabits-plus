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

package org.isoron.uhabits.core.io

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.run
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopDBImporterTest : BaseUnitTest() {

    @Test
    fun testRejectsDatabaseWithNextEntitiesInCanHandle() = runTest {
        val userFile = copyResourceToTempFile("loop.db")
        val dbOpener = databaseOpener()

        // Inject a Next entity table into the loop.db copy
        val db = dbOpener.open(userFile.pathString)
        db.run("CREATE TABLE Containers (id INTEGER PRIMARY KEY, name TEXT)")
        db.close()

        val importer = LoopDBImporter(
            habitList,
            modelFactory,
            dbOpener,
            commandRunner,
            StandardLogging(),
            fileOpener
        )

        // Ordinary importer must reject files with Next entities to avoid silent data loss
        assertFalse(importer.canHandle(userFile), "LoopDBImporter should reject database containing Next entity tables")

        userFile.delete()
    }

    @Test
    fun testRejectsDatabaseWithNextEntitiesInImportHabits() = runTest {
        val userFile = copyResourceToTempFile("loop.db")
        val dbOpener = databaseOpener()

        // Inject Next entity tables
        val db = dbOpener.open(userFile.pathString)
        db.run("CREATE TABLE UserTasks (id TEXT PRIMARY KEY, title TEXT)")
        db.close()

        val importer = LoopDBImporter(
            habitList,
            modelFactory,
            dbOpener,
            commandRunner,
            StandardLogging(),
            fileOpener
        )

        assertFailsWith<NextEntitiesDetectedException> {
            importer.importHabitsFromFile(userFile)
        }

        // Habit list must not be modified
        assertEquals(0, habitList.size())

        userFile.delete()
    }

    @Test
    fun testAcceptsPureLegacyDatabase() = runTest {
        val userFile = copyResourceToTempFile("loop.db")
        val dbOpener = databaseOpener()

        val importer = LoopDBImporter(
            habitList,
            modelFactory,
            dbOpener,
            commandRunner,
            StandardLogging(),
            fileOpener
        )

        assertTrue(importer.canHandle(userFile))
        importer.importHabitsFromFile(userFile)
        assertEquals(9, habitList.size())

        userFile.delete()
    }
}
