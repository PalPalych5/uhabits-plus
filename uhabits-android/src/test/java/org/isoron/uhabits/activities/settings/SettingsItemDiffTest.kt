package org.isoron.uhabits.activities.settings

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsItemDiffTest {
    private fun status(summary: String, progress: Boolean = false) =
        SettingItem.Status("syncStatus", 1, "Last sync", summary, progress, SettingPresentation(), true)

    private fun action(busy: Boolean = false, enabled: Boolean = true, click: () -> Unit = {}) =
        SettingItem.Navigation("syncNow", 1, "Sync now", "Sync", onClick = click, isBusy = busy,
            presentation = SettingPresentation(enabled = enabled))

    @Test
    fun syncStatusTransitionsGenerateTargetedDiffs() {
        val states = listOf(status("Signed out"), status("Idle"), status("Idle", true),
            status("Success"), status("Syncing", true), status("Error"),
            status("Idle"), status("Review required"))
        states.zipWithNext().forEach { (old, new) ->
            assertEquals(old.stableKey, new.stableKey)
            assertFalse(SettingsItemDiff.contentSame(old, new))
            assertEquals(listOf("change:0:1"), dispatch(old, new))
        }
        assertEquals(SettingsItemDiff.ChangePayload.STATUS_PROGRESS,
            SettingsItemDiff.changePayload(status("Idle"), status("Idle", true)))
        assertEquals(SettingsItemDiff.ChangePayload.STATUS_PROGRESS,
            SettingsItemDiff.changePayload(status("Idle", true), status("Idle")))
        assertNull(SettingsItemDiff.changePayload(status("Syncing", true), status("Error")))
    }

    @Test
    fun busyEnabledAndAccountChangesAreNotDropped() {
        val idle = action()
        val busy = action(busy = true, enabled = false)
        assertEquals(SettingsItemDiff.ChangePayload.NAVIGATION_BUSY, SettingsItemDiff.changePayload(idle, busy))
        assertEquals(listOf("change:0:1"), dispatch(idle, busy))
        assertEquals(listOf("change:0:1"), dispatch(busy, idle))
        val account = SettingItem.Status("syncAccount", 1, "Account", "Not signed in", false, SettingPresentation())
        val updated = account.copy(summary = "person@example.test")
        assertEquals(account.stableKey, updated.stableKey)
        assertFalse(SettingsItemDiff.contentSame(account, updated))
        assertEquals(listOf("change:0:1"), dispatch(account, updated))
        assertTrue(SettingsItemDiff.contentSame(idle, action(click = { error("different callback") })))
    }

    @Test
    fun boundActionsUseCurrentCallbackAndIgnoreDisabledOrRemovedItems() {
        val calls = mutableListOf<String>()
        val original = action(click = { calls += "old" })
        var current: List<SettingItem> = listOf(original)
        val bound = composeSettingsItem(original) { current } as SettingItem.Navigation
        current = listOf(action(click = { calls += "new" }))
        bound.onClick!!.invoke()
        current = listOf(action(enabled = false, click = { calls += "disabled" }))
        bound.onClick.invoke()
        current = listOf(action(busy = true, click = { calls += "busy" }))
        bound.onClick.invoke()
        current = emptyList()
        bound.onClick.invoke()
        assertEquals(listOf("new"), calls)

        val switch = SettingItem.Switch("pref_sync_enabled", 1, "Sync", checked = false,
            onCheckedChange = { calls += "old switch" })
        current = listOf(switch)
        val boundSwitch = composeSettingsItem(switch) { current } as SettingItem.Switch
        current = listOf(switch.copy(onCheckedChange = { calls += "new switch $it" }))
        boundSwitch.onCheckedChange(true)
        current = listOf(switch.copy(presentation = SettingPresentation(enabled = false)))
        boundSwitch.onCheckedChange(false)
        assertEquals(listOf("new", "new switch true"), calls)
    }

    private fun dispatch(old: SettingItem, new: SettingItem): List<String> {
        val changes = mutableListOf<String>()
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = 1
            override fun getNewListSize() = 1
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old.stableKey == new.stableKey
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = SettingsItemDiff.contentSame(old, new)
            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int) = SettingsItemDiff.changePayload(old, new)
        }).dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { changes += "insert:$position:$count" }
            override fun onRemoved(position: Int, count: Int) { changes += "remove:$position:$count" }
            override fun onMoved(fromPosition: Int, toPosition: Int) { changes += "move:$fromPosition:$toPosition" }
            override fun onChanged(position: Int, count: Int, payload: Any?) { changes += "change:$position:$count" }
        })
        return changes
    }
}
