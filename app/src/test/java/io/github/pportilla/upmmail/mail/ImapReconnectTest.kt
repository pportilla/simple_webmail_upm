package io.github.pportilla.upmmail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.Properties

/** The server goes away under an open connection; the next call must reconnect transparently. */
class ImapReconnectTest {
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var greenMail: GreenMail

    @Before
    fun start() {
        MailSessions.ensureInitialized()
        greenMail = GreenMail(ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_IMAP))
        greenMail.start()
        greenMail.setUser("ana@upm.es", "ana", "secret")
    }

    @After
    fun stop() = greenMail.stop()

    private fun appendOne(subject: String) {
        val session = Session.getInstance(Properties())
        val store = session.getStore("imap")
        store.connect("127.0.0.1", port, "ana", "secret")
        try {
            val inbox = store.getFolder("INBOX")
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress("bea@upm.es"))
                setSubject(subject)
                setText("x")
            }
            inbox.appendMessages(arrayOf(message))
            inbox.open(Folder.READ_ONLY)
            inbox.close(false)
        } finally {
            store.close()
        }
    }

    @Test
    fun reconnectsAfterTheServerDroppedTheConnection() = runBlocking {
        appendOne("Antes")
        val client = ImapClient(ServerSettings("127.0.0.1", port, SecurityMode.NONE, "ana")) { "secret" }
        try {
            assertEquals(listOf("Antes"), client.fetchNewest("INBOX", 10).messages.map { it.subject })

            // Restarting the server kills every open connection (and its in-memory mailboxes).
            greenMail.stop()
            greenMail = GreenMail(ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_IMAP))
            greenMail.start()
            greenMail.setUser("ana@upm.es", "ana", "secret")
            appendOne("Después")

            assertEquals(listOf("Después"), client.fetchNewest("INBOX", 10).messages.map { it.subject })
            assertEquals(1, client.status("INBOX").messageCount)
        } finally {
            client.close()
        }
    }
}
