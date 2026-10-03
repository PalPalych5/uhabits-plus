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
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.createTestDatabaseOpenerSuspend
import org.isoron.platform.io.createTestFileOpener
import org.isoron.platform.io.queryInt
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SQLiteOrganizationStoreSpecificTest {

    @Test
    fun testRealForeignKeyEnforcement() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)

        val fkStatus = db.queryInt("PRAGMA foreign_keys")
        assertEquals(1, fkStatus, "Foreign keys must be actively enforced")

        val store = SQLiteOrganizationStore(db)
        store.seedBaseline(revision = OrganizationRevision(1L), recordedAt = 1000L)

        // 1. Invalid parent_id fails foreign key constraint
        assertFails {
            db.run(
                """
                INSERT INTO Containers (uuid, parent_id, name, sibling_order, is_archived, updated_at, revision)
                VALUES ('orphan-child', 999999, 'Orphan', 0, 0, 1000, 1)
                """.trimIndent()
            )
        }

        // 2. Invalid habit_id fails foreign key constraint
        assertFails {
            db.run(
                """
                INSERT INTO HabitPlacements (habit_id, container_id, local_order, placement_origin, revision)
                VALUES (999999, NULL, 0, 'USER_CHANGE', 1)
                """.trimIndent()
            )
        }

        // 3. Invalid revision fails foreign key constraint
        assertFails {
            db.run(
                """
                INSERT INTO Containers (uuid, parent_id, name, sibling_order, is_archived, updated_at, revision)
                VALUES ('bad-rev', NULL, 'Bad Rev', 0, 0, 1000, 999999)
                """.trimIndent()
            )
        }

        db.close()
    }

    @Test
    fun testDeleteRestrictEnforcement() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)
        val store = SQLiteOrganizationStore(db)
        store.seedBaseline(revision = OrganizationRevision(1L), recordedAt = 1000L)

        // Insert parent and child container
        db.run(
            """
            INSERT INTO Containers (id, uuid, parent_id, name, sibling_order, is_archived, updated_at, revision)
            VALUES (10, 'p1', NULL, 'Parent', 0, 0, 1000, 1)
            """.trimIndent()
        )
        db.run(
            """
            INSERT INTO Containers (id, uuid, parent_id, name, sibling_order, is_archived, updated_at, revision)
            VALUES (11, 'c1', 10, 'Child', 0, 0, 1000, 1)
            """.trimIndent()
        )

        // Attempt to DELETE parent container with existing child -> FAILS due to ON DELETE RESTRICT
        assertFails {
            db.run("DELETE FROM Containers WHERE id = 10")
        }

        // Insert habit and placement
        val habitRepo = HabitRepository(db)
        val habitLocalId = habitRepo.insert(HabitData(name = "Habit A", uuid = "ha"))

        db.run(
            """
            INSERT INTO HabitPlacements (habit_id, container_id, local_order, placement_origin, revision)
            VALUES ($habitLocalId, 11, 0, 'USER_CHANGE', 1)
            """.trimIndent()
        )

        // Attempt to DELETE container with placed habit -> FAILS due to ON DELETE RESTRICT
        assertFails {
            db.run("DELETE FROM Containers WHERE id = 11")
        }

        // Attempt to DELETE habit with placement -> FAILS due to ON DELETE RESTRICT
        assertFails {
            db.run("DELETE FROM Habits WHERE id = $habitLocalId")
        }

        db.close()
    }

    @Test
    fun testTransactionFailureRollback_allTablesAtomic() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)
        val store = SQLiteOrganizationStore(db)
        store.seedBaseline(revision = OrganizationRevision(1L), recordedAt = 1000L)

        val initialContainersCount = db.queryLong("SELECT count(*) FROM Containers")
        val initialHistoryCount = db.queryLong("SELECT count(*) FROM ContainerHistory")
        val initialChangesCount = db.queryLong("SELECT count(*) FROM OrganizationChanges")
        val initialRevision = store.readState().currentRevision

        // Execute a transaction that makes several modifications and then fails
        assertFails {
            store.executeInTransaction { tx ->
                val c = Container(
                    id = ContainerId("temp-c"),
                    parentId = null,
                    name = "Will Rollback",
                    color = null,
                    icon = null,
                    siblingOrder = 0,
                    isArchived = false,
                    createdAt = 2000L,
                    updatedAt = 2000L,
                    deletedAt = null,
                    revision = OrganizationRevision(2L),
                )
                tx.recordChange(
                    OrganizationChangeRecord(
                        revision = OrganizationRevision(2L),
                        opUuid = "tx-rollback-op",
                        recordedAt = 2000L,
                        operationType = "CREATE",
                        origin = "USER",
                        commandPayload = "PAYLOAD",
                    )
                )
                tx.saveContainer(c)
                tx.recordContainerHistory(c, OrganizationRevision(2L))
                tx.updateState(OrganizationRevision(2L))

                // Deliberately throw an exception inside the transaction
                throw IllegalStateException("Intentional test abort")
            }
        }

        // Verify that EVERYTHING was rolled back
        assertEquals(initialContainersCount, db.queryLong("SELECT count(*) FROM Containers"))
        assertEquals(initialHistoryCount, db.queryLong("SELECT count(*) FROM ContainerHistory"))
        assertEquals(initialChangesCount, db.queryLong("SELECT count(*) FROM OrganizationChanges"))
        assertEquals(initialRevision, store.readState().currentRevision)

        db.close()
    }

    @Test
    fun testReopenAndPersistence() = runTest {
        val fileOpener = createTestFileOpener()
        val tempFile = fileOpener.openUserFile("test-reopen-${idCounter++}.db")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val dbOpener = createTestDatabaseOpenerSuspend()
        var db1: Database? = null
        var db2: Database? = null

        try {
            // 1. Open first database connection, setup schema, create containers and placements
            val db = dbOpener.open(tempFile.pathString)
            db1 = db
            db.run("PRAGMA user_version = $DATABASE_VERSION")
            // Create habits table and base tables if needed
            db.run(
                """
                CREATE TABLE IF NOT EXISTS Habits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    question TEXT NOT NULL DEFAULT '',
                    freq_num INTEGER NOT NULL DEFAULT 1,
                    freq_den INTEGER NOT NULL DEFAULT 1,
                    color INTEGER NOT NULL DEFAULT 0,
                    position INTEGER NOT NULL DEFAULT 0,
                    reminder_hour INTEGER,
                    reminder_min INTEGER,
                    reminder_days INTEGER NOT NULL DEFAULT 0,
                    highlight INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    type INTEGER NOT NULL DEFAULT 0,
                    target_value REAL NOT NULL DEFAULT 0.0,
                    target_type INTEGER NOT NULL DEFAULT 0,
                    unit TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )
            db.run(
                """
                CREATE TABLE IF NOT EXISTS HabitBlocks (
                    id INTEGER PRIMARY KEY,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    icon TEXT,
                    position INTEGER NOT NULL,
                    is_archived INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER
                );
                """.trimIndent()
            )

            OrganizationSchema.createSchema(db)
            val store1 = SQLiteOrganizationStore(db)
            store1.seedBaseline(revision = OrganizationRevision(1L), recordedAt = 1000L)

            // Insert a habit
            db.run("INSERT INTO Habits (id, uuid, name) VALUES (1, 'h-persist', 'Persisted Habit')")

            // Add a container and habit placement through service
            val clock = Clock { 2000L }
            var genCounter = 0
            val idGen = IdGenerator { "gen-id-${++genCounter}" }
            val habitLookup = HabitIdentityLookup { true }
            val service1 = OrganizationServiceImpl(
                store = store1,
                unitOfWork = store1,
                containerQueries = store1,
                placementQueries = store1,
                clock = clock,
                idGenerator = idGen,
                habitIdentityLookup = habitLookup,
            )

            val createRes = service1.create(CreateContainer(name = "Persisted Folder", explicitId = ContainerId("c-persisted")))
            assertTrue(createRes.isSuccess)

            val placeRes = service1.placeHabit(
                PlaceHabit(
                    habit = HabitRef("h-persist"),
                    targetContainerId = ContainerId("c-persisted"),
                    expectedRevision = store1.readState().currentRevision,
                )
            )
            assertTrue(placeRes.isSuccess)
            val revBeforeClose = store1.readState().currentRevision

            // Close connection 1
            db.close()
            db1 = null

            // 2. Open second database connection to the same file
            val openedDb2 = dbOpener.open(tempFile.pathString)
            db2 = openedDb2
            val store2 = SQLiteOrganizationStore(openedDb2)

            // Verify state is preserved
            val stateAfterReopen = store2.readState()
            assertEquals(revBeforeClose, stateAfterReopen.currentRevision)

            // Verify container is preserved
            val container = store2.getContainer(ContainerId("c-persisted"))
            assertNotNull(container)
            assertEquals("Persisted Folder", container.name)

            // Verify placement is preserved
            val placement = store2.getHabitPlacement(HabitRef("h-persist"))
            assertNotNull(placement)
            assertEquals(ContainerId("c-persisted"), placement.containerId)

            // Verify queries
            val roots = store2.roots()
            assertEquals(1, roots.size)
            assertEquals(ContainerId("c-persisted"), roots[0].id)

            val habitsInFolder = store2.directHabits(ContainerId("c-persisted"))
            assertEquals(listOf(HabitRef("h-persist")), habitsInFolder)

            openedDb2.close()
            db2 = null
        } finally {
            try { db1?.close() } catch (_: Throwable) {}
            try { db2?.close() } catch (_: Throwable) {}
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Test
    fun testSchemaAndIndicesValidation() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        OrganizationSchema.createSchema(db)

        val tables = mutableSetOf<String>()
        val stmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
        while (stmt.step() == org.isoron.platform.io.StepResult.ROW) {
            tables.add(stmt.getText(0))
        }
        stmt.finalize()

        assertTrue(tables.contains("OrganizationState"))
        assertTrue(tables.contains("OrganizationChanges"))
        assertTrue(tables.contains("Containers"))
        assertTrue(tables.contains("HabitPlacements"))
        assertTrue(tables.contains("ContainerHistory"))
        assertTrue(tables.contains("HabitPlacementHistory"))
        assertTrue(tables.contains("LegacyBlockMap"))

        // Check indices
        val indices = mutableSetOf<String>()
        val idxStmt = db.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'index'")
        while (idxStmt.step() == org.isoron.platform.io.StepResult.ROW) {
            indices.add(idxStmt.getText(0))
        }
        idxStmt.finalize()

        assertTrue(indices.contains("idx_containers_tree"))
        assertTrue(indices.contains("idx_containers_active"))
        assertTrue(indices.contains("idx_habit_placements_contents"))
        assertTrue(indices.contains("idx_container_history_lookup"))
        assertTrue(indices.contains("idx_habit_placement_history_lookup"))
        assertTrue(indices.contains("idx_org_changes_recorded"))

        db.close()
    }

    companion object {
        private var idCounter = 100
    }
}
