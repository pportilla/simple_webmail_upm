package io.github.pportilla.upmmail.ui.mailbox

import io.github.pportilla.upmmail.data.repository.LocalDraftRows
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SwipeAction
import io.github.pportilla.upmmail.ui.navigation.DeepLinks

/*
 * Pure (Android-free) rules behind the mailbox screen, kept apart from the ViewModel so they can
 * be unit-tested on the plain JVM.
 */

/** Rows requested from the cache at a time, and messages downloaded per "load older" call. */
internal const val PAGE_SIZE = 50

/** Start paging when the user is this many rows away from the end of the list. */
internal const val LOAD_AHEAD = 10

/** A folder synced longer ago than this is refreshed when it is opened. */
internal const val STALE_AFTER_MILLIS = 2 * 60_000L

/** What the mailbox currently shows. */
sealed interface MailboxView {
    /** Accounts or folders are not known yet (e.g. right after signing in). */
    data object Loading : MailboxView

    /** A server folder of the current account. */
    data class FolderView(val folder: Folder) : MailboxView

    /** The local outbox of [accountId] (queued and failed outgoing messages). */
    data class Outbox(val accountId: Long) : MailboxView
}

/** The view to show and, when the stored choice was unusable, the folder to remember instead. */
internal data class ViewResolution(
    val view: MailboxView,
    /** Folder id to persist as AppSettings.lastFolderId, null when the settings are fine. */
    val persistFolderId: Long? = null,
)

/** The stored account if it still exists, otherwise the first one. */
internal fun resolveAccount(accounts: List<Account>, lastAccountId: Long): Account? =
    accounts.firstOrNull { it.id == lastAccountId } ?: accounts.firstOrNull()

/**
 * Matches the persisted selection (lastAccountId / lastFolderId) against the folders of the
 * current account [accountId].
 *
 * [foldersAccountId] is the account [folders] belong to; while it differs from [accountId] (the
 * account was just switched) or the list is not [foldersLoaded] yet the result is
 * [MailboxView.Loading], so a stale folder list never overrides the user's choice. Falls back to
 * the INBOX (or the first selectable folder) and asks to persist it.
 */
internal fun resolveView(
    accountId: Long?,
    foldersAccountId: Long?,
    foldersLoaded: Boolean,
    folders: List<Folder>,
    lastAccountId: Long,
    lastFolderId: Long,
): ViewResolution {
    if (accountId == null) return ViewResolution(MailboxView.Loading)
    val storedAccount = lastAccountId == accountId
    if (storedAccount && lastFolderId == DeepLinks.OUTBOX_FOLDER_ID) {
        return ViewResolution(MailboxView.Outbox(accountId))
    }
    if (foldersAccountId != accountId || !foldersLoaded) return ViewResolution(MailboxView.Loading)
    if (storedAccount) {
        folders.firstOrNull { it.id == lastFolderId && it.selectable }?.let {
            return ViewResolution(MailboxView.FolderView(it))
        }
    }
    val fallback = folders.firstOrNull { it.role == FolderRole.INBOX && it.selectable }
        ?: folders.firstOrNull { it.selectable }
        ?: return ViewResolution(MailboxView.Loading)
    return ViewResolution(MailboxView.FolderView(fallback), persistFolderId = fallback.id)
}

/** One folder row of the navigation drawer. */
internal data class DrawerFolderEntry(
    val folder: Folder,
    /** Indentation level (0 = none). */
    val indent: Int,
    /**
     * Label to show instead of the folder name: the full path for a sub-folder whose parent is
     * not listed next to it (e.g. "INBOX/Projects"), so it is not mistaken for a top-level one.
     */
    val pathLabel: String? = null,
)

/** Drawer sections: one entry per well-known role, then every other folder. */
internal data class DrawerFolders(
    val roles: List<DrawerFolderEntry>,
    val others: List<DrawerFolderEntry>,
)

private val DRAWER_ROLE_ORDER = listOf(
    FolderRole.INBOX,
    FolderRole.DRAFTS,
    FolderRole.SENT,
    FolderRole.ARCHIVE,
    FolderRole.JUNK,
    FolderRole.TRASH,
)

/**
 * Splits [folders] (in repository order) into the drawer sections. Only the first selectable
 * folder of each role goes to the top section; duplicates and the rest keep their order and are
 * indented below their parent. A sub-folder whose parent is not in the list (e.g. a child of
 * INBOX, which sits in the top section) is not indented and shows its full path instead.
 */
internal fun arrangeDrawerFolders(folders: List<Folder>): DrawerFolders {
    val roleFolders = DRAWER_ROLE_ORDER.mapNotNull { role ->
        folders.firstOrNull { it.role == role && it.selectable } ?: folders.firstOrNull { it.role == role }
    }
    val roleIds = roleFolders.mapTo(HashSet()) { it.id }
    val others = ArrayList<DrawerFolderEntry>()
    for (folder in folders) {
        if (folder.id in roleIds) continue
        val depth = folder.depth.coerceAtLeast(0)
        val parent = if (depth == 0) {
            null
        } else {
            others.lastOrNull { it.folder.depth == depth - 1 && isParentPath(it.folder.fullName, folder.fullName) }
        }
        others += when {
            parent != null -> DrawerFolderEntry(folder, parent.indent + 1)
            depth == 0 -> DrawerFolderEntry(folder, 0)
            else -> DrawerFolderEntry(folder, 0, pathLabel = folder.fullName)
        }
    }
    return DrawerFolders(roleFolders.map { DrawerFolderEntry(it, 0) }, others)
}

