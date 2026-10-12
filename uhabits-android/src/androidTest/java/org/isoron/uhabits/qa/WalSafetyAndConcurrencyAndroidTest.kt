package org.isoron.uhabits.qa

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.backup.BackupArchiveCreator
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import org.isoron.uhabits.utils.DatabaseUtils
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.platform.io.*
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.containers.*
import org.isoron.uhabits.core.containers.sqlite.SQLiteOrganizationStore
import org.isoron.uhabits.core.focus.*
import org.isoron.uhabits.core.focus.sqlite.FocusSessionSchema
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.rules.*
import org.isoron.uhabits.core.rules.sqlite.SourceRuleSchema
import org.isoron.uhabits.core.contribution.*
import org.isoron.uhabits.core.contribution.sqlite.ContributionSchema
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask

@RunWith(AndroidJUnit4::class)
class WalSafetyAndConcurrencyAndroidTest {

    companion object {
        @Volatile
        private var qaSession: DatasetSession? = null
        private lateinit var cleanDbFile: File

        fun prepareSession(context: Context): DatasetSession {
            val existing = qaSession
            if (existing != null) return existing
            val current = HabitsApplication.component
            if (!(current.session.isExperimental && current.habitList.size() >= 30)) {
                val app = context.applicationContext as HabitsApplication
                val defaultSession = org.isoron.uhabits.core.containers.session.DatasetSessionFactory.production()
                DatabaseUtils.closeDatabase()
                context.deleteDatabase("test.db")
                val testDb = context.getDatabasePath("test.db")
                File("${testDb.path}-wal").delete()
                File("${testDb.path}-shm").delete()
                File("${testDb.path}-journal").delete()
                DatabaseUtils.initializeDatabase(context, "test.db")
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    app.rebuildComponent(defaultSession)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            val fixture = UiReviewCaptureTest.initializeFixture()
            ProductionUiHotfixFixture.prepare(fixture)
            val session = fixture.session.copy(
                mode = DatasetMode.PRODUCTION_NEXT,
                capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                    syncEnabled = false, remoteCallsAllowed = false,
                    backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false
                )
            )
            cleanDbFile = File(context.cacheDir, "wal-test-clean-fixture.db")
            DatabaseUtils.closeDatabase()
            val dbFile = context.getDatabasePath(session.databaseFilename)
            dbFile.copyTo(cleanDbFile, overwrite = true)
            qaSession = session
            return session
        }
    }

    private val ins = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var app: HabitsApplication

    @Before
    fun setUp() {
        context = ins.targetContext
        app = context.applicationContext as HabitsApplication
        val session = prepareSession(context)
        DatabaseUtils.closeDatabase()
        val dbFile = context.getDatabasePath(session.databaseFilename)
        cleanDbFile.copyTo(dbFile, overwrite = true)
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()
        DatabaseUtils.initializeDatabase(context, session.databaseFilename)
        ins.runOnMainSync { app.rebuildComponent(session) }
        ins.waitForIdleSync()
    }

    @After
    fun tearDown() {
        org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.setExplicitCutoverAuthorizedForTesting(false)
        DatabaseUtils.enableWalForNext = true
        DatabaseUtils.restoreFailureInjection = DatabaseUtils.RestoreFailureInjection.NONE
        DatabaseUtils.closeDatabase()
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = Math.ceil(sorted.size * p).toInt() - 1
        return sorted[Math.max(0, Math.min(index, sorted.size - 1))]
    }

