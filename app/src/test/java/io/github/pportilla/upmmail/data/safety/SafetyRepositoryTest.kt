package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.data.db.*
import io.github.pportilla.upmmail.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SafetyRepositoryTest {
    private fun draft() = Draft(accountId = 1, to = listOf(EmailAddress("friend@example.org")), subject = "Unsent", body = "Preserved text")
    private fun blocked(block: () -> Unit) {
        try { block(); fail("Expected policy refusal") } catch (e: MailException) { assertEquals(MailErrorKind.SAFETY_BLOCKED, e.kind) }
    }
    private fun message(): MessageDetail {
        val store = newStore()
        val account = store.addTestAccount()
        val folder = store.addTestFolder(account.id, "INBOX", FolderRole.INBOX)
        val id = store.messages.upsertHeaders(account.id, folder.id, listOf(remoteMessage(1, from = EmailAddress("friend@example.org")))).single()
        store.messages.storeBody(id, "<b>Hello</b>", "Hello", "Hello", emptyList(), "Hello")
        return store.messages.detail(id)!!
    }
    @Test fun unconfiguredAndUnacceptedProfilesCannotUseCredentialsOrNetwork() {
        val policy = LocalSafetyRepository()
        blocked(policy::requireNetwork); blocked(policy::requireAccountManagement); blocked(policy::requireAttachments)
        policy.chooseAgeBand(AgeBand.AGE_13_PLUS)
        blocked(policy::requireNetwork)
        policy.acceptTerms(); policy.requireNetwork(); policy.requireAttachments()
        blocked { policy.chooseAgeBand(AgeBand.ADULT) }
    }
    @Test fun generalSetupAtomicallyAcknowledgesEligibilityAndTermsWithoutCollectingBirthDate() {
        val storage = MemorySafetyStorage()
        val policy = LocalSafetyRepository(storage)
        policy.completeGeneralSetup()
        assertEquals(AgeBand.AGE_13_PLUS, policy.state.value.ageBand)
        assertTrue(policy.state.value.termsAccepted)
        assertEquals(Audience.ADULT, policy.state.value.audience)
        val restarted = LocalSafetyRepository(storage)
        restarted.requireNetwork(); restarted.requireAccountManagement(); restarted.requireAttachments()
    }
    @Test fun everyEligibleAgeBandHasTheSameCompleteMailboxAccess() {
        listOf(AgeBand.TEEN, AgeBand.ADULT, AgeBand.AGE_13_PLUS).forEach { band ->
            val policy = LocalSafetyRepository().apply { chooseAgeBand(band); acceptTerms() }
            policy.requireNetwork(); policy.requireAccountManagement(); policy.requireAttachments()
            val original = message()
            assertEquals(original, policy.projectMessage(original))
            val id = policy.enqueueApproved(draft()) { 9 }
            policy.requireQueuedSend(id, draft())
        }
    }
    @Test fun freshUnder13SetupIsRejected() {
        val policy = LocalSafetyRepository()
        blocked { policy.chooseAgeBand(AgeBand.UNDER_13) }
        assertEquals(Audience.UNCONFIGURED, policy.state.value.audience)
        blocked(policy::requireNetwork)
    }
    @Test fun legacyTeenRemovesGuardianRestrictionsOnlyAfterQueuedMailHoldCompletes() {
        val storage = legacySafetyStorage(AgeBand.TEEN, setOf("blocked@example.org"), listOf(SafetyReport(1, 7, "Saved concern", 9)))
        val policy = LocalSafetyRepository(storage)
        assertTrue(policy.state.value.migrationPending)
        assertEquals(Audience.ADULT, policy.state.value.audience)
        blocked(policy::requireNetwork); blocked(policy::requireAttachments); blocked(policy::completeGeneralSetup)
        var held = false
        policy.completeGeneralAudienceMigration {
            blocked(policy::requireNetwork)
            held = true
        }
        assertTrue(held)
        assertFalse(policy.state.value.migrationPending)
        blocked(policy::requireNetwork) // New terms still need acknowledgement.
        policy.completeGeneralSetup()
        policy.requireNetwork(); policy.requireAttachments()
        assertTrue(policy.isSenderBlocked("blocked@example.org"))
        assertEquals("Saved concern", policy.reports().single().reason)
        val serialized = String(Base64.getDecoder().decode(storage.read()), Charsets.ISO_8859_1)
        assertFalse(serialized.contains("old-incoming-digest"))
        assertFalse(serialized.contains("old-outgoing-digest"))
        val restarted = LocalSafetyRepository(storage)
        assertFalse(restarted.state.value.migrationPending)
        restarted.completeGeneralAudienceMigration { fail("Migration must run once") }
        restarted.requireNetwork()
    }
    @Test fun failedQueueHoldAndFailedPolicyPersistenceCannotUnlockMigratedMail() {
        val original = legacySafetyStorage(AgeBand.TEEN)
        var failing = false
        val storage = object : SafetyStorage {
            override fun read() = original.read()
            override fun write(value: String) { if (failing) error("disk full"); original.write(value) }
        }
        val policy = LocalSafetyRepository(storage)
        assertTrue(runCatching { policy.completeGeneralAudienceMigration { error("database unavailable") } }.isFailure)
        assertTrue(policy.state.value.migrationPending)
        failing = true
        assertTrue(runCatching { policy.completeGeneralAudienceMigration {} }.isFailure)
        assertTrue(policy.state.value.migrationPending)
        blocked(policy::requireNetwork)
        assertTrue(LocalSafetyRepository(storage).state.value.migrationPending)
        failing = false
        policy.completeGeneralAudienceMigration {}
        policy.completeGeneralSetup(); policy.requireNetwork()
    }
    @Test fun knownLegacyUnder13RemainsUnsupportedAcrossRestartAndCannotSelfAcknowledgeIntoAccess() {
        val storage = legacySafetyStorage(AgeBand.UNDER_13)
        repeat(2) {
            val policy = LocalSafetyRepository(storage)
            assertEquals(AgeBand.UNDER_13, policy.state.value.ageBand)
            assertFalse(policy.state.value.migrationPending)
            blocked(policy::completeGeneralSetup); blocked(policy::acceptTerms)
            blocked { policy.chooseAgeBand(AgeBand.AGE_13_PLUS) }
            blocked(policy::requireNetwork); blocked(policy::requireAttachments); blocked(policy::requireAccountManagement)
            policy.completeGeneralAudienceMigration { fail("Under-13 migration must not release any queued mail") }
            assertNull(policy.projectMessage(message()))
            blocked { policy.enqueueApproved(draft()) { fail("Must not enqueue"); 1 } }
            blocked { policy.requireQueuedSend(88, draft()) }
        }
    }
    @Test fun legacyAdultNeedsOnlyUpdatedTermsAndDoesNotHoldExistingQueuedMail() {
        val policy = LocalSafetyRepository(legacySafetyStorage(AgeBand.ADULT))
        assertFalse(policy.state.value.migrationPending)
        policy.completeGeneralAudienceMigration { fail("Ordinary queues must not be changed") }
        blocked(policy::requireNetwork)
        policy.completeGeneralSetup(); policy.requireQueuedSend(1, draft())
    }
    @Test fun blockedSenderDisappearsAndCannotBeSentMailUntilExplicitlyUnblocked() {
        val storage = MemorySafetyStorage()
        val policy = LocalSafetyRepository(storage).apply { completeGeneralSetup() }
        policy.blockSender(" FRIEND@EXAMPLE.ORG ")
        assertNull(policy.projectMessage(message()))
        blocked { policy.enqueueApproved(draft()) { 1 } }
        val restarted = LocalSafetyRepository(storage)
        assertTrue(restarted.isSenderBlocked("friend@example.org"))
        restarted.unblockSender("friend@example.org")
        assertNotNull(restarted.projectMessage(message()))
    }
    @Test fun localReportsRemainAvailableToTheOrdinaryAccountOwner() {
        val policy = LocalSafetyRepository().apply { completeGeneralSetup() }
        policy.reportMessage(8, "Unwanted message")
        assertEquals("Unwanted message", policy.reports().single().reason)
        policy.clearReport(policy.reports().single().id)
        assertTrue(policy.reports().isEmpty())
    }
    @Test fun corruptStorageFailsClosed() {
        val policy = LocalSafetyRepository(MemorySafetyStorage().apply { write("invalid profile") })
        assertEquals(Audience.UNCONFIGURED, policy.state.value.audience)
        blocked(policy::requireNetwork)
    }
    @Test fun queueHoldPreservesMessagesAttachmentsAccountsDraftsAndAlreadyFailedMail() {
        val policy = LocalSafetyRepository(legacySafetyStorage(AgeBand.TEEN))
        val store = MailStore(JdbcSqlDatabase.inMemory(), safety = policy)
        val account = store.addTestAccount()
        val attachment = OutgoingAttachment("content://test/kept", "kept.txt", "text/plain", 12)
        val original = draft().copy(accountId = account.id, attachments = listOf(attachment))
        val queued = store.outbox.enqueue(original, null, 0)
        val sending = store.outbox.enqueue(original.copy(subject = "Possibly sent"), null, 0)
        assertTrue(store.outbox.claim(sending))
        val failed = store.outbox.enqueue(original.copy(subject = "Already failed"), null, 0)
        store.outbox.fail(failed, 4, MailErrorKind.AUTHENTICATION.name)
        val localDraft = store.drafts.save(original)
        policy.completeGeneralAudienceMigration { store.outbox.holdForAudienceMigration() }
        assertTrue(store.outbox.due(null, Long.MAX_VALUE).isEmpty())
        listOf(queued, sending).forEach {
            val item = store.outbox.get(it)!!.item
            assertEquals(OutboxState.FAILED, item.state)
            assertEquals(if (it == sending) MailErrorKind.SEND_UNCERTAIN.name else MailErrorKind.REVIEW_REQUIRED.name, item.lastError)
            assertEquals(listOf(attachment), item.draft.attachments)
            assertEquals(original.body, item.draft.body)
        }
        assertEquals(MailErrorKind.AUTHENTICATION.name, store.outbox.get(failed)!!.item.lastError)
        assertEquals(4, store.outbox.get(failed)!!.item.attempts)
        assertNotNull(store.accounts.get(account.id)); assertNotNull(store.drafts.get(localDraft))
        policy.completeGeneralSetup()
        assertTrue(store.outbox.retry(queued))
        assertEquals(listOf(queued), store.outbox.due(null, Long.MAX_VALUE).map { it.item.id })
    }
    @Test fun missingAndCorruptProductionProfilesCannotReleaseExistingQueueThroughFreshAcknowledgement() {
        listOf<String?>(null, "corrupt profile").forEach { stored ->
            val storage = MemorySafetyStorage().apply { if (stored != null) write(stored) }
            val policy = LocalSafetyRepository(storage, protectExistingOutbox = true)
            val store = MailStore(JdbcSqlDatabase.inMemory(), safety = policy)
            val account = store.addTestAccount()
            val queued = store.outbox.enqueue(draft().copy(accountId = account.id), null, 0)
            assertTrue(policy.state.value.migrationPending)
            blocked(policy::completeGeneralSetup); blocked(policy::acceptTerms)
            blocked { policy.chooseAgeBand(AgeBand.AGE_13_PLUS) }
            blocked { policy.requireQueuedSend(queued, draft()) }
            policy.completeGeneralAudienceMigration { store.outbox.holdForAudienceMigration() }
            policy.completeGeneralSetup()
            policy.requireNetwork()
            assertEquals(OutboxState.FAILED, store.outbox.get(queued)!!.item.state)
            assertTrue(store.outbox.due(null, Long.MAX_VALUE).isEmpty())
            assertEquals(MailErrorKind.REVIEW_REQUIRED.name, store.outbox.get(queued)!!.item.lastError)
        }
    }
    @Test fun explicitPlatformRestrictionsPersistAndBlockEveryMailBoundaryWithoutChangingTeenEligibility() {
        PlatformRestriction.entries.filter { it != PlatformRestriction.NONE }.forEach { restriction ->
            val storage = MemorySafetyStorage()
            val policy = LocalSafetyRepository(storage).apply { chooseAgeBand(AgeBand.TEEN); acceptTerms() }
            policy.updatePlatformRestriction(restriction)
            assertFalse(policy.state.value.mailboxAccessAllowed)
            blocked(policy::requireNetwork); blocked(policy::requireAccountManagement); blocked(policy::requireAttachments)
            blocked(policy::completeGeneralSetup)
            blocked { policy.enqueueApproved(draft()) { fail("Restricted platform profile cannot enqueue"); 1 } }
            blocked { policy.requireQueuedSend(1, draft()) }
            assertNull(policy.projectMessage(message()))
            val restarted = LocalSafetyRepository(storage)
            assertEquals(restriction, restarted.state.value.platformRestriction)
            blocked(restarted::requireNetwork)
            restarted.updatePlatformRestriction(PlatformRestriction.NONE)
            assertEquals(AgeBand.TEEN, restarted.state.value.ageBand)
            assertTrue(restarted.state.value.mailboxAccessAllowed)
            restarted.requireNetwork(); restarted.requireAttachments()
        }
    }
    @Test fun aPlatformBlockTakesEffectDespiteWriteFailureButFailedClearCannotGrantAccess() {
        val persisted = MemorySafetyStorage()
        var failWrites = false
        val storage = object : SafetyStorage {
            override fun read() = persisted.read()
            override fun write(value: String) { if (failWrites) error("disk full"); persisted.write(value) }
        }
        val policy = LocalSafetyRepository(storage).apply { completeGeneralSetup() }
        failWrites = true
        assertTrue(runCatching { policy.updatePlatformRestriction(PlatformRestriction.APPROVAL_PENDING) }.isFailure)
        assertEquals(PlatformRestriction.APPROVAL_PENDING, policy.state.value.platformRestriction)
        blocked(policy::requireNetwork)
        assertTrue(runCatching { policy.updatePlatformRestriction(PlatformRestriction.NONE) }.isFailure)
        blocked(policy::requireNetwork)
        failWrites = false
        policy.updatePlatformRestriction(PlatformRestriction.APPROVAL_PENDING)
        assertEquals(PlatformRestriction.APPROVAL_PENDING, LocalSafetyRepository(storage).state.value.platformRestriction)
        policy.updatePlatformRestriction(PlatformRestriction.NONE)
        policy.requireNetwork()
    }

    @Test fun foregroundPlatformCheckPausesAccessOnlyInThisProcessAndDoesNotErasePersistedRestriction() {
        val storage = MemorySafetyStorage()
        val policy = LocalSafetyRepository(storage).apply { completeGeneralSetup() }
        policy.setPlatformCheckPending(true)
        val pending = runCatching { policy.requireNetwork() }.exceptionOrNull() as MailException
        assertEquals(MailErrorKind.ACCESS_CHECK_PENDING, pending.kind)
        assertTrue(pending.isTransient)
        assertTrue("A brief lookup must not tear down cached mailbox/navigation", policy.state.value.mailboxAccessAllowed)
        policy.requireAccountManagement(); policy.requireAttachments()
        assertNotNull(policy.projectMessage(message()))
        val queued = policy.enqueueApproved(draft()) { 123 }
        assertEquals(MailErrorKind.ACCESS_CHECK_PENDING,
            (runCatching { policy.requireQueuedSend(queued, draft()) }.exceptionOrNull() as MailException).kind)
        assertTrue(LocalSafetyRepository(storage).state.value.mailboxAccessAllowed)
        policy.updatePlatformRestriction(PlatformRestriction.APPROVAL_PENDING)
        policy.setPlatformCheckPending(false)
        blocked(policy::requireNetwork)
        val restarted = LocalSafetyRepository(storage)
        assertFalse(restarted.state.value.platformCheckPending)
        assertEquals(PlatformRestriction.APPROVAL_PENDING, restarted.state.value.platformRestriction)
        blocked(restarted::requireNetwork)
    }

}
