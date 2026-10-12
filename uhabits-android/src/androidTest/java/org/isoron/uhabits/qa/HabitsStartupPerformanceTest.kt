package org.isoron.uhabits.qa

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.isoron.uhabits.utils.HabitsStartupTrace
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.test.core.app.ActivityScenario
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetCapabilities

/** Setup only. Measured cold launches run without instrumentation via the guarded host runner. */
@RunWith(AndroidJUnit4::class)
class HabitsStartupPerformanceTest {
    @Test fun prepareOfflineV30Fixture() {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(androidx.test.uiautomator.UiDevice.getInstance(ins).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        if (!HabitsApplication.component.session.isExperimental) {
            val activated = org.isoron.uhabits.session.DatasetSessionManager(ins.targetContext).activateExistingExperiment()
            if (activated is org.isoron.uhabits.core.containers.session.DatasetActivationResult.Success)
                ins.runOnMainSync { (ins.targetContext.applicationContext as HabitsApplication).rebuildComponent(activated.session) }
        }
        val fixture = UiReviewCaptureTest.initializeFixture()
        if (!org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(fixture.db).getAll()
                .any { it.id.value == "qa-hotfix-meditation-short" }) ProductionUiHotfixFixture.prepare(fixture)
        check(!fixture.session.capabilities.remoteCallsAllowed && !fixture.session.capabilities.syncEnabled)
        fixture.preferences.isFirstRun = false
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, HabitsStartupTrace.MARKER).writeText("offline-v30-next-startup-qa")
        val context = ins.targetContext
        fixture.habitCardListCache.shutdown()
        val source = context.getDatabasePath(fixture.session.databaseFilename)
        // This canonical file belongs exclusively to the guarded disposable AVD.
        // It was created empty by initializeFixture; no real-user file is accessed.
        ins.runOnMainSync { (context.applicationContext as HabitsApplication).shutdownForDatabaseRestoreRestart() }
        check(!File(source.path + "-wal").let { it.exists() && it.length() > 0 }) { "Closed fixture has WAL" }
        android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor -> check(cursor.moveToFirst() && cursor.getInt(0) == 30) }
        }
        source.copyTo(context.getDatabasePath(org.isoron.uhabits.core.DATABASE_FILENAME), overwrite = true)
        val canonical = context.getDatabasePath(org.isoron.uhabits.core.DATABASE_FILENAME)
        android.database.sqlite.SQLiteDatabase.openDatabase(canonical.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE OrganizationState SET mode = 'PRODUCTION_NEXT', runtime_authority = 'NEXT' WHERE id = 1")
        }
        check(org.isoron.uhabits.utils.DatabaseUtils.validateDatabaseBackup(canonical, 30).isValid) { "QA production fixture validation failed" }
        canonical.copyTo(context.getDatabasePath("test.db"), overwrite = true)
        org.isoron.uhabits.session.DatasetSessionManager(context).persistProductionNextSelection(fixture.session.datasetId)
        File(context.filesDir, "${org.isoron.uhabits.utils.DemoDataGenerator.DISPOSABLE_MARKER}-${fixture.session.datasetId}")
            .writeText(org.isoron.uhabits.core.DATABASE_FILENAME)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("pref_first_run", false).putBoolean("pref_sync_enabled", false).commit()
    }

    @Test fun verifyHydrationLoadingFailureRetryAndWarmNavigation() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val fixture = HabitsApplication.component
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        check(androidx.test.uiautomator.UiDevice.getInstance(ins).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "UHabit_UI_Review_Disposable_20261011")
        check(fixture.session.mode == DatasetMode.PRODUCTION_NEXT && !fixture.session.capabilities.remoteCallsAllowed && !fixture.session.capabilities.syncEnabled)
        val app = ins.targetContext.applicationContext as HabitsApplication
        ins.runOnMainSync { app.rebuildComponent(fixture.session) }
        val component = HabitsApplication.component
        val today = org.isoron.platform.time.getToday()
        // A fresh repository's single hydration pass must equal an explicit recompute.
        component.habitList.forEach { actual ->
            val expected = actual.copy(goalHistory = actual.goalHistory.toMutableList(),
                originalEntries = org.isoron.uhabits.core.models.EntryList().also { entries -> actual.originalEntries.getKnown().forEach(entries::add) },
                computedEntries = org.isoron.uhabits.core.models.EntryList(),
                scores = org.isoron.uhabits.core.models.ScoreList(), streaks = org.isoron.uhabits.core.models.StreakList())
            expected.recompute()
            org.junit.Assert.assertEquals(expected.computedEntries.getKnown(), actual.computedEntries.getKnown())
            org.junit.Assert.assertEquals(expected.scores[today], actual.scores[today])
            org.junit.Assert.assertEquals(expected.streaks.getBest(Int.MAX_VALUE), actual.streaks.getBest(Int.MAX_VALUE))
        }
        component.habitCardListCache.shutdown()
        ins.runOnMainSync { app.rebuildComponent(component.session) }
        val fresh = HabitsApplication.component
        val cache = fresh.habitCardListCache
        val delegate = requireNotNull(cache.effectiveSnapshots)
        val release = java.util.concurrent.CountDownLatch(1)
        cache.effectiveSnapshots = { check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)); throw android.database.sqlite.SQLiteException("QA injected read failure") }
        fun views(v: android.view.View): Sequence<android.view.View> = sequence {
            yield(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
        }
        try {
            ActivityScenario.launch<MainActivity>(MainActivity.intent(ins.targetContext, MainDestination.HABITS)).use { scenario ->
                scenario.onActivity { activity ->
                    check(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any {
                        it.isShown && it.text.toString() == activity.getString(org.isoron.uhabits.R.string.habits_loading) })
                    check(!cache.hasLoaded && cache.habitCount == 0)
                }
                release.countDown()
                repeat(3) { kotlinx.coroutines.runBlocking { fresh.taskRunner.await() }; ins.waitForIdleSync() }
                scenario.onActivity { activity ->
                    check(cache.lastRefreshFailed && !cache.hasLoaded && cache.habitCount == 0)
                    val retry = views(activity.window.decorView).filterIsInstance<android.widget.Button>().first {
                        it.isShown && it.text.toString() == activity.getString(org.isoron.uhabits.R.string.habits_loading_retry) }
                    cache.effectiveSnapshots = delegate
                    retry.performClick()
                }
                repeat(3) { kotlinx.coroutines.runBlocking { fresh.taskRunner.await() }; ins.waitForIdleSync() }
                val ids = (0 until cache.habitCount).map { cache.getHabitByPosition(it)!!.uuid }
                check(cache.hasLoaded && cache.habitCount == 31 && !cache.lastRefreshFailed)
                scenario.onActivity { it.navigate(MainDestination.TASKS) }
                ins.waitForIdleSync()
                scenario.onActivity { it.navigate(MainDestination.HABITS) }
                repeat(3) { kotlinx.coroutines.runBlocking { fresh.taskRunner.await() }; ins.waitForIdleSync() }
                check(ids == (0 until cache.habitCount).map { cache.getHabitByPosition(it)!!.uuid })
            }
        } finally { release.countDown(); cache.effectiveSnapshots = delegate }
    }
}
