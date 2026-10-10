package org.isoron.uhabits.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.containers.HabitRef
import org.isoron.uhabits.core.models.projection.EffectiveHabitReadSnapshot
import org.isoron.uhabits.core.models.projection.EffectiveReadInvalidatedException
import org.isoron.uhabits.inject.HabitsApplicationComponent
import java.util.concurrent.Executors

/** One dataset-owned, coalesced render batch. No Activity or persisted projection is required. */
class WidgetRenderCoordinator(
    private val context: Context,
    private val component: HabitsApplicationComponent
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val readLock = Any()
    private val pending = linkedMapOf<Class<out BaseWidgetProvider>, MutableSet<Int>>()
    @Volatile private var disposed = false
    @Volatile private var generation = 0L
    @Volatile internal var lastPublishedRevision = -1L
        private set
    @Volatile internal var lastBatchSnapshots: Map<HabitRef, EffectiveHabitReadSnapshot> = emptyMap()
        private set
    private val dispatch = Runnable {
        if (!disposed) {
            val requests = pending.mapValues { it.value.toIntArray() }
            pending.clear()
            val token = generation
            executor.execute { synchronized(readLock) { render(requests, token) } }
        }
    }

    fun request(provider: Class<out BaseWidgetProvider>, ids: IntArray) {
        handler.post {
            if (!disposed) {
                pending.getOrPut(provider) { linkedSetOf() }.addAll(ids.toList())
                generation++
                handler.removeCallbacks(dispatch)
                handler.postDelayed(dispatch, 50)
            }
        }
    }

    fun requestAll() {
        val manager = AppWidgetManager.getInstance(context)
        PROVIDERS.forEach { request(it, manager.getAppWidgetIds(ComponentName(context, it))) }
    }

    fun shutdown() {
        disposed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        synchronized(readLock) { /* Drain the captured dataset's SQL readers before DB closure. */ }
    }

    private fun current(token: Long) = !disposed && token == generation &&
        HabitsApplication.component === component

    private fun render(requests: Map<Class<out BaseWidgetProvider>, IntArray>, token: Long) {
        if (!current(token)) return
        if (Looper.myLooper() == null) Looper.prepare()
        val manager = AppWidgetManager.getInstance(context)
        val ids = requests.values.flatMap { it.toList() }.distinct()
        if (ids.isEmpty()) return
        val selected = ids.associateWith { component.widgetPreferences.getHabitIdsFromWidgetId(it).toList() }
        val habits = selected.values.flatten().distinct()
            .mapNotNull { component.habitList.getById(it) }
        val revision = component.effectiveReadRevision.value
        val from = habits.mapNotNull { it.effectiveStatisticsStartDate() }.minOrNull() ?: getToday().minus(365)
        val snapshots = try {
            if (component.session.capabilities.widgetsEnabled)
                component.effectiveHabitReads?.snapshots(habits, from, getToday()).orEmpty()
            else emptyMap()
        } catch (_: EffectiveReadInvalidatedException) {
            // A committed revision/calendar callback has already requested the next generation.
            return
        }
        if (!current(token)) return
        context.setTheme(R.style.WidgetTheme)
        requests.forEach { (provider, widgetIds) ->
            widgetIds.forEach { id ->
                if (!current(token)) return
                val habitIdsForWidget = selected.getValue(id)
                val selectedHabits = habitIdsForWidget.mapNotNull { habitId -> habits.find { it.id == habitId } }
                val valid = component.session.capabilities.widgetsEnabled && selectedHabits.isNotEmpty() &&
                    selectedHabits.size == habitIdsForWidget.size && selectedHabits.none { it.isArchived }
                if (!valid) {
                    val views = RemoteViews(context.packageName, R.layout.widget_error)
                    views.setTextViewText(R.id.label, context.getString(R.string.habit_not_found))
                    manager.updateAppWidget(id, views)
                } else {
                    try {
                        val type = typeFor(provider)
                        val widget = if (selectedHabits.size > 1) {
                            StackWidget(context, id, type, selectedHabits)
                        } else createWidget(context, id, type, selectedHabits.single(), false).apply {
                            effectiveSnapshot = selectedHabits.single().uuid?.let { snapshots[HabitRef(it)] }
                        }
                        val options = manager.getAppWidgetOptions(id)
                        widget.setDimensions(provider.getDeclaredConstructor().newInstance().getDimensionsFromOptions(context, options))
                        // Dataset/revision checks precede publishing obsolete RemoteViews.
                        val landscape = widget.landscapeRemoteViews
                        val portrait = widget.portraitRemoteViews
                        if (current(token) && revision == component.effectiveReadRevision.value) {
                            manager.updateAppWidget(id, RemoteViews(landscape, portrait))
                            if (selectedHabits.size > 1) {
                                manager.notifyAppWidgetViewDataChanged(
                                    id,
                                    StackWidgetType.getStackWidgetAdapterViewId(type)
                                )
                            }
                        }
                    } catch (failure: RuntimeException) {
                        if (current(token)) {
                            android.util.Log.e("WidgetRender", "Widget $id unavailable", failure)
                            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_error))
                        }
                    }
                }
            }
        }
        if (current(token) && revision == component.effectiveReadRevision.value) {
            lastBatchSnapshots = snapshots
            lastPublishedRevision = revision
        }
    }

    companion object {
        val PROVIDERS = listOf(CheckmarkWidgetProvider::class.java, TargetWidgetProvider::class.java,
            HistoryWidgetProvider::class.java, ScoreWidgetProvider::class.java,
            StreakWidgetProvider::class.java, FrequencyWidgetProvider::class.java)
        fun typeFor(provider: Class<out BaseWidgetProvider>) = when (provider) {
            CheckmarkWidgetProvider::class.java -> StackWidgetType.CHECKMARK
            TargetWidgetProvider::class.java -> StackWidgetType.TARGET
            HistoryWidgetProvider::class.java -> StackWidgetType.HISTORY
            ScoreWidgetProvider::class.java -> StackWidgetType.SCORE
            StreakWidgetProvider::class.java -> StackWidgetType.STREAKS
            FrequencyWidgetProvider::class.java -> StackWidgetType.FREQUENCY
            else -> error("Unknown widget provider")
        }
        fun createWidget(context: Context, id: Int, type: StackWidgetType,
            habit: org.isoron.uhabits.core.models.Habit, stacked: Boolean): BaseWidget = when (type) {
            StackWidgetType.CHECKMARK -> CheckmarkWidget(context, id, habit, stacked)
            StackWidgetType.TARGET -> TargetWidget(context, id, habit, stacked)
            StackWidgetType.HISTORY -> HistoryWidget(context, id, habit, stacked)
            StackWidgetType.SCORE -> ScoreWidget(context, id, habit, stacked)
            StackWidgetType.STREAKS -> StreakWidget(context, id, habit, stacked)
            StackWidgetType.FREQUENCY -> FrequencyWidget(context, id, habit,
                HabitsApplication.component.preferences.firstWeekday, stacked)
        }
    }
}
