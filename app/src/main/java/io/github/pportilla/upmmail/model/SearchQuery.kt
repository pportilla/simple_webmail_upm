package io.github.pportilla.upmmail.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A search typed in the search box, split into its criteria. The text form uses Gmail-like
 * operators, in English or Spanish, so advanced searches can also be typed by hand and are kept
 * in the recent searches as plain text:
 *
 * - `from:` / `de:` sender address or name
 * - `to:` / `para:` recipient (To or Cc) address or name
 * - `subject:` / `asunto:` words that must be in the subject
 * - `after:` / `desde:` and `before:` / `antes:` / `hasta:` dates (`2026-06-01` or `1/6/2026`)
 * - `has:attachment` / `tiene:adjunto`, `is:unread` / `es:noleido`, `is:starred` / `es:destacado`
 * - `-word` excludes messages containing the word; `"two words"` is a phrase
 *
 * Several values of the same person field match any of them (`from:ana from:carlos` finds mail
 * from either); everything else must match together. Unknown operators are ordinary words.
 */
data class SearchQuery(
    /** Words or "quoted phrases" that must appear somewhere (subject, people or text). */
    val words: List<String> = emptyList(),
    /** Sender address or name fragments; any of them may match. */
    val from: List<String> = emptyList(),
    /** Recipient (To or Cc) address or name fragments; any of them may match. */
    val to: List<String> = emptyList(),
    /** Words or phrases that must all appear in the subject. */
    val subject: List<String> = emptyList(),
    /** Words or phrases none of which may appear anywhere. */
    val excluded: List<String> = emptyList(),
    /** Messages dated on or after this day (local time zone). */
    val after: LocalDate? = null,
    /** Messages dated on or before this day (local time zone), inclusive. */
    val before: LocalDate? = null,
    val hasAttachment: Boolean = false,
    val unread: Boolean = false,
    val starred: Boolean = false,
) {
    /** True when nothing would be searched for. */
    val isEmpty: Boolean
        get() = words.isEmpty() && from.isEmpty() && to.isEmpty() && subject.isEmpty() && excluded.isEmpty() &&
            after == null && before == null && !hasAttachment && !unread && !starred

    /** True when any criterion other than plain words is set (the query is "advanced"). */
    val hasCriteria: Boolean
        get() = from.isNotEmpty() || to.isNotEmpty() || subject.isNotEmpty() || excluded.isNotEmpty() ||
            after != null || before != null || hasAttachment || unread || starred

    /**
     * True when matching never needs the message text: only people, subject, dates and flags.
     * Such a query is fully answered by headers (the local index and the server's header search).
     */
    val headersOnly: Boolean
        get() = words.isEmpty() && excluded.isEmpty()

    /** Canonical text form with English operators; [parse] of it gives back an equal query. */
    fun format(): String = buildList {
        from.forEach { add("from:" + quote(it)) }
        to.forEach { add("to:" + quote(it)) }
        subject.forEach { add("subject:" + quote(it)) }
        after?.let { add("after:$it") }
        before?.let { add("before:$it") }
        if (hasAttachment) add("has:attachment")
        if (unread) add("is:unread")
        if (starred) add("is:starred")
        words.forEach { add(quote(it)) }
        excluded.forEach { add("-" + quote(it)) }
    }.joinToString(" ")

    companion object {
        private val FROM = setOf("from", "de")
        private val TO = setOf("to", "para")
        private val SUBJECT = setOf("subject", "asunto")
        private val AFTER = setOf("after", "since", "desde")
        private val BEFORE = setOf("before", "until", "antes", "hasta")
        private val HAS = setOf("has", "tiene")
        private val IS = setOf("is", "es")
        private val ATTACHMENT = setOf("attachment", "attachments", "adjunto", "adjuntos")
        private val UNREAD = setOf("unread", "noleido", "noleído", "no-leido", "no-leído")
        private val STARRED = setOf("starred", "destacado", "destacados")

        /** Parses free text; never fails (anything not understood is a word). */
        fun parse(text: String): SearchQuery {
            var query = SearchQuery()
            for (token in tokenize(text)) {
                val raw = token.text
                if (token.quoted) {
                    query = query.copy(words = query.words + raw)
                    continue
                }
                if (raw.startsWith("-") && raw.length > 1) {
                    query = query.copy(excluded = query.excluded + raw.substring(1).trim('"'))
                    continue
                }
                val colon = raw.indexOf(':')
                if (colon <= 0) {
                    query = query.copy(words = query.words + raw)
                    continue
                }
                val key = raw.substring(0, colon).lowercase()
                val value = raw.substring(colon + 1).trim('"').trim()
                if (value.isEmpty()) {
                    // "from:" with nothing after it yet (the user is still typing): ignore.
                    if (key in FROM || key in TO || key in SUBJECT || key in AFTER || key in BEFORE ||
                        key in HAS || key in IS
                    ) {
                        continue
                    }
                    query = query.copy(words = query.words + raw)
                    continue
                }
                val lowerValue = value.lowercase()
                query = when {
                    key in FROM -> query.copy(from = query.from + value)
                    key in TO -> query.copy(to = query.to + value)
                    key in SUBJECT -> query.copy(subject = query.subject + value)
                    key in AFTER -> parseDate(value)?.let { query.copy(after = it) } ?: query.copy(words = query.words + raw)
                    key in BEFORE -> parseDate(value)?.let { query.copy(before = it) } ?: query.copy(words = query.words + raw)
                    key in HAS && lowerValue in ATTACHMENT -> query.copy(hasAttachment = true)
                    key in IS && lowerValue in UNREAD -> query.copy(unread = true)
                    key in IS && lowerValue in STARRED -> query.copy(starred = true)
                    // A mail address or URL typed as a word ("ana@upm.es", "https://...").
                    else -> query.copy(words = query.words + raw)
                }
            }
            return query
        }

        /** `2026-06-01`, `2026/06/01`, `1/6/2026`, `01-06-2026` or `1.6.2026` (day first, as in Spain). */
        fun parseDate(text: String): LocalDate? {
            val t = text.trim()
            try {
                if (Regex("""\d{4}-\d{1,2}-\d{1,2}""").matches(t) || Regex("""\d{4}/\d{1,2}/\d{1,2}""").matches(t)) {
                    val (y, m, d) = t.split('-', '/').map { it.toInt() }
                    return LocalDate.of(y, m, d)
                }
                val dmy = Regex("""(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2}|\d{4})""").matchEntire(t) ?: return null
                val (d, m, y) = dmy.destructured
                val year = y.toInt().let { if (y.length == 2) 2000 + it else it }
                return LocalDate.of(year, m.toInt(), d.toInt())
            } catch (_: DateTimeParseException) {
                return null
            } catch (_: java.time.DateTimeException) {
                return null
            } catch (_: NumberFormatException) {
                return null
            }
        }

        private fun quote(value: String): String =
            if (value.isEmpty() || value.any { it.isWhitespace() || it == '"' }) "\"" + value.replace("\"", "") + "\"" else value

        private data class Token(val text: String, val quoted: Boolean)

        /**
         * Splits on whitespace, keeping "quoted phrases" together, also after an operator
         * (`subject:"exam dates"`). An unbalanced quote runs to the end of the text.
         */
        private fun tokenize(text: String): List<Token> {
            val tokens = mutableListOf<Token>()
            val current = StringBuilder()
            var inQuotes = false
            var quotedWhole = false
            fun flush() {
                val value = current.toString().trim()
                if (value.isNotEmpty()) tokens += Token(value, quotedWhole)
                current.clear()
                quotedWhole = false
            }
            for (c in text) {
                when {
                    c == '"' -> {
                        if (!inQuotes && current.isEmpty()) quotedWhole = true
                        inQuotes = !inQuotes
                        if (!quotedWhole) current.append(c)
                    }
                    c.isWhitespace() && !inQuotes -> flush()
                    else -> current.append(c)
                }
            }
            flush()
            return tokens
        }
    }
}
