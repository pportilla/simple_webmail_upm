package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.data.db.addTestAccount
import io.github.pportilla.upmmail.data.db.addTestFolder
import io.github.pportilla.upmmail.data.db.newStore
import io.github.pportilla.upmmail.data.db.remoteMessage
import io.github.pportilla.upmmail.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SafetyRepositoryTest {
    private val adultDate = LocalDate.of(1980, 1, 1)
    private val pin = "724961"
    private suspend fun supervised(storage: SafetyStorage = MemorySafetyStorage(), clock: () -> Long = System::currentTimeMillis): LocalSafetyRepository =
        LocalSafetyRepository(storage, clock).apply {
            chooseAgeBand(AgeBand.UNDER_13); acceptTerms(); setupGuardian(pin, adultDate)
            approveContact("friend@example.org"); setCommunicationEnabled(true)
        }
    private fun draft() = Draft(accountId = 1, to = listOf(EmailAddress("friend@example.org")), subject = "Reviewed", body = "This exact text")
    private fun blocked(block: () -> Unit) {
        try { block(); fail("Expected policy refusal") } catch (e: MailException) { assertEquals(MailErrorKind.SAFETY_BLOCKED, e.kind) }
    }
    private fun message(): MessageDetail {
        val store = newStore()
        val account = store.addTestAccount()
        val folder = store.addTestFolder(account.id, "INBOX", FolderRole.INBOX)
        val id = store.messages.upsertHeaders(account.id, folder.id, listOf(remoteMessage(1, from = EmailAddress("friend@example.org")))).single()
        store.messages.storeBody(id, "<b>Hello</b><img src='https://tracker.invalid/pixel'>", "Hello", "Hello", emptyList(), "Hello")
        return store.messages.detail(id)!!
    }
    @Test fun unconfiguredAndUnacceptedProfilesCannotUseCredentialsOrNetwork() {
        val policy = LocalSafetyRepository()
        blocked(policy::requireNetwork); blocked(policy::requireAccountManagement); blocked(policy::requireAttachments)
        policy.chooseAgeBand(AgeBand.ADULT)
        blocked(policy::requireNetwork)
        policy.acceptTerms(); policy.requireNetwork(); policy.requireAttachments()
        blocked { policy.chooseAgeBand(AgeBand.ADULT) }
    }
    @Test fun minorCannotBecomeAdultOrCreateGuardianWithChildBirthDate() = runBlocking {
        val policy = LocalSafetyRepository().apply { chooseAgeBand(AgeBand.TEEN); acceptTerms() }
        blocked { policy.chooseAgeBand(AgeBand.ADULT) }
        try { policy.setupGuardian(pin, LocalDate.now().minusYears(14)); fail() } catch (e: MailException) { assertEquals(MailErrorKind.SAFETY_BLOCKED, e.kind) }
        assertFalse(policy.state.value.guardianConfigured)
        blocked(policy::requireNetwork)
    }
    @Test fun pinVerifierPersistsButSessionAndBirthDateDoNot() = runBlocking {
        val storage = MemorySafetyStorage()
        val policy = supervised(storage)
        assertTrue(policy.state.value.guardianUnlocked)
        val restarted = LocalSafetyRepository(storage)
        assertFalse(restarted.state.value.guardianUnlocked)
        assertTrue(restarted.state.value.guardianConfigured)
        assertFalse(storage.read()!!.contains(pin))
        assertTrue(restarted.unlockGuardian(pin))
        restarted.lockGuardian(); assertFalse(restarted.unlockGuardian("111111"))
    }
    @Test fun pinFailuresSurviveRestartAndClockRollback() = runBlocking {
        var now = 1_800_000_000_000L
        val storage = MemorySafetyStorage(); val policy = supervised(storage) { now }
        policy.lockGuardian()
        repeat(5) { assertFalse(policy.unlockGuardian("000000")) }
        val restarted = LocalSafetyRepository(storage, { now })
        assertFalse(restarted.unlockGuardian(pin))
        now -= 60_000; assertFalse(restarted.unlockGuardian(pin))
        now += 361_000; assertTrue(restarted.unlockGuardian(pin))
    }
    @Test fun approvedMessageBindsReviewedPlaintextAndHeadersAndRevokesImmediately() = runBlocking {
        val policy = supervised(); val message = message()
        val guardianProjection = policy.projectMessage(message)!!
        assertNull(guardianProjection.bodyHtml); assertTrue(guardianProjection.attachments.isEmpty())
        policy.approveMessage(guardianProjection); policy.lockGuardian()
        assertNotNull(policy.projectMessage(message))
        assertTrue(policy.isMessageApproved(guardianProjection))
        assertNull(policy.projectMessage(message.copy(bodyText = "Changed content")))
        assertNull(policy.projectMessage(message.copy(summary = message.summary.copy(subject = "Changed subject"))))
        assertNull(policy.projectMessage(message.copy(summary = message.summary.copy(from = EmailAddress("spoof@example.org")))))
        assertNull(policy.projectMessage(message.copy(bodyLoaded = false)))
        policy.unlockGuardian(pin); policy.revokeMessage(message.summary.id); policy.lockGuardian()
        assertNull(policy.projectMessage(message))
    }
    @Test fun allowlistAloneNeverRevealsMailAndAttachmentsStayBlockedForGuardian() = runBlocking {
        val policy = supervised(); val message = message()
        blocked(policy::requireAttachments)
        policy.lockGuardian(); assertNull(policy.projectMessage(message))
        blocked(policy::requireAccountManagement)
    }
    @Test fun outgoingApprovalIsExactOneUseAndSurvivesShortGuardianLock() = runBlocking {
        val policy = supervised(); val draft = draft()
        policy.approveOutgoing(draft)
        blocked { policy.enqueueApproved(draft.copy(body = "edited")) { 1 } }
        blocked { policy.enqueueApproved(draft.copy(cc = listOf(EmailAddress("other@example.org")))) { 1 } }
        policy.lockGuardian()
        assertEquals(9L, policy.enqueueApproved(draft) { 9 })
        policy.requireQueuedSend(9, draft.copy(id = 23, updatedAt = 123))
        blocked { policy.enqueueApproved(draft) { 10 } }
        blocked { policy.requireQueuedSend(9, draft.copy(subject = "edited")) }
        blocked { policy.requireQueuedSend(10, draft) }
    }
    @Test fun outgoingApprovalPersistsForRetryButRemovalAndRevocationInvalidateIt() = runBlocking {
        val storage = MemorySafetyStorage(); val policy = supervised(storage); val draft = draft()
        policy.approveOutgoing(draft); policy.enqueueApproved(draft) { 7 }
        val restarted = LocalSafetyRepository(storage)
        restarted.requireQueuedSend(7, draft)
        restarted.unlockGuardian(pin); restarted.removeContact("friend@example.org")
        blocked { restarted.requireQueuedSend(7, draft) }
        restarted.revokeAllApprovals(); restarted.lockGuardian()
        blocked(restarted::requireNetwork)
    }
    @Test fun guardianReportExceptionIsExactScopedAndCannotSendWhileLocked() = runBlocking {
        val policy = supervised(); val report = draft().copy(to = listOf(EmailAddress("abuse@provider.example")))
        policy.approveGuardianReport(report)
        assertFalse("abuse@provider.example" in policy.state.value.approvedContacts)
        policy.enqueueApproved(report) { 4 }
        policy.requireQueuedSend(4, report)
        policy.lockGuardian(); blocked { policy.requireQueuedSend(4, report) }
        blocked { policy.enqueueApproved(report) { 5 } }
    }
    @Test fun blockedSenderDisappearsForAdultsAndCannotBeSentMail() {
        val policy = LocalSafetyRepository().apply { chooseAgeBand(AgeBand.ADULT); acceptTerms() }
        policy.blockSender(" FRIEND@EXAMPLE.ORG ")
        assertNull(policy.projectMessage(message()))
        blocked { policy.enqueueApproved(draft()) { 1 } }
        policy.unblockSender("friend@example.org")
        assertNotNull(policy.projectMessage(message()))
    }
    @Test fun childCanBlockButCannotUnblockOrReadLocalReports() = runBlocking {
        val policy = supervised(); policy.lockGuardian()
        policy.blockSender("friend@example.org")
        blocked { policy.unblockSender("friend@example.org") }
        policy.reportMessage(8, "Please review this")
        blocked { policy.reports() }
        policy.unlockGuardian(pin)
        assertEquals("Please review this", policy.reports().single().reason)
    }
    @Test fun transitionToSupervisedInvalidatesLegacyAdultQueue() = runBlocking {
        val policy = LocalSafetyRepository().apply { chooseAgeBand(AgeBand.ADULT); acceptTerms() }
        policy.enqueueApproved(draft()) { 2 }
        policy.enableSupervised(AgeBand.TEEN, pin, adultDate)
        policy.approveContact("friend@example.org"); policy.setCommunicationEnabled(true)
        blocked { policy.requireQueuedSend(2, draft()) }
    }
    @Test fun corruptStorageFailsClosed() {
        val storage = MemorySafetyStorage().apply { write("invalid profile") }
        val policy = LocalSafetyRepository(storage)
        assertEquals(Audience.UNCONFIGURED, policy.state.value.audience)
        blocked(policy::requireNetwork)
    }
    @Test fun nestedAddressAndReferenceFieldsHaveUnambiguousDigests() {
        val a = draft().copy(to = listOf(EmailAddress("a@example.org", "one\nb@example.org\u0000two")))
        val b = draft().copy(to = listOf(EmailAddress("a@example.org", "one"), EmailAddress("b@example.org", "two")))
        assertNotEquals(SafetyDigests.outgoing(a), SafetyDigests.outgoing(b))
        assertNotEquals(SafetyDigests.outgoing(draft().copy(references = listOf("a\nb"))), SafetyDigests.outgoing(draft().copy(references = listOf("a", "b"))))
    }
}
