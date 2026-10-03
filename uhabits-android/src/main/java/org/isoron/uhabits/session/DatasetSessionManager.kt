/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.session

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.preference.PreferenceManager
import org.isoron.platform.io.migrateTo
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.containers.backup.ContainerBackupManifest
import org.isoron.uhabits.core.containers.backup.ContainerBackupRouter
import org.isoron.uhabits.core.containers.backup.ContainerBackupService
import org.isoron.uhabits.core.containers.backup.DatasetKind
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationExecutor
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationPlanner
import org.isoron.uhabits.core.containers.migration.LegacyContainerMigrationValidator
import org.isoron.uhabits.core.containers.migration.MigrationExecutionResult
import org.isoron.uhabits.core.containers.migration.MigrationValidationResult
import org.isoron.uhabits.core.containers.migration.RawLegacyInventoryReader
import org.isoron.uhabits.core.containers.session.DatasetActivationResult
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.DatasetSessionResolver
import org.isoron.uhabits.core.containers.session.DatasetValidationResult
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages the lifecycle, persistence, validation, creation, and switching of [DatasetSession] on Android.
 *
 * Guarantees:
 * - Production database is never modified during experimental session creation or execution.
 * - Experimental session runs on a completely separate SQLite file ([DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME]).
 * - Gated activation: blocked if timer is active or migration fails.
 * - Corrupted experimental database never causes silent data blending; safely falls back to production.
 */
