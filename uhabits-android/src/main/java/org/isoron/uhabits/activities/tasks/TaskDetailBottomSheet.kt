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
import android.content.res.ColorStateList
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.isoron.platform.io.runInTransaction
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

class TaskDetailBottomSheet : BottomSheetDialogFragment() {

    private lateinit var taskService: UserTaskService
    private lateinit var organizationFacade: HabitOrganizationFacade
    private lateinit var palette: SettingsThemePalette

    private var taskId: TaskId? = null
    private var currentTask: UserTask? = null
    private var isCreateMode: Boolean = false

    private var selectedContainerId: ContainerId? = null
    private var selectedDue: TaskDue = TaskDue.None
    private var selectedImportant: Boolean? = null
    private var selectedUrgent: Boolean? = null
    private lateinit var classificationControls: LinearLayout
    private var isCompleted: Boolean = false
    private var selectedTagIds: Set<String> = emptySet()
    private lateinit var tagChips: com.google.android.material.chip.ChipGroup
    private val draftSubtasks = mutableListOf<TaskDraft.DraftSubtask>()

    // Top Bar views
    private lateinit var topBarCreate: View
    private lateinit var topBarEdit: View
    private lateinit var btnDetailCollapse: TextView
    private lateinit var tvDetailSheetTitle: TextView
    private lateinit var btnDetailCreate: Button
    private lateinit var tvDetailSaveStatus: TextView
    private lateinit var btnDetailMore: ImageButton

    // Title & Check
    private lateinit var btnCheck: FrameLayout
    private lateinit var viewCheckCircle: View
    private lateinit var ivCheckmark: ImageView
    private lateinit var etTitle: EditText

    // Attributes (Chips)
    private lateinit var chipDue: View
    private lateinit var ivDueIcon: ImageView
    private lateinit var tvDueValue: TextView
    private lateinit var btnClearDue: ImageView
    private lateinit var chipContainer: View
    private lateinit var ivContainerIcon: ImageView
    private lateinit var tvContainerValue: TextView

    // Note
    private lateinit var etDescription: EditText

    // Subtasks
    private lateinit var rvSubtasks: RecyclerView
    private lateinit var subtasksAdapter: SubtasksAdapter
    private lateinit var tvSubtasksCount: TextView
    private lateinit var etNewSubtask: EditText
    private lateinit var btnAddSubtask: ImageButton

