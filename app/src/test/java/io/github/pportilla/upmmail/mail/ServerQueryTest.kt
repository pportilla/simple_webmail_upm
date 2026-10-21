package io.github.pportilla.upmmail.mail

import jakarta.mail.Message
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerQueryTest {
    private fun words(text: String) = ServerQuery.parse(text)?.words?.map { it.first() }

    @Test
    fun splitsOnWhitespaceAndKeepsQuotedPhrases() {
        assertEquals(listOf("elecciones", "rector"), words("  elecciones\trector "))
        assertEquals(listOf("junta electoral", "2024"), words("\"junta   electoral\" 2024"))
        assertEquals(listOf("junta electoral"), words("“junta electoral”"))
        assertEquals("an unclosed quote is ordinary text", listOf("junta", "electoral"), words("\"junta electoral"))
    }

    @Test
    fun stripsPunctuationAtTheEndsOnly() {
        assertEquals(listOf("acta"), words("(acta),"))
        assertEquals(listOf("cuándo"), words("¿¿cuándo??"))
        assertEquals(listOf("bea@alumnos.upm.es"), words("<bea@alumnos.upm.es>."))
        assertEquals(listOf("e-mail", "v1.2", "mi_nota"), words("«e-mail» v1.2; mi_nota!"))
        assertEquals(listOf("hola"), words("'hola'"))
    }

    @Test
    fun dropsShortWordsAndRepeats() {
        assertEquals(listOf("de", "acta"), words("a de y Acta ACTA acta"))
        assertNull(ServerQuery.parse("a y o"))
        assertNull(ServerQuery.parse("   "))
        assertNull(ServerQuery.parse("\"\" ... ¡!"))
        assertNull("no fields", ServerQuery.parse("acta", emptySet()))
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
    fun asciiOnlyKeepsAsciiSpellingsOrGivesUp() {
        val query = ServerQuery.parse("revisión acta", ServerQuery.TEXT_FIELDS)!!
        assertEquals(listOf(listOf("revision"), listOf("acta")), query.asciiOnly()!!.words)
        assertEquals(ServerQuery.TEXT_FIELDS, query.asciiOnly()!!.fields)
        assertNull("año cannot be searched as ano", ServerQuery.parse("año acta")!!.asciiOnly())
        val ascii = ServerQuery.parse("elecciones")!!
        assertEquals(ascii, ascii.asciiOnly())
    }

    @Test
    fun withFieldsKeepsTheWords() {
        val headers = ServerQuery.parse("revision")!!
        assertEquals(ServerQuery.HEADER_FIELDS, headers.fields)
        val text = headers.withFields(ServerQuery.TEXT_FIELDS)
        assertEquals(headers.words, text.words)
        assertEquals(ServerQuery.TEXT_FIELDS, text.fields)
    }

    @Test
    fun termIsAnAndOfOrsOverVariantsAndFields() {
        val single = ServerQuery.parse("elecciones", ServerQuery.TEXT_FIELDS)!!.toSearchTerm()
        assertEquals("BODY(elecciones)", describe(single))

        val accented = ServerQuery.parse("revisión", ServerQuery.TEXT_FIELDS)!!.toSearchTerm()
        assertEquals("OR[BODY(revisión), BODY(revision)]", describe(accented))

        val headers = ServerQuery.parse("acta")!!.toSearchTerm()
        assertEquals("OR[SUBJECT(acta), FROM(acta), TO(acta), CC(acta)]", describe(headers))

        val twoWords = ServerQuery.parse("acta revision", setOf(SearchField.SUBJECT))!!.toSearchTerm()
        assertEquals(
            "AND[SUBJECT(acta), OR[SUBJECT(revision), SUBJECT(revisión)]]",
            describe(twoWords),
        )
    }

    private fun describe(term: SearchTerm): String = when (term) {
        is AndTerm -> term.terms.joinToString(", ", "AND[", "]") { describe(it) }
        is OrTerm -> term.terms.joinToString(", ", "OR[", "]") { describe(it) }
        is SubjectTerm -> "SUBJECT(${term.pattern})"
        is FromStringTerm -> "FROM(${term.pattern})"
        is RecipientStringTerm -> when (term.recipientType) {
            Message.RecipientType.TO -> "TO(${term.pattern})"
            Message.RecipientType.CC -> "CC(${term.pattern})"
            else -> "BCC(${term.pattern})"
        }
        is BodyTerm -> "BODY(${term.pattern})"
        else -> term.javaClass.simpleName
    }
}
