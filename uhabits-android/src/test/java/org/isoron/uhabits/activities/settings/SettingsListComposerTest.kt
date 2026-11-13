package org.isoron.uhabits.activities.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.isoron.uhabits.R
import java.nio.file.Files
import java.nio.file.Path

class SettingsListComposerTest {
    private val sections = listOf(
        section(SettingsSectionId.APPEARANCE, "appearance", "theme"),
        section(SettingsSectionId.HABITS, "habits", "toggle"),
        section(SettingsSectionId.SYNC, "sync", "sync-now")
    )

    @Test
    fun homeContainsOnlyCompactSections() {
        val items = SettingsListComposer.composeHome(sections, { it.title }, { _ -> })

        assertEquals(3, items.size)
        assertTrue(items.all { it is SettingItem.Section })
        assertFalse(items.any { it.stableKey == "navigation:toggle" })
    }

    @Test
    fun adaptiveProfileUsesApprovedBreakpoints() {
        assertEquals(SettingsAdaptiveProfile.COMPACT, SettingsAdaptiveProfile.forWidthDp(320))
        assertEquals(SettingsAdaptiveProfile.REGULAR, SettingsAdaptiveProfile.forWidthDp(360))
        assertEquals(SettingsAdaptiveProfile.REGULAR, SettingsAdaptiveProfile.forWidthDp(411))
        assertEquals(SettingsAdaptiveProfile.WIDE, SettingsAdaptiveProfile.forWidthDp(412))
        assertEquals(SettingsAdaptiveProfile.WIDE, SettingsAdaptiveProfile.forWidthDp(600))
    }

    @Test
    fun homeSectionCanRunDirectActionWithoutOpeningDetail() {
        var directActions = 0
        var openedSections = 0
        val statistics = SettingsSectionModel.fromItems(
            id = SettingsSectionId.STATISTICS,
            iconRes = 1,
            title = "Statistics",
            summary = "All time",
            items = emptyList(),
            homeAction = { directActions++ }
        )

        val item = SettingsListComposer.composeHome(
            listOf(statistics),
            { it.title },
            { _ -> openedSections++ }
        ).single() as SettingItem.Section
        item.onClick(null)

        assertEquals(1, directActions)
        assertEquals(0, openedSections)
        assertEquals(null, item.endIconRes)
    }

    @Test
    fun detailPreservesStableNavigationContentWhileBusy() {
        val busyAction = SettingItem.Navigation(
            key = "syncNow",
            iconRes = 1,
            title = "Sync now",
            summary = "Send and receive changes",
            isBusy = true,
            presentation = SettingPresentation(enabled = false)
        )
        val section = SettingsSectionModel.fromItems(
            id = SettingsSectionId.SYNC,
            iconRes = 1,
            title = "Sync",
            summary = "Syncing",
            items = listOf(busyAction)
        )

        val item = SettingsListComposer.composeDetail(section).single() as SettingItem.Navigation

        assertTrue(item.isBusy)
        assertFalse(item.presentation.enabled)
        assertEquals("Sync now", item.title)
        assertEquals("Send and receive changes", item.summary)
    }

    @Test
    fun navigationRowsDoNotRenderASecondBusyIndicator() {
        val composeRows = readSettingsSource("SettingsComposeRows.kt")

        assertTrue(composeRows.contains("is SettingItem.Status"))
        assertTrue(composeRows.contains("CircularProgressIndicator"))
        assertTrue(composeRows.contains("item.reserveProgressSpace"))
        assertFalse(
            "Navigation rows should keep standard click/chevron rendering without progress spinners",
            composeRows.substringAfter("is SettingItem.Navigation").substringBefore("is SettingItem.Switch")
                .contains("CircularProgressIndicator")
        )
    }

    @Test
    fun detailContainsOnlySelectedSectionGroups() {
        val items = SettingsListComposer.composeDetail(sections[1])

        assertEquals(listOf("subsection:habits_main", "navigation:toggle"), items.map { it.stableKey })
        assertTrue(items.first().presentation.isFirst)
        assertTrue(items.last().presentation.isLast)
        assertEquals("habits_main", items.last().presentation.cardId)
    }

    @Test
    fun stableKeysAreUnique() {
        val items = SettingsListComposer.composeDetail(sections[0])

        assertEquals(items.size, items.map { it.stableKey }.distinct().size)
    }

    @Test
    fun iconRegistryHasValidDrawablesAndOnlyAllowedSemanticRepeats() {
        val drawableIds = R.drawable::class.java.fields.map { it.getInt(null) }.toSet()
        assertTrue(SettingsIconRegistry.icons.values.all { it != 0 })
        assertTrue(SettingsIconRegistry.icons.values.all { it in drawableIds })

        val repeats = SettingsIconRegistry.icons.entries
            .groupBy({ it.value }, { it.key })
            .values
            .filter { it.size > 1 }
            .map { it.toSet() }
            .toSet()
        assertEquals(SettingsIconRegistry.allowedSemanticRepeats, repeats)
    }

