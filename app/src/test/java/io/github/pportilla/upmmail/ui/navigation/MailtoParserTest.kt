package io.github.pportilla.upmmail.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailtoParserTest {

    @Test
    fun `plain address`() {
        assertEquals(MailtoData(to = listOf("ana@upm.es")), MailtoParser.parse("mailto:ana@upm.es"))
    }

    @Test
    fun `all fields with percent-encoded UTF-8`() {
        val data = MailtoParser.parse(
            "mailto:a@upm.es,b@upm.es?cc=c@upm.es&bcc=d@upm.es&subject=Hola%20mundo" +
                "&body=L%C3%ADnea%201%0D%0AL%C3%ADnea%202",
        )!!
        assertEquals(listOf("a@upm.es", "b@upm.es"), data.to)
        assertEquals(listOf("c@upm.es"), data.cc)
        assertEquals(listOf("d@upm.es"), data.bcc)
        assertEquals("Hola mundo", data.subject)
        assertEquals("Línea 1\nLínea 2", data.body)
    }

    @Test
    fun `plus signs are literal`() {
        val data = MailtoParser.parse("mailto:ana+list@upm.es?subject=1+1")!!
        assertEquals(listOf("ana+list@upm.es"), data.to)
        assertEquals("1+1", data.subject)
    }

    @Test
    fun `scheme and field names are case-insensitive and extra recipients are merged`() {
        val data = MailtoParser.parse("MAILTO:x@upm.es?SUBJECT=Hi&To=y@upm.es,z@upm.es&CC=w@upm.es&cc=v@upm.es")!!
        assertEquals(listOf("x@upm.es", "y@upm.es", "z@upm.es"), data.to)
        assertEquals(listOf("w@upm.es", "v@upm.es"), data.cc)
        assertEquals("Hi", data.subject)
    }

    @Test
    fun `encoded separators in header fields split addresses`() {
        val data = MailtoParser.parse("mailto:?to=a%40upm.es%2Cb%40upm.es")!!
        assertEquals(listOf("a@upm.es", "b@upm.es"), data.to)
    }

    @Test
    fun `semicolons separate addresses`() {
        assertEquals(listOf("a@x.es", "b@x.es"), MailtoParser.parse("mailto:a@x.es;b@x.es")!!.to)
    }

    @Test
    fun `duplicate addresses are dropped ignoring case`() {
        assertEquals(listOf("a@x.es"), MailtoParser.parse("mailto:a@x.es?to=A@X.es")!!.to)
    }

    @Test
    fun `latin-1 escapes from old pages are decoded`() {
        assertEquals("Café", MailtoParser.parse("mailto:?subject=Caf%E9")!!.subject)
    }

    @Test
    fun `malformed escapes are kept`() {
        assertEquals("100% sure%", MailtoParser.parse("mailto:?subject=100%25%20sure%")!!.subject)
        assertEquals("a%zzb", MailtoParser.percentDecode("a%zzb"))
        assertEquals("%4", MailtoParser.percentDecode("%4"))
    }

    @Test
    fun `empty mailto gives empty data`() {
        assertEquals(MailtoData(), MailtoParser.parse("mailto:"))
        assertEquals(MailtoData(), MailtoParser.parse("mailto:?subject=&body="))
    }

    @Test
    fun `double slash prefix and fragments are tolerated`() {
        val data = MailtoParser.parse("mailto://ana@upm.es?subject=Hi#top")!!
        assertEquals(listOf("ana@upm.es"), data.to)
        assertEquals("Hi", data.subject)
    }

    @Test
    fun `subject is one line and body keeps line breaks`() {
        val data = MailtoParser.parse("mailto:?subject=a%0D%0Ab&body=x%0Dy%0A%0Az")!!
        assertEquals("a b", data.subject)
        assertEquals("x\ny\n\nz", data.body)
    }

    @Test
    fun `first subject and body win`() {
        val data = MailtoParser.parse("mailto:?subject=one&subject=two&body=a&body=b")!!
        assertEquals("one", data.subject)
        assertEquals("a", data.body)
    }

    @Test
    fun `non mailto URIs are rejected`() {
        assertNull(MailtoParser.parse("https://upm.es"))
        assertFalse(MailtoParser.isMailto("tel:123"))
        assertTrue(MailtoParser.isMailto("  mailto:a@b.es"))
    }

    @Test
    fun `separators inside quoted names and brackets do not split`() {
        assertEquals(
            listOf("\"Garcia, Ana\" <ana@upm.es>", "b@x.es"),
            MailtoParser.splitAddresses("\"Garcia, Ana\" <ana@upm.es>; b@x.es"),
        )
        assertEquals(
            listOf("\"Say \\\"hi, there\\\"\" <c@x.es>"),
            MailtoParser.splitAddresses("\"Say \\\"hi, there\\\"\" <c@x.es>"),
        )
        val data = MailtoParser.parse("mailto:?to=%22Garcia%2C%20Ana%22%20%3Cana%40upm.es%3E%2Cb%40x.es")!!
        assertEquals(listOf("\"Garcia, Ana\" <ana@upm.es>", "b@x.es"), data.to)
    }

    @Test
    fun `joinAddresses merges lists and splits combined entries`() {
        assertEquals("a@x.es, b@x.es", MailtoParser.joinAddresses(listOf("a@x.es"), listOf("b@x.es, A@x.es")))
        assertNull(MailtoParser.joinAddresses(emptyList(), listOf(" ", "")))
    }
}
