package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.files.FileAttachmentOpener
import io.github.pportilla.upmmail.data.safety.*
import io.github.pportilla.upmmail.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production repository/IMAP/SMTP paths after migration from a released family profile. */
class GeneralAudienceMailTest : SyncTestBase() {
    private fun install(policy: LocalSafetyRepository) {
        clients.closeAll()
        store = MailStore(store.sql, safety = policy, clock = clock::now)
        clients = MailClientProvider({ store.accounts.get(it) }, passwords,
            transportPolicy = ::allowLoopbackTransport, safety = policy)
        engine = SyncEngine(store, clients, files, FileAttachmentOpener, listener, clock = clock::now)
    }
    private fun migrateTeen(): LocalSafetyRepository = LocalSafetyRepository(legacySafetyStorage(AgeBand.TEEN)).also {
        install(it)
        it.completeGeneralAudienceMigration { store.outbox.holdForAudienceMigration() }
        it.completeGeneralSetup()
    }
    @Test fun oldTeenQueueIsPreservedAndSendsOnlyAfterExplicitRetry() = runBlocking {
        val queued = store.outbox.enqueue(draftTo("bob@upm.es", "Before update"), null, clock.now())
        val draftId = store.drafts.save(draftTo("bob@upm.es", "Unsent draft"))
        migrateTeen()
        assertEquals(0, engine.sendOutbox().sent)
        assertEquals(OutboxState.FAILED, store.outbox.get(queued)!!.item.state)
        assertEquals(MailErrorKind.REVIEW_REQUIRED.name, store.outbox.get(queued)!!.item.lastError)
        assertEquals(0, greenMail.receivedMessages.size)
        assertNotNull(store.drafts.get(draftId))
        composeRepository().retrySend(queued)
        assertEquals(1, engine.sendOutbox().sent)
        assertNull(store.outbox.get(queued))
        assertEquals("Before update", greenMail.receivedMessages.single().subject)
    }
    @Test fun migratedTeenCanSendNormallyWithoutApprovalAndRetainsBlockedSenders() = runBlocking {
        val policy = migrateTeen()
        val draft = draftTo("bob@upm.es", "Ordinary 13+ email")
        val queued = composeRepository().send(draft, 0)
        assertEquals(1, engine.sendOutbox().sent)
        assertNull(store.outbox.get(queued))
        assertEquals(1, greenMail.receivedMessages.size)
        policy.blockSender("bob@upm.es")
        assertTrue(runCatching { composeRepository().send(draft, 0) }.isFailure)
        assertEquals(1, greenMail.receivedMessages.size)
    }
    @Test fun migratedTeenCanReadInboxSearchAndUploadDraftsWithoutGuardian() = runBlocking {
        appendMessages("INBOX", listOf(message("Ordinary searchable incoming", from = "bob@upm.es")))
        migrateTeen()
        engine.syncFolderList(account.id)
        val inbox = store.folders.findByFullName(account.id, "INBOX")!!.id
        engine.syncFolder(inbox)
        val repository = mailRepository()
        val id = repository.observeMessages(inbox, 50, false).first().single().id
        repository.ensureBodyLoaded(id).getOrThrow()
        assertNotNull(repository.observeMessage(id).first())
        assertTrue(repository.searchLocal("Ordinary", SearchFilters(), 50).isNotEmpty())
        val draftId = composeRepository().saveDraft(draftTo("bob@upm.es", "Editable draft"), upload = true)
        assertNotNull(store.drafts.get(draftId))
        assertTrue(store.pendingOps.accountsWithPending().isNotEmpty())
        engine.flushPendingOps(account.id)
        assertNotNull(store.drafts.get(draftId))
    }
    @Test fun knownUnder13CannotReadCachedMailSearchOrReleaseAnOldQueue() = runBlocking {
        appendMessages("INBOX", listOf(message("Private cached message", from = "bob@upm.es")))
        engine.syncAccount(account.id)
        val inbox = store.folders.findByFullName(account.id, "INBOX")!!.id
        val queued = store.outbox.enqueue(draftTo("bob@upm.es", "Do not release"), null, clock.now())
        val receivedBefore = greenMail.receivedMessages.size
        install(LocalSafetyRepository(legacySafetyStorage(AgeBand.UNDER_13)))
        val repository = mailRepository()
        assertTrue(repository.observeMessages(inbox, 50, false).first().isEmpty())
        assertTrue(repository.searchLocal("Private", SearchFilters(), 50).isEmpty())
        assertTrue(runCatching { repository.ensureBodyLoaded(store.messages.list(inbox, 50, false).first().id).getOrThrow() }.isFailure)
        assertEquals(0, engine.sendOutbox().sent)
        assertEquals(MailErrorKind.SAFETY_BLOCKED.name, store.outbox.get(queued)!!.item.lastError)
        assertEquals(receivedBefore, greenMail.receivedMessages.size)
    }
    @Test fun blockingRecipientAfterQueueingPreventsTransportUntilExplicitRetry() = runBlocking {
        val policy = migrateTeen()
        val queued = composeRepository().send(draftTo("bob@upm.es", "Blocked after send"), 0)
        policy.blockSender("bob@upm.es")
        assertEquals(0, engine.sendOutbox().sent)
        assertEquals(0, greenMail.receivedMessages.size)
        policy.unblockSender("bob@upm.es")
        assertEquals(0, engine.sendOutbox().sent)
        composeRepository().retrySend(queued)
        assertEquals(1, engine.sendOutbox().sent)
    }
    @Test fun repeatedForegroundChecksKeepQueuedMailAndRetryBudgetUntilChecksFinish() = runBlocking {
        val policy = migrateTeen()
        val queued = composeRepository().send(draftTo("bob@upm.es", "Resume automatically after check"), 0)
        policy.setPlatformCheckPending(true)
        repeat(3) {
            assertEquals(0, engine.sendOutbox().sent)
            val held = store.outbox.get(queued)!!.item
            assertEquals(OutboxState.QUEUED, held.state)
            assertEquals(0, held.attempts)
            assertNull(held.lastError)
            assertEquals(0, greenMail.receivedMessages.size)
            clock.advance(3_000)
        }
        policy.setPlatformCheckPending(false)
        assertEquals(1, engine.sendOutbox().sent)
        assertNull(store.outbox.get(queued))
        assertEquals(1, greenMail.receivedMessages.size)
    }

