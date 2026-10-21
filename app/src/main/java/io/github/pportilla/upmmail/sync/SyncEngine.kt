package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.FolderListEntry
import io.github.pportilla.upmmail.data.db.FolderSyncState
import io.github.pportilla.upmmail.data.db.LocalFlag
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.OutboxEntry
import io.github.pportilla.upmmail.data.db.PendingOp
import io.github.pportilla.upmmail.data.db.PendingOpType
import io.github.pportilla.upmmail.data.db.ProtectedUids
import io.github.pportilla.upmmail.data.db.ServerCopy
import io.github.pportilla.upmmail.data.files.AttachmentOpener
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.mail.AttachmentSource
import io.github.pportilla.upmmail.mail.ComposedMessage
import io.github.pportilla.upmmail.mail.FolderRoleDetector
import io.github.pportilla.upmmail.mail.HtmlText
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.MessageComposer
import io.github.pportilla.upmmail.mail.MessageFlag
import io.github.pportilla.upmmail.mail.ParsedBody
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.mail.RemoteMessage
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.model.OutgoingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Outcome of [SyncEngine.flushPendingOps]. */
data class FlushResult(
    /** When the next waiting operation becomes due (undo window), or null if none is waiting. */
    val nextDueAt: Long?,
    /** Set when an account-wide problem (network, login, certificate) stopped the flush. */
    val blockedBy: MailException?,
    /** Local ids of folders whose server content changed (move targets, Drafts). */
    val touchedFolders: Set<Long>,
) {
    companion object {
        val EMPTY = FlushResult(null, null, emptySet())
    }
}

/** Outcome of [SyncEngine.sendOutbox]. */
data class OutboxResult(
    val sent: Int,
    val failed: Int,
    /** Messages put back in the queue after a network error. */
    val retrying: Int,
    /** Earliest send time of the messages still queued, or null if the queue is empty. */
    val nextSendAt: Long?,
)

/** A move that reached the server, remembered for a short while so it can still be undone. */
data class CompletedMove(
    val accountId: Long,
    val fromFolder: String,
    val toFolder: String,
    val messageIdHeaders: List<String>,
    val completedAt: Long,
)

/**
 * Keeps the SQLite cache in step with the IMAP server and sends the outbox. Pure Kotlin: every
 * dependency is passed in, so it runs in JVM tests against GreenMail.
 *
 * Network work of one account (sync, flush, paging) is serialised by a per-account mutex; the
 * outbox has its own mutex so sending is never stuck behind a long sync. Every public function
 * throws [MailException] on failure.
 */
