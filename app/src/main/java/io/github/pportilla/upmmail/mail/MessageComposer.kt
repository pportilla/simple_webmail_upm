package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress

object MessageComposer {
    /**
     * Builds the MIME message for [draft] sent by [from]: multipart/alternative (text/plain +
     * generated text/html) wrapped in multipart/mixed when there are attachments, UTF-8
     * everywhere, In-Reply-To/References for replies and a fresh Message-ID.
     */
    fun build(
        from: EmailAddress,
        draft: Draft,
        attachments: List<AttachmentSource>,
        date: Long = System.currentTimeMillis(),
        userAgent: String = "UPM Mail for Android",
    ): ComposedMessage = TODO()
}
