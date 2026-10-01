package org.isoron.uhabits.core.models

private val minuteUnits = setOf(
    "min", "mins", "minute", "minutes", "min.",
    "мин", "минута", "минуты", "минут", "мин.",
    "m."
)

fun String.isMinuteUnit(): Boolean = trim().lowercase() in minuteUnits
