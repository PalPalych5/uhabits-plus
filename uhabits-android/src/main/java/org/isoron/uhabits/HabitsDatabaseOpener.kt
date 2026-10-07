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

class HabitsDatabaseOpener(
    private val context: Context,
    private val databaseFilename: String,
    private val version: Int
) : SQLiteOpenHelper(context, databaseFilename, null, version) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.disableWriteAheadLogging()
        db.version = 8
        onUpgrade(db, -1, version)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.disableWriteAheadLogging()
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        db.disableWriteAheadLogging()
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
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        throw UnsupportedDatabaseVersionException()
    }
}
