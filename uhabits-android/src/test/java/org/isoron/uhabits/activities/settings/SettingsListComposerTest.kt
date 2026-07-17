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
        val items = SettingsListComposer.composeHome(sections, { it.title }, {})

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
            { openedSections++ }
        ).single() as SettingItem.Section
        item.onClick()

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
        val layoutDir = listOf(
            Path.of("src/main/res/layout"),
            Path.of("uhabits-android/src/main/res/layout")
        ).first { Files.isDirectory(it) }
        val navigationRow = String(
            Files.readAllBytes(layoutDir.resolve("item_settings_row.xml")),
            Charsets.UTF_8
        )
        val statusRow = String(
            Files.readAllBytes(layoutDir.resolve("item_settings_status.xml")),
            Charsets.UTF_8
        )

        assertFalse(navigationRow.contains("accessoryProgress"))
        assertTrue(statusRow.contains("statusProgress"))
        assertTrue(statusRow.contains("android:visibility=\"invisible\""))
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
        val layoutDir = listOf(
            Path.of("src/main/res/layout"),
            Path.of("uhabits-android/src/main/res/layout")
        ).first { Files.isDirectory(it) }
        val rowLayouts = listOf(
            "item_settings_row.xml",
            "item_settings_status.xml",
            "item_settings_segmented.xml"
        )

        rowLayouts.forEach { fileName ->
            val xml = String(Files.readAllBytes(layoutDir.resolve(fileName)), Charsets.UTF_8)
            assertTrue(
                "$fileName must use shared row divider start",
                xml.contains("android:layout_marginStart=\"@dimen/settings_detail_row_divider_start\"")
            )
            assertTrue(
                "$fileName must use shared divider end",
                xml.contains("android:layout_marginEnd=\"@dimen/settings_detail_divider_end\"")
            )
        }

        val subsection = String(
            Files.readAllBytes(layoutDir.resolve("item_settings_subsection.xml")),
            Charsets.UTF_8
        )
        assertTrue(
            subsection.contains("android:layout_marginStart=\"@dimen/settings_detail_group_divider_start\"")
        )
        assertTrue(
            subsection.contains("android:layout_marginEnd=\"@dimen/settings_detail_divider_end\"")
        )
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
