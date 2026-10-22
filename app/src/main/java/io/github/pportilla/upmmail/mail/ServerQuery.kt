package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.SearchQuery
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.NotTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SentDateTerm
import jakarta.mail.search.SubjectTerm
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** A part of a message that a server search looks in. */
enum class SearchField { SUBJECT, FROM, TO, CC, BODY }

/**
 * What the user typed ([SearchQuery]), as an IMAP search. Every criterion must hold:
 * - each of [words] in any of its spelling variants and in any of [fields];
 * - [from]: any of the values in From; [to]: any of them in To or Cc;
 * - each of [subject] in the Subject;
 * - none of [excluded] in Subject, From, To, Cc or the text, whatever [fields] are: `-word` means
 *   the word is nowhere in the message (unless [excludeInText] is off, see [headerExclusionsOnly]);
 * - the date on or after [since] and on or before [until], whole days of the device's time zone
 *   ([datesMatch]). The server compares the calendar date written in the Date header, in the
 *   sender's zone, so it is asked for one more day on each side (`SENTSINCE` the day before
 *   [since], `SENTBEFORE` two days after [until]) and the caller keeps the matches whose date
 *   passes [datesMatch];
 * - unread ([unseen], `UNSEEN`) and starred ([flagged], `FLAGGED`).
 *
 * Every value is a list of spelling variants, the typed spelling in lower case first. IMAP matches
 * substrings, so a word also finds longer words that contain it, and a person value finds the
 * addresses and names that contain it ("garcia@", "Ana"). [hasAttachment] has no IMAP search key:
 * the caller keeps the matches whose BODYSTRUCTURE shows an attachment.
 */
