package org.isoron.uhabits.activities.tasks

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.TaskDueDate
import org.isoron.uhabits.utils.DemoDataGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/** Explicitly opted-in disposable offline AVD only. Run again with tagMode=restart after process death. */
@RunWith(AndroidJUnit4::class)
class TaskTagsVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val component get() = HabitsApplication.component
    private val manifest get() = File(context.filesDir, "pr9-tag-fixture.properties")
    private val due = TaskDue.Day(TaskDueDate.parse("2026-10-24"))

    @Test fun seedPreUpgradeV32Fixture() {
        bootstrapDisposableSession()
        val harness = TaskDraftLifecycleTest()
        harness.guard()
        val habit = component.modelFactory.buildHabit()
        habit.name = "PR9 · Dev Upgrade Habit"
        component.habitList.add(habit)
        val task = component.userTaskRepository.getAll().find { it.title == "PR9 · Dev Upgrade Existing Task" }
            ?: component.userTaskService.createTask("PR9 · Dev Upgrade Existing Task", due = due)
        Properties().apply {
            setProperty("upgradeTask", task.id.value)
            setProperty("upgradeHabitName", habit.name)
        }.also { properties ->
            File(context.filesDir, "pr9-upgrade-v32.properties").outputStream().use {
                properties.store(it, "PR9 pre-upgrade v32 baseline fixture")
            }
        }
    }

    @Test fun tagsCrudFiltersVisualsAndRestart() {
        bootstrapDisposableSession()
        val harness = TaskDraftLifecycleTest()
        harness.guard()
        val upgradeManifest = File(context.filesDir, "pr9-upgrade-v32.properties")
        if (upgradeManifest.isFile && InstrumentationRegistry.getArguments().getString("tagMode") != "restart") {
            val props = Properties().also { p -> upgradeManifest.inputStream().use { p.load(it) } }
            val upgradedTask = component.userTaskRepository.getById(TaskId(props.getProperty("upgradeTask")))
            assertNotNull("Pre-upgrade v32 task must survive in-place v32->v33 APK upgrade", upgradedTask)
            assertEquals(due, upgradedTask!!.due)
            assertTrue(
                "Pre-upgrade v32 habit must survive in-place v32->v33 APK upgrade",
                component.habitList.any { it.name == props.getProperty("upgradeHabitName") },
            )
            // Clean up the upgrade verification task so visual list screenshots show the exact expected PR9 tasks
            component.userTaskService.deleteTask(upgradedTask.id, cascadeSubtasks = true)
        }
        if (InstrumentationRegistry.getArguments().getString("tagMode") == "restart") {
            verifyRetainedFixture()
            harness.QaActivityController().use { controller ->
                select(controller, emptySet())
                screenshot("restart-retained-list")
            }
            return
        }
        val tags = component.taskTagRepository
        val work = tags.create("Работа")
        val important = tags.create("Важно")
        val empty = tags.create("Когда-нибудь")
        fun seed(title: String, parent: TaskId? = null): UserTask =
            component.userTaskRepository.getAll().find { it.title == title }
                ?: component.userTaskService.createTask(title, due = due, parentTaskId = parent)
        val first = seed("PR9 · Подготовить материалы к встрече")
        val second = seed("PR9 · Проверить заметки")
        val child = seed("PR9 · Подзадача с тегом", first.id)
        tags.setTags(first.id, setOf(work.id, important.id))
        tags.setTags(second.id, setOf(work.id))
        tags.setTags(child.id, setOf(important.id))
        // CRUD must preserve links on rename and remove only the deleted tag's links.
        val temporary = tags.create("PR9 временный тег")
        tags.setTags(second.id, setOf(work.id, temporary.id))
        assertEquals(temporary.id, tags.rename(temporary.id, "PR9 переименованный тег").id)
        assertTrue(tags.getTagIds(second.id).contains(temporary.id))
        tags.delete(temporary.id)
        assertEquals(setOf(work.id), tags.getTagIds(second.id))
        val oldTheme = component.preferences.theme
        val oldBlack = component.preferences.isPureBlackEnabled
        try {
            harness.QaActivityController().use { controller ->
                for ((name, theme, black) in listOf(
                    Triple("light", ThemeSwitcher.THEME_LIGHT, false),
                    Triple("dark", ThemeSwitcher.THEME_DARK, false),
                    Triple("amoled", ThemeSwitcher.THEME_DARK, true),
                )) {
                    component.preferences.theme = theme
                    component.preferences.isPureBlackEnabled = black
                    controller.recreate()
                    select(controller, emptySet())
                    screenshot("$name-list")
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        TaskDetailBottomSheet.newInstance(first.id).show(fragment.childFragmentManager, "task_detail")
                        fragment.childFragmentManager.executePendingTransactions()
                    }
                    idle()
                    screenshot("$name-editor-multiple-tags")
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        val detail = fragment.childFragmentManager.findFragmentByTag("task_detail")!!
                        val add = descendants(detail.requireView()).first {
                            it.contentDescription?.toString() == "Добавить или выбрать теги"
                        }
                        assertTrue(add.performClick())
                        fragment.childFragmentManager.executePendingTransactions()
                    }
                    idle()
                    screenshot("$name-picker")
                    assertNotNull(device.findObject(By.desc("Тег: Работа")))
                    assertNotNull(device.findObject(By.desc("Тег: Важно")))
                    device.findObject(By.desc("Тег: Когда-нибудь")).click()
                    device.findObject(By.text("Применить")).click()
                    idle()
                    assertEquals("Picker Apply persists multiple selected tags", setOf(work.id, important.id, empty.id), tags.getTagIds(first.id))
                    controller.onActivity { activity ->
                        val fragment = tasks(activity)
                        val detail = fragment.childFragmentManager.findFragmentByTag("task_detail")!!
                        descendants(detail.requireView()).first {
                            it.contentDescription?.toString() == "Добавить или выбрать теги"
                        }.performClick()
                        detail.childFragmentManager.executePendingTransactions()
                    }
                    idle()
                    device.findObject(By.desc("Тег: Когда-нибудь")).click()
                    device.findObject(By.text("Применить")).click()
                    idle()
                    assertEquals("Picker removal preserves other links", setOf(work.id, important.id), tags.getTagIds(first.id))
                    controller.onActivity { activity ->
                        (tasks(activity).childFragmentManager.findFragmentByTag("task_detail") as TaskDetailBottomSheet).dismissNow()
                    }
                    select(controller, setOf(work.id, important.id))
                    assertRendered(controller, setOf(first.id))
                    screenshot("$name-filter-intersection")
                    controller.recreate()
                    awaitLoaded(controller)
                    assertRendered(controller, setOf(first.id))
                    select(controller, setOf(empty.id))
                    assertRendered(controller, emptySet())
                    screenshot("$name-filter-empty")
                    select(controller, emptySet())
                    assertEquals("Tag operations must preserve Due", due, component.userTaskRepository.getById(first.id)!!.due)
                    assertEquals(due, component.userTaskRepository.getById(second.id)!!.due)
                }
                val oldFontScale = context.resources.configuration.fontScale
                try {
                    device.executeShellCommand("settings put system font_scale 1.3")
                    device.executeShellCommand("wm size 720x1280")
                    device.executeShellCommand("wm density 320")
                    idle()
                    for ((name, theme, black) in listOf(
                        Triple("light", ThemeSwitcher.THEME_LIGHT, false),
                        Triple("dark", ThemeSwitcher.THEME_DARK, false),
                    )) {
                        component.preferences.theme = theme
                        component.preferences.isPureBlackEnabled = black
                        controller.recreate()
                        select(controller, setOf(work.id))
                        val filterChip = requireNotNull(device.findObject(By.res("tvTasksTagFilter")))
                        val tagLabel = "#Работа"
                        assertTrue(filterChip.hasObject(By.textContains(tagLabel)) || filterChip.text.orEmpty().contains(tagLabel))
                        assertVisibleTagMetadata(tagLabel)
                        screenshot("font130-$name-list")
                        select(controller, emptySet())
                    }
                } finally {
                    device.executeShellCommand("settings put system font_scale $oldFontScale")
                    device.executeShellCommand("wm size reset")
                    device.executeShellCommand("wm density reset")
                    idle()
                }
            }
            Properties().apply {
                setProperty("dataset", component.session.datasetId)
                setProperty("first", first.id.value)
                setProperty("second", second.id.value)
                setProperty("work", work.id)
                setProperty("important", important.id)
            }.also { properties -> manifest.outputStream().use { properties.store(it, "PR9 disposable QA retained fixture") } }
            verifyRetainedFixture()
        } finally {
            component.preferences.theme = oldTheme
            component.preferences.isPureBlackEnabled = oldBlack
        }
    }

    private fun verifyRetainedFixture() {
        check(manifest.isFile) { "Restart mode requires the prior seeded run; do not bootstrap fresh data" }
        val properties = Properties().also { p -> manifest.inputStream().use { p.load(it) } }
        assertEquals(properties.getProperty("dataset"), component.session.datasetId)
        val first = TaskId(properties.getProperty("first"))
        val second = TaskId(properties.getProperty("second"))
        assertEquals(setOf(properties.getProperty("work"), properties.getProperty("important")), component.taskTagRepository.getTagIds(first))
        assertEquals(setOf(properties.getProperty("work")), component.taskTagRepository.getTagIds(second))
        assertEquals(due, component.userTaskRepository.getById(first)!!.due)
        assertEquals(due, component.userTaskRepository.getById(second)!!.due)
    }

    private fun select(controller: TaskDraftLifecycleTest.QaActivityController, filter: Set<String>) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.TASKS)
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = tasks(activity)
            set(fragment, "activeSmartView", TaskSmartView.ALL)
            set(fragment, "selectedContainerFilter", null)
            set(fragment, "selectedTagFilter", filter)
            invoke(fragment, "loadTasks")
        }
        awaitLoaded(controller)
        controller.onActivity { tasks(it).taskListState.requestScrollToItem(0) }
        idle()
    }

    private fun awaitLoaded(controller: TaskDraftLifecycleTest.QaActivityController) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            idle()
            var loaded = false
            controller.onActivity { loaded = field<kotlinx.coroutines.Job?>(tasks(it), "loadJob")?.isActive != true }
            if (loaded) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Tag-filtered list did not settle")
    }

    private fun assertRendered(controller: TaskDraftLifecycleTest.QaActivityController, expected: Set<TaskId>) {
        controller.onActivity { activity ->
            val fragment = tasks(activity)
            val ids = fragment.taskListContent?.items.orEmpty()
                .filterIsInstance<TaskListItem.Row>().map { it.task.id }
            assertEquals("Tag filter must contain only matching root tasks", expected, ids.toSet())
            assertEquals("Tag filter must not duplicate tasks", ids.size, ids.distinct().size)
        }
    }

    @Suppress("DEPRECATION")
    private fun assertVisibleTagMetadata(label: String) {
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val list = accessibilityNodes(root).first { it.viewIdResourceName == "tasksList" }
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        val metas = accessibilityNodes(list).filter { node ->
            node.isVisibleToUser && node.text?.contains(label) == true && key in node.availableExtraData
        }.toList()
        assertTrue("1.3x list must render visible tag metadata", metas.isNotEmpty())
        for (meta in metas) {
            val length = requireNotNull(meta.text).length
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            }
            assertTrue("Tag text must expose measured geometry", meta.refreshWithExtraData(key, args))
            val characters = requireNotNull(meta.extras.getParcelableArray(key)).filterIsInstance<RectF>()
            assertEquals("Tag text must expose all character bounds", length, characters.size)
            assertTrue("Tag metadata must fit within two lines", characters.map { it.top }.distinct().size in 1..2)
            val bounds = Rect().also(meta::getBoundsInScreen)
            assertTrue("Tag metadata must not clip vertically", characters.all {
                it.top >= bounds.top - 1 && it.bottom <= bounds.bottom + 1
            })
        }
    }

    private fun accessibilityNodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            yieldAll(accessibilityNodes(child))
        }
    }

    private fun bootstrapDisposableSession() {
        val authorized = InstrumentationRegistry.getArguments().getString("authorizedAvd")
        check(authorized?.startsWith("UHabit_Temp_") == true)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == authorized)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("input keyevent 82")
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 1 ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0) {
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            device.executeShellCommand("settings put global airplane_mode_on 1")
            device.executeShellCommand("settings put global wifi_on 0")
        }
        if (component.session.isExperimental) return
        check(InstrumentationRegistry.getArguments().getString("tagMode") != "restart") {
            "Restart must reopen the existing experimental selection"
        }
        val app = context.applicationContext as HabitsApplication
        val metadata = ExperimentalDatasetMetadata("qa-temp-pr9", DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME,
            1, "", System.currentTimeMillis())
        val session = DatasetSessionFactory.experimental(metadata)
        File(context.filesDir, DemoDataGenerator.DISPOSABLE_MARKER + '-' + session.datasetId).writeText(session.databaseFilename)
        instrumentation.runOnMainSync { app.switchDatasetSession(session) }
        val statement = component.db.prepareStatement("UPDATE OrganizationState SET dataset_uuid = ?, mode = 'CONTAINER_LOCAL' WHERE id = 1")
        try { statement.bindText(1, session.datasetId); statement.step() } finally { statement.finalize() }
        component.preferences.isFirstRun = false
        app.sessionManager.persistExperimentalSelection(metadata)
    }

    private fun screenshot(name: String) {
        idle()
        SystemClock.sleep(400)
        val file = File(context.getExternalFilesDir(null), "pr9/$name.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        check(device.takeScreenshot(file))
    }
    private fun idle() { instrumentation.waitForIdleSync(); device.waitForIdle() }
    private fun tasks(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag("main.tasks") as TasksFragment
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun set(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value) }
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun invoke(instance: Any, name: String) = instance.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
}
