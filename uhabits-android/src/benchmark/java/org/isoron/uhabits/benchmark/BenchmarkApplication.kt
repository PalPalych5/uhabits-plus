package org.isoron.uhabits.benchmark

import android.os.Build
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.core.containers.session.DatasetValidationResult
import org.isoron.uhabits.utils.DemoDataGenerator
import java.io.File

/** No ordinary database or runtime is opened until explicit isolated provisioning. */
class BenchmarkApplication : HabitsApplication() {
    internal override fun currentHabitDate(hourOffset: Int, minuteOffset: Int) =
        org.isoron.platform.time.LocalDate(2026, 10, 11)

    var initialized = false
        private set

    override fun onCreate() {
        check(packageName == BenchmarkFixtureActivity.PACKAGE)
        val manager = DatasetSessionManager(this)
        if (manager.loadExperimentalMetadata() != null) initializeProvisionedRuntime()
    }

    fun initializeProvisionedRuntime() {
        check(!initialized)
        BenchmarkFixtureActivity.verifyDevice(this, checkNotNull(getSharedPreferences("benchmark_fixture", MODE_PRIVATE).getString("avd", null)))
        check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices forbidden" }
        val manager = DatasetSessionManager(this)
        val metadata = checkNotNull(manager.loadExperimentalMetadata())
        check(manager.isExperimentalSelected())
        check(metadata.datasetId == BenchmarkFixtureActivity.DATASET)
        check(metadata.databaseFilename == BenchmarkFixtureActivity.DATABASE)
        check(manager.validateExperimental(metadata) is DatasetValidationResult.Valid)
        check(File(filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${metadata.datasetId}").readText() == metadata.databaseFilename)
        org.isoron.platform.time.DateUtils.setFixedLocalTime(org.isoron.platform.time.LocalDate(2026, 10, 11).unixTime + 12 * 3600000L)
        super.onCreate()
        initialized = true
    }
}
