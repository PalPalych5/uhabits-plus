package org.isoron.uhabits.activities.tasks

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.test.runner.AndroidJUnitRunner
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult
import org.isoron.platform.io.migrateTo
import org.isoron.uhabits.database.AndroidDatabase
import kotlinx.coroutines.runBlocking
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.utils.DemoDataGenerator
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Prevent legacy test DB deletion until the temporary device and explicit intent are verified. */
class TaskDraftQaRunner : AndroidJUnitRunner() {
    private var qaArguments: Bundle? = null

    override fun onCreate(arguments: Bundle) {
        qaArguments = Bundle(arguments)
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        val args = requireNotNull(qaArguments) { "QA arguments must be captured before app startup" }
        val authorized = args.getString("authorizedAvd")
        check(BuildConfig.DEBUG && authorized != null && authorized.startsWith("UHabit_Temp_"))
        val actual = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.qemu.avd_name"))
            .inputStream.bufferedReader().use { it.readText().trim() }
        check(actual == authorized) { "Only the explicitly authorized temporary AVD is allowed" }
        check(Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(app.contentResolver, Settings.Global.WIFI_ON, 0) == 0)
        if (args.getString("freshFixture") == "true") {
            createFreshFixture(app)
        } else if (args.getString("provision") == "true") {
            check(app.databaseList().isEmpty()) { "Provisioning requires a fresh installation with no databases" }
        } else {
            val manager = DatasetSessionManager(app)
            check(manager.isExperimentalSelected())
            val metadata = requireNotNull(manager.loadExperimentalMetadata())
            val marker = File(app.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${metadata.datasetId}")
            check(marker.isFile && marker.readText().trim() == metadata.databaseFilename)
        }
        super.callApplicationOnCreate(app)
    }

    private fun createFreshFixture(app: Application) {
        val manager = DatasetSessionManager(app)
        check(!manager.isTimerActive()) { "Pending timer forbids fixture replacement" }
        val previous = manager.loadExperimentalMetadata()
        val target = manager.getExperimentalDatabaseFile()
        if (previous == null) {
            check(app.databaseList().isEmpty()) { "Initial fixture requires an empty database directory" }
            check(!manager.isExperimentalSelected()) { "Experimental selection without metadata is invalid" }
        } else {
            check(manager.isExperimentalSelected())
            val previousMarker = File(app.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${previous.datasetId}")
            check(previousMarker.isFile && previousMarker.readText().trim() == previous.databaseFilename)
            check(target.canonicalFile == app.getDatabasePath(previous.databaseFilename).canonicalFile)
        }
        val uuid = UUID.randomUUID().toString()
        val diagnostics = File(app.getExternalFilesDir(null), "pr5-final-qa/fixture-diagnostics-$uuid.txt")
        check(diagnostics.parentFile!!.mkdirs() || diagnostics.parentFile!!.isDirectory)
        // No open application DB handles exist yet. Preserve every SQLite file byte for byte.
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val source = File(target.path + suffix)
            if (!source.exists()) continue
            val archived = File(target.parentFile, "${target.name}.failed-$uuid$suffix")
            check(!archived.exists())
            val before = sha256(source)
            check(source.renameTo(archived)) { "Failed to preserve ${source.name}" }
            check(sha256(archived) == before)
            diagnostics.appendText("${archived.name} bytes=${archived.length()} sha256=$before\n")
        }
        check(!target.exists())
        val metadata = ExperimentalDatasetMetadata(uuid, target.name, 1, "", System.currentTimeMillis())
        HabitsDatabaseOpener(app, target.name, 29, false).use { opener ->
            val raw = opener.writableDatabase
            val wrapped = AndroidDatabase(raw)
            val result = ProductionNextSchemaMigrator.migrate(wrapped, metadata.createdAtMillis, datasetUuid = uuid)
            check(result is ProductionNextMigrationResult.Success) { result.toString() }
            runBlocking {
                wrapped.migrateTo(DATABASE_VERSION) { version ->
                    app.assets.open("migrations/%02d.sql".format(version)).bufferedReader().use { it.readText() }
                }
            }
            check(raw.version == DATABASE_VERSION)
            raw.execSQL("UPDATE OrganizationState SET mode='CONTAINER_LOCAL' WHERE id=1")
        }
        val marker = File(app.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-$uuid")
        check(!marker.exists()); marker.writeText(target.name)
        check(app.getSharedPreferences("uhabits_prefs_experimental_$uuid", Context.MODE_PRIVATE).edit()
            .putBoolean("pref_first_run", false).putBoolean("pref_sync_enabled", false)
            .putBoolean("pref_developer", true).commit())
        manager.persistExperimentalSelection(metadata)
        diagnostics.appendText("newDataset=$uuid newFilename=${target.name} version=$DATABASE_VERSION\n")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
