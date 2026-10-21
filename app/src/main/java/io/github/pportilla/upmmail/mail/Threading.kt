package io.github.pportilla.upmmail.mail

import java.util.Locale

object Threading {
    /**
     * Thread key: the first (root) entry of References, else In-Reply-To, else the message's own
     * Message-ID, else [fallback]. Lower-cased so that case differences do not split threads.
     */
    fun threadId(messageId: String?, inReplyTo: String?, references: List<String>, fallback: String): String {
        val root = references.firstNotNullOfOrNull { normalize(it) }
            ?: normalize(inReplyTo)
            ?: normalize(messageId)
        return root ?: fallback
    }

    /** "<id>" in lower case, or null when [value] holds no usable id. */
    private fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val id = HeaderValues.firstMessageId(value) ?: value.trim().takeIf { ' ' !in it }?.let { "<${it.trim('<', '>')}>" }
        return id?.takeIf { it.length > 2 }?.lowercase(Locale.ROOT)
    }
}
