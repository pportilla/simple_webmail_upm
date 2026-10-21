package io.github.pportilla.upmmail.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

class MimeParserTest {
    @Before
    fun init() = MailSessions.ensureInitialized()

    private fun message(vararg chunks: Any): MimeMessage {
        val bytes = ByteArrayOutputStream()
        for (chunk in chunks) {
            when (chunk) {
                is String -> bytes.write(chunk.replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
                is ByteArray -> bytes.write(chunk)
            }
        }
        return MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bytes.toByteArray()))
    }

    private val headers = "From: a@upm.es\nTo: b@upm.es\nSubject: test\nMIME-Version: 1.0\n"

    @Test
    fun singlePartBodyIsPartOne() {
        val msg = message(headers, "Content-Type: text/plain; charset=utf-8\n\nHola, ¿qué tal?\n")
        val body = MimeParser.parse(msg)
        assertEquals("Hola, ¿qué tal?\n", body.text)
        assertNull(body.html)
        assertTrue(body.attachments.isEmpty())
        assertFalse(MimeParser.hasAttachments(msg))
        assertTrue(MimeParser.findPart(msg, "1") === msg)
        assertNull(MimeParser.findPart(msg, "2"))
        assertNull(MimeParser.findPart(msg, "1.1"))
        assertNull(MimeParser.findPart(msg, "x"))
    }

    @Test
    fun missingContentTypeIsPlainText() {
        val body = MimeParser.parse(message(headers, "\nSolo texto\n"))
        assertEquals("Solo texto\n", body.text)
    }

    @Test
    fun charsetFallbacks() {
        val utf8InUnknown = message(headers, "Content-Type: text/plain; charset=x-unknown-thing\n\n", "Año ñandú".toByteArray(Charsets.UTF_8))
        assertEquals("Año ñandú", MimeParser.parse(utf8InUnknown).text)

        val latinInUnknown = message(headers, "Content-Type: text/plain; charset=bogus\n\n", "Año ñandú".toByteArray(Charsets.ISO_8859_1))
        assertEquals("Año ñandú", MimeParser.parse(latinInUnknown).text)

        val utf8LabelledAscii = message(headers, "Content-Type: text/plain; charset=us-ascii\n\n", "Camión".toByteArray(Charsets.UTF_8))
        assertEquals("Camión", MimeParser.parse(utf8LabelledAscii).text)

        val windows = message(headers, "Content-Type: text/plain; charset=\"windows-1252\"\n\n", intArrayOf(0x93, 0x41, 0x94).map { it.toByte() }.toByteArray())
        assertEquals("“A”", MimeParser.parse(windows).text)

        val htmlMeta = message(
            headers,
            "Content-Type: text/html\n\n<html><head><meta charset=\"iso-8859-1\"></head><body>",
            "Información".toByteArray(Charsets.ISO_8859_1),
            "</body></html>",
        )
        assertTrue(MimeParser.parse(htmlMeta).html!!.contains("Información"))
    }

    @Test
    fun brokenEncodingsDoNotFail() {
        val badBase64 = message(headers, "Content-Type: text/plain; charset=utf-8\nContent-Transfer-Encoding: base64\n\nSG9sYQ==!!!*\n")
        assertTrue(MimeParser.parse(badBase64).text!!.startsWith("Hola"))
        val unknownEncoding = message(headers, "Content-Type: text/plain; charset=utf-8\nContent-Transfer-Encoding: 8bits\n\nTexto\n")
        assertEquals("Texto\n", MimeParser.parse(unknownEncoding).text)
        val qp = message(headers, "Content-Type: text/plain; charset=utf-8\nContent-Transfer-Encoding: quoted-printable\n\nCami=C3=B3n=\n largo=ZZ\n")
        assertTrue(MimeParser.parse(qp).text!!.startsWith("Camión largo"))
    }

    @Test
    fun alternativeWithCalendarAndRelated() {
        val msg = message(
            headers,
            """Content-Type: multipart/alternative; boundary="alt"

--alt
Content-Type: text/plain; charset=utf-8

Invitación
--alt
Content-Type: multipart/related; boundary="rel"; type="text/html"

--rel
Content-Type: text/html; charset=utf-8

<p>Invitación <img src="cid:img1@x"></p>
--rel
Content-Type: image/gif
Content-ID: <img1@x>
Content-Transfer-Encoding: base64

R0lGODlhAQABAAAAACw=
--rel
Content-Type: image/png
Content-ID: <unused@x>
Content-Transfer-Encoding: base64

iVBORw0KGgo=
--rel--
--alt
Content-Type: text/calendar; method=REQUEST; charset=utf-8

BEGIN:VCALENDAR
END:VCALENDAR
--alt--
""",
        )
        val body = MimeParser.parse(msg)
        assertEquals("Invitación", body.text?.trim())
        assertTrue(body.html!!.contains("cid:img1@x"))
        val byPath = body.attachments.associateBy { it.partPath }
        assertEquals(setOf("2.2", "2.3", "3"), byPath.keys)
        assertTrue(byPath.getValue("2.2").isInline)
        assertEquals("img1@x", byPath.getValue("2.2").contentId)
        assertFalse("unreferenced inline images become attachments", byPath.getValue("2.3").isInline)
        assertEquals("attachment-2-3.png", byPath.getValue("2.3").fileName)
        assertEquals("attachment-3.ics", byPath.getValue("3").fileName)
        assertEquals("text/calendar", byPath.getValue("3").mimeType)
        assertTrue(MimeParser.hasAttachments(msg))
        assertNotNull(MimeParser.findPart(msg, "2.2"))
        assertNull(MimeParser.findPart(msg, "2.4"))
        assertNull(MimeParser.findPart(msg, "4"))
    }

    @Test
    fun inlineTextPartsAreConcatenatedAroundImages() {
        val msg = message(
            headers,
            """Content-Type: multipart/mixed; boundary="b"

--b
Content-Type: text/plain; charset=utf-8

Antes de la foto
--b
Content-Type: image/jpeg; name="foto.jpg"
Content-Disposition: inline; filename="foto.jpg"
Content-Transfer-Encoding: base64

/9j/4AAQ
--b
Content-Type: text/plain; charset=utf-8

Después de la foto
--b--
""",
        )
        val body = MimeParser.parse(msg)
        assertEquals("Antes de la foto\nDespués de la foto", body.text)
        val photo = body.attachments.single()
        assertEquals("foto.jpg", photo.fileName)
        assertFalse(photo.isInline)
        assertTrue(MimeParser.hasAttachments(msg))
    }

    @Test
    fun mixedHtmlAndTextAreMergedIntoHtml() {
        val msg = message(
            headers,
            """Content-Type: multipart/mixed; boundary="b"

--b
Content-Type: text/html; charset=utf-8

<p>Cuerpo</p>
--b
Content-Type: text/plain; charset=utf-8

Pie de lista <info@lists.upm.es>
--b--
""",
        )
        val body = MimeParser.parse(msg)
        assertTrue(body.html!!.contains("<p>Cuerpo</p>"))
        assertTrue(body.html!!.contains("Pie de lista &lt;"))
        assertEquals("Pie de lista <info@lists.upm.es>", body.text?.trim())
    }

    @Test
    fun encodedFileNamesSignaturesAndMissingBoundary() {
        val msg = message(
            headers,
            """Content-Type: multipart/signed; protocol="application/pkcs7-signature"; boundary="s"

--s
Content-Type: multipart/mixed; boundary="m"

--m
Content-Type: text/plain

Firmado
--m
Content-Type: application/pdf
Content-Disposition: attachment; filename*=UTF-8''Gu%C3%ADa%20docente.pdf

JVBERi0=
--m
Content-Type: application/octet-stream; name="=?UTF-8?Q?Acta_reuni=C3=B3n.docx?="
Content-Disposition: attachment

UEsDBA==
--m
Content-Type: application/pdf

JVBERi0=
--m--
--s
Content-Type: application/pkcs7-signature; name=smime.p7s
Content-Disposition: attachment; filename=smime.p7s

MIAGCSqGSIb3DQEHAqCAMIACAQEx
--s--
""",
        )
        val body = MimeParser.parse(msg)
        assertEquals("Firmado", body.text?.trim())
        assertEquals(
            listOf("Guía docente.pdf", "Acta reunión.docx", "attachment-1-4.pdf", "smime.p7s"),
            body.attachments.map { it.fileName },
        )
        assertEquals(listOf("1.2", "1.3", "1.4", "2"), body.attachments.map { it.partPath })

        val signedOnly = message(
            headers,
            """Content-Type: multipart/signed; boundary="s"

--s
Content-Type: text/plain

Solo texto firmado
--s
Content-Type: application/pkcs7-signature; name=smime.p7s

MIAGCSqGSIb3DQEHAqCAMIACAQEx
--s--
""",
        )
        assertFalse("a detached signature is not an attachment", MimeParser.hasAttachments(signedOnly))

        val noBoundary = message(
            headers,
            """Content-Type: multipart/mixed

--zzz
Content-Type: text/plain

Sin boundary
--zzz--
""",
        )
        assertEquals("Sin boundary", MimeParser.parse(noBoundary).text?.trim())
    }

    @Test
    fun nestedMessageIsAnEmlAttachment() {
        val msg = message(
            headers,
            """Content-Type: multipart/mixed; boundary="b"

--b
Content-Type: text/plain

Reenvío
--b
Content-Type: message/rfc822

From: c@upm.es
Subject: =?UTF-8?Q?Acta/final:_versi=C3=B3n?=
Content-Type: text/plain

Contenido interno
--b--
""",
        )
        val body = MimeParser.parse(msg)
        assertEquals("Reenvío", body.text?.trim())
        val eml = body.attachments.single()
        assertEquals("Acta_final_ versión.eml", eml.fileName)
        assertEquals("message/rfc822", eml.mimeType)
        val out = ByteArrayOutputStream()
        MimeParser.writeContent(MimeParser.findPart(msg, "2")!!, out) {}
        assertTrue(String(out.toByteArray()).contains("Subject: =?UTF-8?Q?Acta/final:_versi=C3=B3n?="))
    }

    @Test
    fun sanitizesFileNames() {
        assertEquals("a_b_c.pdf", MimeParser.sanitizeFileName("a/b\\c.pdf"))
        assertNull(MimeParser.sanitizeFileName(" .. "))
        assertEquals(120, MimeParser.sanitizeFileName("x".repeat(300) + ".pdf")!!.length)
        assertTrue(MimeParser.sanitizeFileName("x".repeat(300) + ".pdf")!!.endsWith(".pdf"))
    }
}
