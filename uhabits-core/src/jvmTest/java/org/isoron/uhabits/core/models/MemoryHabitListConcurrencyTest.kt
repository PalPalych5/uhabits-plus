package org.isoron.uhabits.core.models

import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.mokkery.matcher.any
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.isoron.uhabits.core.models.memory.MemoryHabitList
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class MemoryHabitListConcurrencyTest {
    @Test
    fun sphereSortCachesUnassignedRootsAndInvalidatesOnNextResort() {
        val parent = MemoryHabitList()
        val factory = MemoryModelFactory()
        repeat(30) { index ->
            parent.add(factory.buildHabit().apply { name = "Habit $index"; position = index })
        }
        val facade = mock<HabitOrganizationFacade>()
        every { facade.mode } returns OrganizationAuthorityMode.CONTAINER_LOCAL
        var calls = 0
        var root: String? = null
        every { facade.getRootGroupKey(any(), any()) } calls { calls++; root }
        every { facade.rootContainers() } returns emptyList()
        parent.organizationFacade = facade
        parent.primaryOrder = HabitList.Order.BY_SPHERE
        assertEquals(30, calls, "Unassigned roots must be fetched once per habit, not per comparison")
        calls = 0
        root = "reassigned-root"
        parent.resort()
        assertEquals(30, calls, "A new sort must invalidate cached organization data")
    }

    @Test
    fun detachDoesNotHoldChildMonitorWhileWaitingForParentNotification() {
        val parent = MemoryHabitList()
        val notificationEntered = CountDownLatch(1)
        val continueNotification = CountDownLatch(1)
        parent.observable.addListener {
            notificationEntered.countDown()
            check(continueNotification.await(5, TimeUnit.SECONDS))
        }
        val child = parent.getFiltered(HabitMatcher()) as MemoryHabitList
        val workerFailure = AtomicReference<Throwable?>()
        val notification = thread(isDaemon = true) {
            try { parent.observable.notifyListeners() } catch (e: Throwable) { workerFailure.set(e) }
        }
        assertTrue(notificationEntered.await(5, TimeUnit.SECONDS))
        val detach = thread(isDaemon = true) {
            try { child.detachFromParent() } catch (e: Throwable) { workerFailure.set(e) }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (detach.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, detach.state)
        } finally {
            continueNotification.countDown()
        }
        notification.join(5000)
        detach.join(5000)
        assertFalse(notification.isAlive, "Parent callback must finish while detach waits")
        assertFalse(detach.isAlive, "Detach must finish after the in-flight callback")
        assertNull(workerFailure.get(), "Concurrent detach must not invalidate an in-flight callback")
    }

    @Test
    fun detachedListStopsFollowingParentAndCanBeDetachedRepeatedly() {
        val parent = MemoryHabitList()
        val child = parent.getFiltered(HabitMatcher()) as MemoryHabitList
        child.detachFromParent()
        child.detachFromParent()
        parent.add(MemoryModelFactory().buildHabit().apply { name = "Added after detach" })
        assertEquals(1, parent.size())
        assertEquals(0, child.size())
    }
}
