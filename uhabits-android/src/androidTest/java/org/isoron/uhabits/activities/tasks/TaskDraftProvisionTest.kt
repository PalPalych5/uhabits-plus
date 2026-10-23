package org.isoron.uhabits.activities.tasks

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TaskDraftProvisionTest {
    @Test
    fun provisionFreshTemporaryOfflineExperiment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is TaskDraftQaRunner)
        check(InstrumentationRegistry.getArguments().getString("provision") == "true")
        val context = instrumentation.targetContext
        SQLiteDatabase.openDatabase(HabitsApplication.component.dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (table in listOf("Habits", "UserTasks")) {
                val exists = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                    check(it.moveToFirst()); it.getLong(0) != 0L
                }
                if (!exists) continue
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Provisioning requires empty $table" }
                }
            }
        }
        val manager = DatasetSessionManager(context)
        val result = manager.createAndActivateExperiment()
        check(result is org.isoron.uhabits.core.containers.session.DatasetActivationResult.Success) { result.toString() }
        val metadata = requireNotNull(manager.loadExperimentalMetadata())
        val marker = File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${metadata.datasetId}")
        check(!marker.exists())
        marker.writeText(metadata.databaseFilename)
    }
}
