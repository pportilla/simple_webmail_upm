package io.github.pportilla.upmmail.mail

import jakarta.mail.Message
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import java.text.Normalizer
import java.util.Locale

/** A part of a message that a server search looks in. */
enum class SearchField { SUBJECT, FROM, TO, CC, BODY }

/**
 * What the user typed, as an IMAP search: every word must match (the words are ANDed), each in
 * any of its spelling variants and in any of [fields]. IMAP matches substrings, so a word also
 * finds longer words that contain it.
 */
data class ServerQuery(
    /** One entry per word: its variants, the typed spelling in lower case first. */
    val words: List<List<String>>,
    val fields: Set<SearchField>,
) {
    init {
        require(words.isNotEmpty() && words.all { it.isNotEmpty() }) { "A query needs at least one word" }
        require(fields.isNotEmpty()) { "A query needs at least one field" }
    }

    fun withFields(fields: Set<SearchField>): ServerQuery = copy(fields = fields)

    /**
     * The query reduced to its ASCII variants, for a server that refuses `CHARSET UTF-8`; null when
     * a word has no ASCII spelling ("año"): leaving it out would match far too much.
     */
    fun asciiOnly(): ServerQuery? {
        val ascii = words.map { variants -> variants.filter(::isAscii) }
        return if (ascii.any { it.isEmpty() }) null else ServerQuery(ascii, fields)
    }

    /** An AND over the words of an OR over each word's variants in each field; single terms are not wrapped. */
    internal fun toSearchTerm(): SearchTerm {
        val ordered = SearchField.entries.filter { it in fields }
        val perWord = words.map { variants ->
            val terms = variants.flatMap { variant -> ordered.map { field -> termFor(field, variant) } }
            if (terms.size == 1) terms[0] else OrTerm(terms.toTypedArray())
        }
        return if (perWord.size == 1) perWord[0] else AndTerm(perWord.toTypedArray())
    }

    companion object {
        val HEADER_FIELDS: Set<SearchField> = setOf(SearchField.SUBJECT, SearchField.FROM, SearchField.TO, SearchField.CC)
        val TEXT_FIELDS: Set<SearchField> = setOf(SearchField.BODY)

        /** More words make every server command slower without narrowing much further. */
        const val MAX_WORDS = 5
        const val MAX_VARIANTS = 3

        /** A word is never shorter than this; single letters match nearly every message. */
        private const val MIN_WORD_LENGTH = 2

        /** A "quoted phrase" (straight or typographic quotes) or a run of non-blank characters. */
        private val TOKEN = Regex("\"([^\"]*)\"|“([^”]*)”|\\S+")
        private val WHITESPACE = Regex("\\s+")

        /** Stripped from both ends of a word; inside it (addresses, "e-mail", "v1.2") they stay. */
        private const val EDGE_PUNCTUATION = "\"'“”‘’«»()[]{}<>,;:!?¿¡."

        /**
         * The words of [text], or null if none is left. A quoted phrase counts as one word; words
         * shorter than 2 characters and case-insensitive repeats are dropped, and of more than
         * [MAX_WORDS] words only the longest are kept (in the order typed).
         */
        fun parse(text: String, fields: Set<SearchField> = HEADER_FIELDS): ServerQuery? {
            if (fields.isEmpty()) return null
            // Control characters cannot be sent in an IMAP string.
            val cleaned = buildString(text.length) {
                for (ch in text) append(if (Character.isISOControl(ch)) ' ' else ch)
            }
            val seen = HashSet<String>()
            val unique = ArrayList<String>()
            for (match in TOKEN.findAll(cleaned)) {
                val phrase = match.groups[1]?.value ?: match.groups[2]?.value
                val raw = phrase?.replace(WHITESPACE, " ") ?: match.value
                val word = raw.trim { it.isWhitespace() || it in EDGE_PUNCTUATION }
                if (word.length < MIN_WORD_LENGTH) continue
                if (seen.add(word.lowercase(Locale.ROOT))) unique += word
            }
            if (unique.isEmpty()) return null
            val kept = if (unique.size <= MAX_WORDS) {
                unique
            } else {
                val longest = unique.indices.sortedByDescending { unique[it].length }.take(MAX_WORDS).toSet()
                unique.filterIndexed { index, _ -> index in longest }
            }
            return ServerQuery(kept.map(::variants), fields)
        }

        /**
         * Spellings of [word] to search for, at most [MAX_VARIANTS]: the word in lower case, the
         * word without accents (keeping ñ, so "año" never becomes "ano"), and for a word typed
         * without accents that ends in -cion/-sion, the accented ending ("revision" → "revisión").
         */
        internal fun variants(word: String): List<String> {
            val lower = word.lowercase(Locale.ROOT)
            val folded = foldKeepEnye(lower)
            val result = LinkedHashSet<String>()
            result += lower
            result += folded
            if (folded == lower && (folded.endsWith("cion") || folded.endsWith("sion"))) {
                result += folded.dropLast(2) + "ón"
            }
            return result.take(MAX_VARIANTS)
        }

        /** [text] without combining accents, except the tilde of ñ/Ñ. */
        private fun foldKeepEnye(text: String): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            val out = StringBuilder(decomposed.length)
            for (ch in decomposed) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) {
                    val enye = ch == '̃' && out.isNotEmpty() && (out.last() == 'n' || out.last() == 'N')
                    if (!enye) continue
                }
                out.append(ch)
            }
            return Normalizer.normalize(out, Normalizer.Form.NFC)
        }

        private fun isAscii(text: String): Boolean = text.all { it.code < 0x80 }

        private fun termFor(field: SearchField, value: String): SearchTerm = when (field) {
            SearchField.SUBJECT -> SubjectTerm(value)
            SearchField.FROM -> FromStringTerm(value)
            SearchField.TO -> RecipientStringTerm(Message.RecipientType.TO, value)
            SearchField.CC -> RecipientStringTerm(Message.RecipientType.CC, value)
            SearchField.BODY -> BodyTerm(value)
        }
    }
}
