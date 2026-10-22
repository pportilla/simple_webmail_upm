package io.github.pportilla.upmmail.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchQueryTest {

    @Test
    fun plainWordsStayWords() {
        val q = SearchQuery.parse("  elecciones   rector ")
        assertEquals(listOf("elecciones", "rector"), q.words)
        assertFalse(q.hasCriteria)
        assertTrue(q.headersOnly.not())
    }

    @Test
    fun englishAndSpanishOperators() {
        val q = SearchQuery.parse(
            "from:ana.garcia@upm.es para:carlos asunto:tribunal desde:1/6/2026 before:2026-06-30 " +
                "tiene:adjunto is:unread es:destacado acta",
        )
        assertEquals(listOf("ana.garcia@upm.es"), q.from)
        assertEquals(listOf("carlos"), q.to)
        assertEquals(listOf("tribunal"), q.subject)
        assertEquals(LocalDate.of(2026, 6, 1), q.after)
        assertEquals(LocalDate.of(2026, 6, 30), q.before)
        assertTrue(q.hasAttachment && q.unread && q.starred)
        assertEquals(listOf("acta"), q.words)
        assertTrue(q.hasCriteria)
    }

    @Test
    fun quotedPhrasesAndExclusions() {
        val q = SearchQuery.parse("""subject:"fechas de examen" "junta de escuela" -borrador -"no responder"""")
        assertEquals(listOf("fechas de examen"), q.subject)
        assertEquals(listOf("junta de escuela"), q.words)
        assertEquals(listOf("borrador", "no responder"), q.excluded)
    }

    @Test
    fun repeatedPeopleAreAlternatives() {
        val q = SearchQuery.parse("from:ana from:carlos")
        assertEquals(listOf("ana", "carlos"), q.from)
        assertTrue(q.headersOnly)
    }

    @Test
    fun unknownOperatorsAndBadDatesAreWords() {
        val q = SearchQuery.parse("label:work after:yesterday http://upm.es ana@upm.es")
        assertEquals(listOf("label:work", "after:yesterday", "http://upm.es", "ana@upm.es"), q.words)
        assertNull(q.after)
    }

    @Test
    fun operatorStillBeingTypedIsIgnored() {
        val q = SearchQuery.parse("elecciones from:")
        assertEquals(listOf("elecciones"), q.words)
        assertTrue(q.from.isEmpty())
    }

    @Test
    fun formatRoundTrips() {
        val original = SearchQuery(
            words = listOf("acta", "junta de escuela"),
            from = listOf("Ana García"),
            to = listOf("carlos@upm.es"),
            subject = listOf("tribunal TFM"),
            excluded = listOf("borrador"),
            after = LocalDate.of(2026, 6, 1),
            before = LocalDate.of(2026, 6, 30),
            hasAttachment = true,
            unread = true,
            starred = true,
        )
        assertEquals(original, SearchQuery.parse(original.format()))
        assertEquals(SearchQuery(), SearchQuery.parse(SearchQuery().format()))
        assertTrue(SearchQuery().isEmpty)
    }

    @Test
    fun dateFormats() {
        assertEquals(LocalDate.of(2026, 6, 1), SearchQuery.parseDate("2026/06/01"))
        assertEquals(LocalDate.of(2026, 6, 1), SearchQuery.parseDate("01.06.2026"))
        assertEquals(LocalDate.of(2026, 6, 1), SearchQuery.parseDate("1-6-26"))
        assertNull(SearchQuery.parseDate("31/02/2026"))
        assertNull(SearchQuery.parseDate("junio"))
    }
}
