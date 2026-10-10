package org.isoron.uhabits.activities.tasks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.theme.MainTabsThemeBridge
import org.isoron.uhabits.activities.habits.show.timer.PomodoroAlertIssue
import org.isoron.uhabits.activities.habits.show.timer.PomodoroCompletionNotifier
import org.isoron.uhabits.activities.habits.show.views.TimerCardView
import org.isoron.uhabits.core.userTasks.TaskId
import org.isoron.uhabits.inject.HabitsApplicationComponent

/** Hosts the existing timer view; Task Detail and its workspace stay underneath. */
class TaskFocusBottomSheet : BottomSheetDialogFragment() {
    private lateinit var component: HabitsApplicationComponent
    private lateinit var timer: TimerCardView
    private var pendingStart: (() -> Unit)? = null
    private var launchConsumed = false
    private var awaitingPermission = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingStart
        pendingStart = null
        val wasAwaiting = awaitingPermission
        awaitingPermission = false
        if (isCurrentDataset()) {
            if (action != null) action.invoke()
            else if (wasAwaiting && !component.timerSessionManager.snapshot().hasActiveSession) {
                val id = TaskId(requireArguments().getString("taskId")!!)
                if (!launchConsumed && requireArguments().getBoolean("autoStart")) component.timerSessionManager.startTask(id)
                else component.timerSessionManager.toggleTask(id, requireArguments().getString("title") ?: "")
            }
            if (wasAwaiting) launchConsumed = true
        }
        if (!granted && isAdded) Toast.makeText(requireContext(), R.string.timer_notification_permission_denied, Toast.LENGTH_LONG).show()
    }

    private fun isCurrentDataset() = isAdded && ::component.isInitialized &&
        (requireContext().applicationContext as HabitsApplication).component === component &&
        arguments?.getString("datasetId") == component.session.datasetId

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        component = (requireContext().applicationContext as HabitsApplication).component
        launchConsumed = state?.getBoolean("launchConsumed") ?: false
        awaitingPermission = state?.getBoolean("awaitingPermission") ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val palette = MainTabsThemeBridge.resolve(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
        }
        if (!component.session.isNextArchitecture || !isCurrentDataset()) return root
        val id = TaskId(requireArguments().getString("taskId")!!)
        val title = requireArguments().getString("title") ?: ""
        root.addView(TextView(requireContext()).apply {
            text = title
            textSize = 20f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.onSurface)
            val pad = TaskThemeUtils.dpInt(context, 16f)
            setPadding(pad, pad, pad, 0)
        })
        timer = TimerCardView(requireContext()).apply {
            setTask(id, title, component.timerSessionManager)
            setColor(palette.accent)
            setNotificationPermissionRequester(::ensurePermission)
            setAlertHealth(component.pomodoroCompletionNotifier::health, ::openAlertSettings)
        }
        root.addView(timer)
        // The shared duration wheel can exceed a landscape sheet's available height.
        return androidx.core.widget.NestedScrollView(requireContext()).apply { addView(root) }
    }

    override fun onStart() {
        super.onStart()
        if (!component.session.isNextArchitecture || !isCurrentDataset()) { dismiss(); return }
        (dialog as? BottomSheetDialog)?.let { sheet ->
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                GradientDrawable().apply {
                    setColor(MainTabsThemeBridge.resolve(requireContext()).surface)
                    val radius = TaskThemeUtils.dp(requireContext(), 24f)
                    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                }
        }
        if (!launchConsumed && !awaitingPermission && requireArguments().getBoolean("autoStart")) {
            val manager = component.timerSessionManager
            // Opening an already active session never pauses it or changes its mode.
            if (!manager.snapshot().hasActiveSession) ensurePermission {
                if (isCurrentDataset()) {
                    manager.startTask(TaskId(requireArguments().getString("taskId")!!))
                    launchConsumed = true
                }
            }
            else launchConsumed = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isCurrentDataset() || !component.session.isNextArchitecture) dismissAllowingStateLoss()
        else if (::timer.isInitialized) timer.refreshAlertHealth()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("launchConsumed", launchConsumed)
        outState.putBoolean("awaitingPermission", awaitingPermission)
    }

    private fun ensurePermission(action: () -> Unit) {
        if (!isCurrentDataset()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = action
            awaitingPermission = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }

    private fun openAlertSettings(issue: PomodoroAlertIssue) {
        if (!isCurrentDataset()) return
        val intent = when (issue) {
            PomodoroAlertIssue.NOTIFICATIONS_DISABLED -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            PomodoroAlertIssue.CHANNEL_DISABLED, PomodoroAlertIssue.CHANNEL_SILENT -> {
                component.pomodoroCompletionNotifier.ensureChannel()
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, PomodoroCompletionNotifier.CHANNEL_ID)
            }
            PomodoroAlertIssue.EXACT_ALARMS_DISABLED -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${requireContext().packageName}"))
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val TAG = "taskFocusTimer"
        fun newInstance(id: TaskId, title: String, autoStart: Boolean = true) = TaskFocusBottomSheet().apply {
            arguments = Bundle().apply {
                putString("taskId", id.value)
                putString("title", title)
                putBoolean("autoStart", autoStart)
                putString("datasetId", HabitsApplication.component.session.datasetId)
            }
        }
    }
}
