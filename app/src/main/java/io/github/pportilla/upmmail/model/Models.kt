package io.github.pportilla.upmmail.model

/**
 * Domain models shared by every layer. They are plain immutable data: no Android types, so the
 * mail engine and its JVM tests can use them too.
 */

enum class SecurityMode { SSL_TLS, STARTTLS, NONE }

data class ServerSettings(
    val host: String,
    val port: Int,
    val security: SecurityMode,
    /** Login name sent to the server. UPM IMAP usually wants the part before the '@'. */
    val username: String,
)

data class Account(
    val id: Long,
    val email: String,
    /** Name shown to recipients in the From header. */
    val displayName: String,
    val imap: ServerSettings,
    val smtp: ServerSettings,
    val signature: String,
    /** ARGB colour used for the account chip / avatar in the drawer. */
    val color: Int,
    val notificationsEnabled: Boolean,
    val sortOrder: Int,
)

enum class FolderRole { INBOX, DRAFTS, SENT, ARCHIVE, TRASH, JUNK, OTHER }

data class Folder(
    val id: Long,
    val accountId: Long,
    /** IMAP full name, e.g. "INBOX.Sent" or "Enviados". */
    val fullName: String,
    /** Last path segment as reported by the server (UI localises well-known roles instead). */
    val displayName: String,
    val role: FolderRole,
    /** Nesting depth (0 for top level) used for indentation in the drawer. */
    val depth: Int,
    val unreadCount: Int,
    val totalCount: Int,
    val selectable: Boolean,
    val lastSyncedAt: Long?,
)

data class EmailAddress(
    val address: String,
    val name: String? = null,
) {
    /** Name when present, otherwise the address. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address

    /** RFC 5322-ish rendering, e.g. `Ana Pérez <ana@upm.es>`. */
    override fun toString(): String =
        if (name.isNullOrBlank()) address else "$name <$address>"
}

/** One row of a message list. */
data class MessageSummary(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val threadId: String,
    val subject: String,
    val from: EmailAddress?,
    val to: List<EmailAddress>,
    /** Epoch millis: the Date header, falling back to the server's internal date. */
    val date: Long,
    val snippet: String,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isAnswered: Boolean,
    val isForwarded: Boolean,
    val isDraft: Boolean,
    val hasAttachments: Boolean,
    /** Messages of the same thread in this folder (1 when conversation view is off). */
    val threadCount: Int = 1,
)

data class Attachment(
    val id: Long,
    val messageId: Long,
    /** MIME part path inside the message, e.g. "2" or "1.3", used to download it later. */
    val partPath: String,
    val fileName: String,
    val mimeType: String,
    /** Size in bytes as announced by the server (encoded size estimate), -1 if unknown. */
    val size: Long,
    /** Content-ID without angle brackets, for inline images referenced as cid:. */
    val contentId: String?,
    val isInline: Boolean,
    /** Absolute path of the downloaded copy, null until downloaded. */
    val localPath: String?,
)

data class MessageDetail(
    val summary: MessageSummary,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    /** Message-ID header including angle brackets. */
    val messageIdHeader: String?,
    val inReplyTo: String?,
    val references: List<String>,
    /** Raw (unsanitised) HTML body if the message has one. Sanitise before rendering. */
    val bodyHtml: String?,
    val bodyText: String?,
    /** False until the body has been downloaded from the server. */
    val bodyLoaded: Boolean,
    val attachments: List<Attachment>,
    /** Raw List-Unsubscribe header, if any. */
    val listUnsubscribe: String?,
    val folderRole: FolderRole,
)

data class Contact(
    val email: String,
    val name: String?,
    /** How many times the user has sent mail to this address. */
    val timesContacted: Int,
    /** How many times this address appeared in received mail. */
    val timesSeen: Int,
    val lastUsed: Long,
)

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, EDIT_DRAFT }

data class OutgoingAttachment(
    /** content:// or file:// URI of the data to attach. */
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
)

/** Everything needed to build and send a message; also the persisted form of a draft. */
data class Draft(
    /** Local draft id, 0 when not yet saved. */
    val id: Long = 0,
    val accountId: Long,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    /** Plain-text body including the signature and any quoted text. */
    val body: String = "",
    val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val mode: ComposeMode = ComposeMode.NEW,
    /** Local message id this draft replies to / forwards, so flags can be updated after sending. */
    val sourceMessageId: Long? = null,
    /** Local message id of the server-side draft being edited; deleted from the server once sent. */
    val serverDraftMessageId: Long? = null,
    val updatedAt: Long = 0,
)

enum class OutboxState { QUEUED, SENDING, FAILED }

data class OutboxItem(
    val id: Long,
    val accountId: Long,
    val draft: Draft,
    val state: OutboxState,
    val attempts: Int,
    val lastError: String?,
    /** Epoch millis before which the message must not be sent (undo-send window). */
    val sendAt: Long,
    val createdAt: Long,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SwipeAction { NONE, ARCHIVE, DELETE, TOGGLE_READ, TOGGLE_STAR }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+) instead of UPM blue. */
    val dynamicColor: Boolean = false,
    /** Background check interval in minutes; 0 means manual only. */
    val syncIntervalMinutes: Int = 15,
    val notificationsEnabled: Boolean = true,
    /** Action for a start-to-end (left to right) swipe. */
    val swipeStartToEnd: SwipeAction = SwipeAction.ARCHIVE,
    /** Action for an end-to-start (right to left) swipe. */
    val swipeEndToStart: SwipeAction = SwipeAction.DELETE,
    val loadRemoteImages: Boolean = false,
    /** Senders whose remote images are always shown. Lower-case addresses. */
    val trustedImageSenders: Set<String> = emptySet(),
    val confirmDelete: Boolean = false,
    /** Seconds during which a sent message can be undone; 0 disables undo-send. */
    val undoSendSeconds: Int = 5,
    val conversationView: Boolean = true,
    val showSnippets: Boolean = true,
    val defaultReplyAll: Boolean = false,
    /** Account and folder selected last time, restored on start (-1 = none). */
    val lastAccountId: Long = -1,
    val lastFolderId: Long = -1,
)

/** Filters for local search. */
data class SearchFilters(
    val accountId: Long? = null,
    /** Restrict to one folder; null searches every folder of the account(s). */
    val folderId: Long? = null,
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val withAttachments: Boolean = false,
)

/** An address suggestion for the recipient autocomplete. */
data class ContactSuggestion(
    val address: EmailAddress,
    /** Where the suggestion came from, for a small hint icon. */
    val source: ContactSource,
)

enum class ContactSource { HISTORY, DEVICE }
