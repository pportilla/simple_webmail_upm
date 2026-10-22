package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchQuery
import java.time.ZoneId

/*
 * Local search over the full-text tables `messages_fts` (subject, sender, recipients, body) and
 * `header_index_fts` (subject, sender, recipients): what their columns hold, and a [SearchQuery]
 * as SQL conditions on them.
 */

/** Text of the people columns, folded like every full-text column ([SearchText.fold]). */
internal object FtsPeople {
    /** The sender column: name and address of the From header. */
    fun sender(from: EmailAddress?): String = SearchText.fold(AddressCodec.people(listOfNotNull(from)))

    /**
     * The recipients column: names and addresses of To and Cc. Never Bcc, known for our own mail
     * only: `to:` and words would then find a message while it is cached and miss it once it is
     * only in the header index or on the server, which search To and Cc.
     */
    fun recipients(vararg lists: List<EmailAddress>): String = SearchText.fold(AddressCodec.people(*lists))

    /** The words of a stored address list (`from_addr`, `to_addr`...), even one [AddressCodec.decode] rejects. */
    fun ofStored(vararg values: String?): String =
        SearchText.fold(values.filterNot { it.isNullOrBlank() }.joinToString(" "))

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /**
     * Where the sender ends in the "people" text of a version 2 index row: that text is
     * [AddressCodec.people] of the sender followed by the recipients, folded, so its first words
     * are [senderWords], the words of the row's `from_addr`. Returns the index right after them
     * (the whole text when it was cut inside the sender), or -1 when they do not line up (never
     * seen).
     */
    fun senderEnd(people: String, senderWords: List<String>): Int {
        var end = 0
        var match = WORD.find(people)
        for (word in senderWords) {
            // The text was cut ([HeaderIndexDao] caps it) inside the sender: no recipients left.
            if (match == null) return people.length
            if (match.value != word) return -1
            end = match.range.last + 1
            match = match.next()
        }
        return end
    }

    /**
     * [senderEnd] as texts: the sender and the recipients of a version 2 "people" text. When the
     * words do not line up, the sender is taken from [fromAddr] and the whole text is kept as the
     * recipients: `to:` may then match the sender of that row, but never misses a recipient.
     */
    fun split(people: String, fromAddr: String): Pair<String, String> {
        val end = senderEnd(people, SearchText.words(fromAddr))
        if (end < 0) return ofStored(fromAddr) to people.trim()
        return people.substring(0, end).trim() to people.substring(end).trim()
    }
}

/** A full-text condition: `<column> MATCH <expression>`, over every column when [column] is null. */
internal data class FtsMatch(val column: String?, val expression: String)

/**
 * A [SearchQuery], combined with the flag chips of [SearchFilters], as conditions on a full-text
 * table and its row table (aliased; columns `id`, `date`, `is_read`, `is_starred`,
 * `has_attachments`):
 * - words: each a phrase ([SearchText.phrase]) somewhere in the row (any column);
 * - subject: every phrase in the subject column;
 * - from / to: any of the phrases in the sender / recipients column;
 * - excluded: none of the phrases anywhere;
 * - after / before: the row's date, whole days of [ZoneId], both inclusive;
 * - unread, starred, attachments: the row's flags (a chip or the operator turns one on).
 *
 * The last word of a phrase is a prefix when it has at least `minPrefix` letters; shorter ones
 * match whole words. Excluded words are prefixes only from [HeaderIndexDao.MIN_PREFIX] letters, in
 * every table: "-r" typed on the way to "-revision" must not hide everything with an r-word.
 *
 * FTS4 limits a single word to a column ("sender:ana") but never a phrase, so each criterion on
 * a column is a MATCH on that column ("sender MATCH ...") in a subquery of its own, and the
 * subqueries are intersected ([candidates]).
 */
