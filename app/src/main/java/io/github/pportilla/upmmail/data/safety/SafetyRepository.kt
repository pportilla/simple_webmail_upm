package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.MessageDetail
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

enum class Audience { UNCONFIGURED, ADULT, SUPERVISED }
enum class AgeBand { UNDER_13, TEEN, ADULT }

data class SafetyState(
    val audience: Audience = Audience.UNCONFIGURED,
    val ageBand: AgeBand? = null,
    val termsAccepted: Boolean = false,
    val guardianConfigured: Boolean = false,
    val guardianUnlocked: Boolean = false,
    val communicationEnabled: Boolean = false,
    val approvedContacts: Set<String> = emptySet(),
    val blockedSenders: Set<String> = emptySet(),
    val blockedUntilMillis: Long = 0,
    /** Changes whenever approvals, contacts or the guardian session change. */
    val revision: Long = 0,
)

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
    fun isMessageApproved(message: MessageDetail): Boolean
    /** Serializes consuming a one-use approval with creating its outbox row. */
    fun enqueueApproved(draft: Draft, enqueue: () -> Long): Long
    fun requireQueuedSend(outboxId: Long, draft: Draft)
    fun forgetQueuedSend(outboxId: Long)
    fun forgetAccount(accountId: Long)
}

interface SafetyRepository : SafetyPolicy {
    fun chooseAgeBand(band: AgeBand)
    fun acceptTerms()
    suspend fun setupGuardian(pin: String, adultBirthDate: LocalDate)
    suspend fun enableSupervised(ageBand: AgeBand, pin: String, adultBirthDate: LocalDate)
    suspend fun unlockGuardian(pin: String): Boolean
    fun lockGuardian()
    fun setCommunicationEnabled(enabled: Boolean)
    fun approveContact(address: String)
    fun removeContact(address: String)
    /** Children may always make their restrictions stronger. */
    fun blockSender(address: String)
    fun unblockSender(address: String)
    fun approveMessage(message: MessageDetail)
    fun revokeMessage(messageId: Long)
    fun approveOutgoing(draft: Draft)
    fun approveGuardianReport(draft: Draft)
    fun revokeAllApprovals()
    /** Stores a concern locally for the guardian; does not send it to anyone. */
    fun reportMessage(messageId: Long, reason: String)
    fun reports(): List<SafetyReport>
    fun clearReport(id: Long)
}
