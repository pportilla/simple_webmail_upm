package io.github.pportilla.upmmail.data.db

import java.text.Normalizer
import java.util.Locale

/** Text normalisation shared by the full-text index, local search and contact matching. */
object SearchText {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Most terms used in one query; more would only slow the FTS query down. */
    private const val MAX_TERMS = 12

    /** Lower case without diacritics: "Pérez Núñez" -> "perez nunez". */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)

    /** Folded words (letters and digits only) of [text]. */
    fun words(text: String): List<String> =
        fold(text).split(nonWord).filter { it.isNotEmpty() }

    /**
     * A safe FTS4 MATCH expression for free text typed by the user: every word of at least
     * [minPrefixLength] characters becomes a prefix term ("term*"), shorter ones must match a whole
     * word, and terms are implicitly ANDed. All FTS syntax characters are dropped and the words are
     * lower case, so they can never be read as operators (OR, NOT, NEAR). Returns null when nothing
     * searchable is left. The default makes every word a prefix.
     */
    fun matchExpression(query: String, minPrefixLength: Int = 1): String? {
        val terms = words(query).distinct().take(MAX_TERMS)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { if (it.length >= minPrefixLength) "$it*" else it }
    }

    /** True if [query] has words (as split by [words]) and each has at least [length] characters. */
    fun allTermsHaveLength(query: String, length: Int): Boolean =
        words(query).let { terms -> terms.isNotEmpty() && terms.all { it.length >= length } }

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
