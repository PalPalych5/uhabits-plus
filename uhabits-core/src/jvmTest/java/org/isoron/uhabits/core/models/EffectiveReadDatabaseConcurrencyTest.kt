package org.isoron.uhabits.core.models

import dev.mokkery.answering.calls
import dev.mokkery.every
import dev.mokkery.mock
import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.run
import org.isoron.uhabits.core.models.projection.EffectiveReadDatabase
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectiveReadDatabaseConcurrencyTest {
    @Test fun waitingPlatformBeginDoesNotPreventCurrentWriterFromCommitting() = compareWriters(false)
    @Test fun waitingSqlBeginDoesNotPreventCurrentWriterFromCommitting() = compareWriters(true)

    private fun compareWriters(sql: Boolean) {
        val waiting = CountDownLatch(1)
        val ownerReady = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val raw = ThreadBoundDatabase(waiting)
        val db = EffectiveReadDatabase(raw, EffectiveReadRevision("disposable-test"))
        fun begin() { if (sql) db.run("BEGIN") else db.beginTransaction() }
        fun commit() {
            if (sql) db.run("COMMIT") else { db.setTransactionSuccessful(); db.endTransaction() }
        }
        thread(isDaemon = true, name = "revision-current-writer") {
            try {
                begin()
                ownerReady.countDown()
                check(allowCommit.await(3, TimeUnit.SECONDS))
                commit()
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally { completed.countDown() }
        }
        assertTrue(ownerReady.await(3, TimeUnit.SECONDS))
        thread(isDaemon = true, name = "revision-waiting-writer") {
            try { begin(); commit() }
            catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally { completed.countDown() }
        }
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS), "Second writer must reach the connection wait")
        } finally { allowCommit.countDown() }
        assertTrue(completed.await(3, TimeUnit.SECONDS), "Connection wait must not hold the revision monitor needed by its current owner")
        assertNull(failure.get())
    }

    /** Models Android SQLiteSession's thread-owned writer connection without touching any database. */
    private class ThreadBoundDatabase(private val waiting: CountDownLatch) : Database {
        private val writer = ReentrantLock()
        override fun beginTransaction() {
            if (!writer.tryLock()) { waiting.countDown(); writer.lock() }
        }
        override fun setTransactionSuccessful() = Unit
        override fun endTransaction() { writer.unlock() }
        override fun inTransaction() = writer.isHeldByCurrentThread
        override fun close() = Unit
        override fun prepareStatement(sql: String): PreparedStatement {
            val statement = mock<PreparedStatement>()
            every { statement.step() } calls {
                when (sql) {
                    "BEGIN" -> beginTransaction()
                    "COMMIT" -> endTransaction()
                }
                StepResult.DONE
            }
            every { statement.finalize() } calls { Unit }
            return statement
        }
    }
}
