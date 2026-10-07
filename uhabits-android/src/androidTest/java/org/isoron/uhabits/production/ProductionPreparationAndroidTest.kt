package org.isoron.uhabits.production

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.production.preflight.PreflightIssueCategory
import org.isoron.uhabits.core.production.preflight.ProductionPreflightService
import org.isoron.uhabits.core.production.preflight.TimerMigrationReadiness
import org.isoron.uhabits.core.production.recovery.ProductionRecoveryGate
import org.isoron.uhabits.core.production.recovery.RecoveryBlocker
import org.isoron.uhabits.core.production.recovery.RecoveryPreferenceKey
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses only private disposable files; never swaps the application component or default prefs. */
@RunWith(AndroidJUnit4::class)
class ProductionPreparationAndroidTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "pr21-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = File(root, "legacy-v29.db")
        val bundles = File(root, "recovery")
        val preferences = ProductionPreparationStartup.capturePreferences(emptyMap<String, Any>())

        init {
            HabitsDatabaseOpener(context, source.absolutePath, DATABASE_VERSION).use { opener ->
                assertEquals(29, opener.writableDatabase.version)
            }
        }

        fun runner() = ProductionPreparationRunner(source, bundles, { preferences }, {})

        fun inspect() = SQLiteDatabase.openDatabase(source.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use {
            ProductionPreflightService.inspect(AndroidDatabase(it), source.path,
                ProductionRecoveryGate.fileSha256(source))
        }

        override fun close() { root.deleteRecursively() }
    }

    @Test fun realLegacyPreflightAndVerifiedSnapshotAreByteIdentical() = Fixture().use { fixture ->
        val original = fixture.source.readBytes()
        val before = fixture.inspect()
        assertEquals(29, DATABASE_VERSION)
        assertTrue(before.blockingIssues.toString(), before.sourceEligible)
        assertFalse(before.migrationEligible)
        assertTrue(before.blockingIssues.any { it.category == PreflightIssueCategory.RecoveryBundleUnavailable })
        val runner = fixture.runner()
        val ready = runner.prepare()
        assertTrue(ready.toString(), ready.migrationEligible)
        assertTrue(runner.report!!.migrationEligible)
        assertArrayEquals(original, fixture.source.readBytes())
        assertArrayEquals(original, File(fixture.bundles, "${ready.bundleId}/legacy-v29.db").readBytes())
        assertEquals(before.fingerprint, fixture.inspect().fingerprint)
        assertEquals(before.schemaInventory, fixture.inspect().schemaInventory)
        assertTrue(fixture.runner().recover().migrationEligible)
    }

    @Test fun recoveryPreferenceCaptureExcludesSecretsWithoutMutatingInput() {
        val raw = mapOf<String, Any>("pref_sync_key" to "secret-api-key", "access_token" to "secret-token",
            "password" to "secret-password", "pref_sync_account_email" to "private@example.org",
            "pref_sync_last_log_id" to 7L, "sync_device_id" to "0123456789abcdef0123456789abcdef")
        val captured = ProductionPreparationStartup.capturePreferences(raw)
        assertEquals("7", captured.values[RecoveryPreferenceKey.SYNC_LAST_LOG_ID])
        assertEquals(raw["sync_device_id"], captured.values[RecoveryPreferenceKey.SYNC_DEVICE_ID])
        assertEquals(RecoveryPreferenceKey.values().toSet(), captured.values.keys)
        assertFalse(captured.values.values.any { it.contains("secret") || it.contains("private@example") })
        assertEquals("secret-api-key", raw["pref_sync_key"])
        Fixture().use { fixture ->
            val runner = ProductionPreparationRunner(fixture.source, fixture.bundles, { captured }, {})
            assertTrue(runner.prepare().migrationEligible)
            assertEquals(raw["sync_device_id"], runner.report!!.fingerprint.identityMarkers["syncDeviceId"])
            assertTrue(runner.recover().migrationEligible)
        }
    }

    @Test fun unexpectedTaskTablesAreReportedAndNeverRepaired() {
        for (nonempty in listOf(false, true)) Fixture().use { fixture ->
            SQLiteDatabase.openDatabase(fixture.source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TABLE UserTasks(id TEXT PRIMARY KEY)")
                if (nonempty) it.execSQL("INSERT INTO UserTasks VALUES('preserved-experiment')")
            }
            val before = fixture.source.readBytes()
            val report = fixture.inspect()
            val expected = if (nonempty) PreflightIssueCategory.NonEmptyUnexpectedNextTable
                else PreflightIssueCategory.UnexpectedNextTable
            assertTrue(report.blockingIssues.toString(), report.blockingIssues.any {
                it.category == expected && it.table == "UserTasks"
            })
            assertFalse(fixture.runner().prepare().migrationEligible)
            assertArrayEquals(before, fixture.source.readBytes())
            assertEquals(if (nonempty) 1L else 0L, fixture.inspect().fingerprint.rowCounts["UserTasks"])
        }
    }

    @Test fun legacyTaskProviderRejectsBeforeOpeningOrInstallingSchema() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        val component = HabitsApplicationComponent::class.create(appContext = fixture.context,
            dbFile = fixture.source, session = DatasetSessionFactory.production())
        try {
            component.userTaskRepository()
            fail("Legacy task repository resolution must reject before SQL")
        } catch (_: IllegalStateException) { }
        assertArrayEquals(before, fixture.source.readBytes())
        assertFalse(fixture.inspect().schemaInventory.any { it.name == "UserTasks" })
    }

    @Test fun sourceChangesInvalidateReadiness() = Fixture().use { fixture ->
        assertTrue(fixture.runner().prepare().migrationEligible)
        SQLiteDatabase.openDatabase(fixture.source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO AppSettings(key,long_value) VALUES('pr21-fixture-change',42)")
        }
        val changed = fixture.source.readBytes()
        val stale = fixture.runner().recover()
        assertFalse(stale.migrationEligible)
        assertEquals(RecoveryBlocker.SOURCE_CHANGED, stale.blocker)
        assertArrayEquals(changed, fixture.source.readBytes())
    }

    @Test fun missingSnapshotBlocksWithoutTouchingCanonicalDatabase() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        val ready = fixture.runner().prepare()
        assertTrue(ready.migrationEligible)
        assertTrue(File(fixture.bundles, "${ready.bundleId}/legacy-v29.db").delete())
        val runner = fixture.runner()
        val recovered = runner.recover()
        assertEquals(RecoveryBlocker.BUNDLE_MISSING, recovered.blocker)
        assertFalse(recovered.migrationEligible)
        assertNotNull(runner.report)
        assertTrue(runner.report!!.sourceEligible)
        ProductionPreparationStartup.requireSafeLegacyStartup(runner.report, recovered)
        assertArrayEquals(before, fixture.source.readBytes())
    }

    @Test fun corruptManifestBlocksWithoutTouchingCanonicalDatabase() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        val ready = fixture.runner().prepare()
        assertTrue(ready.migrationEligible)
        File(fixture.bundles, "${ready.bundleId}/manifest").appendText("corrupt")
        val runner = fixture.runner()
        val recovered = runner.recover()
        assertEquals(RecoveryBlocker.BUNDLE_CORRUPT, recovered.blocker)
        assertFalse(recovered.migrationEligible)
        assertNotNull(runner.report)
        assertTrue(runner.report!!.sourceEligible)
        ProductionPreparationStartup.requireSafeLegacyStartup(runner.report, recovered)
        assertArrayEquals(before, fixture.source.readBytes())
    }

    @Test fun uncheckpointedWalBlocksMainFileOnlyRecoverySnapshot() = Fixture().use { fixture ->
        SQLiteDatabase.openDatabase(fixture.source.path, null, SQLiteDatabase.OPEN_READWRITE).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { assertTrue(it.moveToFirst()) }
            writer.execSQL("INSERT INTO AppSettings(key,long_value) VALUES('pr21-wal-fixture',42)")
            assertTrue(File(fixture.source.path + "-wal").length() > 0)
            val before = fixture.source.readBytes()
            val blocked = fixture.runner().prepare()
            assertFalse(blocked.migrationEligible)
            assertEquals(RecoveryBlocker.ACTIVE_SIDECAR, blocked.blocker)
            assertArrayEquals(before, fixture.source.readBytes())
        }
    }

    @Test fun activeLegacyTimersBlockPreparationAndRestartWithCompleteReport() {
        val variants = listOf(
            mapOf<String, Any>("pref_timer_habit_id" to 1L, "pref_timer_is_running" to true,
                "pref_timer_started_at_wall_clock_millis" to 1000L),
            mapOf<String, Any>("pref_timer_habit_id" to 1L, "pref_timer_accumulated_elapsed_millis" to 1000L),
            mapOf<String, Any>("pref_timer_habit_id" to 1L, "pref_timer_mode" to "POMODORO",
                "pref_timer_is_running" to true, "pref_timer_started_at_wall_clock_millis" to 1000L),
            mapOf<String, Any>("pref_timer_habit_id" to 1L, "pref_timer_mode" to "POMODORO", "pref_timer_phase" to "BREAK"),
            mapOf<String, Any>("pref_timer_completion_display_active" to true),
        )
        variants.forEach { raw -> Fixture().use { fixture ->
            val before = fixture.source.readBytes()
            val preferences = ProductionPreparationStartup.capturePreferences(raw)
            fun runner() = ProductionPreparationRunner(fixture.source, fixture.bundles, { preferences }, {})
            val preparation = runner()
            val prepared = preparation.prepare()
            assertFalse(prepared.migrationEligible)
            assertNotNull(preparation.report)
            ProductionPreparationStartup.requireSafeLegacyStartup(preparation.report, prepared)
            val expected = if (raw["pref_timer_completion_display_active"] == true)
                TimerMigrationReadiness.BLOCKED_PENDING_COMPLETION else TimerMigrationReadiness.BLOCKED_ACTIVE_WORK
            assertEquals(expected, preparation.report!!.timerReadiness)
            val restart = runner()
            val recovered = restart.recover()
            assertFalse(recovered.migrationEligible)
            assertNotNull(restart.report)
            ProductionPreparationStartup.requireSafeLegacyStartup(restart.report, recovered)
            assertEquals(expected, restart.report!!.timerReadiness)
            assertTrue(restart.report!!.blockingIssues.any { it.category == PreflightIssueCategory.LegacyTimerActive })
            assertArrayEquals(before, fixture.source.readBytes())
        } }
    }

    @Test fun preDiLeaseGuardFailsBeforeSnapshotOrDatabaseMutation() = Fixture().use { fixture ->
        val before = fixture.source.readBytes()
        val runner = ProductionPreparationRunner(fixture.source, fixture.bundles, { fixture.preferences }, {
            throw IllegalStateException("Normal dependencies already initialized")
        })
        try {
            runner.prepare()
            fail("Initialized dependencies must reject preparation")
        } catch (expected: IllegalStateException) {
            assertEquals("Normal dependencies already initialized", expected.message)
        }
        assertNull(runner.report)
        assertFalse(fixture.bundles.exists())
        assertArrayEquals(before, fixture.source.readBytes())
    }

    @Test fun unsupportedSourceVersionsBlockOrdinaryOpenerWithoutMutation() {
        for (version in listOf(28, 30)) Fixture().use { fixture ->
            SQLiteDatabase.openDatabase(fixture.source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.version = version
            }
            val before = fixture.source.readBytes()
            val runner = fixture.runner()
            val result = runner.prepare()
            assertFalse(result.migrationEligible)
            assertNotNull(runner.report)
            assertEquals(version, runner.report!!.userVersion)
            try {
                ProductionPreparationStartup.requireSafeLegacyStartup(runner.report, result)
                fail("Unsupported source must not reach the ordinary SQLiteOpenHelper")
            } catch (_: IOException) { }
            assertArrayEquals(before, fixture.source.readBytes())
            assertFalse(File(fixture.source.path + ".invalid").exists())
        }
    }

    @Test fun missingCanonicalSourceBlocksStartupWithoutCreatingEmptyDatabase() = Fixture().use { fixture ->
        assertTrue(fixture.source.delete())
        val runner = fixture.runner()
        val result = runner.prepare()
        assertEquals(RecoveryBlocker.SOURCE_MISSING, result.blocker)
        assertNull(runner.report)
        try {
            ProductionPreparationStartup.requireSafeLegacyStartup(runner.report, result)
            fail("Missing canonical file must not reach SQLiteOpenHelper.onCreate")
        } catch (_: IOException) { }
        assertFalse(fixture.source.exists())
    }
}
