package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties

class MessageComposerTest {
    private val from = EmailAddress("ana.perez@upm.es", "Ana Pérez")

    private class Source(
        override val fileName: String,
        override val mimeType: String,
        private val bytes: ByteArray,
    ) : AttachmentSource {
        var opened = 0

        override fun openStream(): InputStream {
            opened++
            return ByteArrayInputStream(bytes)
        }
    }

    @Before
    fun init() = MailSessions.ensureInitialized()

    private fun roundTrip(message: MimeMessage): MimeMessage {
        val out = ByteArrayOutputStream()
        message.writeTo(out)
        return MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun buildsHeadersForAReply() {
        val draft = Draft(
            accountId = 1,
            to = listOf(EmailAddress("bea@alumnos.upm.es", "Beatriz Gómez"), EmailAddress("bea@alumnos.upm.es")),
            cc = listOf(EmailAddress("carlos@upm.es")),
            bcc = listOf(EmailAddress("oculto@upm.es")),
            subject = "Re: Revisión\r\ndel TFG ✓",
            body = "Hola,\n\n> cita",
            inReplyTo = "<orig@upm.es>",
            references = (1..12).map { "<reference-number-$it@mail.upm.es>" },
        )
        val composed = MessageComposer.build(from, draft, emptyList(), date = 1_700_000_000_000L, userAgent = "UPM Mail 1.0")
        assertTrue(composed.messageId.matches(Regex("<[0-9a-f\\-]{36}@upm\\.es>")))
        assertEquals(composed.messageId, composed.mime.messageID)

        val parsed = roundTrip(composed.mime)
        assertEquals(composed.messageId, parsed.messageID)
        val sender = parsed.from.single() as InternetAddress
        assertEquals("Ana Pérez", sender.personal)
        assertEquals("ana.perez@upm.es", sender.address)
        val to = parsed.getRecipients(Message.RecipientType.TO).map { it as InternetAddress }
        assertEquals(listOf("bea@alumnos.upm.es"), to.map { it.address })
        assertEquals("Beatriz Gómez", to.single().personal)
        assertEquals("carlos@upm.es", (parsed.getRecipients(Message.RecipientType.CC).single() as InternetAddress).address)
        assertEquals("oculto@upm.es", (parsed.getRecipients(Message.RecipientType.BCC).single() as InternetAddress).address)
        assertEquals("Re: Revisión del TFG ✓", parsed.subject)
        assertEquals(1_700_000_000_000L, parsed.sentDate.time)
        assertEquals("<orig@upm.es>", parsed.getHeader("In-Reply-To", null))
        assertEquals(draft.references, HeaderValues.messageIds(parsed.getHeader("References", null)))
        assertTrue(composed.mime.getHeader("References", null).contains("\r\n "))
        assertEquals("UPM Mail 1.0", parsed.getHeader("User-Agent", null))
    }

    @Test
    fun bodyIsAlternativeOfPlainAndGeneratedHtml() {
        val composed = MessageComposer.build(
            from,
            Draft(accountId = 1, to = listOf(EmailAddress("b@upm.es")), subject = "Hola", body = "Línea 1\r\nVer https://www.upm.es\n> cita"),
            emptyList(),
        )
        val parsed = roundTrip(composed.mime)
        assertTrue(parsed.isMimeType("multipart/alternative"))
        val alternative = parsed.content as Multipart
        assertEquals(2, alternative.count)
        val plain = alternative.getBodyPart(0)
        val html = alternative.getBodyPart(1)
        assertTrue(plain.isMimeType("text/plain"))
        assertTrue(plain.contentType.contains("UTF-8", ignoreCase = true))
        assertEquals("quoted-printable", plain.getHeader("Content-Transfer-Encoding").single())
        assertEquals("Línea 1\nVer https://www.upm.es\n> cita", (plain.content as String).replace("\r\n", "\n"))
        assertTrue(html.isMimeType("text/html"))
        val htmlText = html.content as String
        assertTrue(htmlText.contains("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">"))
        assertTrue(htmlText.contains("<a href=\"https://www.upm.es\">"))
        assertTrue(htmlText.contains("<blockquote"))
        assertTrue(htmlText.contains("Línea 1<br>"))
    }

    @Test
    fun attachmentsAreWrappedInMixedWithEncodedNames() {
        val pdfBytes = ByteArray(5000) { (it % 251).toByte() }
        val pdf = Source("Memoria año 2025.pdf", "application/pdf", pdfBytes)
        val txt = Source("notes.txt", "TEXT/PLAIN", "plain notes".toByteArray())
        val composed = MessageComposer.build(
            from,
            Draft(accountId = 1, to = listOf(EmailAddress("b@upm.es")), subject = "Adjuntos", body = "Ver adjuntos"),
            listOf(pdf, txt),
        )
        val first = ByteArrayOutputStream().also { composed.mime.writeTo(it) }.toByteArray()
        val second = ByteArrayOutputStream().also { composed.mime.writeTo(it) }.toByteArray()
        assertArrayEquals("the message can be written twice", first, second)
        assertTrue(pdf.opened >= 2)

        val raw = String(first, Charsets.ISO_8859_1)
        assertTrue(raw, raw.contains("filename*=UTF-8''Memoria%20a%C3%B1o%202025.pdf"))
        assertTrue(raw, raw.contains("name=\"=?UTF-8?B?"))

        val parsed = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(first))
        assertTrue(parsed.isMimeType("multipart/mixed"))
        val mixed = parsed.content as Multipart
        assertEquals(3, mixed.count)
        assertTrue(mixed.getBodyPart(0).isMimeType("multipart/alternative"))
        val attachment = mixed.getBodyPart(1)
        assertEquals(Part.ATTACHMENT, attachment.disposition)
        assertEquals("Memoria año 2025.pdf", attachment.fileName)
        assertEquals("base64", attachment.getHeader("Content-Transfer-Encoding").single())
        assertArrayEquals(pdfBytes, attachment.inputStream.readBytes())
        val notes = mixed.getBodyPart(2)
        assertTrue(notes.isMimeType("text/plain"))
        assertEquals("notes.txt", notes.fileName)
        assertEquals("plain notes", String(notes.inputStream.readBytes()))

        val body = MimeParser.parse(parsed)
        assertEquals("Ver adjuntos", body.text)
        assertEquals(listOf("2", "3"), body.attachments.map { it.partPath })
        assertEquals("Memoria año 2025.pdf", body.attachments[0].fileName)
    }

    @Test
    fun longNonAsciiAttachmentNamesKeepLinesShort() {
        // 115 CJK characters: 1035 characters once percent-encoded, beyond SMTP's 998 per line.
        val name = "\u4F1A\u8BAE\u8BB0\u5F55".repeat(29).take(115) + ".pdf"
        val emoji = "Informe \uD83D\uDCCA ".repeat(8).trim() + ".xlsx"
        val composed = MessageComposer.build(
            from,
            Draft(accountId = 1, to = listOf(EmailAddress("b@upm.es")), subject = "Adjunto", body = "Ver adjunto"),
            listOf(Source(name, "application/pdf", byteArrayOf(1, 2, 3)), Source(emoji, "application/octet-stream", byteArrayOf(4))),
        )
        val bytes = ByteArrayOutputStream().also { composed.mime.writeTo(it) }.toByteArray()
        val raw = String(bytes, Charsets.ISO_8859_1)
        val longest = raw.split("\r\n").maxByOrNull { it.length }!!
        assertTrue(longest, longest.length <= 78)
        assertTrue(raw, raw.contains("filename*0*=UTF-8''%E4%BC%9A"))
        assertTrue(raw, raw.contains("filename*1*="))

        val parsed = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bytes))
        val mixed = parsed.content as Multipart
        assertEquals(name, mixed.getBodyPart(1).fileName)
        assertEquals(emoji, mixed.getBodyPart(2).fileName)
        assertEquals(listOf(name, emoji), MimeParser.parse(parsed).attachments.map { it.fileName })
    }

    @Test
    fun messageIdDomainFallsBack() {
        assertTrue(MessageComposer.newMessageId("no-at-sign").endsWith("@localhost.localdomain>"))
        assertTrue(MessageComposer.newMessageId("x@UPM.ES").endsWith("@upm.es>"))
        val composed = MessageComposer.build(from, Draft(accountId = 1, subject = ""), emptyList())
        assertEquals("", composed.mime.subject ?: "")
        assertNull(composed.mime.getHeader("In-Reply-To"))
        assertNull(composed.mime.getHeader("References"))
    }
}