    // Focus & Legacy Plan
    private lateinit var rowFocus: View
    private lateinit var cardLegacyPlanDecision: View
    private lateinit var tvLegacyPlanPrompt: TextView
    private lateinit var btnAcceptLegacyPlan: Button
    private lateinit var btnDismissLegacyPlan: Button

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
                selectedImportant = it.important
                selectedUrgent = it.urgent
                selectedTagIds = launchComponent.taskTagRepository.getTagIds(it.id)
                selectedContainerId = it.containerId
                selectedDue = it.due
                isCompleted = it.isCompleted
            }
        } else {
            isCreateMode = true
            val initialDraft = TaskDraft.fromBundle(arguments?.getBundle(ARG_TASK_DRAFT))
            selectedImportant = initialDraft.important
            selectedUrgent = initialDraft.urgent
            selectedTagIds = initialDraft.tagIds
            selectedContainerId = initialDraft.containerId
            selectedDue = initialDraft.due
            isCompleted = false
            draftSubtasks.clear()
            draftSubtasks.addAll(initialDraft.subtasks)
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
        setupTagChips(view)
        populateData()
        updateTagDisplay()

        savedInstanceState?.let { restored ->
            etTitle.setText(restored.getString("detailTitle", etTitle.text.toString()))
            etDescription.setText(restored.getString("detailNotes", etDescription.text.toString()))
            etNewSubtask.setText(restored.getString("detailSubtask", ""))
            if (isCreateMode && restored.containsKey("draftBundle")) {
                val restoredDraft = TaskDraft.fromBundle(restored.getBundle("draftBundle"))
                selectedImportant = restoredDraft.important
                selectedUrgent = restoredDraft.urgent
                selectedTagIds = restoredDraft.tagIds
                updateTagDisplay()
                selectedContainerId = restoredDraft.containerId
                selectedDue = restoredDraft.due
                draftSubtasks.clear()
                draftSubtasks.addAll(restoredDraft.subtasks)
                updateDueDisplay()
                updateContainerDisplay()
                refreshSubtasks()
            }
        }

        setupClassificationControls()
        setupListeners()
        viewReady = true

        childFragmentManager.setFragmentResultListener("detailDue", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            selectedDue = TaskDuePickerBottomSheet.readResult(result)
            updateDueDisplay()
            if (!isCreateMode) {
                saveChanges(forceDue = result.getBoolean(TaskDuePickerBottomSheet.RESULT_EDITED))
            }
        }

        childFragmentManager.setFragmentResultListener("detailContainer", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            selectedContainerId = result.getString(TaskContainerPickerBottomSheet.RESULT_CONTAINER_ID)?.let(::ContainerId)
            updateContainerDisplay()
            if (!isCreateMode) {
                saveChanges()
            }
        }

        childFragmentManager.setFragmentResultListener("detailTags", viewLifecycleOwner) { _, result ->
            if (taskDeleted || HabitsApplication.component !== launchComponent) return@setFragmentResultListener
            if (result.getBoolean(TaskTagPickerBottomSheet.RESULT_APPLIED)) {
                val next = result.getStringArrayList(TaskTagPickerBottomSheet.RESULT_TAG_IDS).orEmpty().toSet()
                if (isCreateMode) selectedTagIds = next else {
                    try {
                        launchComponent.taskTagRepository.setTags(taskId ?: return@setFragmentResultListener, next)
                        selectedTagIds = next
                        tvDetailSaveStatus.text = "Изменения сохранены"
                    } catch (e: Exception) {
                        tvDetailSaveStatus.text = "Не удалось сохранить теги"
                    }
                }
            }
            updateTagDisplay()
            onTaskUpdatedListener?.invoke()
        }

        rowFocus.apply {
            visibility = if (!isCreateMode && launchComponent.session.isNextArchitecture && currentTask != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
            setOnClickListener {
                val app = requireContext().applicationContext as HabitsApplication
                if (app.component !== launchComponent || taskDeleted || isCreateMode) return@setOnClickListener
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

        if (!isCreateMode) {
            refreshFocusSummary(view)
        }

        return view
    }

    private fun refreshFocusSummary(root: View? = view) {
        if (!launchComponent.session.isNextArchitecture || isCreateMode) return
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
                    text = "всего $formatted"
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.dismissWithAnimation = true
            sheet.behavior.skipCollapsed = true
            // The editor consumes IME insets itself; resizing the dialog as well would
            // subtract the keyboard twice on short displays.
            sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            sheet.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                fun resize(insets: WindowInsetsCompat?) {
                    val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                    val windowHeight = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        requireContext().getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.height()
                    } else resources.displayMetrics.heightPixels
                    val height = (windowHeight - (bars?.top ?: 0) - dpInt(16f)).coerceAtLeast(dpInt(160f))
                    if (layoutParams.height != height) {
                        layoutParams = layoutParams.apply { this.height = height }
                    }
                    sheet.behavior.maxHeight = height
                    val bottom = maxOf(bars?.bottom ?: 0, insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                    view?.findViewById<View>(R.id.taskDetailRoot)?.setPadding(dpInt(16f), dpInt(8f), dpInt(16f), bottom)
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
                background = GradientDrawable().apply {
                    setColor(palette.surface)
                    cornerRadii = floatArrayOf(dp(24f), dp(24f), dp(24f), dp(24f), 0f, 0f, 0f, 0f)
                }
                clipToOutline = true
                sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun bindViews(view: View) {
        topBarCreate = view.findViewById(R.id.topBarCreate)
        topBarEdit = view.findViewById(R.id.topBarEdit)
        btnDetailCollapse = view.findViewById(R.id.btnDetailCollapse)
        tvDetailSheetTitle = view.findViewById(R.id.tvDetailSheetTitle)
        btnDetailCreate = view.findViewById(R.id.btnDetailCreate)
        tvDetailSaveStatus = view.findViewById(R.id.tvDetailSaveStatus)
        btnDetailMore = view.findViewById(R.id.btnDetailMore)

        btnCheck = view.findViewById(R.id.btnDetailCheck)
        viewCheckCircle = view.findViewById(R.id.viewDetailCheckCircle)
        ivCheckmark = view.findViewById(R.id.ivDetailCheckmark)
        etTitle = view.findViewById(R.id.etDetailTitle)

        chipDue = view.findViewById(R.id.chipDetailDue)
        ivDueIcon = view.findViewById(R.id.ivDetailDueIcon)
        tvDueValue = view.findViewById(R.id.tvDetailDueValue)
        btnClearDue = view.findViewById(R.id.btnClearDue)

        chipContainer = view.findViewById(R.id.chipDetailContainer)
        ivContainerIcon = view.findViewById(R.id.ivDetailContainerIcon)
        tvContainerValue = view.findViewById(R.id.tvDetailContainerValue)

        etDescription = view.findViewById(R.id.etDetailDescription)

        rvSubtasks = view.findViewById(R.id.rvDetailSubtasks)
        tvSubtasksCount = view.findViewById(R.id.tvDetailSubtasksCount)
        etNewSubtask = view.findViewById(R.id.etNewSubtaskTitle)
        btnAddSubtask = view.findViewById(R.id.ivAddSubtask)

        rowFocus = view.findViewById(R.id.rowDetailFocus)
        cardLegacyPlanDecision = view.findViewById(R.id.cardLegacyPlanDecision)
        tvLegacyPlanPrompt = view.findViewById(R.id.tvLegacyPlanPrompt)
        btnAcceptLegacyPlan = view.findViewById(R.id.btnAcceptLegacyPlan)
        btnDismissLegacyPlan = view.findViewById(R.id.btnDismissLegacyPlan)
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
        dragHandle.background = GradientDrawable().apply {
            cornerRadius = dp(2f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.4f))
        }

        listOf(R.id.dividerAttributes, R.id.dividerNote).forEach { id ->
            view.findViewById<View>(id)?.setBackgroundColor(palette.divider)
        }

        // Top bar theme
        btnDetailCollapse.setTextColor(palette.onSurfaceVariant)
        tvDetailSheetTitle.setTextColor(palette.onSurface)
        btnDetailCreate.apply {
            val blue = ColorStateList.valueOf(0xFF2563EB.toInt())
            (this as? androidx.appcompat.widget.AppCompatButton)?.supportBackgroundTintList = blue
            isAllCaps = false
            minimumHeight = 0
            minHeight = 0
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(0xFF2563EB.toInt())
            }
            backgroundTintList = blue
            (this as? androidx.appcompat.widget.AppCompatButton)?.supportBackgroundTintList = blue
            setTextColor(Color.WHITE)
        }
        tvDetailSaveStatus.setTextColor(palette.onSurfaceVariant)
        btnDetailMore.setColorFilter(palette.onSurfaceVariant)

        // Title and description
        etTitle.setTextColor(palette.onSurface)
        etTitle.setHintTextColor(palette.onSurfaceVariant)
        etDescription.setTextColor(palette.onSurface)
        etDescription.setHintTextColor(palette.onSurfaceVariant)

        // Subtasks theme
        view.findViewById<TextView>(R.id.tvDetailDescriptionLabel).setTextColor(palette.onSurfaceVariant)
        view.findViewById<TextView>(R.id.tvDetailSubtasksLabel).setTextColor(palette.onSurface)
        tvSubtasksCount.setTextColor(palette.onSurfaceVariant)
        etNewSubtask.setTextColor(palette.onSurface)
        etNewSubtask.setHintTextColor(palette.onSurfaceVariant)
        btnAddSubtask.setColorFilter(palette.onSurfaceVariant)

        // Focus card theme
        view.findViewById<TextView>(R.id.tvDetailFocusTitle)?.setTextColor(palette.onSurface)
        view.findViewById<TextView>(R.id.tvDetailFocus)?.setTextColor(palette.onSurfaceVariant)
        val focusBg = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.04f))
            setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.2f))
        }
        rowFocus.background = focusBg
        val playBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accent)
        }
        view.findViewById<View>(R.id.layoutFocusPlay)?.background = playBg
        view.findViewById<ImageView>(R.id.ivDetailFocusIcon)?.setColorFilter(Color.WHITE)

        // Legacy plan card theme
        cardLegacyPlanDecision.background = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.12f))
        }
        tvLegacyPlanPrompt.setTextColor(palette.onSurface)
        btnDismissLegacyPlan.setTextColor(palette.onSurfaceVariant)
        btnAcceptLegacyPlan.setTextColor(palette.accent)
    }

    private fun populateData() {
        if (isCreateMode) {
            topBarCreate.visibility = View.VISIBLE
            topBarEdit.visibility = View.GONE
            btnCheck.visibility = View.GONE
            cardLegacyPlanDecision.visibility = View.GONE
            rowFocus.visibility = View.GONE

            val draft = TaskDraft.fromBundle(arguments?.getBundle(ARG_TASK_DRAFT))
            etTitle.setText(draft.title)
            etDescription.setText(draft.description)
            selectedContainerId = draft.containerId
            selectedDue = draft.due
            updateContainerDisplay()
            updateDueDisplay()
            setupSubtasks()
        } else {
            topBarCreate.visibility = View.GONE
            topBarEdit.visibility = View.VISIBLE
            btnCheck.visibility = View.VISIBLE

            val task = currentTask ?: return
            etTitle.setText(task.title)
            etDescription.setText(task.description)
            selectedContainerId = task.containerId
            selectedDue = task.due
            isCompleted = task.isCompleted

            updateCheckState()
            updateContainerDisplay()
            updateDueDisplay()
            setupSubtasks()
        }
    }

    private fun setupClassificationControls() {
        classificationControls = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val holder = tagChips.parent as LinearLayout
        holder.addView(classificationControls, holder.indexOfChild(tagChips) + 1)
        updateClassificationControls()
    }

    private fun updateClassificationControls() {
        classificationControls.removeAllViews()
        val ru = TaskDueDisplayFormatter.selectedLocale(requireContext()).language == "ru"
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TaskThemeUtils.dpInt(context, 4f)
            }
        }
        listOf(true, false).forEachIndexed { index, important ->
            val label = if (important) { if (ru) "Важность" else "Important" } else { if (ru) "Срочность" else "Urgent" }
            val options = if (important) {
                if (ru) arrayOf("Не определено", "Важно", "Не важно")
                else arrayOf("Not set", "Important", "Not important")
            } else {
                if (ru) arrayOf("Не определено", "Срочно", "Не срочно")
                else arrayOf("Not set", "Urgent", "Not urgent")
            }
            val value = if (important) selectedImportant else selectedUrgent
            val choiceIndex = when (value) { null -> 0; true -> 1; false -> 2 }
            val valueText = options[choiceIndex]
            val btn = Button(requireContext()).apply {
                text = "$label: $valueText"
                isAllCaps = false
                textSize = 12f
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                minHeight = TaskThemeUtils.dpInt(context, 40f)
                minimumHeight = TaskThemeUtils.dpInt(context, 40f)
                stateListAnimator = null
                elevation = 0f
                translationZ = 0f
                setPaddingRelative(TaskThemeUtils.dpInt(context, 8f), TaskThemeUtils.dpInt(context, 6f), TaskThemeUtils.dpInt(context, 8f), TaskThemeUtils.dpInt(context, 6f))
                setTextColor(if (value != null) palette.onSurface else palette.onSurfaceVariant)
                background = GradientDrawable().apply {
                    cornerRadius = dp(18f)
                    if (value != null) {
                        setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.12f))
                        setStroke(TaskThemeUtils.dpInt(context, 1f), MainTabsThemeBridge.withAlpha(palette.accent, 0.45f))
                    } else {
                        setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                        setStroke(TaskThemeUtils.dpInt(context, 1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.2f))
                    }
                }
                contentDescription = "$label: $valueText. " + (if (ru) "Нажмите, чтобы изменить" else "Tap to change")
                setOnClickListener {
                    if (HabitsApplication.component !== launchComponent || taskDeleted) return@setOnClickListener
                    AlertDialog.Builder(requireContext()).setTitle(label)
                        .setSingleChoiceItems(options, choiceIndex) { dialog, choice ->
                            if (HabitsApplication.component === launchComponent && !taskDeleted) {
                                val next = when (choice) { 1 -> true; 2 -> false; else -> null }
                                if (important) selectedImportant = next else selectedUrgent = next
                                updateClassificationControls()
                                if (!isCreateMode) {
                                    tvDetailSaveStatus.text = if (ru) "Сохранение…" else "Saving…"
                                    try { saveChanges() } catch (e: Exception) {
                                        tvDetailSaveStatus.text = if (ru) "Не удалось сохранить" else "Could not save"
                                    }
                                }
                            }
                            dialog.dismiss()
                        }.setNegativeButton(R.string.containers_cancel, null).show()
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index == 0) marginEnd = TaskThemeUtils.dpInt(requireContext(), 4f)
                else marginStart = TaskThemeUtils.dpInt(requireContext(), 4f)
            }
            row.addView(btn, lp)
        }
        classificationControls.addView(row)
        classificationControls.addView(TextView(requireContext()).apply {
            text = if (ru) "Локально · без синхронизации" else "Local only · No cloud sync"
            textSize = 11.5f
            setTextColor(MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.75f))
            contentDescription = if (ru) "Оценки сохраняются на этом устройстве и в резервной копии. Облачная синхронизация пока недоступна."
                else "These ratings are saved on this device and in backups. Cloud sync is not available yet."
            setPaddingRelative(TaskThemeUtils.dpInt(context, 4f), 0, TaskThemeUtils.dpInt(context, 4f), TaskThemeUtils.dpInt(context, 8f))
            setOnClickListener {
                android.widget.Toast.makeText(context, contentDescription, android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setupTagChips(view: View) {
        tagChips = com.google.android.material.chip.ChipGroup(requireContext()).apply {
            chipSpacingHorizontal = TaskThemeUtils.dpInt(context, 6f)
            chipSpacingVertical = 0
            isSingleLine = false
            setPadding(0, 0, 0, TaskThemeUtils.dpInt(context, 8f))
        }
        val attributes = view.findViewById<View>(R.id.layoutDetailChips).parent as View
        val holder = attributes.parent as LinearLayout
        holder.addView(tagChips, holder.indexOfChild(attributes) + 1)
    }

    private fun updateTagDisplay() {
        if (!::tagChips.isInitialized || HabitsApplication.component !== launchComponent) return
        val available = launchComponent.taskTagRepository.getAll()
        selectedTagIds = if (!isCreateMode && taskId != null) launchComponent.taskTagRepository.getTagIds(taskId!!)
            else selectedTagIds.intersect(available.map { it.id }.toSet())
        tagChips.removeAllViews()
        for (tag in available.filter { it.id in selectedTagIds }) {
            tagChips.addView(com.google.android.material.chip.Chip(requireContext()).apply {
                text = tag.name; textSize = 12f
                maxWidth = TaskThemeUtils.dpInt(context, 220f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.onSurfaceVariant)
                chipBackgroundColor = ColorStateList.valueOf(palette.surfaceVariant)
                chipStrokeWidth = 0f
                isCloseIconVisible = true
                closeIconTint = ColorStateList.valueOf(palette.onSurfaceVariant)
                closeIconContentDescription = "Снять тег ${tag.name}"
                setEnsureMinTouchTargetSize(true)
                setOnCloseIconClickListener {
                    if (HabitsApplication.component !== launchComponent || taskDeleted) return@setOnCloseIconClickListener
                    val next = selectedTagIds - tag.id
                    try {
                        if (!isCreateMode) launchComponent.taskTagRepository.setTags(taskId!!, next)
                        selectedTagIds = next; updateTagDisplay(); onTaskUpdatedListener?.invoke()
                    } catch (e: Exception) { tvDetailSaveStatus.text = "Не удалось сохранить теги" }
                }
            })
        }
        tagChips.addView(com.google.android.material.chip.Chip(requireContext()).apply {
            text = "+ Тег"; textSize = 12f; contentDescription = "Добавить или выбрать теги"
            setTextColor(palette.onSurfaceVariant)
            chipBackgroundColor = ColorStateList.valueOf(palette.surface)
            chipStrokeColor = ColorStateList.valueOf(palette.divider); chipStrokeWidth = TaskThemeUtils.dp(context, 1f)
            setEnsureMinTouchTargetSize(true)
            setOnClickListener {
                if (HabitsApplication.component === launchComponent && !taskDeleted && childFragmentManager.findFragmentByTag("detailTags") == null)
                    TaskTagPickerBottomSheet.newInstance(selectedTagIds, "detailTags", launchComponent.session.datasetId)
                        .show(childFragmentManager, "detailTags")
            }
        })
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
        val containerName = if (cid == null) {
            "Входящие"
        } else {
            val containers = organizationFacade.availableContainers()
            containers.find { it.key == cid.value }?.name ?: "Недоступный раздел"
        }
        tvContainerValue.text = containerName
        chipContainer.contentDescription = "Раздел: $containerName. Изменить раздел"

        val hasCustomContainer = cid != null
        chipContainer.background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
            setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, if (hasCustomContainer) 0.35f else 0.2f))
        }
        tvContainerValue.setTextColor(if (hasCustomContainer) palette.onSurface else palette.onSurfaceVariant)
        ivContainerIcon.setColorFilter(palette.onSurfaceVariant)
    }

    private fun updateDueDisplay() {
        chipDue.contentDescription = null
        val hasLegacy = !isCreateMode && currentTask?.hasPendingLegacyPlan == true && selectedDue is TaskDue.None
        if (hasLegacy) {
            cardLegacyPlanDecision.visibility = View.VISIBLE
            tvLegacyPlanPrompt.text = "Ранее запланировано: ${currentTask?.legacyPlanDate}"
        } else {
            cardLegacyPlanDecision.visibility = View.GONE
        }

        val hasDue = selectedDue !is TaskDue.None
        val text = TaskDueDisplayFormatter.format(selectedDue, TaskDueDisplayFormatter.selectedLocale(requireContext()))
        tvDueValue.text = text
        btnClearDue.visibility = if (hasDue) View.VISIBLE else View.GONE
        chipDue.contentDescription = if (hasDue) "Срок: $text. Изменить срок"
            else "Срок не задан. Нажмите, чтобы задать срок"

        chipDue.background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            if (hasDue) {
                setColor(MainTabsThemeBridge.withAlpha(palette.accent, 0.12f))
                setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.accent, 0.4f))
            } else {
                setColor(MainTabsThemeBridge.withAlpha(palette.onSurface, 0.05f))
                setStroke(dpInt(1f), MainTabsThemeBridge.withAlpha(palette.onSurfaceVariant, 0.2f))
            }
        }
        val contentColor = if (hasDue) palette.accent else palette.onSurfaceVariant
        tvDueValue.setTextColor(contentColor)
        ivDueIcon.setColorFilter(contentColor)
        btnClearDue.setColorFilter(contentColor)
    }

    private fun setupSubtasks() {
        subtasksAdapter = SubtasksAdapter(
            palette = palette,
            onToggleComplete = { item ->
                if (isCreateMode) {
                    val idx = item.id.toIntOrNull()
                    if (idx != null && idx in draftSubtasks.indices) {
                        draftSubtasks[idx] = draftSubtasks[idx].copy(isCompleted = !draftSubtasks[idx].isCompleted)
                        refreshSubtasks()
                    }
                } else {
                    taskService.toggleComplete(TaskId(item.id))
                    refreshSubtasks()
                    onTaskUpdatedListener?.invoke()
                }
            },
            onDeleteSubtask = { item ->
                if (isCreateMode) {
                    val idx = item.id.toIntOrNull()
                    if (idx != null && idx in draftSubtasks.indices) {
                        draftSubtasks.removeAt(idx)
                        refreshSubtasks()
                    }
                } else {
                    taskService.deleteTask(TaskId(item.id))
                    refreshSubtasks()
                    onTaskUpdatedListener?.invoke()
                }
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
        if (isCreateMode) {
            val items = draftSubtasks.mapIndexed { idx, sub ->
                SubtasksAdapter.SubtaskItem(
                    id = idx.toString(),
                    title = sub.title,
                    isCompleted = sub.isCompleted
                )
            }
            subtasksAdapter.updateData(items)
            val completed = draftSubtasks.count { it.isCompleted }
            tvSubtasksCount.text = "$completed / ${draftSubtasks.size}"
        } else {
            val task = currentTask ?: return
            val app = requireContext().applicationContext as HabitsApplication
            val subtasks = app.component.userTaskRepository.getSubtasks(task.id)
            val items = subtasks.map {
                SubtasksAdapter.SubtaskItem(
                    id = it.id.value,
                    title = it.title,
                    isCompleted = it.isCompleted
                )
            }
            subtasksAdapter.updateData(items)
            val completedCount = subtasks.count { it.isCompleted }
            tvSubtasksCount.text = "$completedCount / ${subtasks.size}"
        }
    }

    private fun addNewSubtask() {
        val title = etNewSubtask.text?.toString()?.trim() ?: ""
        if (title.isBlank()) return

        if (isCreateMode) {
            draftSubtasks.add(TaskDraft.DraftSubtask(title = title, isCompleted = false))
            etNewSubtask.text?.clear()
            refreshSubtasks()
        } else {
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
    }

    private fun setupListeners() {
        if (isCreateMode) {
            btnDetailCollapse.setOnClickListener {
                dismiss()
            }

            btnDetailCreate.setOnClickListener {
                submitCreateTask()
            }
        } else {
            etTitle.doAfterTextChanged {
                tvDetailSaveStatus.text = "Сохранение…"
                tvDetailSaveStatus.alpha = 0.7f
                saveHandler.removeCallbacks(pendingSave)
                saveHandler.postDelayed(pendingSave, 500)
            }

            etDescription.doAfterTextChanged {
                tvDetailSaveStatus.text = "Сохранение…"
                tvDetailSaveStatus.alpha = 0.7f
                saveHandler.removeCallbacks(pendingSave)
                saveHandler.postDelayed(pendingSave, 500)
            }

            btnCheck.setOnClickListener {
                isCompleted = !isCompleted
                updateCheckState()
                saveChanges()
            }

            btnDetailMore.setOnClickListener {
                PopupMenu(requireContext(), btnDetailMore).apply {
                    menu.add("Удалить задачу")
                    setOnMenuItemClickListener {
                        confirmDelete()
                        true
                    }
                }.show()
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
        }

        chipContainer.setOnClickListener {
            showContainerPicker()
        }

        chipDue.setOnClickListener {
            showDuePicker()
        }

        btnClearDue.setOnClickListener {
            selectedDue = TaskDue.None
            updateDueDisplay()
            if (!isCreateMode) {
                saveChanges(forceDue = true)
            }
        }
    }

    private fun submitCreateTask() {
        if (taskCreated || !btnDetailCreate.isEnabled) return
        val title = etTitle.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            etTitle.requestFocus()
            return
        }

        // Double-tap prevention
        btnDetailCreate.isEnabled = false

        val description = etDescription.text?.toString()?.trim().orEmpty()
        val due = selectedDue
        val containerId = selectedContainerId
        val subtasksSnapshot = draftSubtasks.toList()

        try { launchComponent.db.runInTransaction {
            val mainTask = taskService.createTask(
                title = title,
                description = description,
                containerId = containerId,
                due = due,
                important = selectedImportant,
                urgent = selectedUrgent,
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
        } } catch (e: Exception) {
            android.util.Log.e("TaskDetailBottomSheet", "Task creation failed", e)
            btnDetailCreate.isEnabled = true
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
        if (!viewReady || taskDeleted || isCreateMode || HabitsApplication.component !== launchComponent) return
        val task = currentTask ?: return
        val newTitle = etTitle.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: task.title
        val newDesc = etDescription.text?.toString()?.trim() ?: ""

        val importantChanged = selectedImportant != task.important
        val urgentChanged = selectedUrgent != task.urgent
        val propertiesChanged = newTitle != task.title || newDesc != task.description ||
            selectedContainerId != task.containerId || selectedDue != task.due || forceDue ||
            importantChanged || urgentChanged
        if (!propertiesChanged && isCompleted == task.isCompleted) {
            tvDetailSaveStatus.text = "Изменения сохранены"
            tvDetailSaveStatus.alpha = 1f
            return
        }

        var savedTask = task
        if (propertiesChanged) savedTask = taskService.updateTask(
            id = task.id,
            title = newTitle,
            description = newDesc,
            containerId = selectedContainerId,
            clearContainer = selectedContainerId == null,
            important = if (importantChanged && selectedImportant != null) selectedImportant else null,
            urgent = if (urgentChanged && selectedUrgent != null) selectedUrgent else null,
            clearImportant = importantChanged && selectedImportant == null,
            clearUrgent = urgentChanged && selectedUrgent == null,
            due = selectedDue.takeIf { forceDue || it != task.due },
        )

        if (isCompleted != task.isCompleted) {
            savedTask = taskService.setCompleted(task.id, isCompleted)
        }

        currentTask = savedTask
        val rebasedImportant = savedTask.important
        val rebasedUrgent = savedTask.urgent
        val controlsNeedUpdate = rebasedImportant != selectedImportant || rebasedUrgent != selectedUrgent
        selectedImportant = rebasedImportant
        selectedUrgent = rebasedUrgent
        if (controlsNeedUpdate && ::classificationControls.isInitialized) {
            updateClassificationControls()
        }
        updateDueDisplay()
        tvDetailSaveStatus.text = "Изменения сохранены"
        tvDetailSaveStatus.alpha = 1f
        onTaskUpdatedListener?.invoke()
    }

    override fun onPause() {
        if (!isCreateMode) {
            saveChanges()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (viewReady) {
            if (!isCreateMode) {
                saveChanges()
            }
            outState.putString("detailTitle", etTitle.text.toString())
            outState.putString("detailNotes", etDescription.text.toString())
            outState.putString("detailSubtask", etNewSubtask.text.toString())
            if (isCreateMode) {
                val currentDraft = buildCurrentDraft()
                outState.putBundle("draftBundle", currentDraft.toBundle())
            }
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildCurrentDraft(): TaskDraft {
        return TaskDraft(
            title = if (::etTitle.isInitialized) etTitle.text?.toString().orEmpty() else "",
            description = if (::etDescription.isInitialized) etDescription.text?.toString().orEmpty() else "",
            tagIds = selectedTagIds,
            important = selectedImportant,
            urgent = selectedUrgent,
            due = selectedDue,
            containerId = selectedContainerId,
            subtasks = draftSubtasks.toList()
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!isCreateMode) {
            saveChanges()
        } else if (!taskCreated) {
            // Sheet was dismissed/collapsed without saving: return current draft back to Quick Add
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
        if (!isCreateMode) {
            saveChanges()
        }
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

        private const val ARG_TASK_ID = "task_id"
        private const val ARG_TASK_DRAFT = "task_draft"

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
