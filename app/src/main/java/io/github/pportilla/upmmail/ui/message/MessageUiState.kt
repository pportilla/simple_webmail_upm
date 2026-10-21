package io.github.pportilla.upmmail.ui.message

import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageDetail
import java.util.Locale

/** What the conversation screen is showing. */
enum class ThreadStatus { LOADING, NOT_FOUND, READY, CLOSED }

/** Message body ready for the WebView. */
data class RenderedBody(
    /** A complete sanitised document when [isHtml], else a fragment from plainTextToHtml. */
    val html: String,
    val isHtml: Boolean,
    /** The sanitised HTML references http(s) images, backgrounds or stylesheets. */
    val hasRemoteContent: Boolean,
    /** Attachments shown inside the body (inline cid: images), not listed as attachments. */
    val hiddenAttachmentIds: Set<Long>,
    /** The message has no text at all. */
    val isEmpty: Boolean = false,
    /** False while inline images are still being fetched (they appear as broken images). */
    val complete: Boolean = true,
)

/** Body of an expanded message. */
sealed interface BodyState {
    data object Loading : BodyState

    data class Error(val error: Throwable) : BodyState

    data class Ready(val body: RenderedBody) : BodyState
}

data class AttachmentItemUi(
    val attachment: Attachment,
    val kind: AttachmentKind,
    /** Being downloaded (to open, share or save it). */
    val downloading: Boolean,
)

/** One message card. */
data class MessageItemUi(
    val detail: MessageDetail,
    val expanded: Boolean,
    /** The message was unread when the conversation was opened (its sender is shown in bold). */
    val wasUnread: Boolean,
    val showDetails: Boolean,
    val isDraft: Boolean,
    /** Sent by the user (the sender is shown as "me"). */
    val fromSelf: Boolean,
    /** Only meaningful when [expanded]. */
    val body: BodyState,
    val remoteImagesAllowed: Boolean,
    val showRemoteImagesBanner: Boolean,
    val attachments: List<AttachmentItemUi>,
    val canReplyAll: Boolean,
    /** "Delete this message" would delete only this message (see the conversation-view caveat). */
    val canDeleteSingly: Boolean,
    /** A single-message conversation cannot be collapsed. */
    val collapsible: Boolean,
) {
    val id: Long get() = detail.summary.id
}

sealed interface ThreadRowUi {
    data class Message(val item: MessageItemUi) : ThreadRowUi

    data class Hidden(val ids: List<Long>) : ThreadRowUi
}

/** Spam action available for the conversation. */
sealed interface SpamAction {
    val target: Folder

    data class Report(override val target: Folder) : SpamAction

    data class NotSpam(override val target: Folder) : SpamAction
}

data class MessageUiState(
    val status: ThreadStatus = ThreadStatus.LOADING,
    val accountId: Long = -1,
    val subject: String = "",
    /** Folder of the opened message; conversation actions apply to its messages in this folder. */
    val folder: Folder? = null,
    val folderRole: FolderRole = FolderRole.OTHER,
    val rows: List<ThreadRowUi> = emptyList(),
    /** All folders of the account, for "Move to…". */
    val folders: List<Folder> = emptyList(),
    val canArchive: Boolean = false,
    /** Deleting removes the messages permanently (they are in Trash/Junk or there is no Trash). */
    val deleteIsPermanent: Boolean = false,
    /** Number of messages a conversation action affects. */
    val actionCount: Int = 0,
    val isStarred: Boolean = false,
    val canMarkUnread: Boolean = false,
    val spamAction: SpamAction? = null,
    val unsubscribe: UnsubscribeTarget? = null,
    /** Sender of the newsletter, for the unsubscribe confirmation. */
    val unsubscribeSender: String? = null,
    /** Newest non-draft message, target of the bottom reply buttons. */
    val replyTarget: MessageItemUi? = null,
    /** The opened message is a draft: the bottom bar offers "Edit draft" instead of replies. */
    val openedDraftId: Long? = null,
    val defaultReplyAll: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Lower-case addresses of the account, shown as "me". */
    val selfAddresses: Set<String> = emptySet(),
)

/** Screen-local state kept by the ViewModel next to the cached thread. */
data class LocalState(
    val expanded: Set<Long> = emptySet(),
    val wasUnread: Set<Long> = emptySet(),
    val detailsShown: Set<Long> = emptySet(),
    val imagesShown: Set<Long> = emptySet(),
    val revealAll: Boolean = false,
    /** Deleted from this screen; hidden at once (the opened message stays in observeThread). */
    val removed: Set<Long> = emptySet(),
    val downloading: Set<Long> = emptySet(),
    val bodyErrors: Map<Long, Throwable> = emptyMap(),
    val rendered: Map<Long, RenderedBody> = emptyMap(),
)

/** The thread as observed from the cache. */
sealed interface ThreadLoad {
    data object Loading : ThreadLoad

    data object NotFound : ThreadLoad

    data class Loaded(val messages: List<MessageDetail>) : ThreadLoad
}

