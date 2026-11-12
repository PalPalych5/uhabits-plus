package org.isoron.uhabits.activities.habits.show.timer

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.*
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.BaseAndroidJVMTest
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.CoroutineTaskRunner
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.timer.PomodoroPhase
import org.isoron.uhabits.core.timer.TimerMode
import org.isoron.uhabits.core.timer.TimerSessionEngine
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.memory.MemoryFocusCaptureStore
import org.isoron.uhabits.core.focus.runtime.FocusCaptureStore
import org.isoron.uhabits.core.focus.runtime.FocusCaptureTarget
import org.isoron.uhabits.core.userTasks.TaskId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TimerPersistenceSafetyTest : BaseAndroidJVMTest() {

    private lateinit var fakeContext: FakeContext
    private lateinit var db: Database
    private lateinit var modelFactory: SQLModelFactory
    private lateinit var habitList: HabitList
    private lateinit var appPreferences: Preferences
    private lateinit var fakeAlarmScheduler: FakePomodoroAlarmScheduler
    private lateinit var fakeCompletionNotifier: FakePomodoroCompletionNotifier
    private lateinit var testPrefs: TestSharedPreferences
    private lateinit var synchronousCommandRunner: CommandRunner
    private var currentTimeMillis: Long = 1_000_000L

    private class FakeContext : ContextWrapper(null) {
        override fun stopService(name: Intent?): Boolean = true
        override fun startForegroundService(service: Intent?): android.content.ComponentName? = null
        override fun startService(service: Intent?): android.content.ComponentName? = null
    }

    private class FakePomodoroAlarmScheduler(context: Context) : PomodoroAlarmScheduler(context) {
        var scheduledTrigger: Long? = null
        var scheduledRevision: Long? = null
        override fun schedule(triggerAtMillis: Long, revision: Long) {
            scheduledTrigger = triggerAtMillis
            scheduledRevision = revision
        }
        override fun cancel() {}
        override fun canScheduleExactAlarms(): Boolean = true
    }

    private class FakePomodoroCompletionNotifier(
        context: Context,
        habitList: HabitList,
        alarmScheduler: PomodoroAlarmScheduler
    ) : PomodoroCompletionNotifier(context, habitList, alarmScheduler) {
        var shownEvent: PomodoroCompletionEvent? = null
        override fun canPostCompletion(): Boolean = true
        override fun isCompletionVisible(): Boolean = false
        override fun showCompletion(event: PomodoroCompletionEvent) {
            shownEvent = event
        }
        override fun cancelCompletion() {}
    }

    private class ControllableTaskRunner : TaskRunner {
        val tasks = mutableListOf<Task>()
        override fun addListener(listener: TaskRunner.Listener) {}
        override fun removeListener(listener: TaskRunner.Listener) {}
        override fun publishProgress(task: Task, progress: Int) {}
        override val activeTaskCount: Int get() = tasks.size
        override fun execute(task: Task) {
            tasks.add(task)
        }
        override suspend fun await() {}
        fun executeNext(shouldFail: Boolean = false, exception: Throwable = RuntimeException("Injected DB failure")) {
            if (tasks.isEmpty()) return
            val task = tasks.removeAt(0)
            if (shouldFail) {
                task.onCompletion(exception)
            } else {
                runBlocking {
                    task.doInBackground()
                }
                task.onPostExecute()
                task.onCompletion(null)
            }
        }
    }

    private class TestSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()
        var commitShouldFailFilter: ((Map<String, Any?>) -> Boolean)? = null

        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String?, defValue: String?): String? =
            map[key]?.let { it as? String } ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            map[key]?.let { it as? Set<String> } ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (map[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (map[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (map[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = TestEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class TestEditor(private val prefs: TestSharedPreferences) : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) modifications[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (prefs.commitShouldFailFilter?.invoke(modifications) == true) {
                return false
            }
            apply()
            return true
        }

        override fun apply() {
            if (clear) prefs.map.clear()
            for ((key, value) in modifications) {
                if (value == null) prefs.map.remove(key)
                else prefs.map[key] = value
            }
            modifications.clear()
            clear = false
        }
    }

    private suspend fun createTestDatabase(): Database {
        val memoryDb = JavaDatabaseOpener().open(":memory:")
        memoryDb.setVersion(8)

        var migrationsDir = File("../uhabits-core/assets/main/migrations")
        if (!migrationsDir.exists()) {
            migrationsDir = File("uhabits-core/assets/main/migrations")
        }
        if (!migrationsDir.exists()) {
            System.getenv("UHABIT_MIGRATIONS_DIR")?.let { migrationsDir = File(it) }
        }

        memoryDb.migrateTo(DATABASE_VERSION) { v: Int ->
            val file = File(migrationsDir, "%02d.sql".format(v))
            if (!file.exists()) {
                throw java.io.FileNotFoundException("Migration file not found: ${file.absolutePath}")
            }
            file.readText()
        }
        return memoryDb
    }

    @Before
    override fun setUp() {
        super.setUp()
        setToday(LocalDate(2026, 10, 31))
        fakeContext = FakeContext()
        runBlocking {
            db = createTestDatabase()
        }
        modelFactory = SQLModelFactory(db)
        habitList = modelFactory.buildHabitList()
        appPreferences = Preferences(MemoryStorage())
        fakeAlarmScheduler = FakePomodoroAlarmScheduler(fakeContext)
        fakeCompletionNotifier = FakePomodoroCompletionNotifier(fakeContext, habitList, fakeAlarmScheduler)
        testPrefs = TestSharedPreferences()
        val taskRunner = CoroutineTaskRunner(
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined
        )
        synchronousCommandRunner = CommandRunner(taskRunner)
    }

    @After
    fun tearDownDb() {
        if (::db.isInitialized) {
            runBlocking {
                db.close()
            }
        }
    }

    private fun createManager(
        commandRunner: CommandRunner = synchronousCommandRunner,
        timeProvider: () -> Long = { currentTimeMillis },
        captureStore: FocusCaptureStore? = null,
        session: DatasetSession = DatasetSessionFactory.production(),
        captureTargetResolver: (FocusPrimaryTargetType, String) -> Pair<String, FocusCaptureTarget>? = { kind, id ->
            Pair("Target $id", FocusCaptureTarget(kind, id))
        }
    ): TimerSessionManager {
        val engine = TimerSessionEngine(timeProvider)
        return TimerSessionManager(
            context = fakeContext,
            habitList = habitList,
            commandRunner = commandRunner,
            appPreferences = appPreferences,
            alarmScheduler = fakeAlarmScheduler,
            completionNotifier = fakeCompletionNotifier,
            engine = engine,
            captureStore = captureStore,
            session = session,
            captureTargetResolver = captureTargetResolver,
            prefs = testPrefs
        )
    }

    private fun createHabit(isNumerical: Boolean, unit: String = "min"): Habit {
        val habit = modelFactory.buildHabit().apply {
            this.name = if (isNumerical) "Numerical Habit" else "YesNo Habit"
            this.type = if (isNumerical) HabitType.NUMERICAL else HabitType.YES_NO
            this.unit = unit
        }
        habitList.add(habit)
        return habit
    }

    @Test
    fun saveElapsedIgnoresNonNumericalHabit() {
        val habit = createHabit(isNumerical = false)
        val manager = createManager()
        val callbackCalled = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        manager.startOrPause(habit)
        currentTimeMillis += 60_000L // 1 minute
        manager.finish(habit) { error ->
            callbackCalled.set(true)
            errorRef.set(error)
        }

        assertTrue("Completion callback should be called", callbackCalled.get())
        assertEquals(null, errorRef.get())
        // For non-numerical habit, no repetition entry should be recorded with numerical scaled values
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(Entry.UNKNOWN, entry.value)
        assertEquals(0L, manager.getUnrecordedElapsedMillis(habit.id!!))
    }

    @Test
    fun saveElapsedPreservesElapsedDataOnNumericalOverflow() {
        val habit = createHabit(isNumerical = true)
        val manager = createManager()

        manager.startOrPause(habit)
        // Set elapsed time exceeding MAX_NUMERICAL_VALUE: Entry.toScaledValue throws IllegalArgumentException
        // 2_148_000 minutes = 2_148_000 * 60_000 ms
        val hugeElapsedMillis = 2_148_000L * 60_000L
        currentTimeMillis += hugeElapsedMillis

        val callbackCalled = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        manager.finish(habit) { error ->
            callbackCalled.set(true)
            errorRef.set(error)
        }

        assertTrue("Completion callback should be called", callbackCalled.get())
        assertNotNull("Should deliver error on overflow", errorRef.get())
        assertTrue("Error should be IllegalArgumentException", errorRef.get() is IllegalArgumentException)

        // Verify elapsed data was NOT silently clamped or discarded, but preserved in preferences!
        val preserved = manager.getUnrecordedElapsedMillis(habit.id!!)
        assertEquals(hugeElapsedMillis, preserved)
    }

    @Test
    fun completeIfDueGuaranteesStatePersistenceEvenOnSaveElapsedFailure() {
        val habit = createHabit(isNumerical = true)
        val controllableTaskRunner = ControllableTaskRunner()
        val commandRunner = CommandRunner(controllableTaskRunner)
        val manager = createManager(commandRunner = commandRunner)

        // Configure pomodoro with 25m focus
        manager.switchMode(habit, TimerMode.POMODORO)
        manager.startOrPause(habit)

        // Advance time to complete focus phase
        currentTimeMillis += 25 * 60 * 1000L

        // Trigger onAlarm for the current revision
        val revision = testPrefs.getLong("pref_timer_alarm_revision", 0L)
        val callbackCalled = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        manager.onAlarm(revision) { error ->
            callbackCalled.set(true)
            errorRef.set(error)
        }

        // The persistence task is queued
        assertEquals(1, controllableTaskRunner.tasks.size)

        // Inject genuine failure into the database persistence task
        val injectedFailure = RuntimeException("Simulated SQLite failure")
        controllableTaskRunner.executeNext(shouldFail = true, exception = injectedFailure)

        assertTrue("Completion callback called", callbackCalled.get())
        assertEquals(injectedFailure, errorRef.get())

        // Verify persistState was committed: phase in preferences should now be BREAK, not stale running focus!
        val savedPhase = testPrefs.getString("pref_timer_phase", null)
        val savedRunning = testPrefs.getBoolean("pref_timer_is_running", true)
        assertEquals("BREAK", savedPhase)
        assertFalse("Timer should be paused in break awaiting transition or auto-switch", savedRunning)

        // Verify pending unrecorded time was NOT discarded or wiped out, but preserved in preferences!
        val preserved = manager.getUnrecordedElapsedMillis(habit.id!!)
        assertEquals(25 * 60 * 1000L, preserved)
    }

    @Test
    fun completeIfDueGuaranteesPendingElapsedDurableBeforeTimerStateTransition() {
        val habit = createHabit(isNumerical = true)
        val controllableTaskRunner = ControllableTaskRunner()
        val commandRunner = CommandRunner(controllableTaskRunner)
        val manager = createManager(commandRunner = commandRunner)

        manager.switchMode(habit, TimerMode.POMODORO)
        manager.startOrPause(habit)

        currentTimeMillis += 25 * 60 * 1000L
        val revision = testPrefs.getLong("pref_timer_alarm_revision", 0L)

        manager.onAlarm(revision)

        // The DB write has NOT executed yet (task is queued)
        assertEquals(1, controllableTaskRunner.tasks.size)

        // Verify that pending elapsed millis is ALREADY durable in preferences synchronously,
        // and timer state is also committed, so process death at this instant loses zero data!
        assertEquals("BREAK", testPrefs.getString("pref_timer_phase", null))
        assertEquals(25 * 60 * 1000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Complete the task
        controllableTaskRunner.executeNext(shouldFail = false)
        assertEquals(0L, manager.getUnrecordedElapsedMillis(habit.id!!))
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(25000, entry.value)
    }

    @Test
    fun overlappingSaveElapsedCallsSerializeAndPreserveAllPendingTime() {
        val habit = createHabit(isNumerical = true)
        val controllableTaskRunner = ControllableTaskRunner()
        val commandRunner = CommandRunner(controllableTaskRunner)
        val manager = createManager(commandRunner = commandRunner)

        val cb1Called = AtomicBoolean(false)
        val cb1Error = AtomicReference<Throwable?>(null)
        val cb2Called = AtomicBoolean(false)

        // Call 1: save 25 minutes (1_500_000 ms)
        manager.startOrPause(habit)
        currentTimeMillis += 25 * 60 * 1000L
        manager.finish(habit) { error ->
            cb1Called.set(true)
            cb1Error.set(error)
        }

        // Call 1 queued task 1
        assertEquals(1, controllableTaskRunner.tasks.size)
        assertEquals(25 * 60 * 1000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Call 2: while task 1 is in-flight, another session finishes 5 minutes (300_000 ms)
        manager.startOrPause(habit)
        currentTimeMillis += 5 * 60 * 1000L
        manager.finish(habit) {
            cb2Called.set(true)
        }

        // Call 2 must NOT spawn a concurrent racing task, but must update durable pending elapsed to 30 min
        assertEquals(1, controllableTaskRunner.tasks.size)
        assertEquals(30 * 60 * 1000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Simulate Task 1 failing
        val injectedFailure = RuntimeException("SQLite disk error")
        controllableTaskRunner.executeNext(shouldFail = true, exception = injectedFailure)

        // Callback 1 receives the failure notification
        assertTrue("Callback 1 should be notified", cb1Called.get())
        assertEquals(injectedFailure, cb1Error.get())

        // CRITICAL: Call 2's callback or completion did NOT wipe out Call 1's time!
        // All 30 minutes remain durable in preferences!
        assertEquals(30 * 60 * 1000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Now retry/flush unrecorded time
        val retryCalled = AtomicBoolean(false)
        manager.flushUnrecordedElapsed(habit.id!!) {
            retryCalled.set(true)
        }

        assertEquals(1, controllableTaskRunner.tasks.size)
        controllableTaskRunner.executeNext(shouldFail = false)

        assertTrue("Retry callback should be called", retryCalled.get())
        // All 30 minutes credited to database entry
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(30000, entry.value)
        assertEquals(0L, manager.getUnrecordedElapsedMillis(habit.id!!))
    }

    @Test
    fun overlappingSaveElapsedCallsDrainSequentiallyOnSuccess() {
        val habit = createHabit(isNumerical = true)
        val controllableTaskRunner = ControllableTaskRunner()
        val commandRunner = CommandRunner(controllableTaskRunner)
        val manager = createManager(commandRunner = commandRunner)

        val cb1Called = AtomicBoolean(false)
        val cb2Called = AtomicBoolean(false)

        // Call 1: save 25 minutes
        manager.startOrPause(habit)
        currentTimeMillis += 25 * 60 * 1000L
        manager.finish(habit) { cb1Called.set(true) }

        // While Task 1 is queued, Call 2 arrives with 5 minutes
        manager.startOrPause(habit)
        currentTimeMillis += 5 * 60 * 1000L
        manager.finish(habit) { cb2Called.set(true) }

        assertEquals(1, controllableTaskRunner.tasks.size)
        assertEquals(30 * 60 * 1000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Task 1 completes successfully (credits 25 min)
        controllableTaskRunner.executeNext(shouldFail = false)

        // Because there were 5 remaining minutes, Task 2 was automatically queued!
        assertEquals(1, controllableTaskRunner.tasks.size)
        assertEquals(5 * 60 * 1000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Task 2 completes successfully (credits 5 min)
        controllableTaskRunner.executeNext(shouldFail = false)

        assertTrue(cb1Called.get())
        assertTrue(cb2Called.get())
        assertEquals(0L, manager.getUnrecordedElapsedMillis(habit.id!!))
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(30000, entry.value)
    }

    @Test
    fun preservedUnrecordedElapsedRecoversOnSubsequentSave() {
        val habit = createHabit(isNumerical = true)
        val manager = createManager()

        // Seed 120_000 ms (2 minutes) of unrecorded time
        val unrecordedKey = manager.unrecordedKey(habit.id!!)
        testPrefs.edit().putLong(unrecordedKey, 120_000L).apply()
        assertEquals(120_000L, manager.getUnrecordedElapsedMillis(habit.id!!))

        // Start timer, run for 3 minutes (180_000 ms)
        manager.startOrPause(habit)
        currentTimeMillis += 180_000L

        val callbackCalled = AtomicBoolean(false)
        manager.finish(habit) {
            callbackCalled.set(true)
        }

        assertTrue(callbackCalled.get())

        // Total credited should be 2 min + 3 min = 5 min = 5000 scaled value
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(5000, entry.value)

        // Unrecorded millis in preferences should now be cleared
        assertEquals(0L, manager.getUnrecordedElapsedMillis(habit.id!!))
    }

    @Test
    fun onAlarmChainsCompletionCallbackToCaller() {
        val habit = createHabit(isNumerical = true)
        val manager = createManager()

        manager.switchMode(habit, TimerMode.POMODORO)
        manager.startOrPause(habit)
        currentTimeMillis += 25 * 60 * 1000L

        val revision = testPrefs.getLong("pref_timer_alarm_revision", 0L)
        val completed = AtomicBoolean(false)

        manager.onAlarm(revision) { error ->
            completed.set(true)
            assertEquals(null, error)
        }

        assertTrue("onAlarm must notify completion callback", completed.get())
    }

    @Test
    fun saveElapsedCommitFailurePreventsTimerStateAdvancement() {
        val habit = createHabit(isNumerical = true)
        val manager = createManager()

        manager.switchMode(habit, TimerMode.POMODORO)
        manager.startOrPause(habit)

        currentTimeMillis += 25 * 60 * 1000L
        val revision = testPrefs.getLong("pref_timer_alarm_revision", 0L)

        // Simulate SharedPreferences commit failure when writing unrecorded elapsed time
        testPrefs.commitShouldFailFilter = { mods ->
            mods.keys.any { it.startsWith("pref_timer_unrecorded_elapsed_millis_") }
        }

        val callbackCalled = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        manager.onAlarm(revision) { error ->
            callbackCalled.set(true)
            errorRef.set(error)
        }

        assertTrue("Callback should be notified of commit failure", callbackCalled.get())
        assertNotNull("Should receive commit failure error", errorRef.get())
        assertTrue("Error should be IOException", errorRef.get() is java.io.IOException)

        // CRITICAL: Timer state must NOT advance to BREAK when durable pending write fails!
        assertEquals(PomodoroPhase.FOCUS, manager.snapshot().phase)
        val savedPhase = testPrefs.getString("pref_timer_phase", null)
        assertEquals("FOCUS", savedPhase)
    }

    @Test
    fun saveElapsedLongOverflowThrowsArithmeticExceptionWithoutClamping() {
        val habit = createHabit(isNumerical = true)
        val manager = createManager()

        // Seed unrecorded elapsed with near Long.MAX_VALUE
        val unrecordedKey = manager.unrecordedKey(habit.id!!)
        val nearMax = Long.MAX_VALUE - 10_000L
        testPrefs.edit().putLong(unrecordedKey, nearMax).commit()

        manager.startOrPause(habit)
        // Advance by 20_000 ms, causing nearMax + 20_000 to overflow Long
        currentTimeMillis += 20_000L

        val callbackCalled = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        manager.finish(habit) { error ->
            callbackCalled.set(true)
            errorRef.set(error)
        }

        assertTrue("Callback should be called", callbackCalled.get())
        assertNotNull("Should receive overflow error", errorRef.get())
        assertTrue("Error must be ArithmeticException, not clamped", errorRef.get() is ArithmeticException)

        // Data must not be clamped or discarded
        assertEquals(nearMax, manager.getUnrecordedElapsedMillis(habit.id!!))
    }

    @Test
    fun callbackCommitFailureReportsDuplicateReplayRisk() {
        val habit = createHabit(isNumerical = true)
        val controllableTaskRunner = ControllableTaskRunner()
        val commandRunner = CommandRunner(controllableTaskRunner)
        val manager = createManager(commandRunner = commandRunner)

        val callbackCalled = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        manager.startOrPause(habit)
        currentTimeMillis += 25 * 60 * 1000L
        manager.finish(habit) { error ->
            callbackCalled.set(true)
            errorRef.set(error)
        }

        assertEquals(1, controllableTaskRunner.tasks.size)

        // Simulate commit failure when trying to clear the unrecorded elapsed time after DB write
        testPrefs.commitShouldFailFilter = { mods ->
            mods.any { it.key.startsWith("pref_timer_unrecorded_elapsed_millis_") && it.value == null }
        }

        // Database write succeeds
        controllableTaskRunner.executeNext(shouldFail = false)

        assertTrue("Callback should be notified", callbackCalled.get())
        assertNotNull("Should receive error about duplicate replay risk", errorRef.get())
        assertTrue("Error should be IOException", errorRef.get() is java.io.IOException)
        assertTrue(
            "Error message must document cross-storage atomicity limitation",
            errorRef.get()?.message?.contains("Atomicity limitation") == true
        )

        // Repetition was written to database
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(25000, entry.value)
    }

    @Test
    fun pomodoroFullLifecycleAutomaticTransitionExcludesBreaksAndPausesFromCreditedWork() {
        val habit = createHabit(isNumerical = true)
        appPreferences.isPomodoroAutoSwitch = true
        val manager = createManager()
        manager.prepare(habit)
        manager.switchMode(habit, TimerMode.POMODORO)
        manager.updateDurations(habit, 25, 5)

        // 1. Start Focus
        assertTrue(manager.startOrPause(habit))
        assertTrue(manager.snapshot().isRunning)
        assertEquals(PomodoroPhase.FOCUS, manager.snapshot().phase)

        // 2. Run for 10 minutes, then pause
        currentTimeMillis += 10 * 60 * 1000L
        assertTrue(manager.startOrPause(habit))
        assertFalse(manager.snapshot().isRunning)
        assertEquals(PomodoroPhase.FOCUS, manager.snapshot().phase)

        // 3. Pause for 5 minutes (this must be excluded from credited work)
        currentTimeMillis += 5 * 60 * 1000L

        // 4. Resume Focus
        assertTrue(manager.startOrPause(habit))
        assertTrue(manager.snapshot().isRunning)

        // 5. Run remaining 15 minutes to completion
        currentTimeMillis += 15 * 60 * 1000L

        // 6. Complete focus via alarm / tick
        val rev = fakeAlarmScheduler.scheduledRevision ?: testPrefs.getLong("pref_timer_alarm_revision", 0L)
        manager.onAlarm(rev)

        // Verify: Focus completed and automatically transitioned to BREAK
        val postFocusSnapshot = manager.snapshot()
        assertEquals(PomodoroPhase.BREAK, postFocusSnapshot.phase)
        assertFalse(postFocusSnapshot.isRunning)

        // 7. Break phase execution: start break, run 2 min, pause 3 min, resume, run 3 min
        assertTrue(manager.startOrPause(habit))
        assertTrue(manager.snapshot().isRunning)

        currentTimeMillis += 2 * 60 * 1000L
        assertTrue(manager.startOrPause(habit))
        assertFalse(manager.snapshot().isRunning)

        currentTimeMillis += 3 * 60 * 1000L // paused in break
        assertTrue(manager.startOrPause(habit))
        assertTrue(manager.snapshot().isRunning)

        currentTimeMillis += 3 * 60 * 1000L // finish remaining 3 min of break

        val breakRev = fakeAlarmScheduler.scheduledRevision ?: testPrefs.getLong("pref_timer_alarm_revision", 0L)
        manager.onAlarm(breakRev)

        // Verify: Break completed and returned to FOCUS
        assertEquals(PomodoroPhase.FOCUS, manager.snapshot().phase)

        // Break time MUST NOT add to habit entry
        val postBreakEntry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals("Break time must add 0 to habit credited work", 25000, postBreakEntry.value)
    }

    @Test
    fun pomodoroManualBreakTransitionOvertimeIsDistinct() {
        val habit = createHabit(isNumerical = true)
        appPreferences.isPomodoroAutoSwitch = false
        val manager = createManager()
        manager.prepare(habit)
        manager.switchMode(habit, TimerMode.POMODORO)
        manager.updateDurations(habit, 25, 5)

        // Start Focus
        manager.startOrPause(habit)

        // Run 25 min base + 3 min overtime
        currentTimeMillis += 28 * 60 * 1000L

        // Trigger manual break transition
        manager.takeBreakCurrent()

        // Phase transitioned to BREAK
        assertEquals(PomodoroPhase.BREAK, manager.snapshot().phase)

        // Base 25 min + 3 min overtime = 28 min credited
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(28000, entry.value)
    }

    @Test
    fun pomodoroCancellationAndResetRestoresCleanStateWithoutCreditingWork() {
        val habit = createHabit(isNumerical = true)
        val manager = createManager()
        manager.prepare(habit)
        manager.switchMode(habit, TimerMode.POMODORO)

        // Start Focus, advance 5 minutes
        manager.startOrPause(habit)
        currentTimeMillis += 5 * 60 * 1000L

        // Cancel / reset
        manager.resetCurrent()

        // Must be idle, not running
        assertFalse(manager.snapshot().isRunning)
        assertEquals(0L, manager.snapshot().elapsedMillis)

        // Zero work credited (UNKNOWN = unrecorded)
        val entry = habit.originalEntries.get(LocalDate(2026, 10, 31))
        assertEquals(Entry.UNKNOWN, entry.value)
    }

    @Test
    fun taskFocusFullLifecycleWithDeterministicClock() {
        val captureStore = MemoryFocusCaptureStore()
        val manager = createManager(
            captureStore = captureStore,
            session = DatasetSessionFactory.production().copy(mode = DatasetMode.PRODUCTION_NEXT)
        )
        val taskId = TaskId("task-lifecycle-test")
        val taskTitle = "Important Task"

        manager.prepareTask(taskId, taskTitle)
        manager.switchTaskMode(taskId, taskTitle, TimerMode.STOPWATCH)

        // 1. Toggle task focus to start
        assertTrue(manager.toggleTask(taskId, taskTitle))
        assertTrue(manager.isTaskTarget(taskId))
        assertTrue(manager.snapshot().isRunning)

        // 2. Run for 8 minutes
        currentTimeMillis += 8 * 60 * 1000L

        // 3. Pause
        assertTrue(manager.toggleTask(taskId, taskTitle))
        assertFalse(manager.snapshot().isRunning)

        // 4. Paused for 4 minutes
        currentTimeMillis += 4 * 60 * 1000L

        // 5. Resume
        assertTrue(manager.toggleTask(taskId, taskTitle))
        assertTrue(manager.snapshot().isRunning)

        // 6. Run for 12 minutes
        currentTimeMillis += 12 * 60 * 1000L

        // 7. Finish task focus
        manager.finishCurrent()

        // State is cleaned up
        assertFalse(manager.snapshot().isRunning)
        assertFalse(manager.isTaskTarget(taskId))

        // Fact recorded with duration = 8 + 12 = 20 min (1_200_000 ms), excluding the 4 min paused
        val facts = captureStore.repository.getAll()
        assertEquals(1, facts.size)
        assertEquals(20 * 60 * 1000L, facts.single().durationMs)
        assertEquals(taskId.value, facts.single().taskId?.value)
    }
}
