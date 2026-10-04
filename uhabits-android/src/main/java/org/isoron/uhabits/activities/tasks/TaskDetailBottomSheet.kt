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

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.settings.SettingsThemePalette
import org.isoron.uhabits.core.containers.ContainerId
import org.isoron.uhabits.core.containers.facade.HabitOrganizationFacade
import org.isoron.uhabits.core.containers.facade.OrganizationalItem
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.core.userTasks.UserTask
import org.isoron.uhabits.core.userTasks.UserTaskService
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class TaskDetailBottomSheet : BottomSheetDialogFragment() {

    private lateinit var taskService: UserTaskService
    private lateinit var organizationFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette

    private var taskId: TaskId? = null
    private var currentTask: UserTask? = null
    private var selectedContainerId: ContainerId? = null
    private var selectedPlannedDate: LocalDate? = null
    private var selectedDeadline: Long? = null
    private var isCompleted: Boolean = false

    private lateinit var etTitle: EditText
    private lateinit var etDescription: EditText
    private lateinit var btnCheck: FrameLayout
    private lateinit var viewCheckCircle: View
    private lateinit var ivCheckmark: ImageView

    private lateinit var rowContainer: LinearLayout
    private lateinit var viewContainerDot: View
    private lateinit var tvContainerValue: TextView

    private lateinit var rowPlannedDate: LinearLayout
    private lateinit var tvPlannedDateValue: TextView
    private lateinit var btnClearPlannedDate: ImageButton

    private lateinit var rowDeadline: LinearLayout
    private lateinit var tvDeadlineValue: TextView
    private lateinit var btnClearDeadline: ImageButton

    private lateinit var rvSubtasks: RecyclerView
    private lateinit var subtasksAdapter: SubtasksAdapter
    private lateinit var tvSubtasksCount: TextView
    private lateinit var etNewSubtask: EditText

    private lateinit var btnDelete: Button
    private lateinit var btnSave: Button

    var onTaskUpdatedListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireContext().applicationContext as HabitsApplication
        taskService = app.component.userTaskService
        organizationFacade = app.component.organizationFacade
        palette = MainTabsThemeBridge.resolve(requireContext())

        val idStr = arguments?.getString(ARG_TASK_ID)
        if (idStr != null) {
            taskId = TaskId(idStr)
            currentTask = app.component.userTaskRepository.getById(taskId!!)
            currentTask?.let {
                selectedContainerId = it.containerId
                selectedPlannedDate = it.plannedDate
                selectedDeadline = it.deadline
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
        setupListeners()
        return view
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

        rowPlannedDate = view.findViewById(R.id.rowDetailPlannedDate)
        tvPlannedDateValue = view.findViewById(R.id.tvDetailPlannedDateValue)
        btnClearPlannedDate = view.findViewById(R.id.btnClearPlannedDate)

        rowDeadline = view.findViewById(R.id.rowDetailDeadline)
        tvDeadlineValue = view.findViewById(R.id.tvDetailDeadlineValue)
        btnClearDeadline = view.findViewById(R.id.btnClearDeadline)

        rvSubtasks = view.findViewById(R.id.rvDetailSubtasks)
        tvSubtasksCount = view.findViewById(R.id.tvDetailSubtasksCount)
        etNewSubtask = view.findViewById(R.id.etNewSubtaskTitle)

        btnDelete = view.findViewById(R.id.btnDeleteTask)
        btnSave = view.findViewById(R.id.btnSaveTask)
    }

    private fun dp(value: Float): Float = TaskThemeUtils.dp(requireContext(), value)
    private fun dpInt(value: Float): Int = TaskThemeUtils.dpInt(requireContext(), value)

    private fun applyTheme(view: View) {
        val root = view.findViewById<LinearLayout>(R.id.taskDetailRoot)
        root.setBackgroundColor(palette.surface)

        val dragHandle = view.findViewById<View>(R.id.viewDragHandle)
        val handleBg = GradientDrawable().apply {
            cornerRadius = dp(2f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.4f))
        }
        dragHandle.background = handleBg

        val dividers = listOf(
            view.findViewById<View>(R.id.divider1),
            view.findViewById<View>(R.id.divider2),
            view.findViewById<View>(R.id.divider3)
        )
        for (d in dividers) {
            d.setBackgroundColor(palette.divider)
        }

        etTitle.setTextColor(palette.onSurface)
        etTitle.setHintTextColor(palette.onSurfaceVariant)
        etDescription.setTextColor(palette.onSurface)
        etDescription.setHintTextColor(palette.onSurfaceVariant)
        etNewSubtask.setTextColor(palette.onSurface)
        etNewSubtask.setHintTextColor(palette.onSurfaceVariant)

        tvContainerValue.setTextColor(palette.onSurface)
        tvPlannedDateValue.setTextColor(palette.onSurface)
        tvDeadlineValue.setTextColor(palette.onSurface)
        tvSubtasksCount.setTextColor(palette.onSurfaceVariant)

        btnClearPlannedDate.setColorFilter(palette.onSurfaceVariant)
        btnClearDeadline.setColorFilter(palette.onSurfaceVariant)

        btnSave.setBackgroundColor(palette.accent)
        btnSave.setTextColor(Color.WHITE)
    }

    private fun populateData() {
        val task = currentTask ?: return

        etTitle.setText(task.title)
        etDescription.setText(task.description)
        updateCheckState()
        updateContainerDisplay()
        updatePlannedDateDisplay()
        updateDeadlineDisplay()
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
            circleDrawable.setStroke(dpInt(1.5f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.45f))
            viewCheckCircle.background = circleDrawable
            ivCheckmark.visibility = View.GONE
        }
    }

    private fun updateContainerDisplay() {
        val cid = selectedContainerId
        if (cid == null) {
            viewContainerDot.visibility = View.GONE
            tvContainerValue.text = "Без раздела"
        } else {
            val containers = organizationFacade.availableContainers()
            val item = containers.find { it.key == cid.value }
            if (item != null) {
                viewContainerDot.visibility = View.VISIBLE
                val dotColor = TaskThemeUtils.resolvePaletteColor(requireContext(), item.color, palette.accent)
                viewContainerDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
                tvContainerValue.text = item.name
            } else {
                viewContainerDot.visibility = View.GONE
                tvContainerValue.text = "Раздел $cid"
            }
        }
    }

    private fun updatePlannedDateDisplay() {
        val p = selectedPlannedDate
        if (p == null) {
            tvPlannedDateValue.text = "Не запланировано"
            btnClearPlannedDate.visibility = View.GONE
        } else {
            val today = runCatching { getToday() }.getOrDefault(LocalDate(2026, 10, 4))
            val text = when {
                p == today -> "Сегодня"
                p.daysSince2000 == today.daysSince2000 + 1 -> "Завтра"
                p.daysSince2000 == today.daysSince2000 - 1 -> "Вчера"
                else -> "${p.day}.${p.month.toString().padStart(2, '0')}.${p.year}"
            }
            tvPlannedDateValue.text = text
            btnClearPlannedDate.visibility = View.VISIBLE
        }
    }

    private fun updateDeadlineDisplay() {
        val d = selectedDeadline
        if (d == null) {
            tvDeadlineValue.text = "Нет дедлайна"
            btnClearDeadline.visibility = View.GONE
        } else {
            val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            tvDeadlineValue.text = df.format(Date(d))
            btnClearDeadline.visibility = View.VISIBLE
        }
    }

    private fun setupSubtasks() {
        val task = currentTask ?: return
        val app = requireContext().applicationContext as HabitsApplication

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
        btnCheck.setOnClickListener {
            isCompleted = !isCompleted
            updateCheckState()
        }

        rowContainer.setOnClickListener {
            showContainerPicker()
        }

        rowPlannedDate.setOnClickListener {
            showPlannedDatePicker()
        }

        btnClearPlannedDate.setOnClickListener {
            selectedPlannedDate = null
            updatePlannedDateDisplay()
        }

        rowDeadline.setOnClickListener {
            showDeadlinePicker()
        }

        btnClearDeadline.setOnClickListener {
            selectedDeadline = null
            updateDeadlineDisplay()
        }

        btnDelete.setOnClickListener {
            taskId?.let { id ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Удалить задачу?")
                    .setMessage("Задача и все её подзадачи будут удалены.")
                    .setPositiveButton("Удалить") { _, _ ->
                        taskService.deleteTask(id, cascadeSubtasks = true)
                        onTaskUpdatedListener?.invoke()
                        dismiss()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }

        btnSave.setOnClickListener {
            saveChanges()
            dismiss()
        }
    }

    private fun showContainerPicker() {
        val containers = organizationFacade.availableContainers()
        val items = mutableListOf("Без раздела")
        items.addAll(containers.map { it.name })

        AlertDialog.Builder(requireContext())
            .setTitle("Выберите раздел")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedContainerId = null
                } else {
                    selectedContainerId = ContainerId(containers[which - 1].key)
                }
                updateContainerDisplay()
            }
            .show()
    }

    private fun showPlannedDatePicker() {
        val options = arrayOf("Сегодня", "Завтра", "Выбрать дату в календаре")
        AlertDialog.Builder(requireContext())
            .setTitle("Запланировать на")
            .setItems(options) { _, which ->
                val today = runCatching { getToday() }.getOrDefault(LocalDate(2026, 10, 4))
                when (which) {
                    0 -> {
                        selectedPlannedDate = today
                        updatePlannedDateDisplay()
                    }
                    1 -> {
                        selectedPlannedDate = LocalDate(today.daysSince2000 + 1)
                        updatePlannedDateDisplay()
                    }
                    2 -> {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(
                            requireContext(),
                            { _, year, month, dayOfMonth ->
                                selectedPlannedDate = LocalDate(year, month + 1, dayOfMonth)
                                updatePlannedDateDisplay()
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun showDeadlinePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    requireContext(),
                    { _, hourOfDay, minute ->
                        val selected = Calendar.getInstance().apply {
                            set(year, month, dayOfMonth, hourOfDay, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        selectedDeadline = selected.timeInMillis
                        updateDeadlineDisplay()
                    },
                    18,
                    0,
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveChanges() {
        val task = currentTask ?: return
        val newTitle = etTitle.text?.toString()?.trim() ?: task.title
        val newDesc = etDescription.text?.toString()?.trim() ?: ""

        taskService.updateTask(
            id = task.id,
            title = if (newTitle.isNotBlank()) newTitle else task.title,
            description = newDesc,
            containerId = selectedContainerId,
            clearContainer = selectedContainerId == null,
            plannedDate = selectedPlannedDate,
            clearPlannedDate = selectedPlannedDate == null,
            deadline = selectedDeadline,
            clearDeadline = selectedDeadline == null,
        )

        if (isCompleted != task.isCompleted) {
            taskService.setCompleted(task.id, isCompleted)
        }

        onTaskUpdatedListener?.invoke()
    }

    override fun onDestroyView() {
        saveChanges()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TASK_ID = "task_id"

        fun newInstance(taskId: TaskId): TaskDetailBottomSheet {
            return TaskDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TASK_ID, taskId.value)
                }
            }
        }
    }
}
