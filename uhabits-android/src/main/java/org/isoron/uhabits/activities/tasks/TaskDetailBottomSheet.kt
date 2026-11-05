/*
 * Copyright (C) 2016-2026 Álinson Santos Xavier <git@axavier.org> and contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.activities.tasks

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.io.runInTransaction
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskService
import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.time.LocalDate
import java.time.ZoneId

class TaskDetailBottomSheet : BottomSheetDialogFragment() {

    private lateinit var taskService: UserTaskService
    private lateinit var organizationFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette

    private var taskId: TaskId? = null
    private var currentTask: UserTask? = null
    private var isCreateMode: Boolean = false

    private var currentTitle: String = ""
    private var currentNotes: String = ""
    private var isCompleted: Boolean = false
    private var selectedContainerId: ContainerId? = null
    private var selectedDue: TaskDue = TaskDue.None
    private var selectedImportant: Boolean? = null
    private var selectedUrgent: Boolean? = null
    private var selectedTagIds: Set<String> = emptySet()
    private val draftSubtasks = mutableListOf<TaskDraft.DraftSubtask>()

    private var selectedLinkedHabitUuid: String? = null
    private var selectedHabitTimeAccrualEnabled: Boolean = false
    private var selectedHabitLinkEffectiveFrom: Long? = null

    private var currentFocusSummary: String? = null
    private var currentSaveStatus: String = ""

    private var uiState by mutableStateOf<TaskDetailUiState?>(null)

    private val saveHandler = Handler(Looper.getMainLooper())
    private val pendingSave = Runnable { saveChanges() }
    private var viewReady = false
    private var taskCreated = false
    private var taskDeleted = false
    private lateinit var launchComponent: org.isoron.uhabits.inject.HabitsApplicationComponent

    var onTaskUpdatedListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireContext().applicationContext as HabitsApplication
        launchComponent = app.component
        taskService = app.component.userTaskService
        organizationFacade = app.component.organizationFacade
        palette = MainTabsThemeBridge.resolve(requireContext())

        val idStr = arguments?.getString(ARG_TASK_ID)?.takeIf {
            arguments?.getString("focusDatasetId") == app.component.session.datasetId
        }

        if (idStr != null) {
            isCreateMode = false
            taskId = TaskId(idStr)
            currentTask = app.component.userTaskRepository.getById(taskId!!)
            currentTask?.let {
                currentTitle = it.title
                currentNotes = it.description
                selectedImportant = it.important
                selectedUrgent = it.urgent
                selectedTagIds = launchComponent.taskTagRepository.getTagIds(it.id)
                selectedContainerId = it.containerId
                selectedDue = it.due
                isCompleted = it.isCompleted
                selectedLinkedHabitUuid = it.linkedHabitUuid
                selectedHabitTimeAccrualEnabled = it.habitTimeAccrualEnabled
                selectedHabitLinkEffectiveFrom = it.habitLinkEffectiveFrom
            }
        } else {
            isCreateMode = true
            val initialDraft = TaskDraft.fromBundle(arguments?.getBundle(ARG_TASK_DRAFT))
            currentTitle = initialDraft.title
            currentNotes = initialDraft.description
            selectedImportant = initialDraft.important
            selectedUrgent = initialDraft.urgent
            selectedTagIds = initialDraft.tagIds
            selectedContainerId = initialDraft.containerId
            selectedDue = initialDraft.due
            isCompleted = false
            draftSubtasks.clear()
            draftSubtasks.addAll(initialDraft.subtasks)
            selectedLinkedHabitUuid = initialDraft.linkedHabitUuid
            selectedHabitTimeAccrualEnabled = if (initialDraft.linkedHabitUuid != null) initialDraft.habitTimeAccrualEnabled else false
            selectedHabitLinkEffectiveFrom = if (initialDraft.linkedHabitUuid != null) initialDraft.habitLinkEffectiveFrom else null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val composeView = inflater.inflate(R.layout.sheet_task_detail, container, false) as ComposeView

        savedInstanceState?.let { restored ->
            currentTitle = restored.getString("detailTitle", currentTitle)
            currentNotes = restored.getString("detailNotes", currentNotes)
            if (restored.containsKey("detailLinkedHabitUuid")) {
                selectedLinkedHabitUuid = restored.getString("detailLinkedHabitUuid")
            }
            if (selectedLinkedHabitUuid != null) {
                if (restored.containsKey("detailHabitTimeAccrualEnabled")) {
                    selectedHabitTimeAccrualEnabled = restored.getBoolean("detailHabitTimeAccrualEnabled")
                }
                if (restored.containsKey("detailHabitLinkEffectiveFrom")) {
                    selectedHabitLinkEffectiveFrom = restored.getLong("detailHabitLinkEffectiveFrom").takeIf { it > 0L }
                }
            } else {
                selectedHabitTimeAccrualEnabled = false
                selectedHabitLinkEffectiveFrom = null
            }
            if (isCreateMode && restored.containsKey("draftBundle")) {
                val restoredDraft = TaskDraft.fromBundle(restored.getBundle("draftBundle"))
                selectedImportant = restoredDraft.important
                selectedUrgent = restoredDraft.urgent
                selectedTagIds = restoredDraft.tagIds
                selectedContainerId = restoredDraft.containerId
                selectedDue = restoredDraft.due
                draftSubtasks.clear()
                draftSubtasks.addAll(restoredDraft.subtasks)
                selectedLinkedHabitUuid = restoredDraft.linkedHabitUuid
                selectedHabitTimeAccrualEnabled = if (restoredDraft.linkedHabitUuid != null) restoredDraft.habitTimeAccrualEnabled else false
                selectedHabitLinkEffectiveFrom = if (restoredDraft.linkedHabitUuid != null) restoredDraft.habitLinkEffectiveFrom else null
            }
        }

        viewReady = true
        updateUiState()

        childFragmentManager.setFragmentResultListener("detailDue", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            selectedDue = TaskDuePickerBottomSheet.readResult(result)
            updateUiState()
            if (!isCreateMode) {
                saveChanges(forceDue = result.getBoolean(TaskDuePickerBottomSheet.RESULT_EDITED))
            }
        }

        childFragmentManager.setFragmentResultListener("detailContainer", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            selectedContainerId = result.getString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID)?.let(::ContainerId)
            updateUiState()
            if (!isCreateMode) {
                saveChanges()
            }
        }

        childFragmentManager.setFragmentResultListener("detailTags", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            if (result.getBoolean(TaskTagPickerBottomSheet.RESULT_APPLIED)) {
                val next = result.getStringArrayList(TaskTagPickerBottomSheet.RESULT_TAG_IDS).orEmpty().toSet()
                if (isCreateMode) {
                    selectedTagIds = next
                } else {
                    try {
                        launchComponent.taskTagRepository.setTags(taskId ?: return@setFragmentResultListener, next)
                        selectedTagIds = next
                        currentSaveStatus = ""
                    } catch (e: Exception) {
                        currentSaveStatus = "Не удалось сохранить теги"
                    }
                }
                updateUiState()
                onTaskUpdatedListener?.invoke()
            }
        }

        if (!isCreateMode) {
            refreshFocusSummary()
        }

        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val danger = ContextCompat.getColor(
                    context,
                    if (palette.isDark) R.color.color_picker_danger_dark else R.color.color_picker_danger_light
                )
                val warning = if (palette.isDark) Color.parseColor("#FFB74D") else Color.parseColor("#B45309")
                val composeColors = UHabitComposeColors.fromPalette(palette, danger, warning)

                UHabitComposeTheme(composeColors) {
                    uiState?.let { state ->
                        TaskDetailComposeScreen(
                            state = state,
                            colors = composeColors,
                            onTitleChange = { newTitle ->
                                currentTitle = newTitle
                                updateUiState()
                                if (!isCreateMode) {
                                    saveHandler.removeCallbacks(pendingSave)
                                    saveHandler.postDelayed(pendingSave, 400L)
                                }
                            },
                            onNotesChange = { newNotes ->
                                currentNotes = newNotes
                                updateUiState()
                                if (!isCreateMode) {
                                    saveHandler.removeCallbacks(pendingSave)
                                    saveHandler.postDelayed(pendingSave, 400L)
                                }
                            },
                            onToggleComplete = {
                                if (!isCreateMode) {
                                    isCompleted = !isCompleted
                                    saveChanges()
                                }
                            },
                            onDueClick = { showDuePicker() },
                            onClearDue = {
                                selectedDue = TaskDue.None
                                updateUiState()
                                if (!isCreateMode) {
                                    saveChanges(forceDue = true)
                                }
                            },
                            onContainerClick = { showContainerPicker() },
                            onImportanceClick = { showImportanceDialog() },
                            onUrgencyClick = { showUrgencyDialog() },
                            onTagsClick = { showTagsPicker() },
                            onAddSubtask = { title -> addNewSubtask(title) },
                            onToggleSubtask = { id -> toggleSubtask(id) },
                            onEditSubtask = { id -> editSubtask(id) },
                            onDeleteSubtask = { id -> deleteSubtask(id) },
                            onFocusClick = { startFocusSession() },
                            onHabitLinkClick = { showHabitPicker() },
                            onHabitUnlinkClick = { unlinkHabit() },
                            onHabitAccrualToggle = { enabled -> onAccrualToggleChanged(enabled) },
                            onAcceptLegacyPlan = { acceptLegacyPlan() },
                            onDismissLegacyPlan = { dismissLegacyPlan() },
                            onCreateSubmit = { submitCreateTask() },
                            onClose = { dismiss() },
                            onMoreOptionsClick = { showMoreOptionsMenu() },
                            onRetrySave = { saveChanges() }
                        )
                    }
                }
            }
        }

        return composeView
    }

    private var timerActiveForTask = false
    private val focusCreditObserver = {
        activity?.runOnUiThread {
            if (isAdded && viewReady && !isCreateMode) {
                refreshFocusSummary()
            }
        } ?: Unit
    }
    private val timerListener = org.isoron.uhabits.activities.habits.show.timer.TimerSessionManager.Listener {
        activity?.runOnUiThread {
            if (!isAdded || !viewReady || isCreateMode) return@runOnUiThread
            val id = taskId ?: return@runOnUiThread
            val manager = launchComponent.timerSessionManager
            val active = manager.snapshot()
            val isNowActive = active.hasActiveSession && manager.isCurrentTask(id)
            if (timerActiveForTask && !isNowActive) {
                refreshFocusSummary()
            }
            timerActiveForTask = isNowActive
        }
    }

    private fun refreshFocusSummary() {
        if (!::launchComponent.isInitialized || !launchComponent.session.isNextArchitecture || isCreateMode) return
        val id = taskId ?: return
        launchComponent.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var duration = 0L
            override suspend fun doInBackground() {
                val repo = launchComponent.focusSessionRepository
                    ?: org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(launchComponent.db)
                duration = repo.getTotalDurationByTask(id)
            }
            override fun onPostExecute() {
                if (!isAdded || !viewReady || (requireContext().applicationContext as HabitsApplication).component !== launchComponent)
                    return
                val minutes = duration / 60_000L
                currentFocusSummary = if (minutes >= 60) {
                    val h = minutes / 60
                    val m = minutes % 60
                    "Затрачено: $h ч $m мин"
                } else if (minutes > 0) {
                    "Затрачено: $minutes мин"
                } else {
                    "Запустить сессию фокуса"
                }
                updateUiState()
            }
        })
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(requireContext(), value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(requireContext(), value)

    override fun onStart() {
        super.onStart()
        if (!isCreateMode && ::launchComponent.isInitialized && launchComponent.session.isNextArchitecture) {
            val id = taskId
            timerActiveForTask = id != null && launchComponent.timerSessionManager.snapshot().let {
                it.hasActiveSession && launchComponent.timerSessionManager.isCurrentTask(id)
            }
            launchComponent.timerSessionManager.addListener(timerListener)
            launchComponent.focusCreditController?.addObserver(focusCreditObserver)
        }
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.dismissWithAnimation = true
            sheet.behavior.skipCollapsed = true
            sheet.behavior.isFitToContents = true
            sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            sheet.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            sheet.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, window.decorView)?.apply {
                    isAppearanceLightStatusBars = !palette.isDark
                    isAppearanceLightNavigationBars = !palette.isDark
                }
            }
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                fun resize(insets: WindowInsetsCompat?) {
                    val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                    val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
                    val windowHeight = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        requireContext().getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.height()
                    } else resources.displayMetrics.heightPixels
                    val topOffset = (bars?.top ?: 0) + dpInt(16f)
                    val maxHeight = (windowHeight - topOffset).coerceAtLeast(dpInt(200f))
                    if (layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        layoutParams = layoutParams.apply { this.height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    }
                    sheet.behavior.maxHeight = maxHeight
                    sheet.behavior.isFitToContents = true

                    val bottom = maxOf(bars?.bottom ?: 0, ime?.bottom ?: 0)
                    view?.findViewById<View>(R.id.taskDetailRoot)?.setPadding(0, 0, 0, bottom)
                }
                resize(ViewCompat.getRootWindowInsets(this))
                view?.let { content ->
                    ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
                        resize(insets)
                        insets
                    }
                    ViewCompat.requestApplyInsets(content)
                }
                backgroundTintList = null
                val cornerRadiusPx = dp(24f)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val w = maxOf(0, view.width)
                        val h = maxOf(0, view.height)
                        if (w > 0 && h > 0) {
                            outline.setRoundRect(0, 0, w, h + cornerRadiusPx.toInt(), cornerRadiusPx)
                        } else {
                            outline.setEmpty()
                        }
                    }
                }
                clipToOutline = true
                background = ColorDrawable(palette.surface)
                sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isCreateMode && viewReady && ::launchComponent.isInitialized && launchComponent.session.isNextArchitecture) {
            refreshFocusSummary()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::launchComponent.isInitialized) {
            launchComponent.timerSessionManager.removeListener(timerListener)
            launchComponent.focusCreditController?.removeObserver(focusCreditObserver)
        }
    }

    private fun updateUiState() {
        if (!isAdded || context == null) return
        val context = requireContext()
        val locale = TaskDueDisplayFormatter.selectedLocale(context)
        val ru = locale.language == "ru"

        val containerItem = selectedContainerId?.let { cid ->
            organizationFacade.availableContainers().find { it.key == cid.value }
        }

        val formattedDue = TaskDueDisplayFormatter.format(selectedDue, locale)
        val isOverdue = when (val due = selectedDue) {
            is TaskDue.Day -> runCatching {
                val day = LocalDate.parse(due.date.isoDate)
                val today = LocalDate.now(ZoneId.systemDefault())
                day.isBefore(today)
            }.getOrDefault(false) && !isCompleted
            is TaskDue.Timed -> due.epochMillis < System.currentTimeMillis() && !isCompleted
            TaskDue.None -> false
        }

        val allTags = launchComponent.taskTagRepository.getAll().associateBy { it.id }
        val tagItems = selectedTagIds.mapNotNull { tagId ->
            allTags[tagId]?.let { tag ->
                TagItemState(id = tag.id, name = tag.name, color = null)
            }
        }

        val subtaskItems = if (isCreateMode) {
            draftSubtasks.mapIndexed { idx, sub ->
                SubtaskItemState(id = idx.toString(), title = sub.title, isCompleted = sub.isCompleted)
            }
        } else {
            val task = currentTask
            if (task != null) {
                launchComponent.userTaskRepository.getSubtasks(task.id).map {
                    SubtaskItemState(id = it.id.value, title = it.title, isCompleted = it.isCompleted)
                }
            } else emptyList()
        }

        val linkedHabitState = selectedLinkedHabitUuid?.let { uuid ->
            val h = launchComponent.habitList.getByUUID(uuid)
            if (h != null) {
                LinkedHabitState(
                    uuid = h.uuid ?: uuid,
                    name = h.name,
                    unit = h.unit.ifBlank { if (ru) "мин" else "min" },
                    color = TaskThemeUtils.resolvePaletteColor(context, h.color, palette.accent),
                    isArchived = h.isArchived,
                    isIncompatible = !isCompatibleHabit(h),
                    timeAccrualEnabled = selectedHabitTimeAccrualEnabled
                )
            } else null
        }

        uiState = TaskDetailUiState(
            isCreateMode = isCreateMode,
            title = currentTitle,
            notes = currentNotes,
            isCompleted = isCompleted,
            due = selectedDue,
            dueFormatted = formattedDue,
            isOverdue = isOverdue,
            containerName = containerItem?.name,
            containerColor = containerItem?.color?.let { TaskThemeUtils.resolvePaletteColor(context, it, palette.accent) },
            important = selectedImportant,
            urgent = selectedUrgent,
            tags = tagItems,
            subtasks = subtaskItems,
            focusSummary = currentFocusSummary,
            hasFocusFeature = !isCreateMode && launchComponent.session.isNextArchitecture && currentTask != null,
            linkedHabit = linkedHabitState,
            pendingLegacyPlanDate = currentTask?.takeIf { it.hasPendingLegacyPlan }?.legacyPlanDate,
            saveStatusText = currentSaveStatus,
            canCreate = currentTitle.isNotBlank()
        )
    }

    private fun showImportanceDialog() {
        val app = requireContext().applicationContext as? HabitsApplication
        if (app?.component !== launchComponent || taskDeleted) return
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
        val options = if (ru) arrayOf("Не определено", "Важно", "Не важно") else arrayOf("Not set", "Important", "Not important")
        val choiceIndex = when (selectedImportant) { null -> 0; true -> 1; false -> 2 }

        AlertDialog.Builder(requireContext())
            .setTitle(if (ru) "Важность" else "Important")
            .setSingleChoiceItems(options, choiceIndex) { dialog, choice ->
                selectedImportant = when (choice) { 1 -> true; 2 -> false; else -> null }
                updateUiState()
                if (!isCreateMode) {
                    saveChanges()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getCancelButtonText(requireContext()), null)
            .show()
    }

    private fun showUrgencyDialog() {
        val app = requireContext().applicationContext as? HabitsApplication
        if (app?.component !== launchComponent || taskDeleted) return
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
        val options = if (ru) arrayOf("Не определено", "Срочно", "Не срочно") else arrayOf("Not set", "Urgent", "Not urgent")
        val choiceIndex = when (selectedUrgent) { null -> 0; true -> 1; false -> 2 }

        AlertDialog.Builder(requireContext())
            .setTitle(if (ru) "Срочность" else "Urgent")
            .setSingleChoiceItems(options, choiceIndex) { dialog, choice ->
                selectedUrgent = when (choice) { 1 -> true; 2 -> false; else -> null }
                updateUiState()
                if (!isCreateMode) {
                    saveChanges()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getCancelButtonText(requireContext()), null)
            .show()
    }

    private fun showTagsPicker() {
        TaskTagPickerBottomSheet.newInstance(ids = selectedTagIds, resultKey = "detailTags", datasetId = launchComponent.session.datasetId)
            .show(childFragmentManager, "detailTags")
    }

    private fun addNewSubtask(title: String) {
        if (title.isBlank()) return
        if (isCreateMode) {
            draftSubtasks.add(TaskDraft.DraftSubtask(title = title, isCompleted = false))
            updateUiState()
        } else {
            val task = currentTask ?: return
            taskService.createTask(
                title = title,
                parentTaskId = task.id,
                containerId = selectedContainerId
            )
            updateUiState()
            onTaskUpdatedListener?.invoke()
        }
    }

    private fun toggleSubtask(id: String) {
        if (isCreateMode) {
            val idx = id.toIntOrNull()
            if (idx != null && idx in draftSubtasks.indices) {
                draftSubtasks[idx] = draftSubtasks[idx].copy(isCompleted = !draftSubtasks[idx].isCompleted)
                updateUiState()
            }
        } else {
            taskService.toggleComplete(TaskId(id))
            updateUiState()
            onTaskUpdatedListener?.invoke()
        }
    }

    private fun deleteSubtask(id: String) {
        if (isCreateMode) {
            val idx = id.toIntOrNull()
            if (idx != null && idx in draftSubtasks.indices) {
                draftSubtasks.removeAt(idx)
                updateUiState()
            }
        } else {
            taskService.deleteTask(TaskId(id))
            updateUiState()
            onTaskUpdatedListener?.invoke()
        }
    }

    private fun editSubtask(id: String) {
        val app = requireContext().applicationContext as? HabitsApplication
        if (app?.component !== launchComponent || taskDeleted) return
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"

        val initialTitle = if (isCreateMode) {
            val idx = id.toIntOrNull()
            if (idx != null && idx in draftSubtasks.indices) draftSubtasks[idx].title else ""
        } else {
            launchComponent.userTaskRepository.getById(TaskId(id))?.title ?: ""
        }

        val input = EditText(requireContext()).apply {
            setText(initialTitle)
            setSelection(text.length)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPaddingRelative(dpInt(16f), dpInt(12f), dpInt(16f), dpInt(12f))
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(dpInt(8f), dpInt(4f), dpInt(8f), dpInt(4f))
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (ru) "Редактировать подзадачу" else "Edit subtask")
            .setView(container)
            .setPositiveButton(if (ru) "Сохранить" else "Save") { _, _ ->
                if (HabitsApplication.component !== launchComponent || taskDeleted) return@setPositiveButton
                val newTitle = input.text?.toString()?.trim().orEmpty()
                if (newTitle.isNotBlank() && newTitle != initialTitle) {
                    if (isCreateMode) {
                        val idx = id.toIntOrNull()
                        if (idx != null && idx in draftSubtasks.indices) {
                            draftSubtasks[idx] = draftSubtasks[idx].copy(title = newTitle)
                            updateUiState()
                        }
                    } else {
                        try {
                            taskService.updateTask(TaskId(id), title = newTitle)
                            updateUiState()
                            onTaskUpdatedListener?.invoke()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), if (ru) "Не удалось обновить подзадачу" else "Could not update subtask", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getCancelButtonText(requireContext()), null)
            .show()
    }

    private fun startFocusSession() {
        val app = requireContext().applicationContext as HabitsApplication
        if (app.component !== launchComponent || taskDeleted || isCreateMode) return
        saveHandler.removeCallbacks(pendingSave)
        saveChanges()
        val id = taskId ?: return
        val manager = launchComponent.timerSessionManager
        val active = manager.snapshot()
        if (active.hasActiveSession && !manager.isCurrentTask(id)) {
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.task_focus_conflict, active.habitName))
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (parentFragmentManager.findFragmentByTag(TaskFocusBottomSheet.TAG) == null) {
            TaskFocusBottomSheet.newInstance(id, currentTitle).apply {
                onClosed = { if (viewReady) refreshFocusSummary() }
            }.show(parentFragmentManager, TaskFocusBottomSheet.TAG)
        }
    }

    private fun onAccrualToggleChanged(isChecked: Boolean) {
        val app = requireContext().applicationContext as? HabitsApplication
        if (app?.component !== launchComponent || taskDeleted) return
        if (selectedHabitTimeAccrualEnabled != isChecked) {
            selectedHabitTimeAccrualEnabled = isChecked
            if (isChecked) {
                selectedHabitLinkEffectiveFrom = System.currentTimeMillis()
            }
            updateUiState()
            if (!isCreateMode) {
                saveChanges()
            }
        }
    }

    private fun showHabitPicker() {
        val app = requireContext().applicationContext as? HabitsApplication
        if (app?.component !== launchComponent || taskDeleted) return

        val eligibleHabits = launchComponent.habitList.filter { habit ->
            !habit.isArchived && isCompatibleHabit(habit)
        }

        val selectedLocale = TaskDueDisplayFormatter.selectedLocale(requireContext())
        val ru = selectedLocale.language == "ru"
        val cancelText = getCancelButtonText(requireContext())

        if (eligibleHabits.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(if (ru) "Привязка к привычке" else "Link to habit")
                .setMessage(if (ru) "Нет доступных числовых привычек с минутами. Создайте числовую привычку с единицей измерения «мин» или «минуты»."
                    else "No numerical minute habits available. Create a numerical habit with unit 'min' or 'minutes'.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val habitNames = eligibleHabits.map { "${it.name} (${it.unit})" }.toTypedArray()
        val currentIndex = eligibleHabits.indexOfFirst { it.uuid == selectedLinkedHabitUuid }

        AlertDialog.Builder(requireContext())
            .setTitle(if (ru) "Выберите привычку" else "Select habit")
            .setSingleChoiceItems(habitNames, currentIndex) { dialog, which ->
                val chosen = eligibleHabits[which]
                val habitChanged = chosen.uuid != selectedLinkedHabitUuid
                selectedLinkedHabitUuid = chosen.uuid
                selectedHabitTimeAccrualEnabled = true
                if (habitChanged || selectedHabitLinkEffectiveFrom == null) {
                    selectedHabitLinkEffectiveFrom = System.currentTimeMillis()
                }
                updateUiState()
                if (!isCreateMode) {
                    saveChanges()
                }
                dialog.dismiss()
            }
            .setNegativeButton(cancelText, null)
            .apply {
                if (selectedLinkedHabitUuid != null) {
                    setNeutralButton(if (ru) "Отвязать" else "Unlink") { _, _ ->
                        unlinkHabit()
                    }
                }
            }
            .show()
    }

    private fun unlinkHabit() {
        selectedLinkedHabitUuid = null
        selectedHabitTimeAccrualEnabled = false
        selectedHabitLinkEffectiveFrom = null
        updateUiState()
        if (!isCreateMode) {
            saveChanges()
        }
    }

    private fun acceptLegacyPlan() {
        val t = currentTask ?: return
        val updated = try {
            taskService.resolveLegacyPlan(t.id, accept = true)
        } catch (_: org.isoron.uhabits.core.userTasks.InvalidLegacyPlanException) {
            AlertDialog.Builder(requireContext())
                .setMessage("Не удалось распознать старую дату. Исходное значение сохранено. Выберите корректный срок вручную или нажмите «Отклонить».")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        currentTask = updated
        selectedDue = updated.due
        updateUiState()
        onTaskUpdatedListener?.invoke()
    }

    private fun dismissLegacyPlan() {
        val t = currentTask ?: return
        val updated = taskService.resolveLegacyPlan(t.id, accept = false)
        currentTask = updated
        selectedDue = updated.due
        updateUiState()
        onTaskUpdatedListener?.invoke()
    }

    private fun showContainerPicker() {
        if (childFragmentManager.findFragmentByTag("detailContainer") == null) {
            TaskContainerPickerBottomSheet.newInstance(selectedContainerId, "detailContainer", launchComponent.session.datasetId)
                .show(childFragmentManager, "detailContainer")
        }
    }

    private fun showDuePicker() {
        if (childFragmentManager.findFragmentByTag("detailDue") == null) {
            TaskDuePickerBottomSheet.newInstance("detailDue", selectedDue)
                .show(childFragmentManager, "detailDue")
        }
    }

    private fun showMoreOptionsMenu() {
        val context = context ?: return
        val ru = TaskDueDisplayFormatter.selectedLocale(context).language == "ru"
        val items = mutableListOf<String>()
        items.add(if (ru) "Удалить задачу" else "Delete task")
        if (selectedLinkedHabitUuid != null) {
            items.add(if (ru) "Отвязать привычку" else "Unlink habit")
        }

        AlertDialog.Builder(context)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> confirmDelete()
                    1 -> unlinkHabit()
                }
            }
            .show()
    }

    private fun submitCreateTask() {
        if (taskCreated) return
        val title = currentTitle.trim()
        if (title.isBlank()) return

        val description = currentNotes.trim()
        val due = selectedDue
        val containerId = selectedContainerId
        val subtasksSnapshot = draftSubtasks.toList()

        try {
            launchComponent.db.runInTransaction {
                val hasLink = selectedLinkedHabitUuid != null
                val mainTask = taskService.createTask(
                    title = title,
                    description = description,
                    containerId = containerId,
                    due = due,
                    important = selectedImportant,
                    urgent = selectedUrgent,
                    linkedHabitUuid = selectedLinkedHabitUuid,
                    habitTimeAccrualEnabled = if (hasLink) selectedHabitTimeAccrualEnabled else false,
                    habitLinkEffectiveFrom = if (hasLink) selectedHabitLinkEffectiveFrom else null,
                )
                launchComponent.taskTagRepository.setTags(mainTask.id, selectedTagIds)
                for (sub in subtasksSnapshot) {
                    if (sub.title.isNotBlank()) {
                        val createdSub = taskService.createTask(
                            title = sub.title,
                            parentTaskId = mainTask.id,
                            containerId = containerId,
                        )
                        if (sub.isCompleted) {
                            taskService.setCompleted(createdSub.id, true)
                        }
                    }
                }
                mainTask
            }
        } catch (e: Exception) {
            android.util.Log.e("TaskDetailBottomSheet", "Task creation failed", e)
            Toast.makeText(requireContext(), "Не удалось создать задачу. Попробуйте ещё раз.", Toast.LENGTH_LONG).show()
            return
        }

        taskCreated = true
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY_CREATE,
            Bundle().apply {
                putString(RESULT_ACTION, ACTION_CREATED)
            }
        )
        onTaskUpdatedListener?.invoke()
        dismiss()
    }

    private fun confirmDelete() {
        taskId?.let { id ->
            AlertDialog.Builder(requireContext())
                .setTitle("Удалить задачу?")
                .setMessage("Задача и все её подзадачи будут удалены.")
                .setPositiveButton("Удалить") { _, _ ->
                    saveHandler.removeCallbacks(pendingSave)
                    taskService.deleteTask(id, cascadeSubtasks = true)
                    taskDeleted = true
                    currentTask = null
                    onTaskUpdatedListener?.invoke()
                    dismiss()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun saveChanges(forceDue: Boolean = false) {
        saveHandler.removeCallbacks(pendingSave)
        if (!viewReady || taskDeleted || isCreateMode || HabitsApplication.component !== launchComponent) return
        val task = currentTask ?: return
        val rawTitle = currentTitle.trim()
        val titleChanged = rawTitle.isNotBlank() && rawTitle != task.title
        val newTitle = if (rawTitle.isNotBlank()) rawTitle else task.title
        val newDesc = currentNotes.trim()

        val importantChanged = selectedImportant != task.important
        val urgentChanged = selectedUrgent != task.urgent
        val linkChanged = selectedLinkedHabitUuid != task.linkedHabitUuid ||
            selectedHabitTimeAccrualEnabled != task.habitTimeAccrualEnabled ||
            selectedHabitLinkEffectiveFrom != task.habitLinkEffectiveFrom
        val propertiesChanged = titleChanged || newDesc != task.description ||
            selectedContainerId != task.containerId || selectedDue != task.due || forceDue ||
            importantChanged || urgentChanged || linkChanged

        if (!propertiesChanged && isCompleted == task.isCompleted) {
            currentSaveStatus = ""
            updateUiState()
            return
        }

        var savedTask = task
        if (propertiesChanged) {
            val habitUuidChanged = selectedLinkedHabitUuid != task.linkedHabitUuid
            val accrualChanged = selectedHabitTimeAccrualEnabled != task.habitTimeAccrualEnabled
            val effectiveFromChanged = selectedHabitLinkEffectiveFrom != task.habitLinkEffectiveFrom
            val unlinking = habitUuidChanged && selectedLinkedHabitUuid == null

            try {
                savedTask = taskService.updateTask(
                    id = task.id,
                    title = if (titleChanged) newTitle else null,
                    description = newDesc,
                    containerId = selectedContainerId,
                    clearContainer = selectedContainerId == null,
                    important = if (importantChanged && selectedImportant != null) selectedImportant else null,
                    urgent = if (urgentChanged && selectedUrgent != null) selectedUrgent else null,
                    clearImportant = importantChanged && selectedImportant == null,
                    clearUrgent = urgentChanged && selectedUrgent == null,
                    due = selectedDue.takeIf { forceDue || it != task.due },
                    linkedHabitUuid = if (linkChanged && !unlinking) selectedLinkedHabitUuid.takeIf { habitUuidChanged } else null,
                    clearLinkedHabit = unlinking,
                    habitTimeAccrualEnabled = if (unlinking) false else selectedHabitTimeAccrualEnabled.takeIf { accrualChanged || habitUuidChanged },
                    habitLinkEffectiveFrom = if (unlinking) null else selectedHabitLinkEffectiveFrom.takeIf { effectiveFromChanged || habitUuidChanged },
                    clearHabitLinkEffectiveFrom = unlinking || (effectiveFromChanged && selectedHabitLinkEffectiveFrom == null),
                )
            } catch (e: Exception) {
                android.util.Log.e("TaskDetailBottomSheet", "Failed to update task", e)
                currentSaveStatus = "Не удалось сохранить"
                updateUiState()
                return
            }
        }

        if (isCompleted != task.isCompleted) {
            try {
                savedTask = taskService.setCompleted(task.id, isCompleted)
            } catch (e: Exception) {
                android.util.Log.e("TaskDetailBottomSheet", "Failed to update task completion", e)
                currentSaveStatus = "Не удалось сохранить"
                updateUiState()
                return
            }
        }

        currentTask = savedTask
        selectedLinkedHabitUuid = savedTask.linkedHabitUuid
        selectedHabitTimeAccrualEnabled = savedTask.habitTimeAccrualEnabled
        selectedHabitLinkEffectiveFrom = savedTask.habitLinkEffectiveFrom
        selectedImportant = savedTask.important
        selectedUrgent = savedTask.urgent
        currentSaveStatus = ""
        updateUiState()
        onTaskUpdatedListener?.invoke()
    }

    override fun onPause() {
        if (!isCreateMode && viewReady && !taskDeleted && HabitsApplication.component === launchComponent) {
            saveChanges()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("detailTitle", currentTitle)
        outState.putString("detailNotes", currentNotes)
        selectedLinkedHabitUuid?.let { outState.putString("detailLinkedHabitUuid", it) }
        outState.putBoolean("detailHabitTimeAccrualEnabled", selectedHabitTimeAccrualEnabled)
        selectedHabitLinkEffectiveFrom?.let { outState.putLong("detailHabitLinkEffectiveFrom", it) }
        if (isCreateMode) {
            outState.putBundle("draftBundle", buildCurrentDraft().toBundle())
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildCurrentDraft(): TaskDraft {
        val hasLink = selectedLinkedHabitUuid != null
        return TaskDraft(
            title = currentTitle,
            description = currentNotes,
            tagIds = selectedTagIds,
            important = selectedImportant,
            urgent = selectedUrgent,
            due = selectedDue,
            containerId = selectedContainerId,
            subtasks = draftSubtasks.toList(),
            linkedHabitUuid = selectedLinkedHabitUuid,
            habitTimeAccrualEnabled = if (hasLink) selectedHabitTimeAccrualEnabled else false,
            habitLinkEffectiveFrom = if (hasLink) selectedHabitLinkEffectiveFrom else null,
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        saveHandler.removeCallbacks(pendingSave)
        if (!isCreateMode && viewReady && !taskDeleted && HabitsApplication.component === launchComponent) {
            saveChanges()
        } else if (isCreateMode && !taskCreated) {
            val currentDraft = buildCurrentDraft()
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY_CREATE,
                Bundle().apply {
                    putString(RESULT_ACTION, ACTION_COLLAPSED)
                    putBundle(RESULT_DRAFT, currentDraft.toBundle())
                }
            )
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        viewReady = false
        saveHandler.removeCallbacks(pendingSave)
        super.onDestroyView()
    }

    companion object {
        const val REQUEST_KEY_CREATE = "task_detail_create_result"
        const val RESULT_ACTION = "action"
        const val ACTION_CREATED = "action_created"
        const val ACTION_COLLAPSED = "action_collapsed"
        const val RESULT_DRAFT = "draft"

        const val ARG_TASK_ID = "task_id"
        const val ARG_TASK_DRAFT = "task_draft"

        val MINUTE_UNITS = setOf("min", "mins", "minute", "minutes", "min.", "мин", "минута", "минуты", "минут", "мин.")

        fun isCompatibleHabit(habit: org.isoron.uhabits.core.models.Habit): Boolean {
            return habit.isNumerical && habit.unit.trim().lowercase() in MINUTE_UNITS
        }

        fun getCancelButtonText(context: android.content.Context): String {
            val selectedLocale = TaskDueDisplayFormatter.selectedLocale(context)
            val ru = selectedLocale.language == "ru"
            return if (ru) "Отмена" else "Cancel"
        }

        fun newInstance(taskId: TaskId): TaskDetailBottomSheet {
            return TaskDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TASK_ID, taskId.value)
                    putString("focusDatasetId", HabitsApplication.component.session.datasetId)
                }
            }
        }

        fun newInstanceForCreate(draft: TaskDraft, datasetId: String): TaskDetailBottomSheet {
            return TaskDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putBundle(ARG_TASK_DRAFT, draft.toBundle())
                    putString("focusDatasetId", datasetId)
                }
            }
        }
    }
}