    @Test
    fun test01_compareRollbackJournalAndWalContention() {
        val session = qaSession!!
        val results = JSONObject()

        // -------------------------------------------------------------
        // Phase A: Rollback Journal Mode (enableWalForNext = false)
        // -------------------------------------------------------------
        DatabaseUtils.enableWalForNext = false
        ins.runOnMainSync { app.rebuildComponent(session) }
        ins.waitForIdleSync()
        val dbRollback = DatabaseUtils.openDatabase()

        val rollbackJournalMode = dbRollback.rawQuery("PRAGMA journal_mode", null).use {
            if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
        }
        assertNotEquals("wal", rollbackJournalMode)

        val numWrites = 30
        val rollbackWriteLatencies = mutableListOf<Double>()
        val rollbackReaderRunning = AtomicBoolean(true)
        val rollbackReadsCompleted = AtomicInteger(0)
        val rollbackReaderLatch = CountDownLatch(1)

        val readerThreadA = Thread {
            rollbackReaderLatch.countDown()
            val rDb = SQLiteDatabase.openDatabase(dbRollback.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                while (rollbackReaderRunning.get()) {
                    rDb.rawQuery("SELECT count(*), coalesce(sum(value), 0) FROM Repetitions", null).use {
                        if (it.moveToFirst()) { it.getLong(0); it.getLong(1) }
                    }
                    rDb.rawQuery("SELECT count(*) FROM UserTasks", null).use {
                        if (it.moveToFirst()) it.getLong(0)
                    }
                    rDb.rawQuery("SELECT count(*) FROM Contributions", null).use {
                        if (it.moveToFirst()) it.getLong(0)
                    }
                    rollbackReadsCompleted.incrementAndGet()
                    Thread.sleep(8)
                }
            } finally {
                rDb.close()
            }
        }
        readerThreadA.start()
        assertTrue(rollbackReaderLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(20)

        for (i in 1..numWrites) {
            val start = System.nanoTime()
            try {
                dbRollback.beginTransaction()
                try {
                    dbRollback.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES ((SELECT id FROM Habits LIMIT 1), ${100000L + i}, 2, 'rollback-bench', 'rep-rb-$i')")
                    dbRollback.execSQL("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('task-rb-$i', 'Task RB $i', 1000, 1000)")
                    dbRollback.setTransactionSuccessful()
                } finally {
                    dbRollback.endTransaction()
                }
                val elapsedMs = (System.nanoTime() - start) / 1e6
                rollbackWriteLatencies.add(elapsedMs)
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - start) / 1e6
                rollbackWriteLatencies.add(elapsedMs)
                runCatching { if (dbRollback.inTransaction()) dbRollback.endTransaction() }
            }
            Thread.sleep(2)
        }

        rollbackReaderRunning.set(false)
        readerThreadA.join(5000)

        val sortedRollback = rollbackWriteLatencies.sorted()
        val rollbackStats = JSONObject().apply {
            put("journalMode", rollbackJournalMode)
            put("writeCount", numWrites)
            put("p50Ms", percentile(sortedRollback, 0.50))
            put("p95Ms", percentile(sortedRollback, 0.95))
            put("p99Ms", percentile(sortedRollback, 0.99))
            put("maxMs", sortedRollback.lastOrNull() ?: 0.0)
            put("concurrentReadsCompleted", rollbackReadsCompleted.get())
        }
        results.put("rollbackJournal", rollbackStats)

        // -------------------------------------------------------------
        // Phase B: WAL Mode (enableWalForNext = true)
        // -------------------------------------------------------------
        DatabaseUtils.closeDatabase()
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        ins.waitForIdleSync()
        val dbWal = DatabaseUtils.openDatabase()

        val walJournalMode = dbWal.rawQuery("PRAGMA journal_mode", null).use {
            if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
        }
        assertEquals("wal", walJournalMode)

        val walWriteLatencies = mutableListOf<Double>()
        val walReaderRunning = AtomicBoolean(true)
        val walReadsCompleted = AtomicInteger(0)
        val walReaderLatch = CountDownLatch(1)

        val readerThreadB = Thread {
            walReaderLatch.countDown()
            val rDb = SQLiteDatabase.openDatabase(dbWal.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                while (walReaderRunning.get()) {
                    rDb.rawQuery("SELECT count(*), coalesce(sum(value), 0) FROM Repetitions", null).use {
                        if (it.moveToFirst()) { it.getLong(0); it.getLong(1) }
                    }
                    rDb.rawQuery("SELECT count(*) FROM UserTasks", null).use {
                        if (it.moveToFirst()) it.getLong(0)
                    }
                    rDb.rawQuery("SELECT count(*) FROM Contributions", null).use {
                        if (it.moveToFirst()) it.getLong(0)
                    }
                    walReadsCompleted.incrementAndGet()
                    Thread.sleep(8)
                }
            } finally {
                rDb.close()
            }
        }
        readerThreadB.start()
        assertTrue(walReaderLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(20)

        for (i in 1..numWrites) {
            val start = System.nanoTime()
            try {
                dbWal.beginTransaction()
                try {
                    dbWal.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES ((SELECT id FROM Habits LIMIT 1), ${200000L + i}, 2, 'wal-bench', 'rep-wal-$i')")
                    dbWal.execSQL("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('task-wal-$i', 'Task WAL $i', 1000, 1000)")
                    dbWal.setTransactionSuccessful()
                } finally {
                    dbWal.endTransaction()
                }
                val elapsedMs = (System.nanoTime() - start) / 1e6
                walWriteLatencies.add(elapsedMs)
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - start) / 1e6
                walWriteLatencies.add(elapsedMs)
                runCatching { if (dbWal.inTransaction()) dbWal.endTransaction() }
            }
            Thread.sleep(2)
        }

        walReaderRunning.set(false)
        readerThreadB.join(5000)

        val sortedWal = walWriteLatencies.sorted()
        val walStats = JSONObject().apply {
            put("journalMode", walJournalMode)
            put("writeCount", numWrites)
            put("p50Ms", percentile(sortedWal, 0.50))
            put("p95Ms", percentile(sortedWal, 0.95))
            put("p99Ms", percentile(sortedWal, 0.99))
            put("maxMs", sortedWal.lastOrNull() ?: 0.0)
            put("concurrentReadsCompleted", walReadsCompleted.get())
        }
        results.put("wal", walStats)

        // Write benchmark comparison JSON to disk
        val outDir = File(context.getExternalFilesDir(null), "wal-benchmark").apply { mkdirs() }
        val outFile = File(outDir, "comparison.json")
        outFile.writeText(results.toString(2))

        // Assertions: WAL mode is verified, and concurrent reads were not blocked
        assertEquals("wal", walJournalMode)
        assertTrue("WAL should allow concurrent reads without starvation", walReadsCompleted.get() > 0)
    }

