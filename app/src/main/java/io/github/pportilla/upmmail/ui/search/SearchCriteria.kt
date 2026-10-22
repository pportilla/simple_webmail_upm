package io.github.pportilla.upmmail.ui.search

import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchQuery
import java.time.LocalDate
import java.util.Locale

/*
 * The search box text and the controls that edit it: the criterion chips under the field, the
 * flag chips and the advanced search panel. The text, read by SearchQuery.parse, is the only
 * state: every chip is derived from it and every control rewrites it, so a criterion is never
 * shown twice and a chip never disagrees with the text. Pure (Android-free), unit-tested on the JVM.
 */

/** The person criteria of a query. */
enum class PersonField { FROM, TO }

/** A criterion turned on and off by its own filter chip (and by the matching operator). */
enum class SearchFlag(
    /** The operator [withFlag] adds; [SearchQuery.format] writes the same. */
    val operator: String,
) {
    UNREAD("is:unread"),
    STARRED("is:starred"),
    ATTACHMENT("has:attachment"),
    ;

    fun isSet(query: SearchQuery): Boolean = when (this) {
        UNREAD -> query.unread
        STARRED -> query.starred
        ATTACHMENT -> query.hasAttachment
    }
}

/** The date choices of the advanced search panel. */
enum class DateChoice { ANY, LAST_7_DAYS, LAST_30_DAYS, LAST_6_MONTHS, LAST_YEAR, CUSTOM }

/** Presets, in the order the panel offers them. */
internal val DATE_PRESETS = listOf(DateChoice.LAST_7_DAYS, DateChoice.LAST_30_DAYS, DateChoice.LAST_6_MONTHS, DateChoice.LAST_YEAR)

/**
 * First day of a preset: the period ends today, so "Last 7 days" is today and the 6 days before.
 * Null for [DateChoice.ANY] and [DateChoice.CUSTOM].
 */
internal fun DateChoice.start(today: LocalDate): LocalDate? = when (this) {
    DateChoice.LAST_7_DAYS -> today.minusDays(6)
    DateChoice.LAST_30_DAYS -> today.minusDays(29)
    DateChoice.LAST_6_MONTHS -> today.minusMonths(6).plusDays(1)
    DateChoice.LAST_YEAR -> today.minusYears(1).plusDays(1)
    DateChoice.ANY, DateChoice.CUSTOM -> null
}

/** The preset that [after] and [before] stand for on [today] (only a start, no end), or null. */
internal fun presetOf(after: LocalDate?, before: LocalDate?, today: LocalDate): DateChoice? {
    if (after == null || before != null) return null
    return DATE_PRESETS.firstOrNull { it.start(today) == after }
}

// ---------------------------------------------------------------------- chips

/** A removable chip under the search field for an active criterion (flags have their own toggle chips). */
sealed interface CriterionChip {
    data class Person(val field: PersonField, val value: String) : CriterionChip

    data class Subject(val value: String) : CriterionChip

    data class Excluded(val value: String) : CriterionChip

    /** Messages dated [after]..[before], both inclusive, either open; [preset] when they are one. */
    data class Dates(val after: LocalDate?, val before: LocalDate?, val preset: DateChoice?) : CriterionChip
}

/**
 * The criterion chips of [query], in the order the field shows them: senders, recipients,
 * subject, dates, excluded words. A value given twice (in any case) is one chip.
 */
internal fun criterionChips(query: SearchQuery, today: LocalDate): List<CriterionChip> = buildList {
    distinctValues(query.from).forEach { add(CriterionChip.Person(PersonField.FROM, it)) }
    distinctValues(query.to).forEach { add(CriterionChip.Person(PersonField.TO, it)) }
    distinctValues(query.subject).forEach { add(CriterionChip.Subject(it)) }
    if (query.after != null || query.before != null) {
        add(CriterionChip.Dates(query.after, query.before, presetOf(query.after, query.before, today)))
    }
    distinctValues(query.excluded).forEach { add(CriterionChip.Excluded(it)) }
}

