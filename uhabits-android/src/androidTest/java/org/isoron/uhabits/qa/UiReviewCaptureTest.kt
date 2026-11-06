package org.isoron.uhabits.qa

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.activities.settings.*
import org.isoron.uhabits.activities.tasks.*
import org.isoron.uhabits.core.containers.session.*
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.create
import org.isoron.uhabits.session.DatasetSessionManager
import org.isoron.uhabits.utils.DatabaseUtils
import org.isoron.uhabits.utils.DemoDataGenerator
import org.isoron.platform.time.LocalDate
import org.isoron.platform.io.queryString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Never run through connectedAndroidTest. The runner supplies a verified dedicated AVD token. */
@RunWith(AndroidJUnit4::class)
class UiReviewCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val manifest = JSONArray()
    private lateinit var output: File
    private var sequence = 0
    private var variant = "light"

    companion object {
        fun initializeFixture(): HabitsApplicationComponent {
            val ins = InstrumentationRegistry.getInstrumentation()
            val context = ins.targetContext
            check(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator")) { "Physical devices forbidden" }
            val token = InstrumentationRegistry.getArguments().getString("disposableAvd")
            val isTemporary = token?.matches(Regex("UHabit_Temp_[a-f0-9]{32}")) == true
            check(token == "UHabit_UI_Review_Disposable_20261011" || isTemporary) { "Verified disposable AVD token required" }
            check(UiDevice.getInstance(ins).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == token) { "Actual AVD identity mismatch" }
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Throwable) {}
            }
            if (isTemporary) {
                check(org.isoron.uhabits.BuildConfig.DEBUG)
                check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
                check(Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) == 0)
            }
            UiDevice.getInstance(ins).wakeUp()
            val original = HabitsApplication.component
            if (isTemporary) {
                val marker = File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${original.session.datasetId}")
                check(original.session.isExperimental && !original.session.capabilities.syncEnabled &&
                    !original.session.capabilities.remoteCallsAllowed && marker.isFile &&
                    marker.readText().trim() == original.session.databaseFilename &&
                    original.dbFile.canonicalFile == context.getDatabasePath(original.session.databaseFilename).canonicalFile &&
                    original.session.databaseFilename != org.isoron.uhabits.core.DATABASE_FILENAME) {
                    "Temporary capture requires a marked isolated offline dataset"
                }
            }
            if (original.session.isExperimental && original.habitList.size() >= 30) return original
            if (isTemporary) {
                check(original.session.isExperimental && original.habitList.size() == 0) { "Temporary capture requires an empty experimental fixture" }
                original.preferences.isFirstRun = false
                original.preferences.isDeveloper = true
                original.preferences.isDayTiersEnabled = true
                original.preferences.isHabitSpheresEnabled = true
                original.preferences.theme = 2
                DemoDataGenerator.generate(context, original.sqlModelFactory, original.habitList, null,
                    original.habitCardListCache, true, LocalDate(2026, 10, 11))
                return original
            }
            val legacySnapshot = File(context.cacheDir, "ui-review-empty-legacy.db")
            check(original.habitList.size() == 0) { "QA initialization requires an empty source dataset" }
            DatabaseUtils.createDatabaseSnapshot(context, legacySnapshot)
            val result = DatasetSessionManager(context).createAndActivateExperiment()
            check(result is DatasetActivationResult.Success) { "Disposable experiment creation failed: $result" }
            original.timerSessionManager.dispose()
            DatabaseUtils.closeDatabase()
            val file = context.getDatabasePath(result.session.databaseFilename)
            // The old experimental migration retains user_version 29. Build the disposable
            // fixture through the real v29 -> v30 migrator instead of upgrading it twice.
            legacySnapshot.copyTo(file, overwrite = true)
            legacySnapshot.delete()
            android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                val migrated = org.isoron.uhabits.core.production.migration.ProductionNextSchemaMigrator.migrate(
                    org.isoron.uhabits.database.AndroidDatabase(sqlite), System.currentTimeMillis(),
                    datasetUuid = result.session.datasetId)
                check(migrated is org.isoron.uhabits.core.production.migration.ProductionNextMigrationResult.Success) {
                    "Disposable v30 migration failed: $migrated"
                }
                sqlite.execSQL("UPDATE OrganizationState SET mode = 'CONTAINER_LOCAL' WHERE id = 1")
            }
            DatabaseUtils.initializeDatabase(context, result.session.databaseFilename)
            val component = HabitsApplicationComponent::class.create(appContext = context, dbFile = file, session = result.session)
            ins.runOnMainSync { HabitsApplication.component = component }
            File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${result.session.datasetId}").writeText(result.session.databaseFilename)
            component.preferences.isFirstRun = false
            component.preferences.isDeveloper = true
            component.preferences.theme = 2
            DemoDataGenerator.generate(context, component.sqlModelFactory, component.habitList, null, component.habitCardListCache, true,
                LocalDate(2026, 10, 11))
            fun fixtureFingerprint(): String = component.db.queryString("""
                SELECT group_concat(row, '|') FROM (
                  SELECT h.uuid || ':' || r.timestamp || ':' || r.value AS row
                  FROM Repetitions r JOIN Habits h ON h.id = r.habit ORDER BY h.uuid, r.timestamp
                )
            """.trimIndent()) + component.userTaskRepository.getAll().sortedBy { it.id.value }.toString() +
                org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(component.db).getAll().toString()
            val fingerprint = fixtureFingerprint()
            // Removing the explicit disposable marker must reject even this isolated fixture.
            val marker = File(context.filesDir, "${DemoDataGenerator.DISPOSABLE_MARKER}-${result.session.datasetId}")
            marker.delete()
            val rejected = runCatching {
                DemoDataGenerator.generate(context, component.sqlModelFactory, component.habitList, null,
                    component.habitCardListCache, true, LocalDate(2026, 10, 11))
            }.exceptionOrNull()
            check(rejected is IllegalStateException && fingerprint == fixtureFingerprint()) { "Unmarked dataset was modified" }
            marker.writeText(result.session.databaseFilename)
            // Exercise repeat seeding to catch tree foreign-key constraints and stale ledger data.
            DemoDataGenerator.generate(context, component.sqlModelFactory, component.habitList, null, component.habitCardListCache, true,
                LocalDate(2026, 10, 11))
            check(fingerprint == fixtureFingerprint()) { "Reseed changed logical fixture data" }
            return component
        }
    }

    @Test fun captureRenderedScreens() {
        val fixture = initializeFixture()
        val app = context.applicationContext as HabitsApplication
        // Both modes use the same disposable offline fixture; no remote/background work.
        val mode = InstrumentationRegistry.getArguments().getString("uiReviewMode", "production")
        check(mode == "production" || mode == "experimental")
        val qaSession = if (mode == "production") fixture.session.copy(mode = DatasetMode.PRODUCTION_NEXT,
            capabilities = DatasetCapabilities.PRODUCTION_NEXT.copy(syncEnabled = false, remoteCallsAllowed = false,
                backgroundJobsEnabled = false, widgetsEnabled = false, remindersEnabled = false)) else fixture.session
        instrumentation.runOnMainSync { app.rebuildComponent(qaSession) }
        val component = HabitsApplication.component
        if (mode == "production") {
            val productionRejection = runCatching {
                DemoDataGenerator.generate(context, component.sqlModelFactory, component.habitList, null,
                    component.habitCardListCache, true, LocalDate(2026, 10, 11))
            }.exceptionOrNull()
            check(productionRejection is IllegalStateException) { "Generator accepted production mode" }
        }
        output = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val variants = listOf(Triple("light", 2, "1.0"), Triple("dark", 1, "1.0"), Triple("amoled", 1, "1.0"), Triple("large-font", 2, "1.3"))
        val requestedVariant = InstrumentationRegistry.getArguments().getString("uiReviewVariant")
        check(requestedVariant == null || variants.any { it.first == requestedVariant })
        for ((name, theme, scale) in variants.filter { requestedVariant == null || it.first == requestedVariant }) {
            variant = name; component.preferences.theme = theme; component.preferences.isPureBlackEnabled = name == "amoled"
            shell("settings put system font_scale $scale")
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                fun route(destination: MainDestination) { scenario.onActivity { it.navigate(destination) }; device.waitForIdle() }
                route(MainDestination.HABITS); capture("habits", "main-list")
                attempt("habits", "overflow") {
                    val overflow = device.findObject(By.desc("Ещё")) ?: device.findObject(By.desc("More options"))
                        ?: error("Overflow action unavailable")
                    overflow.click(); device.waitForIdle(); capture("habits", "overflow"); device.pressBack()
                }
                attempt("habits", "details") { clickDescription("Читать книгу"); check(device.wait(androidx.test.uiautomator.Until.hasObject(By.res(java.util.regex.Pattern.compile(".*:id/action_edit_habit"))), 5000)) { "Habit details did not open" }; capture("habits", "details"); device.swipe(600, 1700, 600, 650, 20); capture("habits", "history-statistics"); device.pressBack() }
                route(MainDestination.TASKS); capture("tasks", "today")
                attempt("tasks", "creation") { device.findObject(By.res(context.packageName, "layoutQuickAddCollapsed")).click(); capture("tasks", "creation"); device.pressBack() }
                for (label in listOf("Входящие", "Все задачи", "Предстоящие", "Завершённые")) attempt("tasks", label) {
                    openTaskNavigation(); clickText(label); capture("tasks", label)
                }
                attempt("containers", "navigation-tree") { openTaskNavigation(); capture("containers", "navigation-tree"); clickText("Учёба"); capture("containers", "study-content") }
                attempt("containers", "nested") { openTaskNavigation(); clickText("Курсовая работа"); capture("containers", "nested") }
                attempt("containers", "empty") { openTaskNavigation(); clickText("Пустой раздел"); capture("containers", "empty") }
                scenario.onActivity { TaskDetailBottomSheet.newInstance(TaskId("qa-task-1")).show(it.supportFragmentManager, "qa-task") }
                device.waitForIdle(); capture("tasks", "detail-notes-subtasks")
                for ((label, id) in listOf("due" to "chipDetailDue", "container" to "chipDetailContainer")) attempt("tasks", "$label-picker") {
                    QaEmulatorUi.clearSystemUiError()
                    val control = device.findObject(By.res(java.util.regex.Pattern.compile(".*:id/$id")))
                        ?: error("Missing actual detail control: $id")
                    control.click(); device.waitForIdle(); capture("tasks", "$label-picker"); device.pressBack()
                }
                attempt("focus", "preparation") {
                    clickTextContains("Начать фокус"); capture("focus", "preparation")
                    attempt("focus", "pomodoro-preparation") {
                        clickText(context.getString(org.isoron.uhabits.R.string.timer_mode_pomodoro).uppercase()); capture("focus", "pomodoro-preparation")
                        clickText(context.getString(org.isoron.uhabits.R.string.timer_mode_stopwatch).uppercase())
                    }
                    check(!component.timerSessionManager.snapshot().hasActiveSession) { "Opening Focus started a session" }
                    clickText("Начать")
                    device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))?.click()
                    device.waitForIdle()
                    instrumentation.runOnMainSync {
                        check(component.timerSessionManager.snapshot().isRunning) { "Start did not begin Focus" }
                        check(component.timerSessionManager.isCurrentTask(TaskId("qa-task-1"))) { "Wrong Focus attribution target" }
                    }
                    capture("focus", "running")
                    instrumentation.runOnMainSync {
                        component.timerSessionManager.pauseOrResumeCurrent()
                        check(!component.timerSessionManager.snapshot().isRunning && component.timerSessionManager.snapshot().hasActiveSession)
                    }; capture("focus", "paused")
                    instrumentation.runOnMainSync { component.timerSessionManager.pauseOrResumeCurrent() }
                    Thread.sleep(1200)
                    instrumentation.runOnMainSync {
                        component.timerSessionManager.finishCurrent()
                        check(!component.timerSessionManager.snapshot().hasActiveSession)
                    }; capture("focus", "completed")
                    device.pressBack(); capture("focus", "task-summary")
                }
                device.pressBack()
                route(MainDestination.STATISTICS); capture("statistics", "global-focus-containers")
                device.swipe(600, 1700, 600, 650, 20); capture("statistics", "below-fold")
                route(MainDestination.SETTINGS); capture("settings", "home")
                for (section in SettingsSectionId.entries.filter { it != SettingsSectionId.DEVELOPER }) attempt("settings", section.name) {
                    scenario.onActivity { activity ->
                        val host = activity.supportFragmentManager.fragments.filterIsInstance<SettingsSectionFragment>().first()
                        host.openSettingsSection(section)
                        host.childFragmentManager.executePendingTransactions()
                    }
                    capture("settings", section.name.lowercase()); device.pressBack(); device.waitForIdle()
                }
                UiReviewExtraCoverage.capture(scenario, component, ::capture, ::attempt)
            }
        }
        shell("settings put system font_scale 1.0")
        writeManifest()
    }
    private fun writeManifest() {
        val component = HabitsApplication.component
        val temporary = File(output, "capture-manifest.tmp")
        temporary.writeText(JSONObject().put("avd", InstrumentationRegistry.getArguments().getString("disposableAvd"))
            .put("seed", DemoDataGenerator.SEED).put("referenceDate", "2026-10-11").put("datasetMode", component.session.mode.name)
            .put("fixtureSource", "Generated isolated disposable experiment rendered under offline production UI gates")
            .put("appVersion", org.isoron.uhabits.BuildConfig.VERSION_NAME).put("sdk", Build.VERSION.SDK_INT)
            .put("versionCode", org.isoron.uhabits.BuildConfig.VERSION_CODE).put("timezone", java.util.TimeZone.getDefault().id)
            .put("captures", manifest).toString(2))
        check(temporary.renameTo(File(output, "capture-manifest.json"))) { "Manifest replacement failed" }
    }
    private fun shell(command: String) = device.executeShellCommand(command)
    private fun openTaskNavigation() { device.findObject(By.desc("Открыть навигацию задач"))?.click(); device.waitForIdle() }
    private fun clickText(label: String) {
        QaEmulatorUi.clearSystemUiError()
        val pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label),
            java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)
        val target = device.findObject(By.text(pattern))
            ?: device.findObject(By.desc(pattern))
            ?: device.findObject(By.descContains(label))
            ?: error("No text selector: $label")
        target.click(); device.waitForIdle()
    }
    private fun clickTextContains(label: String) { QaEmulatorUi.clearSystemUiError(); val target = device.findObject(By.textContains(label)) ?: error("No textContains selector: $label"); target.click(); device.waitForIdle() }
    private fun clickDescription(label: String) { QaEmulatorUi.clearSystemUiError(); val target = device.findObject(By.desc(label)) ?: error("No accessibility selector: $label"); target.click(); device.waitForIdle() }
    private fun capture(group: String, state: String) {
        QaEmulatorUi.clearSystemUiError()
        runBlocking { withTimeout(30000) { HabitsApplication.component.taskRunner.await() } }
        device.waitForIdle(); Thread.sleep(350)
        val owner = device.currentPackageName
        val systemFilePicker = state == "restore-picker-empty" && owner?.endsWith("documentsui") == true
        check(owner == context.packageName || systemFilePicker) { "Expected app screen, found $owner" }
        fun hasControl(id: String) = device.findObject(By.res(java.util.regex.Pattern.compile(".*:id/$id"))) != null
        when (state) {
            "details", "history-statistics" -> check(hasControl("action_edit_habit")) { "Expected Habit detail toolbar" }
            "План-custom-calendar", "Дедлайн-custom-calendar" -> check(hasControl("mtrl_calendar_main_pane")) { "Expected Material calendar" }
            "detail-notes-subtasks" -> check(hasControl("chipDetailDue") && hasControl("chipDetailContainer")) { "Expected Task details" }
            "preparation", "pomodoro-preparation" -> check(device.findObject(By.desc("Начать фокус")) != null) { "Expected explicit Focus preparation confirmation" }
        }
        val safeState = state.replace(Regex("[^a-zA-Zа-яА-Я0-9_-]"), "-")
        val directory = File(output, "$variant/$group").apply { mkdirs() }
        val stem = "%03d-%s".format(++sequence, safeState)
        val png = File(directory, "$stem.png")
        device.dumpWindowHierarchy(File(directory, "$stem.xml"))
        device.waitForIdle(); Thread.sleep(1000)
        check(device.takeScreenshot(png)) { "Screenshot failed: $stem" }
        manifest.put(JSONObject().put("screen", group).put("route", state).put("theme", variant)
            .put("fontScale", if (variant == "large-font") 1.3 else 1.0).put("status", "captured")
            .put("image", png.relativeTo(output).invariantSeparatorsPath).put("package", device.currentPackageName)
            .put("width", device.displayWidth).put("height", device.displayHeight)
            .put("stateSource", "Running Android hierarchy then settled screenshot; route assertion for detail/calendar/preparation; other routes require visual review"))
        writeManifest()
    }
    private fun attempt(group: String, state: String, action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            val failureDirectory = File(output, "$variant/failures").apply { mkdirs() }
            val stem = "%03d-%s".format(++sequence, state.replace(Regex("[^a-zA-Zа-яА-Я0-9_-]"), "-"))
            val failureImage = File(failureDirectory, "$stem.png")
            device.takeScreenshot(failureImage)
            device.dumpWindowHierarchy(File(failureDirectory, "$stem.xml"))
            manifest.put(JSONObject().put("screen", group).put("route", state).put("theme", variant)
                .put("status", "failed").put("error", failure.message)
                .put("diagnosticImage", failureImage.relativeTo(output).invariantSeparatorsPath))
            writeManifest()
            if (device.findObject(By.res(java.util.regex.Pattern.compile(".*:id/mainRoot"))) == null) device.pressBack()
            device.waitForIdle()
        }
    }
}