/** True if [child] is directly or indirectly below [parent] ("Work" / "Work/2025", "INBOX.a" / "INBOX.a.b"). */
private fun isParentPath(parent: String, child: String): Boolean =
    child.length > parent.length + 1 &&
        child.startsWith(parent) &&
        !child[parent.length].isLetterOrDigit()

/** Number shown next to a folder in the drawer: drafts count all, sent/trash show nothing. */
internal fun drawerBadgeCount(folder: Folder): Int = when (folder.role) {
    FolderRole.DRAFTS -> folder.totalCount
    FolderRole.SENT, FolderRole.TRASH -> 0
    else -> folder.unreadCount
}.coerceAtLeast(0)

/** Folders whose list shows the recipients rather than the sender. */
internal fun showsRecipients(role: FolderRole?): Boolean = role == FolderRole.SENT || role == FolderRole.DRAFTS

/** Deleting in these folders removes messages permanently. */
internal fun deletesPermanently(role: FolderRole?): Boolean = role == FolderRole.TRASH || role == FolderRole.JUNK

/**
 * Deleting [ids] in a folder with [role] removes something for good: everything in Trash/Spam,
 * and a draft saved only on this device anywhere (it is discarded; there is nothing to put in
 * Trash or to undo).
 */
internal fun deletesPermanently(role: FolderRole?, ids: Collection<Long>): Boolean =
    deletesPermanently(role) || ids.any(::isLocalDraftRow)

/** A Drafts row standing for a draft saved only on this device (see LocalDraftRows). */
internal fun isLocalDraftRow(messageId: Long): Boolean = LocalDraftRows.draftId(messageId) != null

/** Which toggles the contextual app bar offers for the current selection. */
internal data class SelectionActions(
    /** True: "Mark as read" (something is unread); false: "Mark as unread". */
    val markRead: Boolean,
    /** True: "Star" (something is not starred); false: "Remove star". */
    val star: Boolean,
)

internal fun selectionActions(selected: List<MessageSummary>): SelectionActions = SelectionActions(
    markRead = selected.isEmpty() || selected.any { !it.isRead },
    star = selected.isEmpty() || selected.any { !it.isStarred },
)

/**
 * The swipe action really available for a row: archiving inside the Archive folder is
 * meaningless, so it is disabled there.
 */
internal fun effectiveSwipeAction(action: SwipeAction, folderRole: FolderRole?): SwipeAction =
    if (action == SwipeAction.ARCHIVE && folderRole == FolderRole.ARCHIVE) SwipeAction.NONE else action

/** True if [folder] has never been synced or not for [STALE_AFTER_MILLIS]. */
internal fun isStale(folder: Folder, now: Long): Boolean {
    val last = folder.lastSyncedAt ?: return true
    return now - last >= STALE_AFTER_MILLIS || now < last
}

/** Paging bookkeeping of the current folder. */
data class PagingState(
    val folderId: Long? = null,
    /** Rows requested from the cache. */
    val limit: Int = PAGE_SIZE,
    /** Limit the currently shown rows were loaded with (-1 before the first result). */
    val shownLimit: Int = -1,
    val loadingOlder: Boolean = false,
    /** The server reported that there are no older messages. */
    val endReached: Boolean = false,
    /** The last "load older" failed; paging stops until the user retries. */
    val failed: Boolean = false,
)

/** What to do when the user scrolls near the end of the list. */
internal enum class PagingStep { NOTHING, GROW_LIMIT, LOAD_OLDER }

internal fun nextPagingStep(paging: PagingState, shownRows: Int): PagingStep = when {
    paging.loadingOlder || paging.failed -> PagingStep.NOTHING
    // The rows on screen belong to an older request: wait for the current one.
    paging.shownLimit != paging.limit -> PagingStep.NOTHING
    shownRows >= paging.limit -> PagingStep.GROW_LIMIT
    paging.endReached -> PagingStep.NOTHING
    else -> PagingStep.LOAD_OLDER
}

/**
 * Short list of recipient names for list rows: the full name for one recipient, first names
 * (or the local part of the address) for several. [ownAddress] is shown as [meLabel].
 */
fun recipientNames(recipients: List<EmailAddress>, ownAddress: String?, meLabel: String): String {
    if (recipients.isEmpty()) return ""
    fun isMe(address: EmailAddress) = ownAddress != null && address.address.equals(ownAddress, ignoreCase = true)
    if (recipients.size == 1) {
        val only = recipients.first()
        return if (isMe(only)) meLabel else only.displayName
    }
    return recipients.joinToString(", ") { address ->
        when {
            isMe(address) -> meLabel
            !address.name.isNullOrBlank() -> firstName(address.name) ?: address.address.substringBefore('@')
            else -> address.address.substringBefore('@')
        }
    }
}

/**
 * Why an outbox message was not sent, from its stored last error (the name of a MailErrorKind);
 * null when it has none. Anything else counts as an unknown error.
 */
fun outboxErrorKind(lastError: String?): MailErrorKind? {
    val name = lastError?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return MailErrorKind.entries.firstOrNull { it.name == name } ?: MailErrorKind.UNKNOWN
}

/**
 * Given name from a display name, without quotes or brackets: "\"Ana\" Pérez" and the directory
 * form "Pérez García, Ana" both give "Ana".
 */
private fun firstName(name: String): String? {
    val cleaned = name.trim().trim('"', '\'').trim()
    val given = if (cleaned.count { it == ',' } == 1) cleaned.substringAfter(',') else cleaned
    return given.trim()
        .split(Regex("\\s+"))
        .map { it.trim('"', '\'', '(', ')', '<', '>', ',', ';') }
        .firstOrNull { it.isNotEmpty() }
}
