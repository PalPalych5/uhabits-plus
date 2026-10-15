package org.isoron.uhabits.core.containers

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.run
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacadeImpl
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.containers.sqlite.OrganizationSchema
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import kotlin.test.*

class SQLiteRootGroupingTest {
    private class TrackedDatabase(private val delegate: Database) : Database by delegate {
        val sql = mutableListOf<String>()
        var active = 0
        var failContainerRead = false
        override fun prepareStatement(query: String): PreparedStatement {
            sql.add(query)
            val statement = delegate.prepareStatement(query)
            active++
            return object : PreparedStatement by statement {
                private var finalized = false
                override fun step(): StepResult {
                    if (failContainerRead && query.contains("FROM Containers", ignoreCase = true)) {
                        failContainerRead = false
                        throw IllegalStateException("Injected root traversal read failure")
                    }
                    return statement.step()
                }
                override fun finalize() {
                    try { statement.finalize() } finally {
                        if (!finalized) { finalized = true; active-- }
                    }
                }
            }
        }
    }

    private class Rig(val db: TrackedDatabase, val prefix: String) {
        val store = SQLiteOrganizationStore(db)
        val facade = HabitOrganizationFacadeImpl(mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
            containerQueries = store, habitPlacementQueries = store)
        val habitId: Long
        init {
            store.seedBaseline(datasetUuid = prefix)
            // Fixture-owned insertion avoids HabitRepository's deliberately cached
            // insert statement; active counts below apply strictly to root reads.
            habitId = 201L
            db.run("""INSERT INTO Habits(id,name,description,question,freq_num,freq_den,color,position,
                reminder_hour,reminder_min,reminder_days,highlight,archived,type,target_value,target_type,
                unit,uuid,updated_at,deleted_at) VALUES
                ($habitId,'Root grouping','','',1,1,0,0,NULL,NULL,0,0,0,0,0,0,'','$prefix-habit',0,NULL)""".trimIndent())
            for ((id, parent) in listOf(101 to null, 102 to 101, 103 to 102, 104 to null)) {
                db.run("INSERT INTO Containers(id,uuid,parent_id,name,sibling_order,is_archived,updated_at,revision) " +
                    "VALUES ($id,'$prefix-$id',${parent ?: "NULL"},'Container $id',0,0,1,1)")
            }
            place(103)
        }
        fun place(id: Int?) = db.run("INSERT OR REPLACE INTO HabitPlacements(habit_id,container_id,local_order,placement_origin,revision) " +
            "VALUES ($habitId,${id ?: "NULL"},0,'USER_CHANGE',1)")
        fun key(): Any? = facade.getRootGroupKey("$prefix-habit", 999)
        fun assertPathParity(id: Int) {
            val expected = store.path(ContainerId("$prefix-$id")).nodes.firstOrNull()?.value
            assertEquals(expected, key(), "Root grouping must equal existing path semantics for $prefix/$id")
        }
    }

    private suspend fun rig(prefix: String = "dataset", action: (Rig) -> Unit) {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        try {
            OrganizationSchema.createSchema(raw)
            action(Rig(TrackedDatabase(raw), prefix))
        } finally { raw.close() }
    }

    @Test fun rootLookupOmitsStateAndMaterializedMetadataWhileReusingParentStatement() = runTest {
        rig { r ->
            r.assertPathParity(103)
            r.db.sql.clear()
            assertEquals("dataset-101", r.key())
            assertFalse(r.db.sql.any { it.contains("OrganizationState", ignoreCase = true) },
                "Root grouping must not load an unused path revision: ${r.db.sql}")
            assertFalse(r.db.sql.any { query -> listOf("c.name", "c.color", "c.icon", "c.updated_at").any {
                query.contains(it, ignoreCase = true)
            } }, "Root-only traversal must not materialize container metadata: ${r.db.sql}")
            assertEquals(1, r.db.sql.count { it.contains("FROM Containers", ignoreCase = true) },
                "A nested root traversal must reuse one prepared parent statement")
            assertEquals(0, r.db.active)
        }
    }

    @Test fun rootGroupingMatchesPathAcrossNestedArchivedDeletedAndCyclicTrees() = runTest {
        rig { r ->
            for (id in 101..104) { r.place(id); r.assertPathParity(id) }
            r.place(103)
            r.db.run("UPDATE Containers SET is_archived=1 WHERE id=101")
            r.assertPathParity(103)
            r.db.run("UPDATE Containers SET deleted_at=2 WHERE id=102")
            r.assertPathParity(103)
            r.db.run("UPDATE Containers SET deleted_at=2 WHERE id=103")
            r.assertPathParity(103)
            r.db.run("UPDATE Containers SET deleted_at=NULL")
            // Deliberately corrupt only this isolated fixture to exercise the
            // existing path's bounded behavior for a self-parent cycle.
            r.db.run("PRAGMA ignore_check_constraints=ON")
            try {
                r.db.run("UPDATE Containers SET parent_id=101 WHERE id=101")
                r.place(101); r.assertPathParity(101)
            } finally {
                r.db.run("UPDATE Containers SET parent_id=NULL WHERE id=101")
                r.db.run("PRAGMA ignore_check_constraints=OFF")
            }
            r.db.run("UPDATE Containers SET parent_id=103 WHERE id=101")
            for (id in 101..103) { r.place(id); r.assertPathParity(id) }
            val placement = assertNotNull(r.store.current(HabitRef("dataset-habit")))
            val missing = ContainerId("missing-container")
            val missingPlacement = object : HabitPlacementQueries by r.store {
                override fun current(habit: HabitRef): HabitPlacement = placement.copy(containerId = missing)
            }
            val missingFacade = HabitOrganizationFacadeImpl(mode = OrganizationAuthorityMode.CONTAINER_LOCAL,
                containerQueries = r.store, habitPlacementQueries = missingPlacement)
            assertEquals(r.store.path(missing).nodes.firstOrNull()?.value,
                missingFacade.getRootGroupKey("dataset-habit"), "Missing container retains existing path sentinel semantics")
            r.place(null)
            assertNull(r.key())
            assertNull(r.facade.getRootGroupKey("missing-habit", 999))
            assertNull(r.facade.getRootGroupKey("dataset-habit"))
            assertEquals(0, r.db.active)
        }
    }

    @Test fun placementMoveRollbackAndSameLocalIdsInNewDatasetStayFresh() = runTest {
        rig("first") { r ->
            assertEquals("first-101", r.key())
            r.place(104); assertEquals("first-104", r.key())
            r.db.beginTransaction()
            try { r.place(103); r.assertPathParity(103) } finally { r.db.endTransaction() }
            assertEquals("first-104", r.key(), "Rolled-back placement must not leak through root lookup")
            r.db.run("UPDATE Containers SET parent_id=104 WHERE id=102")
            r.place(103); r.assertPathParity(103)
            assertEquals("first-104", r.key())
        }
        rig("second") { r ->
            assertEquals("second-101", r.key(), "Identical local IDs cannot reuse another dataset's roots")
            r.assertPathParity(103)
        }
    }

    @Test fun traversalStatementFailureIsPropagatedAndFinalizedWithoutPoisoningNextRead() = runTest {
        rig { r ->
            r.db.failContainerRead = true
            val failure = assertFailsWith<IllegalStateException> { r.key() }
            assertEquals("Injected root traversal read failure", failure.message)
            assertEquals(0, r.db.active, "Failed root traversal must finalize its statement")
            r.assertPathParity(103)
        }
    }
}
