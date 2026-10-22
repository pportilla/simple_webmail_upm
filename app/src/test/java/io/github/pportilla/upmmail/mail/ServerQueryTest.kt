package io.github.pportilla.upmmail.mail

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

class ServerQueryTest {
    private fun words(text: String) = ServerQuery.parse(text)?.words?.map { it.first() }

    private fun parse(text: String, fields: Set<SearchField> = ServerQuery.HEADER_FIELDS) = ServerQuery.parse(text, fields)!!

    private fun term(text: String, fields: Set<SearchField> = ServerQuery.HEADER_FIELDS) = describe(parse(text, fields).toSearchTerm()!!)

    @Test
    fun splitsOnWhitespaceAndKeepsQuotedPhrases() {
        assertEquals(listOf("elecciones", "rector"), words("  elecciones\trector "))
        assertEquals(listOf("junta electoral", "2024"), words("\"junta   electoral\" 2024"))
        assertEquals("typographic quotes are punctuation", listOf("junta", "electoral"), words("“junta electoral”"))
        assertEquals("an unclosed quote runs to the end", listOf("junta electoral"), words("\"junta electoral"))
    }

    @Test
    fun stripsPunctuationAtTheEndsOnly() {
        assertEquals(listOf("acta"), words("(acta),"))
        assertEquals(listOf("cuándo"), words("¿¿cuándo??"))
        assertEquals(listOf("bea@alumnos.upm.es"), words("<bea@alumnos.upm.es>."))
        assertEquals(listOf("e-mail", "v1.2", "mi_nota"), words("«e-mail» v1.2; mi_nota!"))
        assertEquals(listOf("hola"), words("'hola'"))
        assertEquals(listOf(listOf("ana@upm.es")), parse("from:<ana@upm.es>,").from)
        assertEquals("part of an address", listOf(listOf("garcia@")), parse("from:garcia@").from)
    }

    @Test
    fun dropsShortValuesAndRepeats() {
        assertEquals(listOf("de", "acta"), words("a de y Acta ACTA acta"))
        assertNull(ServerQuery.parse("a y o"))
        assertNull(ServerQuery.parse("   "))
        assertNull(ServerQuery.parse("\"\" ... ¡!"))
        assertNull("no fields", ServerQuery.parse("acta", emptySet()))
        assertNull("an operator still being typed", ServerQuery.parse("from:"))
        assertNull(ServerQuery.parse("from:a -b"))
        assertEquals(listOf(listOf("ana")), parse("from:ana from:ANA de:Ana").from)
    }

    @Test
    fun keepsTheFiveLongestWordsInTypedOrder() {
        assertEquals(
            listOf("convocatoria", "elecciones", "rectorado", "claustro", "junta"),
            words("la convocatoria de elecciones al rectorado del claustro y la junta"),
        )
        assertEquals(ServerQuery.MAX_WORDS, ServerQuery.parse("uno dos tres cuatro cinco seis siete")!!.words.size)
        // Of the 4-letter words "tres" and "seis" both fit; the 3-letter "uno" and "dos" are left out.
        assertEquals(listOf("tres", "cuatro", "cinco", "seis", "siete"), words("uno dos tres cuatro cinco seis siete"))
        // Senders are an OR: the first ones typed are kept.
        assertEquals(
            listOf("ana", "bea", "carlos", "dani", "eva"),
            parse("from:ana from:bea from:carlos from:dani from:eva from:fernando").from.map { it.first() },
        )
    }

    @Test
    fun removesControlCharacters() {
        assertEquals(listOf("acta", "junta"), words("acta\u0000junta\r\n"))
    }

    @Test
    fun variantsCoverAccentsBothWays() {
        assertEquals(listOf("revision", "revisión"), ServerQuery.variants("revision"))
        assertEquals(listOf("revisión", "revision"), ServerQuery.variants("Revisión"))
        assertEquals(listOf("canción", "cancion"), ServerQuery.variants("CANCIÓN"))
        assertEquals(listOf("cancion", "canción"), ServerQuery.variants("cancion"))
        assertEquals(listOf("elecciones"), ServerQuery.variants("Elecciones"))
        assertEquals(listOf("año"), ServerQuery.variants("Año"))
        assertEquals(listOf("niño"), ServerQuery.variants("NIÑO"))
        assertEquals(listOf("pingüino", "pinguino"), ServerQuery.variants("pingüino"))
        assertEquals(listOf("ñandú", "ñandu"), ServerQuery.variants("Ñandú"))
        assertEquals(listOf("bea@upm.es"), ServerQuery.variants("Bea@UPM.es"))
        assertTrue(ServerQuery.variants("Acción").size <= ServerQuery.MAX_VARIANTS)
    }

