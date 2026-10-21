package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** How ImapClient uses its connection, seen through a proxy in front of the server. */
class ImapConnectionUseTest : GreenMailTestBase() {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var proxy: TcpProxy
    private lateinit var client: ImapClient

    @Before
    fun startProxy() {
        proxy = TcpProxy(greenMail.imap.port)
        client = ImapClient(ServerSettings("127.0.0.1", proxy.port, SecurityMode.NONE, login)) { password }
    }

    @After
    fun stopProxy() {
        client.close()
        proxy.close()
    }

    @Test
    fun movesAndDeletesReuseTheOpenConnection() = runBlocking {
        append("INBOX", *(1..4).map { plainMessage("Message $it") }.toTypedArray())
        createFolder("Archive")
        val uids = client.fetchNewest("INBOX", 10).messages.map { it.uid }
        client.move("INBOX", listOf(uids[0]), "Archive")
        client.move("INBOX", listOf(uids[1]), "Archive")
        client.disabledCapabilities = setOf("MOVE")
        client.move("INBOX", listOf(uids[2]), "Archive")
        client.deletePermanently("INBOX", listOf(uids[3]))

        assertEquals(1, proxy.connections.get())
        assertEquals(0, client.fetchNewest("INBOX", 10).messages.size)
        assertEquals(3, client.fetchNewest("Archive", 10).messages.size)
        assertEquals(1, proxy.connections.get())
    }

    @Test
    fun connectionLostDuringADownloadIsRetriedOnAFreshOne() = runBlocking {
        val pdf = seedAttachment()
        val uid = client.fetchNewest("INBOX", 1).messages.single().uid
        client.fetchBody("INBOX", uid)

        proxy.cutAfter(400_000)
        val target = temp.root.resolve("download/informe.pdf")
        client.downloadPart("INBOX", uid, "2", target)

        assertArrayEquals(pdf, target.readBytes())
        assertEquals(2, proxy.connections.get())
    }

    @Test
    fun connectionLostDuringEveryDownloadIsANetworkError() = runBlocking {
        seedAttachment()
        val uid = client.fetchNewest("INBOX", 1).messages.single().uid
        client.fetchBody("INBOX", uid)

        proxy.cutAfter(400_000, everyConnection = true)
        val target = temp.root.resolve("download/informe.pdf")
        try {
            client.downloadPart("INBOX", uid, "2", target)
            fail("expected NETWORK")
        } catch (e: MailException) {
            assertEquals(e.message, MailErrorKind.NETWORK, e.kind)
        }
        assertFalse(target.exists())
        assertEquals("no partial file is left behind", emptyList<String>(), target.parentFile!!.list()!!.toList())
    }

    private fun seedAttachment(): ByteArray {
        val pdf = Random(7).nextBytes(2_000_000)
        val message = plainMessage("Con adjunto")
        message.setContent(
            MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply { setText("Ver adjunto", "UTF-8") })
                addBodyPart(attachment("informe.pdf", "application/pdf", pdf))
            },
        )
        message.saveChanges()
        append("INBOX", message)
        return pdf
    }
}
