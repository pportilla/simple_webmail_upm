package io.github.pportilla.upmmail.model

/** Supported mail-check preferences, including values saved by earlier app versions. */
object MailCheckIntervals {
    const val DEFAULT_MINUTES = 15

    /** Preserve manual mode; retire old 30/60-minute (and unsupported positive) values. */
    fun normalize(minutes: Int): Int = when {
        minutes <= 0 -> 0
        minutes == 5 || minutes == 10 || minutes == DEFAULT_MINUTES -> minutes
        else -> DEFAULT_MINUTES
    }

    fun backgroundMinutes(settings: AppSettings): Int =
        if (settings.pushEnabled) DEFAULT_MINUTES else normalize(settings.syncIntervalMinutes)

    /** Push keeps a short visible-mailbox fallback; manual mode has no repeating timer. */
    fun foregroundDelayMillis(settings: AppSettings): Long? = when {
        settings.pushEnabled -> 2 * 60_000L
        normalize(settings.syncIntervalMinutes) == 0 -> null
        else -> normalize(settings.syncIntervalMinutes) * 60_000L
    }
}
