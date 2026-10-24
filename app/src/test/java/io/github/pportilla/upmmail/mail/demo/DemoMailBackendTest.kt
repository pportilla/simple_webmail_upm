package io.github.pportilla.upmmail.mail.demo

import io.github.pportilla.upmmail.data.safety.AgeBand
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.data.security.PasswordStore
import io.github.pportilla.upmmail.mail.*
import io.github.pportilla.upmmail.model.*
import io.github.pportilla.upmmail.sync.MailClientProvider
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DemoMailBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = ServerSettings("must-not-connect.invalid", 993, SecurityMode.SSL_TLS, "sample")
    private fun account(id: Long = 1) = Account(id, "review@example.invalid", "Sample", server, server, "", 0, false, 0, isDemo = true)
    private fun backend(id: Long = 1) = DemoMailBackend(temporary.root, account(id))
    private fun adult() = LocalSafetyRepository().apply { chooseAgeBand(AgeBand.ADULT); acceptTerms() }
    private val forbiddenPasswords = object : PasswordStore {
        override fun get(accountId: Long): String? = error("Sample operation accessed credentials")
        override fun set(accountId: Long, password: String) = error("Sample operation stored credentials")
        override fun remove(accountId: Long) = error("Sample transport removed credentials")
    }
    private fun message(to: String = "alex@example.invalid", subject: String = "Local sample", body: String = "This remains on this device."): MimeMessage =
        MessageComposer.build(EmailAddress(account().email), Draft(accountId = 1, to = listOf(EmailAddress(to)), subject = subject, body = body), emptyList()).mime

    @Test fun explicitFlagRoutesAllTransportsWithoutCredentialsOrLiveFactories() = runBlocking {
        val backend = backend()
        var current: Account? = account()
        val clients = MailClientProvider({ current }, forbiddenPasswords,
            imapFactory = { _, _ -> error("Live IMAP factory used") },
            smtpFactory = { _, _ -> error("Live SMTP factory used") },
            transportPolicy = { error("Sample route entered live transport policy") },
            safety = adult(), demoFactory = { backend })
        val imap = clients.imap(1)
        assertEquals(7, imap.listFolders().size)
        clients.smtp(1).verify()
        assertNotNull(clients.idler(1))
        current = null
        assertEquals(MailErrorKind.NOT_FOUND, (runCatching { imap.status("INBOX") }.exceptionOrNull() as MailException).kind)
    }

    @Test fun hostOrEmailNeverSelectsDemoAndMissingFactoryNeverFallsBackToNetwork() = runBlocking {
        var liveFactoryCalls = 0
        val real = account().copy(isDemo = false)
        val clients = MailClientProvider({ real }, forbiddenPasswords, safety = adult(),
            imapFactory = { _, _ -> liveFactoryCalls++; error("Expected real factory") },
            demoFactory = { error("A real account entered sample transport") })
        assertTrue(runCatching { clients.imap(1) }.isFailure)
        assertEquals(1, liveFactoryCalls)
        val missing = MailClientProvider({ account() }, forbiddenPasswords, safety = adult(),
            imapFactory = { _, _ -> error("Must never fall back to network") })
        assertEquals(MailErrorKind.UNKNOWN, (runCatching { missing.imap(1) }.exceptionOrNull() as MailException).kind)
        assertTrue(runCatching { DemoMailBackend(temporary.root, real) }.isFailure)
    }

    @Test fun unconfiguredAndRevokedProfilesCannotUseSampleToBypassSafety() = runBlocking {
        val safety = LocalSafetyRepository()
        val backend = backend()
        var factoryCalls = 0
        val clients = MailClientProvider({ account() }, forbiddenPasswords, safety = safety,
            demoFactory = { factoryCalls++; backend })
        assertEquals(MailErrorKind.SAFETY_BLOCKED, (runCatching { clients.imap(1) }.exceptionOrNull() as MailException).kind)
        assertEquals(0, factoryCalls)
        safety.chooseAgeBand(AgeBand.ADULT); safety.acceptTerms()
        val imap = clients.imap(1)
        assertTrue(imap.status("INBOX").messageCount > 0)
        safety.enableSupervised(AgeBand.UNDER_13, "713529", java.time.LocalDate.of(1980, 1, 1))
        safety.lockGuardian()
        assertEquals(MailErrorKind.SAFETY_BLOCKED, (runCatching { imap.status("INBOX") }.exceptionOrNull() as MailException).kind)
    }

    @Test fun movesFlagsAttachmentsAndDraftsPersistAndAccountsStayIsolated() = runBlocking {
        val imap = backend().imap()
        val all = imap.fetchNewest("INBOX", 50).messages
        val attachmentMail = all.single { it.hasAttachments }
        val body = imap.fetchBody("INBOX", attachmentMail.uid)
        assertEquals("sample-notes.txt", body.attachments.single().fileName)
        val downloaded = File(temporary.root, "download.txt")
        imap.downloadPart("INBOX", attachmentMail.uid, body.attachments.single().partPath, downloaded)
        assertTrue(downloaded.readText().contains("SAMPLE FILE"))
        val uid = all.first().uid
        imap.setFlag("INBOX", listOf(uid), MessageFlag.FLAGGED, true)
        imap.move("INBOX", listOf(uid), "Archive")
        val draftId = imap.append("Drafts", message(subject = "Saved draft"), seen = true, draft = true)!!
        val reloaded = backend().imap()
        assertFalse(reloaded.fetchFlagsFor("INBOX", listOf(uid)).containsKey(uid))
        assertTrue(reloaded.fetchNewest("Archive", 20).messages.any { it.flags.flagged })
        assertTrue(reloaded.fetchMessages("Drafts", listOf(draftId)).single().flags.draft)
        assertEquals(1, backend(2).imap().status("Drafts").messageCount)
        reloaded.deletePermanently("Drafts", listOf(draftId))
        assertEquals(1, backend().imap().status("Drafts").messageCount)
    }

    @Test fun searchRangesIndexHeadersAndDerivedClientsShareActualMailboxState() = runBlocking {
        val imap = backend().imap()
        val search = imap.derive(ImapOptions.SEARCH)
        val probe = search.examine("INBOX")
        val query = ServerQuery.parse("project", SearchField.entries.toSet())!!
        assertEquals(2, search.searchUids("INBOX", query, null, 20).totalMatches)
        assertEquals(1, search.searchUids("INBOX", query, SearchRange.Seq(2, 2), 20).totalMatches)
        assertTrue(search.searchUids("INBOX", query, SearchRange.UidsFrom(probe.uidNext), 20).uids.isEmpty())
        val headers = search.fetchIndexHeaders("INBOX", listOf(1, 999))
        assertEquals(listOf(1L), headers.headers.map { it.uid })
        assertEquals(listOf(999L), headers.missingUids)
        imap.setFlag("INBOX", listOf(1), MessageFlag.DELETED, true)
        assertFalse(search.searchUndeletedUids("INBOX").uids.contains(1))
        assertTrue(search.searchAllUids("INBOX").uids.contains(1))
        assertEquals(5, search.fetchNewest("INBOX", 50).messages.size)
    }

    @Test fun simulatedSendCreatesOnlyOneLocalReplyAndEngineCanAppendSentCopy() = runBlocking {
        val backend = backend()
        val imap = backend.imap()
        val originalCount = imap.status("INBOX").messageCount
        val outgoing = message(to = "recipient@ordinary-domain.example")
        backend.smtp().send(outgoing)
        backend.smtp().send(outgoing)
        assertEquals(originalCount + 1, imap.status("INBOX").messageCount)
        val reply = imap.fetchNewest("INBOX", 1).messages.single()
        assertEquals(outgoing.messageID, reply.inReplyTo)
        assertEquals("tutor@example.invalid", reply.from?.address)
        assertTrue(imap.fetchBody("INBOX", reply.uid).text!!.contains("No email was delivered"))
        assertEquals(0, imap.status("Sent").messageCount)
        val sent = imap.append("Sent", outgoing, true, false)!!
        assertEquals(outgoing.messageID, imap.fetchMessages("Sent", listOf(sent)).single().messageId)
    }

    @Test fun retryIsDeterministicAndPersistsAcrossProcessRestart() = runBlocking {
        val outgoing = message(to = "fail-once@example.invalid")
        val first = backend()
        val count = first.imap().status("INBOX").messageCount
        val error = runCatching { first.smtp().send(outgoing) }.exceptionOrNull() as MailException
        assertTrue(error.isTransient)
        val restarted = backend()
        restarted.smtp().send(outgoing)
        assertEquals(count + 1, restarted.imap().status("INBOX").messageCount)
        backend().smtp().send(outgoing)
        assertEquals(count + 1, backend().imap().status("INBOX").messageCount)
    }

    @Test fun authorizationIsCheckedBeforeAcceptanceAndDeniedSendsDoNotMutateInbox() = runBlocking {
        val backend = backend()
        val count = backend.imap().status("INBOX").messageCount
        var checks = 0
        val outgoing = message()
        val failure = runCatching {
            backend.smtp().send(outgoing, authorize = {
                if (++checks >= 3) throw MailException(MailErrorKind.SAFETY_BLOCKED, "Revoked")
            })
        }.exceptionOrNull() as MailException
        assertEquals(MailErrorKind.SAFETY_BLOCKED, failure.kind)
        assertEquals(count, backend.imap().status("INBOX").messageCount)
        backend.smtp().send(outgoing)
        assertEquals(count + 1, backend.imap().status("INBOX").messageCount)
    }

    @Test fun localIdleReceivesChangesAndDeletionInvalidatesEveryRetainedHandle() = runBlocking {
        val backend = backend()
        val imap = backend.imap()
        val ready = CompletableDeferred<Unit>()
        val notifications = Channel<ImapIdler.Change>(Channel.UNLIMITED)
        val idler = backend.idler()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            idler.run(onIdle = { ready.complete(Unit) }, onChange = { notifications.trySend(it) })
        }
        withTimeout(2_000) { ready.await() }
        assertTrue(idler.refresh())
        backend.smtp().send(message())
        assertEquals(ImapIdler.Change.NEW_MESSAGES, withTimeout(2_000) { notifications.receive() })
        job.cancelAndJoin()
        assertFalse(idler.refresh())
        backend.delete()
        assertTrue(runCatching { imap.listFolders() }.isFailure)
        assertTrue(runCatching { backend.smtp().send(message()) }.isFailure)
        assertFalse(File(temporary.root, "1").exists())
    }

    @Test fun htmlSampleBodyCannotExposeRemoteResources() = runBlocking {
        val backend = backend()
        val mime = message().apply {
            setContent("<p>Sample text</p><img src=\"https://tracking.example/pixel\"><a href=\"https://outside.example/\">Link text</a>", "text/html; charset=UTF-8")
            saveChanges()
        }
        val uid = backend.imap().append("Drafts", mime, true, true)!!
        val parsed = backend.imap().fetchBody("Drafts", uid)
        assertTrue(parsed.text!!.contains("Sample text"))
        assertFalse(parsed.html.orEmpty().contains("https://"))
        assertFalse(HtmlSanitizer.hasRemoteContent(parsed.html.orEmpty()))
    }

    @Test fun corruptSnapshotFailsWithoutSilentlyReseedingMailbox() = runBlocking {
        backend().imap().deleteAll("INBOX")
        val file = File(temporary.root, "1/mailbox-v1.bin")
        file.writeBytes(byteArrayOf(0, 1, 2))
        assertEquals(MailErrorKind.UNKNOWN, (runCatching { backend().imap().status("INBOX") }.exceptionOrNull() as MailException).kind)
        assertArrayEquals(byteArrayOf(0, 1, 2), file.readBytes())
    }
}
