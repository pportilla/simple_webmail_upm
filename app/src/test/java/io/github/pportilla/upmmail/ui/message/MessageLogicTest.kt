package io.github.pportilla.upmmail.ui.message

import io.github.pportilla.upmmail.mail.HtmlSanitizer
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MessageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLogicTest {

    // ------------------------------------------------------------------ mailto

    @Test
    fun `mailto with only an address`() {
        assertEquals(MailtoLink(to = listOf("ana@upm.es")), parseMailto("mailto:ana@upm.es"))
    }

    @Test
    fun `mailto scheme is case insensitive and other schemes are rejected`() {
        assertEquals(listOf("ana@upm.es"), parseMailto("MAILTO:ana@upm.es")?.to)
        assertNull(parseMailto("https://upm.es"))
        assertNull(parseMailto("ana@upm.es"))
    }

    @Test
    fun `mailto headers are decoded as UTF-8 and plus stays a plus`() {
        val link = parseMailto(
            "mailto:ana@upm.es,luis%2Btag@upm.es?subject=Reuni%C3%B3n%20de%20m%C3%A1ster+1&body=Hola%0D%0Aadi%C3%B3s" +
                "&cc=jefe@upm.es&bcc=a@x.es;b@x.es&to=extra@upm.es",
        )!!
        assertEquals(listOf("ana@upm.es", "luis+tag@upm.es", "extra@upm.es"), link.to)
        assertEquals(listOf("jefe@upm.es"), link.cc)
        assertEquals(listOf("a@x.es", "b@x.es"), link.bcc)
        assertEquals("Reunión de máster+1", link.subject)
        assertEquals("Hola\nadiós", link.body)
    }

    @Test
    fun `mailto without address keeps the headers`() {
        val link = parseMailto("mailto:?to=ana@upm.es&SUBJECT=Hi&subject=ignored")!!
        assertEquals(listOf("ana@upm.es"), link.to)
        assertEquals("Hi", link.subject)
    }

    @Test
    fun `malformed escapes are kept literally`() {
        assertEquals("100% sure", parseMailto("mailto:a@b.es?subject=100%25 sure")!!.subject)
        assertEquals("50%", parseMailto("mailto:a@b.es?subject=50%")!!.subject)
        assertEquals("%zz", parseMailto("mailto:a@b.es?subject=%zz")!!.subject)
    }

    // ------------------------------------------------------------------ List-Unsubscribe

    @Test
    fun `list unsubscribe prefers https then mailto then http`() {
        val header = "<mailto:unsub@lists.upm.es?subject=unsubscribe>, <https://lists.upm.es/u?id=1>"
        assertEquals(UnsubscribeTarget.Web("https://lists.upm.es/u?id=1"), chooseUnsubscribeTarget(header))

        val mailOnly = chooseUnsubscribeTarget("<http://x.es/u>, <mailto:unsub@x.es?subject=baja>")
        assertTrue(mailOnly is UnsubscribeTarget.Mail)
        mailOnly as UnsubscribeTarget.Mail
        assertEquals(listOf("unsub@x.es"), mailOnly.link.to)
        assertEquals("baja", mailOnly.link.subject)

        assertEquals(UnsubscribeTarget.Web("http://x.es/u"), chooseUnsubscribeTarget("<http://x.es/u>"))
    }

    @Test
    fun `list unsubscribe tolerates folded headers and missing brackets`() {
        assertEquals(listOf("https://x.es/a?b=c"), parseListUnsubscribe("<https://x.es/a?\r\n b=c>"))
        assertEquals(listOf("mailto:a@x.es", "https://x.es"), parseListUnsubscribe("mailto:a@x.es, https://x.es"))
        assertNull(chooseUnsubscribeTarget(null))
        assertNull(chooseUnsubscribeTarget("   "))
        assertNull(chooseUnsubscribeTarget("<ftp://x.es>"))
        assertNull(chooseUnsubscribeTarget("<mailto:>"))
    }

    // ------------------------------------------------------------------ attachments

    @Test
    fun `attachment kinds from mime type or extension`() {
        assertEquals(AttachmentKind.PDF, attachmentKind("application/pdf", "x"))
        assertEquals(AttachmentKind.PDF, attachmentKind("application/octet-stream", "Acta.PDF"))
        assertEquals(AttachmentKind.IMAGE, attachmentKind("image/jpeg", "foto"))
        assertEquals(AttachmentKind.IMAGE, attachmentKind("application/octet-stream", "foto.heic"))
        assertEquals(AttachmentKind.DOCUMENT, attachmentKind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a"))
        assertEquals(AttachmentKind.DOCUMENT, attachmentKind("", "notas.odt"))
        assertEquals(AttachmentKind.SPREADSHEET, attachmentKind("application/vnd.ms-excel", "a"))
        assertEquals(AttachmentKind.SPREADSHEET, attachmentKind("text/csv; charset=utf-8", "a"))
        assertEquals(AttachmentKind.PRESENTATION, attachmentKind("application/vnd.openxmlformats-officedocument.presentationml.presentation", "a"))
        assertEquals(AttachmentKind.PRESENTATION, attachmentKind("application/octet-stream", "clase.key"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKind("application/zip", "a"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKind("application/octet-stream", "codigo.tar.gz"))
        assertEquals(AttachmentKind.AUDIO, attachmentKind("audio/mpeg", "a"))
        assertEquals(AttachmentKind.VIDEO, attachmentKind("video/mp4", "a"))
        assertEquals(AttachmentKind.CALENDAR, attachmentKind("text/calendar", "invite"))
        assertEquals(AttachmentKind.CALENDAR, attachmentKind("application/octet-stream", "invite.ics"))
        assertEquals(AttachmentKind.GENERIC, attachmentKind("application/octet-stream", "datos.bin"))
    }

    @Test
    fun `referenced content ids are normalised`() {
        val html = """<img src="cid:Logo@UPM"><img src='CID:%3Cfoto%2B1@x%3E'><div style="background:url(cid:bg.png)">"""
        assertEquals(setOf("logo@upm", "foto+1@x", "bg.png"), referencedContentIds(html))
        assertEquals(emptySet<String>(), referencedContentIds("<p>no images</p>"))
        assertEquals(emptySet<String>(), referencedContentIds(null))
        assertEquals("logo@upm", normalizeContentId(" <Logo@UPM> "))
    }

    @Test
    fun `inline image mime falls back to the extension`() {
        assertEquals("image/png", inlineImageMime("image/png", "x"))
        assertEquals("image/jpeg", inlineImageMime("application/octet-stream", "foto.JPG"))
        assertEquals("image/gif", inlineImageMime("", "anim.gif"))
        assertEquals("application/octet-stream", inlineImageMime("application/octet-stream", "blob"))
    }

    // ------------------------------------------------------------------ expansion and rows

    @Test
    fun `opened and unread messages start expanded`() {
        val messages = listOf(summary(1, read = true), summary(2, read = false), summary(3, read = true), summary(4, read = true))
        assertEquals(setOf(3L, 2L), initialExpandedIds(messages, openedId = 3))
        assertEquals(setOf(4L, 2L), initialExpandedIds(messages, openedId = 99))
        assertEquals(emptySet<Long>(), initialExpandedIds(emptyList(), 1))
    }

    @Test
    fun `short threads show every message`() {
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(ids.map { ThreadRow.Message(it) }, threadRows(ids, expanded = setOf(5L), revealAll = false))
    }

    @Test
    fun `long runs of collapsed messages are folded`() {
        val ids = (1L..10L).toList()
        val rows = threadRows(ids, expanded = setOf(10L), revealAll = false)
        assertEquals(
            listOf(
                ThreadRow.Message(1),
                ThreadRow.Hidden(listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L)),
                ThreadRow.Message(9),
                ThreadRow.Message(10),
            ),
            rows,
        )
        assertEquals(ids.map { ThreadRow.Message(it) }, threadRows(ids, expanded = setOf(10L), revealAll = true))
    }

    @Test
    fun `expanded messages split the folded runs`() {
        val ids = (1L..12L).toList()
        val rows = threadRows(ids, expanded = setOf(4L, 12L), revealAll = false)
        assertEquals(
            listOf(
                ThreadRow.Message(1),
                ThreadRow.Message(2),
                ThreadRow.Message(3),
                ThreadRow.Message(4),
                ThreadRow.Hidden(listOf(5L, 6L, 7L, 8L, 9L, 10L)),
                ThreadRow.Message(11),
                ThreadRow.Message(12),
            ),
            rows,
        )
        // Every message appears exactly once.
        val flattened = rows.flatMap { if (it is ThreadRow.Hidden) it.ids else listOf((it as ThreadRow.Message).id) }
        assertEquals(ids, flattened)
    }

    // ------------------------------------------------------------------ recipients

    @Test
    fun `short names use the first name or the local part`() {
        assertEquals("Ana", shortName(EmailAddress("ana@upm.es", "Ana Pérez García")))
        assertEquals("Ana", shortName(EmailAddress("ana@upm.es", "Pérez García, Ana")))
        assertEquals("Ana", shortName(EmailAddress("ana@upm.es", "\"Ana\"")))
        assertEquals("luis.gomez", shortName(EmailAddress("luis.gomez@upm.es", null)))
        assertEquals("luis.gomez", shortName(EmailAddress("luis.gomez@upm.es", "luis.gomez@upm.es")))
    }

    @Test
    fun `recipient summary puts me first and counts the rest`() {
        val summary = recipientSummary(
            to = listOf(EmailAddress("ana@upm.es", "Ana Pérez"), EmailAddress("Yo@UPM.es", "Yo")),
            cc = listOf(EmailAddress("luis@upm.es"), EmailAddress("ana@UPM.es", "Ana again")),
            bcc = listOf(EmailAddress("eva@upm.es", "Eva"), EmailAddress("pablo@upm.es", "Pablo")),
            selfAddresses = setOf("yo@upm.es"),
            meLabel = "me",
        )
        assertEquals(listOf("me", "Ana", "luis"), summary.names)
        assertEquals(2, summary.more)
        assertTrue(recipientSummary(emptyList(), emptyList(), emptyList(), emptySet(), "me").isEmpty)
    }

    @Test
    fun `reply all is useful only with other recipients`() {
        val me = setOf("yo@upm.es")
        val ana = EmailAddress("ana@upm.es", "Ana")
        assertFalse(replyAllAddsRecipients(ana, listOf(EmailAddress("yo@upm.es")), emptyList(), emptyList(), me))
        assertTrue(replyAllAddsRecipients(ana, listOf(EmailAddress("yo@upm.es")), listOf(EmailAddress("luis@upm.es")), emptyList(), me))
    }

    // ------------------------------------------------------------------ documents

    @Test
    fun `wide content fix matches the sanitiser output`() {
        val sanitized = HtmlSanitizer.sanitize("<table width=600><tr><td>Hola</td></tr></table>")
        val fitted = fitWideContent(sanitized)
        assertTrue(fitted.contains("content=\"width=device-width\""))
        assertFalse(fitted.contains("initial-scale=1"))
        assertTrue(fitted.contains("Hola"))
    }

    @Test
    fun `plain text document uses the given colours`() {
        val document = plainTextDocument(
            HtmlSanitizer.plainTextToHtml("Hola <b>\n> cita"),
            PlainTextColors(text = "#111111", link = "#222222", quote = "#333333", quoteBorder = "#444444"),
        )
        assertTrue(document.startsWith("<!DOCTYPE html>"))
        assertTrue(document.contains("color:#111111"))
        assertTrue(document.contains("a{color:#222222;}"))
        assertTrue(document.contains("Hola &lt;b&gt;"))
        assertTrue(document.contains("<blockquote"))
    }

    @Test
    fun `print document adds an escaped header`() {
        val html = PrintDocument.build(
            body = HtmlSanitizer.sanitize("<p>Cuerpo</p>"),
            isHtml = true,
            subject = "Notas <finales>",
            header = listOf(
                PrintDocument.HeaderLine("De", "Ana <ana@upm.es>"),
                PrintDocument.HeaderLine("Cc", ""),
            ),
        )
        assertTrue(html.contains("Notas &lt;finales&gt;"))
        assertTrue(html.contains("Ana &lt;ana@upm.es&gt;"))
        assertFalse(html.contains("<b>Cc: </b>"))
        val headerAt = html.indexOf("<div class=\"upm-print-header\">")
        assertTrue(headerAt >= 0 && headerAt < html.indexOf("Cuerpo"))

        val plain = PrintDocument.build(HtmlSanitizer.plainTextToHtml("línea 1\nlínea 2"), false, "S", emptyList())
        assertTrue(plain.contains("línea 1<br>"))
        assertTrue(plain.contains("<h1>S</h1>"))
    }

    private fun summary(id: Long, read: Boolean) = MessageSummary(
        id = id,
        accountId = 1,
        folderId = 1,
        uid = id,
        threadId = "t",
        subject = "s",
        from = null,
        to = emptyList(),
        date = id,
        snippet = "",
        isRead = read,
        isStarred = false,
        isAnswered = false,
        isForwarded = false,
        isDraft = false,
        hasAttachments = false,
    )
}
