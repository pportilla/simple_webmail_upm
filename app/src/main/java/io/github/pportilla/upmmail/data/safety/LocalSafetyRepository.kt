package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

interface SafetyStorage {
    fun read(): String?
    /** Must atomically replace the previous value or throw. */
    fun write(value: String)
}

class MemorySafetyStorage : SafetyStorage {
    private var value: String? = null
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
}

/** A persisted policy with a process-only guardian session. Birth dates and PINs are never stored. */
class LocalSafetyRepository(
    private val storage: SafetyStorage = MemorySafetyStorage(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) : SafetyRepository {
    private data class Approval(val digest: String, val report: Boolean)
    private data class Saved(
        val state: SafetyState = SafetyState(), val termsVersion: Int = 0,
        val salt: String = "", val verifier: String = "", val failures: Int = 0,
        val lastAttempt: Long = 0, val incoming: Map<Long, String> = emptyMap(),
        val outgoing: Map<String, Approval> = emptyMap(), val queued: Map<Long, Approval> = emptyMap(),
        val reports: List<SafetyReport> = emptyList(),
    )
    private val monitor = Any()
    private var saved: Saved = try { storage.read()?.let(::decode) ?: Saved() } catch (_: Exception) { Saved() }
    private val mutableState = MutableStateFlow(saved.state.copy(guardianUnlocked = false, termsAccepted = saved.termsVersion == TERMS_VERSION))
    override val state: StateFlow<SafetyState> = mutableState.asStateFlow()
    private var sessionEpoch = 0L

    private fun denied(): Nothing = throw MailException(MailErrorKind.SAFETY_BLOCKED, "This action needs the current safety profile and guardian approval.")
    private fun checkAllowed(condition: Boolean) { if (!condition) denied() }
    private fun guardian() { checkAllowed(state.value.audience == Audience.SUPERVISED && state.value.guardianConfigured && state.value.guardianUnlocked) }
    private fun manager() { checkAllowed(state.value.termsAccepted && (state.value.audience == Audience.ADULT || state.value.guardianUnlocked)) }
    private fun update(next: Saved) {
        val nextState = next.state.copy(revision = state.value.revision + 1, termsAccepted = next.termsVersion == TERMS_VERSION)
        val value = next.copy(state = nextState)
        storage.write(encode(value))
        saved = value
        mutableState.value = nextState
    }
    private fun clearApprovals(next: Saved = saved) = next.copy(incoming = emptyMap(), outgoing = emptyMap(), queued = emptyMap())
    override fun chooseAgeBand(band: AgeBand) = synchronized(monitor) {
        checkAllowed(state.value.audience == Audience.UNCONFIGURED)
        update(saved.copy(state = state.value.copy(ageBand = band, audience = if (band == AgeBand.ADULT) Audience.ADULT else Audience.SUPERVISED)))
    }
    override fun acceptTerms() = synchronized(monitor) { update(saved.copy(termsVersion = TERMS_VERSION, state = state.value)) }

    private fun validateGuardian(pin: String, birthDate: LocalDate) {
        checkAllowed(pin.length in 6..64 && pin.all { it in '0'..'9' })
        checkAllowed(!birthDate.isAfter(today().minusYears(18)) && !birthDate.isBefore(today().minusYears(120)))
    }
    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    override suspend fun setupGuardian(pin: String, adultBirthDate: LocalDate) {
        validateGuardian(pin, adultBirthDate)
        val startingEpoch = synchronized(monitor) { sessionEpoch }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = withContext(Dispatchers.Default) { derive(pin, salt) }
        synchronized(monitor) {
            checkAllowed(state.value.audience == Audience.SUPERVISED && !state.value.guardianConfigured)
            update(clearApprovals(saved).copy(salt = b64(salt), verifier = b64(verifier), failures = 0,
                state = state.value.copy(guardianConfigured = true, guardianUnlocked = startingEpoch == sessionEpoch, communicationEnabled = false)))
        }
    }
    override suspend fun enableSupervised(ageBand: AgeBand, pin: String, adultBirthDate: LocalDate) {
        validateGuardian(pin, adultBirthDate)
        checkAllowed(ageBand != AgeBand.ADULT)
        synchronized(monitor) { manager() }
        val startingEpoch = synchronized(monitor) { sessionEpoch }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = withContext(Dispatchers.Default) { derive(pin, salt) }
        synchronized(monitor) {
            manager()
            val mayUnlock = startingEpoch == sessionEpoch
            sessionEpoch++
            update(clearApprovals(saved).copy(salt = b64(salt), verifier = b64(verifier), failures = 0,
                state = state.value.copy(audience = Audience.SUPERVISED, ageBand = ageBand, guardianConfigured = true,
                    guardianUnlocked = mayUnlock, communicationEnabled = false, approvedContacts = emptySet(), blockedUntilMillis = 0)))
        }
    }
    override suspend fun unlockGuardian(pin: String): Boolean {
        val attempt = synchronized(monitor) {
            val now = clock()
            if (!state.value.guardianConfigured || now < state.value.blockedUntilMillis || now < saved.lastAttempt) return false
            // Record the attempt before expensive work, so parallel attempts and process death cannot bypass throttling.
            val failures = saved.failures + 1
            update(saved.copy(failures = failures, lastAttempt = now, state = state.value.copy(
                blockedUntilMillis = if (failures >= MAX_FAILURES) now + LOCKOUT_MILLIS else 0)))
            Triple(saved.salt, saved.verifier, sessionEpoch)
        }
        val matches = withContext(Dispatchers.Default) {
            pin.length in 6..64 && MessageDigest.isEqual(derive(pin, Base64.getDecoder().decode(attempt.first)), Base64.getDecoder().decode(attempt.second))
        }
        return synchronized(monitor) {
            if (!matches || attempt.third != sessionEpoch) return@synchronized false
            update(saved.copy(failures = 0, state = state.value.copy(guardianUnlocked = true, blockedUntilMillis = 0)))
            true
        }
    }
    override fun lockGuardian() = synchronized(monitor) {
        sessionEpoch++
        if (state.value.guardianUnlocked) {
            // Session lock must succeed even if persistence is temporarily unavailable.
            mutableState.value = state.value.copy(guardianUnlocked = false, revision = state.value.revision + 1)
            saved = saved.copy(state = state.value)
        }
    }
    override fun setCommunicationEnabled(enabled: Boolean) = synchronized(monitor) {
        guardian()
        update((if (enabled) saved else clearApprovals()).copy(state = state.value.copy(communicationEnabled = enabled)))
    }
    private fun address(value: String): String = normalizedSafetyAddress(value).also { checkAllowed(AddressParser.isValidAddress(it)) }
    override fun approveContact(address: String) = synchronized(monitor) {
        guardian(); val value = address(address)
        checkAllowed(value !in state.value.blockedSenders)
        update(saved.copy(state = state.value.copy(approvedContacts = state.value.approvedContacts + value)))
    }
    override fun removeContact(address: String) = synchronized(monitor) {
        guardian(); val value = address(address)
        update(clearApprovals().copy(state = state.value.copy(approvedContacts = state.value.approvedContacts - value)))
    }
    override fun blockSender(address: String) = synchronized(monitor) {
        checkAllowed(state.value.audience != Audience.UNCONFIGURED)
        val value = address(address)
        update(clearApprovals().copy(state = state.value.copy(blockedSenders = state.value.blockedSenders + value,
            approvedContacts = state.value.approvedContacts - value)))
    }
    override fun unblockSender(address: String) = synchronized(monitor) {
        manager(); val value = address(address)
        update(saved.copy(state = state.value.copy(blockedSenders = state.value.blockedSenders - value)))
    }
    override fun isSenderBlocked(address: String?): Boolean = address != null && normalizedSafetyAddress(address) in state.value.blockedSenders
    override fun requireNetwork() {
        val current = state.value
        checkAllowed(current.termsAccepted && (current.audience == Audience.ADULT ||
            current.audience == Audience.SUPERVISED && current.guardianConfigured && (current.guardianUnlocked || current.communicationEnabled)))
    }
    override fun requireAccountManagement() = synchronized(monitor) { manager() }
    override fun requireAttachments() { checkAllowed(state.value.termsAccepted && state.value.audience == Audience.ADULT) }
    override fun isMessageApproved(message: MessageDetail): Boolean = synchronized(monitor) {
        state.value.termsAccepted && state.value.audience == Audience.SUPERVISED && state.value.communicationEnabled &&
            message.bodyLoaded && !isSenderBlocked(message.summary.from?.address) &&
            normalizedSafetyAddress(message.summary.from?.address.orEmpty()) in state.value.approvedContacts &&
            saved.incoming[message.summary.id] == SafetyDigests.message(message)
    }
    override fun requireMessageAccess(message: MessageDetail) {
        val current = state.value
        checkAllowed(current.termsAccepted && !isSenderBlocked(message.summary.from?.address) &&
            (current.audience == Audience.ADULT || current.audience == Audience.SUPERVISED &&
                (current.guardianUnlocked || current.communicationEnabled && isMessageApproved(message))))
    }
    override fun projectMessage(message: MessageDetail): MessageDetail? {
        try { requireMessageAccess(message) } catch (_: MailException) { return null }
        return if (state.value.audience == Audience.SUPERVISED) message.copy(bodyHtml = null,
            bodyText = SafetyDigests.plainText(message), attachments = emptyList(), listUnsubscribe = null,
            summary = message.summary.copy(hasAttachments = false)) else message
    }
    override fun approveMessage(message: MessageDetail) = synchronized(monitor) {
        guardian()
        checkAllowed(message.bodyLoaded && !isSenderBlocked(message.summary.from?.address) &&
            normalizedSafetyAddress(message.summary.from?.address.orEmpty()) in state.value.approvedContacts)
        update(saved.copy(incoming = saved.incoming + (message.summary.id to SafetyDigests.message(message)), state = state.value))
    }
    override fun revokeMessage(messageId: Long) = synchronized(monitor) {
        guardian(); update(saved.copy(incoming = saved.incoming - messageId, state = state.value))
    }
    private fun checkDraft(draft: Draft, report: Boolean) {
        requireNetwork()
        val recipients = draft.to + draft.cc + draft.bcc
        checkAllowed(recipients.isNotEmpty() && recipients.all { AddressParser.isValidAddress(it.address) && !isSenderBlocked(it.address) })
        if (state.value.audience == Audience.SUPERVISED) {
            checkAllowed(draft.attachments.isEmpty())
            if (report) guardian() else checkAllowed(state.value.communicationEnabled && recipients.all {
                normalizedSafetyAddress(it.address) in state.value.approvedContacts
            })
        }
    }
    override fun approveOutgoing(draft: Draft) = synchronized(monitor) {
        guardian(); checkDraft(draft, false)
        val digest = SafetyDigests.outgoing(draft)
        update(saved.copy(outgoing = saved.outgoing + (digest to Approval(digest, false)), state = state.value))
    }
    override fun approveGuardianReport(draft: Draft) = synchronized(monitor) {
        guardian(); checkDraft(draft, true)
        val digest = SafetyDigests.outgoing(draft)
        update(saved.copy(outgoing = saved.outgoing + (digest to Approval(digest, true)), state = state.value))
    }
    override fun enqueueApproved(draft: Draft, enqueue: () -> Long): Long = synchronized(monitor) {
        val digest = SafetyDigests.outgoing(draft)
        val approval = saved.outgoing[digest]
        checkDraft(draft, approval?.report == true)
        if (state.value.audience == Audience.SUPERVISED) checkAllowed(approval != null)
        val id = enqueue()
        if (state.value.audience == Audience.SUPERVISED) update(saved.copy(outgoing = saved.outgoing - digest,
            queued = saved.queued + (id to approval!!), state = state.value))
        id
    }
    override fun requireQueuedSend(outboxId: Long, draft: Draft) = synchronized(monitor) {
        val approval = saved.queued[outboxId]
        checkDraft(draft, approval?.report == true)
        if (state.value.audience == Audience.SUPERVISED) checkAllowed(approval?.digest == SafetyDigests.outgoing(draft))
    }
    override fun forgetQueuedSend(outboxId: Long) = synchronized(monitor) {
        if (outboxId in saved.queued) update(saved.copy(queued = saved.queued - outboxId, state = state.value))
    }
    override fun forgetAccount(accountId: Long) = synchronized(monitor) { update(clearApprovals().copy(state = state.value)) }
    override fun revokeAllApprovals() = synchronized(monitor) {
        guardian(); update(clearApprovals().copy(state = state.value.copy(communicationEnabled = false)))
    }
    override fun reportMessage(messageId: Long, reason: String) = synchronized(monitor) {
        checkAllowed(state.value.audience != Audience.UNCONFIGURED && reason.isNotBlank())
        val report = SafetyReport((saved.reports.maxOfOrNull { it.id } ?: 0) + 1, messageId, reason.take(2000), clock())
        update(saved.copy(reports = (saved.reports + report).takeLast(200), state = state.value))
    }
    override fun reports(): List<SafetyReport> = synchronized(monitor) { manager(); saved.reports.toList() }
    override fun clearReport(id: Long) = synchronized(monitor) { manager(); update(saved.copy(reports = saved.reports.filterNot { it.id == id }, state = state.value)) }

    private fun encode(value: Saved): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(value.state.audience.name); out.writeUTF(value.state.ageBand?.name.orEmpty())
            out.writeInt(value.termsVersion); out.writeUTF(value.salt); out.writeUTF(value.verifier)
            out.writeInt(value.failures); out.writeLong(value.lastAttempt); out.writeLong(value.state.blockedUntilMillis)
            out.writeBoolean(value.state.communicationEnabled)
            fun strings(items: Set<String>) { out.writeInt(items.size); items.forEach(out::writeUTF) }
            strings(value.state.approvedContacts); strings(value.state.blockedSenders)
            out.writeInt(value.incoming.size); value.incoming.forEach { (id, hash) -> out.writeLong(id); out.writeUTF(hash) }
            out.writeInt(value.outgoing.size); value.outgoing.forEach { (hash, approval) -> out.writeUTF(hash); out.writeBoolean(approval.report) }
            out.writeInt(value.queued.size); value.queued.forEach { (id, approval) -> out.writeLong(id); out.writeUTF(approval.digest); out.writeBoolean(approval.report) }
            out.writeInt(value.reports.size); value.reports.forEach { out.writeLong(it.id); out.writeLong(it.messageId); out.writeUTF(it.reason); out.writeLong(it.createdAt) }
        }
        return b64(bytes.toByteArray())
    }
    private fun decode(encoded: String): Saved = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
        require(input.readInt() == 1)
        val audience = Audience.valueOf(input.readUTF()); val band = input.readUTF().takeIf { it.isNotEmpty() }?.let(AgeBand::valueOf)
        val terms = input.readInt(); val salt = input.readUTF(); val verifier = input.readUTF()
        val failures = input.readInt(); val lastAttempt = input.readLong(); val blockedUntil = input.readLong(); val enabled = input.readBoolean()
        fun count(): Int = input.readInt().also { require(it in 0..100_000) }
        fun strings(): Set<String> = List(count()) { input.readUTF() }.toSet()
        val contacts = strings(); val blocked = strings()
        val incoming = List(count()) { input.readLong() to input.readUTF() }.toMap()
        val outgoing = List(count()) { val hash = input.readUTF(); hash to Approval(hash, input.readBoolean()) }.toMap()
        val queued = List(count()) { input.readLong() to Approval(input.readUTF(), input.readBoolean()) }.toMap()
        val reports = List(count()) { SafetyReport(input.readLong(), input.readLong(), input.readUTF(), input.readLong()) }
        require(input.available() == 0 && failures >= 0)
        val configured = salt.isNotEmpty() && verifier.isNotEmpty()
        if (configured) require(Base64.getDecoder().decode(salt).size == 32 && Base64.getDecoder().decode(verifier).size == 32)
        Saved(SafetyState(audience, band, terms == TERMS_VERSION, configured, false, enabled, contacts, blocked, blockedUntil),
            terms, salt, verifier, failures, lastAttempt, incoming, outgoing, queued, reports)
    }
    private fun b64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
    companion object {
        const val TERMS_VERSION = 1
        private const val PIN_ITERATIONS = 210_000
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MILLIS = 5 * 60_000L
    }
}
