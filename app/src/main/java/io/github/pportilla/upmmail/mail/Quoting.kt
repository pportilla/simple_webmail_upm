package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress

object Quoting {
    data class ReplyRecipients(val to: List<EmailAddress>, val cc: List<EmailAddress>)

    /** "Re: " + subject unless it already starts with a reply prefix (Re:, RE:, Re[2]:, RV:, AW:...). */
    fun replySubject(subject: String): String = TODO()

    /** "Fwd: " + subject unless it already starts with a forward prefix (Fwd:, Fw:, RV:, ENC:, WG:...). */
    fun forwardSubject(subject: String): String = TODO()

    /** Prefixes every line with "> " (an already quoted line becomes ">> ..."). */
    fun quote(text: String): String = TODO()

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
    ): ReplyRecipients = TODO()

    /** References for a reply: the original references plus its Message-ID, deduplicated, at most 20. */
    fun references(originalMessageId: String?, originalReferences: List<String>): List<String> = TODO()
}