    @Test
    fun peopleAreAlsoSpelledAsInMailAddresses() {
        assertEquals(listOf("garcía", "garcia"), ServerQuery.personVariants("García"))
        assertEquals(listOf("muñoz", "munoz"), ServerQuery.personVariants("Muñoz"))
        assertEquals(listOf("íñigo", "iñigo", "inigo"), ServerQuery.personVariants("Íñigo"))
        assertEquals(listOf("ana.garcia@upm.es"), ServerQuery.personVariants("ANA.Garcia@UPM.es"))
        assertEquals(listOf(listOf("muñoz", "munoz")), parse("to:Muñoz").to)
        assertEquals("subject words keep ñ", listOf(listOf("año")), parse("subject:Año").subject)
    }

    @Test
    fun operatorsBecomeCriteria() {
        val query = parse(
            "from:ana de:García to:carlos subject:\"acta junta\" asunto:revisión -borrador " +
                "after:2026-06-01 before:30/06/2026 is:unread es:destacado has:attachment informe",
        )
        assertEquals(listOf(listOf("informe")), query.words)
        assertEquals(listOf(listOf("ana"), listOf("garcía", "garcia")), query.from)
        assertEquals(listOf(listOf("carlos")), query.to)
        assertEquals(listOf(listOf("acta junta"), listOf("revisión", "revision")), query.subject)
        assertEquals(listOf(listOf("borrador")), query.excluded)
        assertEquals(LocalDate.of(2026, 6, 1), query.since)
        assertEquals(LocalDate.of(2026, 6, 30), query.until)
        assertTrue(query.unseen)
        assertTrue(query.flagged)
        assertTrue(query.hasAttachment)
        assertTrue(query.hasWords)
        assertEquals(ServerQuery.HEADER_FIELDS, query.fields)
    }

    @Test
    fun queriesWithoutWordsAreAnsweredByTheHeaders() {
        for (text in listOf("from:ana", "to:carlos subject:acta", "after:2026-01-01", "is:unread", "from:ana -borrador", "has:attachment")) {
            assertFalse(text, parse(text).hasWords)
        }
        assertTrue(parse("from:ana informe").hasWords)
        assertFalse("a word too short to search", parse("from:ana x").hasWords)
    }

    @Test
    fun anAttachmentFilterAloneHasNoSearchKey() {
        val query = parse("has:attachment")
        assertTrue(query.hasAttachment)
        assertFalse(query.hasImapCriteria)
        assertNull(query.toSearchTerm())
        assertNull(query.headerCriteria())
        assertEquals("FROM(ana)", describe(parse("from:ana tiene:adjunto").toSearchTerm()!!))
    }

    @Test
    fun termIsAnAndOfOrsOverVariantsAndFields() {
        assertEquals("BODY(elecciones)", term("elecciones", ServerQuery.TEXT_FIELDS))
        assertEquals("OR[BODY(revisión), BODY(revision)]", term("revisión", ServerQuery.TEXT_FIELDS))
        assertEquals("OR[SUBJECT(acta), FROM(acta), TO(acta), CC(acta)]", term("acta"))
        assertEquals(
            "AND[SUBJECT(acta), OR[SUBJECT(revision), SUBJECT(revisión)]]",
            term("acta revision", setOf(SearchField.SUBJECT)),
        )
    }

