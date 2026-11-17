/* SPDX-License-Identifier: GPL-3.0-or-later */

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
