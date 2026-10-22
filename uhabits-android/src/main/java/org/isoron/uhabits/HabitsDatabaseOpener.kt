/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.database.AndroidDatabase

class HabitsDatabaseOpener @JvmOverloads constructor(
    private val context: Context,
    private val databaseFilename: String,
    private val version: Int,
    val isWalRequested: Boolean = defaultWalEnabled(databaseFilename, version)
) : SQLiteOpenHelper(context, databaseFilename, null, version) {

    init {
        if (isWalRequested) {
            setWriteAheadLoggingEnabled(true)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        if (isWalRequested) {
            db.enableWriteAheadLogging()
        }
    }

    companion object {
        fun defaultWalEnabled(databaseFilename: String, version: Int): Boolean {
            val isNext = version >= 30 || org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.isCutoverAuthorized(databaseFilename)
            return isNext && org.isoron.uhabits.utils.DatabaseUtils.enableWalForNext
        }

        fun hasEvidenceOfExistingDataset(context: Context, databaseFilename: String): Boolean {
            val dbFile = context.getDatabasePath(databaseFilename)
            val parent = dbFile.parentFile
            if (parent != null) {
                if (java.io.File(parent, "$databaseFilename.rollback").exists()) return true
                if (java.io.File(parent, "$databaseFilename.restore.state").exists()) return true
                if (java.io.File(parent, "$databaseFilename.restore.tmp").exists()) return true
                val recoveryFiles = parent.listFiles { file ->
                    file.name.startsWith("$databaseFilename.recovery.") || file.name == "$databaseFilename.invalid"
                }
                if (!recoveryFiles.isNullOrEmpty()) return true
            }

            val recoveryDir = java.io.File(context.noBackupFilesDir, "production-recovery")
            if (java.io.File(recoveryDir, "preparation.state").exists()) return true

            val canonicalName = org.isoron.uhabits.core.DATABASE_FILENAME
            if (databaseFilename == canonicalName || databaseFilename.endsWith("/$canonicalName") || databaseFilename.endsWith("\\$canonicalName")) {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val hasPrefs = prefs.contains("last_app_version") ||
                    prefs.contains("pref_sync_device_id") ||
                    prefs.contains("pref_first_weekday")
                if (hasPrefs) return true
            }

            return false
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        if (isWalRequested) {
            db.enableWriteAheadLogging()
        } else {
            db.disableWriteAheadLogging()
        }
        if (hasEvidenceOfExistingDataset(context, databaseFilename)) {
            throw IllegalStateException(
                "Refusing to create empty database for '$databaseFilename': evidence of existing dataset or interrupted restore found."
            )
        }
        db.version = 8
        onUpgrade(db, -1, version)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (isWalRequested) {
            db.enableWriteAheadLogging()
        } else {
            db.disableWriteAheadLogging()
        }
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (isWalRequested) {
            db.enableWriteAheadLogging()
        } else {
            db.disableWriteAheadLogging()
        }
        if (db.version < 8) throw UnsupportedDatabaseVersionException()
        if (newVersion >= 30 && !org.isoron.uhabits.core.production.cutover.ProductionCutoverGate.isCutoverAuthorized(databaseFilename)) {
            throw org.isoron.uhabits.core.production.cutover.CutoverNotAuthorizedException(databaseFilename)
        }
        val wrappedDb = AndroidDatabase(db)
        wrappedDb.setVersion(db.version)
        val intermediateTarget = if (newVersion >= 30 && oldVersion < 29) 29 else if (newVersion < 30) newVersion else 29
        if (wrappedDb.getVersion() < intermediateTarget) {
            runBlocking {
                wrappedDb.migrateTo(intermediateTarget) { version ->
                    val filename = "%02d.sql".format(version)
                    context.assets.open("migrations/$filename").bufferedReader().readText()
                }
            }
        }
        if (newVersion >= 30 && wrappedDb.getVersion() == 29) {
            val result = org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator.migrate(
                db = wrappedDb,
                cutoverTimestamp = System.currentTimeMillis(),
                ownsTransaction = false
            )
            if (result is org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult.Failure) {
                throw IllegalStateException("Version 30 schema migration failed: ${result.reason}", result.cause)
            }
        }
        if (newVersion >= 31 && wrappedDb.getVersion() < newVersion) {
            runBlocking {
                wrappedDb.migrateTo(newVersion) { version ->
                    val filename = "%02d.sql".format(version)
                    context.assets.open("migrations/$filename").bufferedReader().readText()
                }
            }
        }
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        throw UnsupportedDatabaseVersionException()
    }
}
