package io.github.pportilla.upmmail.data

import android.content.Context
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.ComposeTexts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Reply attribution and forward header in the app's current language. */
class AndroidComposeTexts(private val context: Context) : ComposeTexts {

    override fun replyAttribution(date: Long, sender: String): String =
        context.getString(R.string.notif_quote_attribution, formatDate(date), sender)

    override fun forwardHeader(from: String, date: Long, subject: String, to: String, cc: String): String =
        buildList {
            add(context.getString(R.string.notif_quote_forward_separator))
            add(context.getString(R.string.notif_quote_forward_from, from))
            add(context.getString(R.string.notif_quote_forward_date, formatDate(date)))
            add(context.getString(R.string.notif_quote_forward_subject, subject))
            if (to.isNotBlank()) add(context.getString(R.string.notif_quote_forward_to, to))
            if (cc.isNotBlank()) add(context.getString(R.string.notif_quote_forward_cc, cc))
        }.joinToString("\n")

    private fun formatDate(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }
}
