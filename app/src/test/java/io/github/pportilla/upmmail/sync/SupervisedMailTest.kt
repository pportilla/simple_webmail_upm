package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.safety.*
import io.github.pportilla.upmmail.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Real IMAP/SMTP integration: authorization remains below Compose and background workers. */
class SupervisedMailTest : SyncTestBase() {
    private suspend fun supervise() = (store.safety as SafetyRepository).apply {
        enableSupervised(AgeBand.UNDER_13, "735182", LocalDate.of(1980, 1, 1))
        approveContact("bob@upm.es"); setCommunicationEnabled(true)
    }
    @Test fun unapprovedAndRevokedQueueNeverReachesSmtp() = runBlocking {
        val policy = supervise(); val draft = draftTo("bob@upm.es", "Child private text")
        val unauthorized = store.outbox.enqueue(draft, null, clock.now())
        engine.sendOutbox()
        assertEquals(MailErrorKind.SAFETY_BLOCKED.name, store.outbox.get(unauthorized)!!.item.lastError)
        assertEquals(0, greenMail.receivedMessages.size)
        policy.approveOutgoing(draft)
        val approved = composeRepository().send(draft, 0)
        policy.revokeAllApprovals(); policy.lockGuardian()
        engine.sendOutbox()
        assertEquals(MailErrorKind.SAFETY_BLOCKED.name, store.outbox.get(approved)!!.item.lastError)
        assertEquals(0, greenMail.receivedMessages.size)
    }
    @Test fun exactApprovedMailSendsAfterGuardianSessionLocks() = runBlocking {
        val policy = supervise(); val draft = draftTo("bob@upm.es", "Reviewed by guardian")
        policy.approveOutgoing(draft)
        val id = composeRepository().send(draft, 0)
        policy.lockGuardian()
        engine.sendOutbox()
        assertNull(store.outbox.get(id))
        assertEquals(1, greenMail.receivedMessages.size)
        assertEquals("Reviewed by guardian", greenMail.receivedMessages.single().subject)
    }
    @Test fun childCannotReadPendingInboxOrRemoteSearchAndSupervisedDraftNeverUploads() = runBlocking {
        appendMessages("INBOX", listOf(message("Pending guardian review")))
        engine.syncFolderList(account.id)
        val policy = supervise()
        engine.syncFolder(store.folders.findByFullName(account.id, "INBOX")!!.id)
        val repository = mailRepository()
        assertTrue(repository.observeMessages(store.folders.findByFullName(account.id, "INBOX")!!.id, 50, false).first().isNotEmpty())
        val draftId = composeRepository().saveDraft(draftTo("bob@upm.es", "Unsent draft"), upload = true)
        assertNotNull(store.drafts.get(draftId))
        assertTrue(store.pendingOps.accountsWithPending().isEmpty())
        policy.lockGuardian()
        assertTrue(repository.observeMessages(store.folders.findByFullName(account.id, "INBOX")!!.id, 50, false).first().isEmpty())
        assertTrue(repository.searchLocal("Pending", SearchFilters(), 50).isEmpty())
        assertTrue(repository.searchLocalHits("Pending", SearchFilters(), 50).isEmpty())
    }
    @Test fun legacyQueuedDraftUploadIsRejectedAfterSwitchToChildProfile() = runBlocking {
        val draftId = store.drafts.save(draftTo("bob@upm.es", "Private unfinished text"))
        val opId = store.pendingOps.insert(io.github.pportilla.upmmail.data.db.PendingOp(
            accountId = account.id, type = io.github.pportilla.upmmail.data.db.PendingOpType.APPEND_DRAFT,
            target = draftId.toString(), notBefore = clock.now()))
        supervise()
        engine.flushPendingOps(account.id)
        assertNotNull(store.pendingOps.get(opId))
        assertTrue(store.pendingOps.forAccount(account.id).none { it.id == opId })
        assertNotNull(store.drafts.get(draftId))
        assertEquals(0, greenMail.receivedMessages.size)
    }

    @Test fun approvedIncomingBodyIsPlainTextAndRevocationRemovesItFromRepository() = runBlocking {
        appendMessages("INBOX", listOf(message("Reviewed incoming", from = "bob@upm.es")))
        engine.syncFolderList(account.id)
        val policy = supervise()
        val inboxId = store.folders.findByFullName(account.id, "INBOX")!!.id
        engine.syncFolder(inboxId)
        val repository = mailRepository()
        val id = repository.observeMessages(inboxId, 50, false).first().single().id
        repository.ensureBodyLoaded(id).getOrThrow()
        val review = repository.observeMessage(id).first()!!
        assertTrue(review.bodyLoaded); assertNull(review.bodyHtml); assertTrue(review.attachments.isEmpty())
        policy.approveMessage(review); policy.lockGuardian()
        assertEquals(id, repository.observeMessages(inboxId, 50, false).first().single().id)
        assertNotNull(repository.observeMessage(id).first())
        policy.unlockGuardian("735182"); policy.revokeMessage(id); policy.lockGuardian()
        assertNull(repository.observeMessage(id).first())
        assertTrue(repository.observeMessages(inboxId, 50, false).first().isEmpty())
    }

}
