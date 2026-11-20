/* SPDX-License-Identifier: GPL-3.0-or-later */

package org.isoron.uhabits.activities.blocks

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.settings.SettingsThemePaletteResolver
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.core.database.HabitBlockData
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.containers.facade.OrganizationAuthorityMode
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ManageBlocksActivity : AppCompatActivity() {

    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private val component
        get() = (applicationContext as HabitsApplication).component
    private val canEdit
        get() = component.organizationFacade.mode == OrganizationAuthorityMode.LEGACY

    private var composeColorsState by mutableStateOf<UHabitComposeColors?>(null)
    private var blocksState by mutableStateOf<List<ManageBlockItemUi>>(emptyList())
    private var editorState by mutableStateOf(BlockEditorState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()
        BaseExceptionHandler.install(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        savedInstanceState?.let { restoreEditorState(it) }

        refreshThemeState()
        refreshBlocks()

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val colors = composeColorsState ?: return@setContent
                ManageBlocksComposeScreen(
                    colors = colors,
                    blocks = blocksState,
                    canEdit = canEdit,
                    editorState = editorState,
                    resolveColor = { paletteColor ->
                        themeSwitcher.currentTheme.color(paletteColor).toInt()
                    },
                    onBack = { finish() },
                    onAddClick = {
                        editorState = BlockEditorState(
                            mode = BlockEditorMode.ADD,
                            editingBlockId = null,
                            draftName = "",
                            selectedColor = PaletteColor(8),
                            showNameError = false,
                            showColorPicker = false,
                        )
                    },
                    onBlockClick = { item ->
                        editorState = BlockEditorState(
                            mode = BlockEditorMode.EDIT,
                            editingBlockId = item.id,
                            draftName = item.name,
                            selectedColor = item.color,
                            showNameError = false,
                            showColorPicker = false,
                        )
                    },
                    onNameChange = { updatedName ->
                        editorState = editorState.copy(
                            draftName = updatedName,
                            showNameError = false,
                        )
                    },
                    onOpenColorPicker = {
                        editorState = editorState.copy(showColorPicker = true)
                    },
                    onDismissColorPicker = {
                        editorState = editorState.copy(showColorPicker = false)
                    },
                    onColorPicked = { picked ->
                        editorState = editorState.copy(
                            selectedColor = picked,
                            showColorPicker = false,
                        )
                    },
                    onSaveDialog = { saveEditorDialog() },
                    onDeleteDialog = { deleteEditorDialog() },
                    onDismissDialog = { editorState = BlockEditorState() },
                )
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        refreshThemeState()
        refreshBlocks()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_EDITOR_MODE, editorState.mode.ordinal)
        outState.putLong(KEY_EDITING_BLOCK_ID, editorState.editingBlockId ?: -1L)
        outState.putString(KEY_DRAFT_NAME, editorState.draftName)
        outState.putInt(KEY_DRAFT_COLOR, editorState.selectedColor.paletteIndex)
        outState.putBoolean(KEY_SHOW_NAME_ERROR, editorState.showNameError)
        outState.putBoolean(KEY_SHOW_COLOR_PICKER, editorState.showColorPicker)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun restoreEditorState(savedInstanceState: Bundle) {
        val modeOrdinal = savedInstanceState.getInt(KEY_EDITOR_MODE, BlockEditorMode.NONE.ordinal)
        val mode = BlockEditorMode.entries.getOrElse(modeOrdinal) { BlockEditorMode.NONE }
        if (mode == BlockEditorMode.NONE) return

        val blockIdRaw = savedInstanceState.getLong(KEY_EDITING_BLOCK_ID, -1L)
        editorState = BlockEditorState(
            mode = mode,
            editingBlockId = blockIdRaw.takeIf { it >= 0L },
            draftName = savedInstanceState.getString(KEY_DRAFT_NAME, "") ?: "",
            selectedColor = PaletteColor(savedInstanceState.getInt(KEY_DRAFT_COLOR, 8)),
            showNameError = savedInstanceState.getBoolean(KEY_SHOW_NAME_ERROR, false),
            showColorPicker = savedInstanceState.getBoolean(KEY_SHOW_COLOR_PICKER, false),
        )
    }

    private fun refreshThemeState() {
        val palette = SettingsThemePaletteResolver.resolve(this, component.preferences)
        composeColorsState = UHabitComposeColors.fromPalette(palette)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    private fun refreshBlocks() {
        val blocks = component.habitList.getBlocks()
        val allHabits = component.habitList.toList()
        blocksState = buildManageBlockItems(
            blocks = blocks,
            allHabits = allHabits,
            resolveColor = { paletteColor ->
                themeSwitcher.currentTheme.color(paletteColor).toInt()
            },
        )
    }

    private fun saveEditorDialog() {
        if (!canEdit) return
        val trimmedName = validateBlockName(editorState.draftName)
        if (trimmedName == null) {
            editorState = editorState.copy(showNameError = true)
            return
        }

        val modelFactory = component.modelFactory as SQLModelFactory
        val repository = modelFactory.habitBlockRepository
        val syncManager = modelFactory.syncManager

        when (editorState.mode) {
            BlockEditorMode.ADD -> {
                val nextPosition = computeNextBlockPosition(component.habitList.getBlocks())
                val data = HabitBlockData(
                    name = trimmedName,
                    color = editorState.selectedColor.paletteIndex,
                    icon = "label",
                    position = nextPosition,
                    isArchived = false,
                    uuid = Uuid.random().toHexString(),
                    updatedAt = syncManager.now(),
                )
                val id = repository.insert(data)
                syncManager.enqueueBlockChange(data.copy(id = id), "create")
                (component.habitList as? SQLiteHabitList)?.reload()
                editorState = BlockEditorState()
                refreshBlocks()
            }

            BlockEditorMode.EDIT -> {
                val targetId = editorState.editingBlockId ?: return
                val block = component.habitList.getBlocks().firstOrNull { it.id == targetId } ?: return
                val updated = HabitBlockData(
                    id = block.id,
                    name = trimmedName,
                    color = editorState.selectedColor.paletteIndex,
                    icon = block.icon,
                    position = block.position,
                    isArchived = block.isArchived,
                    uuid = repository.findById(block.id!!)?.uuid,
                    updatedAt = syncManager.now(),
                )
                repository.update(updated)
                syncManager.enqueueBlockChange(updated, "block_change")
                (component.habitList as? SQLiteHabitList)?.reload()
                editorState = BlockEditorState()
                refreshBlocks()
            }

            BlockEditorMode.NONE -> Unit
        }
    }

    private fun deleteEditorDialog() {
        if (!canEdit) return
        val targetId = editorState.editingBlockId
        if (editorState.mode != BlockEditorMode.EDIT || !canDeleteBlock(targetId)) return
        val block = component.habitList.getBlocks().firstOrNull { it.id == targetId } ?: return

        val modelFactory = component.modelFactory as SQLModelFactory
        val repository = modelFactory.habitBlockRepository
        val syncManager = modelFactory.syncManager
        val deletedAt = syncManager.now()

        syncManager.enqueueBlockChange(
            HabitBlockData(
                id = block.id,
                name = block.name,
                color = block.color.paletteIndex,
                icon = block.icon,
                position = block.position,
                isArchived = block.isArchived,
                uuid = repository.findById(block.id!!)?.uuid,
                updatedAt = deletedAt,
                deletedAt = deletedAt,
            ),
            "delete",
        )
        repository.softDelete(block.id!!, deletedAt)
        (component.habitList as? SQLiteHabitList)?.reload()
        editorState = BlockEditorState()
        refreshBlocks()
    }

    companion object {
        private const val KEY_EDITOR_MODE = "blockEditorMode"
        private const val KEY_EDITING_BLOCK_ID = "blockEditingId"
        private const val KEY_DRAFT_NAME = "blockDraftName"
        private const val KEY_DRAFT_COLOR = "blockDraftColor"
        private const val KEY_SHOW_NAME_ERROR = "blockShowNameError"
        private const val KEY_SHOW_COLOR_PICKER = "blockShowColorPicker"
    }
}
