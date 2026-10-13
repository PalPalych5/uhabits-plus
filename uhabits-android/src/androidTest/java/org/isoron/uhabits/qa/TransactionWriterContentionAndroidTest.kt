package org.isoron.uhabits.qa

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.io.run
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.database.AndroidDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns only a UUID database in the test APK cache. Run on a disposable emulator.
 * On a deadlock, retain the file and never close the blocked connection; terminate
 * the disposable instrumentation process after collecting the failure.
 */
@RunWith(AndroidJUnit4::class)
class TransactionWriterContentionAndroidTest {
    @Test fun twoWritersCompleteWithWal() = checkTwoWriters("wal")
    @Test fun twoWritersCompleteWithRollbackJournal() = checkTwoWriters("delete")

    private fun requireDisposableEmulator() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val expected = "UHabit_UI_Review_Disposable_20261011"
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == expected) {
            "Verified disposable AVD token required"
        }
        check(androidx.test.uiautomator.UiDevice.getInstance(ins)
            .executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == expected) {
            "Actual AVD identity mismatch"
        }
    }

    @Test fun releaseOuterSavepointCommitsItsNestedChildren() = checkNamedSavepoint(false)
    @Test fun rollbackToOuterThenReleaseCommitsOnlyRemainingWrites() = checkNamedSavepoint(true)
    @Test fun successfulPlatformChildPreservesOuterSavepoint() = checkMixedNesting(true)
    @Test fun unsuccessfulPlatformChildRollsBackOuterSavepoint() = checkMixedNesting(false)

    @Test fun rollbackToSavepointCannotClearFailedPlatformChild() {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.cacheDir.exists() || context.cacheDir.mkdirs())
        val file = File(context.cacheDir, "failed-child-savepoint-${UUID.randomUUID()}.db")
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val revision = EffectiveReadRevision("disposable-failed-child")
        var rollbacks = 0
        revision.addRollbackListener { rollbacks++ }
        val db = AndroidDatabase(raw, revision)
        try {
            db.run("PRAGMA journal_mode=wal")
            db.run("PRAGMA user_version=30")
            db.run("CREATE TABLE Repetitions(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
            db.run("SAVEPOINT outer_frame")
            db.run("SAVEPOINT inner_frame")
            db.beginTransaction()
            db.run("INSERT INTO Repetitions VALUES(1, 10)")
            db.endTransaction()
            db.run("ROLLBACK TO outer_frame")
            db.run("INSERT INTO Repetitions VALUES(2, 20)")
            db.run("RELEASE outer_frame")
            assertFalse(raw.inTransaction())
            assertEquals(0L, revision.value)
            assertTrue("Failed platform child requires rollback notification", rollbacks > 0)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reopened ->
                val reader = AndroidDatabase(reopened)
                assertEquals(0L, reader.queryLong("SELECT count(*) FROM Repetitions"))
                assertEquals("ok", reader.queryString("PRAGMA integrity_check"))
            }
        } finally {
            while (raw.inTransaction()) raw.endTransaction()
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    private fun checkMixedNesting(successfulChild: Boolean) {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.cacheDir.exists() || context.cacheDir.mkdirs())
        val file = File(context.cacheDir, "mixed-nesting-${UUID.randomUUID()}.db")
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val revision = EffectiveReadRevision("disposable-mixed")
        var rollbacks = 0
        revision.addRollbackListener { rollbacks++ }
        val db = AndroidDatabase(raw, revision)
        try {
            db.run("PRAGMA journal_mode=wal")
            db.run("PRAGMA user_version=30")
            db.run("CREATE TABLE Repetitions(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
            db.run("SAVEPOINT outer_frame")
            db.run("INSERT INTO Repetitions VALUES(1, 10)")
            db.beginTransaction()
            db.run("INSERT INTO Repetitions VALUES(2, 20)")
            if (successfulChild) db.setTransactionSuccessful()
            db.endTransaction()
            assertTrue("Child end must preserve the outer writer", raw.inTransaction())
            db.run("RELEASE outer_frame")
            assertFalse("Outer release must close the native writer", raw.inTransaction())
            assertEquals(if (successfulChild) 1L else 0L, revision.value)
            if (!successfulChild) assertTrue("Rollback callback required", rollbacks > 0)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reopened ->
                val reader = AndroidDatabase(reopened)
                assertEquals(if (successfulChild) 2L else 0L, reader.queryLong("SELECT count(*) FROM Repetitions"))
                assertEquals("ok", reader.queryString("PRAGMA integrity_check"))
            }
        } finally {
            while (raw.inTransaction()) raw.endTransaction()
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test fun unsuccessfulNestedPlatformTransactionPublishesRollbackOnly() {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.cacheDir.exists() || context.cacheDir.mkdirs())
        val file = File(context.cacheDir, "nested-platform-${UUID.randomUUID()}.db")
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val revision = EffectiveReadRevision("disposable-nested-platform")
        var rollbacks = 0
        revision.addRollbackListener { rollbacks++ }
        val db = AndroidDatabase(raw, revision)
        try {
            db.run("PRAGMA journal_mode=wal")
            db.run("PRAGMA user_version=30")
            db.run("CREATE TABLE Repetitions(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
            db.beginTransaction()
            db.run("INSERT INTO Repetitions VALUES(1, 10)")
            db.beginTransaction()
            db.run("INSERT INTO Repetitions VALUES(2, 20)")
            db.endTransaction() // No success: Android marks the outer transaction for rollback.
            db.setTransactionSuccessful()
            db.endTransaction()
            assertFalse(raw.inTransaction())
            assertEquals(0L, db.queryLong("SELECT count(*) FROM Repetitions"))
            assertEquals("A rolled-back outer transaction is not a durable revision", 0L, revision.value)
            assertTrue("Speculative model state must receive rollback notification", rollbacks > 0)
            assertEquals("ok", db.queryString("PRAGMA integrity_check"))
        } finally {
            while (raw.inTransaction()) raw.endTransaction()
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    @Test fun sameThreadSavepointsRemainIsolatedAcrossDatabases() {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.cacheDir.exists() || context.cacheDir.mkdirs())
        val files = (1..2).map { File(context.cacheDir, "savepoint-isolation-${UUID.randomUUID()}.db") }
        val raws = files.map { SQLiteDatabase.openOrCreateDatabase(it, null) }
        val dbs = raws.mapIndexed { index, raw -> AndroidDatabase(raw, EffectiveReadRevision("disposable-$index")) }
        try {
            dbs.forEach {
                it.run("PRAGMA journal_mode=wal")
                it.run("PRAGMA user_version=30")
                it.run("CREATE TABLE Repetitions(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
            }
            dbs[0].run("SAVEPOINT first_db")
            dbs[0].run("INSERT INTO Repetitions VALUES(1, 10)")
            dbs[1].run("SAVEPOINT second_db")
            dbs[1].run("INSERT INTO Repetitions VALUES(2, 20)")
            dbs[0].run("RELEASE first_db")
            assertFalse("First database must commit independently", raws[0].inTransaction())
            assertTrue("Second database must retain its own transaction", raws[1].inTransaction())
            dbs[1].run("RELEASE second_db")
            assertFalse(raws[1].inTransaction())
            files.forEachIndexed { index, file ->
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reopened ->
                    val reader = AndroidDatabase(reopened)
                    assertEquals(1L, reader.queryLong("SELECT count(*) FROM Repetitions"))
                    assertEquals((index + 1) * 10L, reader.queryLong("SELECT sum(value) FROM Repetitions"))
                    assertEquals("ok", reader.queryString("PRAGMA integrity_check"))
                }
            }
        } finally {
            raws.forEach { while (it.inTransaction()) it.endTransaction() }
            dbs.forEach { it.close() }
            files.forEach { SQLiteDatabase.deleteDatabase(it) }
        }
    }

    private fun checkNamedSavepoint(rollback: Boolean) {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.cacheDir.exists() || context.cacheDir.mkdirs())
        val file = File(context.cacheDir, "savepoint-contention-${UUID.randomUUID()}.db")
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val db = AndroidDatabase(raw, EffectiveReadRevision("disposable-savepoint"))
        try {
            db.run("PRAGMA journal_mode=wal")
            db.run("PRAGMA user_version=30")
            db.run("CREATE TABLE Repetitions(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
            db.run("SAVEPOINT outer_frame")
            db.run("INSERT INTO Repetitions VALUES(1, 10)")
            db.run("SAVEPOINT inner_frame")
            db.run("INSERT INTO Repetitions VALUES(2, 20)")
            if (rollback) {
                db.run("ROLLBACK TO outer_frame")
                db.run("INSERT INTO Repetitions VALUES(3, 30)")
            }
            db.run("RELEASE outer_frame")
            assertFalse("Outer RELEASE must free the native writer transaction", raw.inTransaction())
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { reopened ->
                val reader = AndroidDatabase(reopened)
                assertEquals(if (rollback) 1L else 2L, reader.queryLong("SELECT count(*) FROM Repetitions"))
                assertEquals(30L, reader.queryLong("SELECT sum(value) FROM Repetitions"))
                assertEquals("ok", reader.queryString("PRAGMA integrity_check"))
                assertEquals(30L, reader.queryLong("PRAGMA user_version"))
            }
        } finally {
            // Same-thread transaction cleanup is safe even when the assertion fails.
            if (raw.inTransaction()) raw.endTransaction()
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    private fun checkTwoWriters(mode: String) {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.cacheDir.exists() || context.cacheDir.mkdirs())
        val file = File(context.cacheDir, "writer-contention-${UUID.randomUUID()}.db")
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val db = AndroidDatabase(raw, EffectiveReadRevision("disposable-$mode"))
        db.run("PRAGMA journal_mode=$mode")
        db.run("PRAGMA user_version=30")
        db.run("CREATE TABLE Repetitions(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
        assertEquals(mode, db.queryString("PRAGMA journal_mode"))
        val firstOwnsWriter = CountDownLatch(1)
        val secondStarting = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val first = Thread({
            try {
                db.beginTransaction()
                db.run("INSERT INTO Repetitions VALUES(1, 10)")
                firstOwnsWriter.countDown()
                check(secondStarting.await(2, TimeUnit.SECONDS))
                // Give the second writer time to enter the blocking SQLite begin.
                Thread.sleep(100)
                db.setTransactionSuccessful()
                db.endTransaction()
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally { done.countDown() }
        }, "qa-first-writer").apply { isDaemon = true }
        val second = Thread({
            try {
                check(firstOwnsWriter.await(2, TimeUnit.SECONDS))
                secondStarting.countDown()
                db.beginTransaction()
                db.run("INSERT INTO Repetitions VALUES(2, 20)")
                db.setTransactionSuccessful()
                db.endTransaction()
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally { done.countDown() }
        }, "qa-second-writer").apply { isDaemon = true }
        first.start()
        second.start()
        val completed = done.await(4, TimeUnit.SECONDS)
        try {
            val blocked = listOf(first, second).joinToString("\n") { thread ->
                "${thread.name} ${thread.state}\n${thread.stackTrace.joinToString("\n")}" 
            }
            assertTrue("$mode writers failed to complete; $blocked", completed)
            failure.get()?.let { throw AssertionError("$mode writer failed", it) }
            assertEquals(2L, db.queryLong("SELECT count(*) FROM Repetitions"))
            assertEquals(30L, db.queryLong("SELECT sum(value) FROM Repetitions"))
            assertEquals("ok", db.queryString("PRAGMA integrity_check"))
            assertEquals(30L, db.queryLong("PRAGMA user_version"))
        } finally {
            if (completed) {
                db.close()
                SQLiteDatabase.deleteDatabase(file)
            }
        }
    }
}

