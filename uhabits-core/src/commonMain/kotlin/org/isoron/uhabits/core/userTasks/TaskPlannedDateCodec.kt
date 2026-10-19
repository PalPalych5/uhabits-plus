/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks

import org.isoron.platform.time.LocalDate

private const val EPOCH_DAY_MILLIS = 86400000L

/** Existing ISO dates plus the exact UTC epoch-day milliseconds emitted by older sync clients. */
fun parseTaskPlannedDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    // Preserve the established ISO parser's behavior; this is not a new date model.
    parseIsoDateString(raw)?.let { return it }
    val millis = requireNotNull(raw.toLongOrNull()) { "Unsupported planned_date encoding" }
    require(millis % EPOCH_DAY_MILLIS == 0L) { "planned_date is not UTC epoch-day milliseconds" }
    // Divide before subtracting to avoid Long overflow. Never use the device timezone.
    val days = millis / EPOCH_DAY_MILLIS - LocalDate(2000, 1, 1).unixTime / EPOCH_DAY_MILLIS
    // LocalDate's calendar conversion supports one backward 400-year cycle.
    // Reject unsupported values before narrowing or evaluating year/month/day.
    require(days in LocalDate(1600, 1, 1).daysSince2000.toLong()..LocalDate(9999, 12, 31).daysSince2000.toLong()) {
        "planned_date is outside the supported calendar range"
    }
    return LocalDate(days.toInt())
}

/** Repair only the known numeric encoding; do not rewrite historical, lenient ISO text. */
fun normalizeTaskPlannedDateForSync(raw: String?): String? {
    val date = parseTaskPlannedDate(raw) ?: return null
    return if (raw!!.toLongOrNull() != null) date.toIsoDateString() else raw
}