    @Test
    fun detailGroupsDoNotReuseIconsForDifferentActions() {
        val groups = listOf(
            listOf("pref_sticky_notifications", "reminderCustomize", "reminderTest"),
            listOf(
                "pref_pomodoro_focus_alert",
                "pref_pomodoro_break_alert",
                "pomodoroAlertChannel",
                "pomodoroTestAlert"
            ),
            listOf("pomodoroProgressChannel", "pomodoroExactAlarm"),
            listOf("restoreBackup", "importData"),
            listOf("hardResetStatistics"),
            listOf("syncNow", "syncReview")
        )

        groups.forEach { keys ->
            val icons = keys.map { checkNotNull(SettingsIconRegistry.icons[it]) }
            assertEquals(keys.size, icons.distinct().size)
        }

        assertEquals(R.drawable.ic_settings_bell_cog, SettingsIconRegistry.icons["reminderCustomize"])
        assertEquals(R.drawable.ic_settings_bell_check, SettingsIconRegistry.icons["reminderTest"])
        assertEquals(R.drawable.ic_settings_clock_cog, SettingsIconRegistry.icons["pomodoroAlertChannel"])
        assertEquals(R.drawable.ic_settings_clock_check, SettingsIconRegistry.icons["pomodoroTestAlert"])
        assertNotEquals(
            SettingsIconRegistry.icons["reminderCustomize"],
            SettingsIconRegistry.icons["pomodoroAlertChannel"]
        )
    }

    @Test
    fun registryReflectsUnifiedRestoreAndHiddenDeveloperActions() {
        assertEquals(R.drawable.ic_settings_database_down, SettingsIconRegistry.icons["restoreBackup"])
        assertTrue("Stored encryption preference mapping must stay", SettingsIconRegistry.icons.containsKey("pref_encryption_key"))
        listOf(
            "restorePublicBackup",
            "repairDB",
            "seedDemoData",
            "resetDemoData",
            "refreshScreens",
            "softResetStatistics"
        ).forEach { assertFalse(SettingsIconRegistry.icons.containsKey(it)) }
    }

    @Test
    fun settingsVectorFilesAreNotCopiesWithDifferentNames() {
        val drawableDir = listOf(
            Path.of("src/main/res/drawable"),
            Path.of("uhabits-android/src/main/res/drawable")
        ).first { Files.isDirectory(it) }
        val normalized = Files.list(drawableDir).use { paths ->
            paths.filter { it.fileName.toString().startsWith("ic_settings_") }
                .filter { it.fileName.toString().endsWith(".xml") }
                .map { path ->
                    path.fileName.toString() to
                        String(Files.readAllBytes(path), Charsets.UTF_8).replace(Regex("\\s+"), "")
                }
                .toList()
        }
        val duplicateContents = normalized.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }

