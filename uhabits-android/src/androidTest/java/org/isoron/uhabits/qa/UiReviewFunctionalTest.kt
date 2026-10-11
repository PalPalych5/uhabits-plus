package org.isoron.uhabits.qa

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.tasks.TaskDetailBottomSheet
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.userTasks.TaskId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.regex.Pattern

/** Host runner executes the two methods in separate processes on the dedicated AVD. */
@RunWith(AndroidJUnit4::class)
class UiReviewFunctionalTest {
    @Test fun cancelDisposableFocus() {
        val component = UiReviewCaptureTest.initializeFixture()
        instrumentation.runOnMainSync {
            component.timerSessionManager.resetCurrent()
            assertFalse(component.timerSessionManager.snapshot().hasActiveSession)
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val taskId = TaskId("qa-task-1")
    private fun evidence() = File(context.getExternalFilesDir(null), "functional-expected.json")
    private fun clickText(text: String) {
        QaEmulatorUi.clearSystemUiError()
        val pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val menuItem = text in listOf("Сегодня", "Завтра", "Выбрать дату в календаре",
            "Очистить плановую дату", "Очистить дедлайн")
        val selector = if (menuItem) By.res("android", "text1").text(pattern)
            else if (text == "Начать") By.desc("Начать фокус") else By.text(pattern)
        val view = device.wait(Until.findObject(selector), 8000)
            ?: error("Missing rendered text: $text")
        view.click(); device.waitForIdle()
    }
    private fun clickId(id: String) {
        QaEmulatorUi.clearSystemUiError()
        val view = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/$id"))), 8000)
            ?: run {
                val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
                device.takeScreenshot(File(directory, "functional-missing-$id.png"))
                device.dumpWindowHierarchy(File(directory, "functional-missing-$id.xml"))
                error("Missing rendered control: $id")
            }
        view.click(); device.waitForIdle()
    }

    private fun selectCalendarDay(day: Int) {
        check(device.wait(Until.hasObject(By.res(Pattern.compile(".*:id/mtrl_calendar_main_pane"))), 8000))
        val cell = checkNotNull(device.wait(Until.findObject(By.text(day.toString())
            .clazz("android.widget.TextView").clickable(true).enabled(true)), 8000))
        cell.click()
        check(device.wait(Until.hasObject(By.res(Pattern.compile(".*:id/mtrl_picker_header_selection_text"))
            .textContains(day.toString())), 8000)) {
            "Calendar cell was not selected before confirmation: $day"
        }
    }

    @Test fun prepareAndSave() {
        val component = UiReviewCaptureTest.initializeFixture()
        val today = getToday()
        val tomorrow = today.plus(1)
        val facts = SQLiteFocusSessionRepository(component.db)
        val before = facts.getByTask(taskId).size
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity {
                it.navigate(MainDestination.TASKS)
                TaskDetailBottomSheet.newInstance(taskId).show(it.supportFragmentManager, "functional-detail")
            }
            device.waitForIdle()
            clickId("rowDetailPlannedDate"); clickText("Сегодня")
            assertEquals(today, component.userTaskRepository.getById(taskId)!!.plannedDate)
            clickId("rowDetailPlannedDate"); clickText("Выбрать дату в календаре")
            clickText("Отмена")
            assertEquals("Cancellation preserves the saved plan", today, component.userTaskRepository.getById(taskId)!!.plannedDate)
            clickId("rowDetailPlannedDate"); clickText("Выбрать дату в календаре")
            // A real calendar cell, followed by the explicit confirmation control.
            selectCalendarDay(15); clickText("Применить")
            val custom = LocalDate(today.year, today.month, 15)
            assertEquals(custom, component.userTaskRepository.getById(taskId)!!.plannedDate)
            clickId("rowDetailPlannedDate"); clickText("Очистить плановую дату")
            assertNull(component.userTaskRepository.getById(taskId)!!.plannedDate)
            clickId("rowDetailPlannedDate"); clickText("Завтра")
            assertEquals(tomorrow, component.userTaskRepository.getById(taskId)!!.plannedDate)
            clickId("rowDetailDeadline"); clickText("Сегодня")
            val deadlineToday = component.userTaskRepository.getById(taskId)!!.deadline
            assertNotNull(deadlineToday)
            clickId("rowDetailDeadline"); clickText("Выбрать дату в календаре"); clickText("Отмена")
            assertEquals(deadlineToday, component.userTaskRepository.getById(taskId)!!.deadline)
            clickId("rowDetailDeadline"); clickText("Выбрать дату в календаре")
            selectCalendarDay(15); clickText("Применить")
            val deadlineCustom = Calendar.getInstance().apply {
                timeInMillis = component.userTaskRepository.getById(taskId)!!.deadline!!
            }
            assertEquals(15, deadlineCustom.get(Calendar.DAY_OF_MONTH))
            clickId("rowDetailDeadline"); clickText("Очистить дедлайн")
            assertNull(component.userTaskRepository.getById(taskId)!!.deadline)
            clickId("rowDetailDeadline"); clickText("Завтра")
            val saved = component.userTaskRepository.getById(taskId)!!
            clickId("rowDetailFocus")
            assertFalse("Preparation must remain idle", component.timerSessionManager.snapshot().hasActiveSession)
            assertEquals(before, facts.getByTask(taskId).size)
            clickText("Начать")
            instrumentation.runOnMainSync {
                assertSame("Focus must retain the selected dataset component", component, HabitsApplication.component)
                val snapshot = component.timerSessionManager.snapshot()
                assertTrue("Explicit Start begins the timer: $snapshot", snapshot.hasActiveSession)
            }
            Thread.sleep(1200)
            instrumentation.runOnMainSync { component.timerSessionManager.pauseOrResumeCurrent() }
            instrumentation.runOnMainSync { component.timerSessionManager.pauseOrResumeCurrent() }
            instrumentation.runOnMainSync { component.timerSessionManager.finishCurrent() }
            assertFalse(component.timerSessionManager.snapshot().hasActiveSession)
            val completed = facts.getByTask(taskId)
            assertEquals(before + 1, completed.size)
            assertTrue(completed.last().durationMs > 0)
            evidence().writeText(JSONObject().put("datasetId", component.session.datasetId)
                .put("plannedDate", saved.plannedDate!!.daysSince2000).put("deadline", saved.deadline)
                .put("focusCount", completed.size).put("focusDuration", completed.sumOf { it.durationMs }).toString(2))
        }
    }

    @Test fun verifyAfterRestart() {
        check(InstrumentationRegistry.getArguments().getString("disposableAvd") == "UHabit_UI_Review_Disposable_20261011")
        val expected = JSONObject(evidence().readText())
        val component = HabitsApplication.component
        assertEquals(expected.getString("datasetId"), component.session.datasetId)
        assertTrue(component.session.isExperimental)
        val saved = component.userTaskRepository.getById(taskId)!!
        assertEquals(expected.getInt("plannedDate"), saved.plannedDate!!.daysSince2000)
        assertEquals(expected.getLong("deadline"), saved.deadline!!)
        val completed = SQLiteFocusSessionRepository(component.db).getByTask(taskId)
        assertEquals(expected.getInt("focusCount"), completed.size)
        assertEquals(expected.getLong("focusDuration"), completed.sumOf { it.durationMs })
        assertFalse(component.timerSessionManager.snapshot().hasActiveSession)
        File(context.getExternalFilesDir(null), "functional-restart-pass.json").writeText(
            expected.put("restartVerified", true).toString(2))
    }
}
