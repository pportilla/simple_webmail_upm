package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotingTest {
    private val me = EmailAddress("ana@upm.es", "Ana")
    private val bea = EmailAddress("bea@upm.es", "Bea")
    private val carlos = EmailAddress("carlos@upm.es")
    private val dani = EmailAddress("dani@alumnos.upm.es")
    private val list = EmailAddress("lista@lists.upm.es")

    @Test
    fun replySubjectAddsPrefixOnce() {
        assertEquals("Re: Horario", Quoting.replySubject("Horario"))
        assertEquals("Re: Horario", Quoting.replySubject("  Horario "))
        assertEquals("Re: Horario", Quoting.replySubject("Re: Horario"))
        assertEquals("RE: Horario", Quoting.replySubject("RE: Horario"))
        assertEquals("Re[2]: Horario", Quoting.replySubject("Re[2]: Horario"))
        assertEquals("AW: Termin", Quoting.replySubject("AW: Termin"))
        assertEquals("RV: Horario", Quoting.replySubject("RV: Horario"))
        assertEquals("Re: Fwd: Horario", Quoting.replySubject("Fwd: Horario"))
        assertEquals("Re: Revisión", Quoting.replySubject("Revisión"))
        assertEquals("Re:", Quoting.replySubject(""))
        assertEquals("Re: Reunión", Quoting.replySubject("Re: Reunión"))
        assertEquals("Re: Recordatorio", Quoting.replySubject("Recordatorio"))
    }

    @Test
    fun forwardSubjectAddsPrefixOnce() {
        assertEquals("Fwd: Horario", Quoting.forwardSubject("Horario"))
        assertEquals("Fwd: Horario", Quoting.forwardSubject("Fwd: Horario"))
        assertEquals("FW: Horario", Quoting.forwardSubject("FW: Horario"))
        assertEquals("RV: Horario", Quoting.forwardSubject("RV: Horario"))
        assertEquals("ENC: Horario", Quoting.forwardSubject("ENC: Horario"))
        assertEquals("WG: Termin", Quoting.forwardSubject("WG: Termin"))
        assertEquals("Fwd: Re: Horario", Quoting.forwardSubject("Re: Horario"))
        assertEquals("Fwd: Fwdx", Quoting.forwardSubject("Fwdx"))
    }

    @Test
    fun quotePrefixesEveryLine() {
        assertEquals("> Hola\n>\n> Adiós", Quoting.quote("Hola\n\nAdiós"))
        assertEquals("> Hola\n>> antes\n>>> más antes", Quoting.quote("Hola\r\n> antes\n>> más antes\n\n"))
        assertEquals(">", Quoting.quote(""))
    }

    @Test
    fun replyGoesToReplyToOrFrom() {
        val plain = Quoting.replyRecipients(bea, listOf(me), listOf(carlos), emptyList(), setOf("ana@upm.es"), false)
        assertEquals(listOf(bea), plain.to)
        assertEquals(emptyList<EmailAddress>(), plain.cc)

        val withReplyTo = Quoting.replyRecipients(bea, listOf(me), emptyList(), listOf(list), setOf("ana@upm.es"), false)
        assertEquals(listOf(list), withReplyTo.to)
    }

    @Test
    fun replyAllAddsRecipientsWithoutSelfOrDuplicates() {
        val result = Quoting.replyRecipients(
            from = bea,
            to = listOf(me, carlos, EmailAddress("BEA@upm.es")),
            cc = listOf(dani, EmailAddress("Ana@UPM.es"), carlos),
            replyTo = emptyList(),
            selfAddresses = setOf("ana@upm.es"),
            replyAll = true,
        )
        assertEquals(listOf(bea, carlos), result.to)
        assertEquals(listOf(dani), result.cc)
    }

    @Test
    fun replyToOwnMessageUsesOriginalRecipients() {
        val plain = Quoting.replyRecipients(me, listOf(bea, carlos), listOf(dani), emptyList(), setOf("ana@upm.es"), false)
        assertEquals(listOf(bea, carlos), plain.to)
        assertEquals(emptyList<EmailAddress>(), plain.cc)

        val all = Quoting.replyRecipients(me, listOf(bea), listOf(dani, bea), emptyList(), setOf("ana@upm.es"), true)
        assertEquals(listOf(bea), all.to)
        assertEquals(listOf(dani), all.cc)
    }

    @Test
    fun ccMovesToToWhenToWouldBeEmpty() {
        val result = Quoting.replyRecipients(
            from = null,
            to = listOf(me),
            cc = listOf(carlos),
            replyTo = emptyList(),
            selfAddresses = setOf("ana@upm.es"),
            replyAll = true,
        )
        assertEquals(listOf(carlos), result.to)
        assertEquals(emptyList<EmailAddress>(), result.cc)
    }

    @Test
    fun referencesAppendIdDeduplicateAndKeepRoot() {
        assertEquals(listOf("<a@x>", "<b@x>"), Quoting.references("<b@x>", listOf("<a@x>")))
        assertEquals(listOf("<a@x>", "<B@x>"), Quoting.references("<b@x>", listOf("<a@x>", "<B@x>")))
        assertEquals(listOf("<a@x>"), Quoting.references(null, listOf("<a@x>", " ")))
        val many = (1..30).map { "<$it@x>" }
        val trimmed = Quoting.references("<31@x>", many)
        assertEquals(20, trimmed.size)
        assertEquals("<1@x>", trimmed.first())
        assertEquals("<31@x>", trimmed.last())
        assertEquals("<13@x>", trimmed[1])
    }
}
