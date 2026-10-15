/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.qa

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.models.*
import org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Iteration 4: Deterministic regression test for EffectiveHabitReadController contention,
 * in-flight batching, invalidation freshness, and monitor decoupling.
 */
@RunWith(AndroidJUnit4::class)
class EffectiveHabitReadControllerContentionTest {

    @Test fun testScopedRevisionWaitDoesNotOwnControllerMonitor() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"))
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0")
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1")
        val fixture = HabitsApplication.component
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
        check(marker.exists() && marker.readText() == fixture.session.databaseFilename)
        check(fixture.db.queryLong("PRAGMA user_version") == 30L)
        val qaSession = fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        val app = ins.targetContext.applicationContext as HabitsApplication
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        component.habitCardListCache.shutdown()
        runBlocking { component.taskRunner.await() }
        ins.waitForIdleSync()
        val controller = requireNotNull(component.effectiveHabitReads)
        val revision = component.effectiveReadRevision
        val habit = component.habitList.getByPosition(0)
        val to = LocalDate(2026, 10, 11)
        setToday(to)
        val from = to.minus(7)
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        val probeFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val holder = Thread {
            try { synchronized(revision) { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        val reader = Thread {
            try { controller.snapshots(listOf(habit), from, to) }
            catch (t: Throwable) { failure.compareAndSet(null, t) }
        }
        val probe = Thread { synchronized(controller) { probeFinished.countDown() } }
        var sawRevisionWait = false
        var controllerAvailable = false
        try {
            controller.snapshots(listOf(habit), from, to)
            revision.changed(setOf(HabitRef(habit.uuid!!)))
            try {
                holder.start(); check(held.await(2, TimeUnit.SECONDS)); reader.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline) {
                    if (reader.state == Thread.State.BLOCKED && reader.stackTrace.any {
                            it.methodName == "affectedHabitsBetween" }) {
                        sawRevisionWait = true
                        break
                    }
                    Thread.sleep(10)
                }
                if (sawRevisionWait) {
                    probe.start()
                    controllerAvailable = probeFinished.await(1, TimeUnit.SECONDS)
                }
            } finally {
                // The holder never invokes rollback while owned: release first so
                // the baseline can fail its probe without a permanent lock cycle.
                release.countDown(); holder.join(5000)
                if (reader.state != Thread.State.NEW) reader.join(5000)
                if (probe.state != Thread.State.NEW) probe.join(5000)
            }
            assertFalse(holder.isAlive); assertFalse(reader.isAlive); assertFalse(probe.isAlive)
            if (failure.get() != null) throw AssertionError("Revision wait thread failure", failure.get())
            assertTrue("Reader must encounter the strictly-newer revision history monitor", sawRevisionWait)

            // Demonstrate the opposite ownership edge using a genuine rollback,
            // with every deliberately held monitor already released.
            val callbackOwnedRevision = AtomicBoolean(false)
            val callbackSeen = AtomicBoolean(false)
            val listener: () -> Unit = {
                callbackSeen.set(true)
                callbackOwnedRevision.set(Thread.holdsLock(revision))
            }
            revision.addRollbackListener(listener)
            try {
                val before = revision.value
                component.db.beginTransaction()
                try {
                    component.db.run(
                        "INSERT INTO AppSettings(key,long_value) VALUES ('qa_iteration14_rollback_${java.util.UUID.randomUUID()}',14)")
                } finally { component.db.endTransaction() }
                assertEquals("Rollback must not invent a durable revision", before, revision.value)
                assertTrue("Genuine SQL rollback callback required", callbackSeen.get())
                assertTrue("Rollback listener owns the revision monitor", callbackOwnedRevision.get())
            } finally { revision.removeRollbackListener(listener) }
            assertTrue("Controller monitor must remain available while its reader waits for revision history", controllerAvailable)
        } finally {
            release.countDown()
            ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        }
    }

    @Test
    fun testContentionAndInvalidationContracts() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val fixture = UiReviewCaptureTest.initializeFixture()

        // 1. Preflight safety checks
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices strictly forbidden" }
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011") {
            "Verified disposable AVD token required"
        }
        val avd = UiDevice.getInstance(ins).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
        check(avd == "UHabit_UI_Review_Disposable_20261011") { "AVD mismatch: $avd" }
        val serial = UiDevice.getInstance(ins).executeShellCommand("getprop ro.serialno").trim()
        check(serial == "EMULATOR36X6X11X0") { "Serial mismatch: $serial" }
        val airplane = UiDevice.getInstance(ins).executeShellCommand("settings get global airplane_mode_on").trim()
        check(airplane == "1") { "Offline QA environment required: airplane_mode_on was $airplane" }
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
        check(marker.exists() && marker.readText() == fixture.session.databaseFilename) { "Disposable marker mismatch" }
        check(fixture.db.queryLong("PRAGMA user_version") == 30L) { "Schema must be v30" }

