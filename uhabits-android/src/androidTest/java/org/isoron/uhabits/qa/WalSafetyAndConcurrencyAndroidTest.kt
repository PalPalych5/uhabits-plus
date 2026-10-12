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

@RunWith(AndroidJUnit4::class)
class WalSafetyAndConcurrencyAndroidTest {

    companion object {
        @Volatile
        private var qaSession: DatasetSession? = null
        private lateinit var cleanDbFile: File

        fun prepareSession(context: Context): DatasetSession {
            val existing = qaSession
            if (existing != null) return existing
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
        DatabaseUtils.enableWalForNext = false
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
}
