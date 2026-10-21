package io.github.pportilla.upmmail.sync

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import io.github.pportilla.upmmail.data.db.FakeSettings
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.TestClock
import io.github.pportilla.upmmail.data.db.addTestAccount
import io.github.pportilla.upmmail.data.db.newStore
import io.github.pportilla.upmmail.data.files.FileAttachmentOpener
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.repository.ComposeRepositoryImpl
import io.github.pportilla.upmmail.data.repository.ComposeTexts
import io.github.pportilla.upmmail.data.repository.MailRepositoryImpl
import io.github.pportilla.upmmail.data.repository.SyncController
import io.github.pportilla.upmmail.data.security.InMemoryPasswordStore
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Date
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** GreenMail server, in-memory cache and engine for every test. */
abstract class SyncTestBase {
    @get:Rule
    val temp = TemporaryFolder()

    protected lateinit var greenMail: GreenMail
    protected lateinit var store: MailStore
    protected lateinit var clients: MailClientProvider
    protected lateinit var engine: SyncEngine
    protected lateinit var account: Account
    protected lateinit var files: LocalFiles
    protected val clock = TestClock(System.currentTimeMillis())
    protected val settings = FakeSettings(AppSettings(conversationView = false))
    protected val passwords = InMemoryPasswordStore()
    protected val listener = RecordingListener()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected val anaEmail = "ana@upm.es"
    protected val password = "secret"

    class RecordingListener : SyncListener {
        val newMessages = CopyOnWriteArrayList<MessageSummary>()
        val failures = CopyOnWriteArrayList<OutboxItem>()

        override fun onNewMessages(accountId: Long, folderId: Long, messages: List<MessageSummary>) {
            newMessages += messages
        }

        override fun onSendFailed(item: OutboxItem) {
            failures += item
        }
    }

    class RecordingController : SyncController {
        var outboxRequests = 0

        override fun schedulePeriodicSync() = Unit

        override fun onAppForeground() = Unit

        override fun onAppBackground() = Unit

        override fun requestOutboxSend() {
            outboxRequests++
        }
    }

    object PlainTexts : ComposeTexts {
        override fun replyAttribution(date: Long, sender: String) = "On $date, $sender wrote:"

        override fun forwardHeader(from: String, date: Long, subject: String, to: String, cc: String) =
            "--- Forwarded ---\nFrom: $from\nSubject: $subject\nTo: $to"
    }

    @Before
    fun startServer() {
        greenMail = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP))
        greenMail.start()
        greenMail.setUser(anaEmail, "ana", password)
        greenMail.setUser("bob@upm.es", "bob", password)
        store = newStore(clock::now)
        account = store.addTestAccount(
            email = anaEmail,
            imap = ServerSettings("127.0.0.1", greenMail.imap.port, SecurityMode.NONE, "ana"),
            smtp = ServerSettings("127.0.0.1", greenMail.smtp.port, SecurityMode.NONE, "ana"),
        )
        passwords.set(account.id, password)
        clients = MailClientProvider({ store.accounts.get(it) }, passwords)
        files = LocalFiles(temp.newFolder("files"))
        engine = SyncEngine(store, clients, files, FileAttachmentOpener, listener, clock = clock::now)
    }

    @After
    fun stopServer() {
        scope.cancel()
        clients.closeAll()
        greenMail.stop()
    }

    protected fun draftTo(address: String, subject: String): Draft =
        Draft(accountId = account.id, to = listOf(EmailAddress(address)), subject = subject, body = "Texto")

    protected fun mailRepository(conversations: Boolean = false): MailRepositoryImpl {
        settings.update { it.copy(conversationView = conversations) }
        val scheduler = FlushScheduler(scope, { engine.flushPendingOps(it) }, clock = clock::now)
        return MailRepositoryImpl(store, engine, scheduler, settings, scope, clock = clock::now)
    }

    protected fun composeRepository(controller: SyncController = RecordingController()): ComposeRepositoryImpl {
        val scheduler = FlushScheduler(scope, { engine.flushPendingOps(it) }, clock = clock::now)
        return ComposeRepositoryImpl(store, engine, scheduler, controller, files, PlainTexts, { null }, clock = clock::now)
    }

    // ------------------------------------------------------------------ server helpers

    protected fun <T> withServer(user: String = "ana", block: (Store) -> T): T {
        val props = Properties()
        props["mail.imap.host"] = "127.0.0.1"
        props["mail.imap.port"] = greenMail.imap.port.toString()
        val store = Session.getInstance(props).getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, user, password)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    protected fun createFolder(name: String) = withServer { server ->
        val folder = server.getFolder(name)
        if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)
    }

    protected fun appendMessages(folderName: String, messages: List<MimeMessage>, flags: Flags? = null) = withServer { server ->
        val folder = server.getFolder(folderName)
        if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)
        if (flags != null) messages.forEach { it.setFlags(flags, true) }
        folder.appendMessages(messages.toTypedArray<Message>())
    }

    /** Messages of a server folder as (subject, flags), in uid order. */
    protected fun serverMessages(folderName: String, user: String = "ana"): List<Pair<String, Flags>> = withServer(user) { server ->
        val folder = server.getFolder(folderName)
        if (!folder.exists()) return@withServer emptyList()
        folder.open(Folder.READ_ONLY)
        try {
            folder.messages.map { it.subject.orEmpty() to it.flags }
        } finally {
            folder.close(false)
        }
    }

    /** Applies [change] to the server message with [subject] (then expunges deleted ones). */
    protected fun changeOnServer(folderName: String, subject: String, change: (Message) -> Unit) = withServer { server ->
        val folder = server.getFolder(folderName)
        folder.open(Folder.READ_WRITE)
        folder.messages.filter { it.subject == subject }.forEach(change)
        folder.close(true)
    }

    protected fun message(
        subject: String,
        body: String = "Hola, $subject",
        from: String = "Bea Gómez <bea@alumnos.upm.es>",
        to: String = anaEmail,
        messageId: String? = null,
        date: Date = Date(1_700_000_000_000L),
        attachment: Pair<String, ByteArray>? = null,
    ): MimeMessage {
        val message = FixedIdMessage(Session.getInstance(Properties()), messageId)
        message.setFrom(InternetAddress.parse(from).first())
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        message.setSubject(subject, "UTF-8")
        if (attachment == null) {
            message.setText(body, "UTF-8")
        } else {
            val multipart = MimeMultipart("mixed")
            multipart.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
            multipart.addBodyPart(
                MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(attachment.second, "application/pdf"))
                    fileName = attachment.first
                    disposition = MimeBodyPart.ATTACHMENT
                },
            )
            message.setContent(multipart)
        }
        message.sentDate = date
        message.saveChanges()
        return message
    }

    /** Numbered messages, one minute apart, oldest first. */
    protected fun numbered(count: Int, prefix: String = "Message"): List<MimeMessage> =
        (1..count).map { i -> message("$prefix $i", date = Date(1_700_000_000_000L + i * 60_000L), messageId = "<$prefix-$i@test>") }

    private class FixedIdMessage(session: Session, private val fixedId: String?) : MimeMessage(session) {
        override fun updateMessageID() {
            if (fixedId != null) setHeader("Message-ID", fixedId) else super.updateMessageID()
        }
    }
}
