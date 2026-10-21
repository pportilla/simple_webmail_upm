package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import java.util.Locale

object Quoting {
    data class ReplyRecipients(val to: List<EmailAddress>, val cc: List<EmailAddress>)

    private const val MAX_REFERENCES = 20

    // Reply prefixes in the languages UPM users are likely to meet: English, Spanish (RE as used
    // by Outlook; its RV is a forward), German, Nordic, Dutch, Italian, Portuguese, Polish, Turkish...
    private val REPLY_PREFIX = Regex(
        """^\s*(re|aw|sv|vs|antw|ref|rif|res|odp|ynt|atb)\s*(\[\d+]|\(\d+\))?\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )
    private val FORWARD_PREFIX = Regex(
        """^\s*(fwd?|rv|enc|wg|tr|doorst|vl|pd)\s*(\[\d+]|\(\d+\))?\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    /** "Re: " + subject unless it already starts with a reply prefix (Re:, RE:, Re[2]:, AW:...). */
    fun replySubject(subject: String): String {
        val trimmed = subject.trim()
        return if (REPLY_PREFIX.containsMatchIn(trimmed)) trimmed else "Re: $trimmed".trimEnd()
    }

    /** "Fwd: " + subject unless it already starts with a forward prefix (Fwd:, Fw:, RV:, ENC:, WG:...). */
    fun forwardSubject(subject: String): String {
        val trimmed = subject.trim()
        return if (FORWARD_PREFIX.containsMatchIn(trimmed)) trimmed else "Fwd: $trimmed".trimEnd()
    }

    /** Prefixes every line with "> " (an already quoted line becomes ">> ..."). */
    fun quote(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n', ' ', '\t')
        if (normalized.isEmpty()) return ">"
        return normalized.lineSequence().joinToString("\n") { line ->
            when {
                line.startsWith(">") -> ">$line"
                line.isBlank() -> ">"
                else -> "> $line"
            }
        }
    }

    /**
     * Reply goes to Reply-To (or From). Reply-all adds the original To and Cc. Addresses in
     * [selfAddresses] (lower-case) are removed, unless replying to one's own message, in which
     * case the original recipients are used. Duplicates are removed (case-insensitive).
     */
    fun replyRecipients(
        from: EmailAddress?,
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        replyTo: List<EmailAddress>,
        selfAddresses: Set<String>,
        replyAll: Boolean,
    ): ReplyRecipients {
        val self = selfAddresses.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        fun isSelf(address: EmailAddress) = key(address) in self

        val ownMessage = from != null && isSelf(from)
        if (ownMessage) {
            // Following up on a message we sent: write to the same people again.
            val primary = dedupe(to).ifEmpty { dedupe(replyTo.ifEmpty { listOf(from!!) }) }
            val copies = if (replyAll) dedupe(cc).filter { c -> primary.none { key(it) == key(c) } } else emptyList()
            return ReplyRecipients(primary, copies)
        }

        val primary = replyTo.filter { it.address.isNotBlank() }.ifEmpty { listOfNotNull(from) }
        var toList = dedupe(if (replyAll) primary + to else primary).filterNot(::isSelf)
        var ccList = if (replyAll) {
            dedupe(cc).filterNot(::isSelf).filter { c -> toList.none { key(it) == key(c) } }
        } else {
            emptyList()
        }
        if (toList.isEmpty() && ccList.isNotEmpty()) {
            toList = ccList
            ccList = emptyList()
        }
        if (toList.isEmpty()) {
            // Everyone was "self" (e.g. a note to oneself): reply to the sender anyway.
            toList = dedupe(primary)
        }
        return ReplyRecipients(toList, ccList)
    }

    /** References for a reply: the original references plus its Message-ID, deduplicated, at most 20. */
    fun references(originalMessageId: String?, originalReferences: List<String>): List<String> {
        val seen = HashSet<String>()
        val all = (originalReferences + listOfNotNull(originalMessageId))
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
        if (all.size <= MAX_REFERENCES) return all
        // Keep the thread root (first) and the most recent ancestors.
        return listOf(all.first()) + all.takeLast(MAX_REFERENCES - 1)
    }

    private fun key(address: EmailAddress): String = address.address.trim().lowercase(Locale.ROOT)

    private fun dedupe(addresses: List<EmailAddress>): List<EmailAddress> {
        val seen = HashSet<String>()
        return addresses.filter { it.address.isNotBlank() && seen.add(key(it)) }
    }
}
