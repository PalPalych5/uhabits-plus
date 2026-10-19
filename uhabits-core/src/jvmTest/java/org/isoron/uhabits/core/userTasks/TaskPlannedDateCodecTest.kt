/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks

import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.userTasks.sqlite.SQLiteUserTaskRepository
import org.isoron.uhabits.core.userTasks.sqlite.UserTaskSchema
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class TaskPlannedDateCodecTest {
    @Test fun mixedTaskListLoadsAndUnrelatedEditsPreserveInvalidRaw() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val repo = SQLiteUserTaskRepository(db)
            for (index in 0..3) repo.save(UserTask(TaskId("good-$index"), "Good $index",
                plannedDate = LocalDate(2026, 10, 19), deadline = 1792429200123L, createdAt = 1, updatedAt = 1))
            db.run("INSERT INTO UserTasks(id,title,planned_date,deadline,created_at,updated_at) VALUES ('damaged','Needs date correction','1792393200000',1792429200123,1,1)")
            val service = UserTaskServiceImpl(repo, { 2L }, { "unused" })
            assertEquals(5, repo.getAll().size)
            assertEquals(5, repo.getRoots().size)
            assertNotNull(repo.getById(TaskId("damaged")))
            assertEquals("1792393200000", repo.getById(TaskId("damaged"))!!.unsupportedPlannedDateRaw)
            service.updateTask(TaskId("good-0"), title = "Good edit")
            service.updateTask(TaskId("damaged"), title = "Damaged row title edit")
            assertEquals("Good edit", repo.getById(TaskId("good-0"))!!.title)
            assertEquals("1792393200000", db.querySingle("SELECT planned_date FROM UserTasks WHERE id='damaged'") { it.getText(0) })
            assertEquals(1792429200123L, repo.getById(TaskId("damaged"))!!.deadline)
            assertEquals(5, repo.getAll().size)
            service.toggleComplete(TaskId("damaged"))
            service.reorderTasks(listOf(TaskId("damaged"), TaskId("good-0")))
            repo.saveAll(repo.getAll())
            service.updateTask(TaskId("damaged"), containerId = org.isoron.uhabits.core.containers.ContainerId("section"))
            assertEquals("damaged", repo.getByContainer(org.isoron.uhabits.core.containers.ContainerId("section")).single().id.value)
            repo.save(repo.getById(TaskId("damaged"))!!.copy(parentTaskId = TaskId("good-0")))
            assertEquals("1792393200000", repo.getSubtasks(TaskId("good-0")).single().unsupportedPlannedDateRaw)
            assertEquals("1792393200000", db.querySingle("SELECT planned_date FROM UserTasks WHERE id='damaged'") { it.getText(0) })
        } finally { db.close() }
    }

    @Test fun legacyUtcEpochDaysMatchIndependentCalendarAcrossTimezones() {
        val oldZone = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "Pacific/Pago_Pago", "Pacific/Kiritimati", "America/New_York", "Asia/Novosibirsk")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (iso in listOf("1600-01-01", "1969-12-31", "1970-01-01", "2000-01-01", "2024-02-29", "2026-03-08", "2026-11-01", "9999-12-31")) {
                    val oracle = java.time.LocalDate.parse(iso)
                    val millis = oracle.toEpochDay() * 86400000L
                    val date = parseTaskPlannedDate(millis.toString())!!
                    assertEquals(iso, date.toIsoDateString())
                    assertEquals(millis, date.unixTime)
                }
            }
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun isoAndUnsetSemanticsRemainUnchanged() {
        assertNull(parseTaskPlannedDate(null))
        assertNull(parseTaskPlannedDate(""))
        assertEquals(LocalDate(1970, 1, 1), parseTaskPlannedDate("0"))
        assertEquals(LocalDate(1969, 12, 31), parseTaskPlannedDate("-86400000"))
        for (iso in listOf("2026-10-19", "2024-02-29", "2026-02-30", "+2026-1-1")) {
            assertEquals(parseIsoDateString(iso), parseTaskPlannedDate(iso))
            assertEquals("Do not silently rewrite legacy ISO raw", iso, normalizeTaskPlannedDateForSync(iso))
        }
        assertEquals("2026-10-19", normalizeTaskPlannedDateForSync("1792368000000"))
    }

    @Test fun unsupportedNumericValuesAreRejectedRatherThanRoundedOrNarrowed() {
        for (raw in listOf("1792393200000", "1792368000", "0.0", "1e12", "not-a-date",
            Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(),
            (Long.MAX_VALUE / 86400000L * 86400000L).toString(),
            (Long.MIN_VALUE / 86400000L * 86400000L).toString(),
            (java.time.LocalDate.of(1599, 12, 31).toEpochDay() * 86400000L).toString(),
            (java.time.LocalDate.of(10000, 1, 1).toEpochDay() * 86400000L).toString())) {
            org.junit.Assert.assertThrows(raw, IllegalArgumentException::class.java) { parseTaskPlannedDate(raw) }
        }
    }

    @Test fun unsupportedStoredValueAllowsEditWithoutErasingTheOriginalRow() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            db.run("INSERT INTO UserTasks(id,title,planned_date,deadline,created_at,updated_at) VALUES ('invalid','Original','1792393200000',1792429200123,1,1)")
            val service = UserTaskServiceImpl(SQLiteUserTaskRepository(db), { 2L }, { "unused" })
            service.updateTask(TaskId("invalid"), title = "Edited")
            assertEquals("Edited", db.querySingle("SELECT title FROM UserTasks WHERE id='invalid'") { it.getText(0) })
            assertEquals("1792393200000", db.querySingle("SELECT planned_date FROM UserTasks WHERE id='invalid'") { it.getText(0) })
            assertEquals(1792429200123L, db.querySingle("SELECT deadline FROM UserTasks WHERE id='invalid'") { it.getLong(0) })
        } finally { db.close() }
    }

    @Test fun explicitDateSelectionAndClearResolveOnlyTheAffectedRawValue() = runBlocking {
        val db = TestDatabaseHelper.createEmptyDatabase()
        try {
            UserTaskSchema.createSchema(db)
            val repo = SQLiteUserTaskRepository(db)
            val service = UserTaskServiceImpl(repo, { 2L }, { "unused" })
            for (id in listOf("replace", "clear", "untouched")) db.run("INSERT INTO UserTasks(id,title,planned_date,created_at,updated_at) VALUES ('$id','Repair','1792393200000',1,1)")
            service.updateTask(TaskId("replace"), plannedDate = LocalDate(2026, 10, 20))
            service.updateTask(TaskId("clear"), clearPlannedDate = true)
            assertNull(repo.getById(TaskId("replace"))!!.unsupportedPlannedDateRaw)
            assertEquals(LocalDate(2026, 10, 20), repo.getById(TaskId("replace"))!!.plannedDate)
            assertNull(repo.getById(TaskId("clear"))!!.unsupportedPlannedDateRaw)
            assertNull(db.querySingle("SELECT planned_date FROM UserTasks WHERE id='clear'") { it.getTextOrNull(0) })
            assertEquals("1792393200000", repo.getById(TaskId("untouched"))!!.unsupportedPlannedDateRaw)
            assertEquals(3, repo.getAll().size)
        } finally { db.close() }
    }
}
