package io.github.pportilla.upmmail.ui.mailbox

import java.text.Normalizer
import java.util.Locale

/*
 * Search-term highlighting for list rows. Matching ignores case and accents ("electronica"
 * highlights "Electrónica"), which matters for Spanish mail.
 */

/** Distinct search terms of [query]: whitespace-separated words without surrounding quotes or operators. */
fun highlightTerms(query: String?): List<String> {
    if (query.isNullOrBlank()) return emptyList()
    return query.split(Regex("\\s+"))
        .map { it.trim('"', '\'', '(', ')', '*', ',', ';', ':') }
        .filter { it.isNotEmpty() && !it.equals("OR", ignoreCase = false) && !it.equals("AND", ignoreCase = false) && it != "-" }
        .map { it.trimStart('-') }
        .filter { it.isNotEmpty() }
        .distinctBy { fold(it) }
}

/**
 * Ranges of [text] matching any of [terms], sorted and merged. Each character of [text] maps to
 * exactly one folded character, so the ranges index the original text.
 */
fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val folded = fold(text)
    val raw = ArrayList<IntRange>()
    for (term in terms) {
        val needle = fold(term)
        if (needle.isEmpty()) continue
        var from = 0
        while (from <= folded.length - needle.length) {
            val index = folded.indexOf(needle, from)
            if (index < 0) break
            raw += index until index + needle.length
            from = index + needle.length
        }
    }
    if (raw.isEmpty()) return emptyList()
    raw.sortBy { it.first }
    val merged = ArrayList<IntRange>()
    var current = raw.first()
    for (range in raw.drop(1)) {
        current = if (range.first <= current.last + 1) {
            current.first..maxOf(current.last, range.last)
        } else {
            merged += current
            range
        }
    }
    merged += current
    return merged
}

/** Lower-cases and strips diacritics character by character, keeping the length unchanged. */
internal fun fold(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        val base = if (ch.code < 0x80) {
            ch
        } else {
            Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
        }
        out.append(base.toString().lowercase(Locale.ROOT).singleOrNull() ?: base.lowercaseChar())
    }
    return out.toString()
}