internal class FtsQuery private constructor(
    /** Conditions every result matches; empty when only dates, flags or exclusions are asked for. */
    val required: List<FtsMatch>,
    /** Rows matching this are left out. */
    val excluded: FtsMatch?,
    /** Epoch millis, inclusive. */
    private val since: Long?,
    /** Epoch millis, exclusive. */
    private val until: Long?,
    private val unreadOnly: Boolean,
    private val starredOnly: Boolean,
    private val withAttachments: Boolean,
) {
    /**
     * The `docid`s of [table] matching every [required] condition, as SQL with its arguments;
     * null when there is none.
     */
    fun candidates(table: String): Pair<String, List<Any?>>? {
        if (required.isEmpty()) return null
        val sql = required.joinToString(" INTERSECT ") { "SELECT docid FROM $table WHERE ${it.column ?: table} MATCH ?" }
        return sql to required.map { it.expression }
    }

    /** Appends to [where] (and [args]) the conditions other than [required] on the rows of [alias]. */
    fun appendFilters(where: StringBuilder, args: MutableList<Any?>, alias: String, table: String) {
        excluded?.let {
            where.append(" AND $alias.id NOT IN (SELECT docid FROM $table WHERE $table MATCH ?)")
            args += it.expression
        }
        since?.let {
            where.append(" AND $alias.date >= ?")
            args += it
        }
        until?.let {
            where.append(" AND $alias.date < ?")
            args += it
        }
        if (unreadOnly) where.append(" AND $alias.is_read = 0")
        if (starredOnly) where.append(" AND $alias.is_starred = 1")
        if (withAttachments) where.append(" AND $alias.has_attachments = 1")
    }

    companion object {
        /**
         * [query] with the chips of [filters]; null when the query itself asks for nothing that can
         * be searched (no criterion, or only words without letters or digits): no results then,
         * rather than every message.
         */
        fun of(query: SearchQuery, filters: SearchFilters, minPrefix: Int, zone: ZoneId): FtsQuery? {
            /** The phrases of [values] joined by [operator] (" " is AND), on [column]; null without any. */
            fun match(column: String?, values: List<String>, operator: String, prefixFrom: Int = minPrefix): FtsMatch? =
                values.mapNotNull { phraseOf(it, prefixFrom) }.takeIf { it.isNotEmpty() }?.let { FtsMatch(column, it.joinToString(operator)) }
            val required = listOfNotNull(
                match(null, query.words, " "),
                match(SUBJECT, query.subject, " "),
                match(SENDER, query.from, " OR "),
                match(RECIPIENTS, query.to, " OR "),
            )
            val excluded = match(null, query.excluded, " OR ", prefixFrom = HeaderIndexDao.MIN_PREFIX)
            val since = query.after?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            val until = query.before?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            val searchable = required.isNotEmpty() || excluded != null || since != null || until != null ||
                query.unread || query.starred || query.hasAttachment
            if (!searchable) return null
            return FtsQuery(
                required = required,
                excluded = excluded,
                since = since,
                until = until,
                unreadOnly = query.unread || filters.unreadOnly,
                starredOnly = query.starred || filters.starredOnly,
                withAttachments = query.hasAttachment || filters.withAttachments,
            )
        }

        /**
         * Whether the header index finds every message the server's header search would find for
         * [value] (a word or a person or subject value): the server matches substrings, so a
         * value whose last word is matched whole (shorter than [minPrefix]) may miss longer words
         * ("cv" in "CVs"). A whole mail address is the exception: its last word is the top-level
         * domain, complete as typed.
         */
        fun matchesLikeServer(value: String, minPrefix: Int): Boolean {
            val words = SearchText.words(value)
            if (words.isEmpty() || words.size > SearchText.MAX_TERMS) return false
            return words.last().length >= minPrefix || (words.size > 1 && AddressParser.isValidAddress(value.trim()))
        }

        /** [SearchText.phrase] of [value], its last word a prefix from [minPrefix] letters on. */
        private fun phraseOf(value: String, minPrefix: Int): String? =
            SearchText.phrase(value, prefixLast = (SearchText.words(value).lastOrNull()?.length ?: 0) >= minPrefix)

        const val SUBJECT = "subject"
        const val SENDER = "sender"
        const val RECIPIENTS = "recipients"
    }
}
