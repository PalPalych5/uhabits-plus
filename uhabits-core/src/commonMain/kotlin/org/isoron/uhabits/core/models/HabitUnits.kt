package org.isoron.uhabits.core.models

private val minuteUnits = setOf("min", "mins", "minute", "minutes", "мин", "минута", "минуты", "минут")

fun String.isMinuteUnit(): Boolean = trim().lowercase() in minuteUnits
