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
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskService
import org.isoron.uhabits.core.userTasks.due.TaskDue
import java.text.DateFormat
import java.util.Date

class TaskDetailBottomSheet : BottomSheetDialogFragment() {

    private lateinit var taskService: UserTaskService
    private lateinit var organizationFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette

    private var taskId: TaskId? = null
    private var currentTask: UserTask? = null
    private var selectedContainerId: ContainerId? = null
    private var selectedDue: TaskDue = TaskDue.None
    private var isCompleted: Boolean = false

    private lateinit var etTitle: EditText
    private lateinit var etDescription: EditText
    private lateinit var btnCheck: FrameLayout
    private lateinit var viewCheckCircle: View
    private lateinit var ivCheckmark: ImageView

    private lateinit var rowContainer: LinearLayout
    private lateinit var viewContainerDot: View
    private lateinit var tvContainerValue: TextView

    private lateinit var rowDue: LinearLayout
    private lateinit var tvDueLabel: TextView
    private lateinit var tvDueValue: TextView
    private lateinit var btnClearDue: ImageButton
    private lateinit var cardLegacyPlanDecision: View
    private lateinit var tvLegacyPlanPrompt: TextView
    private lateinit var btnAcceptLegacyPlan: Button
    private lateinit var btnDismissLegacyPlan: Button

    private lateinit var rvSubtasks: RecyclerView
    private lateinit var subtasksAdapter: SubtasksAdapter
    private lateinit var tvSubtasksCount: TextView
    private lateinit var etNewSubtask: EditText
    private lateinit var btnAddSubtask: ImageButton

