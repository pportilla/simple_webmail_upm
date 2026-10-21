package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

class SmtpClientTest : GreenMailTestBase() {
    private val bea = "bea@alumnos.upm.es"
    private val hidden = "oculto@upm.es"

    private class BytesSource(
        override val fileName: String,
        override val mimeType: String,
        private val bytes: ByteArray,
    ) : AttachmentSource {
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    @Test
    fun verifyAcceptsRightPassword() = runBlocking {
        SmtpClient(smtpSettings()) { password }.verify()
    }

    @Test
    fun verifyRejectsWrongPassword() = runBlocking {
        try {
            SmtpClient(smtpSettings()) { "wrong" }.verify()
            fail("expected an authentication failure")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.AUTHENTICATION, e.kind)
        }
    }

    @Test
    fun sendDeliversUtf8MessageWithAttachmentsAndHiddenBcc() = runBlocking {
        greenMail.setUser(bea, bea, "x")
        greenMail.setUser(hidden, hidden, "x")
        val pdf = Random(3).nextBytes(100_000)
        val composed = MessageComposer.build(
            from = EmailAddress(email, "Ana Pérez Núñez"),
            draft = Draft(
                accountId = 1,
                to = listOf(EmailAddress(bea, "Beatriz Gómez")),
                bcc = listOf(EmailAddress(hidden)),
                subject = "Revisión del TFG — versión final ✓",
                body = "Hola Bea,\n\nTe adjunto la memoria.\n\n> cita anterior\n",
            ),
            attachments = listOf(BytesSource("memoria final ñ.pdf", "application/pdf", pdf)),
        )
        SmtpClient(smtpSettings()) { password }.send(composed.mime)

        assertTrue(greenMail.waitForIncomingEmail(5_000, 2))
        assertEquals(2, greenMail.receivedMessages.size)
        val toCopy = inboxOf(bea).single()
        assertEquals("Revisión del TFG — versión final ✓", toCopy.subject)
        val from = toCopy.from.single() as jakarta.mail.internet.InternetAddress
        assertEquals("Ana Pérez Núñez", from.personal)
        assertEquals(email, from.address)
        assertEquals(composed.messageId, toCopy.messageID)
        assertNull("Bcc must not be visible to the recipients", toCopy.getHeader("Bcc"))
        assertEquals(composed.messageId, inboxOf(hidden).single().messageID)

        val parts = leaves(toCopy)
        val text = parts.first { it.isMimeType("text/plain") && it.fileName == null }
        assertEquals("Hola Bea,\n\nTe adjunto la memoria.\n\n> cita anterior\n", (text.content as String).replace("\r\n", "\n"))
        assertTrue(parts.any { it.isMimeType("text/html") })
        val attachment = parts.single { it.fileName != null }
        assertEquals("memoria final ñ.pdf", attachment.fileName)
        assertArrayEquals(pdf, attachment.inputStream.readBytes())
    }

    @Test
    fun sendWithoutRecipientsIsInvalidRecipient() = runBlocking {
        val composed = MessageComposer.build(EmailAddress(email), Draft(accountId = 1, subject = "x"), emptyList())
        try {
            SmtpClient(smtpSettings()) { password }.send(composed.mime)
            fail("expected INVALID_RECIPIENT")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.INVALID_RECIPIENT, e.kind)
        }
    }

    @Test
    fun unreachableServerIsNetworkError() = runBlocking {
        val settings = smtpSettings()
        greenMail.stop()
        try {
            SmtpClient(settings) { password }.verify()
            fail("expected NETWORK")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.NETWORK, e.kind)
        } finally {
            greenMail.start()
        }
    }

    /** The copies GreenMail delivered to the mailbox of [address]. */
    private fun inboxOf(address: String): List<MimeMessage> {
        val user = greenMail.userManager.getUserByEmail(address) ?: return emptyList()
        return greenMail.managers.imapHostManager.getInbox(user).messages.map { it.mimeMessage }
    }

    private fun leaves(part: Part): List<Part> {
        val content = part.content
        return if (content is Multipart) {
            (0 until content.count).flatMap { leaves(content.getBodyPart(it)) }
        } else {
            listOf(part)
        }
    }
}