        assertTrue("Duplicate settings vectors: $duplicateContents", duplicateContents.isEmpty())
        assertNotEquals(0, normalized.size)
    }

    @Test
    fun polishedIconsUseConsistentOutlineGeometry() {
        val drawableDir = listOf(
            Path.of("src/main/res/drawable"),
            Path.of("uhabits-android/src/main/res/drawable")
        ).first { Files.isDirectory(it) }

        listOf(
            "ic_settings_pomodoro_tomato.xml",
            "ic_settings_bell_cog.xml",
            "ic_settings_clock_cog.xml",
            "ic_settings_clock_check.xml",
            "ic_settings_bell_check.xml",
            "ic_settings_database_down.xml",
            "ic_settings_history.xml"
        ).forEach { fileName ->
            val xml = String(Files.readAllBytes(drawableDir.resolve(fileName)), Charsets.UTF_8)
            assertTrue(
                "$fileName must use a 24x24 viewport",
                xml.contains("android:viewportWidth=\"24\"") &&
                    xml.contains("android:viewportHeight=\"24\"")
            )
            assertTrue(
                "$fileName must use an effective stroke width of 2",
                xml.contains("android:strokeWidth=\"2\"") ||
                    (
                        xml.contains("android:scaleX=\"0.5\"") &&
                            xml.contains("android:strokeWidth=\"4\"")
                    )
            )
            assertTrue("$fileName must use round caps", xml.contains("android:strokeLineCap=\"round\""))
            assertTrue("$fileName must use round joins", xml.contains("android:strokeLineJoin=\"round\""))
        }
    }

    @Test
    fun pomodoroIconUsesOrganicTomatoGeometry() {
        val drawableDir = listOf(
            Path.of("src/main/res/drawable"),
            Path.of("uhabits-android/src/main/res/drawable")
        ).first { Files.isDirectory(it) }
        val xml = String(
            Files.readAllBytes(drawableDir.resolve("ic_settings_pomodoro_tomato.xml")),
            Charsets.UTF_8
        )

        assertTrue("tomato must retain Arcticons attribution", xml.contains("Arcticons Team"))
        assertTrue("tomato must retain its CC BY-SA license note", xml.contains("CC BY-SA 4.0"))
        assertTrue("48px source geometry must be normalized to 24px", xml.contains("android:scaleX=\"0.5\"") && xml.contains("android:scaleY=\"0.5\""))
        assertFalse("left-side Arcticons accent strokes must be removed", xml.contains("4.306,15.41") || xml.contains("7.38,20.85"))
        assertTrue("tomato lower contour must retain its subtle optical asymmetry", xml.contains("c0,9.4 8.9,17.2 20.1,17.2s20.9,-7.8"))
        assertFalse("tomato must not contain a decorative highlight", xml.contains("M7.1,13.25"))
    }

    @Test
    fun notificationAndRestoreIconsRetainOpenSourceProvenance() {
        val drawableDir = listOf(
            Path.of("src/main/res/drawable"),
            Path.of("uhabits-android/src/main/res/drawable")
        ).first { Files.isDirectory(it) }
        val notification = String(
            Files.readAllBytes(drawableDir.resolve("ic_settings_bell_check.xml")),
            Charsets.UTF_8
        )
        val restore = String(
            Files.readAllBytes(drawableDir.resolve("ic_settings_database_down.xml")),
            Charsets.UTF_8
        )

        val notificationSettings = String(
            Files.readAllBytes(drawableDir.resolve("ic_settings_bell_cog.xml")),
            Charsets.UTF_8
        )
        val pomodoroSignalSettings = String(
            Files.readAllBytes(drawableDir.resolve("ic_settings_clock_cog.xml")),
            Charsets.UTF_8
        )
        val pomodoroSignalTest = String(
            Files.readAllBytes(drawableDir.resolve("ic_settings_clock_check.xml")),
            Charsets.UTF_8
        )

        assertTrue(notification.contains("Tabler Icons \"bell-check\""))
        assertTrue(notification.contains("MIT License"))
        assertTrue(notification.contains("M11.5,17H4"))
        assertTrue(notificationSettings.contains("Tabler Icons \"bell-cog\""))
        assertTrue(notificationSettings.contains("M12,17H4"))
        assertTrue(pomodoroSignalSettings.contains("Tabler Icons \"clock-cog\""))
        assertTrue(pomodoroSignalSettings.contains("MIT License"))
        assertTrue(pomodoroSignalSettings.contains("M21,12a9,9"))
        assertTrue(pomodoroSignalTest.contains("Tabler Icons \"clock-check\""))
        assertTrue(pomodoroSignalTest.contains("MIT License"))
        assertTrue(pomodoroSignalTest.contains("M20.942,13.021"))
        assertTrue(restore.contains("Lucide \"database-backup\""))
        assertTrue(restore.contains("ISC License"))
        assertTrue(restore.contains("M13,20a5,5"))
    }

    @Test
    fun allSettingRowLayoutsUseSharedDividerGeometry() {
        val valuesDir = listOf(
            Path.of("src/main/res/values"),
            Path.of("uhabits-android/src/main/res/values")
        ).first { Files.isDirectory(it) }
        val dimensXml = String(Files.readAllBytes(valuesDir.resolve("dimens.xml")), Charsets.UTF_8)
        val composeRows = readSettingsSource("SettingsComposeRows.kt")

        assertTrue(dimensXml.contains("name=\"settings_detail_row_divider_start\">52dp"))
        assertTrue(dimensXml.contains("name=\"settings_detail_group_divider_start\">18dp"))
        assertTrue(dimensXml.contains("name=\"settings_detail_divider_end\">16dp"))
        assertTrue(composeRows.contains("R.dimen.settings_detail_row_divider_start"))
        assertTrue(composeRows.contains("R.dimen.settings_detail_group_divider_start"))
        assertTrue(composeRows.contains("R.dimen.settings_detail_divider_end"))
    }

    private fun readSettingsSource(fileName: String): String {
        val sourceDir = listOf(
            Path.of("src/main/java/org/isoron/uhabits/activities/settings"),
            Path.of("uhabits-android/src/main/java/org/isoron/uhabits/activities/settings")
        ).first { Files.isDirectory(it) }
        return String(Files.readAllBytes(sourceDir.resolve(fileName)), Charsets.UTF_8)
    }

    @Test
    fun singleRowGroupMarksOnlyRowAsLast() {
        val section = SettingsSectionModel.fromItems(
            id = SettingsSectionId.DATA,
            iconRes = 1,
            title = "Data",
            summary = "Data",
            items = listOf(
                SettingItem.Subsection(
                    key = "restore",
                    title = "Restore",
                    presentation = SettingPresentation()
                ),
                SettingItem.Navigation(
                    key = "restoreBackup",
                    iconRes = 2,
                    title = "Restore"
                )
            )
        )

        val items = SettingsListComposer.composeDetail(section)

        assertFalse(items.first().presentation.isLast)
        assertTrue(items.last().presentation.isLast)
    }

    @Test
    fun appearanceDetailComposesSegmentedThemeColorPickerAndFixedSeparator() {
        val appearance = SettingsSectionModel.fromItems(
            id = SettingsSectionId.APPEARANCE,
            iconRes = R.drawable.ic_settings_theme,
            title = "Appearance",
            summary = "Dark · #64B5F6 accent",
            items = listOf(
                SettingItem.Subsection("appearance_main", "General", presentation = SettingPresentation()),
                SettingItem.SegmentedTheme(
                    key = "pref_theme",
                    iconRes = R.drawable.ic_settings_theme,
                    title = "Theme",
                    themeValue = 1,
                    pureBlackValue = true,
                    onSegmentSelected = { _, _ -> }
                ),
                SettingItem.ColorPicker(
                    key = "pref_accent_color",
                    iconRes = R.drawable.ic_settings_droplet,
                    title = "Accent color",
                    summary = "#64B5F6",
                    colorString = "preset:blue",
                    onClick = {}
                ),
                SettingItem.Subsection("appearance_interface", "Interface", presentation = SettingPresentation()),
                SettingItem.Switch(
                    key = "pref_show_habit_card_borders",
                    iconRes = R.drawable.ic_settings_layout_list,
                    title = "Card borders",
                    summary = "Show subtle outlines around cards",
                    checked = true,
                    onCheckedChange = {}
                )
            )
        )

        val composed = SettingsListComposer.composeDetail(appearance)

        assertEquals(5, composed.size)
        assertTrue(composed[0].presentation.isFirst)
        assertFalse(composed[0].presentation.isLast)
        assertEquals(composed[0].presentation.cardId, composed[1].presentation.cardId)
        assertEquals(composed[0].presentation.cardId, composed[2].presentation.cardId)
        assertTrue(composed[2].presentation.isLast)
        assertTrue(composed[3].presentation.isFirst)
        assertTrue(composed[4].presentation.isLast)
        assertNotEquals(composed[0].presentation.cardId, composed[3].presentation.cardId)

        val valuesDir = listOf(
            Path.of("src/main/res/values"),
            Path.of("uhabits-android/src/main/res/values")
        ).first { Files.isDirectory(it) }
        val stringsXml = String(Files.readAllBytes(valuesDir.resolve("strings.xml")), Charsets.UTF_8)
        assertTrue(stringsXml.contains("<string name=\"settings_summary_appearance\">%1\$s · %2\$s accent</string>"))
        assertTrue(stringsXml.contains("<string name=\"custom_color_modes\">HEX · HSV · RGB</string>"))
        assertFalse(
            stringsXml.lineSequence()
                .filter {
                    it.contains("settings_summary_") ||
                        it.contains("backup_status_") ||
                        it.contains("sync_account_") ||
                        it.contains("custom_color_")
                }
                .any { it.contains("В·") }
        )
    }

    @Test
    fun habitsDetailComposesSpheresDayTiersBehaviorAndArchiveCards() {
        val enabledHabits = SettingsSectionModel.fromItems(
            id = SettingsSectionId.HABITS,
            iconRes = R.drawable.ic_settings_spheres,
            title = "Habits & Day",
            summary = "Areas and day levels enabled",
            items = listOf(
                SettingItem.Subsection("habits_spheres", "Areas", presentation = SettingPresentation()),
                SettingItem.Switch(
                    key = "pref_enable_habit_spheres",
                    iconRes = R.drawable.ic_settings_spheres,
                    title = "Enable areas",
                    summary = "Group habits by life areas",
                    checked = true,
                    onCheckedChange = {}
                ),
                SettingItem.Navigation(
                    key = "configureSpheres",
                    iconRes = R.drawable.ic_settings_configure_spheres,
                    title = "Configure areas",
                    summary = "Manage custom areas",
                    onClick = {}
                ),
                SettingItem.Switch(
                    key = "pref_show_sphere_names",
                    iconRes = R.drawable.ic_settings_squares,
                    title = "Show area names",
                    summary = "Display labels on cards",
                    checked = true,
                    onCheckedChange = {}
                ),
                SettingItem.Subsection("habits_tiers", "Day levels", presentation = SettingPresentation()),
                SettingItem.Switch(
                    key = "pref_enable_day_tiers",
                    iconRes = R.drawable.ic_settings_tiers,
                    title = "Enable day levels",
                    summary = "Track daily effort levels",
                    checked = true,
                    onCheckedChange = {}
                ),
                SettingItem.Navigation(
                    key = "pref_day_tier_sort_order",
                    iconRes = R.drawable.ic_settings_arrows_sort,
                    title = "Level sort order",
                    summary = "Minimum · Normal · Ideal · Optional",
                    onClick = {}
                ),
                SettingItem.Subsection("habits_behavior", "Behavior", presentation = SettingPresentation()),
                SettingItem.Switch("pref_habit_group_separators", R.drawable.ic_settings_separator_horizontal, "Separators", null, false, {}),
                SettingItem.Switch("pref_short_toggle", R.drawable.ic_settings_check, "Short press", "Toggle on tap", true, {}),
                SettingItem.Switch("pref_checkmark_reverse_order", R.drawable.ic_settings_reverse, "Reverse days", "Most recent last", false, {}),
                SettingItem.Switch("pref_skip_enabled", R.drawable.ic_settings_fast_forward, "Allow skip", "Mark day as skipped", true, {}),
                SettingItem.Switch("pref_unknown_enabled", R.drawable.ic_settings_question, "Unknown marks", "Show question marks", false, {}),
                SettingItem.Switch("pref_midnight_delay", R.drawable.ic_settings_clock, "Extend day", "Until 04:00", true, {}),
                SettingItem.Subsection("habits_archive", "Archive", presentation = SettingPresentation()),
                SettingItem.Navigation(
                    key = "openArchive",
                    iconRes = R.drawable.ic_settings_archive,
                    title = "Open archive",
                    summary = "View archived habits",
                    onClick = {}
                )
            )
        )

        val composedEnabled = SettingsListComposer.composeDetail(enabledHabits)
        assertEquals(16, composedEnabled.size)
        assertTrue(composedEnabled[0].presentation.isFirst)
        assertTrue(composedEnabled[3].presentation.isLast)
        assertEquals(composedEnabled[0].presentation.cardId, composedEnabled[3].presentation.cardId)
        assertTrue(composedEnabled[4].presentation.isFirst)
        assertTrue(composedEnabled[6].presentation.isLast)
        assertEquals(composedEnabled[4].presentation.cardId, composedEnabled[6].presentation.cardId)
        assertTrue(composedEnabled[7].presentation.isFirst)
        assertTrue(composedEnabled[13].presentation.isLast)
        assertTrue(composedEnabled[14].presentation.isFirst)
        assertTrue(composedEnabled[15].presentation.isLast)
        assertEquals(
            setOf(
                composedEnabled[0].presentation.cardId,
                composedEnabled[4].presentation.cardId,
                composedEnabled[7].presentation.cardId,
                composedEnabled[14].presentation.cardId
            ).size,
            4
        )

        val disabledHabits = SettingsSectionModel.fromItems(
            id = SettingsSectionId.HABITS,
            iconRes = R.drawable.ic_settings_spheres,
            title = "Habits & Day",
            summary = "Areas and day levels off",
            items = listOf(
                SettingItem.Subsection("habits_spheres", "Areas", presentation = SettingPresentation()),
                SettingItem.Switch("pref_enable_habit_spheres", R.drawable.ic_settings_spheres, "Enable areas", null, false, {}),
                SettingItem.Subsection("habits_tiers", "Day levels", presentation = SettingPresentation()),
                SettingItem.Switch("pref_enable_day_tiers", R.drawable.ic_settings_tiers, "Enable day levels", null, false, {}),
                SettingItem.Subsection("habits_behavior", "Behavior", presentation = SettingPresentation()),
                SettingItem.Switch("pref_short_toggle", R.drawable.ic_settings_check, "Short press", null, true, {}),
                SettingItem.Subsection("habits_archive", "Archive", presentation = SettingPresentation()),
                SettingItem.Navigation("openArchive", R.drawable.ic_settings_archive, "Open archive", null, onClick = {})
            )
        )

        val composedDisabled = SettingsListComposer.composeDetail(disabledHabits)
        assertEquals(8, composedDisabled.size)
        assertTrue(composedDisabled[0].presentation.isFirst)
        assertTrue(composedDisabled[1].presentation.isLast)
        assertTrue(composedDisabled[2].presentation.isFirst)
        assertTrue(composedDisabled[3].presentation.isLast)
        assertNotEquals(composedDisabled[0].presentation.cardId, composedDisabled[2].presentation.cardId)

        val valuesDir = listOf(
            Path.of("src/main/res/values"),
            Path.of("uhabits-android/src/main/res/values")
        ).first { Files.isDirectory(it) }
        val stringsXml = String(Files.readAllBytes(valuesDir.resolve("strings.xml")), Charsets.UTF_8)
        assertTrue(stringsXml.contains("<string name=\"settings_summary_habits_spheres\">Areas enabled · day levels off</string>"))
        assertTrue(stringsXml.contains("<string name=\"settings_summary_habits_tiers\">Day levels enabled · areas off</string>"))
        assertTrue(stringsXml.contains("<string name=\"pref_unknown_title\">Show “?” for empty days</string>"))
    }

    @Test
    fun notificationsAndStatisticsSectionsComposeCardsAndDirectHomeAction() {
        val notifications = SettingsSectionModel.fromItems(
            id = SettingsSectionId.NOTIFICATIONS,
            iconRes = R.drawable.ic_settings_notification_ringing,
            title = "Notifications",
            summary = "Channel configured · Exact alarms on",
            items = listOf(
                SettingItem.Subsection("notifications_general", "Habit reminders", presentation = SettingPresentation()),
                SettingItem.Switch("pref_sticky_notifications", R.drawable.ic_settings_notification, "Sticky notifications", "Keep visible", false, {}),
                SettingItem.Navigation("reminderCustomize", R.drawable.ic_settings_bell_cog, "Reminder channel", "Default", presentation = SettingPresentation(showIcon = true), onClick = {}),
                SettingItem.Navigation("reminderExactAlarm", R.drawable.ic_settings_clock, "Exact alarms", "Granted", onClick = {}),
                SettingItem.Navigation("reminderTest", R.drawable.ic_settings_bell_check, "Test reminder", "Send test notification", onClick = {}),
                SettingItem.Subsection("notifications_pomodoro", "Pomodoro completion", presentation = SettingPresentation()),
                SettingItem.Switch("pref_pomodoro_focus_alert", R.drawable.ic_settings_pomodoro_focus, "Focus alert", null, true, {}),
                SettingItem.Switch("pref_pomodoro_break_alert", R.drawable.ic_settings_pomodoro_break, "Break alert", null, true, {}),
                SettingItem.Navigation("pomodoroAlertChannel", R.drawable.ic_settings_clock_cog, "Completion channel", "Configured", presentation = SettingPresentation(showIcon = true), onClick = {}),
                SettingItem.Navigation("pomodoroTestAlert", R.drawable.ic_settings_clock_check, "Test completion signal", "Ring now", onClick = {}),
                SettingItem.Subsection("notifications_delivery", "Timer and timing", presentation = SettingPresentation()),
                SettingItem.Navigation("pomodoroProgressChannel", R.drawable.ic_settings_lock, "Timer progress channel", "Open channel settings", onClick = {}),
                SettingItem.Navigation("pomodoroExactAlarm", R.drawable.ic_settings_clock, "Exact timer alarms", "Enabled", onClick = {}),
            )
        )

        val composedNotifications = SettingsListComposer.composeDetail(notifications)
        assertEquals(13, composedNotifications.size)
        assertTrue(composedNotifications[0].presentation.isFirst)
        assertTrue(composedNotifications[4].presentation.isLast)
        assertEquals(composedNotifications[0].presentation.cardId, composedNotifications[4].presentation.cardId)
        assertTrue(composedNotifications[5].presentation.isFirst)
        assertTrue(composedNotifications[9].presentation.isLast)
        assertEquals(composedNotifications[5].presentation.cardId, composedNotifications[9].presentation.cardId)
        assertTrue(composedNotifications[10].presentation.isFirst)
        assertTrue(composedNotifications[12].presentation.isLast)
        assertEquals(composedNotifications[10].presentation.cardId, composedNotifications[12].presentation.cardId)
        assertEquals(
            3,
            setOf(
                composedNotifications[0].presentation.cardId,
                composedNotifications[5].presentation.cardId,
                composedNotifications[10].presentation.cardId
            ).size
        )

        var statisticsOpened = false
        val statistics = SettingsSectionModel.fromItems(
            id = SettingsSectionId.STATISTICS,
            iconRes = R.drawable.ic_settings_statistics,
            title = "Statistics",
            summary = "All time",
            items = emptyList(),
            homeAction = { statisticsOpened = true }
        )

        val composedHome = SettingsListComposer.composeHome(
            sections = listOf(notifications, statistics),
            accessibilityDescription = { "${it.title}: ${it.summary}" },
            onSectionClick = { }
        )
        assertEquals(2, composedHome.size)
        val statsOverview = composedHome[1] as SettingItem.Section
        assertEquals(SettingsSectionId.STATISTICS, statsOverview.sectionId)
        assertEquals("All time", statsOverview.summary)
        statsOverview.onClick(null)
        assertTrue(statisticsOpened)
        assertTrue(SettingsListComposer.composeDetail(statistics).isEmpty())
    }

    @Test
    fun dataSectionComposesStatusConditionalCutoverAndDestructiveCards() {
        fun buildDataModel(includeCutover: Boolean, busyBackup: Boolean = false): SettingsSectionModel =
            SettingsSectionModel.fromItems(
                id = SettingsSectionId.DATA,
                iconRes = R.drawable.ic_settings_backup_history,
                title = "Data & Backup",
                summary = "Never backed up",
                items = buildList {
                    add(SettingItem.Subsection("data_status", "Last backup", presentation = SettingPresentation()))
                    add(
                        SettingItem.Status(
                            key = "backupStatus",
                            iconRes = R.drawable.ic_settings_backup_history,
                            title = "Backup status",
                            summary = "Never backed up",
                            showProgress = busyBackup,
                            presentation = SettingPresentation(showIcon = true),
                            reserveProgressSpace = true
                        )
                    )
                    add(SettingItem.Subsection("data_create", "Create backup", presentation = SettingPresentation()))
                    add(
                        SettingItem.Navigation(
                            key = "exportDB",
                            iconRes = R.drawable.ic_settings_backup_upload,
                            title = "Back up now",
                            summary = "Save full backup",
                            onClick = {}
                        )
                    )
                    add(
                        SettingItem.Navigation(
                            key = "backupToPublicFolder",
                            iconRes = R.drawable.ic_settings_folder_upload,
                            title = "Back up to folder",
                            summary = "External folder",
                            isBusy = busyBackup,
                            onClick = {},
                            presentation = SettingPresentation(showIcon = true, enabled = !busyBackup)
                        )
                    )
                    if (includeCutover) {
                        add(SettingItem.Subsection("data_next_cutover", "UHabit Next", presentation = SettingPresentation()))
                        add(
                            SettingItem.Navigation(
                                key = "pref_production_cutover",
                                iconRes = R.drawable.ic_settings_git_merge,
                                title = "Upgrade to UHabit Next (v30)",
                                summary = "Protected database upgrade",
                                onClick = {}
                            )
                        )
                    }
                    add(
                        SettingItem.Subsection(
                            key = "data_delete",
                            title = "Delete data",
                            isDestructive = true,
                            presentation = SettingPresentation()
                        )
                    )
                    add(
                        SettingItem.Navigation(
                            key = "hardResetStatistics",
                            iconRes = R.drawable.ic_settings_trash,
                            title = "Reset statistics",
                            summary = "Delete entries forever",
                            isDanger = true,
                            onClick = {}
                        )
                    )
                }
            )

        val legacySessionItems = SettingsListComposer.composeDetail(buildDataModel(includeCutover = true))
        assertEquals(9, legacySessionItems.size)
        val statusItem = legacySessionItems[1] as SettingItem.Status
        assertEquals("status:backupStatus", statusItem.stableKey)
        assertTrue(statusItem.reserveProgressSpace)
        assertFalse(statusItem.showProgress)
        assertTrue(statusItem.presentation.isLast)
        assertEquals(legacySessionItems[0].presentation.cardId, statusItem.presentation.cardId)

        val exportItem = legacySessionItems[3] as SettingItem.Navigation
        assertFalse(exportItem.presentation.isLast)
        val publicBackupItem = legacySessionItems[4] as SettingItem.Navigation
        assertTrue(publicBackupItem.presentation.isLast)
        assertEquals(exportItem.presentation.cardId, publicBackupItem.presentation.cardId)

        val deleteHeader = legacySessionItems[7] as SettingItem.Subsection
        assertTrue(deleteHeader.isDestructive)
        val hardResetItem = legacySessionItems[8] as SettingItem.Navigation
        assertTrue(hardResetItem.isDanger)
        assertTrue(hardResetItem.presentation.isLast)

        val nextSessionItems = SettingsListComposer.composeDetail(buildDataModel(includeCutover = false, busyBackup = true))
        assertEquals(7, nextSessionItems.size)
        assertFalse(nextSessionItems.any { it.stableKey == "navigation:pref_production_cutover" })
        assertTrue((nextSessionItems[1] as SettingItem.Status).showProgress)
        val busyPublicItem = nextSessionItems[4] as SettingItem.Navigation
        assertTrue(busyPublicItem.isBusy)
        assertFalse(busyPublicItem.presentation.enabled)
    }

    @Test
    fun restoreFlowGuardPreventsConcurrentValidationAndRestore() {
        val guard = RestoreFlowGuard()
        assertFalse(guard.isBusy)

        assertTrue(guard.tryBeginValidation())
        assertTrue(guard.isValidating)
        assertTrue(guard.isBusy)
        assertFalse(guard.tryBeginValidation())
        assertFalse(guard.tryBeginRestore())

        guard.finishValidation()
        assertFalse(guard.isBusy)

        assertTrue(guard.tryBeginRestore())
        assertTrue(guard.isRestoring)
        assertTrue(guard.isBusy)
        assertFalse(guard.tryBeginValidation())
        assertFalse(guard.tryBeginRestore())

        guard.finishRestore()
        assertFalse(guard.isBusy)
        assertTrue(guard.tryBeginValidation())
    }

    @Test
    fun restoreGuardExcludesOtherFragmentInstancesAndCanBeReleased() {
        val first = RestoreFlowGuard()
        val recreated = RestoreFlowGuard()
        assertTrue(first.tryBeginRestore())
        first.finishValidation() // SettingsFragment.onDestroyView clears only validation.
        assertTrue(recreated.isRestoring)
        assertFalse(recreated.tryBeginRestore())
        assertFalse(recreated.tryBeginValidation())
        first.finishRestore()
        assertTrue(recreated.tryBeginRestore())
        first.finishRestore() // Duplicate completion from the previous operation is harmless.
        assertTrue(recreated.isRestoring)
        recreated.finishRestore()
        assertFalse(first.isBusy)
    }

    @Test
    fun restoreGuardAllowsOnlyOneConcurrentStart() {
        val guards = List(12) { RestoreFlowGuard() }
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(guards.size)
        val winners = java.util.Collections.synchronizedList(mutableListOf<RestoreFlowGuard>())
        guards.forEach { guard ->
            Thread {
                start.await()
                if (guard.tryBeginRestore()) winners.add(guard)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            assertEquals(1, winners.size)
        } finally {
            winners.forEach { it.finishRestore() }
        }
    }

    @Test
    fun restoreTaskReleasesGuardForEveryCompletionAndOnlyOnce() {
        listOf(null, IllegalStateException("failure"), java.util.concurrent.CancellationException()).forEach { cause ->
            val guard = RestoreFlowGuard()
            assertTrue(guard.tryBeginRestore())
            var completions = 0
            val task = object : org.isoron.uhabits.core.tasks.Task {
                override suspend fun doInBackground() = Unit
                override fun onCompletion(cause: Throwable?) { completions++ }
            }
            val guarded = guardRestoreTask(task, guard)
            guarded.onCompletion(cause)
            assertFalse(guard.isBusy)
            assertTrue(guard.tryBeginRestore())
            guarded.onCompletion(cause)
            assertTrue(guard.isRestoring)
            assertEquals(1, completions)
            guard.finishRestore()
        }
    }

    @Test
    fun syncDetailKeepsStatusAndBusyActionAcrossUpdates() {
        fun section(syncing: Boolean, signedIn: Boolean, review: Boolean) =
            SettingsSectionModel.fromItems(
                id = SettingsSectionId.SYNC, iconRes = 1, title = "Sync", summary = "Status",
                items = buildList {
                    add(SettingItem.Subsection("account", "Account", presentation = SettingPresentation()))
                    add(SettingItem.Status("syncStatus", 1, "Status", if (syncing) "Syncing" else "Idle", syncing, SettingPresentation(), reserveProgressSpace = true))
                    add(SettingItem.Navigation(if (signedIn) "syncSignOut" else "syncSignIn", 1, "Account", null, onClick = {}))
                    add(SettingItem.Navigation("syncNow", 1, "Sync now", "Sync", isBusy = syncing, presentation = SettingPresentation(enabled = !syncing), onClick = {}))
                    if (review) add(SettingItem.Navigation("syncReview", 1, "Review", null, onClick = {}))
                }
            )
        val idle = SettingsListComposer.composeDetail(section(false, false, false))
        val busy = SettingsListComposer.composeDetail(section(true, true, true))
        val idleStatus = idle.filterIsInstance<SettingItem.Status>().single()
        val busyStatus = busy.filterIsInstance<SettingItem.Status>().single()
        assertEquals(idleStatus.stableKey, busyStatus.stableKey)
        assertTrue(busyStatus.showProgress)
        assertTrue(busyStatus.reserveProgressSpace)
        assertFalse(busy.filterIsInstance<SettingItem.Navigation>().first { it.key == "syncNow" }.presentation.enabled)
        assertTrue(busy.any { it.stableKey == "navigation:syncSignOut" })
        assertTrue(busy.any { it.stableKey == "navigation:syncReview" })
        assertTrue(idle.any { it.stableKey == "navigation:syncSignIn" })
    }

    private fun section(id: SettingsSectionId, title: String, childKey: String) =
        SettingsSectionModel.fromItems(
            id = id,
            iconRes = 1,
            title = title,
            summary = title,
            items = listOf(
                SettingItem.Subsection(
                    key = "${title}_main",
                    title = "Main",
                    presentation = SettingPresentation()
                ),
                SettingItem.Navigation(
                    key = childKey,
                    iconRes = 2,
                    title = childKey,
                    onClick = {}
                )
            )
        )
}