/** Pure projection of the observed data onto the screen state. */
fun buildMessageUiState(
    openedId: Long,
    load: ThreadLoad,
    local: LocalState,
    settings: AppSettings,
    folders: List<Folder>,
    selfAddresses: Set<String>,
): MessageUiState {
    val messages = when (load) {
        ThreadLoad.Loading -> return MessageUiState(status = ThreadStatus.LOADING)
        ThreadLoad.NotFound -> return MessageUiState(status = ThreadStatus.NOT_FOUND)
        is ThreadLoad.Loaded -> load.messages.filter { it.summary.id !in local.removed }
    }
    if (messages.isEmpty()) return MessageUiState(status = ThreadStatus.CLOSED)

    val opened = messages.firstOrNull { it.summary.id == openedId } ?: messages.last()
    val folderId = opened.summary.folderId
    val folder = folders.firstOrNull { it.id == folderId }
    val role = opened.folderRole
    val inFolder = messages.filter { it.summary.folderId == folderId }
    val selectable = folders.filter { it.selectable }
    val archive = selectable.firstOrNull { it.role == FolderRole.ARCHIVE }
    val junk = selectable.firstOrNull { it.role == FolderRole.JUNK }
    val inbox = selectable.firstOrNull { it.role == FolderRole.INBOX }
    val hasTrash = selectable.any { it.role == FolderRole.TRASH }
    val folderCounts = messages.groupingBy { it.summary.folderId }.eachCount()

    fun isSelf(detail: MessageDetail): Boolean =
        detail.summary.from?.address?.trim()?.lowercase(Locale.ROOT)?.let { it in selfAddresses } == true

    fun item(detail: MessageDetail): MessageItemUi {
        val id = detail.summary.id
        val expanded = messages.size == 1 || id in local.expanded
        val rendered = local.rendered[id]
        val body = when {
            detail.bodyLoaded -> rendered?.let { BodyState.Ready(it) } ?: BodyState.Loading
            local.bodyErrors[id] != null -> BodyState.Error(local.bodyErrors.getValue(id))
            else -> BodyState.Loading
        }
        val sender = detail.summary.from?.address?.trim()?.lowercase(Locale.ROOT)
        val imagesAllowed = settings.loadRemoteImages || id in local.imagesShown ||
            (sender != null && sender in settings.trustedImageSenders)
        val hidden = rendered?.hiddenAttachmentIds
        val attachments = if (!expanded) {
            emptyList()
        } else {
            detail.attachments
                .filter { attachment ->
                    if (hidden != null) {
                        attachment.id !in hidden
                    } else {
                        // Until the body is rendered, assume inline images belong to the body.
                        !(attachment.isInline && attachment.contentId != null && attachment.mimeType.startsWith("image/"))
                    }
                }
                .map { AttachmentItemUi(it, attachmentKind(it.mimeType, it.fileName), it.id in local.downloading) }
        }
        val draft = detail.isDraftMessage()
        return MessageItemUi(
            detail = detail,
            expanded = expanded,
            wasUnread = id in local.wasUnread,
            showDetails = id in local.detailsShown,
            isDraft = draft,
            fromSelf = isSelf(detail),
            body = body,
            remoteImagesAllowed = imagesAllowed,
            showRemoteImagesBanner = rendered != null && rendered.isHtml && rendered.hasRemoteContent && !imagesAllowed,
            attachments = attachments,
            canReplyAll = !draft && replyAllAddsRecipients(
                from = detail.summary.from,
                to = detail.summary.to,
                cc = detail.cc,
                replyTo = detail.replyTo,
                selfAddresses = selfAddresses,
            ),
            canDeleteSingly = !settings.conversationView || folderCounts[detail.summary.folderId] == 1,
            collapsible = messages.size > 1,
        )
    }

    val items = messages.associate { it.summary.id to item(it) }
    val rows = threadRows(messages.map { it.summary.id }, items.values.filter { it.expanded }.mapTo(HashSet()) { it.id }, local.revealAll)
        .map { row ->
            when (row) {
                is ThreadRow.Message -> ThreadRowUi.Message(items.getValue(row.id))
                is ThreadRow.Hidden -> ThreadRowUi.Hidden(row.ids)
            }
        }
    val newsletter = messages.lastOrNull { chooseUnsubscribeTarget(it.listUnsubscribe) != null }
    val spamAction = when {
        role == FolderRole.JUNK -> inbox?.let { SpamAction.NotSpam(it) }
        role == FolderRole.SENT || role == FolderRole.DRAFTS -> null
        else -> junk?.let { SpamAction.Report(it) }
    }
    return MessageUiState(
        status = ThreadStatus.READY,
        accountId = opened.summary.accountId,
        subject = opened.summary.subject.ifBlank { messages.first().summary.subject },
        folder = folder,
        folderRole = role,
        rows = rows,
        folders = folders,
        canArchive = archive != null && role != FolderRole.ARCHIVE && role != FolderRole.DRAFTS,
        deleteIsPermanent = role == FolderRole.TRASH || role == FolderRole.JUNK || !hasTrash,
        actionCount = inFolder.size,
        isStarred = inFolder.any { it.summary.isStarred },
        canMarkUnread = inFolder.any { !it.isDraftMessage() },
        spamAction = spamAction,
        unsubscribe = newsletter?.let { chooseUnsubscribeTarget(it.listUnsubscribe) },
        unsubscribeSender = newsletter?.summary?.from?.displayName,
        replyTarget = messages.lastOrNull { !it.isDraftMessage() }?.let { items[it.summary.id] },
        openedDraftId = opened.summary.id.takeIf { opened.isDraftMessage() },
        defaultReplyAll = settings.defaultReplyAll,
        confirmDelete = settings.confirmDelete,
        selfAddresses = selfAddresses,
    )
}