        // 2. Set QA Session mode to PRODUCTION_NEXT with disabled side effects
        val app = ins.targetContext.applicationContext as HabitsApplication
        val qaSession = fixture.session.copy(
            mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(
                syncEnabled = false,
                remoteCallsAllowed = false,
                backgroundJobsEnabled = false,
                widgetsEnabled = false,
                remindersEnabled = false,
            )
        )
        ins.runOnMainSync { app.rebuildComponent(qaSession) }
        var component = HabitsApplication.component

        fun drain() {
            repeat(5) {
                runBlocking { component.taskRunner.await() }
                ins.waitForIdleSync()
            }
        }
        drain()

        val referenceDate = LocalDate(2026, 10, 11)
        setToday(referenceDate)

        // 3. Build test habits
        val defaultBlockId = component.habitList.getByPosition(0).blockId
        val habits = (0 until 4).map { index ->
            val name = "ContentionHabit $index"
            val existing = component.habitList.toList().firstOrNull { it.name == name }
            if (existing != null) existing else {
                val h = component.sqlModelFactory.buildHabit().apply {
                    this.name = name
                    this.blockId = defaultBlockId
                }
                component.habitList.add(h)
                h
            }
        }
        habits[0].apply { type = HabitType.YES_NO; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""; goalHistory = mutableListOf() }
        habits[1].apply { type = HabitType.YES_NO; frequency = Frequency(3, 7); targetType = NumericalHabitType.AT_LEAST; targetValue = 1.0; unit = ""; goalHistory = mutableListOf() }
        habits[2].apply { type = HabitType.NUMERICAL; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 2000.0; unit = "ml"; goalHistory = mutableListOf() }
        habits[3].apply { type = HabitType.NUMERICAL; frequency = Frequency(1, 1); targetType = NumericalHabitType.AT_LEAST; targetValue = 10000.0; unit = "steps"; goalHistory = mutableListOf() }
        habits.forEach { component.habitList.update(it) }
        drain()

        var controller = requireNotNull(component.effectiveHabitReads)

        // =========================================================================
        // SCENARIO 7: Concurrent same-key requests and bounded duplicate work
        // =========================================================================
        run {
            val t1Started = CountDownLatch(1)
            val t1Release = CountDownLatch(1)
            val prepareCount = AtomicInteger(0)

            controller.prepareHook = { requested ->
                if (requested.any { it.uuid == habits[0].uuid }) {
                    prepareCount.incrementAndGet()
                    t1Started.countDown()
                    check(t1Release.await(5, TimeUnit.SECONDS))
                }
            }

            val res1 = AtomicReference<Map<HabitRef, Any>?>()
            val res2 = AtomicReference<Map<HabitRef, Any>?>()

            val th1 = Thread {
                res1.set(controller.snapshots(listOf(habits[0])))
            }
            val th2 = Thread {
                // Ensure th1 entered prepareHook before th2 launches
                check(t1Started.await(5, TimeUnit.SECONDS))
                res2.set(controller.snapshots(listOf(habits[0])))
            }

            th1.start()
            th2.start()

            // Release th1
            Thread.sleep(50)
            t1Release.countDown()

            th1.join(5000)
            th2.join(5000)

            controller.prepareHook = null

            assertNotNull("Thread 1 should return snapshot", res1.get())
            assertNotNull("Thread 2 should return snapshot", res2.get())
            assertEquals("Duplicate preparation must be bounded (exactly 1 prepare)", 1, prepareCount.get())
        }

