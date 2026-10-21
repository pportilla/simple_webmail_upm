package io.github.pportilla.upmmail.model

import java.util.Locale

/*
 * Search results from the local cache, the on-device header index and the server, and the progress
 * of a server search: plain immutable data like the models in Models.kt, plus [mergeHits].
 */

/** Identity of a message on the server; the same in `messages` and in the header index. */
data class SearchKey(val folderId: Long, val uid: Long)

/** One row of the header index: a message known only by its headers, not cached in `messages`. */
data class IndexedMessage(
    /** Row id in `header_index`; not a message id. */
    val indexId: Long,
    val accountId: Long,
    val folderId: Long,
    val folderRole: FolderRole,
    val uid: Long,
    /** Message-ID header with angle brackets. */
    val messageIdHeader: String?,
    val subject: String,
    val from: EmailAddress?,
    /** Kept for the Sent folder only; empty elsewhere. */
    val to: List<EmailAddress>,
    /** Epoch millis: the Date header, falling back to the server's internal date. */
    val date: Long,
    val size: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isAnswered: Boolean,
    /** Heuristic from the top-level Content-Type; approximate. */
    val hasAttachments: Boolean,
)

/** One search result, either cached in `messages` or known only from the header index. */
sealed interface SearchHit {
    val key: SearchKey
    val accountId: Long
    val date: Long
    /** Message-ID header with angle brackets, used to drop copies of the same message. */
    val messageIdHeader: String?
    val isRead: Boolean
    val isStarred: Boolean
    val hasAttachments: Boolean

    /** For display only; id == 0 for [Indexed]. Never navigate with it. */
    val display: MessageSummary

    /** A row of `messages`: [message].id can be opened directly. */
    data class Cached(val message: MessageSummary, override val messageIdHeader: String?) : SearchHit {
        override val key: SearchKey = SearchKey(message.folderId, message.uid)
        override val accountId: Long get() = message.accountId
        override val date: Long get() = message.date
        override val isRead: Boolean get() = message.isRead
        override val isStarred: Boolean get() = message.isStarred
        override val hasAttachments: Boolean get() = message.hasAttachments
        override val display: MessageSummary get() = message
    }

    /** Known only from the header index; the message must be downloaded before it can be opened. */
    data class Indexed(val entry: IndexedMessage) : SearchHit {
        override val key: SearchKey = SearchKey(entry.folderId, entry.uid)
        override val accountId: Long get() = entry.accountId
        override val date: Long get() = entry.date
        override val messageIdHeader: String? get() = entry.messageIdHeader
        override val isRead: Boolean get() = entry.isRead
        override val isStarred: Boolean get() = entry.isStarred
        override val hasAttachments: Boolean get() = entry.hasAttachments
        override val display: MessageSummary = MessageSummary(
            id = 0,
            accountId = entry.accountId,
            folderId = entry.folderId,
            uid = entry.uid,
            threadId = "",
            subject = entry.subject,
            from = entry.from,
            to = entry.to,
            date = entry.date,
            snippet = "",
            isRead = entry.isRead,
            isStarred = entry.isStarred,
            isAnswered = entry.isAnswered,
            isForwarded = false,
            isDraft = false,
            hasAttachments = entry.hasAttachments,
        )
    }
}

/**
 * Merges search results from several sources into one list, newest first (then by folder id
 * and uid, descending), with at most [limit] hits:
 * - one hit per [SearchKey]: [SearchHit.Cached] beats [SearchHit.Indexed]; between two of the
 *   same kind the one from the later list (the fresher answer) is kept;
 * - then one hit per Message-ID (compared in lower case; hits without one are kept): Cached beats
 *   Indexed; among Cached the newer date, then the lower message id; among Indexed the folder
 *   role (Inbox, Archive, Sent, other folders, Drafts, Junk, Trash), then the newer date, then
 *   the lower index id.
 */
fun mergeHits(vararg lists: List<SearchHit>, limit: Int = Int.MAX_VALUE): List<SearchHit> {
    val byKey = LinkedHashMap<SearchKey, SearchHit>()
    for (list in lists) {
        for (hit in list) {
            val current = byKey[hit.key]
            if (current == null || !(current is SearchHit.Cached && hit is SearchHit.Indexed)) byKey[hit.key] = hit
        }
    }
    val result = ArrayList<SearchHit>(byKey.size)
    val byMessageId = HashMap<String, SearchHit>()
    for (hit in byKey.values) {
        val messageId = hit.messageIdHeader?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (messageId == null) {
            result += hit
        } else {
            val current = byMessageId[messageId]
            if (current == null || HIT_PREFERENCE.compare(hit, current) < 0) byMessageId[messageId] = hit
        }
    }
    result += byMessageId.values
    result.sortWith(NEWEST_HIT_FIRST)
    return if (result.size > limit) result.subList(0, limit.coerceAtLeast(0)).toList() else result
}

