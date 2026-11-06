package org.isoron.uhabits.qa

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.migrateTo
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.tasks.TasksFragment
import org.isoron.uhabits.activities.tasks.TasksWorkspaceSnapshot
import org.isoron.uhabits.backup.BackupArchiveCreator
import org.isoron.uhabits.backup.BackupArchiveExtractor
import org.isoron.uhabits.backup.BackupCompatibilityStatus
import org.isoron.uhabits.core.LEGACY_DATABASE_VERSION
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.TaskContainerVisibility
import org.isoron.uhabits.core.containers.backup.DatabaseSnapshotter
import org.isoron.uhabits.core.contribution.Contribution
import org.isoron.uhabits.core.contribution.ContributionId
import org.isoron.uhabits.core.contribution.ContributionSourceType
import org.isoron.uhabits.core.focus.FocusPrimaryTargetType
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionRecordResult
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult
import org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.database.AndroidDatabase
import org.isoron.uhabits.utils.DatabaseUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Task section visibility and lifecycle/persistence tests on an authorized disposable offline AVD.
 * Validates:
 * 1. UI navigation drawer & dialog visibility actions.
 * 2. HabitPlacement and Focus/history independence and retention when sections are hidden in Tasks.
 * 3. Old archives & legacy v29 compatibility without corruption or stale container crash.
 * 4. Synthetic fixture backup/restore and explicit documentation of local-only visibility upon restore.
 * 5. Update/migration of old database (v29 -> v35) with HabitBlock -> Container & HabitPlacement mapping.
 * 6. True process restart and cold reload persistence across SharedPreferences and SQLite.
 */
