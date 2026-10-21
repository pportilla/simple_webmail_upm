package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** How SmtpClient reports the server's replies, and a connection lost at each stage. */
class SmtpRepliesTest {
    private val servers = ArrayList<ScriptedSmtpServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    /**
     * Sends a message to bea and carlos through a server whose replies are the usual ones except
     * for [overrides] (see [ScriptedSmtpServer]). Returns the failure, or null once it was sent.
     */
    private fun send(vararg overrides: Pair<String, String?>): MailException? = runBlocking {
        MailSessions.ensureInitialized()
        val server = ScriptedSmtpServer(mapOf(*overrides)).also { servers += it }
        val composed = MessageComposer.build(
            from = EmailAddress("ana@upm.es"),
            draft = Draft(
                accountId = 1,
                to = listOf(EmailAddress("bea@upm.es"), EmailAddress("carlos@upm.es")),
                subject = "Horario",
                body = "Hola",
            ),
            attachments = emptyList(),
        )
        try {
            SmtpClient(ServerSettings("127.0.0.1", server.port, SecurityMode.NONE, "ana")) { "secret" }.send(composed.mime)
            null
        } catch (e: MailException) {
            e
        }
    }

    private fun assertFailure(error: MailException?, kind: MailErrorKind, transient: Boolean) {
        if (error == null) fail("expected $kind")
        assertEquals(error!!.message, kind, error.kind)
        assertEquals(error.message, transient, error.isTransient)
    }

    @Test
    fun acceptedMessageIsSentOnce() {
        assertNull(send())
        assertEquals(1, servers.single().accepted.get())
    }

    @Test
    fun temporaryRecipientRefusalIsRetriedLater() {
        assertFailure(send("RCPT <carlos@upm.es>" to "450 4.2.0 Greylisted, try again later"), MailErrorKind.SERVER, true)
        assertFailure(send("RCPT <carlos@upm.es>" to "552 5.2.2 Mailbox full"), MailErrorKind.SERVER, true)
        assertEquals(0, servers.sumOf { it.accepted.get() })
    }

    @Test
    fun unknownRecipientIsInvalidRecipient() {
        assertFailure(send("RCPT <carlos@upm.es>" to "550 5.1.1 User unknown"), MailErrorKind.INVALID_RECIPIENT, false)
    }

    @Test
    fun tooManyRecipientsIsNotRetried() {
        assertFailure(send("RCPT <carlos@upm.es>" to "452 4.5.3 Too many recipients"), MailErrorKind.SERVER, false)
    }

    @Test
    fun temporaryRefusalOfTheSenderOrTheDataIsRetriedLater() {
        assertFailure(send("MAIL" to "421 4.7.0 Too many connections"), MailErrorKind.SERVER, true)
        assertFailure(send("DATA" to "451 4.3.0 Try again later"), MailErrorKind.SERVER, true)
        // A clear refusal of the finished message: nothing was accepted.
        assertFailure(send("END" to "451 4.7.1 Try again later"), MailErrorKind.SERVER, true)
        assertFailure(send("END" to "552 5.3.4 Message size exceeds fixed limit"), MailErrorKind.TOO_LARGE, false)
    }

    @Test
    fun temporaryAuthenticationFailureIsRetriedLater() {
        assertFailure(send("AUTH" to "454 4.7.0 Temporary authentication failure"), MailErrorKind.SERVER, true)
        assertFailure(send("AUTH" to "535 5.7.8 Authentication credentials invalid"), MailErrorKind.AUTHENTICATION, false)
    }

    @Test
    fun connectionLostBeforeTheEndOfTheDataIsRetried() {
        // The server never saw the end of the message, so it cannot have accepted it.
        assertFailure(send("RCPT <bea@upm.es>" to null), MailErrorKind.NETWORK, true)
        assertFailure(send("DATA" to null), MailErrorKind.NETWORK, true)
    }

    @Test
    fun connectionLostAfterTheEndOfTheDataIsNotRetried() {
        // The server may have queued the message before the connection broke: sending it again
        // could deliver it twice, so the user decides.
        val error = send("END" to null)
        assertFailure(error, MailErrorKind.SEND_UNCERTAIN, false)
        assertTrue(error!!.message!!.contains("may have been sent"))
        assertFalse(error.temporary)
    }

    /**
     * A minimal SMTP server. Its replies to "AUTH", "MAIL", "RCPT <address>", "DATA" and "END" (the
     * end of the data) can be overridden; an override of null resets the connection instead.
     */
    private class ScriptedSmtpServer(private val overrides: Map<String, String?>) : Closeable {
        private val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        val accepted = AtomicInteger()

        init {
            thread(isDaemon = true, name = "scripted-smtp") {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun answer(step: String, usual: String): String? = if (step in overrides) overrides[step] else usual

        private fun serve(client: Socket): Unit = client.use {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = client.getOutputStream()
            fun reply(text: String?): Boolean {
                if (text == null) {
                    client.setSoLinger(true, 0)
                    client.close()
                    return false
                }
                output.write("$text\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                return true
            }
            try {
                reply("220 fake.upm.es ESMTP")
                while (true) {
                    val line = input.readLine() ?: return
                    val ok = when (line.substringBefore(' ').uppercase()) {
                        "EHLO" -> reply("250-fake.upm.es\r\n250-AUTH PLAIN LOGIN\r\n250 8BITMIME")
                        "AUTH" -> reply(answer("AUTH", "235 2.7.0 Authenticated"))
                        "MAIL" -> reply(answer("MAIL", "250 2.1.0 Ok"))
                        "RCPT" -> reply(answer("RCPT ${line.substringAfter(':').trim()}", "250 2.1.5 Ok"))
                        "DATA" -> {
                            val text = answer("DATA", "354 End data with <CR><LF>.<CR><LF>")
                            reply(text) && (!text!!.startsWith("354") || endOfData(input, ::reply))
                        }
                        "QUIT" -> {
                            reply("221 2.0.0 Bye")
                            return
                        }
                        else -> reply("250 2.0.0 Ok")
                    }
                    if (!ok) return
                }
            } catch (_: IOException) {
                // The client went away.
            }
        }

        private fun endOfData(input: BufferedReader, reply: (String?) -> Boolean): Boolean {
            while (true) {
                val line = input.readLine() ?: return false
                if (line == ".") break
            }
            val text = answer("END", "250 2.0.0 Ok: queued")
            if (text != null && text.startsWith("250")) accepted.incrementAndGet()
            return reply(text)
        }

        override fun close() = socket.close()
    }
}