/** Which of two copies of one message [mergeHits] keeps: the smaller one. */
private val HIT_PREFERENCE = Comparator<SearchHit> { a, b ->
    when {
        a is SearchHit.Cached && b is SearchHit.Cached ->
            b.date.compareTo(a.date).takeIf { it != 0 } ?: a.message.id.compareTo(b.message.id)
        a is SearchHit.Indexed && b is SearchHit.Indexed ->
            ROLE_PREFERENCE.indexOf(a.entry.folderRole).compareTo(ROLE_PREFERENCE.indexOf(b.entry.folderRole)).takeIf { it != 0 }
                ?: b.date.compareTo(a.date).takeIf { it != 0 }
                ?: a.entry.indexId.compareTo(b.entry.indexId)
        a is SearchHit.Cached -> -1
        else -> 1
    }
}

/** Folder roles in the order [mergeHits] prefers index copies of one message. */
private val ROLE_PREFERENCE = listOf(
    FolderRole.INBOX,
    FolderRole.ARCHIVE,
    FolderRole.SENT,
    FolderRole.OTHER,
    FolderRole.DRAFTS,
    FolderRole.JUNK,
    FolderRole.TRASH,
)

private val NEWEST_HIT_FIRST: Comparator<SearchHit> =
    compareByDescending<SearchHit> { it.date }.thenByDescending { it.key.folderId }.thenByDescending { it.key.uid }

/**
 * Where a server search looks. [accountIds] in search order (the selected account first);
 * [folderId] restricts it to one folder of those accounts; [trashAndJunkOnly] searches only the
 * bins, which are left out otherwise.
 */
data class ServerSearchScope(
    val accountIds: List<Long>,
    val folderId: Long? = null,
    val trashAndJunkOnly: Boolean = false,
)

enum class SearchPhase {
    /** Subject, From, To and Cc of every folder in scope. */
    HEADERS,
    /** Message text (BODY), in windows of the newest messages first. Slow; only on request. */
    TEXT,
    /** Downloads the newest few text matches so their rows can show a preview. */
    SNIPPETS,
}

data class ServerSearchRequest(
    val query: String,
    val scope: ServerSearchScope,
    val phases: Set<SearchPhase> = setOf(SearchPhase.HEADERS),
    /** Maximum number of hits; the search stops once it has this many. */
    val limit: Int = 200,
    /** Download a few text matches for previews after the [SearchPhase.TEXT] phase. */
    val snippets: Boolean = true,
)

enum class StepOutcome { DONE, TIMED_OUT, FAILED, SKIPPED }

/** A part of a server search that did not complete; the hits may be missing matches from it. */
data class StepProblem(
    val folder: Folder,
    val phase: SearchPhase,
    val outcome: StepOutcome,
    /** Sequence window of the step, inclusive; null for a step not limited to a window. */
    val firstSeq: Int?,
    val lastSeq: Int?,
    val error: MailException?,
)

data class ServerSearchProgress(
    val request: ServerSearchRequest,
    /** Phase of the current step, if any. */
    val phase: SearchPhase?,
    /** Folder being searched now, if any. */
    val folder: Folder?,
    val step: Int,
    val totalSteps: Int,
    /** Every hit found so far, newest first. */
    val hits: List<SearchHit>,
    val problems: List<StepProblem>,
    val finished: Boolean,
    /** The search stopped at [ServerSearchRequest.limit] hits; more may exist. */
    val truncated: Boolean,
    /** The error that stopped the search (sign-in, certificate, network). */
    val error: MailException?,
)

/** Header index coverage of one account. */
data class IndexStatus(
    val accountId: Long,
    /** Messages in the index. */
    val indexed: Int,
    /** Messages the index aims to hold: the server counts of the indexable folders, capped. */
    val total: Int,
    /** Every indexable folder has been fully checked against the server. */
    val complete: Boolean,
    /** An index run is working on this account now. */
    val running: Boolean = false,
)

/** What opening a [SearchHit] led to. */
sealed interface HitOpenResult {
    /** The message is in `messages` under [messageId]; navigate to it. */
    data class Opened(val messageId: Long) : HitOpenResult

    /** The message is no longer on the server. */
    data object Gone : HitOpenResult

    /** A local move or delete of this message is still waiting to reach the server. */
    data object PendingChange : HitOpenResult
}
