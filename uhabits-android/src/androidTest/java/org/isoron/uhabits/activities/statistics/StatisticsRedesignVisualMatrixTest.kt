/*
 * Copyright (C) 2026 Álinson Santos Xavier and contributors.
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package org.isoron.uhabits.activities.statistics

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.tabs.TabLayout
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.main.MainActivity
import org.isoron.uhabits.activities.main.MainDestination
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.containers.session.DatasetSessionFactory
import org.isoron.uhabits.core.containers.session.ExperimentalDatasetMetadata
import org.isoron.uhabits.utils.DemoDataGenerator
import org.isoron.uhabits.core.focus.FocusSession
import org.isoron.uhabits.core.focus.FocusSessionId
import org.isoron.uhabits.core.focus.FocusSessionSource
import org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.userTasks.TaskStatus
import org.isoron.uhabits.core.userTasks.due.TaskDue
import org.isoron.uhabits.core.userTasks.due.toTaskDueDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Isolated visual & functional QA matrix for Statistics Redesign corrections (R01-R08).
 * Captures real screen states on a synthetic disposable dataset into
 * /sdcard/Android/data/org.isoron.uhabits.plus/files/statistics-review-v2/.
 */
@RunWith(AndroidJUnit4::class)
class StatisticsRedesignVisualMatrixTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val component get() = HabitsApplication.component
    private val device = UiDevice.getInstance(instrumentation)

    private val matrixMode get() = args.getString("matrixMode", "standard")!!

    private val outputDir
        get() = File(context.getExternalFilesDir(null), "statistics-review-v2").apply {
            check(mkdirs() || isDirectory) { "Failed to create output dir: $path" }
        }

    data class MatrixSeed(
        val healthId: ContainerId,
        val workId: ContainerId,
        val emptyId: ContainerId,
    )

    @Test
    fun captureAndVerifyMatrix() {
        val app = context.applicationContext as HabitsApplication
        val suffix = UUID.randomUUID().toString().take(8)
        val metadata = ExperimentalDatasetMetadata(
            datasetId = "qa-matrix-$suffix",
            databaseFilename = DatasetSessionFactory.EXPERIMENTAL_DB_FILENAME,
            foundationVersion = 1,
            sourceSnapshotSha256 = "",
            createdAtMillis = System.currentTimeMillis(),
        )
        val expSession = DatasetSessionFactory.experimental(metadata)
        File(context.filesDir, DemoDataGenerator.DISPOSABLE_MARKER + "-" + expSession.datasetId)
            .writeText(expSession.databaseFilename)
        instrumentation.runOnMainSync { app.switchDatasetSession(expSession) }
        app.sessionManager.consumeStartupError()
        val stmt = component.db.prepareStatement(
            "UPDATE OrganizationState SET dataset_uuid = ?, mode = 'CONTAINER_LOCAL' WHERE id = 1"
        )
        stmt.bindText(1, expSession.datasetId)
        stmt.step()
        stmt.finalize()
        app.sessionManager.persistExperimentalSelection(metadata)

        val prefs = component.preferences
        val oldTheme = prefs.theme
        val oldBlack = prefs.isPureBlackEnabled
        val oldLayoutMode = prefs.sectionLayoutMode
        val oldFirstRun = prefs.isFirstRun

        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        idle()

        val ruLocale = java.util.Locale("ru", "RU")
        java.util.Locale.setDefault(ruLocale)
        instrumentation.runOnMainSync {
            val res = context.resources
            val config = android.content.res.Configuration(res.configuration)
            config.setLocale(ruLocale)
            res.updateConfiguration(config, res.displayMetrics)
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("pref_app_language", "ru-RU").commit()
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags("ru-RU")
            )
        }
        idle()

        val seed = seedMatrixDataset()

        fun applyAppearance(theme: Int, pureBlack: Boolean, layoutMode: String) {
            instrumentation.runOnMainSync {
                prefs.theme = theme
                prefs.isPureBlackEnabled = pureBlack
                prefs.sectionLayoutMode = layoutMode
            }
            idle()
        }

        try {
            prefs.isFirstRun = false

            when (matrixMode) {
                "narrow320" -> {
                    applyAppearance(ThemeSwitcher.THEME_DARK, true, Preferences.SECTION_LAYOUT_CARDS)
                    captureThemeSet("narrow320", seed, includeExtendedStates = false)
                }
                "font200" -> {
                    applyAppearance(ThemeSwitcher.THEME_LIGHT, false, Preferences.SECTION_LAYOUT_CARDS)
                    captureThemeSet("font200", seed, includeExtendedStates = false)
                }
                else -> {
                    // 1. Modern AMOLED + full state transitions (Week/Prev/Month/Year/All, Tasks populated & empty, Focus multi vs single)
                    applyAppearance(ThemeSwitcher.THEME_DARK, true, Preferences.SECTION_LAYOUT_CARDS)
                    captureThemeSet("modern_amoled", seed, includeExtendedStates = true)

                    // 2. Modern Dark
                    applyAppearance(ThemeSwitcher.THEME_DARK, false, Preferences.SECTION_LAYOUT_CARDS)
                    captureThemeSet("modern_dark", seed, includeExtendedStates = false)

                    // 3. Modern Light
                    applyAppearance(ThemeSwitcher.THEME_LIGHT, false, Preferences.SECTION_LAYOUT_CARDS)
                    captureThemeSet("modern_light", seed, includeExtendedStates = false)

                    // 4. Classic Light (R07 seamless #FFFFFF verification)
                    applyAppearance(ThemeSwitcher.THEME_LIGHT, false, Preferences.SECTION_LAYOUT_CLASSIC)
                    captureThemeSet("classic_light", seed, includeExtendedStates = false)

                    // 5. Classic Dark
                    applyAppearance(ThemeSwitcher.THEME_DARK, false, Preferences.SECTION_LAYOUT_CLASSIC)
                    captureThemeSet("classic_dark", seed, includeExtendedStates = false)

                    // 6. Classic AMOLED
                    applyAppearance(ThemeSwitcher.THEME_DARK, true, Preferences.SECTION_LAYOUT_CLASSIC)
                    captureThemeSet("classic_amoled", seed, includeExtendedStates = false)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                prefs.theme = oldTheme
                prefs.isPureBlackEnabled = oldBlack
                prefs.sectionLayoutMode = oldLayoutMode
                prefs.isFirstRun = oldFirstRun
            }
            app.sessionManager.consumeStartupError()
            app.sessionManager.clearExperimentalSelection()
        }
    }

    private fun captureThemeSet(
        prefix: String,
        seed: MatrixSeed,
        includeExtendedStates: Boolean,
    ) {
        val isClassic = component.preferences.isClassicSectionLayout
        QaActivityController().use { controller ->
            openStatistics(controller)
            waitForSettled(controller)

            // R01 + R02 Overview
            assertMinimumTouchTargets(controller)
            assertNoTextClipping(controller)
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                assertEquals(
                    "Matrix capture requires Russian UI localization",
                    "Статистика",
                    fragment.getString(R.string.unified_statistics_title)
                )
                findScrollView(fragment)?.scrollTo(0, 0)
                val uiState = field<UnifiedReportUiState>(fragment, "currentUiState")
                val currentTab = field<UnifiedAnalyticsTab>(fragment, "currentTab")
                val content = fragment.view?.findViewWithTag<ViewGroup>("unified_content")
                val contentTexts = content?.let { collectTexts(it) } ?: emptyList()
                val overview = requireNotNull(fragment.view?.findViewWithTag<View>("habitOverviewLayout")) {
                    "Missing habitOverviewLayout: uiState=$uiState, tab=$currentTab, childCount=${content?.childCount}, texts=$contentTexts"
                }
                assertNotNull(overview.findViewWithTag<View>("habitCadenceDailyRow"))
                assertNull("Overview must NOT show secondary weekly row (R02)", overview.findViewWithTag<View>("habitCadenceWeeklyRow"))
                if (isClassic) {
                    assertClassicSurfaceCoherence(fragment)
                }
            }
            idle()
            capture("${prefix}_overview")

            // R03 + R04 Habits Tab (Current week: ongoing neutral + near cap warning + exceeded danger)
            selectTab(controller, 1)
            assertNoTextClipping(controller)
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val actionsStrip = requireNotNull(fragment.view?.findViewWithTag<View>("habitAlphaPreviewList"))
                assertNotNull(actionsStrip.findViewWithTag<View>("habitAllHabitsButton"))
                assertNotNull(actionsStrip.findViewWithTag<View>("habitCompareEntry"))
                val textsInView = collectTexts(requireNotNull(fragment.view))
                assertTrue(
                    "R04 comfortably-within ongoing limit must clarify ongoing",
                    textsInView.any { it.contains("период идёт") || it.contains("ongoing") }
                )
                if (isClassic) {
                    assertClassicSurfaceCoherence(fragment)
                }
            }
            capture("${prefix}_habits")

            if (!includeExtendedStates && prefix == "classic_light") {
                selectTab(controller, 2)
                assertNoTextClipping(controller)
                capture("${prefix}_tasks_populated")

                selectTab(controller, 3)
                assertNoTextClipping(controller)
                capture("${prefix}_focus_multi")
            }

            if (includeExtendedStates) {
                // Habits scrolled to bottom action strip
                controller.onActivity { activity ->
                    val scroll = findScrollView(statsFragment(activity))
                    scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                idle()
                capture("${prefix}_habits_scroll")

                // Previous closed week -> closed satisfied AT_MOST ("Видеоигры" 120/180 -> SUCCESS)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    findScrollView(fragment)?.scrollTo(0, 0)
                    fragment.view?.findViewWithTag<View>("unified_prev")?.performClick()
                }
                idle()
                waitForSettled(controller)
                assertNoTextClipping(controller)
                controller.onActivity { activity ->
                    val content = requireNotNull(statsFragment(activity).view?.findViewWithTag<View>("unified_content"))
                    assertNotNull(
                        "Previous closed week must render closed AT_MOST summary card",
                        content.findViewWithTag<View>("habitOngoingSummary")
                    )
                }
                capture("${prefix}_habits_prev_closed_atmost")

                // Return to current week
                controller.onActivity { activity ->
                    statsFragment(activity).view?.findViewWithTag<View>("unified_next")?.performClick()
                }
                idle()
                waitForSettled(controller)

                // Month, Year, All period traversal
                selectPeriodViaUi(controller, UnifiedPeriod.MONTH)
                assertNoTextClipping(controller)
                capture("${prefix}_habits_month")

                selectPeriodViaUi(controller, UnifiedPeriod.YEAR)
                assertNoTextClipping(controller)
                capture("${prefix}_habits_year")

                selectPeriodViaUi(controller, UnifiedPeriod.ALL)
                assertNoTextClipping(controller)
                capture("${prefix}_habits_all")

                // Restore WEEK for Tasks & Focus
                selectPeriodViaUi(controller, UnifiedPeriod.WEEK)

                // Tasks populated cohorts
                selectTab(controller, 2)
                assertNoTextClipping(controller)
                capture("${prefix}_tasks_populated")

                // Tasks empty-state under previous MONTH + ALL (R06: must NOT suggest switching to All sections!)
                selectPeriodViaUi(controller, UnifiedPeriod.MONTH)
                controller.onActivity { activity ->
                    statsFragment(activity).view?.findViewWithTag<View>("unified_prev")?.performClick()
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val content = requireNotNull(fragment.view?.findViewWithTag<View>("unified_content"))
                    assertNotNull("Empty state card must render on previous month without tasks", content.findViewWithTag<View>("unified_tasks_empty_card"))
                    val textsInContent = collectTexts(content)
                    assertFalse(
                        "R06 empty state under All must NOT ask user to reset scope filter: $textsInContent",
                        textsInContent.any { it.contains("все разделы", ignoreCase = true) || it.contains("All sections", ignoreCase = true) }
                    )
                }
                capture("${prefix}_tasks_empty_all_day")

                // Tasks empty-state under empty scoped container -> suggests switching to All sections
                controller.onActivity { activity ->
                    statsFragment(activity).applyScopedContainer(seed.emptyId, includeDescendants = true)
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val content = requireNotNull(statsFragment(activity).view?.findViewWithTag<View>("unified_content"))
                    assertNotNull("Scoped empty state card must render", content.findViewWithTag<View>("unified_tasks_empty_card"))
                    val textsInContent = collectTexts(content)
                    assertTrue(
                        "R06 scoped empty state must offer switching to All sections: $textsInContent",
                        textsInContent.any { it.contains("все разделы", ignoreCase = true) || it.contains("All sections", ignoreCase = true) }
                    )
                }
                capture("${prefix}_tasks_empty_scoped")

                // Restore current month (unified_next) then scope = All, waiting for each transition to settle
                controller.onActivity { activity ->
                    statsFragment(activity).view?.findViewWithTag<View>("unified_next")?.performClick()
                }
                idle()
                waitForSettled(controller)

                controller.onActivity { activity ->
                    statsFragment(activity).applyScopedAll()
                }
                idle()
                waitForSettled(controller)
                selectPeriodViaUi(controller, UnifiedPeriod.WEEK)

                selectTab(controller, 3)
                assertNoTextClipping(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    val uiState = field<UnifiedReportUiState>(fragment, "currentUiState") as? UnifiedReportUiState.Success
                    val report = uiState?.report
                    val focus = report?.focus
                    val ctx = report?.context
                    val allDbSessions = (component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)).getAll()
                    val diag = "size=${focus?.sectionBreakdowns?.size}, typeSizes=${focus?.targetTypeSummaries?.size}, count=${focus?.sessionCount}, dbCount=${allDbSessions.size}, dbEnded=${allDbSessions.map { it.endedAt }}, ctxStart=${ctx?.startMillis}, ctxEnd=${ctx?.endMillis}, from=${ctx?.from}, to=${ctx?.to}, anchor=${fragment.currentAnchorValue}, scope=${ctx?.sectionScope}"
                    assertNotNull("F3 sections must appear for multiple sections ($diag)", fragment.view?.findViewWithTag<View>("unified_focus_f3_breakdown"))
                    assertNotNull("F4 source breakdown must appear for multiple sources ($diag)", fragment.view?.findViewWithTag<View>("unified_focus_f4_breakdown"))
                }
                capture("${prefix}_focus_multi")

                // Scope to Work (single section + single TASK target type -> R05 hides F3 and F4)
                controller.onActivity { activity ->
                    statsFragment(activity).applyScopedContainer(seed.workId, includeDescendants = true)
                }
                idle()
                waitForSettled(controller)
                controller.onActivity { activity ->
                    val fragment = statsFragment(activity)
                    assertNull("R05 must hide F3 when sectionBreakdowns.size <= 1", fragment.view?.findViewWithTag<View>("unified_focus_f3_breakdown"))
                    assertNull("R05 must hide F4 when targetTypeSummaries.size <= 1", fragment.view?.findViewWithTag<View>("unified_focus_f4_breakdown"))
                }
                capture("${prefix}_focus_scoped_single")

                // Reset scope to All before closing controller
                controller.onActivity { activity ->
                    statsFragment(activity).applyScopedAll()
                }
                idle()
                waitForSettled(controller)
            }
        }
    }

    private fun assertClassicSurfaceCoherence(fragment: UnifiedStatisticsFragment) {
        val root = fragment.view?.findViewWithTag<View>("unified_statistics_root")
            ?: fragment.view as? ViewGroup ?: return
        val content = fragment.view?.findViewWithTag<ViewGroup>("unified_content") ?: return
        val rootColor = (root.background as? ColorDrawable)?.color
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                assertEquals("Classic section $i must have 0 topMargin (R07)", 0, lp.topMargin)
            }
        }
        assertEquals("Classic content top padding must be 0dp (R07)", 0, content.paddingTop)
        if (rootColor != null && !component.preferences.isPureBlackEnabled && component.preferences.theme == ThemeSwitcher.THEME_LIGHT) {
            assertEquals("Classic Light root background must be white surface", Color.WHITE, rootColor)
        }
    }

    private fun seedMatrixDataset(): MatrixSeed {
        val today = getToday()
        setToday(today)
        val now = System.currentTimeMillis()
        val prevWeekDay = today.minus(7)

        val orgFacade = component.organizationFacade
        val queries = requireNotNull(orgFacade.containerQueries)
        val healthId = queries.roots().firstOrNull { it.name == "Здоровье" }?.id
            ?: orgFacade.createContainer("Здоровье").getOrThrow().id
        val workId = queries.roots().firstOrNull { it.name == "Работа" }?.id
            ?: orgFacade.createContainer("Работа").getOrThrow().id
        val emptyId = queries.roots().firstOrNull { it.name == "Пустой раздел" }?.id
            ?: orgFacade.createContainer("Пустой раздел").getOrThrow().id

        val habitList = component.habitList
        val factory = component.sqlModelFactory

        fun ensureHabit(name: String, configure: org.isoron.uhabits.core.models.Habit.() -> Unit): org.isoron.uhabits.core.models.Habit {
            val existing = habitList.firstOrNull { it.name == name }
            if (existing != null) return existing
            return factory.buildHabit().apply {
                uuid = UUID.randomUUID().toString()
                this.name = name
                configure()
            }.also { habitList.add(it) }
        }

        // 1. Daily Boolean #1
        val dailyRun = ensureHabit("Зарядка") {
            frequency = Frequency.DAILY
            type = HabitType.YES_NO
            color = PaletteColor(7)
        }
        orgFacade.placeHabit(requireNotNull(dailyRun.uuid), healthId)
        dailyRun.originalEntries.apply {
            clear()
            for (d in 0..14) {
                add(Entry(today.minus(d), if (d == 3 || d == 9) Entry.NO else Entry.YES_MANUAL))
            }
        }
        habitList.update(dailyRun)

        // 2. Daily Boolean #2
        val dailyMed = ensureHabit("Медитация") {
            frequency = Frequency.DAILY
            type = HabitType.YES_NO
            color = PaletteColor(9)
        }
        orgFacade.placeHabit(requireNotNull(dailyMed.uuid), healthId)
        dailyMed.originalEntries.apply {
            clear()
            for (d in 0..14) {
                add(Entry(today.minus(d), if (d == 1 || d == 5 || d == 11) Entry.NO else Entry.YES_MANUAL))
            }
        }
        habitList.update(dailyMed)

        // 3. Weekly AT_LEAST (9 / 15 км)
        val weeklyRun = ensureHabit("Бег за неделю") {
            frequency = Frequency.WEEKLY
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_LEAST
            targetValue = 15.0
            unit = "км"
            color = PaletteColor(8)
        }
        orgFacade.placeHabit(requireNotNull(weeklyRun.uuid), healthId)
        val twoWeeksAgoDay = today.minus(14)

        weeklyRun.originalEntries.apply {
            clear()
            add(Entry(today, 9_000))
            add(Entry(prevWeekDay, 16_000))
            add(Entry(twoWeeksAgoDay, 15_000))
        }
        habitList.update(weeklyRun)

        // 4. Weekly AT_MOST comfortably within cap (60 / 180 -> NEUTRAL ongoing) + closed previous week (120 / 180 -> SUCCESS)
        val games = ensureHabit("Видеоигры") {
            frequency = Frequency.WEEKLY
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 180.0
            unit = "мин"
            color = PaletteColor(4)
        }
        orgFacade.placeHabit(requireNotNull(games.uuid), workId)
        games.originalEntries.apply {
            clear()
            add(Entry(today, 60_000))
            add(Entry(prevWeekDay, 120_000))
            add(Entry(twoWeeksAgoDay, 100_000))
        }
        habitList.update(games)

        // 5. Weekly AT_MOST near limit (90 / 100 = 90% -> WARNING)
        val social = ensureHabit("Соцсети") {
            frequency = Frequency.WEEKLY
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 100.0
            unit = "мин"
            color = PaletteColor(2)
        }
        orgFacade.placeHabit(requireNotNull(social.uuid), workId)
        social.originalEntries.apply {
            clear()
            add(Entry(today, 90_000))
            add(Entry(prevWeekDay, 80_000))
            add(Entry(twoWeeksAgoDay, 70_000))
        }
        habitList.update(social)

        // 6. Weekly AT_MOST exceeded (85 / 60 -> DANGER)
        val sweets = ensureHabit("Сладкое") {
            frequency = Frequency.WEEKLY
            type = HabitType.NUMERICAL
            targetType = NumericalHabitType.AT_MOST
            targetValue = 60.0
            unit = "г"
            color = PaletteColor(0)
        }
        orgFacade.placeHabit(requireNotNull(sweets.uuid), healthId)
        sweets.originalEntries.apply {
            clear()
            add(Entry(today, 85_000))
            add(Entry(prevWeekDay, 75_000))
            add(Entry(twoWeeksAgoDay, 50_000))
        }
        habitList.update(sweets)

        // Tasks: Completed today (so WEEK is populated while prev MONTH = Oct has NO tasks -> R06 empty state!), overdue active, no-due completed
        val taskService = component.userTaskService
        val taskRepo = component.userTaskRepository
        val todayBaseMs = org.isoron.platform.time.startOfLocalDay(today, 0, 0) + 3L * 3_600_000L
        val yDate = today.minus(1)

        if (taskRepo.getRoots().isEmpty()) {
            val tDoneDue = taskService.createTask("Подготовить квартальный отчёт", containerId = workId, due = TaskDue.Day(today.toTaskDueDate()))
            taskRepo.save(tDoneDue.copy(createdAt = todayBaseMs - 3_600_000L, status = TaskStatus.COMPLETED, completedAt = todayBaseMs))

            val tDoneNoDue = taskService.createTask("Разобрать заметки недели", containerId = healthId, due = TaskDue.None)
            taskRepo.save(tDoneNoDue.copy(createdAt = todayBaseMs - 7_200_000L, status = TaskStatus.COMPLETED, completedAt = todayBaseMs - 1_800_000L))

            taskService.createTask("Отправить налоговую форму", containerId = workId, due = TaskDue.Day(yDate.toTaskDueDate()))

            val tActiveNoDue = taskService.createTask("Купить фильтр для воды", containerId = healthId, due = TaskDue.None)
            taskRepo.save(tActiveNoDue)
        }

        // Focus sessions: Work Task + Health Habit + Unassigned -> multi-section & multi-type on WEEK, single on Work
        val focusRepo = component.focusSessionRepository ?: SQLiteFocusSessionRepository(component.db)
        val existingTask = taskRepo.getRoots().firstOrNull()
        val weekNoonMs = org.isoron.platform.time.startOfLocalDay(today, 0, 0) + 2L * 3_600_000L
        val sessions = listOf(
            FocusSession(
                id = FocusSessionId("matrix-fs-work-1"),
                startedAt = weekNoonMs - 2_700_000L,
                endedAt = weekNoonMs,
                durationMs = 2_700_000L, // 45m
                taskId = existingTask?.id,
                containerId = workId,
                source = FocusSessionSource.TIMER,
                createdAt = weekNoonMs,
                updatedAt = weekNoonMs,
            ),
            FocusSession(
                id = FocusSessionId("matrix-fs-health-2"),
                startedAt = weekNoonMs + 3_600_000L,
                endedAt = weekNoonMs + 5_400_000L,
                durationMs = 1_800_000L, // 30m
                habit = HabitRef(requireNotNull(weeklyRun.uuid)),
                containerId = healthId,
                source = FocusSessionSource.TIMER,
                createdAt = weekNoonMs + 5_400_000L,
                updatedAt = weekNoonMs + 5_400_000L,
            ),
            FocusSession(
                id = FocusSessionId("matrix-fs-free-3"),
                startedAt = weekNoonMs + 7_200_000L,
                endedAt = weekNoonMs + 8_100_000L,
                durationMs = 900_000L, // 15m
                containerId = null,
                source = FocusSessionSource.TIMER,
                createdAt = weekNoonMs + 8_100_000L,
                updatedAt = weekNoonMs + 8_100_000L,
            ),
        )
        sessions.forEach { focusRepo.record(it) }

        return MatrixSeed(healthId = healthId, workId = workId, emptyId = emptyId)
    }

    private fun openStatistics(controller: QaActivityController) {
        controller.onActivity { activity ->
            activity.navigate(MainDestination.STATISTICS)
            activity.supportFragmentManager.executePendingTransactions()
        }
        idle()
        waitForSettled(controller)
        selectTab(controller, 0)
    }

    private fun statsFragment(activity: MainActivity): UnifiedStatisticsFragment {
        return activity.supportFragmentManager.findFragmentByTag("main.reports") as UnifiedStatisticsFragment
    }

    private fun selectTab(controller: QaActivityController, index: Int) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val tabs = fragment.view?.findViewWithTag<TabLayout>("unified_tabs")
            requireNotNull(tabs?.getTabAt(index)) { "Missing tab index $index" }.select()
        }
        idle()
        waitForSettled(controller)
    }

    private fun selectPeriodViaUi(controller: QaActivityController, period: UnifiedPeriod) {
        val labelRes = when (period) {
            UnifiedPeriod.DAY -> R.string.unified_statistics_period_day
            UnifiedPeriod.WEEK -> R.string.unified_statistics_period_week
            UnifiedPeriod.MONTH -> R.string.unified_statistics_period_month
            UnifiedPeriod.YEAR -> R.string.unified_statistics_period_year
            UnifiedPeriod.ALL -> R.string.unified_statistics_period_all
        }
        var target = ""
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            target = fragment.getString(labelRes)
            requireNotNull(fragment.view?.findViewWithTag<View>("unified_period")).performClick()
        }
        idle()
        SystemClock.sleep(250)
        val item = requireNotNull(device.wait(Until.findObject(By.text(target)), 3000)) {
            "Period popup option '$target' for $period did not appear"
        }
        item.click()
        idle()
        waitForSettled(controller)
        controller.onActivity { activity ->
            val actual = field<UnifiedPeriod>(statsFragment(activity), "currentPeriod")
            assertEquals("Period must switch to $period", period, actual)
        }
    }

    private fun waitForSettled(controller: QaActivityController, timeoutMs: Long = 8_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var busy = true
            controller.onActivity { activity ->
                val fragment = statsFragment(activity)
                val uiState = field<UnifiedReportUiState>(fragment, "currentUiState")
                busy = uiState is UnifiedReportUiState.InitialLoading ||
                    (uiState is UnifiedReportUiState.Success && uiState.isRefreshing)
            }
            if (!busy) return
            SystemClock.sleep(50)
        }
        error("UnifiedStatisticsFragment did not settle in ${timeoutMs}ms")
    }

    private fun assertMinimumTouchTargets(controller: QaActivityController) {
        controller.onActivity { activity ->
            val fragment = statsFragment(activity)
            val density = activity.resources.displayMetrics.density
            val rect = Rect()
            for (tag in listOf("unified_prev", "unified_next", "unified_period", "unified_scope")) {
                val view = fragment.view?.findViewWithTag<View>(tag) ?: continue
                if (view.isShown && view.getGlobalVisibleRect(rect)) {
                    assertTrue("$tag width < 48dp (${view.width / density}dp)", view.width / density >= 47.5f)
                    assertTrue("$tag height < 48dp (${view.height / density}dp)", view.height / density >= 47.5f)
                }
            }
        }
    }

    private fun assertNoTextClipping(controller: QaActivityController) {
        controller.onActivity { activity ->
            val root = statsFragment(activity).view ?: return@onActivity
            val views = mutableListOf<TextView>()
            fun walk(v: View) {
                if (v is TextView && v.isShown && v.width > 0 && v.height > 0) views.add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            for (tv in views) {
                val layout = tv.layout ?: continue
                val txt = tv.text?.toString().orEmpty()
                if (txt.isBlank()) continue
                val availH = tv.height - tv.compoundPaddingTop - tv.compoundPaddingBottom
                assertTrue("TextView clipped '$txt': textH=${layout.height} > availH=$availH", layout.height <= availH + 4)
            }
        }
    }

    private fun collectTexts(root: View): List<String> = buildList {
        if (root is TextView) add(root.text.toString())
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(collectTexts(root.getChildAt(i)))
    }

    private fun findScrollView(fragment: UnifiedStatisticsFragment): ScrollView? {
        fun walk(v: View?): ScrollView? {
            if (v is ScrollView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        return walk(fragment.view)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(300)
        val target = File(outputDir, "$name.png")
        check(device.takeScreenshot(target)) { "Failed screenshot $name" }
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        SystemClock.sleep(150)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
}
