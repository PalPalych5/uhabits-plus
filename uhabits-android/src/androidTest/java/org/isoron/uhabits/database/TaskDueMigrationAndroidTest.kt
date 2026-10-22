/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_VERSION
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run with TaskDueMigrationRunner: uses only a UUID database and no app component/preferences. */
@RunWith(AndroidJUnit4::class)
class TaskDueMigrationAndroidTest {
    @Test fun existingV30OpensWithCurrentDueSchemaWithoutLosingTaskFacts() {
        check(InstrumentationRegistry.getInstrumentation() is TaskDueMigrationRunner)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "pr31_due_${UUID.randomUUID()}.db"
        check(!context.getDatabasePath(filename).exists())
        try {
            val oldOpener = HabitsDatabaseOpener(context, filename, 30)
            try {
                val db = oldOpener.writableDatabase
                assertEquals(30, db.version)
                db.execSQL("INSERT INTO UserTasks(id,title,planned_date,deadline,created_at,updated_at) VALUES " +
                    "('plan','Plan','2029-12-31',NULL,1,1)," +
                    "('deadline','Deadline',NULL,1792756800123,1,1)," +
                    "('both','Both','2020-01-15',1792756800123,1,1)," +
                    "('invalid','Invalid','not-a-date',NULL,1,1)")
            } finally { oldOpener.close() }
            repeat(2) {
                val opener = HabitsDatabaseOpener(context, filename, DATABASE_VERSION)
                try {
                    val db = opener.writableDatabase
                    assertEquals(DATABASE_VERSION, db.version)
                    db.rawQuery("PRAGMA integrity_check", null).use { c ->
                        assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0))
                    }
                    db.rawQuery("SELECT id,due_kind,planned_date,due_time_millis,legacy_plan_review_pending FROM UserTasks ORDER BY id", null).use { c ->
                        assertEquals(4, c.count)
                        while (c.moveToNext()) {
                            when (c.getString(0)) {
                                "plan" -> { assertEquals("NONE", c.getString(1)); assertEquals("2029-12-31", c.getString(2)); assertEquals(1, c.getInt(4)) }
                                "invalid" -> { assertEquals("NONE", c.getString(1)); assertEquals("not-a-date", c.getString(2)); assertEquals(1, c.getInt(4)) }
                                "both" -> { assertEquals("TIMED", c.getString(1)); assertEquals("2020-01-15", c.getString(2)); assertEquals(1792756800123, c.getLong(3)); assertEquals(0, c.getInt(4)) }
                                "deadline" -> { assertEquals("TIMED", c.getString(1)); assertTrue(c.isNull(2)); assertEquals(1792756800123, c.getLong(3)); assertEquals(0, c.getInt(4)) }
                            }
                        }
                    }
                } finally { opener.close() }
            }
        } finally {
            // UUID name belongs exclusively to this test; SQLite removes its own sidecars.
            context.deleteDatabase(filename)
        }
    }
}
