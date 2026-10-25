package io.github.pportilla.upmmail.data.safety

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

/** Exact format written by released versions 1.0.1–1.0.3, independent of the new serializer. */
fun legacySafetyStorage(
    band: AgeBand,
    blocked: Set<String> = emptySet(),
    reports: List<SafetyReport> = emptyList(),
    terms: Int = 1,
): MemorySafetyStorage {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeInt(1)
        out.writeUTF(if (band == AgeBand.ADULT) "ADULT" else "SUPERVISED")
        out.writeUTF(band.name)
        out.writeInt(terms)
        out.writeUTF(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        out.writeUTF(Base64.getEncoder().encodeToString(ByteArray(32) { 9 }))
        out.writeInt(2); out.writeLong(123); out.writeLong(456); out.writeBoolean(true)
        out.writeInt(1); out.writeUTF("friend@example.org")
        out.writeInt(blocked.size); blocked.forEach(out::writeUTF)
        out.writeInt(1); out.writeLong(77); out.writeUTF("old-incoming-digest")
        out.writeInt(1); out.writeUTF("old-outgoing-digest"); out.writeBoolean(false)
        out.writeInt(1); out.writeLong(88); out.writeUTF("old-queue-digest"); out.writeBoolean(false)
        out.writeInt(reports.size)
        reports.forEach { out.writeLong(it.id); out.writeLong(it.messageId); out.writeUTF(it.reason); out.writeLong(it.createdAt) }
    }
    return MemorySafetyStorage().apply { write(Base64.getEncoder().encodeToString(bytes.toByteArray())) }
}

/** Exercise runtime cancellation without retaining a production age/profile-switching interface. */
class RevocableTestSafety(private val delegate: LocalSafetyRepository = LocalSafetyRepository().apply { completeGeneralSetup() }) : SafetyPolicy by delegate {
    private val mutable = MutableStateFlow(delegate.state.value)
    override val state: StateFlow<SafetyState> = mutable
    fun revoke() { mutable.value = mutable.value.copy(termsAccepted = false, revision = mutable.value.revision + 1) }
    override fun requireNetwork() {
        if (!state.value.termsAccepted) throw MailException(MailErrorKind.SAFETY_BLOCKED)
        delegate.requireNetwork()
    }
    override fun requireAccountManagement() = requireNetwork()
}