class SyncEngine(
    private val store: MailStore,
    private val clients: MailClientProvider,
    private val files: LocalFiles,
    private val attachmentOpener: AttachmentOpener,
    private val listener: SyncListener = SyncListener.NONE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val accountLocks = ConcurrentHashMap<Long, Mutex>()
    private val outboxLock = Mutex()
    private val syncingCounts = HashMap<Long, Int>()
    private val syncing = MutableStateFlow<Set<Long>>(emptySet())
    private val inFlightOps = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val completedMoves = ConcurrentHashMap<Long, MutableList<CompletedMove>>()
    private val sendingNow: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val recoveryLock = Mutex()

    @Volatile
    private var recovered = false

    /** Folder ids currently being synchronised. */
    val syncingFolders: StateFlow<Set<Long>> = syncing.asStateFlow()

    // ------------------------------------------------------------------ folders

    /** Refreshes the folder list of the account (roles detected, ids kept stable). */
    suspend fun syncFolderList(accountId: Long): Unit = withContext(io) { syncFolderListOnIo(accountId) }

    private suspend fun syncFolderListOnIo(accountId: Long) = withAccountLock(accountId) {
        syncFolderListLocked(accountId)
    }

    private suspend fun syncFolderListLocked(accountId: Long) {
        val remote = clients.imap(accountId).listFolders()
        val entries = remote.map { folder ->
            FolderListEntry(
                fullName = folder.fullName,
                displayName = folder.name.ifEmpty { folder.fullName },
                separator = folder.separator,
                attributes = folder.attributes,
                selectable = folder.selectable,
                depth = folder.depth,
                role = FolderRoleDetector.detect(folder.fullName, folder.attributes),
            )
        }.toMutableList()
        // INBOX always exists in IMAP, even if a server forgets to list it.
        if (entries.none { it.fullName.equals("INBOX", ignoreCase = true) }) {
            entries += FolderListEntry("INBOX", "INBOX", '/', emptySet(), true, 0, FolderRole.INBOX)
        }
        db { store.folders.applyServerList(accountId, entries) }
    }

    /** The INBOX of the account, fetching the folder list first if it is not known yet. */
    suspend fun inboxOf(accountId: Long): Folder = withContext(io) { inboxOfOnIo(accountId) }

    private suspend fun inboxOfOnIo(accountId: Long): Folder {
        db { store.folders.findByRole(accountId, FolderRole.INBOX) }?.let { return it }
        syncFolderList(accountId)
        return db { store.folders.findByRole(accountId, FolderRole.INBOX) }
            ?: throw MailException(MailErrorKind.NOT_FOUND, "No INBOX")
    }

    /** Folder list, outbox and INBOX of one account: what "refresh" means for the user. */
    suspend fun syncAccount(accountId: Long): List<MessageSummary> =
        withContext(io) { syncAccountOnIo(accountId) }

    private suspend fun syncAccountOnIo(accountId: Long): List<MessageSummary> {
        syncFolderList(accountId)
        try {
            sendOutbox(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Send failures are recorded on the outbox items themselves.
        }
        return syncFolder(inboxOf(accountId).id)
    }

    // ------------------------------------------------------------------ messages

    /**
     * Pushes pending changes, then fetches new messages and flag changes of the folder and
     * prefetches the newest bodies. Returns the new unread messages when the folder is an INBOX
     * (the listener has been told about them as well).
     */
    suspend fun syncFolder(folderId: Long): List<MessageSummary> = withContext(io) { syncFolderOnIo(folderId) }

    private suspend fun syncFolderOnIo(folderId: Long): List<MessageSummary> {
        val folder = db { store.folders.get(folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        if (!folder.selectable) return emptyList()
        val newIds = markSyncing(folderId) {
            withAccountLock(folder.accountId) {
                flushLocked(folder.accountId)
                syncHeadersLocked(folderId)
            }
        }
        prefetchBodies(folder)
        if (folder.role != FolderRole.INBOX) return emptyList()
        val fresh = db { store.messages.summaries(newIds) }.filter { !it.isRead }.sortedByDescending { it.date }
        notify {
            listener.onInboxSynced(folder.accountId, folderId)
            if (fresh.isNotEmpty()) listener.onNewMessages(folder.accountId, folderId, fresh)
        }
        return fresh
    }

    /** Returns the ids of the messages inserted by this sync that are genuinely new mail. */
    private suspend fun syncHeadersLocked(folderId: Long): List<Long> {
        var state = db { store.folders.syncState(folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        val folder = state.folder
        val imap = clients.imap(folder.accountId)
        val status = imap.status(folder.fullName)
        if (state.uidValidity != 0L && state.uidValidity != status.uidValidity) {
            dropFolderCache(state)
            state = db { store.folders.syncState(folderId) } ?: return emptyList()
        }
        val own = db { store.accounts.ownAddresses() }
        val feedContacts = folder.role != FolderRole.TRASH && folder.role != FolderRole.JUNK
        val protectedUids = db { store.pendingOps.protectedUids(folder.accountId, folder.fullName) }
        val newMail = ArrayList<Long>()

        if (state.uidValidity == 0L) {
            // First sync (or after a reset): the newest messages only, nothing to notify about.
            val result = imap.fetchNewest(folder.fullName, INITIAL_WINDOW)
            db { store.messages.upsertHeaders(folder.accountId, folderId, result.messages, protectedUids.uids, feedContacts, own) }
            val start = result.messages.minOfOrNull { it.uid } ?: status.uidNext.coerceAtLeast(1)
            val end = maxOf(result.messages.maxOfOrNull { it.uid } ?: 0L, status.uidNext - 1)
            db { store.folders.setWindow(folderId, start, end, result.truncated) }
        } else {
            val oldEnd = state.windowEndUid
            val result = imap.fetchNewer(folder.fullName, oldEnd, NEWER_LIMIT)
            if (result.truncated) {
                // Too many new messages to fill the gap: restart the window at the newest ones.
                val keep = result.messages.takeLast(INITIAL_WINDOW)
                db {
                    store.messages.deleteVisibleExcept(folderId, protectedUids.uids + keep.map { it.uid })
                }
                newMail += db { store.messages.upsertHeaders(folder.accountId, folderId, keep, protectedUids.uids, feedContacts, own) }
                val start = keep.minOfOrNull { it.uid } ?: oldEnd
                val end = maxOf(keep.maxOfOrNull { it.uid } ?: oldEnd, status.uidNext - 1)
                db { store.folders.setWindow(folderId, start, end, true) }
            } else {
                newMail += db {
                    store.messages.upsertHeaders(folder.accountId, folderId, result.messages, protectedUids.uids, feedContacts, own)
                }
                val end = maxOf(oldEnd, result.messages.maxOfOrNull { it.uid } ?: 0L, status.uidNext - 1)
                db { store.folders.setWindow(folderId, state.windowStartUid, end, state.moreAvailable) }
                reconcileFlags(imap, state, oldEnd, protectedUids)
            }
        }
        db { store.folders.updateAfterSync(folderId, status.uidValidity, status.unreadCount, status.messageCount) }
        return newMail
    }

    /** Server flags for the cached window, plus a few extra rows found by server searches. */
    private suspend fun reconcileFlags(
        imap: ImapClient,
        state: FolderSyncState,
        windowEnd: Long,
        protectedUids: ProtectedUids,
    ) {
        val folder = state.folder
        val start = state.windowStartUid.coerceAtLeast(1)
        if (windowEnd >= start && db { store.messages.uidBounds(folder.id) } != null) {
            val flags = imap.fetchFlags(folder.fullName, start, windowEnd)
            db { applyFlags(folder.id, start..windowEnd, flags, protectedUids) }
        }
        val extras = db { store.messages.uidsBelow(folder.id, start) }
        if (extras.size > MAX_EXTRA_CHECKS) {
            db { store.messages.deleteByUids(folder.id, extras.filter { it !in protectedUids }) }
        } else {
            for (uid in extras) {
                val flags = imap.fetchFlags(folder.fullName, uid, uid)
                db { applyFlags(folder.id, uid..uid, flags, protectedUids) }
            }
        }
    }

    private fun applyFlags(
        folderId: Long,
        range: LongRange,
        flags: Map<Long, RemoteFlags>,
        protectedUids: ProtectedUids,
    ) {
        val protectedSet = if (protectedUids.wholeFolder) range.toProtectedSet(flags.keys) else protectedUids.uids
        store.messages.applyServerFlags(folderId, range, flags, protectedSet)
    }

    private fun LongRange.toProtectedSet(uids: Set<Long>): Set<Long> = uids.filterTo(HashSet()) { it in this }

    private suspend fun dropFolderCache(state: FolderSyncState) {
        val folder = state.folder
        db {
            store.tx {
                val dropped = store.pendingOps.deleteForFolder(folder.accountId, folder.fullName)
                store.messages.clearFolder(folder.id)
                store.folders.resetSyncState(folder.id)
                dropped
            }
        }
    }

    /**
     * Downloads up to [count] messages older than the cached window. Returns true if even older
     * messages may exist on the server.
     */
    suspend fun loadOlder(folderId: Long, count: Int): Boolean = withContext(io) { loadOlderOnIo(folderId, count) }

    private suspend fun loadOlderOnIo(folderId: Long, count: Int): Boolean {
        val state = db { store.folders.syncState(folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        if (state.uidValidity == 0L) {
            syncFolder(folderId)
            return db { store.folders.syncState(folderId) }?.moreAvailable ?: false
        }
        if (!state.folder.selectable) return false
        return markSyncing(folderId) {
            withAccountLock(state.folder.accountId) {
                val current = db { store.folders.syncState(folderId) } ?: return@withAccountLock false
                val folder = current.folder
                val start = current.windowStartUid
                if (start <= 1L) {
                    db { store.folders.setWindow(folderId, start, current.windowEndUid, false) }
                    return@withAccountLock false
                }
                val result = clients.imap(folder.accountId).fetchOlder(folder.fullName, start, count.coerceIn(1, 500))
                if (result.uidValidity != 0L && result.uidValidity != current.uidValidity) {
                    // The mailbox was renumbered: the next sync rebuilds the cache.
                    dropFolderCache(current)
                    return@withAccountLock true
                }
                val own = db { store.accounts.ownAddresses() }
                val protectedUids = db { store.pendingOps.protectedUids(folder.accountId, folder.fullName) }
                val feedContacts = folder.role != FolderRole.TRASH && folder.role != FolderRole.JUNK
                db { store.messages.upsertHeaders(folder.accountId, folderId, result.messages, protectedUids.uids, feedContacts, own) }
                val newStart = result.messages.minOfOrNull { it.uid } ?: start
                db { store.folders.setWindow(folderId, newStart, current.windowEndUid, result.truncated) }
                result.truncated
            }
        }
    }

    /** Downloads and stores the body (and, with [inlineImages], the inline images) of a message. */
    suspend fun loadBody(messageId: Long, inlineImages: Boolean): Unit =
        withContext(io) { loadBodyOnIo(messageId, inlineImages) }

    private suspend fun loadBodyOnIo(messageId: Long, inlineImages: Boolean) {
        val ref = db { store.messages.ref(messageId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Message $messageId")
        if (!ref.bodyLoaded) {
            val folder = db { store.folders.get(ref.folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder ${ref.folderId}")
            val body = clients.imap(ref.accountId).fetchBody(folder.fullName, ref.uid)
            db { storeBody(ref.id, body) }
        }
        if (inlineImages) downloadInlineImages(messageId)
    }

    private fun storeBody(messageId: Long, body: ParsedBody) {
        val plain = body.text ?: body.html?.let { runCatching { HtmlText.toPlainText(it) }.getOrNull() } ?: ""
        val snippet = runCatching { HtmlText.snippet(body.text, body.html) }.getOrDefault(plain.take(SNIPPET_FALLBACK))
        store.messages.storeBody(messageId, body.html, body.text, snippet, body.attachments, plain)
    }

    private suspend fun downloadInlineImages(messageId: Long) {
        val inline = db { store.messages.attachmentsFor(listOf(messageId))[messageId].orEmpty() }
            .filter { it.isInline && it.contentId != null && it.size in 0..MAX_INLINE_IMAGE && it.mimeType.startsWith("image/") }
            .filter { it.localPath == null || !File(it.localPath).isFile }
            .take(MAX_INLINE_IMAGES)
        for (attachment in inline) {
            try {
                downloadAttachment(attachment.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Images are optional; stop only when the connection is gone.
                if (e.toMailException().isTransient) return
            }
        }
    }

    private suspend fun prefetchBodies(folder: Folder) {
        val candidates = try {
            db { store.messages.needingBody(folder.id, PREFETCH_COUNT, PREFETCH_MAX_SIZE) }
        } catch (_: Exception) {
            return
        }
        for (ref in candidates) {
            try {
                val body = clients.imap(folder.accountId).fetchBody(folder.fullName, ref.uid)
                db { storeBody(ref.id, body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.toMailException().kind != MailErrorKind.NOT_FOUND) return
            }
        }
    }

    /** Returns the local copy of an attachment, downloading it first if needed. */
    suspend fun downloadAttachment(attachmentId: Long): File =
        withContext(io) { downloadAttachmentOnIo(attachmentId) }

    private suspend fun downloadAttachmentOnIo(attachmentId: Long): File {
        val location = db { store.messages.attachment(attachmentId) }
            ?: throw MailException(MailErrorKind.NOT_FOUND, "Attachment $attachmentId")
        val attachment = location.attachment
        attachment.localPath?.let { File(it) }?.takeIf { it.isFile && (it.length() > 0 || attachment.size == 0L) }?.let { return it }
        val destination = files.attachmentFile(attachment.messageId, attachment.id, attachment.fileName)
        val partial = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
        withContext(io) {
            destination.parentFile?.mkdirs()
        }
        try {
            clients.imap(location.accountId).downloadPart(location.folderFullName, location.uid, attachment.partPath, partial)
            withContext(io) {
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
            }
        } finally {
            withContext(NonCancellable + io) { if (partial.exists()) partial.delete() }
        }
        db { store.messages.setAttachmentLocalPath(attachment.id, destination.absolutePath) }
        return destination
    }

    /**
     * IMAP SEARCH in [folderId] (or the INBOX); matches missing from the cache are fetched and
     * stored. Returns the matches newest first.
     */
    suspend fun searchServer(accountId: Long, folderId: Long?, query: String, limit: Int): List<MessageSummary> =
        withContext(io) { searchServerOnIo(accountId, folderId, query, limit) }

    private suspend fun searchServerOnIo(accountId: Long, folderId: Long?, query: String, limit: Int): List<MessageSummary> {
        val folder = (folderId?.let { db { store.folders.get(it) } } ?: inboxOf(accountId))
        if (folder.accountId != accountId) throw MailException(MailErrorKind.NOT_FOUND, "Folder of another account")
        val imap = clients.imap(accountId)
        val uids = imap.search(folder.fullName, query, limit.coerceAtLeast(1))
        if (uids.isEmpty()) return emptyList()
        val missing = db { uids.filter { store.messages.findByUid(folder.id, it) == null } }
        if (missing.isNotEmpty()) {
            val fetched = imap.fetchMessages(folder.fullName, missing)
            val own = db { store.accounts.ownAddresses() }
            val protectedUids = db { store.pendingOps.protectedUids(accountId, folder.fullName) }
            db { store.messages.upsertHeaders(accountId, folder.id, fetched, protectedUids.uids, feedContacts = false, ownAddresses = own) }
            prefetchSearchBodies(folder, fetched)
        }
        val ids = db { uids.mapNotNull { uid -> store.messages.findByUid(folder.id, uid)?.takeIf { !it.hidden }?.id } }
        return db { store.messages.summaries(ids) }.sortedByDescending { it.date }
    }

    /** Snippets for the first search results, so the list is not just subjects. */
    private suspend fun prefetchSearchBodies(folder: Folder, fetched: List<RemoteMessage>) {
        val newest = fetched.sortedByDescending { it.uid }.filter { it.size < PREFETCH_MAX_SIZE }.take(SEARCH_PREFETCH)
        for (message in newest) {
            try {
                val ref = db { store.messages.findByUid(folder.id, message.uid) } ?: continue
                if (ref.bodyLoaded) continue
                val body = clients.imap(folder.accountId).fetchBody(folder.fullName, message.uid)
                db { storeBody(ref.id, body) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        }
    }

    /** Permanently deletes every message of the folder on the server and in the cache. */
    suspend fun emptyFolder(folderId: Long): Unit = withContext(io) { emptyFolderOnIo(folderId) }

    private suspend fun emptyFolderOnIo(folderId: Long) {
        val folder = db { store.folders.get(folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        withAccountLock(folder.accountId) {
            clients.imap(folder.accountId).deleteAll(folder.fullName)
            db {
                store.tx {
                    store.pendingOps.deleteForFolder(folder.accountId, folder.fullName)
                    store.messages.clearFolder(folderId)
                    store.folders.adjustCounts(folderId, -folder.unreadCount, -folder.totalCount)
                }
            }
        }
    }

    // ------------------------------------------------------------------ pending operations

    /**
     * Replays the account's pending operations in order. Operations still inside their undo
     * window are skipped. Network, login and certificate problems stop the flush and keep the
     * operation for later; any other error (e.g. NOT_FOUND) drops it and its local effect is
     * reverted (hidden messages reappear; flags get corrected by the next sync).
     */
    suspend fun flushPendingOps(accountId: Long): FlushResult =
        withContext(io) { flushPendingOpsOnIo(accountId) }

    private suspend fun flushPendingOpsOnIo(accountId: Long): FlushResult = withAccountLock(accountId) { flushLocked(accountId) }

    private suspend fun flushLocked(accountId: Long): FlushResult {
        recoverAfterRestart()
        val ops = db { store.pendingOps.forAccount(accountId) }
        if (ops.isEmpty()) return FlushResult.EMPTY
        val touched = HashSet<Long>()
        var blockedBy: MailException? = null
        val now = clock()
        for (op in ops) {
            if (op.inFlight || op.notBefore > now) continue
            if (!db { store.pendingOps.claim(op.id) }) continue
            // Completed only after the database reflects the outcome, so an undo waiting on it
            // sees either a released (cancellable) or a finished operation.
            val done = CompletableDeferred<Boolean>()
            inFlightOps[op.id] = done
            try {
                execute(op, touched)
                db { finishSuccess(op) }
                done.complete(true)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { db { store.pendingOps.release(op.id, op.attempts) } }
                throw e
            } catch (e: Exception) {
                val error = e.toMailException()
                if (error.kind in BLOCKING_KINDS && op.attempts + 1 < MAX_OP_ATTEMPTS) {
                    db { store.pendingOps.release(op.id, op.attempts + 1) }
                    blockedBy = error
                    break
                }
                db { finishFailure(op) }
            } finally {
                done.complete(false)
                inFlightOps.remove(op.id)
            }
        }
        val remaining = db { store.pendingOps.forAccount(accountId) }
        val nextDue = remaining.filter { !it.inFlight && it.notBefore > now }.minOfOrNull { it.notBefore }
        return FlushResult(nextDue, blockedBy, touched)
    }

    private suspend fun execute(op: PendingOp, touched: MutableSet<Long>) {
        val imap = clients.imap(op.accountId)
        when (op.type) {
            PendingOpType.FLAG -> {
                val flag = MessageFlag.entries.firstOrNull { it.name == op.flag } ?: return
                if (op.uids.isNotEmpty()) imap.setFlag(op.folder, op.uids, flag, op.value)
            }
            PendingOpType.MOVE -> {
                val target = op.target ?: return
                if (op.uids.isNotEmpty()) imap.move(op.folder, op.uids, target)
                db { store.folders.findByFullName(op.accountId, target) }?.let { touched += it.id }
            }
            PendingOpType.DELETE -> if (op.uids.isNotEmpty()) imap.deletePermanently(op.folder, op.uids)
            PendingOpType.MOVE_BY_MESSAGE_ID -> {
                val target = op.target ?: return
                val uids = op.payload.orEmpty().lines().filter { it.isNotBlank() }
                    .mapNotNull { imap.findUidByMessageId(op.folder, it.trim()) }
                if (uids.isNotEmpty()) imap.move(op.folder, uids, target)
                db { store.folders.findByFullName(op.accountId, target) }?.let { touched += it.id }
            }
            PendingOpType.MARK_FOLDER_READ -> {
                val status = imap.status(op.folder)
                val last = if (status.uidNext > 1) status.uidNext - 1 else MAX_UID
                val flags = imap.fetchFlags(op.folder, 1, last)
                val unseen = flags.filter { !it.value.seen && !it.value.deleted }.keys.sorted()
                unseen.chunked(FLAG_CHUNK).forEach { imap.setFlag(op.folder, it, MessageFlag.SEEN, true) }
            }
            PendingOpType.APPEND_DRAFT -> uploadDraft(op, imap, touched)
            PendingOpType.DELETE_SERVER_COPY ->
                deleteServerCopy(op.accountId, imap, ServerCopy(op.folder, op.uids.firstOrNull(), op.payload))
        }
    }

    private suspend fun uploadDraft(op: PendingOp, imap: ImapClient, touched: MutableSet<Long>) {
        val draftId = op.target?.toLongOrNull() ?: return
        val draft = db { store.drafts.get(draftId) } ?: return
        val account = clients.account(op.accountId)
        val draftsFolder = db { store.folders.findByRole(op.accountId, FolderRole.DRAFTS) } ?: return
        val composed = compose(account, draft)
        val uid = imap.append(draftsFolder.fullName, composed.mime, true, true)
        touched += draftsFolder.id
        val newCopy = ServerCopy(draftsFolder.fullName, uid, composed.messageId)
        val previous = db {
            store.tx {
                if (!store.drafts.exists(draftId)) return@tx null
                val old = store.drafts.serverCopy(draftId)
                store.drafts.setServerCopy(draftId, newCopy)
                old ?: NO_COPY
            }
        }
        when {
            // Sent or discarded while uploading: the copy just made must not linger.
            previous == null -> ignoreNotFound { deleteServerCopy(op.accountId, imap, newCopy) }
            previous !== NO_COPY -> ignoreNotFound { deleteServerCopy(op.accountId, imap, previous) }
        }
    }

    /**
     * Deletes a server copy of a draft. Found by Message-ID when known (a stored uid could
     * belong to another message after a UIDVALIDITY change), else by uid.
     */
    private suspend fun deleteServerCopy(accountId: Long, imap: ImapClient, copy: ServerCopy) {
        val uid = if (copy.messageIdHeader != null) {
            imap.findUidByMessageId(copy.folder, copy.messageIdHeader)
        } else {
            copy.uid
        } ?: return
        ignoreNotFound { imap.deletePermanently(copy.folder, listOf(uid)) }
        db {
            store.folders.findByFullName(accountId, copy.folder)?.let { folder -> store.messages.deleteByUids(folder.id, listOf(uid)) }
        }
    }

    private fun finishSuccess(op: PendingOp) = store.tx {
        when (op.type) {
            PendingOpType.MOVE, PendingOpType.DELETE -> {
                val rows = store.messages.refs(op.messageIds)
                if (op.type == PendingOpType.MOVE && op.undoGroup != null && op.target != null) {
                    val headers = rows.mapNotNull { it.messageIdHeader }
                    rememberMove(op.undoGroup, CompletedMove(op.accountId, op.folder, op.target, headers, clock()))
                }
                store.messages.deleteIds(rows.filter { it.hidden }.map { it.id })
            }
            PendingOpType.DELETE_SERVER_COPY ->
                store.messages.deleteIds(store.messages.refs(op.messageIds).filter { it.hidden }.map { it.id })
            else -> Unit
        }
        store.pendingOps.delete(op.id)
    }

    private fun finishFailure(op: PendingOp) = store.tx {
        if (op.type == PendingOpType.MOVE || op.type == PendingOpType.DELETE || op.type == PendingOpType.DELETE_SERVER_COPY) {
            store.messages.setHidden(op.messageIds, false)
        }
        store.pendingOps.delete(op.id)
    }

    private fun rememberMove(group: Long, move: CompletedMove) {
        val cutoff = clock() - COMPLETED_MOVE_TTL
        completedMoves.entries.removeIf { entry -> entry.value.all { it.completedAt < cutoff } }
        completedMoves.getOrPut(group) { mutableListOf() }.add(move)
    }

    /** Waits (bounded) until the given operations, if still executing, have finished. */
    suspend fun awaitInFlight(opIds: Collection<Long>, timeoutMillis: Long = IN_FLIGHT_WAIT) {
        withTimeoutOrNull(timeoutMillis) {
            for (id in opIds) inFlightOps[id]?.await()
        }
    }

    /** Moves of [group] that already reached the server (for a late undo), forgetting them. */
    fun takeCompletedMoves(group: Long): List<CompletedMove> {
        val cutoff = clock() - COMPLETED_MOVE_TTL
        return completedMoves.remove(group).orEmpty().filter { it.completedAt >= cutoff }
    }

    // ------------------------------------------------------------------ outbox

    /**
     * Sends the due QUEUED messages (of one account or all). Network failures put a message
     * back in the queue with a growing delay (FAILED after [MAX_SEND_ATTEMPTS]); other failures
     * mark it FAILED with the error kind as lastError. A message whose SMTP send succeeded is
     * removed from the outbox at once, so it can never be sent twice.
     */
    suspend fun sendOutbox(accountId: Long? = null): OutboxResult =
        withContext(io) { sendOutboxOnIo(accountId) }

    private suspend fun sendOutboxOnIo(accountId: Long?): OutboxResult = outboxLock.withLock {
        failStaleSending()
        val due = db { store.outbox.due(accountId, clock()) }
        var sent = 0
        var failed = 0
        var retrying = 0
        val sentFolders = HashSet<Long>()
        for (entry in due) {
            when (val outcome = sendOne(entry)) {
                is SendOutcome.Sent -> {
                    sent++
                    afterSend(outcome.account, entry, outcome.message)?.let { sentFolders += it }
                }
                SendOutcome.Failed -> failed++
                SendOutcome.Retry -> retrying++
                SendOutcome.Skipped -> Unit
            }
        }
        for (folderId in sentFolders) {
            try {
                syncFolder(folderId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The Sent copy shows up with the next refresh instead.
            }
        }
        OutboxResult(sent, failed, retrying, db { store.outbox.nextSendAt() })
    }

    private sealed interface SendOutcome {
        data class Sent(val account: Account, val message: ComposedMessage) : SendOutcome
        data object Failed : SendOutcome
        data object Retry : SendOutcome
        data object Skipped : SendOutcome
    }

    private suspend fun sendOne(entry: OutboxEntry): SendOutcome = withContext(NonCancellable) {
        val item = entry.item
        if (!db { store.outbox.claim(item.id) }) return@withContext SendOutcome.Skipped
        sendingNow += item.id
        try {
            val account = db { store.accounts.get(item.accountId) }
            if (account == null) {
                db { store.outbox.delete(item.id) }
                return@withContext SendOutcome.Skipped
            }
            val attempts = item.attempts + 1
            val composed = try {
                checkAttachments(item.draft.attachments)
                compose(account, item.draft)
            } catch (e: Exception) {
                // Building fails on local problems (unreadable attachment): retrying cannot help.
                val error = e.toMailException().let {
                    if (it.kind == MailErrorKind.NETWORK) MailException(MailErrorKind.UNKNOWN, it.message, it) else it
                }
                return@withContext markFailed(entry, attempts, error)
            }
            try {
                clients.smtp(account.id).send(composed.mime)
            } catch (e: Exception) {
                val error = e.toMailException()
                if (error.isTransient && attempts < MAX_SEND_ATTEMPTS) {
                    db { store.outbox.requeue(item.id, attempts, error.kind.name, clock() + retryDelay(attempts)) }
                    return@withContext SendOutcome.Retry
                }
                return@withContext markFailed(entry, attempts, error)
            }
            db { store.outbox.delete(item.id) }
            SendOutcome.Sent(account, composed)
        } finally {
            sendingNow -= item.id
        }
    }

    private suspend fun markFailed(entry: OutboxEntry, attempts: Int, error: MailException): SendOutcome {
        db { store.outbox.fail(entry.item.id, attempts, error.kind.name) }
        notifySendFailed(entry.item.copy(state = OutboxState.FAILED, attempts = attempts, lastError = error.kind.name))
        return SendOutcome.Failed
    }

    private suspend fun notifySendFailed(item: OutboxItem) = notify { listener.onSendFailed(item) }

    /** Listener calls run on the IO dispatcher (they may query the database) and never throw. */
    private suspend fun notify(block: () -> Unit) {
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Notifications are best effort.
        }
    }

    /**
     * Operations marked in flight by a process that died can never complete: put them back in
     * the queue, once, before the first flush of this process.
     */
    private suspend fun recoverAfterRestart() {
        if (recovered) return
        recoveryLock.withLock {
            if (!recovered) {
                db { store.pendingOps.resetInFlight() }
                recovered = true
            }
        }
    }

    /**
     * Best-effort bookkeeping after a successful send. Returns the Sent folder to refresh, if
     * the copy was stored there and the folder is being synchronised.
     */
    private suspend fun afterSend(account: Account, entry: OutboxEntry, message: ComposedMessage): Long? {
        val draft = entry.item.draft
        bestEffort { db { store.contacts.recordContacted(draft.to + draft.cc + draft.bcc, clock()) } }
        var refresh: Long? = null
        bestEffort {
            val sent = db { store.folders.findByRole(account.id, FolderRole.SENT) }
            if (sent != null) {
                clients.imap(account.id).append(sent.fullName, message.mime, true, false)
                if (sent.lastSyncedAt != null) refresh = sent.id
            }
        }
        bestEffort { db { recordSourceFlag(draft) } }
        bestEffort { db { recordServerDraftDeletion(account.id, entry) } }
        bestEffort { flushPendingOps(account.id) }
        bestEffort {
            db {
                val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
                files.deleteComposeFiles(draft.attachments.map { it.uri }, referenced)
            }
        }
        return refresh
    }

    /** \Answered or $Forwarded on the message replied to or forwarded. */
    private fun recordSourceFlag(draft: Draft) {
        val sourceId = draft.sourceMessageId ?: return
        val (localFlag, serverFlag) = when (draft.mode) {
            ComposeMode.REPLY, ComposeMode.REPLY_ALL -> LocalFlag.ANSWERED to MessageFlag.ANSWERED
            ComposeMode.FORWARD -> LocalFlag.FORWARDED to MessageFlag.FORWARDED
            else -> return
        }
        store.tx {
            store.messages.setFlag(listOf(sourceId), localFlag, true)
            val ref = store.messages.ref(sourceId) ?: return@tx
            val folder = store.folders.get(ref.folderId) ?: return@tx
            store.pendingOps.insert(
                PendingOp(
                    accountId = ref.accountId,
                    type = PendingOpType.FLAG,
                    folder = folder.fullName,
                    uids = listOf(ref.uid),
                    messageIds = listOf(ref.id),
                    flag = serverFlag.name,
                    value = true,
                    notBefore = clock(),
                ),
            )
        }
    }

    /** Queues the deletion of the server draft the sent message was edited from. */
    private fun recordServerDraftDeletion(accountId: Long, entry: OutboxEntry) {
        val copy = entry.serverCopy
            ?: entry.item.draft.serverDraftMessageId?.let { store.drafts.serverCopyOfMessage(it) }
            ?: return
        store.tx {
            val folder = store.folders.findByFullName(accountId, copy.folder)
            val local = folder?.let { f ->
                listOfNotNull(
                    copy.uid?.let { store.messages.findByUid(f.id, it) },
                    copy.messageIdHeader?.let { store.messages.findByMessageIdHeader(f.id, it) },
                ).distinctBy { it.id }
            }.orEmpty()
            store.messages.setHidden(local.map { it.id }, true)
            store.pendingOps.insert(
                PendingOp(
                    accountId = accountId,
                    type = PendingOpType.DELETE_SERVER_COPY,
                    folder = copy.folder,
                    uids = listOfNotNull(copy.uid),
                    messageIds = local.map { it.id },
                    payload = copy.messageIdHeader,
                    notBefore = clock(),
                ),
            )
        }
    }

    /**
     * Messages left in SENDING for more than ten minutes by a process that died mid-send become
     * FAILED. They may have been sent already, so they are never resent automatically.
     */
    suspend fun failStaleSending(): Unit = withContext(io) { failStaleSendingOnIo() }

    private suspend fun failStaleSendingOnIo() {
        val failed = db { store.outbox.failStaleSending(clock() - STALE_SENDING, sendingNow.toSet(), MailErrorKind.UNKNOWN.name) }
        failed.forEach { notifySendFailed(it) }
    }

    private fun checkAttachments(attachments: List<OutgoingAttachment>) {
        for (attachment in attachments) {
            try {
                attachmentOpener.open(attachment.uri).close()
            } catch (e: Exception) {
                throw MailException(MailErrorKind.UNKNOWN, "Attachment ${attachment.fileName} is not readable", e)
            }
        }
    }

    private fun compose(account: Account, draft: Draft): ComposedMessage {
        val from = EmailAddress(account.email, account.displayName.takeIf { it.isNotBlank() })
        val sources = draft.attachments.map { attachment ->
            object : AttachmentSource {
                override val fileName: String = attachment.fileName
                override val mimeType: String = attachment.mimeType.ifBlank { "application/octet-stream" }
                override fun openStream(): InputStream = attachmentOpener.open(attachment.uri)
            }
        }
        return MessageComposer.build(from, draft, sources, clock())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun <T> withAccountLock(accountId: Long, block: suspend () -> T): T =
        accountLocks.getOrPut(accountId) { Mutex() }.withLock { block() }

    private suspend fun <T> markSyncing(folderId: Long, block: suspend () -> T): T {
        updateSyncing(folderId, +1)
        try {
            return block()
        } finally {
            updateSyncing(folderId, -1)
        }
    }

    private fun updateSyncing(folderId: Long, delta: Int) {
        synchronized(syncingCounts) {
            val count = (syncingCounts[folderId] ?: 0) + delta
            if (count <= 0) syncingCounts.remove(folderId) else syncingCounts[folderId] = count
            syncing.value = syncingCounts.keys.toSet()
        }
    }

    private suspend fun <T> db(block: () -> T): T = withContext(io) { block() }

    private suspend inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the message is already sent; the next sync repairs the rest.
        }
    }

    private suspend inline fun ignoreNotFound(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.toMailException().kind != MailErrorKind.NOT_FOUND) throw e
        }
    }

    private fun retryDelay(attempts: Int): Long =
        (FIRST_RETRY_DELAY shl (attempts - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_DELAY)

    companion object {
        const val INITIAL_WINDOW = 50
        const val NEWER_LIMIT = 200
        const val PREFETCH_COUNT = 20
        const val PREFETCH_MAX_SIZE = 300L * 1024
        const val MAX_SEND_ATTEMPTS = 10
        private const val SEARCH_PREFETCH = 10
        private const val MAX_EXTRA_CHECKS = 30
        private const val MAX_INLINE_IMAGES = 20
        private const val MAX_INLINE_IMAGE = 2L * 1024 * 1024
        private const val SNIPPET_FALLBACK = 160
        private const val FLAG_CHUNK = 500
        private const val MAX_UID = 4_294_967_295L
        private const val MAX_OP_ATTEMPTS = 50
        private const val IN_FLIGHT_WAIT = 15_000L
        private const val COMPLETED_MOVE_TTL = 5 * 60_000L
        private const val STALE_SENDING = 10 * 60_000L
        private const val FIRST_RETRY_DELAY = 30_000L
        private const val MAX_RETRY_DELAY = 15 * 60_000L

        private val BLOCKING_KINDS = setOf(MailErrorKind.NETWORK, MailErrorKind.AUTHENTICATION, MailErrorKind.CERTIFICATE)

        /** Marker: the draft had no previous server copy. */
        private val NO_COPY = ServerCopy("", null, null)
    }
}
