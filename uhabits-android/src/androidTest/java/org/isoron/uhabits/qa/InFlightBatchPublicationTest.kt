/* Copyright (C) 2026 Álinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.qa

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.HabitEditTrace
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetCapabilities
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException
import org.isoron.uhabits.core.models.projection.EffectiveReadRevision
import org.isoron.uhabits.focus.EffectiveHabitReadController
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Publication barriers are outside the monitor; timeout windows assert liveness, never simulate work. */
@RunWith(AndroidJUnit4::class)
class InFlightBatchPublicationTest {
    private var originalSession: DatasetSession? = null

    @Before fun establishOfflineProductionNext() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"))
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0")
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1")
        val original = HabitsApplication.component
        check(original.db.queryLong("PRAGMA user_version") == 30L)
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${original.session.datasetId}")
        check(marker.exists() && marker.readText() == original.session.databaseFilename)
        check(original.habitList.size() >= 2)
        originalSession = original.session
        val session = original.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false))
        val app = ins.targetContext.applicationContext as HabitsApplication
        ins.runOnMainSync { app.rebuildComponent(session) }
        HabitsApplication.component.habitCardListCache.shutdown()
    }

    @After fun restoreSession() {
        val session = originalSession ?: return
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as HabitsApplication
        ins.runOnMainSync { app.rebuildComponent(session) }
    }

    @Test fun boundedRetryExhaustionDoesNotPublishInvalidatedResults() {
        val component = HabitsApplication.component
        val controller = requireNotNull(component.effectiveHabitReads)
        val habit = component.habitList.getByPosition(0)
        component.effectiveReadRevision.changed()
        val preparations = AtomicInteger()
        var invalidated: Throwable? = null
        var staleResult: EffectiveHabitReadSnapshot? = null
        controller.prepareHook = {
            assertFalse(Thread.holdsLock(controller))
            preparations.incrementAndGet()
            // Every attempt becomes stale before the provider can return.
            component.effectiveReadRevision.changed()
        }
        try {
            try { staleResult = controller.snapshot(habit) }
            catch (t: Throwable) { invalidated = t }
        } finally { controller.prepareHook = null }
        assertNull("No invalidated attempt may return a snapshot", staleResult)
        assertTrue("Retry exhaustion retains typed invalidation", invalidated is EffectiveReadInvalidatedException)
        assertEquals("Retry budget must remain exactly three attempts", 3, preparations.get())
        val recoveryPreparations = AtomicInteger()
        controller.prepareHook = { recoveryPreparations.incrementAndGet() }
        try {
            val recovered = controller.snapshot(habit)
            assertEquals(component.effectiveReadRevision.value, recovered.revision)
            assertEquals(component.session.datasetId, recovered.datasetId)
            assertEquals("Exhausted batches cannot leak or cause duplicate recovery preparation", 1, recoveryPreparations.get())
            assertEquals(recovered, controller.snapshot(habit))
            assertEquals("Second stable request reuses published cache", 1, recoveryPreparations.get())
        } finally { controller.prepareHook = null }
    }

    /** Logical component replacement over the guarded QA database, not physical dataset activation. */
    @Test fun logicalDatasetReplacementInvalidatesOldWaiters() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val component = HabitsApplication.component
        val oldController = requireNotNull(component.effectiveHabitReads)
        val habit = component.habitList.getByPosition(0)
        component.effectiveReadRevision.changed()
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val awaiting = CountDownLatch(1)
        val waiterDone = CountDownLatch(1)
        val ownerError = AtomicReference<Throwable?>()
        val waiterError = AtomicReference<Throwable?>()
        oldController.prepareHook = {
            preparing.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        oldController.batchAwaitHook = { assertFalse(Thread.holdsLock(oldController)); awaiting.countDown() }
        val owner = Thread({
            try { oldController.snapshot(habit); ownerError.set(AssertionError("Old dataset returned success")) }
            catch (t: Throwable) { ownerError.set(t) }
        }, "old-dataset-owner")
        val waiter = Thread({
            try { oldController.snapshot(habit); waiterError.set(AssertionError("Old dataset returned success")) }
            catch (t: Throwable) { waiterError.set(t) }
            finally { waiterDone.countDown() }
        }, "old-dataset-waiter")
        var replacement: EffectiveHabitReadController? = null
        try {
            owner.start()
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            waiter.start()
            assertTrue(awaiting.await(5, TimeUnit.SECONDS))
            oldController.dispose()
            assertTrue("Old waiter wakes before provider is released", waiterDone.await(5, TimeUnit.SECONDS))
            assertTrue(waiterError.get() is EffectiveReadInvalidatedException)
            val newRevision = EffectiveReadRevision("qa-new-dataset-id")
            replacement = EffectiveHabitReadController(ins.targetContext, component.db, component.preferences,
                newRevision, component.habitCardListCache, component.habitList)
            val fresh = replacement.snapshot(habit)
            assertEquals("qa-new-dataset-id", fresh.datasetId)
            assertEquals(newRevision.value, fresh.revision)
            assertEquals("New component cannot retain old dataset identity", fresh.datasetId,
                replacement.snapshot(habit).datasetId)
        } finally {
            release.countDown()
            owner.join(10000)
            waiter.join(10000)
            oldController.prepareHook = null
            oldController.batchAwaitHook = null
            replacement?.dispose()
        }
        assertFalse(owner.isAlive)
        assertFalse(waiter.isAlive)
        assertTrue(ownerError.get() is EffectiveReadInvalidatedException)
    }

    @Test fun partialOverlapPublishesOwnedSubsetBeforeWaiting() {
        val component = HabitsApplication.component
        val controller = requireNotNull(component.effectiveHabitReads)
        val habits = listOf(component.habitList.getByPosition(0), component.habitList.getByPosition(1))
        fun exercise(interruptOverlap: Boolean) {
            component.effectiveReadRevision.changed()
            val ownerPreparing = CountDownLatch(1)
            val releaseOwner = CountDownLatch(1)
            val overlapAwaiting = CountDownLatch(1)
            val overlapDone = CountDownLatch(1)
            val thirdDone = CountDownLatch(1)
            val firstPreparations = AtomicInteger()
            val secondPreparations = AtomicInteger()
            val ownerError = AtomicReference<Throwable?>()
            val overlapError = AtomicReference<Throwable?>()
            val thirdError = AtomicReference<Throwable?>()
            val overlapResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
            val thirdResult = AtomicReference<EffectiveHabitReadSnapshot?>()
            controller.prepareHook = { requested ->
                assertFalse(Thread.holdsLock(controller))
                if (requested.any { it.uuid == habits[0].uuid }) {
                    firstPreparations.incrementAndGet()
                    ownerPreparing.countDown()
                    check(releaseOwner.await(10, TimeUnit.SECONDS))
                }
                if (requested.any { it.uuid == habits[1].uuid }) secondPreparations.incrementAndGet()
            }
            controller.batchAwaitHook = {
                assertFalse(Thread.holdsLock(controller))
                if (Thread.currentThread().name == "partial-overlap") overlapAwaiting.countDown()
            }
            val owner = Thread({
                try { controller.snapshot(habits[0]) } catch (t: Throwable) { ownerError.set(t) }
            }, "partial-owner")
            val overlap = Thread({
                try { overlapResult.set(controller.snapshots(habits)) } catch (t: Throwable) { overlapError.set(t) }
                finally { overlapDone.countDown() }
            }, "partial-overlap")
            val third = Thread({
                try { thirdResult.set(controller.snapshot(habits[1])) } catch (t: Throwable) { thirdError.set(t) }
                finally { thirdDone.countDown() }
            }, "partial-third")
            try {
                owner.start()
                assertTrue(ownerPreparing.await(5, TimeUnit.SECONDS))
                overlap.start()
                assertTrue("Overlap computes its missing subset before awaiting owner", overlapAwaiting.await(5, TimeUnit.SECONDS))
                if (interruptOverlap) {
                    overlap.interrupt()
                    assertTrue("Interrupted dependency wait terminates", overlapDone.await(5, TimeUnit.SECONDS))
                    assertTrue(overlapError.get() is InterruptedException)
                }
                third.start()
                assertTrue("Owned subset must be independently available while original owner remains blocked",
                    thirdDone.await(5, TimeUnit.SECONDS))
                assertNull(thirdError.get())
                assertNotNull(thirdResult.get())
                assertEquals(component.effectiveReadRevision.value, thirdResult.get()!!.revision)
                assertEquals(1, firstPreparations.get())
                assertEquals("No duplicate preparation for owned subset", 1, secondPreparations.get())
            } finally {
                releaseOwner.countDown()
                owner.join(10000)
                overlap.join(10000)
                third.join(10000)
                controller.prepareHook = null
                controller.batchAwaitHook = null
            }
            assertFalse(owner.isAlive)
            assertFalse(overlap.isAlive)
            assertFalse(third.isAlive)
            assertNull(ownerError.get())
            if (!interruptOverlap) {
                assertNull(overlapError.get())
                assertEquals(2, requireNotNull(overlapResult.get()).size)
                assertEquals(thirdResult.get(), overlapResult.get()!![HabitRef(requireNotNull(habits[1].uuid))])
            }
            assertEquals(1, firstPreparations.get())
            assertEquals(1, secondPreparations.get())
        }
        exercise(interruptOverlap = false)
        exercise(interruptOverlap = true)
    }

    @Test fun captureJoinedReaderTimings() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val component = HabitsApplication.component
        val controller = requireNotNull(component.effectiveHabitReads)
        val habit = component.habitList.getByPosition(0)
        val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        val samples = JSONArray()
        val errors = mutableListOf<String>()
        val label = InstrumentationRegistry.getArguments().getString("buildLabel") ?: "unlabeled"
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        controller.requestMetricsObserver = { m -> events.add(JSONObject()
            .put("type", "request").put("thread", m.thread).put("totalMs", m.totalMs)
            .put("monitorWaitMs", m.monitorWaitMs).put("monitorHeldMs", m.monitorHeldMs)
            .put("latchMs", m.latchMs).put("latchCount", m.latchCount)
            .put("prepareMs", m.prepareMs).put("prepareCount", m.prepareCount)
            .put("retries", m.retries).put("outcome", m.outcome)
            .put("validatedPublicationMs", m.validatedPublicationMs ?: JSONObject.NULL)
            .put("cachePublicationDurationMs", m.cachePublicationDurationMs)
            .put("joinedHabits", m.joinedHabits).put("createdBatches", m.createdBatches)
            .put("maxConcurrentPreparations", m.maxConcurrentPreparations).put("onMain", m.onMain)
            .put("generation", m.generation ?: JSONObject.NULL).put("batch", m.batch ?: JSONObject.NULL)) }
        controller.snapshotMetricsObserver = { m -> events.add(JSONObject()
            .put("type", "provider").put("thread", Thread.currentThread().name)
            .put("totalPrepareMs", m.totalPrepareDurationMs).put("habitCount", m.habitCount)
            .put("snapshotInitMs", m.snapshotInitDurationMs).put("scoresAndStreaksMs", m.scoresAndStreaksDurationMs)
            .put("copiedOriginalEntries", m.copiedOriginalEntriesCount).put("processedDays", m.processedDays)) }
        try {
            repeat(4) { sample ->
                val held = sample == 3
                component.effectiveReadRevision.changed()
                val preparing = CountDownLatch(1)
                val releasePreparation = CountDownLatch(1)
                val awaiting = CountDownLatch(1)
                val publishing = CountDownLatch(1)
                val releasePublication = CountDownLatch(1)
                val joinerDone = CountDownLatch(1)
                val count = AtomicInteger()
                val ownerError = AtomicReference<Throwable?>()
                val joinerError = AtomicReference<Throwable?>()
                val ownerResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
                val joinerResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
                fun trace(role: String) = HabitEditTrace((sample * 2 + if (role == "owner") 1 else 2).toLong()) { t, stage, revision, generation, batch ->
                    events.add(JSONObject().put("type", "operation").put("sample", sample).put("role", role)
                        .put("ordinal", t.ordinal).put("stage", stage).put("atNs", System.nanoTime())
                        .put("revision", revision ?: JSONObject.NULL).put("generation", generation ?: JSONObject.NULL)
                        .put("batch", batch ?: JSONObject.NULL))
                }
                controller.prepareHook = {
                    assertFalse(Thread.holdsLock(controller))
                    count.incrementAndGet()
                    preparing.countDown()
                    check(releasePreparation.await(10, TimeUnit.SECONDS))
                }
                controller.batchAwaitHook = { assertFalse(Thread.holdsLock(controller)); awaiting.countDown() }
                controller.beforePublicationHook = if (held) ({
                    publishing.countDown()
                    check(releasePublication.await(10, TimeUnit.SECONDS))
                }) else null
                val today = getToday()
                val owner = Thread({
                    try { ownerResult.set(controller.snapshots(listOf(habit), today.minus(365), today,
                        listOf(trace("owner")), sample.toLong(), sample.toLong())) }
                    catch (t: Throwable) { ownerError.set(t) }
                }, "timing-$sample-owner")
                val joiner = Thread({
                    try { joinerResult.set(controller.snapshots(listOf(habit), today.minus(365), today,
                        listOf(trace("joiner")), sample.toLong(), sample.toLong())) }
                    catch (t: Throwable) { joinerError.set(t) }
                    finally { joinerDone.countDown() }
                }, "timing-$sample-joiner")
                var completedWhileHeld = false
                try {
                    owner.start()
                    check(preparing.await(5, TimeUnit.SECONDS))
                    joiner.start()
                    check(awaiting.await(5, TimeUnit.SECONDS))
                    releasePreparation.countDown()
                    if (held) {
                        check(publishing.await(5, TimeUnit.SECONDS))
                        completedWhileHeld = joinerDone.await(250, TimeUnit.MILLISECONDS)
                    }
                } finally {
                    releasePreparation.countDown()
                    releasePublication.countDown()
                    owner.join(10000)
                    joiner.join(10000)
                    controller.prepareHook = null
                    controller.batchAwaitHook = null
                    controller.beforePublicationHook = null
                }
                val parity = ownerResult.get() != null && ownerResult.get() == joinerResult.get() &&
                    joinerResult.get()!!.values.single().revision == component.effectiveReadRevision.value
                val record = JSONObject().put("sample", sample).put("kind", if (held) "held-publication-diagnostic" else "natural-contention")
                    .put("providerInvocations", count.get()).put("ownerError", ownerError.get()?.toString() ?: JSONObject.NULL)
                    .put("joinerError", joinerError.get()?.toString() ?: JSONObject.NULL).put("parity", parity)
                    .put("joinerCompletedWhilePublicationHeld", completedWhileHeld)
                    .put("ownerAlive", owner.isAlive).put("joinerAlive", joiner.isAlive)
                samples.put(record)
                if (!parity || count.get() != 1 || owner.isAlive || joiner.isAlive || completedWhileHeld) errors.add(record.toString())
            }
        } finally {
            controller.requestMetricsObserver = null
            controller.snapshotMetricsObserver = null
            val directory = File(requireNotNull(ins.targetContext.getExternalFilesDir(null)), "inflight-publication").apply { mkdirs() }
            val eventArray = JSONArray()
            synchronized(events) { events.forEach { eventArray.put(it) } }
            File(directory, "$label-timings.json").writeText(JSONObject().put("buildLabel", label)
                .put("datasetId", component.session.datasetId).put("schema", component.db.queryLong("PRAGMA user_version"))
                .put("journalMode", component.db.queryString("PRAGMA journal_mode")).put("samples", samples)
                .put("events", eventArray).put("heldWindowMs", 250).put("heldWindowIsDiagnostic", true).toString(2))
        }
        assertTrue("All readers must terminate with current published parity; ${errors.joinToString()}", errors.isEmpty())
    }

    @Test fun invalidationFailureAndOverlapContracts() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"))
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0")
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1")
        val component = HabitsApplication.component
        check(component.session.mode == DatasetMode.PRODUCTION_NEXT)
        check(!component.session.capabilities.remoteCallsAllowed && !component.session.capabilities.syncEnabled)
        check(component.db.queryLong("PRAGMA user_version") == 30L)
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${component.session.datasetId}")
        check(marker.exists() && marker.readText() == component.session.databaseFilename)
        val controller = requireNotNull(component.effectiveHabitReads)
        val habits = listOf(component.habitList.getByPosition(0), component.habitList.getByPosition(1))

        fun pair(fullOwner: Boolean = false, mutate: (() -> Unit)? = null, failure: Throwable? = null, dispose: Boolean = false) {
            component.effectiveReadRevision.changed()
            val preparing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val awaiting = CountDownLatch(1)
            val joinerDone = CountDownLatch(1)
            val count = AtomicInteger()
            val ownerError = AtomicReference<Throwable?>()
            val joinerError = AtomicReference<Throwable?>()
            val ownerResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
            val joinerResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
            controller.prepareHook = {
                assertFalse(Thread.holdsLock(controller))
                if (count.incrementAndGet() == 1) {
                    preparing.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    if (failure != null) throw failure
                }
            }
            controller.batchAwaitHook = { assertFalse(Thread.holdsLock(controller)); awaiting.countDown() }
            val owner = Thread({
                try { ownerResult.set(controller.snapshots(if (fullOwner) habits else habits.take(1))) }
                catch (t: Throwable) { ownerError.set(t) }
            }, "contract-owner")
            val joiner = Thread({
                try { joinerResult.set(controller.snapshots(habits.take(1))) }
                catch (t: Throwable) { joinerError.set(t) }
                finally { joinerDone.countDown() }
            }, "contract-joiner")
            try {
                owner.start()
                assertTrue(preparing.await(5, TimeUnit.SECONDS))
                joiner.start()
                assertTrue(awaiting.await(5, TimeUnit.SECONDS))
                mutate?.invoke()
                if (dispose) {
                    controller.dispose()
                    assertTrue("Dispose wakes joiner while provider remains gated", joinerDone.await(5, TimeUnit.SECONDS))
                    assertTrue(joinerError.get() is EffectiveReadInvalidatedException)
                }
            } finally {
                release.countDown()
                owner.join(10000)
                joiner.join(10000)
                controller.prepareHook = null
                controller.batchAwaitHook = null
            }
            assertFalse("Owner leaked", owner.isAlive)
            assertFalse("Joiner leaked", joiner.isAlive)
            when {
                dispose -> assertTrue(ownerError.get() is EffectiveReadInvalidatedException)
                failure != null -> {
                    assertSame("Owner error retained", failure, ownerError.get())
                    assertSame("Waiter receives original provider failure", failure, joinerError.get())
                    assertEquals(1, count.get())
                }
                else -> {
                    assertNull("Owner failed", ownerError.get())
                    assertNull("Joiner failed", joinerError.get())
                    val ownerSnapshots = requireNotNull(ownerResult.get())
                    val joinerSnapshot = requireNotNull(joinerResult.get()).values.single()
                    assertEquals(if (fullOwner) 2 else 1, ownerSnapshots.size)
                    assertEquals(ownerSnapshots[HabitRef(requireNotNull(habits[0].uuid))], joinerSnapshot)
                    assertEquals(component.effectiveReadRevision.value, joinerSnapshot.revision)
                    assertEquals(component.session.datasetId, joinerSnapshot.datasetId)
                    assertEquals("One preparation per distinct validated context", if (mutate == null) 1 else 2, count.get())
                }
            }
        }
        pair() // Same-key deduplication, with no publication gate.
        pair(fullOwner = true) // Targeted reader joins a superset batch.
        pair(mutate = { component.effectiveReadRevision.changed() })
        pair(mutate = { component.effectiveReadRevision.rolledBack() })
        pair(mutate = { repeat(4) { component.effectiveReadRevision.changed() } })
        val originalTimeZone = TimeZone.getDefault()
        try {
            pair(mutate = { TimeZone.setDefault(TimeZone.getTimeZone(if (originalTimeZone.id == "UTC") "Asia/Tokyo" else "UTC")) })
        } finally { TimeZone.setDefault(originalTimeZone) }
        pair(failure = IllegalStateException("intentional provider failure"))
        // Dispose last: it intentionally terminates this component's revision listeners.
        pair(dispose = true)
    }

    @Test fun successfulOwnerDoesNotWakeJoinerBeforePublication() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(ins)
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"))
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        check(device.executeShellCommand("getprop ro.serialno").trim() == "EMULATOR36X6X11X0")
        check(device.executeShellCommand("settings get global airplane_mode_on").trim() == "1")
        val component = HabitsApplication.component
        check(component.session.mode == DatasetMode.PRODUCTION_NEXT)
        check(!component.session.capabilities.remoteCallsAllowed && !component.session.capabilities.syncEnabled)
        check(component.db.queryLong("PRAGMA user_version") == 30L)
        val marker = File(ins.targetContext.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${component.session.datasetId}")
        check(marker.exists() && marker.readText() == component.session.databaseFilename)
        val controller = requireNotNull(component.effectiveHabitReads)
        val habit = component.habitList.getByPosition(0)
        component.effectiveReadRevision.changed()
        val preparing = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val awaiting = CountDownLatch(1)
        val publishing = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val joinerDone = CountDownLatch(1)
        val preparations = AtomicInteger()
        val ownerError = AtomicReference<Throwable?>()
        val joinerError = AtomicReference<Throwable?>()
        val ownerResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
        val joinerResult = AtomicReference<Map<HabitRef, EffectiveHabitReadSnapshot>?>()
        controller.prepareHook = {
            assertFalse("Provider preparation must not hold controller monitor", Thread.holdsLock(controller))
            preparations.incrementAndGet()
            preparing.countDown()
            check(releasePreparation.await(10, TimeUnit.SECONDS))
        }
        controller.batchAwaitHook = {
            assertFalse("Latch await must not hold controller monitor", Thread.holdsLock(controller))
            awaiting.countDown()
        }
        controller.beforePublicationHook = {
            assertFalse("Publication test barrier must not hold controller monitor", Thread.holdsLock(controller))
            publishing.countDown()
            check(releasePublication.await(10, TimeUnit.SECONDS))
        }
        val owner = Thread({
            try { ownerResult.set(controller.snapshots(listOf(habit))) }
            catch (t: Throwable) { ownerError.set(t) }
        }, "publication-owner")
        val joiner = Thread({
            try { joinerResult.set(controller.snapshots(listOf(habit))) }
            catch (t: Throwable) { joinerError.set(t) }
            finally { joinerDone.countDown() }
        }, "publication-joiner")
        try {
            owner.start()
            assertTrue("Owner reached provider", preparing.await(5, TimeUnit.SECONDS))
            joiner.start()
            assertTrue("Reader joined owner's batch", awaiting.await(5, TimeUnit.SECONDS))
            releasePreparation.countDown()
            assertTrue("Owner reached held publication boundary", publishing.await(5, TimeUnit.SECONDS))
            assertFalse("Successful preparation must not release waiter before validated publication; error=${joinerError.get()}",
                joinerDone.await(250, TimeUnit.MILLISECONDS))
        } finally {
            releasePreparation.countDown()
            releasePublication.countDown()
            owner.join(10000)
            joiner.join(10000)
            controller.prepareHook = null
            controller.batchAwaitHook = null
            controller.beforePublicationHook = null
        }
        assertFalse("Owner leaked", owner.isAlive)
        assertFalse("Joiner leaked", joiner.isAlive)
        assertNull("Owner failed", ownerError.get())
        assertNull("Joiner failed", joinerError.get())
        assertEquals("Exactly one expensive preparation", 1, preparations.get())
        assertEquals(ownerResult.get(), joinerResult.get())
        assertEquals(component.effectiveReadRevision.value, requireNotNull(joinerResult.get()).values.single().revision)
    }
}