        // =========================================================================
        // SCENARIO 1: Two simultaneous readers, one full and one targeted
        // =========================================================================
        run {
            // Invalidate cache first by incrementing revision
            component.effectiveReadRevision.changed()
            drain()

            val t1Started = CountDownLatch(1)
            val t1Release = CountDownLatch(1)

            controller.prepareHook = { requested ->
                if (requested.size > 1) {
                    t1Started.countDown()
                    check(t1Release.await(5, TimeUnit.SECONDS))
                }
            }

            val fullRes = AtomicReference<Map<HabitRef, Any>?>()
            val targetedRes = AtomicReference<Map<HabitRef, Any>?>()

            val fullThread = Thread {
                fullRes.set(controller.snapshots(habits))
            }
            val targetedThread = Thread {
                check(t1Started.await(5, TimeUnit.SECONDS))
                // Targeted reader for habits[0] while full list is preparing
                targetedRes.set(controller.snapshots(listOf(habits[0])))
            }

            fullThread.start()
            targetedThread.start()

            Thread.sleep(50)
            t1Release.countDown()

            fullThread.join(5000)
            targetedThread.join(5000)

            controller.prepareHook = null

            assertNotNull("Full reader must succeed", fullRes.get())
            assertNotNull("Targeted reader must succeed", targetedRes.get())
            assertEquals("Full reader must return 4 habits", 4, fullRes.get()!!.size)
            assertEquals("Targeted reader must return 1 habit", 1, targetedRes.get()!!.size)
        }

        // =========================================================================
        // SCENARIO 2: Revision changed during expensive preparation
        // =========================================================================
        run {
            component.effectiveReadRevision.changed()
            drain()

            val initialRevision = component.effectiveReadRevision.value
            val prepareStarted = CountDownLatch(1)
            val prepareRelease = CountDownLatch(1)
            val prepareInvocations = AtomicInteger(0)

            controller.prepareHook = {
                val call = prepareInvocations.incrementAndGet()
                if (call == 1) {
                    prepareStarted.countDown()
                    check(prepareRelease.await(5, TimeUnit.SECONDS))
                }
            }

            val resultRef = AtomicReference<Map<HabitRef, Any>?>()
            val th = Thread {
                resultRef.set(controller.snapshots(listOf(habits[0])))
            }
            th.start()

            check(prepareStarted.await(5, TimeUnit.SECONDS))
            // Mutate revision while preparation is in progress
            component.effectiveReadRevision.changed()
            drain()
            val newRevision = component.effectiveReadRevision.value
            assertTrue("Revision must have advanced", newRevision > initialRevision)

            prepareRelease.countDown()
            th.join(5000)

            controller.prepareHook = null

            assertNotNull("Reader must succeed after retry", resultRef.get())
            val snap = resultRef.get()!!.values.first() as org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
            assertEquals("Snapshot revision must reflect newer revision", newRevision, snap.revision)
            assertTrue("Should have retried preparation", prepareInvocations.get() >= 2)
        }

