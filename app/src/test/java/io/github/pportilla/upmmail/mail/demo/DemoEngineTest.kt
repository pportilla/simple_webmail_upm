package io.github.pportilla.upmmail.mail.demo

import io.github.pportilla.upmmail.data.db.*
import io.github.pportilla.upmmail.data.files.FileAttachmentOpener
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.data.repository.*
import io.github.pportilla.upmmail.data.safety.AgeBand
import io.github.pportilla.upmmail.data.safety.SafetyRepository
import io.github.pportilla.upmmail.data.security.InMemoryPasswordStore
import io.github.pportilla.upmmail.model.*
import io.github.pportilla.upmmail.sync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the unchanged repository/outbox/safety path against the local protocol backend. */
class DemoEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val clock = TestClock()
    private val store = newStore(clock::now)
    private val inertScope = CoroutineScope(Job().apply { cancel() })
    private lateinit var account: Account
    private lateinit var engine: SyncEngine
    private lateinit var compose: ComposeRepositoryImpl
    private lateinit var backend: DemoMailBackend

    private suspend fun setup() {
        val server = ServerSettings("must-not-connect.invalid", 993, SecurityMode.SSL_TLS, "sample")
        val id = store.accounts.insert(Account(0, "review@example.invalid", "Sample", server, server, "", 0, false, 0, isDemo = true))
        account = store.accounts.get(id)!!
        backend = DemoMailBackend(temp.newFolder("sample"), account, clock::now)
        val clients = MailClientProvider({ store.accounts.get(it) }, InMemoryPasswordStore(),
            imapFactory = { _, _ -> error("Network transport must not be created") },
            smtpFactory = { _, _ -> error("Network transport must not be created") },
            safety = store.safety, demoFactory = { backend })
        val files = LocalFiles(temp.newFolder("files"))
        engine = SyncEngine(store, clients, files, FileAttachmentOpener, clock = clock::now)
        val scheduler = FlushScheduler(inertScope, engine::flushPendingOps, clock = clock::now)
        val controller = object : SyncController {
            override fun schedulePeriodicSync() = Unit
            override fun onAppForeground() = Unit
            override fun onAppBackground() = Unit
            override fun requestOutboxSend() = Unit
        }
        val texts = object : ComposeTexts {
            override fun replyAttribution(date: Long, sender: String) = "Sample reply to $sender"
            override fun forwardHeader(from: String, date: Long, subject: String, to: String, cc: String) = "Sample forward: $subject"
        }
        compose = ComposeRepositoryImpl(store, engine, scheduler, controller, files, texts, { null }, clock = clock::now)
        engine.syncAccount(account.id)
    }

    @Test fun productionEngineUploadsDraftAndSendsThroughLocalOutboxAndSentCopy() = runBlocking {
        setup()
        val draft = Draft(accountId = account.id, to = listOf(EmailAddress("alex@example.invalid")),
            subject = "Editable sample draft", body = "A local exercise")
        val savedId = compose.saveDraft(draft, upload = true)
        engine.flushPendingOps(account.id)
        val remoteDrafts = backend.imap().fetchNewest("Drafts", 20).messages
        assertTrue(remoteDrafts.any { it.subject == draft.subject })
        val saved = compose.loadDraft(savedId)!!
        val queued = compose.send(saved, 0)
        assertNotNull(store.outbox.get(queued))
        assertEquals(1, engine.sendOutbox(account.id).sent)
        engine.flushPendingOps(account.id)
        engine.syncAccount(account.id)
        assertNull(store.outbox.get(queued))
        assertTrue(backend.imap().fetchNewest("Sent", 20).messages.any { it.subject == draft.subject })
        assertTrue(backend.imap().fetchNewest("INBOX", 20).messages.any { it.inReplyTo != null && it.subject.contains(draft.subject) })
        assertFalse(backend.imap().fetchNewest("Drafts", 20).messages.any { it.subject == draft.subject })
        assertNull("Sample sends must not alter real recipient history", store.contacts.get("alex@example.invalid"))
        assertNull("Sample arrivals must not alter real contact history", store.contacts.get("tutor@example.invalid"))
    }

    @Test fun productionOutboxRetriesLocallyEvenWhenItRebuildsMimeWithAnotherMessageId() = runBlocking {
        setup()
        val queued = compose.send(Draft(accountId = account.id,
            to = listOf(EmailAddress("fail-once@example.invalid")), subject = "Retry practice", body = "Same content"), 0)
        assertEquals(0, engine.sendOutbox(account.id).sent)
        assertEquals(OutboxState.QUEUED, store.outbox.get(queued)!!.item.state)
        clock.advance(10 * 60_000)
        assertEquals(1, engine.sendOutbox(account.id).sent)
        assertNull(store.outbox.get(queued))
        assertEquals(1, backend.imap().fetchNewest("Sent", 20).messages.count { it.subject == "Retry practice" })
    }

    @Test fun blockedSampleRecipientPreventsQueuedSendAndRetryWorksAfterExplicitUnblock() = runBlocking {
        setup()
        val safety = store.safety as SafetyRepository
        val draft = Draft(accountId = account.id, to = listOf(EmailAddress("tutor@example.invalid")),
            subject = "Ordinary sample", body = "Preserved text")
        val queued = compose.send(draft, 0)
        val before = backend.imap().status("INBOX").messageCount
        safety.blockSender("tutor@example.invalid")
        assertTrue(runCatching { compose.send(draft, 0) }.isFailure)
        assertEquals(0, engine.sendOutbox(account.id).sent)
        assertNotNull(store.outbox.get(queued))
        assertEquals(before, backend.imap().status("INBOX").messageCount)
        safety.unblockSender("tutor@example.invalid")
        compose.retrySend(queued)
        assertEquals(1, engine.sendOutbox(account.id).sent)
        assertNull(store.outbox.get(queued))
        assertEquals(before + 1, backend.imap().status("INBOX").messageCount)
    }
}