/** [text] without the criterion of [chip]: every token that sets it is removed. */
internal fun withoutChip(text: String, chip: CriterionChip): String = when (chip) {
    is CriterionChip.Person -> removeTokens(text) { q ->
        (if (chip.field == PersonField.FROM) q.from else q.to).any { sameValue(it, chip.value) }
    }
    is CriterionChip.Subject -> removeTokens(text) { q -> q.subject.any { sameValue(it, chip.value) } }
    is CriterionChip.Excluded -> removeTokens(text) { q -> q.excluded.any { sameValue(it, chip.value) } }
    is CriterionChip.Dates -> removeTokens(text) { q -> q.after != null || q.before != null }
}

/**
 * [text] with [flag] turned on (its operator added at the end) or off (every token setting it
 * removed). The rest of the text is kept as typed.
 */
internal fun withFlag(text: String, flag: SearchFlag, on: Boolean): String {
    if (flag.isSet(SearchQuery.parse(text)) == on) return text
    return if (on) appendToken(text, flag.operator) else removeTokens(text) { flag.isSet(it) }
}

/** The chips' flags read from the query text; [thisFolderOnly] is the screen's own scope. */
internal fun filterStateOf(query: SearchQuery, thisFolderOnly: Boolean): SearchFilterState =
    SearchFilterState(
        unreadOnly = query.unread,
        starredOnly = query.starred,
        withAttachments = query.hasAttachment,
        thisFolderOnly = thisFolderOnly,
    )

/**
 * The terms result rows highlight for the search box [text]: its words and the subject and
 * person values, without operators, flags or excluded words.
 */
internal fun highlightText(text: String): String {
    val query = SearchQuery.parse(text)
    return (query.words + query.subject + query.from + query.to).joinToString(" ")
}

/** The operator tip is shown until a recent search uses an operator: by then the user knows them. */
internal fun showOperatorTip(recents: List<String>): Boolean = recents.none { SearchQuery.parse(it).hasCriteria }

// ---------------------------------------------------------------------- people

/** A full mail address (not a fragment such as "garcia@" or a name). */
internal fun isFullAddress(value: String): Boolean = FULL_ADDRESS.matches(value.trim())

private val FULL_ADDRESS = Regex("""[^\s@"]+@[^\s@"]+\.[^\s@".]+""")

/** Lookup key of a person value in a names map. */
internal fun nameKey(value: String): String = value.trim().lowercase(Locale.ROOT)

/** What a person value is shown as: the contact's name for a known address, else the value. */
internal fun personLabel(value: String, names: Map<String, String>): String = names[nameKey(value)] ?: value

/**
 * Names of the full addresses in [wanted] (lookup keys, see [nameKey]) as the senders and
 * recipients of [hits] spell them; addresses without a name on any hit are left out.
 */
internal fun namesFromHits(wanted: Set<String>, hits: List<SearchHit>): Map<String, String> {
    if (wanted.isEmpty()) return emptyMap()
    val found = HashMap<String, String>()
    for (hit in hits) {
        val message = hit.display
        for (address in listOfNotNull(message.from) + message.to) {
            val key = nameKey(address.address)
            val name = address.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (key in wanted && key !in found) found[key] = name
        }
        if (found.size == wanted.size) break
    }
    return found
}

// ---------------------------------------------------------------------- advanced search panel

/**
 * The advanced search panel's fields. Built from the search box text ([of]) and written back to
 * it ([toQuery], then [SearchQuery.format]).
 *
 * @property from / [to] chosen senders and recipients (any of them may match).
 * @property fromInput / [toInput] text being typed in the person fields; it counts as one more value.
 * @property subject words or "quoted phrases" that must all be in the subject.
 * @property words "Has the words": read like the search box itself, operators included.
 * @property excluded "Doesn't have": words or phrases, none of which may appear.
 * @property customAfter / [customBefore] the range of [DateChoice.CUSTOM]; either may be open.
 */
