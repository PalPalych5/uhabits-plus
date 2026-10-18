package org.isoron.uhabits.benchmark

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.queryString
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.containers.session.*
import org.isoron.uhabits.core.production.migration.*
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.utils.DemoDataGenerator
import java.io.File

/** External test APK entry point. No reset, database copy, or access to another package. */
class BenchmarkFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        setContentView(view)
        try {
            val expectedAvd = checkNotNull(intent.getStringExtra("disposableAvd"))
            verifyDevice(this, expectedAvd)
            when (intent.getStringExtra("operation")) {
                "provision" -> {
                    check(intent.getStringExtra("authorization") == "provision-isolated-synthetic-v1") { "Explicit provisioning authorization required" }
                    provision()
                }
                "verify", "inspectHabit" -> Unit
                else -> error("Expected provision or verify")
            }
            val result = verify()
            view.text = result
            view.contentDescription = result
            val habit = HabitsApplication.component.habitList.first { it.uuid == ("dec0de0a0000000000000000" + "00000001") }
            if (intent.getStringExtra("operation") == "inspectHabit") {
                val value = habit.originalEntries.get(org.isoron.platform.time.getToday()).value
                view.text = "fixture-habit:${habit.name}:$value"
                view.contentDescription = view.text
            }
        } catch (failure: Exception) {
            view.text = "fixture-error:${failure.message}"
            view.contentDescription = "fixture-error:${failure.message}"
        }
    }

    private fun provision() {
        val app = application as BenchmarkApplication
        val manager = DatasetSessionManager(this)
        // Existing or partially provisioned data is never overwritten or silently reset.
        check(!app.initialized && manager.loadExperimentalMetadata() == null)
        val file = getDatabasePath(DATABASE)
        check(!file.exists()) { "Fixture file already exists; manual review required" }
        val databaseDirectory = checkNotNull(file.parentFile)
        check(!databaseDirectory.exists() || (databaseDirectory.isDirectory && checkNotNull(databaseDirectory.listFiles()).isEmpty())) {
            "Benchmark package already contains database files or cannot be inspected; existing data is never reused"
        }
        // No source snapshot exists: this database is created from shipped schema assets only.
        val metadata = ExperimentalDatasetMetadata(DATASET, DATABASE, 1, "", 1791676800000L)
        // Fresh v29 schema only: never snapshot or read any pre-existing database.
        HabitsDatabaseOpener(this, DATABASE, 29, false).use { opener ->
            val raw = opener.writableDatabase
            val result = ProductionNextSchemaMigrator.migrate(AndroidDatabase(raw), metadata.createdAtMillis, datasetUuid = DATASET)
            check(result is ProductionNextMigrationResult.Success) { "Fresh synthetic schema migration failed: $result" }
            raw.execSQL("UPDATE OrganizationState SET mode = 'CONTAINER_LOCAL' WHERE id = 1")
        }
        File(filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-$DATASET").writeText(DATABASE)
        check(getSharedPreferences("benchmark_fixture", MODE_PRIVATE).edit().putString("avd", intent.getStringExtra("disposableAvd")).commit())
        manager.persistExperimentalSelection(metadata)
        check(manager.resolvedSession() == DatasetSessionFactory.experimental(metadata))
        app.initializeProvisionedRuntime()
        val component = HabitsApplication.component
        component.preferences.isFirstRun = false
        component.preferences.isDeveloper = true
        component.preferences.theme = 2
        component.preferences.isShortToggleEnabled = true
        component.preferences.isSkipEnabled = false
        component.preferences.areQuestionMarksEnabled = false
        component.preferences.isCheckmarkSequenceReversed = false
        DemoDataGenerator.generate(this, component.sqlModelFactory, component.habitList, null,
            component.habitCardListCache, false, LocalDate(2026, 10, 11))
    }

    private fun verify(): String {
        check((application as BenchmarkApplication).initialized) { "Explicit pre-provisioning required" }
        val component = HabitsApplication.component
        val session = component.session
        val manager = DatasetSessionManager(this)
        check(manager.isExperimentalSelected())
        check(manager.resolveSessionOnStartup() == session) { "Persisted session mismatch" }
        check(session.isExperimental && session.datasetId == DATASET && session.databaseFilename == DATABASE)
        check(component.dbFile.canonicalFile == getDatabasePath(DATABASE).canonicalFile)
        check(File(filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-$DATASET").readText() == DATABASE)
        check(!session.capabilities.syncEnabled && !session.capabilities.remoteCallsAllowed &&
            !session.capabilities.widgetsEnabled && !session.capabilities.remindersEnabled && !session.capabilities.backgroundJobsEnabled)
        check(!component.preferences.isSyncEnabled)
        check(component.preferences.isShortToggleEnabled && !component.preferences.isSkipEnabled &&
            !component.preferences.areQuestionMarksEnabled && !component.preferences.isCheckmarkSequenceReversed)
        val db = component.db
        check(db.queryLong("PRAGMA user_version") == 30L)
        check(db.queryString("SELECT dataset_uuid FROM OrganizationState WHERE id = 1") == DATASET)
        check(db.queryString("SELECT mode FROM OrganizationState WHERE id = 1") == "CONTAINER_LOCAL")
        check(db.queryLong("SELECT COUNT(*) FROM Habits") == 32L && component.habitList.size() == 32)
        check(db.queryLong("SELECT COUNT(*) FROM Habits WHERE uuid LIKE 'dec0de0a0000000000000000%'") == 32L)
        check(db.queryLong("SELECT COUNT(*) FROM Repetitions") == 5792L)
        check(db.queryLong("SELECT COUNT(DISTINCT timestamp) FROM Repetitions") == 181L)
        check(org.isoron.platform.time.getToday() == LocalDate(2026, 10, 11))
        check(db.queryLong("SELECT MIN(timestamp) FROM Repetitions") == LocalDate(2026, 10, 11).minus(180).unixTime)
        check(db.queryLong("SELECT MAX(timestamp) FROM Repetitions") == LocalDate(2026, 10, 11).unixTime)
        check(db.queryLong("SELECT COUNT(*) FROM (SELECT habit FROM Repetitions GROUP BY habit HAVING COUNT(*) = 181)") == 32L)
        check(db.queryLong("SELECT COUNT(*) FROM Containers") == 10L)
        check(db.queryLong("SELECT COUNT(*) FROM Containers WHERE parent_id IS NOT NULL") == 2L)
        check(db.queryLong("SELECT COUNT(*) FROM HabitPlacements") == 32L)
        check(db.queryLong("SELECT COUNT(*) FROM UserTasks") == 84L)
        check(db.queryLong("SELECT COUNT(*) FROM UserTasks WHERE parent_task_id IS NOT NULL") == 12L)
        check(db.queryLong("SELECT COUNT(*) FROM FocusSessions") == 64L)
        check(!component.timerSessionManager.snapshot().hasActiveSession)
        return "fixture-ready:$DATASET:$DATABASE:32:84:5792:10:2:offline"
    }

    companion object {
        fun verifyDevice(context: android.content.Context, expectedAvd: String) {
            check(context.packageName == PACKAGE)
            check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices forbidden" }
            check(expectedAvd == "UHabit_Next_Benchmark_Disposable_API35_Phase22")
            check(android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0) == 1)
            check(android.provider.Settings.Global.getInt(context.contentResolver, "wifi_on", 1) == 0)
            val process = ProcessBuilder("/system/bin/getprop", "ro.boot.qemu.avd_name").start()
            val actual = process.inputStream.bufferedReader().use { it.readText().trim() }
            check(process.waitFor() == 0 && actual == expectedAvd) { "Disposable AVD identity mismatch" }
        }
        const val PACKAGE = "org.isoron.uhabits.plus.benchmark"
        const val DATASET = "benchmark-synthetic-v1"
        const val DATABASE = DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME
    }
}