class DatasetSessionManager(
    private val context: Context
) : DatasetSessionResolver {

    private val sessionPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun resolvedSession(): DatasetSession {
        val metadata = loadExperimentalMetadata()
        return if (metadata != null && isExperimentalSelected()) {
            DatasetSessionFactory.experimental(metadata)
        } else {
            DatasetSessionFactory.production()
        }
    }

    /**
     * Resolves the session at process startup, running validation on the experimental database if selected.
     * If the experimental database is missing or corrupt, falls back safely to production and records the error.
     */
    fun resolveSessionOnStartup(): DatasetSession {
        if (!isExperimentalSelected()) {
            return DatasetSessionFactory.production()
        }

        val metadata = loadExperimentalMetadata()
        if (metadata == null) {
            clearExperimentalSelection()
            return DatasetSessionFactory.production()
        }

        return when (val validation = validateExperimental(metadata)) {
            is DatasetValidationResult.Valid -> {
                Log.i(TAG, "Experimental dataset validated successfully: ${metadata.datasetId}")
                DatasetSessionFactory.experimental(metadata)
            }
            is DatasetValidationResult.Invalid -> {
                Log.w(TAG, "Experimental dataset corrupt (${validation.reason}), safely falling back to production")
                recordStartupError("Экспериментальный датасет повреждён: ${validation.reason}. Выполнен безопасный возврат в продуктовый режим.")
                clearExperimentalSelection()
                DatasetSessionFactory.production()
            }
            is DatasetValidationResult.Missing -> {
                Log.w(TAG, "Experimental dataset file missing (${validation.reason}), falling back to production")
                recordStartupError("Файл экспериментального датасета не найден. Выполнен возврат в продуктовый режим.")
                clearExperimentalSelection()
                DatasetSessionFactory.production()
            }
        }
    }

    fun isExperimentalSelected(): Boolean {
        val modeStr = sessionPrefs.getString(KEY_SELECTED_MODE, DatasetMode.LEGACY_PRODUCTION.name)
        return modeStr == DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL.name
    }

    override fun loadExperimentalMetadata(): ExperimentalDatasetMetadata? {
        val datasetId = sessionPrefs.getString(KEY_DATASET_ID, null) ?: return null
        val filename = sessionPrefs.getString(KEY_DATABASE_FILENAME, DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME)
            ?: DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME
        val foundationVersion = sessionPrefs.getInt(KEY_FOUNDATION_VERSION, 1)
        val sourceSnapshotSha = sessionPrefs.getString(KEY_SOURCE_SNAPSHOT_SHA, "") ?: ""
        val createdAt = sessionPrefs.getLong(KEY_CREATED_AT_MILLIS, 0L)
        val schemaVersion = sessionPrefs.getInt(KEY_SCHEMA_VERSION, 1)

        return ExperimentalDatasetMetadata(
            datasetId = datasetId,
            databaseFilename = filename,
            foundationVersion = foundationVersion,
            sourceSnapshotSha256 = sourceSnapshotSha,
            createdAtMillis = createdAt,
            organizationSchemaVersion = schemaVersion
        )
    }

    override fun persistExperimentalSelection(metadata: ExperimentalDatasetMetadata) {
        sessionPrefs.edit()
            .putString(KEY_SELECTED_MODE, DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL.name)
            .putString(KEY_DATASET_ID, metadata.datasetId)
            .putString(KEY_DATABASE_FILENAME, metadata.databaseFilename)
            .putInt(KEY_FOUNDATION_VERSION, metadata.foundationVersion)
            .putString(KEY_SOURCE_SNAPSHOT_SHA, metadata.sourceSnapshotSha256)
            .putLong(KEY_CREATED_AT_MILLIS, metadata.createdAtMillis)
            .putInt(KEY_SCHEMA_VERSION, metadata.organizationSchemaVersion)
            .apply()
    }

    override fun clearExperimentalSelection() {
        sessionPrefs.edit()
            .putString(KEY_SELECTED_MODE, DatasetMode.LEGACY_PRODUCTION.name)
            .apply()
    }

    /**
     * Checks if the active legacy Pomodoro/stopwatch timer is running in default preferences.
     */
    fun isTimerActive(): Boolean {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        return defaultPrefs.getBoolean("pref_timer_is_running", false)
    }

    /**
     * Preflight check before activating experimental mode.
     */
    fun canActivateExperiment(): DatasetActivationResult {
        if (isTimerActive()) {
            return DatasetActivationResult.Blocked(
                "Нельзя перейти в эксперимент при работающем таймере. Пожалуйста, остановите или завершите текущую сессию таймера."
            )
        }
        val prodDbFile = DatabaseUtils.getDatabaseFile(context, DATABASE_FILENAME)
        if (!prodDbFile.exists() || prodDbFile.length() <= 0L) {
            return DatasetActivationResult.Blocked("Продуктовая база данных не найдена или пуста.")
        }
        return DatasetActivationResult.Success(DatasetSessionFactory.production())
    }

    /**
     * Checks if a valid existing experimental dataset is already present on device.
     */
    fun hasExistingExperimentalDataset(): Boolean {
        val metadata = loadExperimentalMetadata() ?: return false
        return validateExperimental(metadata) is DatasetValidationResult.Valid
    }

    /**
     * Re-activates an already existing, validated experimental dataset without recreating it from production.
     */
    fun activateExistingExperiment(): DatasetActivationResult {
        val preflight = canActivateExperiment()
        if (preflight !is DatasetActivationResult.Success) {
            return preflight
        }
        val metadata = loadExperimentalMetadata()
            ?: return DatasetActivationResult.Blocked("Экспериментальный датасет не найден.")
        return when (val validation = validateExperimental(metadata)) {
            is DatasetValidationResult.Valid -> {
                sessionPrefs.edit()
                    .putString(KEY_SELECTED_MODE, DatasetMode.CONTAINER_LOCAL_EXPERIMENTAL.name)
                    .apply()
                DatasetActivationResult.Success(DatasetSessionFactory.experimental(metadata))
            }
            is DatasetValidationResult.Invalid -> {
                DatasetActivationResult.ValidationFailed("Экспериментальный датасет повреждён: ${validation.reason}")
            }
            is DatasetValidationResult.Missing -> {
                DatasetActivationResult.Blocked("Файл экспериментального датасета отсутствует: ${validation.reason}")
            }
        }
    }

    /**
     * Full creation pipeline:
     * 1. Validate timer is not active
     * 2. Create staging snapshot from production DB (production remains untouched)
     * 3. Run PR3 legacy inventory & migration plan on staging copy
     * 4. Execute migration and validate semantic invariants (PR3 validator)
     * 5. Check PR4 backup readiness
     * 6. Atomically publish to experimental database file
     * 7. Initialize namespaced experimental preferences with non-sensitive UI settings
     * 8. Persist experimental selection
     */
    fun createAndActivateExperiment(): DatasetActivationResult {
        val preflight = canActivateExperiment()
        if (preflight !is DatasetActivationResult.Success) {
            return preflight
        }

        val cacheDir = context.cacheDir
        val stagingTempFile = File(cacheDir, "container_staging_${System.currentTimeMillis()}.db")
        if (stagingTempFile.exists()) stagingTempFile.delete()

        try {
            // Step 2: Create validated snapshot of production DB
            DatabaseUtils.createDatabaseSnapshot(context, stagingTempFile)

            // Step 3 & 4: Open staging copy and run PR3 migration
            val stagingSqlite = SQLiteDatabase.openDatabase(
                stagingTempFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            val stagingDb = AndroidDatabase(stagingSqlite)

            val inventory = try {
                RawLegacyInventoryReader.read(stagingDb)
            } catch (e: Exception) {
                stagingSqlite.close()
                stagingTempFile.delete()
                return DatasetActivationResult.MigrationFailed("Ошибка чтения исходных данных: ${e.message}")
            }

            val plan = LegacyContainerMigrationPlanner.plan(
                inventory = inventory,
                cutoverTimestamp = System.currentTimeMillis()
            )

            val execResult = LegacyContainerMigrationExecutor.execute(stagingDb, plan)
            if (execResult !is MigrationExecutionResult.Success) {
                stagingSqlite.close()
                stagingTempFile.delete()
                val issuesStr = (execResult as? MigrationExecutionResult.Failure)
                    ?.issues?.joinToString("; ") { it.toString() } ?: "Неизвестная ошибка"
                return DatasetActivationResult.MigrationFailed("Миграция отклонена: $issuesStr")
            }

            val validation = LegacyContainerMigrationValidator.validate(stagingDb, inventory, plan)
            if (validation !is MigrationValidationResult.Valid) {
                stagingSqlite.close()
                stagingTempFile.delete()
                val errorsStr = (validation as? MigrationValidationResult.Invalid)
                    ?.errors?.joinToString("; ") ?: "Ошибка валидации"
                return DatasetActivationResult.ValidationFailed("Семантическая валидация не пройдена: $errorsStr")
            }

            stagingSqlite.close()

            // Step 5: Publish atomic move to experimental DB location
            val targetExpFile = getExperimentalDatabaseFile()
            targetExpFile.parentFile?.mkdirs()
            cleanupSidecars(targetExpFile)

            Files.move(
                stagingTempFile.toPath(),
                targetExpFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )

            // Step 6: Persist metadata
            val metadata = ExperimentalDatasetMetadata(
                datasetId = plan.organizationState.datasetUuid,
                databaseFilename = DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME,
                foundationVersion = plan.organizationState.foundationVersion,
                sourceSnapshotSha256 = plan.sourceSnapshotSha256,
                createdAtMillis = System.currentTimeMillis()
            )
            persistExperimentalSelection(metadata)

            // Step 7: Initialize namespaced preferences with non-sensitive UI settings
            initializeExperimentalPreferences(metadata.datasetId)

            val session = DatasetSessionFactory.experimental(metadata)
            return DatasetActivationResult.Success(session)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create experimental dataset", e)
            stagingTempFile.delete()
            return DatasetActivationResult.MigrationFailed("Исключение при создании эксперимента: ${e.message}")
        }
    }

    override fun validateExperimental(metadata: ExperimentalDatasetMetadata): DatasetValidationResult {
        val file = getExperimentalDatabaseFile()
        if (!file.exists() || file.length() <= 0L) {
            return DatasetValidationResult.Missing("Файл базы данных отсутствует: ${file.name}")
        }

        return try {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val quickCheck = db.rawQuery("PRAGMA quick_check", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (quickCheck != "ok") {
                    return DatasetValidationResult.Invalid("SQLite quick_check failed: $quickCheck")
                }

                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
                val requiredOrgTables = listOf(
                    "OrganizationState",
                    "OrganizationChanges",
                    "Containers",
                    "HabitPlacements",
                    "LegacyBlockMap"
                )
                if (!tables.containsAll(requiredOrgTables)) {
                    return DatasetValidationResult.Invalid("Отсутствуют обязательные таблицы организации: ${requiredOrgTables - tables}")
                }

                val (storedUuid, storedMode) = db.rawQuery("SELECT dataset_uuid, mode FROM OrganizationState WHERE id = 1", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0) to cursor.getString(1)
                    } else {
                        null to null
                    }
                }

                if (storedUuid != metadata.datasetId) {
                    return DatasetValidationResult.Invalid("UUID датасета не совпадает: ожидался ${metadata.datasetId}, фактически $storedUuid")
                }
                if (storedMode != "CONTAINER_LOCAL") {
                    return DatasetValidationResult.Invalid("Режим датасета не CONTAINER_LOCAL: $storedMode")
                }

                DatasetValidationResult.Valid
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            DatasetValidationResult.Invalid("Ошибка инспекции базы данных: ${e.message}")
        }
    }

    fun getExperimentalDatabaseFile(): File {
        return DatabaseUtils.getDatabaseFile(context, DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME)
    }

    private fun initializeExperimentalPreferences(datasetId: String) {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val expPrefs = context.getSharedPreferences("uhabits_prefs_experimental_$datasetId", Context.MODE_PRIVATE)

        // Copy non-sensitive appearance preferences only
        val editor = expPrefs.edit()
        val nonSensitiveKeys = listOf(
            "pref_theme",
            "pref_pure_black",
            "pref_color_accent",
            "pref_habits_card_corner_radius",
            "pref_statistics_card_corner_radius",
            "pref_show_habit_card_borders",
            "pref_enable_day_tiers",
            "pref_disable_confetti_animation",
            "pref_first_weekday",
            "pref_developer"
        )
        for (key in nonSensitiveKeys) {
            val all = defaultPrefs.all
            if (all.containsKey(key)) {
                when (val v = all[key]) {
                    is Boolean -> editor.putBoolean(key, v)
                    is Int -> editor.putInt(key, v)
                    is Long -> editor.putLong(key, v)
                    is String -> editor.putString(key, v)
                }
            }
        }
        // Explicitly ensure sync is disabled in experimental preferences
        editor.putBoolean("pref_sync_enabled", false)
        // Mark first run as completed so IntroActivity does not launch
        editor.putBoolean("pref_first_run", false)
        editor.apply()
    }

    private fun recordStartupError(error: String) {
        sessionPrefs.edit().putString(KEY_LAST_STARTUP_ERROR, error).apply()
    }

    fun consumeStartupError(): String? {
        val error = sessionPrefs.getString(KEY_LAST_STARTUP_ERROR, null)
        if (error != null) {
            sessionPrefs.edit().remove(KEY_LAST_STARTUP_ERROR).apply()
        }
        return error
    }

    private fun cleanupSidecars(file: File) {
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    companion object {
        private const val TAG = "DatasetSessionManager"
        private const val PREFS_NAME = "dataset_session_prefs"

        private const val KEY_SELECTED_MODE = "dataset_selected_mode"
        private const val KEY_DATASET_ID = "dataset_id"
        private const val KEY_DATABASE_FILENAME = "dataset_database_filename"
        private const val KEY_FOUNDATION_VERSION = "dataset_foundation_version"
        private const val KEY_SOURCE_SNAPSHOT_SHA = "dataset_source_snapshot_sha"
        private const val KEY_CREATED_AT_MILLIS = "dataset_created_at_millis"
        private const val KEY_SCHEMA_VERSION = "dataset_schema_version"
        private const val KEY_LAST_STARTUP_ERROR = "dataset_last_startup_error"
    }
}
