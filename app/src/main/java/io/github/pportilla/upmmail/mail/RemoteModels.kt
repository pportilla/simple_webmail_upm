package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.EmailAddress
import jakarta.mail.internet.MimeMessage
import java.io.InputStream

data class RemoteFolder(
    val fullName: String,
    val name: String,
    val separator: Char,
    /** Lower-case IMAP attributes, e.g. "\\sent", "\\noselect", "\\haschildren". */
    val attributes: Set<String>,
    val selectable: Boolean,
    val depth: Int,
)

data class RemoteFolderStatus(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    val unreadCount: Int,
)

data class RemoteFlags(
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    /** The `$Forwarded` keyword. */
    val forwarded: Boolean = false,
    val draft: Boolean = false,
    val deleted: Boolean = false,
)

data class RemoteMessage(
    val uid: Long,
    /** Message-ID header with angle brackets. */
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    /** Decoded subject, "" when absent. */
    val subject: String,
    val from: EmailAddress?,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    /** Date header in epoch millis. */
    val sentDate: Long?,
    /** Server arrival time in epoch millis. */
    val internalDate: Long?,
    val size: Long,
    val flags: RemoteFlags,
    /** Derived from BODYSTRUCTURE without downloading the body. */
    val hasAttachments: Boolean,
    val listUnsubscribe: String?,
)

data class FetchResult(
    val uidValidity: Long,
    /** Ascending UID order. */
    val messages: List<RemoteMessage>,
    /** True when more messages matched than the requested limit. */
    val truncated: Boolean,
)

data class ParsedAttachment(
    /** Dot-separated 1-based part path ("2", "1.3") used by ImapClient.downloadPart. */
    val partPath: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    /** Content-ID without angle brackets. */
    val contentId: String?,
    val isInline: Boolean,
)

data class ParsedBody(
    val html: String?,
    val text: String?,
    val attachments: List<ParsedAttachment>,
)

enum class MessageFlag { SEEN, FLAGGED, ANSWERED, FORWARDED, DRAFT, DELETED }

/** Data of an attachment to send; implemented by the data layer (files, content URIs). */
interface AttachmentSource {
    val fileName: String
    val mimeType: String
    fun openStream(): InputStream
}

data class ComposedMessage(
    val mime: MimeMessage,
    /** The generated Message-ID header value, with angle brackets. */
    val messageId: String,
)
