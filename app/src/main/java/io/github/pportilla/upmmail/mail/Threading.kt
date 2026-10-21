package io.github.pportilla.upmmail.mail

object Threading {
    /**
     * Thread key: the first (root) entry of References, else In-Reply-To, else the message's own
     * Message-ID, else [fallback]. Lower-cased so that case differences do not split threads.
     */
    fun threadId(messageId: String?, inReplyTo: String?, references: List<String>, fallback: String): String = TODO()
}
