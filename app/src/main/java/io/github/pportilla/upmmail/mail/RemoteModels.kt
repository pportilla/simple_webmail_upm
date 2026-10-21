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

/**
 * Our own marker in [RemoteFolder.attributes] for folders outside the user's personal namespace
 * (shared folders, other users' mailboxes). It is never sent by a server: the leading "x-" keeps it
 * apart from real IMAP attributes.
 */
const val NOT_PERSONAL_ATTRIBUTE = "\\x-not-personal"

data class RemoteFolderStatus(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    val unreadCount: Int,
)

/** What EXAMINE reported when a folder was opened for searching. */
data class FolderProbe(
    val uidValidity: Long,
    val uidNext: Long,
    /** EXISTS: messages 1..messageCount are addressable by sequence number while the folder stays open. */
    val messageCount: Int,
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

/** The part of a folder one server search command looks at. */
sealed interface SearchRange {
    /** Message sequence numbers [first]..[last], inclusive and 1-based; 1 is the oldest message. */
    data class Seq(val first: Int, val last: Int) : SearchRange

    /**
     * `UID firstUid:*`. When [firstUid] is above every uid, `n:*` still matches the newest message, so
     * answers below [firstUid] are dropped.
     */
    data class UidsFrom(val firstUid: Long) : SearchRange
}

/**
 * The few header fields the search index keeps, read from ENVELOPE, FLAGS, RFC822.SIZE and the
 * Content-Type header only (no BODYSTRUCTURE, no body).
 */
data class IndexedHeader(
    val uid: Long,
    /** Message-ID header with angle brackets. */
    val messageId: String?,
    /** Decoded subject, "" when absent. */
    val subject: String,
    val from: EmailAddress?,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    /** Date header in epoch millis. */
    val sentDate: Long?,
    /** Server arrival time in epoch millis. */
    val internalDate: Long?,
    val size: Long,
    val flags: RemoteFlags,
    /** Guessed from the top-level Content-Type (multipart/mixed, application/..., image/...); approximate. */
    val likelyHasAttachments: Boolean,
)

fun RemoteMessage.toIndexedHeader(): IndexedHeader = IndexedHeader(
    uid = uid,
    messageId = messageId,
    subject = subject,
    from = from,
    to = to,
    cc = cc,
    sentDate = sentDate,
    internalDate = internalDate,
    size = size,
    flags = flags,
    likelyHasAttachments = hasAttachments,
)

data class HeaderBatch(
    val uidValidity: Long,
    /** Ascending UID order. */
    val headers: List<IndexedHeader>,
    /** Requested uids the server no longer has (expunged meanwhile). */
    val missingUids: List<Long>,
)

/** Uids a `UID SEARCH` returned, with the folder state seen by the same EXAMINE. */
class UidList(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    /** Ascending, without duplicates. */
    val uids: LongArray,
)

data class SearchOutcome(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    /** Matching uids, newest (highest) first, at most the requested limit. */
    val uids: List<Long>,
    /** Matches in the searched range before the limit was applied. */
    val totalMatches: Int,
    /**
     * The server refused `CHARSET UTF-8` ([BADCHARSET]): only the query's ASCII variants were
     * searched, or nothing at all when it had none.
     */
    val charsetRejected: Boolean = false,
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
