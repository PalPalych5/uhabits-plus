package org.isoron.uhabits.core.userTasks.contract

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** PR3 proposal oracle only. No production model, storage or migration uses this code. */
internal sealed class ProposedDue {
    object None : ProposedDue()
    data class Day(val date: LocalDate) : ProposedDue()
    data class Timed(val instant: Instant, val originZoneId: String?) : ProposedDue()
}

internal data class LegacyDates(val rawPlan: String?, val deadlineMillis: Long?)
internal data class ProposedConversion(
    val due: ProposedDue,
    val original: LegacyDates,
    val review: Set<String>,
    val pendingLegacy: Set<LegacyDates> = emptySet(),
)

internal object ProposedTaskDueContract {
    fun strictDay(raw: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw))
        return LocalDate.parse(raw)
    }

    fun timed(local: LocalDateTime, zoneName: String, offset: ZoneOffset?): ProposedDue.Timed {
        val zone = ZoneId.of(zoneName)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "DST gap" }
        require(offset != null || offsets.size == 1) { "DST overlap requires an offset" }
        val selected = offset ?: offsets.single()
        require(selected in offsets) { "Invalid offset" }
        return ProposedDue.Timed(local.toInstant(selected), zone.id)
    }

    fun convert(raw: LegacyDates, existing: ProposedConversion? = null): ProposedConversion {
        if (existing != null) {
            return if (existing.original == raw) existing
            else existing.copy(review = existing.review + "LEGACY_CHANGED", pendingLegacy = existing.pendingLegacy + raw)
        }
        val review = mutableSetOf<String>()
        val plan = raw.rawPlan?.let {
            try { strictDay(it) } catch (_: RuntimeException) {
                review.add("INVALID_PLAN")
                null
            }
        }
        if (plan != null) review.add("PLAN_REQUIRES_REVIEW")
        val due = raw.deadlineMillis?.let {
            review.add("LEGACY_TIME_EXPLICITNESS_AND_ZONE_UNKNOWN")
            ProposedDue.Timed(Instant.ofEpochMilli(it), null)
        } ?: ProposedDue.None
        return ProposedConversion(due, raw, review.toSet())
    }

    fun date(due: ProposedDue, viewingZone: ZoneId): LocalDate? = when (due) {
        ProposedDue.None -> null
        is ProposedDue.Day -> due.date
        is ProposedDue.Timed -> due.instant.atZone(viewingZone).toLocalDate()
    }

    fun overdue(due: ProposedDue, completed: Boolean, now: Instant, zone: ZoneId): Boolean =
        !completed && when (due) {
            ProposedDue.None -> false
            is ProposedDue.Day -> due.date < now.atZone(zone).toLocalDate()
            is ProposedDue.Timed -> due.instant < now
        }

    // Overdue rows have precedence, making the two visible sections disjoint.
    fun today(due: ProposedDue, completed: Boolean, now: Instant, zone: ZoneId): Boolean =
        !completed && date(due, zone) == now.atZone(zone).toLocalDate() && !overdue(due, false, now, zone)
}

/** Versioned field-map envelope proposal, not the current backup or sync wire format. */
internal object ProposedDueEnvelope {
    fun encode(due: ProposedDue): Map<String, String> = when (due) {
        ProposedDue.None -> mapOf("version" to "1", "kind" to "NONE")
        is ProposedDue.Day -> mapOf("version" to "1", "kind" to "DAY", "date" to due.date.toString())
        is ProposedDue.Timed -> mapOf("version" to "1", "kind" to "TIMED", "instant" to due.instant.toString()) +
            (due.originZoneId?.let { mapOf("originZone" to it) } ?: emptyMap())
    }

    fun decode(fields: Map<String, String>): ProposedDue {
        require(fields["version"] == "1")
        val allowed = when (fields["kind"]) {
            "NONE" -> setOf("version", "kind")
            "DAY" -> setOf("version", "kind", "date")
            "TIMED" -> setOf("version", "kind", "instant", "originZone")
            else -> error("Unknown or missing kind")
        }
        require(fields.keys.all { it in allowed })
        return when (fields.getValue("kind")) {
            "NONE" -> ProposedDue.None
            "DAY" -> ProposedDue.Day(ProposedTaskDueContract.strictDay(fields.getValue("date")))
            else -> ProposedDue.Timed(Instant.parse(fields.getValue("instant")), fields["originZone"]?.also { ZoneId.of(it) })
        }
    }

    // Missing field is unchanged. Present NONE is an explicit clear.
    fun patch(existing: ProposedDue, field: Map<String, String>?): ProposedDue = field?.let(::decode) ?: existing
}
