/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
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

package org.isoron.uhabits.production

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.preference.PreferenceManager
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.platform.io.getVersion
import org.isoron.uhabits.core.production.cutover.ProductionCutoverResult
import org.isoron.uhabits.core.production.cutover.ProductionCutoverService
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface CutoverPreflightResult {
    data object Ready : CutoverPreflightResult
    data object AlreadyCutover : CutoverPreflightResult
    data class Blocked(val reason: String) : CutoverPreflightResult
}

/**
 * Android companion manager orchestrating the protected production cutover.
 *
 * Responsibilities:
 * 1. Preflight checks (quiescent timer, v29 schema, file existence).
 * 2. Creating atomic rollback safety snapshot before migration.
 * 3. Delegating migration and semantic validation to [ProductionCutoverService].
 * 4. Automatic rollback restoration if migration fails.
 * 5. Persisting Production Next session and enforcing sync quarantine on success.
 */
class ProductionCutoverManager(
    private val context: Context,
    private val sessionManager: DatasetSessionManager = DatasetSessionManager(context),
    private val cutoverService: ProductionCutoverService = ProductionCutoverService(),
) {

    fun preflight(): CutoverPreflightResult {
        if (sessionManager.isProductionNextSelected()) {
            return CutoverPreflightResult.AlreadyCutover
        }
        if (sessionManager.isTimerActive()) {
            return CutoverPreflightResult.Blocked("Активная сессия таймера не завершена. Завершите таймер перед миграцией.")
        }
        val dbFile = DatabaseUtils.getDatabaseFile(context)
        if (!dbFile.exists()) {
            return CutoverPreflightResult.Blocked("Файл базы данных не найден.")
        }

        val version = try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                AndroidDatabase(db).getVersion()
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            return CutoverPreflightResult.Blocked("Ошибка чтения базы данных: ${t.message}")
        }

        if (version == 30) {
            return CutoverPreflightResult.AlreadyCutover
        }
        if (version != 29) {
            return CutoverPreflightResult.Blocked("Версия базы данных должна быть 29, текущая версия: $version")
        }

        return CutoverPreflightResult.Ready
    }

    @Synchronized
    fun executeCutover(cutoverTimestamp: Long = System.currentTimeMillis()): ProductionCutoverResult {
        val preflight = preflight()
        if (preflight is CutoverPreflightResult.Blocked) {
            return ProductionCutoverResult.Failure(
                stage = "PREFLIGHT",
                reason = preflight.reason
            )
        }
        if (preflight is CutoverPreflightResult.AlreadyCutover) {
            return ProductionCutoverResult.Failure(
                stage = "PREFLIGHT",
                reason = "База данных уже переведена на UHabit Next"
            )
        }

        val dbFile = DatabaseUtils.getDatabaseFile(context)
        val rollbackFile = File(requireNotNull(dbFile.parentFile), "${dbFile.name}.pre_cutover.rollback")

        // 1. Quiesce database handles and create safety rollback copy
        DatabaseUtils.closeDatabase()
        try {
            Files.copy(
                dbFile.toPath(),
                rollbackFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
        } catch (t: Throwable) {
            return ProductionCutoverResult.Failure(
                stage = "BACKUP",
                reason = "Не удалось создать резервную копию перед миграцией: ${t.message}",
                cause = t
            )
        }

        // 2. Open database and execute cutover
        val sqliteDb = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (t: Throwable) {
            rollbackFile.delete()
            return ProductionCutoverResult.Failure(
                stage = "OPEN",
                reason = "Не удалось открыть базу данных для миграции: ${t.message}",
                cause = t
            )
        }

        val result = try {
            val wrappedDb = AndroidDatabase(sqliteDb)
            cutoverService.executeCutover(
                db = wrappedDb,
                cutoverTimestamp = cutoverTimestamp,
                ownsTransaction = true
            )
        } finally {
            sqliteDb.close()
        }

        return when (result) {
            is ProductionCutoverResult.Success -> {
                Log.i(TAG, "Production cutover succeeded: ${result.metadata.datasetUuid}")
                // 3. Persist Production Next session mode
                sessionManager.persistProductionNextSelection(result.metadata.datasetUuid)

                // 4. Set sync quarantine invariant
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                sharedPrefs.edit()
                    .putBoolean("pref_sync_review_required", true)
                    .putString("pref_sync_review_reason", "Продуктовая база переведена на UHabit Next")
                    .commit()

                // 5. Cleanup rollback snapshot
                rollbackFile.delete()
                result
            }
            is ProductionCutoverResult.Failure -> {
                Log.e(TAG, "Production cutover failed at stage ${result.stage}: ${result.reason}. Performing rollback.")
                // Restore original v29 database
                try {
                    DatabaseUtils.closeDatabase()
                    DatabaseUtils.cleanupSidecars(dbFile)
                    Files.move(
                        rollbackFile.toPath(),
                        dbFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (rollbackError: Throwable) {
                    Log.e(TAG, "Critical rollback error after cutover failure", rollbackError)
                }
                sessionManager.clearProductionNextSelection()
                result
            }
        }
    }

    companion object {
        private const val TAG = "ProductionCutoverMgr"
    }
}
