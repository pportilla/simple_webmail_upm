package io.github.pportilla.upmmail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {
    @Test
    fun blockElementsBecomeLineBreaks() {
        val text = HtmlText.toPlainText(
            "<html><head><title>T</title><style>p{color:red}</style></head><body>" +
                "<p>Hola   Ana,</p><p>Primera<br>segunda</p><div>tres</div><div><br></div><div>cuatro</div>" +
                "<ul><li>uno</li><li>dos</li></ul><script>var x;</script></body></html>",
        )
        assertEquals("Hola Ana,\n\nPrimera\nsegunda\n\ntres\n\ncuatro\n\n• uno\n• dos", text)
    }

    @Test
    fun blockquotesBecomeQuotedLinesAndPreIsKept() {
        val text = HtmlText.toPlainText(
            "<div>El lunes, Bea escribió:</div><blockquote><div>Cita</div><blockquote>Doble</blockquote></blockquote>" +
                "<pre>  a\n  b</pre><table><tr><td>x</td><td>y</td></tr></table>",
        )
        assertEquals("El lunes, Bea escribió:\n> Cita\n>> Doble\n\n  a\n  b\n\nx y", text)
    }

    @Test
    fun entitiesAndNbspAreDecoded() {
        assertEquals("a & b < c  d", HtmlText.toPlainText("a &amp; b &lt; c&nbsp;&nbsp;d"))
        assertEquals("", HtmlText.toPlainText("   "))
    }

    @Test
    fun snippetPrefersTextAndCollapsesWhitespace() {
        assertEquals("Hola Ana, ¿qué tal?", HtmlText.snippet("Hola   Ana,\n\n¿qué tal?", "<p>ignored</p>"))
        assertEquals("Desde HTML", HtmlText.snippet(null, "<p>Desde</p><p>HTML</p>"))
        assertEquals("Desde HTML", HtmlText.snippet("  ", "<p>Desde</p><p>HTML</p>"))
        assertEquals("", HtmlText.snippet(null, null))
    }

    @Test
    fun snippetDropsEnglishQuotesAndAttribution() {
        val text = "Sounds good, see you then.\n\nOn Mon, 3 Jun 2024 at 10:00, Bea Gómez <bea@upm.es> wrote:\n> Shall we meet?\n> Bea"
        assertEquals("Sounds good, see you then.", HtmlText.snippet(text, null))
    }

    @Test
    fun snippetDropsSpanishAndWrappedAttribution() {
        val spanish = "Perfecto.\nEl lun, 3 jun 2024 a las 10:00, Bea Gómez (<bea@upm.es>) escribió:\n> ¿Quedamos?"
        assertEquals("Perfecto.", HtmlText.snippet(spanish, null))
        val wrapped = "Vale.\n\nOn Mon, Jun 3, 2024 at 10:00 AM Bea Gómez <\nbea@upm.es> wrote:\n\n> Hi"
        assertEquals("Vale.", HtmlText.snippet(wrapped, null))
        val html = "<div>Gracias</div><div>El mar, Ana escribió:</div><blockquote>Texto citado</blockquote>"
        assertEquals("Gracias", HtmlText.snippet(null, html))
    }

    @Test
    fun snippetStopsAtOutlookHeadersAndSignature() {
        val outlook = "Recibido.\n\n-----Mensaje original-----\nDe: Bea\nEnviado: lunes\nAsunto: x"
        assertEquals("Recibido.", HtmlText.snippet(outlook, null))
        val outlookHeaders = "Ok\n________________________________\nFrom: Bea\nSent: Monday"
        assertEquals("Ok", HtmlText.snippet(outlookHeaders, null))
        val fromBlock = "De acuerdo\nDe: Bea Gómez <bea@upm.es>\nEnviado el: lunes\nPara: Ana"
        assertEquals("De acuerdo", HtmlText.snippet(fromBlock, null))
        assertEquals("Texto", HtmlText.snippet("Texto\n-- \nAna\nUPM", null))
    }

    @Test
    fun snippetOfOnlyQuotedTextFallsBackToIt() {
        assertEquals("solo cita", HtmlText.snippet("> solo cita", null))
    }

    @Test
    fun snippetIsTruncatedAndInvisibleCharsRemoved() {
        val long = "palabra ".repeat(50)
        val snippet = HtmlText.snippet(long, null, maxLength = 30)
        assertTrue(snippet.length <= 30)
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.startsWith("palabra palabra"))
        assertEquals("Hola", HtmlText.snippet("​‌Ho­la﻿", null))
        assertEquals("", HtmlText.snippet("x", null, maxLength = 0))
    }
}
