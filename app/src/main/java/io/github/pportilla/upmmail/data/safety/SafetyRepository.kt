package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.MessageDetail
import kotlinx.coroutines.flow.StateFlow

/** ADULT is the persisted legacy name for ordinary access, now available to all eligible 13+ users. */
enum class Audience { UNCONFIGURED, ADULT, SUPERVISED }
enum class AgeBand { UNDER_13, TEEN, ADULT, AGE_13_PLUS }
/** Only an explicit platform requirement can impose these restrictions; ordinary 13–17 users have NONE. */
enum class PlatformRestriction { NONE, UNDER_13, VERIFICATION_REQUIRED, APPROVAL_PENDING, APPROVAL_DECLINED }

data class SafetyState(
    val audience: Audience = Audience.UNCONFIGURED,
    val ageBand: AgeBand? = null,
    val termsAccepted: Boolean = false,
    val blockedSenders: Set<String> = emptySet(),
    val platformRestriction: PlatformRestriction = PlatformRestriction.NONE,
    /** A foreground platform request is in flight; never persisted across process death. */
    val platformCheckPending: Boolean = false,
    /** Legacy queued mail must be held before an old teen profile gains ordinary access. */
    val migrationPending: Boolean = false,
    /** Changes whenever local access or blocked senders change. */
    val revision: Long = 0,
) {
    val mailboxAccessAllowed: Boolean get() = audience == Audience.ADULT && ageBand != AgeBand.UNDER_13 &&
        termsAccepted && !migrationPending && platformRestriction == PlatformRestriction.NONE
    /** Existing ordinary mail waits unchanged for the updated terms, without a polling loop. */
    val awaitingTermsAcknowledgement: Boolean get() = audience == Audience.ADULT && ageBand != AgeBand.UNDER_13 &&
        !termsAccepted && !migrationPending && platformRestriction == PlatformRestriction.NONE
}

data class SafetyReport(val id: Long, val messageId: Long, val reason: String, val createdAt: Long)

/** Authorization is checked below the UI, including background work and existing queued mail. */
interface SafetyPolicy {
    val state: StateFlow<SafetyState>
    fun requireNetwork()
    fun requireAccountManagement()
    fun requireAttachments()
    fun requireMessageAccess(message: MessageDetail)
    fun projectMessage(message: MessageDetail): MessageDetail?
    fun isSenderBlocked(address: String?): Boolean
    /** Serializes recipient/block checks with creating its outbox row. */
    fun enqueueApproved(draft: Draft, enqueue: () -> Long): Long
    fun requireQueuedSend(outboxId: Long, draft: Draft)
    fun forgetQueuedSend(outboxId: Long)
    fun forgetAccount(accountId: Long)
}

interface SafetyRepository : SafetyPolicy {
    fun chooseAgeBand(band: AgeBand)
    fun acceptTerms()
    /** One atomic acknowledgement: age 13+, authorized mailbox use and current acceptable-use terms. */
    fun completeGeneralSetup()
    /** Updates only the coarse access requirement supplied by the distribution platform. */
    fun updatePlatformRestriction(restriction: PlatformRestriction)
    fun setPlatformCheckPending(pending: Boolean)
    fun blockSender(address: String)
    fun unblockSender(address: String)
    /** Saves a local report; sending a provider report is a separate explicit compose action. */
    fun reportMessage(messageId: Long, reason: String)
    fun reports(): List<SafetyReport>
    fun clearReport(id: Long)
}