    @Test fun checkStartingAfterPendingDraftClaimPreservesUploadAndAttempts() = runBlocking {
        val policy = migrateTeen()
        createFolder("Drafts")
        engine.syncFolderList(account.id)
        val draftId = store.drafts.save(draftTo("bob@upm.es", "Preserved during check"))
        val id = store.pendingOps.insert(io.github.pportilla.upmmail.data.db.PendingOp(
            accountId = account.id, type = io.github.pportilla.upmmail.data.db.PendingOpType.APPEND_DRAFT,
            target = draftId.toString(), notBefore = clock.now()))
        onUpdate = { sql, _ ->
            if (sql.startsWith("UPDATE pending_ops SET state = 1 WHERE")) {
                onUpdate = null
                policy.setPlatformCheckPending(true)
            }
        }
        val result = engine.flushPendingOps(account.id)
        assertEquals(MailErrorKind.ACCESS_CHECK_PENDING, result.blockedBy?.kind)
        val retained = store.pendingOps.get(id)!!
        assertEquals(0, retained.attempts)
        assertFalse(retained.inFlight)
        assertTrue(retained.notBefore > clock.now())
        assertNotNull(store.drafts.get(draftId))
        policy.setPlatformCheckPending(false)
        clock.advance(3_000)
        engine.flushPendingOps(account.id)
        assertNull(store.pendingOps.get(id))
        assertNotNull(store.drafts.get(draftId))
    }

    @Test fun legacyAdultQueueWaitsUnchangedForUpdatedTermsThenDeliversExactlyOnce() = runBlocking {
        val queued = store.outbox.enqueue(draftTo("bob@upm.es", "Authorized before app update"), null, clock.now())
        store.outbox.requeue(queued, 2, MailErrorKind.NETWORK.name, clock.now())
        val before = store.outbox.get(queued)!!
        val policy = LocalSafetyRepository(legacySafetyStorage(AgeBand.ADULT), protectExistingOutbox = true)
        install(policy)
        policy.completeGeneralAudienceMigration { fail("Ordinary adult mail must not be converted to review-required") }
        assertTrue(policy.state.value.awaitingTermsAcknowledgement)
        repeat(2) {
            val result = engine.sendOutbox()
            assertEquals(0, result.sent); assertEquals(0, result.failed)
            assertNull("Wait for acknowledgement instead of scheduling a polling retry", result.nextSendAt)
            assertEquals(before, store.outbox.get(queued))
            assertEquals(0, greenMail.receivedMessages.size)
        }
        policy.completeGeneralSetup()
        assertFalse(policy.state.value.awaitingTermsAcknowledgement)
        assertEquals(1, engine.sendOutbox().sent)
        assertEquals(0, engine.sendOutbox().sent)
        assertNull(store.outbox.get(queued))
        assertEquals(1, greenMail.receivedMessages.size)
    }

}
