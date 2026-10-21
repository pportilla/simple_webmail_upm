package io.github.pportilla.upmmail.ui.mailbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHighlightTest {

    @Test
    fun termsIgnoreQuotesOperatorsAndDuplicates() {
        assertEquals(emptyList<String>(), highlightTerms(null))
        assertEquals(emptyList<String>(), highlightTerms("   "))
        assertEquals(listOf("Reunión", "depto"), highlightTerms("  \"Reunión\"  depto OR  reunion -"))
        assertEquals(listOf("ana", "upm.es"), highlightTerms("(ana) -upm.es"))
    }

    @Test
    fun matchingIgnoresCaseAndAccents() {
        val text = "Reunión del Departamento de Electrónica"
        val ranges = highlightRanges(text, listOf("reunion", "ELECTRONICA"))
        val start = text.indexOf("Electrónica")
        assertEquals(listOf(0..6, start until start + "Electrónica".length), ranges)
        assertEquals("Reunión", text.substring(ranges[0]))
        assertEquals("Electrónica", text.substring(ranges[1]))
    }

    @Test
    fun overlappingAndRepeatedMatchesAreMerged() {
        assertEquals(listOf(0..5), highlightRanges("abcdef", listOf("abcd", "cdef")))
        assertEquals(listOf(0..1, 3..4), highlightRanges("aa aa", listOf("aa")))
        assertEquals(listOf(0..3), highlightRanges("aaaa", listOf("aa")))
        assertTrue(highlightRanges("", listOf("a")).isEmpty())
        assertTrue(highlightRanges("abc", emptyList()).isEmpty())
        assertTrue(highlightRanges("abc", listOf("x")).isEmpty())
    }

    @Test
    fun foldingKeepsLengthSoRangesIndexTheOriginal() {
        val samples = listOf("ÑANDÚ", "Ça va? ¿Qué tal?", "İstanbul", "straße", "emoji 😀 ok")
        for (sample in samples) assertEquals(sample, sample.length, fold(sample).length)
        assertEquals("nandu", fold("ÑANDÚ"))
    }
}
