package io.github.pportilla.upmmail.ui.components

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Compact date for message lists: the time for today, "day month" for this year and a short
 * date for older messages, all in the user's locale.
 */
fun formatListDate(
    epochMillis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (epochMillis <= 0) return ""
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val formatter = when {
        date.toLocalDate() == today ->
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        date.year == today.year ->
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        else ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    }
    return formatter.withLocale(locale).format(date)
}

/** Full date and time for the message header, e.g. "21 oct 2026, 10:42". */
fun formatFullDate(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (epochMillis <= 0) return ""
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val datePart = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
    val timePart = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(date)
    return "$datePart, $timePart"
}

/** A day without time, e.g. "21 oct 2026" or "Oct 21, 2026". */
fun formatDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

/** True if [epochMillis] falls on the current day in [zone]. */
fun isToday(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

/** Human-readable file size, e.g. "1.2 MB". */
fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(locale, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}
