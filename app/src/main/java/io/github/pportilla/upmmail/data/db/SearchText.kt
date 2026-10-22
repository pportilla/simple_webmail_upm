package io.github.pportilla.upmmail.data.db

import java.text.Normalizer
import java.util.Locale

/** Text normalisation shared by the full-text index, local search and contact matching. */
object SearchText {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Most terms used in one query or phrase; more would only slow the FTS query down. */
    internal const val MAX_TERMS = 12

    /** Lower case without diacritics: "Pérez Núñez" -> "perez nunez". */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)

    /** Folded words (letters and digits only) of [text]. */
    fun words(text: String): List<String> =
        fold(text).split(nonWord).filter { it.isNotEmpty() }

    /**
     * A safe FTS4 phrase for text typed by the user: its words in order (at most [MAX_TERMS]), the
     * last one a prefix when [prefixLast] (`"ana garcia*"` matches "Ana García López" and
     * "ana.garcia@upm.es", whose words are ana, garcia, upm, es). A single word is a plain term.
     * It holds only lower case letters, digits, spaces, quotes and the star, so nothing typed is
     * ever read as an FTS operator (OR, NOT, NEAR, column filters). Null when [text] has no words.
     */
    fun phrase(text: String, prefixLast: Boolean): String? {
        val terms = words(text).take(MAX_TERMS)
        if (terms.isEmpty()) return null
        val star = if (prefixLast) "*" else ""
        return if (terms.size == 1) terms[0] + star else terms.joinToString(" ", prefix = "\"", postfix = "$star\"")
    }

    /**
     * True if every word of [query] is a prefix of some word of [candidates]
     * (case- and accent-insensitive), e.g. "ana per" matches "Ana Pérez <ana.perez@upm.es>".
     */
    fun matchesWordPrefixes(query: String, vararg candidates: String?): Boolean {
        val terms = words(query)
        if (terms.isEmpty()) return false
        val haystack = candidates.filterNotNull().flatMap { words(it) }
        val folded = candidates.filterNotNull().map { fold(it) }
        return terms.all { term ->
            haystack.any { it.startsWith(term) } || folded.any { it.startsWith(term) }
        }
    }

    /** Escapes [text] for a LIKE pattern used with `ESCAPE '\'`. */
    fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
