package org.isoron.uhabits.intents

import android.content.Intent
import android.net.Uri
import org.isoron.uhabits.core.containers.session.DatasetMode
import org.isoron.uhabits.core.containers.session.DatasetSession
import org.isoron.uhabits.core.models.Habit

/** Dataset and immutable Habit identity for widget actions that may outlive their component. */
object WidgetIntentIdentity {
    const val DATASET = "widget.dataset"
    const val HABIT_UUID = "widget.habit.uuid"

    fun bind(intent: Intent, session: DatasetSession, habit: Habit): Intent = intent.apply {
        putExtra(DATASET, session.datasetId)
        putExtra(HABIT_UUID, habit.uuid)
        val base = intent.data ?: Uri.parse(habit.uriString)
        data = base.buildUpon()
            .clearQuery()
            .appendQueryParameter(DATASET, session.datasetId)
            .appendQueryParameter(HABIT_UUID, habit.uuid)
            .build()
    }

    fun accepts(intent: Intent, session: DatasetSession, habit: Habit): Boolean = accepts(
        intent.getStringExtra(DATASET) ?: intent.data?.getQueryParameter(DATASET),
        intent.getStringExtra(HABIT_UUID) ?: intent.data?.getQueryParameter(HABIT_UUID),
        session,
        habit.uuid
    )

    internal fun accepts(dataset: String?, uuid: String?, session: DatasetSession, currentUuid: String?): Boolean {
        if (!session.capabilities.widgetsEnabled) return false
        if (dataset == null && uuid == null) return session.mode == DatasetMode.LEGACY_PRODUCTION
        return dataset == session.datasetId && uuid != null && uuid == currentUuid
    }
}