    private lateinit var btnMore: ImageButton
    private val saveHandler = Handler(Looper.getMainLooper())
    private val pendingSave = Runnable { saveChanges() }
    private var viewReady = false
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
            taskId = TaskId(idStr)
            currentTask = app.component.userTaskRepository.getById(taskId!!)
            currentTask?.let {
                selectedContainerId = it.containerId
                selectedDue = it.due
                isCompleted = it.isCompleted
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.sheet_task_detail, container, false)
        bindViews(view)
        applyTheme(view)
        populateData()
        savedInstanceState?.let { restored ->
            etTitle.setText(restored.getString("detailTitle", etTitle.text.toString()))
            etDescription.setText(restored.getString("detailNotes", etDescription.text.toString()))
            etNewSubtask.setText(restored.getString("detailSubtask", ""))
        }
        setupListeners()
        viewReady = true
        childFragmentManager.setFragmentResultListener("detailDue", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            selectedDue = TaskDuePickerBottomSheet.readResult(result)
            updateDueDisplay()
            saveChanges(forceDue = result.getBoolean(TaskDuePickerBottomSheet.RESULT_EDITED))
        }
        childFragmentManager.setFragmentResultListener("detailContainer", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            selectedContainerId = result.getString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID)?.let(::ContainerId)
            updateContainerDisplay()
            saveChanges()
        }

        view.findViewById<View>(R.id.rowDetailFocus).apply {
            visibility = if (launchComponent.session.isNextArchitecture && currentTask != null) View.VISIBLE else View.GONE
            setOnClickListener {
                val app = requireContext().applicationContext as HabitsApplication
                if (app.component !== launchComponent || taskDeleted) return@setOnClickListener
                saveHandler.removeCallbacks(pendingSave)
                saveChanges()
                val id = taskId ?: return@setOnClickListener
                val manager = launchComponent.timerSessionManager
                val active = manager.snapshot()
                if (active.hasActiveSession && !manager.isCurrentTask(id)) {
                    AlertDialog.Builder(requireContext())
                        .setMessage(getString(R.string.task_focus_conflict, active.habitName))
                        .setPositiveButton(android.R.string.ok, null).show()
                    return@setOnClickListener
                }
                if (parentFragmentManager.findFragmentByTag(TaskFocusBottomSheet.TAG) == null) {
                    TaskFocusBottomSheet.newInstance(id, etTitle.text.toString()).apply {
                        onClosed = { if (viewReady) refreshFocusSummary() }
                    }.show(parentFragmentManager, TaskFocusBottomSheet.TAG)
                }
            }
        }
        refreshFocusSummary(view)
        return view
    }

    private fun refreshFocusSummary(root: View? = view) {
        if (!launchComponent.session.isNextArchitecture) return
        val id = taskId ?: return
        launchComponent.taskRunner.execute(object : org.isoron.uhabits.core.tasks.Task {
            private var duration = 0L
            override suspend fun doInBackground() {
                duration = org.isoron.uhabits.core.focus.sqlite.SQLiteFocusSessionRepository(launchComponent.db)
                    .getByTask(id).sumOf { it.durationMs }
            }
            override fun onPostExecute() {
                if (!isAdded || root !== view || (requireContext().applicationContext as HabitsApplication).component !== launchComponent)
                    return
                val seconds = duration / 1000
                val formatted = when {
                    seconds >= 3600 -> "${seconds / 3600} ч ${seconds / 60 % 60} мин"
                    seconds >= 60 -> "${seconds / 60} мин ${seconds % 60} с"
                    else -> "$seconds с"
                }
                root?.findViewById<TextView>(R.id.tvDetailFocus)?.apply {
                    maxLines = 2
                    text = "Начать фокус · всего $formatted"
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.dismissWithAnimation = true
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                backgroundTintList = null
                background = GradientDrawable().apply {
                    setColor(palette.surface)
                    cornerRadii = floatArrayOf(dp(24f), dp(24f), dp(24f), dp(24f), 0f, 0f, 0f, 0f)
                }
                clipToOutline = true
            }
        }
    }

    private fun bindViews(view: View) {
        etTitle = view.findViewById(R.id.etDetailTitle)
        etDescription = view.findViewById(R.id.etDetailDescription)
        btnCheck = view.findViewById(R.id.btnDetailCheck)
        viewCheckCircle = view.findViewById(R.id.viewDetailCheckCircle)
        ivCheckmark = view.findViewById(R.id.ivDetailCheckmark)

        rowContainer = view.findViewById(R.id.rowDetailContainer)
        viewContainerDot = view.findViewById(R.id.viewDetailContainerDot)
        tvContainerValue = view.findViewById(R.id.tvDetailContainerValue)

        rowDue = view.findViewById(R.id.rowDetailDue)
        tvDueLabel = view.findViewById(R.id.tvDetailDueLabel)
        tvDueValue = view.findViewById(R.id.tvDetailDueValue)
        btnClearDue = view.findViewById(R.id.btnClearDue)
        cardLegacyPlanDecision = view.findViewById(R.id.cardLegacyPlanDecision)
        tvLegacyPlanPrompt = view.findViewById(R.id.tvLegacyPlanPrompt)
        btnAcceptLegacyPlan = view.findViewById(R.id.btnAcceptLegacyPlan)
        btnDismissLegacyPlan = view.findViewById(R.id.btnDismissLegacyPlan)

        rvSubtasks = view.findViewById(R.id.rvDetailSubtasks)
        tvSubtasksCount = view.findViewById(R.id.tvDetailSubtasksCount)
        etNewSubtask = view.findViewById(R.id.etNewSubtaskTitle)
        btnAddSubtask = view.findViewById(R.id.ivAddSubtask)

        btnMore = view.findViewById(R.id.btnDetailMore)
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(requireContext(), value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(requireContext(), value)

    private fun applyTheme(view: View) {
        val root = view.findViewById<LinearLayout>(R.id.taskDetailRoot)
        root.setBackgroundColor(Color.TRANSPARENT)
        view.background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadii = floatArrayOf(dp(24f), dp(24f), dp(24f), dp(24f), 0f, 0f, 0f, 0f)
        }
        view.clipToOutline = true

        val dragHandle = view.findViewById<View>(R.id.viewDragHandle)
        val handleBg = GradientDrawable().apply {
            cornerRadius = dp(2f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.4f))
        }
        dragHandle.background = handleBg

        val dividers = listOf(
            view.findViewById<View>(R.id.divider1),
            view.findViewById<View>(R.id.divider2)
        )
        for (d in dividers) {
            d.setBackgroundColor(palette.divider)
        }

        etTitle.setTextColor(palette.onSurface)
        view.findViewById<TextView>(R.id.tvDetailFocus).setTextColor(palette.accent)
        view.findViewById<ImageView>(R.id.ivDetailFocusIcon).setColorFilter(palette.accent)
        etTitle.setHintTextColor(palette.onSurfaceVariant)
        etDescription.setTextColor(palette.onSurfaceVariant)
        etDescription.setHintTextColor(palette.onSurfaceVariant)
        etNewSubtask.setTextColor(palette.onSurface)
        etNewSubtask.setHintTextColor(palette.onSurfaceVariant)

        tvContainerValue.setTextColor(palette.onSurface)
        tvDueValue.setTextColor(palette.onSurface)
        val planBg = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
        }
        cardLegacyPlanDecision.background = planBg
        tvLegacyPlanPrompt.setTextColor(palette.onSurface)
        btnDismissLegacyPlan.setTextColor(palette.onSurfaceVariant)
        btnAcceptLegacyPlan.setTextColor(palette.accent)
        tvSubtasksCount.setTextColor(palette.onSurfaceVariant)
        listOf(R.id.tvDetailContainerLabel, R.id.tvDetailDueLabel).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(palette.onSurfaceVariant)
        }

        btnClearDue.setColorFilter(palette.onSurfaceVariant)

        listOf(R.id.ivDetailContainerIcon, R.id.ivDetailDueIcon,
            R.id.ivDetailContainerChevron, R.id.ivAddSubtask).forEach { id ->
            view.findViewById<ImageView>(id)?.setColorFilter(palette.onSurfaceVariant)
        }
        btnMore.setColorFilter(palette.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvDetailSubtasksLabel).setTextColor(palette.onSurface)
    }

    private fun populateData() {
        val task = currentTask ?: return

        etTitle.setText(task.title)
        etDescription.setText(task.description)
        updateCheckState()
        updateContainerDisplay()
        updateDueDisplay()
        setupSubtasks()
    }

    private fun updateCheckState() {
        val circleDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }

        if (isCompleted) {
            circleDrawable.setColor(palette.accent)
            viewCheckCircle.background = circleDrawable
            ivCheckmark.visibility = View.VISIBLE
            ivCheckmark.setColorFilter(Color.WHITE)
        } else {
            circleDrawable.setColor(Color.TRANSPARENT)
            circleDrawable.setStroke(dpInt(1.5f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.72f))
            viewCheckCircle.background = circleDrawable
            ivCheckmark.visibility = View.GONE
        }
        btnCheck.contentDescription = if (isCompleted) "Возобновить задачу" else "Завершить задачу"
        etTitle.paintFlags = if (isCompleted) etTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else etTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }

    private fun updateContainerDisplay() {
        val cid = selectedContainerId
        viewContainerDot.visibility = View.GONE
        if (cid == null) {
            tvContainerValue.text = "Без раздела"
        } else {
            val containers = organizationFacade.availableContainers()
            val item = containers.find { it.key == cid.value }
            if (item != null) {
                tvContainerValue.text = item.name
            } else {
                viewContainerDot.visibility = View.GONE
                tvContainerValue.text = "Недоступный раздел"
            }
        }
        rowContainer.contentDescription = "Раздел: ${tvContainerValue.text}. Изменить раздел"
    }

    private fun updateDueDisplay() {
        rowDue.contentDescription = null
        val hasLegacy = currentTask?.hasPendingLegacyPlan == true && selectedDue is TaskDue.None
        if (hasLegacy) {
            cardLegacyPlanDecision.visibility = View.VISIBLE
            tvLegacyPlanPrompt.text = "Ранее запланировано: ${currentTask?.legacyPlanDate}"
        } else {
            cardLegacyPlanDecision.visibility = View.GONE
        }
        when (val due = selectedDue) {
            is TaskDue.None -> {
                tvDueLabel.visibility = View.GONE
                tvDueValue.text = "Задать срок"
                tvDueValue.setTextColor(palette.onSurfaceVariant)
                btnClearDue.visibility = View.GONE
                rowDue.contentDescription = "Срок не задан. Нажмите, чтобы задать срок"
            }
            is TaskDue.Day -> {
                tvDueLabel.visibility = View.VISIBLE
                val ld = due.date.toLocalDate()
                val today = getToday()
                val text = when {
                    ld == today -> "Сегодня"
                    ld.daysSince2000 == today.daysSince2000 + 1 -> "Завтра"
                    ld.daysSince2000 == today.daysSince2000 - 1 -> "Вчера"
                    else -> "${ld.day}.${ld.month.toString().padStart(2, '0')}.${ld.year}"
                }
                tvDueValue.text = text
                tvDueValue.setTextColor(palette.onSurface)
                btnClearDue.visibility = View.VISIBLE
                rowDue.contentDescription = "Срок: $text. Изменить срок"
            }
            is TaskDue.Timed -> {
                tvDueLabel.visibility = View.VISIBLE
                val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                val text = df.format(Date(due.epochMillis))
                tvDueValue.text = text
                tvDueValue.setTextColor(palette.onSurface)
                btnClearDue.visibility = View.VISIBLE
                rowDue.contentDescription = "Срок: $text. Изменить срок"
            }
        }
    }

    private fun setupSubtasks() {
        subtasksAdapter = SubtasksAdapter(
            palette = palette,
            onToggleComplete = { subtask ->
                taskService.toggleComplete(subtask.id)
                refreshSubtasks()
                onTaskUpdatedListener?.invoke()
            },
            onDeleteSubtask = { subtask ->
                taskService.deleteTask(subtask.id)
                refreshSubtasks()
                onTaskUpdatedListener?.invoke()
            }
        )

        rvSubtasks.layoutManager = LinearLayoutManager(requireContext())
        rvSubtasks.adapter = subtasksAdapter
        refreshSubtasks()

        etNewSubtask.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNewSubtask()
                true
            } else false
        }
        btnAddSubtask.setOnClickListener { addNewSubtask() }
    }

    private fun refreshSubtasks() {
        val task = currentTask ?: return
        val app = requireContext().applicationContext as HabitsApplication
        val subtasks = app.component.userTaskRepository.getSubtasks(task.id)
        subtasksAdapter.updateData(subtasks)

        val completedCount = subtasks.count { it.isCompleted }
        tvSubtasksCount.text = "$completedCount из ${subtasks.size}"
    }

    private fun addNewSubtask() {
        val title = etNewSubtask.text?.toString()?.trim() ?: ""
        if (title.isBlank()) return
        val task = currentTask ?: return

        taskService.createTask(
            title = title,
            parentTaskId = task.id,
            containerId = selectedContainerId,
        )
        etNewSubtask.text?.clear()
        refreshSubtasks()
        onTaskUpdatedListener?.invoke()
    }

    private fun setupListeners() {
        etTitle.doAfterTextChanged {
            saveHandler.removeCallbacks(pendingSave)
            saveHandler.postDelayed(pendingSave, 500)
        }

        etDescription.doAfterTextChanged {
            saveHandler.removeCallbacks(pendingSave)
            saveHandler.postDelayed(pendingSave, 500)
        }

        btnCheck.setOnClickListener {
            isCompleted = !isCompleted
            updateCheckState()
            saveChanges()
        }

        rowContainer.setOnClickListener {
            showContainerPicker()
        }

        rowDue.setOnClickListener {
            showDuePicker()
        }

        btnClearDue.setOnClickListener {
            selectedDue = TaskDue.None
            updateDueDisplay()
            saveChanges(forceDue = true)
        }

        btnAcceptLegacyPlan.setOnClickListener {
            val t = currentTask ?: return@setOnClickListener
            val updated = try {
                taskService.resolveLegacyPlan(t.id, accept = true)
            } catch (_: org.isoron.uhabits.core.userTasks.InvalidLegacyPlanException) {
                AlertDialog.Builder(requireContext())
                    .setMessage("Не удалось распознать старую дату. Исходное значение сохранено. Выберите корректный срок вручную или нажмите «Отклонить».")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@setOnClickListener
            }
            currentTask = updated
            selectedDue = updated.due
            updateDueDisplay()
            onTaskUpdatedListener?.invoke()
        }

        btnDismissLegacyPlan.setOnClickListener {
            val t = currentTask ?: return@setOnClickListener
            val updated = taskService.resolveLegacyPlan(t.id, accept = false)
            currentTask = updated
            selectedDue = updated.due
            updateDueDisplay()
            onTaskUpdatedListener?.invoke()
        }

        btnMore.setOnClickListener {
            PopupMenu(requireContext(), btnMore).apply {
                menu.add("Удалить задачу")
                setOnMenuItemClickListener {
                    confirmDelete()
                    true
                }
            }.show()
        }
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

    private fun saveChanges(forceDue: Boolean = false) {
        saveHandler.removeCallbacks(pendingSave)
        if (!viewReady || taskDeleted) return
        val task = currentTask ?: return
        val newTitle = etTitle.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: task.title
        val newDesc = etDescription.text?.toString()?.trim() ?: ""

        val propertiesChanged = newTitle != task.title || newDesc != task.description ||
            selectedContainerId != task.containerId || selectedDue != task.due || forceDue
        if (!propertiesChanged && isCompleted == task.isCompleted) return
        var savedTask = task
        if (propertiesChanged) savedTask = taskService.updateTask(
            id = task.id,
            title = newTitle,
            description = newDesc,
            containerId = selectedContainerId,
            clearContainer = selectedContainerId == null,
            due = selectedDue.takeIf { forceDue || it != task.due },
        )

        if (isCompleted != task.isCompleted) {
            savedTask = taskService.setCompleted(task.id, isCompleted)
        }

        currentTask = savedTask
        updateDueDisplay()
        onTaskUpdatedListener?.invoke()
    }

    override fun onPause() {
        saveChanges()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (viewReady) {
            saveChanges()
            outState.putString("detailTitle", etTitle.text.toString())
            outState.putString("detailNotes", etDescription.text.toString())
            outState.putString("detailSubtask", etNewSubtask.text.toString())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDismiss(dialog: DialogInterface) {
        saveChanges()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        saveChanges()
        viewReady = false
        saveHandler.removeCallbacks(pendingSave)
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TASK_ID = "task_id"

        fun newInstance(taskId: TaskId): TaskDetailBottomSheet {
            return TaskDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TASK_ID, taskId.value)
                    putString("focusDatasetId", HabitsApplication.component.session.datasetId)
                }
            }
        }
    }
}