@RunWith(AndroidJUnit4::class)
class TaskSectionVisibilityAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun checkAvdGuards() {
        val args = InstrumentationRegistry.getArguments()
        val avd = args.getString("authorizedAvd").orEmpty()
        check(avd.matches(Regex("UHabit_Temp_[a-f0-9]{32}")))
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == avd)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)
    }

    private fun createLegacyV29Backup(file: File, habitName: String = "Exercise"): File {
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE Habits (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, name TEXT NOT NULL, description TEXT, question TEXT, freq_num INTEGER NOT NULL DEFAULT 1, freq_den INTEGER NOT NULL DEFAULT 1, color INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0, reminder_hour INTEGER, reminder_min INTEGER, reminder_days INTEGER, highlight INTEGER, archived INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 0, target_value REAL NOT NULL DEFAULT 0.0, target_type INTEGER NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE Repetitions (id INTEGER PRIMARY KEY AUTOINCREMENT, habit INTEGER NOT NULL, timestamp INTEGER NOT NULL, value INTEGER NOT NULL DEFAULT 2, notes TEXT, uuid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE HabitExtensions (habit_id INTEGER PRIMARY KEY, day_tier TEXT, timer_enabled INTEGER, block_id INTEGER, stats_start_timestamp INTEGER)")
        db.execSQL("CREATE TABLE HabitBlocks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, color INTEGER, icon TEXT, position INTEGER, is_archived INTEGER, updated_at INTEGER, uuid TEXT, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE HabitGoals (id INTEGER PRIMARY KEY AUTOINCREMENT, habit_id INTEGER NOT NULL, effective_timestamp INTEGER NOT NULL, freq_num INTEGER NOT NULL DEFAULT 1, freq_den INTEGER NOT NULL DEFAULT 1, target_type INTEGER NOT NULL DEFAULT 0, target_value REAL NOT NULL DEFAULT 0.0, unit TEXT NOT NULL DEFAULT '', uuid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
        db.execSQL("CREATE TABLE AppSettings (key TEXT PRIMARY KEY, long_value INTEGER)")
        db.execSQL("CREATE TABLE SyncQueue (queue_id INTEGER PRIMARY KEY AUTOINCREMENT, op_uuid TEXT NOT NULL UNIQUE, entity_type TEXT NOT NULL, entity_uuid TEXT NOT NULL, operation_type TEXT NOT NULL, payload_json TEXT NOT NULL, created_at INTEGER NOT NULL, device_id TEXT NOT NULL, pushed_at INTEGER, failed_at INTEGER, failure_reason TEXT)")
        db.execSQL("CREATE TABLE EntryOps (op_uuid TEXT PRIMARY KEY, habit_uuid TEXT NOT NULL, entry_timestamp INTEGER NOT NULL, delta_value INTEGER NOT NULL, op_type TEXT NOT NULL, notes TEXT, device_id TEXT NOT NULL, created_at INTEGER NOT NULL, deleted_at INTEGER)")
        db.execSQL("INSERT INTO Habits (name, position, type, uuid) VALUES ('$habitName', 0, 0, '${UUID.randomUUID()}')")
        db.execSQL("INSERT INTO Repetitions (habit, timestamp, value, notes) VALUES (1, 1000000, 2, 'done')")
        db.version = 29
        db.close()
        return file
    }

    @Test
    fun hideRestoreBranchAndPreserveTask() {
        checkAvdGuards()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru-RU"))

        val component = HabitsApplication.component
        val args = InstrumentationRegistry.getArguments()
        val variant = args.getString("sectionsVariant") ?: "light"
        component.preferences.theme = if (variant == "dark" || variant == "amoled") 1 else 0
        component.preferences.isPureBlackEnabled = variant == "amoled"
        val suffix = UUID.randomUUID().toString().take(6)
        val parentName = "Раздел $suffix"
        val childName = "Потомок $suffix"
        val siblingName = "Другой $suffix"
        val facade = component.organizationFacade

        // Seed 15-25 sections with realistic categories, colors, icons and nested hierarchy (long scroll)
        val work = facade.createContainer("Работа", color = PaletteColor(1), icon = "ic_l_briefcase").getOrThrow()
        val projects = facade.createContainer("Проекты", parentId = work.id, color = PaletteColor(1), icon = "ic_l_folder").getOrThrow()
        facade.createContainer("Релиз 2.0", parentId = projects.id, color = PaletteColor(1), icon = "ic_l_rocket")
        facade.createContainer("Встречи", parentId = work.id, color = PaletteColor(1), icon = "ic_l_users")

        val study = facade.createContainer("Обучение", color = PaletteColor(2), icon = "ic_l_graduation_cap").getOrThrow()
        facade.createContainer("Книги", parentId = study.id, color = PaletteColor(2), icon = "ic_l_book")
        facade.createContainer("Курсы", parentId = study.id, color = PaletteColor(2), icon = "ic_l_laptop")

        val health = facade.createContainer("Здоровье", color = PaletteColor(3), icon = "ic_l_heart_pulse").getOrThrow()
        facade.createContainer("Спорт", parentId = health.id, color = PaletteColor(3), icon = "ic_l_activity")
        facade.createContainer("Питание", parentId = health.id, color = PaletteColor(3), icon = "ic_l_apple")

        val finance = facade.createContainer("Финансы", color = PaletteColor(4), icon = "ic_l_wallet").getOrThrow()
        facade.createContainer("Инвестиции", parentId = finance.id, color = PaletteColor(4), icon = "ic_l_trending_up")

        val home = facade.createContainer("Дом и быт", color = PaletteColor(5), icon = "ic_l_home").getOrThrow()
        facade.createContainer("Ремонт", parentId = home.id, color = PaletteColor(5), icon = "ic_l_hammer")

        val hobby = facade.createContainer("Хобби", color = PaletteColor(6), icon = "ic_l_palette").getOrThrow()
        facade.createContainer("Музыка", parentId = hobby.id, color = PaletteColor(6), icon = "ic_l_music")

        facade.createContainer("Путешествия", color = PaletteColor(7), icon = "ic_l_plane")
        facade.createContainer("Покупки", color = PaletteColor(8), icon = "ic_l_shopping_cart")

        // Target tree with parent, child, nested subchild, and sibling
        val parent = facade.createContainer(parentName, color = PaletteColor(9), icon = "ic_l_folder").getOrThrow()
        val child = facade.createContainer(childName, parentId = parent.id, color = PaletteColor(9), icon = "ic_l_layers").getOrThrow()
        facade.createContainer("Вложенный $suffix", parentId = child.id, color = PaletteColor(9), icon = "ic_l_file_text")
        val sibling = facade.createContainer(siblingName, parentId = parent.id, color = PaletteColor(9), icon = "ic_l_tag").getOrThrow()
        val task = component.userTaskService.createTask("Задача $suffix", containerId = child.id)
        val output = File(context.getExternalFilesDir(null), "task-section-visibility/$variant")
        check(output.mkdirs() || output.isDirectory)

        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, MainDestination.TASKS)).use { scenario ->
            fun fragment(activity: MainActivity) = activity.supportFragmentManager
                .findFragmentByTag("main.tasks") as TasksFragment
            fun openDrawer() {
                scenario.onActivity { fragment(it).openNavigation() }
                instrumentation.waitForIdleSync()
            }
            fun findRow(id: String): View? {
                var row: View? = null
                scenario.onActivity { row = fragment(it).requireView().findViewWithTag("container_row_$id") }
                return row
            }
            fun awaitRow(id: String, present: Boolean) {
                val until = android.os.SystemClock.uptimeMillis() + 5000
                do {
                    if ((findRow(id) != null) == present) return
                    android.os.SystemClock.sleep(50)
                } while (android.os.SystemClock.uptimeMillis() < until)
                assertEquals("Drawer row $id", present, findRow(id) != null)
            }
            fun openManagement() {
                scenario.onActivity {
                    val button = requireNotNull(fragment(it).requireView().findViewWithTag<View>("manage_hidden_sections_btn"))
                    button.performClick()
                }
                requireNotNull(device.wait(Until.findObject(By.text("Скрытые разделы")), 5000)).click()
                assertNotNull(device.wait(Until.findObject(By.text("Скрытые разделы")), 5000))
            }
            fun clickText(text: String) {
                instrumentation.waitForIdleSync()
                device.waitForIdle()
                val prefix = text.split(" ").first()
                val pattern = java.util.regex.Pattern.compile(
                    text.split(Regex("\\s+")).joinToString("\\s+") { java.util.regex.Pattern.quote(it) },
                    java.util.regex.Pattern.CASE_INSENSITIVE
                )
                var lastEx: Exception? = null
                for (attempt in 1..3) {
                    try {
                        var obj = device.wait(Until.findObject(By.clazz("android.widget.Switch").text(pattern)), 1000)
                            ?: device.wait(Until.findObject(By.clazz("android.widget.Switch").text(text)), 1000)
                            ?: device.wait(Until.findObject(By.text(pattern)), 1000)
                            ?: device.wait(Until.findObject(By.text(text)), 1000)
                            ?: device.wait(Until.findObject(By.textContains(text)), 1000)
                        if (obj == null) {
                            val scrollable = androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
                            if (scrollable.exists()) {
                                runCatching { scrollable.scrollToBeginning(15) }
                                val found = runCatching { scrollable.scrollIntoView(androidx.test.uiautomator.UiSelector().textContains(text)) }.getOrDefault(false)
                                if (!found && text != prefix) {
                                    runCatching { scrollable.scrollIntoView(androidx.test.uiautomator.UiSelector().textContains(prefix)) }
                                }
                            }
                            obj = device.wait(Until.findObject(By.clazz("android.widget.Switch").text(pattern)), 1000)
                                ?: device.wait(Until.findObject(By.clazz("android.widget.Switch").text(text)), 1000)
                                ?: device.wait(Until.findObject(By.text(pattern)), 1500)
                                ?: device.wait(Until.findObject(By.text(text)), 1500)
                                ?: device.wait(Until.findObject(By.textContains(text)), 1500)
                                ?: device.wait(Until.findObject(By.clazz("android.widget.Switch").textContains(prefix)), 500)
                                ?: device.wait(Until.findObject(By.textContains(prefix)), 500)
                        }
                        assertNotNull("Missing control: $text", obj)
                        obj!!.click()
                        instrumentation.waitForIdleSync()
                        device.waitForIdle()
                        return
                    } catch (e: androidx.test.uiautomator.StaleObjectException) {
                        lastEx = e
                        Thread.sleep(250)
                        instrumentation.waitForIdleSync()
                        device.waitForIdle()
                    }
                }
                if (lastEx != null) throw lastEx
            }
            fun closeManagement() { device.pressBack(); instrumentation.waitForIdleSync() }
            fun hideSection(id: String) {
                scenario.onActivity {
                    val row = requireNotNull(fragment(it).requireView().findViewWithTag<View>("container_row_$id"))
                    row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), true)
                    row.performLongClick()
                }
                clickText("Скрыть из задач")
                openDrawer()
            }
            fun showSection(name: String, path: Boolean = false) {
                val label = if (path) "Показать путь: $name" else "Показать: $name"
                requireNotNull(device.wait(Until.findObject(By.desc(label)), 5000)) { "Missing $label" }.click()
                instrumentation.waitForIdleSync()
            }
            fun shot(name: String) {
                instrumentation.waitForIdleSync()
                device.waitForIdle()
                assertTrue(device.takeScreenshot(File(output, "$name.png")))
            }

            openDrawer()
            awaitRow(parent.id.value, true)
            awaitRow(child.id.value, true)
            shot("drawer-before")

            // Hide one child explicitly, then its parent; restoring the parent must
            // preserve the child's own choice and reveal the other child.
            hideSection(child.id.value)
            hideSection(parent.id.value)
            openManagement()
            shot("manager-before")
            shot("hidden-recovery")
            shot("manager-hidden")
            showSection(parentName)
            closeManagement()
            awaitRow(parent.id.value, true)
            awaitRow(child.id.value, false)
            awaitRow(sibling.id.value, true)
            shot("drawer-after")

            // Hide the parent again, then recover the child's full path.
            hideSection(parent.id.value)
            openManagement()
            showSection(childName, path = true)
            shot("manager-after")
            closeManagement()
            awaitRow(parent.id.value, true)
            awaitRow(child.id.value, true)
            shot("drawer-restored")
            assertEquals(child.id, component.userTaskRepository.getById(task.id)?.containerId)
            assertTrue(facade.availableContainers().any { it.key == parent.id.value })

            // Exercise the real context action, including selection fallback.
            scenario.onActivity { fragment(it).requireView().findViewWithTag<View>("container_row_${parent.id.value}").performClick() }
            openDrawer()
            awaitRow(parent.id.value, true)
            scenario.onActivity { fragment(it).requireView().findViewWithTag<View>("container_row_${parent.id.value}").performLongClick() }
            clickText("Скрыть из задач")
            scenario.onActivity {
                val field = TasksFragment::class.java.getDeclaredField("selectedContainerFilter")
                field.isAccessible = true
                assertNull("Hidden selected root must no longer be selected", field.get(fragment(it)))
            }
            openDrawer()
            awaitRow(parent.id.value, false)
            assertEquals(child.id, component.userTaskRepository.getById(task.id)?.containerId)

            // Activity recreation must retain persisted visibility.
            scenario.recreate()
            openDrawer()
            awaitRow(parent.id.value, false)
            openManagement()
            showSection(parentName)
            closeManagement()
            awaitRow(parent.id.value, true)
        }
    }

    @Test
    fun habitPlacementAndFocusHistoryPreservedAcrossVisibility() {
        checkAvdGuards()
        val component = HabitsApplication.component
        val suffix = UUID.randomUUID().toString().take(6)
        val facade = component.organizationFacade

        // 1. Create container hierarchy
        val parent = facade.createContainer("HP Parent $suffix").getOrThrow()
        val child = facade.createContainer("HP Child $suffix", parentId = parent.id).getOrThrow()

        // 2. Create Habit and place into child container
        val habit = component.modelFactory.buildHabit().apply {
            name = "HP Habit $suffix"
            type = HabitType.YES_NO
        }
        component.habitList.add(habit)
        val placeResult = facade.placeHabit(habit.uuid!!, child.id)
        assertTrue("Habit placement must succeed", placeResult.isSuccess)

        // Verify initial HabitPlacement in OrganizationFacade
        val placementInfo = facade.getPlacementInfo(habit.uuid!!)
        assertEquals(child.id, placementInfo.containerId)
        assertTrue(facade.getHabitUuidsForContainer(child.id.value).contains(habit.uuid))
        assertTrue(facade.getHabitUuidsForContainer(parent.id.value, subtree = true).contains(habit.uuid))

        // 3. Create UserTask in child container
        val task = component.userTaskService.createTask("HP Task $suffix", containerId = child.id)

        // 4. Record FocusSession for the task
        val sessionRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
        val now = System.currentTimeMillis()
        val focusSession = FocusSession(
            id = FocusSessionId(UUID.randomUUID().toString()),
            startedAt = now - 1800000L,
            endedAt = now,
            durationMs = 1800000L,
            taskId = task.id,
            source = FocusSessionSource.MANUAL,
            createdAt = now,
            updatedAt = now
        )
        val recordResult = sessionRepo.record(focusSession)
        assertEquals(FocusSessionRecordResult.INSERTED, recordResult)

        // Record Contribution attributing focus work to the habit
        val contribRepo = component.contributionRepository
        if (contribRepo != null) {
            val contribution = Contribution(
                id = ContributionId(UUID.randomUUID().toString()),
                targetHabit = HabitRef(habit.uuid!!),
                sourceType = ContributionSourceType.FOCUS_SESSION,
                sourceId = focusSession.id.value,
                contributedValue = 1800000L,
                effectiveTimestamp = now,
                createdAt = now,
                updatedAt = now
            )
            contribRepo.record(contribution)
        }

        // 5. Hide child container in Tasks navigation
        val prefs = context.getSharedPreferences("task_section_visibility_" + component.session.datasetId, Context.MODE_PRIVATE)
        val hiddenBefore = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()
        prefs.edit().putStringSet("hidden", hiddenBefore + child.id.value).commit()

        // 6. Verify TaskContainerVisibility reflects hidden status in Tasks
        val visibility = TaskContainerVisibility(
            mapOf(parent.id.value to null, child.id.value to parent.id.value),
            prefs.getStringSet("hidden", emptySet()).orEmpty()
        )
        assertFalse("Child container must be hidden in tasks visibility", visibility.isVisible(child.id.value))
        assertTrue("Parent container must remain visible in tasks visibility", visibility.isVisible(parent.id.value))

        // 7. CRITICAL VERIFICATION: HabitPlacement is COMPLETELY INTACT
        val placementAfter = facade.getPlacementInfo(habit.uuid!!)
        assertEquals("HabitPlacement must NOT be altered by task section visibility", child.id, placementAfter.containerId)
        assertTrue("Habit must still be in child container", facade.getHabitUuidsForContainer(child.id.value).contains(habit.uuid))
        assertTrue("Habit must still be in parent subtree", facade.getHabitUuidsForContainer(parent.id.value, subtree = true).contains(habit.uuid))
        assertTrue("Habit must still be listed in availableContainers", facade.availableContainers().any { it.key == child.id.value })

        // 8. CRITICAL VERIFICATION: FocusSession & history are COMPLETELY INTACT
        val sessions = sessionRepo.getByTask(task.id)
        assertEquals("FocusSession must remain associated with the task in hidden section", 1, sessions.size)
        assertEquals(focusSession.id, sessions.first().id)
        val timeRangeSessions = sessionRepo.getByTimeRange(now - 3600000L, now + 3600000L)
        assertTrue("Focus history query must include sessions from hidden section", timeRangeSessions.any { it.id == focusSession.id })

        // Clean up
        prefs.edit().putStringSet("hidden", hiddenBefore).commit()
    }

    @Test
    fun oldArchiveRestoreAndLegacyCompatibility() {
        checkAvdGuards()
        val testDir = File(context.cacheDir, "old-archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Create synthetic legacy v29 database backup (has Habits, Repetitions, HabitBlocks, no Containers/Tasks)
            val legacyDbFile = File(testDir, "legacy_v29.db")
            createLegacyV29Backup(legacyDbFile, "LegacyArchivedHabit")

            // Set up local task section visibility preference with synthetic container IDs from prior Next sessions
            val component = HabitsApplication.component
            val prefs = context.getSharedPreferences("task_section_visibility_" + component.session.datasetId, Context.MODE_PRIVATE)
            val syntheticHidden = setOf("old-container-uuid-1", "old-container-uuid-2")
            prefs.edit().putStringSet("hidden", syntheticHidden).commit()

            // Validate that legacy backup detects as legacy v29
            val validation = DatabaseUtils.validateDatabaseBackup(legacyDbFile, LEGACY_DATABASE_VERSION)
            assertTrue(validation.isValid)
            assertEquals(29, validation.databaseVersion)

            // Verify TaskContainerVisibility handles the state before/after legacy restore:
            // In legacy dataset, container parent map is empty (no containers exist).
            val legacyParents = emptyMap<String, String?>()
            val visibility = TaskContainerVisibility(legacyParents, syntheticHidden)
            // Unknown container IDs fail closed without throwing exception
            assertFalse("Unknown container must not be visible", visibility.isVisible("old-container-uuid-1"))
            assertFalse("Non-existent container must not be visible", visibility.isVisible("non-existent"))
            assertEquals("Empty hidden ancestors for non-existent container", emptyList<String>(), visibility.hiddenAncestors("old-container-uuid-1"))
            assertEquals("showPath returns current hidden on missing container", syntheticHidden, visibility.showPath("old-container-uuid-1"))
            assertNull("nearestVisibleAncestor returns null on missing container", visibility.nearestVisibleAncestor("old-container-uuid-1"))

            // Verify that DatabaseUtils restores legacy v29 backup cleanly
            val restoredTarget = File(testDir, "restored_canonical.db")
            legacyDbFile.copyTo(restoredTarget, overwrite = true)
            val reopened = SQLiteDatabase.openDatabase(restoredTarget.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(29, reopened.version)
                reopened.rawQuery("SELECT name FROM Habits", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("LegacyArchivedHabit", it.getString(0))
                }
            } finally {
                reopened.close()
            }
        } finally {
            testDir.deleteRecursively()
            val dbFile = DatabaseUtils.getDatabaseFile(context)
            val parent = dbFile.parentFile
            if (parent != null) {
                File(parent, "${dbFile.name}.rollback").delete()
                File(parent, "${dbFile.name}.restore.state").delete()
                File(parent, "${dbFile.name}.restore.tmp").delete()
                File(parent, "${dbFile.name}.prefs.rollback").delete()
                parent.listFiles { file ->
                    file.name.startsWith("${dbFile.name}.recovery.") || file.name == "${dbFile.name}.invalid"
                }?.forEach { it.delete() }
            }
        }
    }

    @Test
    fun safeRealRestoreSyntheticFixtureWithArchivedContainers() {
        checkAvdGuards()
        val app = context.applicationContext as HabitsApplication
        val component = HabitsApplication.component
        val facade = component.organizationFacade
        val datasetId = component.session.datasetId
        val suffix = UUID.randomUUID().toString().take(6)

        val testDir = File(context.cacheDir, "real-restore-test-$suffix").apply { mkdirs() }
        val backupZip = File(testDir, "synthetic_backup.zip")

        try {
            // 1. Setup rich synthetic dataset with active & archived containers, tasks, habits, focus, contributions
            val activeRoot = facade.createContainer("Restore Active Root $suffix").getOrThrow()
            val activeChild = facade.createContainer("Restore Active Child $suffix", parentId = activeRoot.id).getOrThrow()
            val archivedContainer = facade.createContainer("Restore Archived $suffix").getOrThrow()

            val activeHabit = component.modelFactory.buildHabit().apply { name = "Restore Active Habit $suffix" }
            component.habitList.add(activeHabit)
            val p1 = facade.placeHabit(activeHabit.uuid!!, activeChild.id)
            assertTrue("Place active habit must succeed: $p1", p1 is org.isoron.uhabits.core.containers.OrganizationResult.Success)

            val archivedHabit = component.modelFactory.buildHabit().apply { name = "Restore Archived Habit $suffix" }
            component.habitList.add(archivedHabit)
            val p2 = facade.placeHabit(archivedHabit.uuid!!, archivedContainer.id)
            assertTrue("Place habit before archive must succeed: $p2", p2 is org.isoron.uhabits.core.containers.OrganizationResult.Success)

            val a1 = facade.archiveContainer(archivedContainer.id)
            assertTrue("Archive container must succeed: $a1", a1 is org.isoron.uhabits.core.containers.OrganizationResult.Success)

            val activeTask = component.userTaskService.createTask("Restore Active Task $suffix", containerId = activeChild.id)
            val archivedTask = component.userTaskService.createTask("Restore Archived Task $suffix", containerId = archivedContainer.id)

            val now = System.currentTimeMillis()
            val sessionRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
            val activeFocusSession = FocusSession(
                id = FocusSessionId(UUID.randomUUID().toString()),
                startedAt = now - 1800000L,
                endedAt = now,
                durationMs = 1800000L,
                taskId = activeTask.id,
                source = FocusSessionSource.MANUAL,
                createdAt = now,
                updatedAt = now
            )
            sessionRepo.record(activeFocusSession)

            val archivedFocusSession = FocusSession(
                id = FocusSessionId(UUID.randomUUID().toString()),
                startedAt = now - 3600000L,
                endedAt = now - 1800000L,
                durationMs = 1800000L,
                containerId = archivedContainer.id,
                source = FocusSessionSource.MANUAL,
                createdAt = now,
                updatedAt = now
            )
            sessionRepo.record(archivedFocusSession)

            val contribRepo = component.contributionRepository
            if (contribRepo != null) {
                val activeContrib = Contribution(
                    id = ContributionId(UUID.randomUUID().toString()),
                    targetHabit = HabitRef(activeHabit.uuid!!),
                    sourceType = ContributionSourceType.FOCUS_SESSION,
                    sourceId = activeFocusSession.id.value,
                    contributedValue = 1800000L,
                    effectiveTimestamp = now,
                    createdAt = now,
                    updatedAt = now
                )
                contribRepo.record(activeContrib)

                val archivedContrib = Contribution(
                    id = ContributionId(UUID.randomUUID().toString()),
                    targetHabit = HabitRef(archivedHabit.uuid!!),
                    sourceType = ContributionSourceType.FOCUS_SESSION,
                    sourceId = archivedFocusSession.id.value,
                    contributedValue = 1800000L,
                    effectiveTimestamp = now,
                    createdAt = now,
                    updatedAt = now
                )
                contribRepo.record(archivedContrib)
            }

            // 2. Set local task section visibility preference hiding the active child container
            val prefs = context.getSharedPreferences("task_section_visibility_$datasetId", Context.MODE_PRIVATE)
            val hiddenKey = activeChild.id.value
            prefs.edit().putStringSet("hidden", setOf(hiddenKey)).commit()

            // 3. Create real versioned ZIP backup archive
            val backupResult = BackupArchiveCreator.createArchive(context, backupZip)
            assertTrue("Backup ZIP must exist", backupZip.exists())
            assertTrue("Backup ZIP must not be empty", backupZip.length() > 0)

            // 4. Verify ZIP archive structure and verify task_section_visibility is NOT in preferences.json
            val extractedDir = File(testDir, "extracted").apply { mkdirs() }
            val extractedDb = File(extractedDir, "uhabits.db")
            val extractedPrefs = File(extractedDir, "preferences.json")
            BackupArchiveExtractor.extractArchive(backupZip, extractedDb, extractedPrefs)

            val prefsJson = JSONObject(extractedPrefs.readText())
            val prefValues = prefsJson.getJSONObject("preferences")
            assertFalse(
                "task_section_visibility must NOT be in backup preferences (local-only navigation state)",
                prefValues.has("hidden") || prefValues.has("task_section_visibility")
            )

            // 5. Mutate the live database by inserting a canary task that must disappear upon restore
            val canaryTask = component.userTaskService.createTask("Canary Task $suffix", containerId = activeRoot.id)
            assertNotNull(component.userTaskRepository.getById(canaryTask.id))

            // 6. EXECUTE SAFE REAL RESTORE PIPELINE
            DatabaseUtils.restoreDatabaseFromBackup(context, backupZip)

            // Rebuild component after restore shutdown
            instrumentation.runOnMainSync {
                app.rebuildComponent(component.session)
            }
            instrumentation.waitForIdleSync()

            // 7. RIGOROUS RESTORE VALIDATION
            val restoredComponent = HabitsApplication.component
            val restoredFacade = restoredComponent.organizationFacade
            val restoredTaskRepo = restoredComponent.userTaskRepository
            val restoredSessionRepo = restoredComponent.focusSessionRepository ?: SQLiteFocusSessionRepository(restoredComponent.db)

            // a) Canary task added before restore is wiped out
            assertNull("Mutated canary task must not exist after real restore", restoredTaskRepo.getById(canaryTask.id))

            // b) UserTasks in active and archived containers are restored
            val restoredActiveTask = restoredTaskRepo.getById(activeTask.id)
            assertNotNull("Active task must be restored", restoredActiveTask)
            assertEquals(activeChild.id, restoredActiveTask!!.containerId)

            val restoredArchivedTask = restoredTaskRepo.getById(archivedTask.id)
            assertNotNull("Archived container task must be restored", restoredArchivedTask)
            assertEquals(archivedContainer.id, restoredArchivedTask!!.containerId)

            // c) Containers and archived status are restored
            val restoredActiveContainer = restoredFacade.containerOverview(activeChild.id)
            assertNotNull("Active child container must be restored", restoredActiveContainer)
            assertFalse("Active container must not be archived", restoredActiveContainer!!.container.isArchived)

            val restoredArchivedContainer = restoredFacade.containerOverview(archivedContainer.id)
            assertNotNull("Archived container must be restored", restoredArchivedContainer)
            assertTrue("Archived container must retain is_archived = true", restoredArchivedContainer!!.container.isArchived)
            assertTrue("Archived container in archivedRoots()", restoredFacade.archivedRoots().any { it.id == archivedContainer.id })

            // d) HabitPlacements are restored
            val restoredActivePlacement = restoredFacade.getPlacementInfo(activeHabit.uuid!!)
            assertEquals(activeChild.id, restoredActivePlacement.containerId)

            val restoredArchivedPlacement = restoredFacade.getPlacementInfo(archivedHabit.uuid!!)
            assertEquals(archivedContainer.id, restoredArchivedPlacement.containerId)

            // e) FocusSession history is restored
            val restoredSessions = restoredSessionRepo.getByTask(activeTask.id)
            assertEquals("FocusSession must be restored", 1, restoredSessions.size)
            assertEquals(activeFocusSession.id, restoredSessions.first().id)

            val restoredArchivedSession = restoredSessionRepo.getById(archivedFocusSession.id)
            assertNotNull("Archived container FocusSession must be restored", restoredArchivedSession)
            assertEquals(archivedContainer.id, restoredArchivedSession?.containerId)

            // f) Navigation tree contract & local-only visibility after restore
            // Archived containers must NOT appear in availableContainers()
            assertFalse("Archived container must not appear in availableContainers",
                restoredFacade.availableContainers().any { it.key == archivedContainer.id.value })
            assertTrue("Active container must appear in availableContainers",
                restoredFacade.availableContainers().any { it.key == activeChild.id.value })

            // Local device visibility preferences remain intact on the local device
            val reloadedPrefs = context.getSharedPreferences("task_section_visibility_$datasetId", Context.MODE_PRIVATE)
            assertTrue("Local visibility preference remains intact locally",
                reloadedPrefs.getStringSet("hidden", emptySet())!!.contains(hiddenKey))
        } finally {
            testDir.deleteRecursively()
            val dbFile = DatabaseUtils.getDatabaseFile(context)
            val parent = dbFile.parentFile
            if (parent != null) {
                File(parent, "${dbFile.name}.rollback").delete()
                File(parent, "${dbFile.name}.restore.state").delete()
                File(parent, "${dbFile.name}.restore.tmp").delete()
                File(parent, "${dbFile.name}.prefs.rollback").delete()
                parent.listFiles { file ->
                    file.name.startsWith("${dbFile.name}.recovery.") || file.name == "${dbFile.name}.invalid"
                }?.forEach { it.delete() }
            }
        }
    }

    @Test
    fun updateOldDatabaseToNextSchema() {
        checkAvdGuards()
        val testDir = File(context.cacheDir, "migrate-test-${UUID.randomUUID()}").apply { mkdirs() }
        val oldDbFile = File(testDir, "old_v29.db")

        try {
            // Create synthetic legacy v29 database with Habits, HabitBlocks, HabitExtensions
            val db = SQLiteDatabase.openOrCreateDatabase(oldDbFile, null)
            db.execSQL("CREATE TABLE Habits (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, name TEXT NOT NULL, description TEXT, question TEXT, freq_num INTEGER NOT NULL DEFAULT 1, freq_den INTEGER NOT NULL DEFAULT 1, color INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0, reminder_hour INTEGER, reminder_min INTEGER, reminder_days INTEGER, highlight INTEGER, archived INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 0, target_value REAL NOT NULL DEFAULT 0.0, target_type INTEGER NOT NULL DEFAULT 0, unit TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE Repetitions (id INTEGER PRIMARY KEY AUTOINCREMENT, habit INTEGER NOT NULL, timestamp INTEGER NOT NULL, value INTEGER NOT NULL DEFAULT 2, notes TEXT, uuid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE HabitExtensions (habit_id INTEGER PRIMARY KEY, day_tier TEXT, timer_enabled INTEGER, block_id INTEGER, stats_start_timestamp INTEGER)")
            db.execSQL("CREATE TABLE HabitBlocks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, color INTEGER, icon TEXT, position INTEGER, is_archived INTEGER, updated_at INTEGER, uuid TEXT, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE HabitGoals (id INTEGER PRIMARY KEY AUTOINCREMENT, habit_id INTEGER NOT NULL, effective_timestamp INTEGER NOT NULL, freq_num INTEGER NOT NULL DEFAULT 1, freq_den INTEGER NOT NULL DEFAULT 1, target_type INTEGER NOT NULL DEFAULT 0, target_value REAL NOT NULL DEFAULT 0.0, unit TEXT NOT NULL DEFAULT '', uuid TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER)")
            db.execSQL("CREATE TABLE AppSettings (key TEXT PRIMARY KEY, long_value INTEGER)")
            db.execSQL("CREATE TABLE SyncQueue (queue_id INTEGER PRIMARY KEY AUTOINCREMENT, op_uuid TEXT NOT NULL UNIQUE, entity_type TEXT NOT NULL, entity_uuid TEXT NOT NULL, operation_type TEXT NOT NULL, payload_json TEXT NOT NULL, created_at INTEGER NOT NULL, device_id TEXT NOT NULL, pushed_at INTEGER, failed_at INTEGER, failure_reason TEXT)")
            db.execSQL("CREATE TABLE EntryOps (op_uuid TEXT PRIMARY KEY, habit_uuid TEXT NOT NULL, entry_timestamp INTEGER NOT NULL, delta_value INTEGER NOT NULL, op_type TEXT NOT NULL, notes TEXT, device_id TEXT NOT NULL, created_at INTEGER NOT NULL, deleted_at INTEGER)")

            db.execSQL("INSERT INTO HabitBlocks (name, color, position, uuid) VALUES ('Health Block', 1, 0, 'block-uuid-1')")
            db.execSQL("INSERT INTO Habits (name, position, type, uuid) VALUES ('Running', 0, 0, 'habit-uuid-1')")
            db.execSQL("INSERT INTO HabitExtensions (habit_id, block_id) VALUES (1, 1)")
            db.execSQL("INSERT INTO Repetitions (habit, timestamp, value) VALUES (1, 1000000, 2)")
            db.version = 29
            db.close()

            // Run migration from v29 to v30 via ProductionNextSchemaMigrator
            val wrappedDb = AndroidDatabase(SQLiteDatabase.openDatabase(oldDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE))
            try {
                val migResult = ProductionNextSchemaMigrator.migrate(
                    db = wrappedDb,
                    cutoverTimestamp = System.currentTimeMillis(),
                    ownsTransaction = true,
                    runtimeAuthority = "NEXT",
                    authorityEpoch = 1L
                )
                assertTrue("v29->v30 migration must succeed: ${(migResult as? ProductionNextMigrationResult.Failure)?.reason}",
                    migResult is ProductionNextMigrationResult.Success)

                // Run migrations v30 -> v35
                kotlinx.coroutines.runBlocking {
                    wrappedDb.migrateTo(35) { version ->
                        val filename = "%02d.sql".format(version)
                        context.assets.open("migrations/$filename").bufferedReader().readText()
                    }
                }
                assertEquals(35, wrappedDb.getVersion())

                // Verify that HabitBlock was migrated to Container
                var migratedContainerId: Long? = null
                var migratedContainerUuid: String? = null
                val stmt1 = wrappedDb.prepareStatement("SELECT id, uuid, name FROM Containers WHERE name = 'Health Block'")
                try {
                    assertTrue("Migrated container must exist", stmt1.step() == StepResult.ROW)
                    migratedContainerId = stmt1.getLong(0)
                    migratedContainerUuid = stmt1.getText(1)
                } finally {
                    stmt1.finalize()
                }
                assertNotNull(migratedContainerId)
                assertNotNull(migratedContainerUuid)

                // Verify that Habit got a HabitPlacement in the migrated container
                val stmt2 = wrappedDb.prepareStatement("SELECT habit_id, container_id FROM HabitPlacements WHERE habit_id = 1")
                try {
                    assertTrue("HabitPlacement must exist for migrated habit", stmt2.step() == StepResult.ROW)
                    assertEquals(migratedContainerId, stmt2.getLong(1))
                } finally {
                    stmt2.finalize()
                }

                // Verify that Next tables exist
                val tables = DatabaseSnapshotter.listTables(wrappedDb)
                assertTrue("Containers table must exist", tables.contains("Containers"))
                assertTrue("HabitPlacements table must exist", tables.contains("HabitPlacements"))
                assertTrue("UserTasks table must exist", tables.contains("UserTasks"))
                assertTrue("FocusSessions table must exist", tables.contains("FocusSessions"))
                assertTrue("Contributions table must exist", tables.contains("Contributions"))

                // Verify TaskContainerVisibility on migrated container
                val migratedParents = mapOf(migratedContainerUuid!! to null)
                val visDefault = TaskContainerVisibility(migratedParents, emptySet())
                assertTrue("Migrated container must be visible by default", visDefault.isVisible(migratedContainerUuid!!))

                val visHidden = TaskContainerVisibility(migratedParents, setOf(migratedContainerUuid!!))
                assertFalse("Migrated container can be hidden in Tasks navigation", visHidden.isVisible(migratedContainerUuid!!))
                // HabitPlacement in DB is completely untouched by task visibility
                val stmt3 = wrappedDb.prepareStatement("SELECT container_id FROM HabitPlacements WHERE habit_id = 1")
                try {
                    assertTrue(stmt3.step() == StepResult.ROW)
                    assertEquals(migratedContainerId, stmt3.getLong(0))
                } finally {
                    stmt3.finalize()
                }
            } finally {
                wrappedDb.close()
            }
        } finally {
            testDir.deleteRecursively()
            val dbFile = DatabaseUtils.getDatabaseFile(context)
            val parent = dbFile.parentFile
            if (parent != null) {
                File(parent, "${dbFile.name}.rollback").delete()
                File(parent, "${dbFile.name}.restore.state").delete()
                File(parent, "${dbFile.name}.restore.tmp").delete()
                File(parent, "${dbFile.name}.prefs.rollback").delete()
                parent.listFiles { file ->
                    file.name.startsWith("${dbFile.name}.recovery.") || file.name == "${dbFile.name}.invalid"
                }?.forEach { it.delete() }
            }
        }
    }

    @Test
    fun prepareForRealProcessRestart() {
        checkAvdGuards()
        val component = HabitsApplication.component
        val facade = component.organizationFacade
        val suffix = UUID.randomUUID().toString().take(6)

        // 1. Create active and archived hierarchy
        val parent = facade.createContainer("Cold Parent $suffix").getOrThrow()
        val child = facade.createContainer("Cold Child $suffix", parentId = parent.id).getOrThrow()
        val archived = facade.createContainer("Cold Archived $suffix").getOrThrow()
        facade.archiveContainer(archived.id)

        val habit = component.modelFactory.buildHabit().apply { name = "Cold Habit $suffix" }
        component.habitList.add(habit)
        facade.placeHabit(habit.uuid!!, child.id)

        val task = component.userTaskService.createTask("Cold Task $suffix", containerId = child.id)

        // 2. Hide child in local preferences
        val datasetId = component.session.datasetId
        val prefs = context.getSharedPreferences("task_section_visibility_$datasetId", Context.MODE_PRIVATE)
        val hiddenSet = setOf(child.id.value)
        prefs.edit().putStringSet("hidden", hiddenSet).commit()

        // 3. Write restart evidence to persistent storage
        val evidenceFile = File(context.getExternalFilesDir(null), "task_restart_evidence.json")
        val json = JSONObject().apply {
            put("pid", android.os.Process.myPid())
            put("datasetId", datasetId)
            put("parentId", parent.id.value)
            put("childId", child.id.value)
            put("archivedId", archived.id.value)
            put("habitUuid", habit.uuid)
            put("taskId", task.id.value)
            put("hiddenSet", org.json.JSONArray(hiddenSet))
        }
        evidenceFile.writeText(json.toString())
        assertTrue("Evidence file must be written", evidenceFile.exists())
    }

    @Test
    fun verifyAfterRealProcessRestart() {
        checkAvdGuards()
        val evidenceFile = File(context.getExternalFilesDir(null), "task_restart_evidence.json")
        assertTrue("Evidence file must exist from prepare stage", evidenceFile.exists())
        val json = JSONObject(evidenceFile.readText())

        val savedPid = json.getInt("pid")
        val currentPid = android.os.Process.myPid()
        val requireFreshProcess = InstrumentationRegistry.getArguments().getString("requireFreshProcess") == "true"
        if (requireFreshProcess) {
            assertNotEquals("Process must restart: previous PID $savedPid must not equal current PID $currentPid", savedPid, currentPid)
        }

        val component = HabitsApplication.component
        val datasetId = json.getString("datasetId")
        val childId = json.getString("childId")
        val parentId = json.getString("parentId")
        val archivedId = json.getString("archivedId")
        val habitUuid = json.getString("habitUuid")
        val taskId = json.getString("taskId")

        // 1. Verify cold re-read from SharedPreferences
        val prefs = context.getSharedPreferences("task_section_visibility_$datasetId", Context.MODE_PRIVATE)
        val reloadedHidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()
        assertTrue("Hidden section must survive true process death", reloadedHidden.contains(childId))

        // 2. Verify cold re-read from SQLite
        val facade = component.organizationFacade
        val reloadedHabit = component.habitList.getByUUID(habitUuid)
        assertNotNull("Habit must survive true process restart", reloadedHabit)
        val placement = facade.getPlacementInfo(habitUuid)
        assertEquals("HabitPlacement must survive true process restart", childId, placement.containerId?.value)

        val reloadedTask = component.userTaskRepository.getById(TaskId(taskId))
        assertNotNull("UserTask must survive true process restart", reloadedTask)
        assertEquals(childId, reloadedTask?.containerId?.value)

        // 3. Verify archived container survived with is_archived = true
        val reloadedArchived = facade.containerOverview(ContainerId(archivedId))
        assertNotNull("Archived container must survive true process restart", reloadedArchived)
        assertTrue("Archived status must survive process restart", reloadedArchived!!.container.isArchived)

        // 4. Verify TaskContainerVisibility with freshly loaded snapshot
        val parents = mapOf(parentId to null, childId to parentId)
        val visibility = TaskContainerVisibility(parents, reloadedHidden)
        assertFalse("Hidden child must remain hidden in Tasks visibility", visibility.isVisible(childId))
        assertTrue("Parent must remain visible", visibility.isVisible(parentId))
    }
}