data class ServerQuery(
    /** One entry per word that must match: its variants. */
    val words: List<List<String>>,
    /** Where [words] are looked for. */
    val fields: Set<SearchField>,
    val from: List<List<String>> = emptyList(),
    val to: List<List<String>> = emptyList(),
    val subject: List<List<String>> = emptyList(),
    val excluded: List<List<String>> = emptyList(),
    val since: LocalDate? = null,
    /** Inclusive. */
    val until: LocalDate? = null,
    val unseen: Boolean = false,
    val flagged: Boolean = false,
    val hasAttachment: Boolean = false,
    /** [excluded] are looked for in the message text too; off only for [headerExclusionsOnly]. */
    val excludeInText: Boolean = true,
) {
    init {
        require(fields.isNotEmpty()) { "A query needs at least one field" }
        require(listOf(words, from, to, subject, excluded).all { values -> values.all { it.isNotEmpty() } }) {
            "Every value needs a spelling"
        }
        require(hasImapCriteria || hasAttachment) { "A query needs at least one criterion" }
    }

    /**
     * True when the query has words. Only then can searching the message text add matches:
     * without words, the headers answer every criterion, and the text can only rule out matches of
     * an exclusion, which every search checks in the text anyway.
     */
    val hasWords: Boolean get() = words.isNotEmpty()

    /** True when there is something to send in an IMAP SEARCH; false for an attachment filter alone. */
    val hasImapCriteria: Boolean
        get() = words.isNotEmpty() || from.isNotEmpty() || to.isNotEmpty() || subject.isNotEmpty() ||
            excluded.isNotEmpty() || since != null || until != null || unseen || flagged

    fun withFields(fields: Set<SearchField>): ServerQuery = copy(fields = fields)

    /**
     * The query with [excluded] looked for in the headers only, as the header index checks them.
     * A message this matches and the query itself does not has an excluded word in its text: the
     * device cannot tell that for a message it knows only by its headers
     * ([io.github.pportilla.upmmail.sync.ServerSearch] uses it to rule such messages out).
     */
    fun headerExclusionsOnly(): ServerQuery = copy(excludeInText = false)

    /**
     * Whether a message dated [date] (epoch millis: the Date header, else the server's internal
     * date, as stored) is within [since]..[until], whole days of [zone], both included: the bounds
     * of the local search ([io.github.pportilla.upmmail.data.db.FtsQuery]).
     */
    fun datesMatch(date: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (since != null && date < since.atStartOfDay(zone).toInstant().toEpochMilli()) return false
        if (until != null && date >= until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) return false
        return true
    }

    /**
     * The query reduced to its ASCII variants, for a server that refuses `CHARSET UTF-8`; null when
     * a value has no ASCII spelling ("año"): leaving it out would match far too much, or too little.
     */
    fun asciiOnly(): ServerQuery? {
        fun ascii(values: List<List<String>>): List<List<String>>? {
            val kept = values.map { variants -> variants.filter(::isAscii) }
            return if (kept.any { it.isEmpty() }) null else kept
        }
        return copy(
            words = ascii(words) ?: return null,
            from = ascii(from) ?: return null,
            to = ascii(to) ?: return null,
            subject = ascii(subject) ?: return null,
            excluded = ascii(excluded) ?: return null,
        )
    }

    /**
     * The criteria answered by the headers and flags alone (people, subject, dates, read and
     * starred), as a query of their own; null when there are none. A server answers it quickly,
     * so a window of messages where it matches nothing needs no search of the message text.
     */
    fun headerCriteria(): ServerQuery? {
        val any = from.isNotEmpty() || to.isNotEmpty() || subject.isNotEmpty() || since != null || until != null || unseen || flagged
        if (!any) return null
        return ServerQuery(
            words = emptyList(),
            fields = HEADER_FIELDS,
            from = from,
            to = to,
            subject = subject,
            since = since,
            until = until,
            unseen = unseen,
            flagged = flagged,
        )
    }

    /**
     * An AND of the criteria, cheapest first (flags, dates, people, subject, words, exclusions),
     * for servers that stop at the first one that fails. A word is an OR over its variants in
     * each of [fields]; a list of senders or recipients an OR over all of them. Single terms are not
     * wrapped. Null when there is no IMAP criterion (an attachment filter alone).
     */
    internal fun toSearchTerm(): SearchTerm? {
        val terms = ArrayList<SearchTerm>()
        if (unseen) terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
        if (flagged) terms += FlagTerm(Flags(Flags.Flag.FLAGGED), true)
        // A day more on each side (see the class): the Date header's own day may be the one
        // before or after the device's. SENTBEFORE is exclusive: two days after the last one.
        since?.let { terms += SentDateTerm(ComparisonTerm.GE, startOf(it.minusDays(1))) }
        until?.let { terms += SentDateTerm(ComparisonTerm.LT, startOf(it.plusDays(2))) }
        if (from.isNotEmpty()) terms += anyOf(from.flatten().map { termFor(SearchField.FROM, it) })
        if (to.isNotEmpty()) {
            terms += anyOf(to.flatten().flatMap { listOf(termFor(SearchField.TO, it), termFor(SearchField.CC, it)) })
        }
        subject.forEach { variants -> terms += anyOf(variants.map { termFor(SearchField.SUBJECT, it) }) }
        val wordFields = SearchField.entries.filter { it in fields }
        words.forEach { variants -> terms += anyOf(variants.flatMap { variant -> wordFields.map { termFor(it, variant) } }) }
        // Last in the AND: a server that stops at the first criterion that fails reads the text
        // only of the messages that match everything else.
        val excludedFields = SearchField.entries.filter { it in HEADER_FIELDS || (it == SearchField.BODY && excludeInText) }
        excluded.forEach { variants ->
            terms += NotTerm(anyOf(variants.flatMap { variant -> excludedFields.map { termFor(it, variant) } }))
        }
        return when (terms.size) {
            0 -> null
            1 -> terms[0]
            else -> AndTerm(terms.toTypedArray())
        }
    }

    companion object {
        val HEADER_FIELDS: Set<SearchField> = setOf(SearchField.SUBJECT, SearchField.FROM, SearchField.TO, SearchField.CC)
        val TEXT_FIELDS: Set<SearchField> = setOf(SearchField.BODY)

        /** More words (or values of one criterion) make every server command slower without narrowing much further. */
        const val MAX_WORDS = 5
        const val MAX_VARIANTS = 3

        /** A value is never shorter than this; single letters match nearly every message. */
        private const val MIN_WORD_LENGTH = 2

        private val WHITESPACE = Regex("\\s+")

        /** Stripped from both ends of a value; inside it (addresses, "e-mail", "v1.2") they stay. */
        private const val EDGE_PUNCTUATION = "\"'“”‘’«»()[]{}<>,;:!?¿¡."

        /**
         * The search box text [text] as a server query whose words are looked for in [fields], or
         * null if nothing is left to search for. The text is read by [SearchQuery.parse].
         */
        fun parse(text: String, fields: Set<SearchField> = HEADER_FIELDS): ServerQuery? {
            // Control characters cannot be sent in an IMAP string; they separate words.
            val cleaned = buildString(text.length) {
                for (ch in text) append(if (Character.isISOControl(ch)) ' ' else ch)
            }
            return of(SearchQuery.parse(cleaned), fields)
        }

        /**
         * [query] as a server query whose words are looked for in [fields], or null if nothing is
         * left to search for. Values are trimmed of punctuation at their ends; values shorter than
         * 2 characters and case-insensitive repeats are dropped. Of more than [MAX_WORDS] words
         * (or subject words) only the longest are kept, in the order typed; of more senders,
         * recipients or exclusions, the first ones.
         */
        fun of(query: SearchQuery, fields: Set<SearchField> = HEADER_FIELDS): ServerQuery? {
            if (fields.isEmpty()) return null
            val words = longest(clean(query.words)).map(::variants)
            val from = clean(query.from).take(MAX_WORDS).map(::personVariants)
            val to = clean(query.to).take(MAX_WORDS).map(::personVariants)
            val subject = longest(clean(query.subject)).map(::variants)
            val excluded = clean(query.excluded).take(MAX_WORDS).map(::variants)
            val anything = words.isNotEmpty() || from.isNotEmpty() || to.isNotEmpty() || subject.isNotEmpty() ||
                excluded.isNotEmpty() || query.after != null || query.before != null ||
                query.unread || query.starred || query.hasAttachment
            if (!anything) return null
            return ServerQuery(
                words = words,
                fields = fields,
                from = from,
                to = to,
                subject = subject,
                excluded = excluded,
                since = query.after,
                until = query.before,
                unseen = query.unread,
                flagged = query.starred,
                hasAttachment = query.hasAttachment,
            )
        }

        /**
         * Spellings of [word] to search for, at most [MAX_VARIANTS]: the word in lower case, the
         * word without accents (keeping ñ, so "año" never becomes "ano"), and for a word typed
         * without accents that ends in -cion/-sion, the accented ending ("revision" → "revisión").
         */
        internal fun variants(word: String): List<String> {
            val lower = word.lowercase(Locale.ROOT)
            val folded = fold(lower, keepEnye = true)
            val result = LinkedHashSet<String>()
            result += lower
            result += folded
            if (folded == lower && (folded.endsWith("cion") || folded.endsWith("sion"))) {
                result += folded.dropLast(2) + "ón"
            }
            return result.take(MAX_VARIANTS)
        }

        /**
         * Spellings of a sender or recipient: the value in lower case, without accents, and also
         * with ñ as n, as mail addresses are written ("Muñoz" → "munoz", found in "jmunoz@upm.es").
         */
        internal fun personVariants(value: String): List<String> {
            val lower = value.lowercase(Locale.ROOT)
            val result = LinkedHashSet<String>()
            result += lower
            result += fold(lower, keepEnye = true)
            result += fold(lower, keepEnye = false)
            return result.take(MAX_VARIANTS)
        }

        /** The values without control characters, repeated blanks and punctuation at their ends; short and repeated ones dropped. */
        private fun clean(values: List<String>): List<String> {
            val seen = HashSet<String>()
            val result = ArrayList<String>(values.size)
            for (value in values) {
                val noControl = buildString(value.length) {
                    for (ch in value) append(if (Character.isISOControl(ch)) ' ' else ch)
                }
                val cleaned = noControl.replace(WHITESPACE, " ").trim { it.isWhitespace() || it in EDGE_PUNCTUATION }
                if (cleaned.length < MIN_WORD_LENGTH) continue
                if (seen.add(cleaned.lowercase(Locale.ROOT))) result += cleaned
            }
            return result
        }

        /** The [MAX_WORDS] longest of [values], in their order. */
        private fun longest(values: List<String>): List<String> {
            if (values.size <= MAX_WORDS) return values
            val kept = values.indices.sortedByDescending { values[it].length }.take(MAX_WORDS).toSet()
            return values.filterIndexed { index, _ -> index in kept }
        }

        /** [text] without combining accents; the tilde of ñ/Ñ stays when [keepEnye]. */
        private fun fold(text: String, keepEnye: Boolean): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            val out = StringBuilder(decomposed.length)
            for (ch in decomposed) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) {
                    val enye = keepEnye && ch == '̃' && out.isNotEmpty() && (out.last() == 'n' || out.last() == 'N')
                    if (!enye) continue
                }
                out.append(ch)
            }
            return Normalizer.normalize(out, Normalizer.Form.NFC)
        }

        private fun isAscii(text: String): Boolean = text.all { it.code < 0x80 }

        /** The start of [day] in the device's time zone; IMAP compares whole days. */
        private fun startOf(day: LocalDate): Date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())

        private fun anyOf(terms: List<SearchTerm>): SearchTerm = if (terms.size == 1) terms[0] else OrTerm(terms.toTypedArray())

        private fun termFor(field: SearchField, value: String): SearchTerm = when (field) {
            SearchField.SUBJECT -> SubjectTerm(value)
            SearchField.FROM -> FromStringTerm(value)
            SearchField.TO -> RecipientStringTerm(Message.RecipientType.TO, value)
            SearchField.CC -> RecipientStringTerm(Message.RecipientType.CC, value)
            SearchField.BODY -> BodyTerm(value)
        }
    }
}
