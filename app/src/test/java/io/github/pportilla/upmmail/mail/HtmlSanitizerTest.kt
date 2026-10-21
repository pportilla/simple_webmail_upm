package io.github.pportilla.upmmail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSanitizerTest {
    private fun clean(html: String) = HtmlSanitizer.sanitize(html).lowercase()

    @Test
    fun producesCompleteDocumentWithDefaults() {
        val result = HtmlSanitizer.sanitize("<p>Hola</p>")
        assertTrue(result.startsWith("<!doctype html>", ignoreCase = true))
        assertTrue(result.contains("<meta charset=\"utf-8\">"))
        assertTrue(result.contains("name=\"viewport\""))
        assertTrue(result.contains("img{max-width:100%;height:auto;}"))
        assertTrue(result.contains("pre{white-space:pre-wrap;}"))
        assertTrue(result.contains("<p>Hola</p>"))
    }

    @Test
    fun removesActiveContent() {
        val vectors = listOf(
            "<script>alert(1)</script>",
            "<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
            "<noscript><p>x</p></noscript>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<frameset><frame src=x></frameset>",
            "<object data=x.swf></object>",
            "<embed src=x.swf>",
            "<applet code=x></applet>",
            "<input type=text value=x>",
            "<button onclick=alert(1)>b</button>",
            "<textarea>t</textarea>",
            "<select><option>o</option></select>",
            "<base href=\"https://evil.example/\">",
            "<link rel=stylesheet href=\"https://evil.example/x.css\">",
            "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\">",
            "<svg><script>alert(1)</script></svg>",
            "<svg><foreignObject><iframe src=x></iframe></foreignObject></svg>",
        )
        for (vector in vectors) {
            val result = clean("<div>keep</div>$vector")
            assertTrue(vector, result.contains("<div>keep</div>"))
            for (forbidden in listOf("<script", "<iframe", "<frame", "<object", "<embed", "<applet", "<input",
                "<button", "<textarea", "<select", "<base", "<link", "http-equiv", "foreignobject", "alert(1)")) {
                assertFalse("$vector -> $result", result.contains(forbidden))
            }
        }
    }

    @Test
    fun removesEventHandlersAndScriptUrls() {
        val result = clean(
            """<img src="x.png" onerror="alert(1)" ONLOAD=alert(2)>""" +
                """<a href="javascript:alert(3)">a</a>""" +
                """<a href=" JaVaScRiPt:alert(4)">b</a>""" +
                """<a href="jav&#x09;ascript:alert(5)">c</a>""" +
                """<a href="vbscript:msgbox(6)">d</a>""" +
                """<a href="data:text/html;base64,PHNjcmlwdD4=">e</a>""" +
                """<svg><a xlink:href="javascript:alert(7)"><text>f</text></a></svg>""" +
                """<body onload="alert(8)"><div style="width:expression(alert(9))">g</div></body>""",
        )
        assertFalse(result, result.contains("onerror"))
        assertFalse(result, result.contains("onload"))
        assertFalse(result, result.contains("javascript"))
        assertFalse(result, result.contains("vbscript"))
        assertFalse(result, result.contains("data:text/html"))
        assertFalse(result, result.contains("expression("))
        assertTrue(result.contains("src=\"x.png\""))
    }

    @Test
    fun keepsStylesTablesImagesAndLinks() {
        val html = """<html><head><style>.a{color:red}</style></head><body>
            |<table style="width:600px"><tr><td class="a">x</td></tr></table>
            |<img src="cid:logo@upm.es" alt="logo"><a href="https://www.upm.es">UPM</a>
            |<form action="https://evil.example"><p>Encuesta</p></form></body></html>""".trimMargin()
        val result = HtmlSanitizer.sanitize(html)
        assertTrue(result.contains(".a{color:red}"))
        assertTrue(result.contains("style=\"width:600px\""))
        assertTrue(result.contains("src=\"cid:logo@upm.es\""))
        assertTrue(result.contains("href=\"https://www.upm.es\""))
        assertTrue("form content is kept", result.contains("<p>Encuesta</p>"))
        assertFalse(result.contains("<form"))
        assertFalse(result.contains("evil.example"))
    }

    @Test
    fun plainTextIsEscapedLinkedAndQuoted() {
        val html = HtmlSanitizer.plainTextToHtml(
            "Hola <Ana> & co\nMira https://www.upm.es/x?a=1&b=2. o www.upm.es\nEscribe a ana@upm.es\n> cita\n>> doble\nfin",
        )
        assertTrue(html, html.contains("Hola &lt;Ana&gt; &amp; co<br>"))
        assertTrue(html, html.contains("<a href=\"https://www.upm.es/x?a=1&amp;b=2\">https://www.upm.es/x?a=1&amp;b=2</a>."))
        assertTrue(html, html.contains("<a href=\"http://www.upm.es\">www.upm.es</a>"))
        assertTrue(html, html.contains("<a href=\"mailto:ana@upm.es\">ana@upm.es</a>"))
        assertEquals(2, Regex("<blockquote").findAll(html).count())
        assertTrue(html, html.contains(">cita<blockquote"))
        assertTrue(html, html.contains("doble</blockquote></blockquote>fin"))
    }

    @Test
    fun plainTextKeepsSpacesAndBalancedParentheses() {
        assertEquals("a &nbsp;b", HtmlSanitizer.plainTextToHtml("a  b"))
        assertEquals("&nbsp;x", HtmlSanitizer.plainTextToHtml(" x"))
        val html = HtmlSanitizer.plainTextToHtml("(ver https://es.wikipedia.org/wiki/UPM_(universidad))")
        assertTrue(html, html.contains("href=\"https://es.wikipedia.org/wiki/UPM_(universidad)\""))
        assertTrue(html, html.endsWith("</a>)"))
    }

    @Test
    fun detectsRemoteContent() {
        assertTrue(HtmlSanitizer.hasRemoteContent("<img src=\"https://tracker.example/p.gif\">"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<img src=\"//cdn.example/p.gif\">"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<table><tr><td background=\"http://x.example/bg.png\">x</td></tr></table>"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<body background=\"http://x.example/bg.png\">x</body>"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<div style=\"background:url('https://x.example/a.png')\">"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<style>@import url(https://x.example/a.css);</style>"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<link rel=stylesheet href=https://x.example/a.css>"))
        assertTrue(HtmlSanitizer.hasRemoteContent("<img srcset=\"a.png 1x, https://x.example/b.png 2x\">"))
        assertFalse(HtmlSanitizer.hasRemoteContent("<img src=\"cid:logo\"><img src=\"data:image/png;base64,AAAA\">"))
        assertFalse(HtmlSanitizer.hasRemoteContent("<a href=\"https://www.upm.es\">link</a>"))
        assertFalse(HtmlSanitizer.hasRemoteContent(""))
    }

    @Test
    fun replacesCidReferences() {
        val html = "<img src=\"cid:logo@upm.es\"><img src='CID:Foto%201@x'><div style=\"background:url(cid:bg)\"></div><img src=\"cid:missing\">"
        val result = HtmlSanitizer.inlineImages(
            html,
            mapOf("logo@upm.es" to "data:image/png;base64,AAA", "<foto 1@x>" to "data:image/jpeg;base64,BBB", "bg" to "data:image/gif;base64,CCC"),
        )
        assertEquals(
            "<img src=\"data:image/png;base64,AAA\"><img src='data:image/jpeg;base64,BBB'>" +
                "<div style=\"background:url(data:image/gif;base64,CCC)\"></div><img src=\"cid:missing\">",
            result,
        )
        assertEquals(html, HtmlSanitizer.inlineImages(html, emptyMap()))
    }
}