    @Test
    fun criteriaAreAndedCheapestFirst() {
        val text = "informe from:ana from:garcía to:carlos subject:acta -borrador after:2026-06-01 before:2026-06-30 is:unread is:starred"
        assertEquals(
            "AND[UNSEEN, FLAGGED, SENTSINCE(2026-05-31), SENTBEFORE(2026-07-02), " +
                "OR[FROM(ana), FROM(garcía), FROM(garcia)], OR[TO(carlos), CC(carlos)], SUBJECT(acta), " +
                "OR[SUBJECT(informe), FROM(informe), TO(informe), CC(informe)], " +
                "NOT[OR[SUBJECT(borrador), FROM(borrador), TO(borrador), CC(borrador), BODY(borrador)]]]",
            term(text),
        )
        // In the text phase the word is looked for in the text; the exclusion is everywhere in both.
        assertEquals(
            "AND[UNSEEN, FLAGGED, SENTSINCE(2026-05-31), SENTBEFORE(2026-07-02), " +
                "OR[FROM(ana), FROM(garcía), FROM(garcia)], OR[TO(carlos), CC(carlos)], SUBJECT(acta), BODY(informe), " +
                "NOT[OR[SUBJECT(borrador), FROM(borrador), TO(borrador), CC(borrador), BODY(borrador)]]]",
            term(text, ServerQuery.TEXT_FIELDS),
        )
        assertEquals("AND[SUBJECT(acta), SUBJECT(junta)]", term("subject:acta subject:junta"))
        assertEquals(
            "AND[NOT[OR[SUBJECT(borrador), FROM(borrador), TO(borrador), CC(borrador), BODY(borrador)]], " +
                "NOT[OR[SUBJECT(revisión), FROM(revisión), TO(revisión), CC(revisión), BODY(revisión), " +
                "SUBJECT(revision), FROM(revision), TO(revision), CC(revision), BODY(revision)]]]",
            term("-borrador -revisión"),
        )
    }

    @Test
    fun exclusionsLookInTheTextUnlessOnlyTheHeadersAreAsked() {
        val query = parse("from:ana -borrador")
        assertEquals(
            "AND[FROM(ana), NOT[OR[SUBJECT(borrador), FROM(borrador), TO(borrador), CC(borrador), BODY(borrador)]]]",
            describe(query.toSearchTerm()!!),
        )
        val headers = query.headerExclusionsOnly()
        assertEquals("AND[FROM(ana), NOT[OR[SUBJECT(borrador), FROM(borrador), TO(borrador), CC(borrador)]]]", describe(headers.toSearchTerm()!!))
        assertFalse("the ASCII fallback keeps it", headers.asciiOnly()!!.excludeInText)
        assertEquals(query.from, headers.from)
        assertEquals(query.excluded, headers.excluded)
    }

