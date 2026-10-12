package org.isoron.uhabits.core.models.projection

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.run
import org.isoron.platform.io.queryLong
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.contribution.sqlite.SQLiteContributionRepository
import org.isoron.uhabits.core.database.HabitGoalData
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import kotlin.test.*

class EffectiveReadRevisionTest {
    @Test fun independentWorkspaceAndOrganizationRevisionsHonorDurableCommitAndRollback() = runTest {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        raw.run("CREATE TABLE UserTasks (id INTEGER PRIMARY KEY)")
        raw.run("CREATE TABLE Containers (id INTEGER PRIMARY KEY)")
        val habitRevision = EffectiveReadRevision("dataset-a")
        val workspaceRevision = EffectiveReadRevision("dataset-a")
        val organizationRevision = EffectiveReadRevision("dataset-a")
        val habits = EffectiveReadDatabase(raw, habitRevision)
        val workspace = EffectiveReadDatabase(habits, workspaceRevision, setOf("UserTasks", "Containers"))
        val db = EffectiveReadDatabase(workspace, organizationRevision, setOf("Containers"))
        var workspaceRollbacks = 0
        workspaceRevision.addRollbackListener { workspaceRollbacks++ }
        try {
            db.run("BEGIN")
            db.run("INSERT INTO UserTasks VALUES (1)")
            assertEquals(0L, workspaceRevision.value)
            db.run("COMMIT")
            assertEquals(1L, workspaceRevision.value)
            assertEquals(0L, habitRevision.value)
            assertEquals(0L, organizationRevision.value)
            db.run("BEGIN")
            db.run("SAVEPOINT organization_edit")
            db.run("INSERT INTO Containers VALUES (1)")
            db.run("RELEASE SAVEPOINT organization_edit")
            assertEquals(0L, organizationRevision.value)
            db.run("ROLLBACK")
            assertEquals(1L, workspaceRevision.value)
            assertEquals(0L, organizationRevision.value)
            assertEquals(1, workspaceRollbacks)
            assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM Containers"))
            db.run("BEGIN")
            db.run("INSERT INTO Containers VALUES (2)")
            db.run("INSERT INTO UserTasks VALUES (2)")
            db.run("COMMIT")
            assertEquals(2L, workspaceRevision.value)
            assertEquals(1L, organizationRevision.value)
            assertEquals(0L, habitRevision.value)
            db.run("UPDATE Containers SET id = 3 WHERE id = 99")
            assertEquals(2L, workspaceRevision.value)
            db.close()
            workspaceRevision.changed()
            organizationRevision.changed()
            assertEquals(2L, workspaceRevision.value)
            assertEquals(1L, organizationRevision.value)
        } finally { runCatching { db.close() } }
    }

    private fun contribution() = Contribution(
        id = ContributionId("revision-credit"), targetHabit = HabitRef("h-1"),
        sourceType = ContributionSourceType.FOCUS_SESSION, sourceId = "s-1",
        contributedValue = 60000L, effectiveTimestamp = 1000L,
        createdAt = 1000L, updatedAt = 1000L, removedAt = null,
    )

    @Test
    fun callerOwnedRollbackDiscardsReleasedInnerSavepoint() = runTest {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        ContributionSchema.createSchema(raw)
        val revision = EffectiveReadRevision("dataset-a")
        val db = EffectiveReadDatabase(raw, revision)
        try {
            val repo = SQLiteContributionRepository(db)
            db.run("BEGIN")
            repo.record(contribution())
            assertEquals(0L, revision.value)
            db.run("ROLLBACK")
            assertEquals(0L, revision.value)
            assertNull(repo.getById(contribution().id))
        } finally { db.close() }
    }

    @Test
    fun outerCommitCoalescesInnerWritesAndNotifiesOnlyAfterCommit() = runTest {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        raw.run("DROP TABLE Repetitions")
        raw.run("CREATE TABLE Repetitions (value INTEGER)")
        val revision = EffectiveReadRevision("dataset-a")
        val db = EffectiveReadDatabase(raw, revision)
        var durableRowCount = -1L
        revision.addListener { durableRowCount = db.queryLong("SELECT count(*) FROM Repetitions") }
        try {
            db.run("BEGIN")
            db.run("INSERT INTO Repetitions VALUES (30)")
            db.run("SAVEPOINT inner_write")
            db.run("INSERT INTO Repetitions VALUES (40)")
            db.run("RELEASE SAVEPOINT inner_write")
            assertEquals(0L, revision.value)
            assertEquals(-1L, durableRowCount)
            db.run("COMMIT")
            assertEquals(1L, revision.value)
            assertEquals(2L, durableRowCount)
        } finally { db.close() }
    }

    @Test
    fun rollbackToSavepointClearsOnlyRolledBackChanges() = runTest {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        raw.run("DROP TABLE Repetitions")
        raw.run("CREATE TABLE Repetitions (value INTEGER)")
        val revision = EffectiveReadRevision("dataset-a")
        val db = EffectiveReadDatabase(raw, revision)
        try {
            db.run("BEGIN")
            db.run("SAVEPOINT inner_write")
            db.run("INSERT INTO Repetitions VALUES (30)")
            db.run("ROLLBACK TO SAVEPOINT inner_write")
            db.run("RELEASE SAVEPOINT inner_write")
            db.run("COMMIT")
            assertEquals(0L, revision.value)
            db.run("BEGIN")
            db.run("INSERT INTO Repetitions VALUES (40)")
            db.run("SAVEPOINT inner_write")
            db.run("INSERT INTO Repetitions VALUES (50)")
            db.run("ROLLBACK TO SAVEPOINT inner_write")
            db.run("RELEASE SAVEPOINT inner_write")
            db.run("COMMIT")
            assertEquals(1L, revision.value)
            assertEquals(1L, db.queryLong("SELECT count(*) FROM Repetitions"))
        } finally { db.close() }
    }

