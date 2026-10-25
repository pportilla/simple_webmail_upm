package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

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

/** Local eligibility and acceptable-use acknowledgement, plus the user's own blocked senders. */
class LocalSafetyRepository(
    private val storage: SafetyStorage = MemorySafetyStorage(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Production must hold any existing queue when a missing/corrupt profile is acknowledged again.
     * False is for isolated, known-empty in-memory policy/test instances only. */
    private val protectExistingOutbox: Boolean = false,
) : SafetyRepository {
    private data class Saved(
        val state: SafetyState = SafetyState(),
        val termsVersion: Int = 0,
        val reports: List<SafetyReport> = emptyList(),
        val needsLegacyRewrite: Boolean = false,
    )
    private val monitor = Any()
    private var saved: Saved = (try { storage.read()?.let(::decode) ?: Saved() } catch (_: Exception) { Saved() }).let {
        if (protectExistingOutbox && it.state.audience == Audience.UNCONFIGURED)
            it.copy(state = it.state.copy(migrationPending = true)) else it
    }
    private val mutableState = MutableStateFlow(saved.state.copy(termsAccepted = saved.termsVersion == TERMS_VERSION && !saved.state.migrationPending))
    override val state: StateFlow<SafetyState> = mutableState.asStateFlow()

    private fun denied(): Nothing = throw MailException(MailErrorKind.SAFETY_BLOCKED, "Complete the 13+ app setup before using email.")
    private fun checkAllowed(condition: Boolean) { if (!condition) denied() }
    private fun manager() { checkAllowed(state.value.mailboxAccessAllowed) }
    private fun update(next: Saved) {
        val nextState = next.state.copy(revision = state.value.revision + 1, termsAccepted = next.termsVersion == TERMS_VERSION && !next.state.migrationPending)
        val value = next.copy(state = nextState)
        storage.write(encode(value))
        saved = value
        mutableState.value = nextState
    }

    override fun chooseAgeBand(band: AgeBand) = synchronized(monitor) {
        checkAllowed(state.value.audience == Audience.UNCONFIGURED && band != AgeBand.UNDER_13 &&
            !state.value.migrationPending && state.value.platformRestriction == PlatformRestriction.NONE)
        update(saved.copy(state = state.value.copy(ageBand = band, audience = Audience.ADULT)))
    }
    override fun acceptTerms() = synchronized(monitor) {
        checkAllowed(state.value.ageBand != AgeBand.UNDER_13 && !state.value.migrationPending &&
            state.value.platformRestriction == PlatformRestriction.NONE)
        update(saved.copy(termsVersion = TERMS_VERSION, state = state.value))
    }
    override fun completeGeneralSetup() = synchronized(monitor) {
        checkAllowed(state.value.ageBand != AgeBand.UNDER_13 && !state.value.migrationPending &&
            state.value.audience != Audience.SUPERVISED && state.value.platformRestriction == PlatformRestriction.NONE)
        update(saved.copy(termsVersion = TERMS_VERSION, state = state.value.copy(
            audience = Audience.ADULT, ageBand = state.value.ageBand ?: AgeBand.AGE_13_PLUS)))
    }

    override fun setPlatformCheckPending(pending: Boolean) = synchronized(monitor) {
        if (state.value.platformCheckPending != pending) {
            val next = state.value.copy(platformCheckPending = pending, revision = state.value.revision + 1)
            saved = saved.copy(state = next)
            mutableState.value = next
        }
    }

    override fun updatePlatformRestriction(restriction: PlatformRestriction) = synchronized(monitor) {
        if (restriction == PlatformRestriction.NONE) {
            // Opening access must survive restart; a failed write leaves the existing block intact.
            if (state.value.platformRestriction != restriction) {
                update(saved.copy(state = state.value.copy(platformRestriction = restriction)))
            }
        } else {
            // A new platform block applies immediately even if the disk cannot persist it.
            val next = state.value.copy(platformRestriction = restriction, revision = state.value.revision + 1)
            saved = saved.copy(state = next)
            mutableState.value = next
            storage.write(encode(saved))
        }
    }

    /**
     * Run on IO before starting background work. The callback commits its transaction before
     * this gate is cleared. A crash/write failure repeats the harmless hold on the next start;
     * neither a failed database migration nor failed policy persistence can enable delivery.
     */
    fun completeGeneralAudienceMigration(holdLegacyQueuedMail: () -> Unit) = synchronized(monitor) {
        if (!state.value.migrationPending && !saved.needsLegacyRewrite) return@synchronized
        if (state.value.migrationPending) holdLegacyQueuedMail()
        update(saved.copy(state = state.value.copy(migrationPending = false), needsLegacyRewrite = false))
    }

    private fun address(value: String): String = normalizedSafetyAddress(value).also {
        checkAllowed(AddressParser.isValidAddress(it))
    }
    override fun blockSender(address: String) = synchronized(monitor) {
        manager()
        val value = address(address)
        update(saved.copy(state = state.value.copy(blockedSenders = state.value.blockedSenders + value)))
    }
    override fun unblockSender(address: String) = synchronized(monitor) {
        manager()
        val value = address(address)
        update(saved.copy(state = state.value.copy(blockedSenders = state.value.blockedSenders - value)))
    }
    override fun isSenderBlocked(address: String?): Boolean =
        address != null && normalizedSafetyAddress(address) in state.value.blockedSenders
    override fun requireNetwork() {
        val current = state.value
        checkAllowed(current.mailboxAccessAllowed)
        if (current.platformCheckPending) throw MailException(MailErrorKind.ACCESS_CHECK_PENDING)
    }
    override fun requireAccountManagement() = manager()
    override fun requireAttachments() = manager()
    override fun requireMessageAccess(message: MessageDetail) {
        manager()
        checkAllowed(!isSenderBlocked(message.summary.from?.address))
    }
    override fun projectMessage(message: MessageDetail): MessageDetail? =
        try { requireMessageAccess(message); message } catch (_: MailException) { null }
    private fun checkDraft(draft: Draft) {
        manager()
        val recipients = draft.to + draft.cc + draft.bcc
        checkAllowed(recipients.isNotEmpty() && recipients.all {
            AddressParser.isValidAddress(it.address) && !isSenderBlocked(it.address)
        })
    }
    override fun enqueueApproved(draft: Draft, enqueue: () -> Long): Long = synchronized(monitor) {
        checkDraft(draft)
        enqueue()
    }
    override fun requireQueuedSend(outboxId: Long, draft: Draft) = synchronized(monitor) {
        checkDraft(draft)
        requireNetwork()
    }
    override fun forgetQueuedSend(outboxId: Long) = Unit
    override fun forgetAccount(accountId: Long) = Unit
    override fun reportMessage(messageId: Long, reason: String) = synchronized(monitor) {
        manager()
        checkAllowed(reason.isNotBlank())
        val report = SafetyReport((saved.reports.maxOfOrNull { it.id } ?: 0) + 1, messageId, reason.take(2000), clock())
        update(saved.copy(reports = (saved.reports + report).takeLast(200), state = state.value))
    }
    override fun reports(): List<SafetyReport> = synchronized(monitor) { manager(); saved.reports.toList() }
    override fun clearReport(id: Long) = synchronized(monitor) {
        manager()
        update(saved.copy(reports = saved.reports.filterNot { it.id == id }, state = state.value))
    }

    private fun encode(value: Saved): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(3)
            out.writeUTF(value.state.audience.name)
            out.writeUTF(value.state.ageBand?.name.orEmpty())
            out.writeInt(value.termsVersion)
            out.writeBoolean(value.state.migrationPending)
            out.writeUTF(value.state.platformRestriction.name)
            out.writeInt(value.state.blockedSenders.size)
            value.state.blockedSenders.forEach(out::writeUTF)
            out.writeInt(value.reports.size)
            value.reports.forEach {
                out.writeLong(it.id); out.writeLong(it.messageId); out.writeUTF(it.reason); out.writeLong(it.createdAt)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    private fun decode(encoded: String): Saved = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
        val version = input.readInt()
        require(version in 1..3)
        val oldAudience = Audience.valueOf(input.readUTF())
        val band = input.readUTF().takeIf { it.isNotEmpty() }?.let(AgeBand::valueOf)
        val terms = input.readInt()
        fun count(): Int = input.readInt().also { require(it in 0..100_000) }
        fun strings(): Set<String> = List(count()) { input.readUTF() }.toSet()
        val pending: Boolean
        val blocked: Set<String>
        var restriction = PlatformRestriction.NONE
        if (version == 1) {
            // Read the complete historical shape; never retain PIN verifiers or obsolete approvals.
            input.readUTF(); input.readUTF(); input.readInt(); input.readLong(); input.readLong(); input.readBoolean()
            strings() // former approved contacts
            blocked = strings()
            repeat(count()) { input.readLong(); input.readUTF() }
            repeat(count()) { input.readUTF(); input.readBoolean() }
            repeat(count()) { input.readLong(); input.readUTF(); input.readBoolean() }
            pending = oldAudience == Audience.SUPERVISED && band == AgeBand.TEEN
        } else {
            pending = input.readBoolean()
            if (version >= 3) restriction = PlatformRestriction.valueOf(input.readUTF())
            blocked = strings()
        }
        val reports = List(count()) { SafetyReport(input.readLong(), input.readLong(), input.readUTF(), input.readLong()) }
        require(input.available() == 0)
        val audience = when {
            band == AgeBand.UNDER_13 -> Audience.SUPERVISED // Known ineligible legacy profile: never silently unlock.
            oldAudience == Audience.SUPERVISED && band == AgeBand.TEEN -> Audience.ADULT
            else -> oldAudience
        }
        Saved(SafetyState(audience = audience, ageBand = band, termsAccepted = terms == TERMS_VERSION,
            blockedSenders = blocked, migrationPending = pending, platformRestriction = restriction), terms, reports, needsLegacyRewrite = version < 3)
    }
    companion object {
        const val TERMS_VERSION = 2
    }
}