    @Test
    fun theServerIsAskedForADayMoreOnEachSide() {
        // The server compares the Date header's own day, in the sender's zone: one day either way.
        val terms = (parse("after:1/6/2026 before:1/6/2026").toSearchTerm() as AndTerm).terms.map { it as SentDateTerm }
        assertEquals(listOf(ComparisonTerm.GE, ComparisonTerm.LT), terms.map { it.comparison })
        val zone = ZoneId.systemDefault()
        val days = terms.map { Instant.ofEpochMilli(it.date.time).atZone(zone) }
        assertEquals(listOf(LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 3)), days.map { it.toLocalDate() })
        assertTrue(days.all { it.toLocalTime() == LocalTime.MIDNIGHT })
        assertEquals("SENTSINCE(2025-12-31)", term("desde:2026-01-01"))
        assertEquals("SENTBEFORE(2027-01-02)", term("hasta:31/12/2026"))
    }

    @Test
    fun datesMatchWholeDaysOfTheDeviceZoneBothIncluded() {
        val madrid = ZoneId.of("Europe/Madrid")
        fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()
        val june = parse("after:1/6/2026 before:30/6/2026")
        assertTrue(june.datesMatch(at("2026-06-01T00:00+02:00"), madrid))
        assertTrue(june.datesMatch(at("2026-06-30T23:59:59+02:00"), madrid))
        assertFalse(june.datesMatch(at("2026-05-31T23:59:59+02:00"), madrid))
        assertFalse(june.datesMatch(at("2026-07-01T00:00+02:00"), madrid))
        // Sent on the evening of 31 May in Mexico City: already 1 June in Madrid.
        assertTrue(june.datesMatch(at("2026-05-31T19:30-06:00"), madrid))
        assertFalse(parse("before:31/5/2026").datesMatch(at("2026-05-31T19:30-06:00"), madrid))
        assertTrue(parse("since:1/6/2026").datesMatch(at("2030-01-01T00:00Z"), madrid))
        assertTrue("no dates", parse("from:ana").datesMatch(0L, madrid))
    }

    @Test
    fun asciiOnlyKeepsAsciiSpellingsOrGivesUp() {
        val query = parse("revisión acta", ServerQuery.TEXT_FIELDS)
        assertEquals(listOf(listOf("revision"), listOf("acta")), query.asciiOnly()!!.words)
        assertEquals(ServerQuery.TEXT_FIELDS, query.asciiOnly()!!.fields)
        assertNull("año cannot be searched as ano", parse("año acta").asciiOnly())
        val ascii = parse("elecciones")
        assertEquals(ascii, ascii.asciiOnly())

        val people = parse("from:Muñoz to:García subject:revisión -canción after:2026-06-01 is:unread").asciiOnly()!!
        assertEquals(listOf(listOf("munoz")), people.from)
        assertEquals(listOf(listOf("garcia")), people.to)
        assertEquals(listOf(listOf("revision")), people.subject)
        assertEquals(listOf(listOf("cancion")), people.excluded)
        assertEquals(LocalDate.of(2026, 6, 1), people.since)
        assertTrue(people.unseen)
        assertNull(parse("subject:año").asciiOnly())
        assertNull("an exclusion left out would match too much", parse("acta -año").asciiOnly())
    }

    @Test
    fun headerCriteriaLeaveOutWordsExclusionsAndAttachments() {
        val query = parse("informe from:ana -borrador after:2026-06-01 is:starred has:attachment", ServerQuery.TEXT_FIELDS)
        val criteria = query.headerCriteria()!!
        assertTrue(criteria.words.isEmpty())
        assertTrue(criteria.excluded.isEmpty())
        assertFalse(criteria.hasAttachment)
        assertEquals(query.from, criteria.from)
        assertEquals(ServerQuery.HEADER_FIELDS, criteria.fields)
        assertEquals("AND[FLAGGED, SENTSINCE(2026-05-31), FROM(ana)]", describe(criteria.toSearchTerm()!!))
        assertNull(parse("informe -borrador").headerCriteria())
        assertNotNull(parse("informe is:unread").headerCriteria())
    }

    @Test
    fun withFieldsKeepsTheCriteria() {
        val headers = parse("revision from:ana")
        assertEquals(ServerQuery.HEADER_FIELDS, headers.fields)
        val text = headers.withFields(ServerQuery.TEXT_FIELDS)
        assertEquals(headers.words, text.words)
        assertEquals(headers.from, text.from)
        assertEquals(ServerQuery.TEXT_FIELDS, text.fields)
    }

    private fun describe(term: SearchTerm): String = when (term) {
        is AndTerm -> term.terms.joinToString(", ", "AND[", "]") { describe(it) }
        is OrTerm -> term.terms.joinToString(", ", "OR[", "]") { describe(it) }
        is NotTerm -> "NOT[${describe(term.term)}]"
        is SubjectTerm -> "SUBJECT(${term.pattern})"
        is FromStringTerm -> "FROM(${term.pattern})"
        is RecipientStringTerm -> when (term.recipientType) {
            Message.RecipientType.TO -> "TO(${term.pattern})"
            Message.RecipientType.CC -> "CC(${term.pattern})"
            else -> "BCC(${term.pattern})"
        }
        is BodyTerm -> "BODY(${term.pattern})"
        is FlagTerm -> {
            val flag = term.flags.systemFlags.single()
            when {
                flag == Flags.Flag.SEEN && !term.testSet -> "UNSEEN"
                flag == Flags.Flag.FLAGGED && term.testSet -> "FLAGGED"
                else -> "FLAG($flag, ${term.testSet})"
            }
        }
        is SentDateTerm -> {
            val day = Instant.ofEpochMilli(term.date.time).atZone(ZoneId.systemDefault()).toLocalDate()
            when (term.comparison) {
                ComparisonTerm.GE -> "SENTSINCE($day)"
                ComparisonTerm.LT -> "SENTBEFORE($day)"
                else -> "SENT(${term.comparison}, $day)"
            }
        }
        else -> term.javaClass.simpleName
    }
}
