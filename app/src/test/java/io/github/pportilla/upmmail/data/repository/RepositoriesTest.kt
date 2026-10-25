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
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.data.safety.PlatformRestriction
import io.github.pportilla.upmmail.mail.ImapTransport
import io.github.pportilla.upmmail.mail.demo.DemoMailBackend
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
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
        val clients = MailClientProvider({ store.accounts.get(it) }, passwords, safety = store.safety)
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
        clients = MailClientProvider({ store.accounts.get(it) }, passwords, safety = store.safety),
        engine = engine,
        settings = settings,
        files = files,
        scope = inertScope,
        checker = checker,
    )

    @Test
    fun sampleAccountRequiresNoCredentialsAndCannotBeConvertedIntoRealMail() = runBlocking {
        val checker = FakeChecker("never", "never")
        val repository = accounts(checker)
        val sample = repository.addDemoAccount().getOrThrow()
        assertTrue(sample.isDemo)
        assertNull(passwords.get(sample.id))
        assertTrue(checker.tried.isEmpty())
        assertEquals(sample.id, repository.addDemoAccount().getOrThrow().id)
        assertTrue(repository.updateAccount(sample.copy(isDemo = false), verify = false).isFailure)
        assertTrue(repository.updateAccount(sample.copy(smtp = sample.smtp.copy(host = "smtp.upm.es")), verify = false).isFailure)
        assertTrue(repository.updateAccount(sample, newPassword = "never-store-this").isFailure)
        val renamed = repository.updateAccount(sample.copy(displayName = "Practice"), verify = true).getOrThrow()
        assertEquals("Practice", renamed.displayName)
        assertNull(passwords.get(sample.id))
        assertTrue(checker.tried.isEmpty())
    }

    @Test
    fun aRealAccountCannotOptIntoSampleTransportThroughUpdate() = runBlocking {
        val repository = accounts(FakeChecker("ana", "ana@upm.es"))
        val real = repository.addAccount("ana@upm.es", "pw", "Ana").getOrThrow()
        assertTrue(repository.updateAccount(real.copy(isDemo = true), verify = false).isFailure)
        assertEquals(real, store.accounts.get(real.id))
        assertEquals("pw", passwords.get(real.id))
    }

    @Test
    fun sampleSetupCannotUnlockKnownUnder13Policy() = runBlocking {
        val safety = io.github.pportilla.upmmail.data.safety.LocalSafetyRepository(
            io.github.pportilla.upmmail.data.safety.legacySafetyStorage(io.github.pportilla.upmmail.data.safety.AgeBand.UNDER_13))
        store = MailStore(store.sql, safety = safety)
        val before = safety.state.value
        val repository = accounts(FakeChecker("never", "never"))
        assertTrue(repository.addDemoAccount().isFailure)
        assertEquals(before, safety.state.value)
        assertTrue(store.accounts.getAll().isEmpty())
        assertTrue(runCatching { safety.completeGeneralSetup() }.isFailure)
        assertTrue(repository.addDemoAccount().isFailure)
    }

    @Test
    fun sampleSuggestionsDoNotUseDeviceContactsOrRealHistory() = runBlocking {
        val sample = accounts(FakeChecker("never", "never")).addDemoAccount().getOrThrow()
        store.contacts.recordContacted(listOf(EmailAddress("alex@real.example", "Alex Private")), System.currentTimeMillis())
        var deviceReads = 0
        val suggestions = ContactRepositoryImpl(store, DeviceContactsSource { _, _ ->
            deviceReads++
            listOf(EmailAddress("alex@device.example", "Alex Device"))
        })
        val results = suggestions.suggestForAccount(sample.id, "alex")
        assertEquals(listOf("alex@example.invalid"), results.map { it.address.address })
        assertEquals(0, deviceReads)
        val inbox = store.addTestFolder(sample.id, "INBOX", FolderRole.INBOX)
        store.messages.upsertHeaders(sample.id, inbox.id, listOf(remoteMessage(1, from = EmailAddress("tutor@example.invalid"))))
        assertTrue(store.contacts.candidates("tutor", 10).isEmpty())
    }

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
    private data class AttachmentFixture(
        val mail: MailRepositoryImpl,
        val id: Long,
        val policy: LocalSafetyRepository,
    )

    /** Real sample MIME/data with the normal repository/cache; a test-only wrapper can interrupt reads. */
    private suspend fun attachmentFixture(onDownload: (suspend (Int) -> Unit)? = null): AttachmentFixture {
        val sample = accounts(FakeChecker("never", "never")).addDemoAccount().getOrThrow()
        val backend = DemoMailBackend(temp.newFolder("attachment-sample"), sample)
        val attempts = AtomicInteger()
        val source = backend.imap()
        if (onDownload != null) store.accounts.update(sample.copy(isDemo = false))
        val clients = MailClientProvider({ store.accounts.get(it) }, passwords, safety = store.safety,
            demoFactory = { backend },
            imapFactory = { _, _ -> object : ImapTransport by source {
                override suspend fun downloadPart(folder: String, uid: Long, partPath: String, destination: File) {
                    onDownload!!(attempts.incrementAndGet())
                    source.downloadPart(folder, uid, partPath, destination)
                }
            } })
        // Unconfined makes the pre-network policy boundary deterministic in these coroutine tests.
        engine = SyncEngine(store, clients, files, FileAttachmentOpener, io = Dispatchers.Unconfined)
        engine.syncAccount(sample.id)
        val inbox = store.folders.findByRole(sample.id, FolderRole.INBOX)!!
        val message = store.messages.list(inbox.id, 50, false).single { it.hasAttachments }
        engine.loadBody(message.id, inlineImages = false)
        val attachment = store.messages.detail(message.id)!!.attachments.single()
        assertNull(attachment.localPath)
        val scheduler = FlushScheduler(inertScope, engine::flushPendingOps)
        val mail = MailRepositoryImpl(store, engine, scheduler, settings, inertScope, io = Dispatchers.Unconfined)
        return AttachmentFixture(mail, attachment.id, store.safety as LocalSafetyRepository)
    }

    private val sampleAttachmentBytes =
        "SAMPLE FILE / ARCHIVO DE EJEMPLO\nRead, write and learn together. / Leed, escribid y aprended juntos.\n".toByteArray(Charsets.UTF_8)

    @Test fun uncachedAttachmentRequestWaitsForForegroundCheckAndReturnsOriginalBytes() = runBlocking {
        val fixture = attachmentFixture()
        fixture.policy.setPlatformCheckPending(true)
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.mail.downloadAttachment(fixture.id) }
        assertFalse("The export request must wait, not return an error and delete its destination", download.isCompleted)
        assertNull(store.messages.attachment(fixture.id)!!.attachment.localPath)
        fixture.policy.setPlatformCheckPending(false)
        val file = withTimeout(3_000) { download.await() }.getOrThrow()
        assertArrayEquals(sampleAttachmentBytes, file.readBytes())
    }

    @Test fun explicitRestrictionWhileAttachmentWaitsStillFailsWithoutDownloading() = runBlocking {
        val fixture = attachmentFixture()
        fixture.policy.setPlatformCheckPending(true)
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.mail.downloadAttachment(fixture.id) }
        assertFalse(download.isCompleted)
        fixture.policy.updatePlatformRestriction(PlatformRestriction.APPROVAL_DECLINED)
        val error = withTimeout(3_000) { download.await() }.exceptionOrNull() as MailException
        assertEquals(MailErrorKind.SAFETY_BLOCKED, error.kind)
        assertNull(store.messages.attachment(fixture.id)!!.attachment.localPath)
    }

    @Test fun cancellingWaitingAttachmentRequestPropagatesAndDoesNotDownloadLater() = runBlocking {
        val fixture = attachmentFixture()
        fixture.policy.setPlatformCheckPending(true)
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.mail.downloadAttachment(fixture.id) }
        assertFalse(download.isCompleted)
        download.cancelAndJoin()
        fixture.policy.setPlatformCheckPending(false)
        assertTrue(download.isCancelled)
        assertNull(store.messages.attachment(fixture.id)!!.attachment.localPath)
    }

    @Test fun cachedAttachmentDoesNotWaitForAnUnrelatedPlatformCheck() = runBlocking {
        val fixture = attachmentFixture()
        val cached = fixture.mail.downloadAttachment(fixture.id).getOrThrow()
        fixture.policy.setPlatformCheckPending(true)
        val file = withTimeout(1_000) { fixture.mail.downloadAttachment(fixture.id) }.getOrThrow()
        assertEquals(cached, file)
        assertArrayEquals(sampleAttachmentBytes, file.readBytes())
    }

    @Test fun attachmentReadInterruptedByACompletedPlatformCheckRetriesTheReadOnly() = runBlocking {
        val policy = store.safety as LocalSafetyRepository
        var reads = 0
        val fixture = attachmentFixture { attempt ->
            reads = attempt
            if (attempt == 1) {
                policy.setPlatformCheckPending(true)
                policy.setPlatformCheckPending(false)
                throw MailException(MailErrorKind.NETWORK, "Socket closed during foreground lookup")
            }
        }
        val file = fixture.mail.downloadAttachment(fixture.id).getOrThrow()
        assertEquals(2, reads)
        assertArrayEquals(sampleAttachmentBytes, file.readBytes())
    }

    @Test fun ordinaryAttachmentNetworkFailureIsNotHiddenOrRetried() = runBlocking {
        var reads = 0
        val fixture = attachmentFixture { attempt ->
            reads = attempt
            throw MailException(MailErrorKind.NETWORK, "Ordinary disconnected network")
        }
        val error = fixture.mail.downloadAttachment(fixture.id).exceptionOrNull() as MailException
        assertEquals(MailErrorKind.NETWORK, error.kind)
        assertEquals(1, reads)
        assertNull(store.messages.attachment(fixture.id)!!.attachment.localPath)
    }

}