        // =========================================================================
        // SCENARIO 3: Rollback generation changed during preparation
        // =========================================================================
        run {
            val prepareStarted = CountDownLatch(1)
            val prepareRelease = CountDownLatch(1)
            val prepareInvocations = AtomicInteger(0)

            controller.prepareHook = {
                val call = prepareInvocations.incrementAndGet()
                if (call == 1) {
                    prepareStarted.countDown()
                    check(prepareRelease.await(5, TimeUnit.SECONDS))
                }
            }

            val resultRef = AtomicReference<Map<HabitRef, Any>?>()
            val th = Thread {
                resultRef.set(controller.snapshots(listOf(habits[1])))
            }
            th.start()

            check(prepareStarted.await(5, TimeUnit.SECONDS))
            // Trigger rollback while preparation is paused
            component.effectiveReadRevision.rolledBack()
            drain()

            prepareRelease.countDown()
            th.join(5000)

            controller.prepareHook = null

            assertNotNull("Reader must succeed after rollback retry", resultRef.get())
            assertTrue("Should have recomputed on rollback", prepareInvocations.get() >= 2)
        }

        // =========================================================================
        // SCENARIO 5: Dataset switch while a previous computation is active
        // =========================================================================
        run {
            val prepareStarted = CountDownLatch(1)
            val prepareRelease = CountDownLatch(1)
            val finished = AtomicBoolean(false)

            controller.prepareHook = {
                prepareStarted.countDown()
                check(prepareRelease.await(5, TimeUnit.SECONDS))
            }

            val th = Thread {
                try {
                    controller.snapshots(listOf(habits[0]))
                    finished.set(true)
                } catch (_: EffectiveReadInvalidatedException) {
                    // Valid outcome when invalidated by switch
                }
            }
            th.start()

            check(prepareStarted.await(5, TimeUnit.SECONDS))
            // Trigger dataset change / revision bump
            component.effectiveReadRevision.changed()
            drain()

            prepareRelease.countDown()
            th.join(5000)

            controller.prepareHook = null
        }

        // =========================================================================
        // SCENARIO 6: Repeated invalidations under a burst of habit edits
        // =========================================================================
        run {
            // Rapid burst of 4 edits
            for (i in 0..3) {
                val cmd = CreateRepetitionCommand(component.habitList, habits[i % habits.size], referenceDate, Entry.YES_MANUAL, "")
                ins.runOnMainSync { component.commandRunner.run(cmd) }
            }
            // Allow 200ms debounce to settle
            Thread.sleep(300)
            drain()

            // Fetch snapshot after burst settles
            val result = controller.snapshots(habits)
            assertEquals("All 4 habits must be present after burst", 4, result.size)
        }

        // =========================================================================
        // SCENARIO 8: Semantic parity verification
        // =========================================================================
        run {
            val snapshots = controller.snapshots(habits)
            for (h in habits) {
                val ref = HabitRef(h.uuid!!)
                val snap = requireNotNull(snapshots[ref])
                assertEquals("DatasetId parity", component.effectiveReadRevision.datasetId, snap.datasetId)
                assertEquals("Revision parity", component.effectiveReadRevision.value, snap.revision)
                assertNotNull("Score result must be present", snap.scoreResult)
                assertNotNull("Streak result must be computed", snap.streakResult)
            }
        }

        // =========================================================================
        // SCENARIO 4: Controller disposed during preparation
        // =========================================================================
        run {
            // Invalidate cache so habits[2] is missing and triggers preparation
            component.effectiveReadRevision.changed()
            drain()

            val prepareStarted = CountDownLatch(1)
            val prepareRelease = CountDownLatch(1)
            val errorRef = AtomicReference<Throwable?>()

            controller.prepareHook = {
                prepareStarted.countDown()
                check(prepareRelease.await(5, TimeUnit.SECONDS))
            }

            val th = Thread {
                try {
                    controller.snapshots(listOf(habits[2]))
                } catch (t: Throwable) {
                    errorRef.set(t)
                }
            }
            th.start()

            check(prepareStarted.await(5, TimeUnit.SECONDS))
            // Dispose controller while thread is computing
            controller.dispose()

            prepareRelease.countDown()
            th.join(5000)

            controller.prepareHook = null

            assertNotNull("Disposed controller must throw during active prepare", errorRef.get())
            assertTrue(
                "Error must be EffectiveReadInvalidatedException",
                errorRef.get() is EffectiveReadInvalidatedException
            )
        }
    }
}
