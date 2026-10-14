package org.isoron.uhabits.core.models

import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EffectiveReadRevisionLockOrderTest {
    @Test fun versionObservationDoesNotWaitForRollbackListener() {
        val revision = EffectiveReadRevision("lock-order-fixture")
        val listenerEntered = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        val observed = AtomicReference<Long?>()
        revision.addRollbackListener {
            listenerEntered.countDown()
            check(releaseListener.await(5, TimeUnit.SECONDS))
        }
        val rollback = Thread({ revision.rolledBack() }, "revision-rollback")
        val reader = Thread({
            try { observed.set(revision.observedValue) } finally { readerDone.countDown() }
        }, "version-reader")
        try {
            rollback.start()
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS))
            reader.start()
            assertTrue("Version read must not take the listener monitor (controller/rollback inversion)",
                readerDone.await(250, TimeUnit.MILLISECONDS))
        } finally {
            releaseListener.countDown()
            rollback.join(5000)
            reader.join(5000)
        }
        assertFalse(rollback.isAlive)
        assertFalse(reader.isAlive)
        assertEquals(0L, observed.get())
    }
    @Test fun versionIsVisibleBeforeListenerCompletionWhileValueRetainsBarrier() {
        val revision = EffectiveReadRevision("version-visibility-fixture")
        val listenerEntered = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val versionDone = CountDownLatch(1)
        val valueDone = CountDownLatch(1)
        val observed = AtomicReference<Long?>()
        revision.addListener {
            listenerEntered.countDown()
            check(releaseListener.await(5, TimeUnit.SECONDS))
        }
        val writer = Thread({ revision.changed() }, "revision-writer")
        val versionReader = Thread({
            try { observed.set(revision.observedValue) } finally { versionDone.countDown() }
        }, "version-reader")
        val valueReader = Thread({ try { revision.value } finally { valueDone.countDown() } }, "barrier-reader")
        try {
            writer.start()
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS))
            versionReader.start()
            valueReader.start()
            assertTrue(versionDone.await(250, TimeUnit.MILLISECONDS))
            assertEquals(1L, observed.get())
            assertFalse("Existing value getter retains listener-completion barrier", valueDone.await(50, TimeUnit.MILLISECONDS))
        } finally {
            releaseListener.countDown()
            writer.join(5000)
            versionReader.join(5000)
            valueReader.join(5000)
        }
        assertFalse(writer.isAlive)
        assertFalse(versionReader.isAlive)
        assertFalse(valueReader.isAlive)
        assertEquals(1L, revision.value)
    }

}
