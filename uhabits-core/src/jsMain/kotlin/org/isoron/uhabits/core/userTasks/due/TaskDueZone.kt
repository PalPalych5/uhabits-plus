/* Copyright (C) 2026 Alinson Santos Xavier and contributors. GPL-3.0-or-later. */
package org.isoron.uhabits.core.userTasks.due

internal actual fun isKnownTaskDueZone(zoneId: String): Boolean = try {
    js("new Intl.DateTimeFormat('en-US', {timeZone: zoneId})")
    true
} catch (_: Throwable) {
    false
}