data class AdvancedSearchForm(
    val from: List<String> = emptyList(),
    val fromInput: String = "",
    val to: List<String> = emptyList(),
    val toInput: String = "",
    val subject: String = "",
    val words: String = "",
    val excluded: String = "",
    val date: DateChoice = DateChoice.ANY,
    val customAfter: LocalDate? = null,
    val customBefore: LocalDate? = null,
    val hasAttachment: Boolean = false,
    val unread: Boolean = false,
    val starred: Boolean = false,
) {
    /** Nothing is filled in: searching would clear the search. */
    val isEmpty: Boolean get() = this == AdvancedSearchForm()

    fun people(field: PersonField): List<String> = if (field == PersonField.FROM) from else to

    fun input(field: PersonField): String = if (field == PersonField.FROM) fromInput else toInput

    /**
     * The person input of [field] changed to [text]. A comma or semicolon ends a value: what is
     * before it becomes a chip, so "ana, carlos" can be typed or pasted.
     */
    fun withInput(field: PersonField, text: String): AdvancedSearchForm {
        val parts = text.split(',', ';')
        if (parts.size == 1) return withPeople(field, people(field), text)
        val added = parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
        return withPeople(field, addDistinct(people(field), added), parts.last().trimStart())
    }

    /** The typed text of [field] becomes a chip (the keyboard's Next, or leaving the field with Enter). */
    fun commitInput(field: PersonField): AdvancedSearchForm {
        val typed = input(field).trim()
        if (typed.isEmpty()) return withPeople(field, people(field), "")
        return withPeople(field, addDistinct(people(field), listOf(typed)), "")
    }

    /** [value] (e.g. a picked contact's address) is added to [field]; the typed text is cleared. */
    fun withPerson(field: PersonField, value: String): AdvancedSearchForm =
        withPeople(field, addDistinct(people(field), listOf(value.trim()).filter { it.isNotEmpty() }), "")

    fun withoutPerson(field: PersonField, index: Int): AdvancedSearchForm {
        val list = people(field)
        if (index !in list.indices) return this
        return withPeople(field, list.filterIndexed { i, _ -> i != index }, input(field))
    }

    /** The search these fields describe; the presets count back from [today]. */
    fun toQuery(today: LocalDate): SearchQuery {
        val typed = SearchQuery.parse(words)
        val (start, end) = when (date) {
            DateChoice.ANY -> null to null
            DateChoice.CUSTOM -> customAfter to customBefore
            else -> date.start(today) to null
        }
        return SearchQuery(
            words = typed.words,
            from = distinctValues(from + splitPeople(fromInput) + typed.from),
            to = distinctValues(to + splitPeople(toInput) + typed.to),
            subject = distinctValues(splitTerms(subject) + typed.subject),
            excluded = distinctValues(typed.excluded + splitTerms(excluded).map { it.trimStart('-').trim() }.filter { it.isNotEmpty() }),
            after = start ?: typed.after,
            before = end ?: typed.before,
            hasAttachment = hasAttachment || typed.hasAttachment,
            unread = unread || typed.unread,
            starred = starred || typed.starred,
        )
    }

    private fun withPeople(field: PersonField, list: List<String>, input: String): AdvancedSearchForm =
        if (field == PersonField.FROM) copy(from = list, fromInput = input) else copy(to = list, toInput = input)

    companion object {
        /** The fields showing [query]; dates that are a preset on [today] show as that preset. */
        fun of(query: SearchQuery, today: LocalDate): AdvancedSearchForm {
            val preset = presetOf(query.after, query.before, today)
            val dated = query.after != null || query.before != null
            return AdvancedSearchForm(
                from = distinctValues(query.from),
                to = distinctValues(query.to),
                subject = joinTerms(query.subject),
                words = joinTerms(query.words),
                excluded = joinTerms(query.excluded),
                date = preset ?: if (dated) DateChoice.CUSTOM else DateChoice.ANY,
                customAfter = if (preset == null) query.after else null,
                customBefore = if (preset == null) query.before else null,
                hasAttachment = query.hasAttachment,
                unread = query.unread,
                starred = query.starred,
            )
        }
    }
}

