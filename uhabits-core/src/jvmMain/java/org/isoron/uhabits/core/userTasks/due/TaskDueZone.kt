/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due

import java.time.DateTimeException
import java.time.ZoneId

internal actual fun isKnownTaskDueZone(zoneId: String): Boolean = try {
    ZoneId.of(zoneId)
    true
} catch (_: DateTimeException) {
    false
}
