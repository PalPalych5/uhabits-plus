/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due

import org.isoron.uhabits.core.userTasks.parseTaskPlannedDate
import org.isoron.uhabits.core.userTasks.toIsoDateString

enum class LegacyPlanEncoding { UNKNOWN, KNOWN_SYNC_UTC_EPOCH_DAY_MILLIS }

data class LegacyTaskDueSnapshot(
    val taskId: String,
    val rawPlannedDate: String?,
    val deadlineMillis: Long?,
    val updatedAt: Long,
    val sourceFormat: String,
    val sourceRevision: String? = null,
    val sourceOperationId: String? = null,
    val numericPlanEncoding: LegacyPlanEncoding = LegacyPlanEncoding.UNKNOWN,
)

enum class LegacyTaskDueReview {
    PLAN_REQUIRES_REVIEW, INVALID_PLAN, LEGACY_TIME_EXPLICITNESS_AND_ZONE_UNKNOWN, LEGACY_CHANGED,
}

data class LegacyTaskDuePlan(
    val due: TaskDue,
    val source: LegacyTaskDueSnapshot,
    val proposedDay: TaskDueDate? = null,
    val review: Set<LegacyTaskDueReview> = emptySet(),
    val pendingSources: Set<LegacyTaskDueSnapshot> = emptySet(),
    val version: Int = 1,
)

/** Pure preview only. Persisting and activating a conversion requires the separate compatibility gate. */
object LegacyTaskDuePlanner {
    fun plan(source: LegacyTaskDueSnapshot, existing: LegacyTaskDuePlan? = null): LegacyTaskDuePlan {
        require(source.taskId.isNotBlank() && source.sourceFormat.isNotBlank())
        require(source.sourceFormat in setOf("legacy-sqlite-v29", "legacy-sqlite-v30")) {
            "Unsupported legacy task source format"
        }
        if (existing != null) {
            require(existing.version == 1 && existing.source.taskId == source.taskId)
            return if (existing.source == source || source in existing.pendingSources) existing
            else existing.copy(
                review = existing.review + LegacyTaskDueReview.LEGACY_CHANGED,
                pendingSources = existing.pendingSources + source,
            )
        }
        val review = mutableSetOf<LegacyTaskDueReview>()
        val proposedDay = source.rawPlannedDate?.let { raw ->
            try {
                if (source.numericPlanEncoding == LegacyPlanEncoding.KNOWN_SYNC_UTC_EPOCH_DAY_MILLIS &&
                    Regex("-?[0-9]+").matches(raw)
                ) {
                    TaskDueDate.parse(requireNotNull(parseTaskPlannedDate(raw)).toIsoDateString())
                } else TaskDueDate.parse(raw)
            } catch (_: IllegalArgumentException) {
                review.add(LegacyTaskDueReview.INVALID_PLAN)
                null
            }
        }
        if (proposedDay != null) review.add(LegacyTaskDueReview.PLAN_REQUIRES_REVIEW)
        val due = source.deadlineMillis?.let {
            review.add(LegacyTaskDueReview.LEGACY_TIME_EXPLICITNESS_AND_ZONE_UNKNOWN)
            TaskDue.Timed.legacy(it)
        } ?: TaskDue.None
        return LegacyTaskDuePlan(due, source, proposedDay, review.toSet())
    }
}