/** Milliseconds per day: the date pickers select UTC midnights. */
private const val MILLIS_PER_DAY = 86_400_000L

/** [date] as the date pickers hold it (UTC midnight). */
internal fun pickerMillis(date: LocalDate): Long = date.toEpochDay() * MILLIS_PER_DAY

/** The day a date picker selected. */
internal fun pickerDate(millis: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(millis, MILLIS_PER_DAY))

/** What the range picker starts with, as [pickerMillis]: the first and last day selected and the month shown. */
internal data class PickerStart(val start: Long?, val end: Long?, val shownMonth: Long?)

/**
 * The range picker's start for the custom range [after]..[before], within [years], the only years
 * the picker accepts (it fails on any other). A typed date can be of any year ("after:1/6/1206",
 * "before:1/1/2200"): such a day is left out, as is a last day without a first one or before it.
 * The month shown is the first day's, else the last day's, else none (the current one).
 */
internal fun pickerStart(after: LocalDate?, before: LocalDate?, years: IntRange): PickerStart {
    val start = after?.takeIf { it.year in years }
    val last = before?.takeIf { it.year in years }
    val end = last?.takeIf { start != null && !it.isBefore(start) }
    return PickerStart(start?.let(::pickerMillis), end?.let(::pickerMillis), (start ?: last)?.let(::pickerMillis))
}

// ---------------------------------------------------------------------- text helpers

/**
 * The tokens of [text] as [SearchQuery.parse] splits it: on whitespace outside double quotes (an
 * unbalanced quote runs to the end). Each token, parsed alone, gives exactly its part of the whole
 * query, so tokens can be dropped or added without retyping the rest.
 */
internal fun queryTokens(text: String): List<String> {
    val tokens = ArrayList<String>()
    var start = -1
    var inQuotes = false
    for ((index, c) in text.withIndex()) {
        when {
            c == '"' -> {
                if (start < 0) start = index
                inQuotes = !inQuotes
            }
            c.isWhitespace() && !inQuotes -> if (start >= 0) {
                tokens += text.substring(start, index)
                start = -1
            }
            start < 0 -> start = index
        }
    }
    if (start >= 0) tokens += text.substring(start)
    return tokens
}

/** [text] without the tokens whose own reading [drop] matches, single-spaced. */
internal fun removeTokens(text: String, drop: (SearchQuery) -> Boolean): String =
    queryTokens(text).filterNot { drop(SearchQuery.parse(it)) }.joinToString(" ")

/** [text] with [token] at the end; an unbalanced quote is closed first, so the token is read on its own. */
internal fun appendToken(text: String, token: String): String {
    val base = text.trim()
    val closed = if (base.count { it == '"' } % 2 == 1) "$base\"" else base
    return if (closed.isEmpty()) token else "$closed $token"
}

/** Words and "quoted phrases" of a panel field, without the quotes. */
internal fun splitTerms(text: String): List<String> =
    queryTokens(text).map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }

/** The values typed in a person field: separated by commas or semicolons. */
private fun splitPeople(text: String): List<String> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * [terms] as a panel field shows them, phrases quoted. A term that the field would read as
 * something else (an exclusion, an operator) is quoted too, so the field reads back the same terms.
 */
internal fun joinTerms(terms: List<String>): String = terms.map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }
    .joinToString(" ") { term ->
        if (term.any { it.isWhitespace() } || term.startsWith("-") || term.contains(':')) "\"$term\"" else term
    }

/** True when two values are the same criterion (case is ignored). */
private fun sameValue(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

/** [values] trimmed, without blanks and without repeats (in any case), in their order. */
private fun distinctValues(values: List<String>): List<String> {
    val seen = HashSet<String>()
    return values.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
}

private fun addDistinct(list: List<String>, added: List<String>): List<String> = distinctValues(list + added)