    @Test
    fun idempotentContributionAndFailedWritesDoNotInventRevisions() = runTest {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        ContributionSchema.createSchema(raw)
        val revision = EffectiveReadRevision("dataset-a")
        val db = EffectiveReadDatabase(raw, revision)
        try {
            val repo = SQLiteContributionRepository(db)
            repo.record(contribution())
            assertEquals(1L, revision.value)
            repo.record(contribution())
            assertEquals(1L, revision.value)
            assertFails { repo.record(contribution().copy(contributedValue = 1L)) }
            assertEquals(1L, revision.value)
            assertFails { db.run("UPDATE Contributions SET nonexistent_column = 1") }
            assertEquals(1L, revision.value)
            repo.removeById(contribution().id, 2000L)
            assertEquals(2L, revision.value)
            repo.removeById(contribution().id, 2000L)
            assertEquals(2L, revision.value)
        } finally { db.close() }
    }

    @Test
    fun repeatedManualGoalAndCutoffWritesDoNotPublish() = runTest {
        setToday(LocalDate(2026, 10, 11))
        val raw = TestDatabaseHelper.createEmptyDatabase()
        val revision = EffectiveReadRevision("dataset-a")
        val db = EffectiveReadDatabase(raw, revision)
        try {
            val factory = SQLModelFactory(db)
            val habits = factory.buildHabitList()
            val habit = factory.buildHabit()
            habits.add(habit)
            val entry = Entry(LocalDate(2026, 10, 6), 30000, "manual")
            habit.originalEntries.add(entry)
            val afterEntry = revision.value
            habit.originalEntries.add(entry)
            assertEquals(afterEntry, revision.value)
            val goal = HabitGoalData(habitId = habit.id!!, uuid = "goal-id",
                effectiveTimestamp = entry.date.unixTime, freqNum = 1, freqDen = 7,
                targetType = 0, targetValue = 300.0, unit = "min", updatedAt = 10L)
            factory.habitGoalRepository.upsert(goal)
            val afterGoal = revision.value
            factory.habitGoalRepository.upsert(goal)
            assertEquals(afterGoal, revision.value)
            val extension = factory.habitExtensionRepository.findByHabitId(habit.id!!)!!
            factory.habitExtensionRepository.upsert(extension.copy(statsStartTimestamp = entry.date.unixTime))
            val afterCutoff = revision.value
            factory.habitExtensionRepository.upsert(extension.copy(statsStartTimestamp = entry.date.unixTime))
            assertEquals(afterCutoff, revision.value)
            habits.globalStatisticsStartDate = entry.date
            val afterGlobalCutoff = revision.value
            habits.globalStatisticsStartDate = entry.date
            assertEquals(afterGlobalCutoff, revision.value)
        } finally { db.close() }
    }

    @Test
    fun nestedBeginIsRejectedAndObserverFailureDoesNotFailCommit() = runTest {
        val raw = TestDatabaseHelper.createEmptyDatabase()
        raw.run("DROP TABLE Repetitions")
        raw.run("CREATE TABLE Repetitions (value INTEGER)")
        val revision = EffectiveReadRevision("dataset-a")
        val db = EffectiveReadDatabase(raw, revision)
        var healthyCalls = 0
        revision.addListener { error("observer failed") }
        revision.addListener { healthyCalls++ }
        try {
            db.run("BEGIN")
            assertFailsWith<IllegalStateException> { db.run("BEGIN") }
            db.run("INSERT INTO Repetitions VALUES (30)")
            db.run("COMMIT")
            assertEquals(1L, revision.value)
            assertEquals(1, healthyCalls)
            assertEquals(1L, db.queryLong("SELECT count(*) FROM Repetitions"))
        } finally { db.close() }
    }

    @Test
    fun datasetDisposalAndListenerRemovalPreventOldCallbacks() {
        val old = EffectiveReadRevision("old")
        val fresh = EffectiveReadRevision("fresh")
        var calls = 0
        var rollbackCalls = 0
        val listener: () -> Unit = { calls++ }
        val rollbackListener: () -> Unit = { rollbackCalls++ }
        old.addRollbackListener(rollbackListener)
        old.rolledBack()
        assertEquals(1, rollbackCalls)
        assertEquals(0L, old.value)
        old.removeRollbackListener(rollbackListener)
        old.rolledBack()
        assertEquals(1, rollbackCalls)
        old.addRollbackListener(rollbackListener)
        old.addListener(listener)
        old.changed()
        old.removeListener(listener)
        old.changed()
        assertEquals(1, calls)
        old.addListener(listener)
        old.dispose()
        old.rolledBack()
        assertEquals(1, rollbackCalls)
        old.changed()
        old.addListener(listener)
        old.changed()
        assertEquals(2L, old.value)
        assertEquals(1, calls)
        fresh.addListener(listener)
        fresh.changed()
        assertEquals(1L, fresh.value)
        assertEquals(2, calls)
    }
}
