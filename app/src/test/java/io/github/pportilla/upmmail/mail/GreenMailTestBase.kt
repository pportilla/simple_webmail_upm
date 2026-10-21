package io.github.pportilla.upmmail.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.junit.After
import org.junit.Before
import java.util.Date
import java.util.Properties

/** Starts a GreenMail SMTP + IMAP server on free ports for every test. */
abstract class GreenMailTestBase {
    protected lateinit var greenMail: GreenMail

    protected val email = "ana@upm.es"
    protected val login = "ana"
    protected val password = "secret"

    @Before
    fun startServer() {
        MailSessions.ensureInitialized()
        greenMail = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP))
        greenMail.start()
        greenMail.setUser(email, login, password)
    }

    @After
    fun stopServer() {
        greenMail.stop()
    }

    protected fun imapSettings(user: String = login): ServerSettings =
        ServerSettings("127.0.0.1", greenMail.imap.port, SecurityMode.NONE, user)

    protected fun smtpSettings(user: String = login): ServerSettings =
        ServerSettings("127.0.0.1", greenMail.smtp.port, SecurityMode.NONE, user)

    protected fun newImapClient(pass: String = password): ImapClient = ImapClient(imapSettings()) { pass }

    protected fun session(): Session = Session.getInstance(Properties())

    /** Runs [block] on a plain Jakarta Mail IMAP connection, for test setup and checks. */
    protected fun <T> withRawStore(block: (Store) -> T): T {
        val props = Properties()
        props["mail.imap.host"] = "127.0.0.1"
        props["mail.imap.port"] = greenMail.imap.port.toString()
        val store = Session.getInstance(props).getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, login, password)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    protected fun createFolder(name: String) = withRawStore { store ->
        val folder = store.getFolder(name)
        if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)
    }

    protected fun append(folderName: String, vararg messages: MimeMessage, flags: Flags? = null) = withRawStore { store ->
        val folder = store.getFolder(folderName)
        if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)
        if (flags != null) messages.forEach { it.setFlags(flags, true) }
        folder.appendMessages(messages.map { it as Message }.toTypedArray())
    }

    protected fun plainMessage(
        subject: String,
        body: String = "Hello",
        from: String = "Bea Gómez <bea@alumnos.upm.es>",
        to: String = email,
        cc: String? = null,
        messageId: String? = null,
        headers: Map<String, String> = emptyMap(),
        date: Date = Date(1_700_000_000_000L),
    ): MimeMessage {
        val message = FixedIdMessage(session(), messageId)
        message.setFrom(InternetAddress.parse(from).first())
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        if (cc != null) message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
        message.setSubject(subject, "UTF-8")
        message.setText(body, "UTF-8")
        message.sentDate = date
        headers.forEach { (name, value) -> message.setHeader(name, value) }
        message.saveChanges()
        return message
    }

    protected fun alternativeMessage(subject: String, text: String, html: String): MimeMessage {
        val message = plainMessage(subject)
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8", "plain") })
        alternative.addBodyPart(MimeBodyPart().apply { setText(html, "UTF-8", "html") })
        message.setContent(alternative)
        message.saveChanges()
        return message
    }

    protected fun attachment(fileName: String, mimeType: String, bytes: ByteArray): MimeBodyPart =
        MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(bytes, mimeType))
            this.fileName = fileName
            disposition = MimeBodyPart.ATTACHMENT
        }

    /** A MimeMessage that keeps a given Message-ID across saveChanges(). */
    private class FixedIdMessage(session: Session, private val id: String?) : MimeMessage(session) {
        override fun updateMessageID() {
            if (id != null) setHeader("Message-ID", id) else super.updateMessageID()
        }
    }
}
