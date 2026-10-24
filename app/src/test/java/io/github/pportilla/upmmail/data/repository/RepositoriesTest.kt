package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.data.db.FakeSettings
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.addTestAccount
import io.github.pportilla.upmmail.data.db.addTestFolder
import io.github.pportilla.upmmail.data.db.newStore
import io.github.pportilla.upmmail.data.db.remoteMessage
import io.github.pportilla.upmmail.data.files.FileAttachmentOpener
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.security.InMemoryPasswordStore
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.sync.FlushScheduler
import io.github.pportilla.upmmail.sync.MailClientProvider
import io.github.pportilla.upmmail.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepositoriesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: MailStore
    private lateinit var files: LocalFiles
    private lateinit var engine: SyncEngine
    private val passwords = InMemoryPasswordStore()
    private val settings = FakeSettings(AppSettings())

    /** A scope whose coroutines never run: no background sync against real servers. */
    private val inertScope = CoroutineScope(Job().apply { cancel() })

    @Before
    fun setUp() {
        store = newStore()
        files = LocalFiles(temp.newFolder("files"))
        val clients = MailClientProvider({ store.accounts.get(it) }, passwords)
        engine = SyncEngine(store, clients, files, FileAttachmentOpener)
    }

    /** Accepts only the given logins; [failure] is thrown for everything else. */
    private class FakeChecker(
        private val imapLogin: String,
        private val smtpLogin: String,
        private val failure: MailErrorKind = MailErrorKind.AUTHENTICATION,
    ) : CredentialChecker {
        val tried = mutableListOf<String>()

        override suspend fun checkImap(settings: ServerSettings, password: String) {
            tried += "imap:${settings.username}"
            if (settings.username != imapLogin) throw MailException(failure)
        }

        override suspend fun checkSmtp(settings: ServerSettings, password: String) {
            tried += "smtp:${settings.username}"
            if (settings.username != smtpLogin) throw MailException(failure)
        }
    }

    private fun accounts(checker: CredentialChecker) = AccountRepositoryImpl(
        store = store,
        passwords = passwords,
        clients = MailClientProvider({ store.accounts.get(it) }, passwords),
        engine = engine,
        settings = settings,
        files = files,
        scope = inertScope,
        checker = checker,
    )

    @Test
    fun plaintextServersAreRejectedBeforeEitherCredentialCheckOrPersistence() = runBlocking {
        val checker = FakeChecker("ana", "ana@upm.es")
        val repository = accounts(checker)
        val insecure = ServerSettings("mail.example.org", 143, SecurityMode.NONE, "ana")
        for ((imap, smtp) in listOf(insecure to null, null to insecure)) {
            val error = repository.addAccount("ana@upm.es", "pw", "Ana", imap, smtp).exceptionOrNull() as MailException
            assertEquals(MailErrorKind.INSECURE_CONNECTION, error.kind)
            assertTrue(checker.tried.isEmpty())
            assertTrue(store.accounts.getAll().isEmpty())
        }

        val account = repository.addAccount("ana@upm.es", "pw", "Ana").getOrThrow()
        checker.tried.clear()
        val error = repository.updateAccount(account.copy(smtp = insecure), "replacement", verify = false)
            .exceptionOrNull() as MailException
        assertEquals(MailErrorKind.INSECURE_CONNECTION, error.kind)
        assertTrue(checker.tried.isEmpty())
        assertEquals(account, store.accounts.get(account.id))
        assertEquals("pw", passwords.get(account.id))
    }

    @Test
    fun addAccountDetectsTheLoginNameAndStoresThePassword() = runBlocking {
        val checker = FakeChecker(imapLogin = "ana.perez@alumnos.upm.es", smtpLogin = "ana.perez@alumnos.upm.es")
        val account = accounts(checker).addAccount("ana.perez@alumnos.upm.es", "pw", "Ana Pérez").getOrThrow()

        assertEquals(
            listOf("imap:ana.perez", "imap:ana.perez@alumnos.upm.es", "smtp:ana.perez@alumnos.upm.es"),
            checker.tried,
        )
        assertEquals("correo.alumnos.upm.es", account.imap.host)
        assertEquals("ana.perez@alumnos.upm.es", account.imap.username)
        assertEquals("smtp.upm.es", account.smtp.host)
        assertEquals("pw", passwords.get(account.id))
        assertEquals(AccountRepositoryImpl.ACCOUNT_COLORS.first(), account.color)
    }

    @Test
    fun networkErrorsAbortDetectionAndDuplicatesAreRejected() = runBlocking {
        val offline = FakeChecker(imapLogin = "-", smtpLogin = "-", failure = MailErrorKind.NETWORK)
        val error = accounts(offline).addAccount("ana@upm.es", "pw", "").exceptionOrNull() as MailException
        assertEquals(MailErrorKind.NETWORK, error.kind)
        assertEquals("only the first candidate was tried", listOf("imap:ana"), offline.tried)
        assertTrue(store.accounts.getAll().isEmpty())

        val repository = accounts(FakeChecker("ana", "ana@upm.es"))
        val first = repository.addAccount("ana@upm.es", "pw", "").getOrThrow()
        assertTrue(repository.addAccount("ANA@upm.es", "pw", "").isFailure)
        val second = repository.addAccount("bea@upm.es", "pw", "").exceptionOrNull()
        assertEquals("wrong logins are reported as such", MailErrorKind.AUTHENTICATION, (second as MailException).kind)
        assertEquals(listOf(first.id), store.accounts.getAll().map { it.id })
    }

    @Test
    fun removeAccountDeletesItsDataAndPassword() = runBlocking {
        val repository = accounts(FakeChecker("ana", "ana@upm.es"))
        val account = repository.addAccount("ana@upm.es", "pw", "").getOrThrow()
        val inbox = store.addTestFolder(account.id, "INBOX", FolderRole.INBOX)
        store.messages.upsertHeaders(account.id, inbox.id, listOf(remoteMessage(1)))
        settings.update { it.copy(lastAccountId = account.id, lastFolderId = inbox.id) }

        repository.removeAccount(account.id)
        assertNull(store.accounts.get(account.id))
        assertNull(passwords.get(account.id))
        assertEquals(0, store.messages.count(inbox.id))
        assertEquals(-1L, settings.settings.value.lastAccountId)
    }

    @Test
    fun theAddressHistoryAndSearchesGoWithTheLastAccount() = runBlocking {
        val repository = accounts(FakeChecker("ana", "ana@upm.es"))
        val first = repository.addAccount("ana@upm.es", "pw", "").getOrThrow()
        val inbox = store.addTestFolder(first.id, "INBOX", FolderRole.INBOX)
        store.messages.upsertHeaders(first.id, inbox.id, listOf(remoteMessage(1)))
        store.recentSearches.add("tribunal")
        val second = accounts(FakeChecker("bea", "bea@upm.es")).addAccount("bea@upm.es", "pw", "").getOrThrow()

        repository.removeAccount(first.id)
        assertTrue("still offered to the remaining account", store.contacts.get("bea@alumnos.upm.es") != null)
        assertEquals(listOf("tribunal"), store.recentSearches.list())

        repository.removeAccount(second.id)
        assertNull(store.contacts.get("bea@alumnos.upm.es"))
        assertTrue(store.recentSearches.list().isEmpty())
    }

    private fun compose(): ComposeRepositoryImpl {
        val texts = object : ComposeTexts {
            override fun replyAttribution(date: Long, sender: String) = "El día, $sender escribió:"

            override fun forwardHeader(from: String, date: Long, subject: String, to: String, cc: String) =
                "--- Reenviado ---\nDe: $from\nAsunto: $subject\nPara: $to"
        }
        val controller = object : SyncController {
            override fun schedulePeriodicSync() = Unit
            override fun onAppForeground() = Unit
            override fun onAppBackground() = Unit
            override fun requestOutboxSend() = Unit
        }
        val scheduler = FlushScheduler(inertScope, { engine.flushPendingOps(it) })
        return ComposeRepositoryImpl(store, engine, scheduler, controller, files, texts, { null })
    }

    @Test
    fun repliesQuoteBelowTheSignatureAndForwardsCarryAHeader() = runBlocking {
        val account = store.addTestAccount(signature = "Ana Pérez\nETSI Informáticos")
        val inbox = store.addTestFolder(account.id, "INBOX", FolderRole.INBOX)
        val id = store.messages.upsertHeaders(
            account.id,
            inbox.id,
            listOf(
                remoteMessage(
                    1,
                    subject = "Tribunal",
                    from = EmailAddress("bea@alumnos.upm.es", "Bea Gómez"),
                    to = listOf(EmailAddress("ana@upm.es"), EmailAddress("carlos@upm.es")),
                    messageId = "<t1@x>",
                ),
            ),
        ).single()
        store.messages.storeBody(id, null, "¿Puedes el martes?\nGracias", "¿Puedes el martes?", emptyList(), "")
        val repository = compose()

        val reply = repository.createDraft(account.id, ComposeMode.REPLY_ALL, id).getOrThrow()
        assertEquals("own address left out", listOf("bea@alumnos.upm.es", "carlos@upm.es"), reply.to.map { it.address })
        assertTrue(reply.cc.isEmpty())
        assertEquals("Re: Tribunal", reply.subject)
        assertEquals("<t1@x>", reply.inReplyTo)
        assertEquals(listOf("<t1@x>"), reply.references)
        assertEquals(
            "\n\n-- \nAna Pérez\nETSI Informáticos\n\nEl día, Bea Gómez <bea@alumnos.upm.es> escribió:\n> ¿Puedes el martes?\n> Gracias",
            reply.body,
        )

        val forward = repository.createDraft(account.id, ComposeMode.FORWARD, id).getOrThrow()
        assertEquals("Fwd: Tribunal", forward.subject)
        assertTrue(forward.to.isEmpty())
        assertTrue(forward.body.contains("--- Reenviado ---\nDe: Bea Gómez <bea@alumnos.upm.es>"))
        assertTrue(forward.body.endsWith("¿Puedes el martes?\nGracias"))
        assertEquals(ComposeMode.FORWARD, forward.mode)
        assertEquals(id, forward.sourceMessageId)

        val fresh = repository.createDraft(account.id, ComposeMode.NEW, null).getOrThrow()
        assertEquals("\n\n-- \nAna Pérez\nETSI Informáticos", fresh.body)
    }
}