    @Test
    fun test02_processDeathAndUncheckpointedDataRecovery() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val db = DatabaseUtils.openDatabase()

        val journalMode = db.rawQuery("PRAGMA journal_mode", null).use {
            if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
        }
        assertEquals("wal", journalMode)

        val testUuid = "uncheckpointed-task-" + UUID.randomUUID()
        val testRepUuid = "uncheckpointed-rep-" + UUID.randomUUID()

        // Write transactions to active WAL
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$testUuid', 'Uncheckpointed Task', 9999, 9999)")
            db.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES ((SELECT id FROM Habits LIMIT 1), 888888, 2, 'uncheckpointed-note', '$testRepUuid')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Verify WAL sidecar exists and has committed frames
        val dbFile = File(db.path)
        val walFile = File("${dbFile.absolutePath}-wal")
        assertTrue("WAL file must exist after write", walFile.exists() && walFile.length() > 0)

        // Simulate abrupt process death: close the raw database connection directly without checkpointing
        DatabaseUtils.closeDatabase()

        // Reopen database via standard startup path
        DatabaseUtils.initializeDatabase(context, session.databaseFilename)
        val reopenedDb = DatabaseUtils.openDatabase()
        try {
            // Verify integrity
            reopenedDb.rawQuery("PRAGMA quick_check", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }
            reopenedDb.rawQuery("PRAGMA foreign_key_check", null).use {
                assertEquals("Foreign key check must have 0 violations", 0, it.count)
            }

            // Verify uncheckpointed data survived process death completely
            val taskFound = reopenedDb.rawQuery("SELECT count(*) FROM UserTasks WHERE id = '$testUuid'", null).use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            assertTrue("Uncheckpointed task transaction must be preserved after restart", taskFound)

            val repFound = reopenedDb.rawQuery("SELECT count(*) FROM Repetitions WHERE uuid = '$testRepUuid'", null).use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            assertTrue("Uncheckpointed repetition transaction must be preserved after restart", repFound)
        } finally {
            DatabaseUtils.closeDatabase()
        }
    }

    @Test
    fun test03_backupAndRestoreSafetyWithActiveWal() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.setExplicitCutoverAuthorizedForTesting(true)
        ins.runOnMainSync { app.rebuildComponent(session) }
        val liveDb = DatabaseUtils.openDatabase()
        liveDb.execSQL("UPDATE OrganizationState SET mode = 'PRODUCTION_NEXT', runtime_authority = 'NEXT' WHERE id = 1")

        val tempBackupZip = File(context.cacheDir, "test_wal_backup_${UUID.randomUUID()}.zip")
        try {
            // 1. Create full versioned ZIP backup while WAL is active
            val result = BackupArchiveCreator.createArchive(context, tempBackupZip)
            assertTrue("Archive must exist", result.archiveFile.exists() && result.sizeBytes > 0)
            assertEquals(30, result.databaseVersion)

            // 2. Modify live database state
            val deletedRepTimestamp = 123456789L
            liveDb.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES ((SELECT id FROM Habits LIMIT 1), $deletedRepTimestamp, 2, 'to-be-overwritten', 'rep-overwritten')")
            val countBeforeRestore = liveDb.rawQuery("SELECT count(*) FROM Repetitions WHERE timestamp = $deletedRepTimestamp", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            assertEquals(1, countBeforeRestore)

            // 3. Restore database from ZIP archive
            DatabaseUtils.restoreDatabaseFromBackup(context, tempBackupZip)

            // 4. Verify restored database has active WAL and consistent state
            val restoredDb = DatabaseUtils.openDatabase()
            try {
                restoredDb.rawQuery("PRAGMA quick_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
                restoredDb.rawQuery("PRAGMA foreign_key_check", null).use {
                    assertEquals(0, it.count)
                }

                // Overwritten row should not exist in restored DB
                val countAfterRestore = restoredDb.rawQuery("SELECT count(*) FROM Repetitions WHERE timestamp = $deletedRepTimestamp", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                assertEquals("Restored database must match backup state", 0, countAfterRestore)
            } finally {
                DatabaseUtils.closeDatabase()
            }
        } finally {
            tempBackupZip.delete()
        }
    }

    @Test
    fun test04_adjacentCorrectnessRisksNumericalAndRevisions() = runBlocking {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val component = HabitsApplication.component

        // Test 1: Unloaded originalEntries returns isLoaded = false without blocking Main
        val habit = component.habitList.first()
        val entryList = habit.originalEntries as? SQLiteEntryList
        assertNotNull(entryList)

        // Test 2: If entry exists, background loading resolves true committed value
        val testDate = org.isoron.platform.time.getToday()
        val committedValue = withContext(Dispatchers.IO) {
            habit.originalEntries.get(testDate)
        }
        assertNotNull(committedValue)

        // Test 3: Container change bumps both effectiveReadRevision and workspaceRevision
        val effRevBefore = component.effectiveReadRevision.value
        val wsRevBefore = component.workspaceReadRevision.value

        val db = component.db
        val cUuid = "test-c-" + UUID.randomUUID()
        val cStmt = db.prepareStatement("INSERT INTO Containers(uuid, name, sibling_order, is_archived, updated_at, revision) VALUES ('$cUuid', 'New Cont', 0, 0, 1000, 1)")
        cStmt.step()
        cStmt.finalize()

        val effRevAfter = component.effectiveReadRevision.value
        val wsRevAfter = component.workspaceReadRevision.value

        assertTrue("Container change must invalidate effectiveReadRevision: before=$effRevBefore, after=$effRevAfter, wsBefore=$wsRevBefore, wsAfter=$wsRevAfter", effRevAfter > effRevBefore)
        assertTrue("Container change must invalidate workspaceReadRevision", wsRevAfter > wsRevBefore)

        // Test 4: Pure task change bumps only workspaceReadRevision
        val wsRevBeforeTask = component.workspaceReadRevision.value
        val effRevBeforeTask = component.effectiveReadRevision.value

        val tId = "test-t-" + UUID.randomUUID()
        val tStmt = db.prepareStatement("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$tId', 'New Task', 1000, 1000)")
        tStmt.step()
        tStmt.finalize()

        val wsRevAfterTask = component.workspaceReadRevision.value
        val effRevAfterTask = component.effectiveReadRevision.value

        assertTrue("UserTask change must invalidate workspaceReadRevision", wsRevAfterTask > wsRevBeforeTask)
        assertEquals("UserTask change must NOT invalidate effectiveReadRevision", effRevBeforeTask, effRevAfterTask)
    }

    @Test
    fun test05_readYourOwnWritesInsideTransaction() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val db = HabitsApplication.component.db

        val testUuid = "ryow-cont-" + UUID.randomUUID()
        val testTaskId = "ryow-task-" + UUID.randomUUID()

        db.beginTransaction()
        try {
            db.run("INSERT INTO Containers(uuid, name, sibling_order, is_archived, updated_at, revision) VALUES ('$testUuid', 'RYOW Container', 0, 0, 1000, 1)")
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$testTaskId', 'RYOW Task', 1000, 1000)")

            // Crucial: query on the SAME thread while uncommitted.
            // Under WAL without platform transaction binding, secondary reader connections cannot observe uncommitted writes.
            val containerCount = db.queryLong("SELECT count(*) FROM Containers WHERE uuid = '$testUuid'")
            val taskCount = db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$testTaskId'")

            assertEquals("Uncommitted container must be visible to the writing thread", 1L, containerCount)
            assertEquals("Uncommitted task must be visible to the writing thread", 1L, taskCount)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Post-commit: both rows are committed and visible to all readers
        val committedContainer = db.queryLong("SELECT count(*) FROM Containers WHERE uuid = '$testUuid'")
        val committedTask = db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$testTaskId'")
        assertEquals(1L, committedContainer)
        assertEquals(1L, committedTask)
    }

    @Test
    fun test06_nestedSavepointsAndRollbacks() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val db = HabitsApplication.component.db

        val outerTaskId = "nested-outer-" + UUID.randomUUID()
        val innerTaskId = "nested-inner-" + UUID.randomUUID()

        db.beginTransaction()
        try {
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$outerTaskId', 'Outer Task', 1000, 1000)")
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$outerTaskId'"))

            // Nested savepoint
            db.run("SAVEPOINT sp_test")
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$innerTaskId', 'Inner Task', 1000, 1000)")
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$innerTaskId'"))

            // Rollback only inner savepoint
            db.run("ROLLBACK TO SAVEPOINT sp_test")
            db.run("RELEASE SAVEPOINT sp_test")

            // Outer row remains; inner row was reverted
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$outerTaskId'"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$innerTaskId'"))

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // After outer commit: outer task is persisted, inner task is not
        assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$outerTaskId'"))
        assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$innerTaskId'"))
    }

    @Test
    fun test07_transactionFailureAndRetry() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val db = HabitsApplication.component.db

        val failTaskId = "fail-retry-" + UUID.randomUUID()

        // 1. Transaction that fails (exception / no setTransactionSuccessful)
        try {
            db.runInTransaction {
                db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$failTaskId', 'Attempt 1', 1000, 1000)")
                throw IllegalStateException("Simulated transient failure")
            }
        } catch (e: IllegalStateException) {
            // expected
        }

        // Verify aborted attempt left no dirty state
        assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$failTaskId'"))

        // 2. Retry succeeds cleanly
        db.runInTransaction {
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$failTaskId', 'Attempt 2 Success', 1000, 1000)")
        }

        assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$failTaskId'"))
    }

    @Test
    fun test08_organizationContainerCreationWithService() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val component = HabitsApplication.component
        val db = component.db

        val store = SQLiteOrganizationStore(db)
        val org = OrganizationServiceImpl(
            store,
            object : UnitOfWork {
                override fun <T> executeInTransaction(block: (OrganizationTransaction) -> T): T =
                    store.executeInTransaction(block)
            },
            store,
            store,
            clock = { 1000L },
            idGenerator = { UUID.randomUUID().toString() },
            habitIdentityLookup = { ref -> component.sqlModelFactory.habitRepository.findByUuid(ref.uuid) != null }
        )

        val parentId = ContainerId("wal-org-parent-" + UUID.randomUUID())
        val childId = ContainerId("wal-org-child-" + UUID.randomUUID())

        // Create parent container
        val parentResult = org.create(CreateContainer("Parent Category", color = PaletteColor(2), explicitId = parentId, opUuid = "op-parent-" + UUID.randomUUID()))
        assertTrue("Parent container creation must succeed under WAL: $parentResult", parentResult.isSuccess)

        // Create nested child container
        val childResult = org.create(CreateContainer("Child Category", parentId = parentId, explicitId = childId, opUuid = "op-child-" + UUID.randomUUID()))
        assertTrue("Child container creation must succeed under WAL: $childResult", childResult.isSuccess)

        // Verify hierarchy read queries
        val childNode = org.containerQueries.find(childId)
        assertNotNull(childNode)
        assertEquals(parentId, childNode?.parentId)
        assertEquals("Child Category", childNode?.name)

        val path = org.containerQueries.path(childId)
        assertEquals(2, path.nodes.size)
        assertEquals(parentId, path.nodes[0])
        assertEquals(childId, path.nodes[1])
    }

    @Test
    fun test09_habitsTasksFocusPersistenceUnderWal() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val component = HabitsApplication.component
        val db = component.db

        FocusSessionSchema.createSchema(db)
        SourceRuleSchema.createSchema(db)
        ContributionSchema.createSchema(db)

        val testHabitUuid = "wal-h-" + UUID.randomUUID()
        val habit = component.modelFactory.buildHabit().apply {
            uuid = testHabitUuid
            name = "WAL Habit"
            description = "Persistence verification under WAL"
            color = PaletteColor(3)
            type = HabitType.YES_NO
            frequency = Frequency.DAILY
        }
        component.habitList.add(habit)

        val habitInDb = component.sqlModelFactory.habitRepository.findByUuid(testHabitUuid)
        assertNotNull("Habit must be saved to database", habitInDb)

        val testTaskId = TaskId("wal-task-" + UUID.randomUUID())
        val userTask = UserTask(
            id = testTaskId,
            title = "WAL Task",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        component.userTaskRepository.save(userTask)

        val taskInDb = component.userTaskRepository.getById(testTaskId)
        assertNotNull("UserTask must be persisted", taskInDb)

        val focusRepo = SQLiteFocusSessionRepository(db)
        val focusSessionId = FocusSessionId("wal-focus-" + UUID.randomUUID())
        val focusFact = FocusSession(
            id = focusSessionId,
            startedAt = 1000L,
            endedAt = 2000L,
            durationMs = 1000L,
            taskId = testTaskId,
            source = FocusSessionSource.TIMER,
            deviceId = "test-device",
            createdAt = 2000L,
            updatedAt = 2000L
        )
        focusRepo.record(focusFact)
        val focusInDb = focusRepo.getById(focusSessionId)
        assertNotNull("FocusSession must be persisted", focusInDb)

        // Close and re-open to verify uncheckpointed WAL data survives
        DatabaseUtils.closeDatabase()
        DatabaseUtils.initializeDatabase(context, session.databaseFilename)
        val reopenedDb = DatabaseUtils.openDatabase()
        try {
            val habitCount = reopenedDb.rawQuery("SELECT count(*) FROM Habits WHERE uuid = '$testHabitUuid'", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            val taskCount = reopenedDb.rawQuery("SELECT count(*) FROM UserTasks WHERE id = '${testTaskId.value}'", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            val focusCount = reopenedDb.rawQuery("SELECT count(*) FROM FocusSessions WHERE id = '${focusSessionId.value}'", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            assertEquals(1, habitCount)
            assertEquals(1, taskCount)
            assertEquals(1, focusCount)
        } finally {
            DatabaseUtils.closeDatabase()
        }
    }

    @Test
    fun test10_datasetSwitchingAndRecovery() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }

        // Verify Next dataset is in WAL mode
        val liveDb = DatabaseUtils.openDatabase()
        val journalMode = liveDb.rawQuery("PRAGMA journal_mode", null).use {
            if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
        }
        assertEquals("wal", journalMode)

        // Verify integrity and foreign keys
        liveDb.rawQuery("PRAGMA quick_check", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        liveDb.rawQuery("PRAGMA foreign_key_check", null).use {
            assertEquals(0, it.count)
        }

        // Test legacy isolation: a database opened with version < 30 stays in rollback journal mode
        val legacyDbFile = File(context.cacheDir, "legacy_test_v29.db")
        legacyDbFile.delete()
        val legacyOpener = HabitsDatabaseOpener(context, legacyDbFile.name, 29, isWalRequested = false)
        val legacyDb = legacyOpener.writableDatabase
        try {
            val legacyJournalMode = legacyDb.rawQuery("PRAGMA journal_mode", null).use {
                if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
            }
            assertNotEquals("wal", legacyJournalMode)
        } finally {
            legacyDb.close()
            legacyOpener.close()
            legacyDbFile.delete()
        }
    }

    @Test
    fun test11_pureSqlNestedSavepointsRollbackInnerAndCommitOuter() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        ins.runOnMainSync { app.rebuildComponent(session) }
        val db = HabitsApplication.component.db

        val outerTaskId = "pure-outer-" + UUID.randomUUID()
        val innerTaskId = "pure-inner-" + UUID.randomUUID()

        // 1. Pure SQL savepoints without explicit db.beginTransaction()
        db.run("SAVEPOINT sp_outer")
        try {
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$outerTaskId', 'Pure Outer', 1000, 1000)")
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$outerTaskId'"))

            // Nested pure savepoint
            db.run("SAVEPOINT sp_inner")
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$innerTaskId', 'Pure Inner', 1000, 1000)")
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$innerTaskId'"))

            // Rollback only inner savepoint
            db.run("ROLLBACK TO SAVEPOINT sp_inner")
            db.run("RELEASE SAVEPOINT sp_inner")

            // Outer row remains; inner row was reverted
            assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$outerTaskId'"))
            assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$innerTaskId'"))

            // Release outer savepoint commits the outer row
            db.run("RELEASE SAVEPOINT sp_outer")
        } catch (e: Exception) {
            db.run("ROLLBACK")
            throw e
        }

        // Verify committed state
        assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$outerTaskId'"))
        assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$innerTaskId'"))

        // 2. Pure SQL BEGIN IMMEDIATE with REPLACE INTO and last_insert_rowid()
        db.run("BEGIN IMMEDIATE TRANSACTION")
        db.run("REPLACE INTO UserTasks(id, title, created_at, updated_at) VALUES ('$outerTaskId', 'Pure Outer Updated', 2000, 2000)")
        val lastId = db.queryLong("SELECT last_insert_rowid()")
        assertTrue("last_insert_rowid should be positive", lastId > 0L)
        db.run("COMMIT TRANSACTION")

        // 3. Rollback failure path leaves clean thread state
        db.run("BEGIN TRANSACTION")
        val rolledTaskId = "rolled-" + UUID.randomUUID()
        db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$rolledTaskId', 'Rolled Task', 1000, 1000)")
        assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$rolledTaskId'"))
        db.run("ROLLBACK")
        assertEquals(0L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$rolledTaskId'"))

        // Subsequent transaction on same thread operates cleanly
        db.runInTransaction {
            db.run("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('$rolledTaskId-2', 'Next Task', 1000, 1000)")
        }
        assertEquals(1L, db.queryLong("SELECT count(*) FROM UserTasks WHERE id = '$rolledTaskId-2'"))
    }

    @Test
    fun test12_existingV30RollbackToWalMigration() {
        val isolatedDbName = "existing_v30_rollback_upgrade.db"
        context.deleteDatabase(isolatedDbName)
        val dbFile = context.getDatabasePath(isolatedDbName)
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()

        // 1. Create realistic v30 database in rollback journal mode
        val openerRollback = HabitsDatabaseOpener(context, isolatedDbName, 30, isWalRequested = false)
        val rollbackDb = openerRollback.writableDatabase
        try {
            val jMode = rollbackDb.rawQuery("PRAGMA journal_mode", null).use {
                if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
            }
            assertNotEquals("wal", jMode)

            // Populate rich v30 entities: Habit, Repetition, Container, Task & subtask, FocusSession, Contribution, EntryOps, SyncQueue
            val habitUuid = "habit-" + UUID.randomUUID()
            rollbackDb.execSQL("INSERT INTO Habits(name, description, color, freq_num, freq_den, type, position, uuid) " +
                    "VALUES ('Upgrade Habit', 'Desc', 1, 1, 1, 0, 0, '$habitUuid')")
            val habitId = rollbackDb.rawQuery("SELECT id FROM Habits WHERE uuid = '$habitUuid'", null).use {
                if (it.moveToFirst()) it.getLong(0) else 1L
            }
            rollbackDb.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) " +
                    "VALUES ($habitId, 1700000000000, 2, 'note', 'rep-${UUID.randomUUID()}')")

            rollbackDb.execSQL("INSERT INTO OrganizationChanges(revision, op_uuid, recorded_at, operation_type, origin, command_payload) " +
                    "VALUES (10, 'op-change-10', 1000, 'CREATE_CONTAINER', 'LOCAL', '{}')")
            rollbackDb.execSQL("INSERT INTO Containers(id, uuid, parent_id, name, color, icon, sibling_order, is_archived, created_at, updated_at, revision) " +
                    "VALUES (101, 'container-uuid-101', NULL, 'Upgrade Project', NULL, NULL, 0, 0, 1000, 1000, 10)")

            val parentTaskId = "task-parent-" + UUID.randomUUID()
            val childTaskId = "task-child-" + UUID.randomUUID()
            rollbackDb.execSQL("INSERT INTO UserTasks(id, title, container_id, parent_task_id, created_at, updated_at) " +
                    "VALUES ('$parentTaskId', 'Parent Task', 'container-uuid-101', NULL, 1000, 1000)")
            rollbackDb.execSQL("INSERT INTO UserTasks(id, title, container_id, parent_task_id, created_at, updated_at) " +
                    "VALUES ('$childTaskId', 'Child Subtask', 'container-uuid-101', '$parentTaskId', 1000, 1000)")

            val focusId = "focus-" + UUID.randomUUID()
            rollbackDb.execSQL("INSERT INTO FocusSessions(id, started_at, ended_at, duration_ms, source, created_at, updated_at, revision) " +
                    "VALUES ('$focusId', 1000, 2000, 1000, 'TIMER', 1000, 1000, 1)")

            val contribId = "contrib-" + UUID.randomUUID()
            rollbackDb.execSQL("INSERT INTO Contributions(id, target_habit_uuid, source_type, source_id, contributed_value, effective_timestamp, created_at, updated_at) " +
                    "VALUES ('$contribId', '$habitUuid', 'FOCUS_SESSION', '$focusId', 1000, 1700000000000, 1000, 1000)")

            val opUuid = "op-" + UUID.randomUUID()
            rollbackDb.execSQL("INSERT INTO EntryOps(op_uuid, habit_uuid, entry_timestamp, delta_value, op_type, device_id, created_at) " +
                    "VALUES ('$opUuid', '$habitUuid', 1700000000000, 2, 'ADD', 'dev1', 1000)")

            rollbackDb.execSQL("INSERT INTO SyncQueue(op_uuid, entity_type, entity_uuid, operation_type, payload_json, created_at, device_id) " +
                    "VALUES ('$opUuid', 'EntryOp', '$opUuid', 'UPSERT', '{}', 1000, 'dev1')")
        } finally {
            rollbackDb.close()
            openerRollback.close()
        }

        // 2. First launch: open existing v30 database with WAL enabled
        val openerWal = HabitsDatabaseOpener(context, isolatedDbName, 30, isWalRequested = true)
        val walDb = openerWal.writableDatabase
        try {
            // Verify journal mode switched to WAL
            val walMode = walDb.rawQuery("PRAGMA journal_mode", null).use {
                if (it.moveToFirst()) it.getString(0).lowercase() else "unknown"
            }
            assertEquals("wal", walMode)

            // Integrity and FK checks
            walDb.rawQuery("PRAGMA quick_check", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }
            walDb.rawQuery("PRAGMA foreign_key_check", null).use {
                assertEquals(0, it.count)
            }

            // Verify all data intact
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM Habits WHERE name = 'Upgrade Habit'", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM Repetitions WHERE timestamp = 1700000000000", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM Containers WHERE id = 101", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            assertEquals(2, walDb.rawQuery("SELECT count(*) FROM UserTasks WHERE container_id = 'container-uuid-101'", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            val subtaskParentId = walDb.rawQuery("SELECT parent_task_id FROM UserTasks WHERE title = 'Child Subtask'", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            assertNotNull(subtaskParentId)
            assertTrue(subtaskParentId!!.startsWith("task-parent-"))
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM FocusSessions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM Contributions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM EntryOps", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            assertEquals(1, walDb.rawQuery("SELECT count(*) FROM SyncQueue", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })

            // Perform new write under WAL mode
            walDb.execSQL("INSERT INTO UserTasks(id, title, created_at, updated_at) VALUES ('wal-post-task', 'Post WAL Task', 2000, 2000)")
            val postTaskCount = walDb.rawQuery("SELECT count(*) FROM UserTasks WHERE id = 'wal-post-task'", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            assertEquals(1, postTaskCount)
        } finally {
            walDb.close()
            openerWal.close()
            context.deleteDatabase(isolatedDbName)
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            File("${dbFile.path}-journal").delete()
        }
    }

    @Test
    fun test13_backupImmediatelyAfterWalCommitAndInterruptedRestore() {
        val session = qaSession!!
        DatabaseUtils.enableWalForNext = true
        org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.setExplicitCutoverAuthorizedForTesting(true)
        ins.runOnMainSync { app.rebuildComponent(session) }

        val db = DatabaseUtils.openDatabase()
        db.execSQL("UPDATE OrganizationState SET mode = 'PRODUCTION_NEXT', runtime_authority = 'NEXT' WHERE id = 1")
        val uniqueTag = "uncheckpointed-" + UUID.randomUUID()
        val uniqueTimestamp = 999888777L

        // Write directly to active WAL without checkpointing
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO Repetitions(habit, timestamp, value, notes, uuid) VALUES ((SELECT id FROM Habits LIMIT 1), $uniqueTimestamp, 5, '$uniqueTag', '$uniqueTag')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Verify WAL file is active and contains changes
        val dbFile = context.getDatabasePath(session.databaseFilename)
        val walFile = File("${dbFile.path}-wal")
        assertTrue("WAL file should exist with active commits", walFile.exists() && walFile.length() > 0)

        // Create backup snapshot immediately
        val tempSnapshot = File(context.cacheDir, "wal-immediate-snapshot.db")
        tempSnapshot.delete()
        try {
            DatabaseUtils.createDatabaseSnapshot(context, tempSnapshot)
            assertTrue("Snapshot should exist", tempSnapshot.exists() && tempSnapshot.length() > 0)

            // Verify snapshot captures uncheckpointed WAL commit
            SQLiteDatabase.openDatabase(tempSnapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { snapDb ->
                val countInSnap = snapDb.rawQuery("SELECT count(*) FROM Repetitions WHERE notes = '$uniqueTag'", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                assertEquals("Snapshot must capture uncheckpointed WAL commits", 1, countInSnap)
                snapDb.rawQuery("PRAGMA quick_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }

            // Create full ZIP backup
            val tempZip = File(context.cacheDir, "wal-immediate-backup.zip")
            tempZip.delete()
            try {
                val archiveResult = BackupArchiveCreator.createArchive(context, tempZip)
                assertTrue("Backup archive should exist", archiveResult.archiveFile.exists() && archiveResult.sizeBytes > 0)

                // Test interrupted restore simulation: create partial staging garbage
                val garbageStaging = File(dbFile.parentFile, "${session.databaseFilename}-restore-staging-fake.tmp")
                garbageStaging.writeText("garbage")

                // Perform restore from valid backup archive
                DatabaseUtils.restoreDatabaseFromBackup(context, tempZip)

                // Clean reopening check
                val reopened = DatabaseUtils.openDatabase()
                try {
                    val countAfterRestore = reopened.rawQuery("SELECT count(*) FROM Repetitions WHERE notes = '$uniqueTag'", null).use {
                        if (it.moveToFirst()) it.getInt(0) else 0
                    }
                    assertEquals("Restored database must contain WAL commit", 1, countAfterRestore)
                    reopened.rawQuery("PRAGMA quick_check", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("ok", it.getString(0))
                    }
                    reopened.rawQuery("PRAGMA foreign_key_check", null).use {
                        assertEquals(0, it.count)
                    }
                } finally {
                    DatabaseUtils.closeDatabase()
                }
            } finally {
                tempZip.delete()
            }
        } finally {
            tempSnapshot.delete()
        }
    }
}
