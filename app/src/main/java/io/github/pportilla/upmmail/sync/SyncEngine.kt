package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.FolderDao
import io.github.pportilla.upmmail.data.db.FolderListEntry
import io.github.pportilla.upmmail.data.db.FolderSyncState
import io.github.pportilla.upmmail.data.db.LocalFlag
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.MessageRef
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
import io.github.pportilla.upmmail.mail.ImapOptions
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
import io.github.pportilla.upmmail.model.HitOpenResult
import io.github.pportilla.upmmail.model.IndexedMessage
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.model.OutgoingAttachment
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.util.SharedFileInputStream
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
import java.util.Locale
import java.util.Properties
import java.util.UUID
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
 *
 * It also keeps the header index (see [HeaderIndexer]) in step with what it learns while syncing,
 * and owns the server search ([serverSearch]) and the indexer ([headerIndexer]), each with
 * connections of their own (`searchOptions`, `indexOptions`).
 */
class SyncEngine(
    private val store: MailStore,
    private val clients: MailClientProvider,
    private val files: LocalFiles,
    private val attachmentOpener: AttachmentOpener,
    private val listener: SyncListener = SyncListener.NONE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    indexOptions: ImapOptions = ImapOptions.INDEX,
    searchOptions: ImapOptions = ImapOptions.SEARCH,
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

    /** Progressive search of every folder on the server. */
    val serverSearch = ServerSearch(
        store,
        clients,
        io,
        clock,
        searchOptions,
        ::storeParsedBody,
        onValidityChanged = ::resyncQuietly,
        ensureFolders = ::ensureFolderList,
    )

    /** Builds and maintains the header index; it pauses while [serverSearch] uses an account. */
    val headerIndexer = HeaderIndexer(
        store,
        clients,
        io,
        clock,
        indexOptions,
        serverSearch.activeAccounts,
        ensureFolders = ::ensureFolderList,
        onValidityChanged = ::resyncQuietly,
    )

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

    /**
     * Fetches the folder list of an account that has none yet, or whose folders were not listed
     * since the upgrade that started keeping their attributes (until then none is indexed or
     * searched: they may be virtual or shared folders).
     */
    private suspend fun ensureFolderList(accountId: Long) {
        val needed = db { store.folders.getByAccount(accountId).isEmpty() || store.folders.hasUnlistedFolders(accountId) }
        if (needed) syncFolderList(accountId)
    }

    /** Syncs a folder found renumbered (new UIDVALIDITY) outside a sync; a failure waits for the next sync. */
    private suspend fun resyncQuietly(folderId: Long) {
        mailResult { syncFolder(folderId) }
    }

    /** Folder list, outbox and INBOX of one account: what "refresh" means for the user. */
    suspend fun syncAccount(accountId: Long): List<MessageSummary> =
        withContext(io) { syncAccountOnIo(accountId) }

    private suspend fun syncAccountOnIo(accountId: Long): List<MessageSummary> {
        syncFolderList(accountId)
        // A send in progress (maybe a long upload) must not hold up the refresh: that sender
        // handles the due messages, and the controller plans the later ones.
        if (outboxLock.tryLock()) {
            try {
                sendOutboxLocked(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Send failures are recorded on the outbox items themselves.
            } finally {
                outboxLock.unlock()
            }
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
        // No later sync reports stored mail as new, so it is reported even when this sync is
        // cancelled (a push restart, a stopped job) or fails after storing it.
        val newIds = ArrayList<Long>()
        var synced = false
        try {
            markSyncing(folderId) {
                withAccountLock(folder.accountId) {
                    flushLocked(folder.accountId)
                    syncHeadersLocked(folderId, newIds)
                }
            }
            prefetchBodies(folder)
            synced = true
        } finally {
            if (!synced && folder.role == FolderRole.INBOX && newIds.isNotEmpty()) {
                withContext(NonCancellable) {
                    // Best effort: the caller hears about the sync's own failure.
                    val fresh = try {
                        freshMail(newIds)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (fresh.isNotEmpty()) notify { listener.onNewMessages(folder.accountId, folderId, fresh) }
                }
            }
        }
        if (folder.role != FolderRole.INBOX) return emptyList()
        return withContext(NonCancellable) {
            val fresh = freshMail(newIds)
            notify {
                listener.onInboxSynced(folder.accountId, folderId)
                if (fresh.isNotEmpty()) listener.onNewMessages(folder.accountId, folderId, fresh)
            }
            fresh
        }
    }

    /** The unread ones among the messages [ids], newest first. */
    private suspend fun freshMail(ids: List<Long>): List<MessageSummary> =
        db { store.messages.summaries(ids) }.filter { !it.isRead }.sortedByDescending { it.date }

    /**
     * Adds the ids of the messages inserted by this sync that are genuinely new mail to [newMail]
     * as they are stored: inside db {}, as a cancellation discards the result of a db {} call.
     */
    private suspend fun syncHeadersLocked(folderId: Long, newMail: MutableList<Long>) {
        var state = db { store.folders.syncState(folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        val folder = state.folder
        val imap = clients.imap(folder.accountId)
        val status = imap.status(folder.fullName)
        if (state.uidValidity != 0L && state.uidValidity != status.uidValidity) {
            dropFolderCache(state)
            state = db { store.folders.syncState(folderId) } ?: return
        }
        val own = db { store.accounts.ownAddresses() }
        val feedContacts = folder.role != FolderRole.TRASH && folder.role != FolderRole.JUNK
        // Whatever the sync fetches goes into the header index too, so search finds it at once.
        val indexed = db { FolderDao.isIndexable(folder.role, folder.selectable, store.folders.attributesOf(folderId)) }
        if (indexed) {
            indexQuietly { store.headerIndex.ensureState(folderId, status.uidValidity, status.uidNext, status.messageCount) }
        }

        if (state.uidValidity == 0L) {
            // First sync (or after a reset): the newest messages only, nothing to notify about.
            val result = imap.fetchNewest(folder.fullName, INITIAL_WINDOW)
            db { storeHeaders(folder, result.messages, feedContacts, own) }
            if (indexed) indexQuietly { store.headerIndex.upsertFromSync(folderId, result.uidValidity, result.messages) }
            val start = result.messages.minOfOrNull { it.uid } ?: status.uidNext.coerceAtLeast(1)
            val end = maxOf(result.messages.maxOfOrNull { it.uid } ?: 0L, status.uidNext - 1)
            db { setWindowAndDetach(folderId, start, end, result.truncated) }
            db { store.folders.updateAfterSync(folderId, status.uidValidity, status.unreadCount, status.messageCount) }
        } else {
            val oldEnd = state.windowEndUid
            val result = imap.fetchNewer(folder.fullName, oldEnd, NEWER_LIMIT)
            if (result.truncated) {
                // Too many new messages to fill the gap: restart the window at the newest ones.
                // Rows the user opened or found a moment ago survive, detached.
                val keep = result.messages.takeLast(INITIAL_WINDOW)
                db {
                    store.tx {
                        store.messages.deleteVisibleExcept(
                            folderId,
                            protectedNow(folder).uids + keep.map { it.uid },
                            spareDetachedSince = clock() - HeaderIndexer.DETACHED_SPARE,
                        )
                    }
                }
                db { newMail += storeHeaders(folder, keep, feedContacts, own) }
                if (indexed) indexQuietly { store.headerIndex.upsertFromSync(folderId, result.uidValidity, result.messages) }
                val start = keep.minOfOrNull { it.uid } ?: oldEnd
                val end = maxOf(keep.maxOfOrNull { it.uid } ?: oldEnd, status.uidNext - 1)
                db { setWindowAndDetach(folderId, start, end, true) }
            } else {
                db { newMail += storeHeaders(folder, result.messages, feedContacts, own) }
                if (indexed) indexQuietly { store.headerIndex.upsertFromSync(folderId, result.uidValidity, result.messages) }
                val end = maxOf(oldEnd, result.messages.maxOfOrNull { it.uid } ?: 0L, status.uidNext - 1)
                db { store.folders.setWindow(folderId, state.windowStartUid, end, state.moreAvailable) }
                reconcileFlags(imap, state, oldEnd)
            }
            db {
                store.tx {
                    // The STATUS count predates read changes the user made during this sync (or
                    // that are still waiting to be pushed): keep the local count then, plus the
                    // new mail stored now.
                    val unread = if (store.pendingOps.hasPendingReadChanges(folder.accountId, folder.fullName)) {
                        val local = store.folders.get(folderId)?.unreadCount ?: 0
                        local + store.messages.refs(newMail).count { !it.isRead && !it.hidden }
                    } else {
                        status.unreadCount
                    }
                    store.folders.updateAfterSync(folderId, status.uidValidity, unread, status.messageCount)
                }
            }
        }
    }

    /**
     * Server flags for the cached window, then for the detached rows (search results and opened
     * messages outside the window): the least recently used beyond [HeaderIndexer.DETACHED_KEEP]
     * are evicted, and the flags of the rest are fetched in one go.
     */
    private suspend fun reconcileFlags(imap: ImapClient, state: FolderSyncState, windowEnd: Long) {
        val folder = state.folder
        val start = state.windowStartUid.coerceAtLeast(1)
        if (windowEnd >= start && db { store.messages.uidBounds(folder.id) } != null) {
            val flags = imap.fetchFlags(folder.fullName, start, windowEnd)
            db { applyFlags(folder, start..windowEnd, flags) }
            val present = flags.filterValues { !it.deleted }.keys
            indexQuietly { store.headerIndex.deleteMissingInRange(folder.id, start..windowEnd, present) }
        }
        if (db { store.messages.extras(folder.id) }.isEmpty()) return
        val spareSince = clock() - HeaderIndexer.DETACHED_SPARE
        db { store.tx { store.messages.evictExtras(folder.id, HeaderIndexer.DETACHED_KEEP, spareSince, protectedNow(folder)) } }
        val extras = db { store.messages.extras(folder.id) }.map { it.uid }
        if (extras.isEmpty()) return
        val flags = imap.fetchFlagsFor(folder.fullName, extras)
        db {
            store.tx {
                val protectedUids = protectedNow(folder)
                store.messages.applyServerFlagsFor(folder.id, extras, flags, extras.filterTo(HashSet()) { it in protectedUids })
            }
        }
        val gone = extras.filter { flags[it]?.deleted != false }
        if (gone.isNotEmpty()) indexQuietly { store.headerIndex.deleteUids(folder.id, gone) }
    }

    /** The folder's new sync window; listed rows outside it are detached (kept for search and open messages). */
    private fun setWindowAndDetach(folderId: Long, start: Long, end: Long, moreAvailable: Boolean) = store.tx {
        store.folders.setWindow(folderId, start, end, moreAvailable)
        store.messages.detachOutsideWindow(folderId, start, end, clock())
    }

    /**
     * Uids of [folder] whose local flags must not get the server's yet. Read in the transaction
     * that writes the server state: the user's read/star changes do not wait for the account
     * lock, so one made while the server was being asked must still win over its older flags.
     */
    private fun protectedNow(folder: Folder): ProtectedUids = store.pendingOps.protectedUids(folder.accountId, folder.fullName)

    private fun applyFlags(folder: Folder, range: LongRange, flags: Map<Long, RemoteFlags>) = store.tx {
        val protectedUids = protectedNow(folder)
        store.messages.applyServerFlags(folder.id, range, flags, flags.keys.filterTo(HashSet()) { it in protectedUids })
    }

    /** Stores fetched headers; returns the ids of the messages that were not cached yet. */
    private fun storeHeaders(folder: Folder, messages: List<RemoteMessage>, feedContacts: Boolean, own: Set<String>): List<Long> =
        store.tx {
            val protectedUids = protectedNow(folder)
            val keepFlags = messages.mapNotNullTo(HashSet()) { message -> message.uid.takeIf { it in protectedUids } }
            store.messages.upsertHeaders(folder.accountId, folder.id, messages, keepFlags, feedContacts, own)
        }

    private suspend fun dropFolderCache(state: FolderSyncState) {
        val folder = state.folder
        db {
            store.tx {
                val dropped = store.pendingOps.deleteForFolder(folder.accountId, folder.fullName)
                store.messages.clearFolder(folder.id)
                store.folders.resetSyncState(folder.id)
                store.headerIndex.resetFolder(folder.id)
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
                val feedContacts = folder.role != FolderRole.TRASH && folder.role != FolderRole.JUNK
                db { storeHeaders(folder, result.messages, feedContacts, own) }
                indexQuietly { store.headerIndex.upsertFromSync(folderId, current.uidValidity, result.messages) }
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
            db { storeParsedBody(ref.id, body) }
        }
        if (inlineImages) downloadInlineImages(messageId)
    }

    /** Stores a downloaded body (text for search, snippet, attachments); also used by [ServerSearch]. */
    internal fun storeParsedBody(messageId: Long, body: ParsedBody) {
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
                // Opened (and downloaded) meanwhile, or gone.
                if (db { store.messages.ref(ref.id) }?.bodyLoaded != false) continue
                val body = clients.imap(folder.accountId).fetchBody(folder.fullName, ref.uid)
                db { storeParsedBody(ref.id, body) }
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
        attachment.localPath?.let { File(it) }?.takeIf { it.isFile && (it.length() > 0 || attachment.size == 0L) }?.let { cached ->
            files.touch(cached, clock())
            return cached
        }
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
     * stored as detached rows (they do not change the folder's list). Returns the matches newest
     * first.
     */
    @Deprecated("Searches one folder and blocks until done; use serverSearch, which searches every folder progressively.")
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
            val fetched = imap.fetchByUids(folder.fullName, missing)
            if (db { store.storeFetchedOutsideSync(folder, fetched.uidValidity, fetched.messages, clock()) }) {
                prefetchSearchBodies(folder, fetched.messages)
            }
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
                db { storeParsedBody(ref.id, body) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        }
    }

    /**
     * Permanently deletes every message of the folder on the server and in the cache. Messages
     * the user moved out of it whose move still waits for its undo window are moved first; if
     * that is not possible now (offline, login problem), nothing is deleted and the error is
     * thrown.
     */
    suspend fun emptyFolder(folderId: Long): Unit = withContext(io) { emptyFolderOnIo(folderId) }

    private suspend fun emptyFolderOnIo(folderId: Long) {
        val folder = db { store.folders.get(folderId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        withAccountLock(folder.accountId) {
            // Until their move has run, such messages are still in this folder on the server.
            // A completed move can still be undone (see takeCompletedMoves).
            db { store.pendingOps.makeDue(folder.accountId, folder.fullName, MOVES_OUT) }
            val flushed = flushLocked(folder.accountId)
            val stranded = db { store.pendingOps.forAccount(folder.accountId) }
                .any { it.folder == folder.fullName && it.type in MOVES_OUT }
            if (stranded) {
                throw flushed.blockedBy
                    ?: MailException(MailErrorKind.UNKNOWN, "Messages moved out of ${folder.fullName} are not moved yet")
            }
            clients.imap(folder.accountId).deleteAll(folder.fullName)
            db {
                store.tx {
                    store.pendingOps.deleteForEmptiedFolder(folder.accountId, folder.fullName)
                    store.messages.clearFolder(folderId)
                    store.headerIndex.clearRows(folderId)
                    store.folders.get(folderId)?.let { current ->
                        store.folders.adjustCounts(folderId, -current.unreadCount, -current.totalCount)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ pending operations

    /**
     * Replays the account's pending operations in order. Operations still inside their undo
     * window are skipped. Network, login and certificate problems (and temporary refusals) stop
     * the flush and keep the operation for later; any other error (e.g. NOT_FOUND) drops it and
     * its local effect is reverted (hidden messages reappear; flags get corrected by the next
     * sync; a draft upload is kept as failed, see [finishFailure]). Only a transient failure
     * while the server otherwise answers counts against the operation itself: it is dropped
     * after [MAX_OP_ATTEMPTS] of those, and only once it is older than [MAX_OP_AGE], so being
     * offline or locked out never discards the user's changes. An upload ([UPLOADS]) failing that
     * way does not stop the flush (nothing depends on its order): from its second such failure on
     * it waits a while before it is uploaded again, and it gives up after [MAX_UPLOAD_ATTEMPTS].
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
                indexQuietly { updateIndexAfter(op) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { db { store.pendingOps.release(op.id, op.attempts) } }
                throw e
            } catch (e: Exception) {
                val error = e.toMailException()
                val keep = when {
                    error.kind in ACCOUNT_KINDS -> {
                        db { store.pendingOps.release(op.id, op.attempts) }
                        true
                    }
                    error.isTransient -> {
                        val answers = serverAnswers(op.accountId)
                        val attempts = if (answers) op.attempts + 1 else op.attempts
                        if (answers && op.type in UPLOADS) {
                            if (attempts < MAX_UPLOAD_ATTEMPTS) {
                                // A first failure may have been the network coming back just now:
                                // due again at once. After that, the message is not uploaded on
                                // every flush.
                                val notBefore = if (attempts > 1) clock() + retryDelay(attempts - 1) else null
                                db { store.pendingOps.release(op.id, attempts, notBefore) }
                            } else {
                                db { finishFailure(op) }
                            }
                            continue
                        }
                        val retry = attempts < MAX_OP_ATTEMPTS || now - op.createdAt < MAX_OP_AGE
                        if (retry) db { store.pendingOps.release(op.id, attempts) }
                        retry
                    }
                    else -> false
                }
                if (keep) {
                    blockedBy = error
                    break
                }
                db { finishFailure(op) }
            } finally {
                done.complete(false)
                inFlightOps.remove(op.id)
            }
        }
        // Mail we added there is new to the header index: a check soon indexes it.
        if (touched.isNotEmpty()) indexQuietly { store.headerIndex.markDirty(touched) }
        val remaining = db { store.pendingOps.forAccount(accountId) }
        val nextDue = remaining.filter { !it.inFlight && it.notBefore > now }.minOfOrNull { it.notBefore }
        return FlushResult(nextDue, blockedBy, touched)
    }

    /** Whether the account's server answers right now (after an operation failed with a network error). */
    private suspend fun serverAnswers(accountId: Long): Boolean =
        try {
            clients.imap(accountId).status("INBOX")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
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
                // Up to the newest message known when the user asked: later mail stays unread.
                val last = op.payload?.toLongOrNull()?.takeIf { it > 0 }
                    ?: imap.status(op.folder).uidNext.let { next -> if (next > 1) next - 1 else MAX_UID }
                val flags = imap.fetchFlags(op.folder, 1, last)
                val unseen = flags.filter { !it.value.seen && !it.value.deleted }.keys.sorted()
                unseen.chunked(FLAG_CHUNK).forEach { imap.setFlag(op.folder, it, MessageFlag.SEEN, true) }
            }
            PendingOpType.APPEND_DRAFT -> uploadDraft(op, imap, touched)
            PendingOpType.DELETE_SERVER_COPY ->
                deleteServerCopy(op.accountId, imap, ServerCopy(op.folder, op.uids.firstOrNull(), op.payload))
            PendingOpType.APPEND_SENT -> appendSentCopy(op, imap, touched)
        }
    }

    /** Stores a saved sent message in the Sent folder, unless an earlier attempt already did. */
    private suspend fun appendSentCopy(op: PendingOp, imap: ImapClient, touched: MutableSet<Long>) {
        val file = op.payload?.let(::File)?.takeIf { isSentCopy(it) && withContext(io) { it.isFile } } ?: return
        val input = readLocal { SharedFileInputStream(file) }
        try {
            val message = readLocal { MimeMessage(Session.getInstance(Properties()), input) }
            // The previous attempt may have reached the server before its answer was lost.
            val messageId = message.messageID
            val stored = messageId != null && imap.findUidByMessageId(op.folder, messageId) != null
            if (!stored) imap.append(op.folder, message, true, false)
        } finally {
            withContext(NonCancellable + io) { input.close() }
        }
        db { store.folders.findByFullName(op.accountId, op.folder) }?.let { touched += it.id }
    }

    /**
     * Stores a local draft in the Drafts folder and deletes its previous copy there. The new copy
     * is added to the cache at once, so the Drafts list never misses the draft. Failing for good
     * (no Drafts folder, refused by the server, unreadable attachment) keeps the operation as
     * failed (see [finishFailure]): the draft stays listed as being only on this device.
     */
    private suspend fun uploadDraft(op: PendingOp, imap: ImapClient, touched: MutableSet<Long>) {
        val draftId = op.target?.toLongOrNull() ?: return
        val draft = db { store.drafts.get(draftId) } ?: return
        val account = clients.account(op.accountId)
        val draftsFolder = db { store.folders.findByRole(op.accountId, FolderRole.DRAFTS) }
            ?: throw MailException(MailErrorKind.NOT_FOUND, "The account has no Drafts folder")
        val composed = compose(account, draft)
        val uid = imap.append(draftsFolder.fullName, composed.mime, true, true)
        touched += draftsFolder.id
        // From here on a failure must not fail the operation: running it again would store a
        // second copy.
        val stored = fetchUploadedDraft(imap, draftsFolder, uid, composed.messageId)
        val newCopy = ServerCopy(draftsFolder.fullName, uid ?: stored?.uid, composed.messageId)
        val previous = db {
            store.tx {
                if (!store.drafts.exists(draftId)) return@tx null
                if (stored != null) storeHeaders(draftsFolder, listOf(stored), feedContacts = false, own = emptySet())
                val old = store.drafts.serverCopy(draftId)
                store.drafts.setServerCopy(draftId, newCopy)
                old ?: NO_COPY
            }
        }
        when {
            // Sent or discarded while uploading: the copy just made must not linger.
            previous == null -> deleteOrQueueServerCopy(op.accountId, imap, newCopy)
            previous !== NO_COPY -> deleteOrQueueServerCopy(op.accountId, imap, previous)
        }
    }

    /** Headers of a draft just appended (by [uid], else by Message-ID), or null if they cannot be had. */
    private suspend fun fetchUploadedDraft(imap: ImapClient, folder: Folder, uid: Long?, messageId: String): RemoteMessage? =
        try {
            val found = uid ?: imap.findUidByMessageId(folder.fullName, messageId)
            found?.let { imap.fetchMessages(folder.fullName, listOf(it)).firstOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next sync of the folder brings it.
            null
        }

    /** Deletes a superseded draft copy now or, if that fails, with the next flush. */
    private suspend fun deleteOrQueueServerCopy(accountId: Long, imap: ImapClient, copy: ServerCopy) {
        try {
            ignoreNotFound { deleteServerCopy(accountId, imap, copy) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            db { store.drafts.queueServerCopyDeletion(accountId, copy, clock()) }
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
        discardSentCopy(op)
    }

    /**
     * What a completed operation changed in the header index: messages moved or deleted leave
     * their folder's index at once, a move target waits for new mail, flags follow. Apart from
     * [finishSuccess], so that a failure here can never undo the operation's local effect.
     */
    private fun updateIndexAfter(op: PendingOp) {
        val source = store.folders.findByFullName(op.accountId, op.folder)
        val target = op.target?.let { store.folders.findByFullName(op.accountId, it) }
        when (op.type) {
            PendingOpType.MOVE, PendingOpType.DELETE, PendingOpType.DELETE_SERVER_COPY -> {
                if (source != null) store.headerIndex.deleteUids(source.id, op.uids)
                if (op.type == PendingOpType.MOVE && target != null) store.headerIndex.markDirty(listOf(target.id))
            }
            PendingOpType.MOVE_BY_MESSAGE_ID -> {
                val messageIds = op.payload.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (source != null) store.headerIndex.deleteByMessageIds(source.id, messageIds)
                if (target != null) store.headerIndex.markDirty(listOf(target.id))
            }
            PendingOpType.FLAG -> {
                val flag = when (op.flag) {
                    MessageFlag.SEEN.name -> LocalFlag.READ
                    MessageFlag.FLAGGED.name -> LocalFlag.STARRED
                    MessageFlag.ANSWERED.name -> LocalFlag.ANSWERED
                    else -> null
                }
                if (source != null && flag != null) store.headerIndex.setFlag(source.id, op.uids, flag, op.value)
            }
            PendingOpType.MARK_FOLDER_READ -> if (source != null) store.headerIndex.markFolderRead(source.id)
            PendingOpType.APPEND_DRAFT, PendingOpType.APPEND_SENT -> Unit
        }
    }

    private fun finishFailure(op: PendingOp) = store.tx {
        when (op.type) {
            PendingOpType.MOVE, PendingOpType.DELETE -> restoreHidden(op)
            PendingOpType.DELETE_SERVER_COPY -> store.messages.setHidden(op.messageIds, false)
            PendingOpType.APPEND_DRAFT -> {
                // Kept as failed: the draft is then listed as only on this device, and saving it
                // again tries the upload again.
                val draftId = op.target?.toLongOrNull()
                if (draftId != null && store.drafts.exists(draftId)) {
                    store.pendingOps.markFailed(op.id)
                    return@tx
                }
            }
            else -> Unit
        }
        store.pendingOps.delete(op.id)
        discardSentCopy(op)
    }

    /**
     * Shows the messages of a failed move or delete again and takes back the counter changes
     * made when it was recorded (as an undo does). Inside a transaction.
     */
    private fun restoreHidden(op: PendingOp) {
        val rows = store.messages.refs(op.messageIds).filter { it.hidden }
        if (rows.isEmpty()) return
        store.messages.setHidden(rows.map { it.id }, false)
        val unread = rows.count { !it.isRead }
        store.folders.findByFullName(op.accountId, op.folder)?.let { store.folders.adjustCounts(it.id, unread, rows.size) }
        op.target?.let { target ->
            store.folders.findByFullName(op.accountId, target)?.let { store.folders.adjustCounts(it.id, -unread, -rows.size) }
        }
    }

    /** The saved message of a finished [PendingOpType.APPEND_SENT] is not needed any more. */
    private fun discardSentCopy(op: PendingOp) {
        if (op.type != PendingOpType.APPEND_SENT) return
        op.payload?.let { File(it) }?.takeIf { isSentCopy(it) }?.delete()
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

    /** True while messages are being sent (or their bookkeeping is being done) in this process. */
    val isSending: Boolean get() = outboxLock.isLocked || sendingNow.isNotEmpty()

    private suspend fun sendOutboxOnIo(accountId: Long?): OutboxResult = outboxLock.withLock { sendOutboxLocked(accountId) }

    private suspend fun sendOutboxLocked(accountId: Long?): OutboxResult {
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
                    afterSend(entry, outcome)?.let { sentFolders += it }
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
        return OutboxResult(sent, failed, retrying, db { store.outbox.nextSendAt() })
    }

    private sealed interface SendOutcome {
        /** Delivered; [sentCopyQueued] when its Sent copy waits as an [PendingOpType.APPEND_SENT]. */
        data class Sent(val account: Account, val message: ComposedMessage, val sentCopyQueued: Boolean) : SendOutcome
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
            // Saved before sending, so that only one transaction separates the delivery from
            // the removal of the message from the outbox (see recordSent).
            val sentCopy = saveSentCopy(account, composed)
            try {
                clients.smtp(account.id).send(composed.mime)
            } catch (e: Exception) {
                sentCopy?.let { withContext(io) { it.file.delete() } }
                val error = e.toMailException()
                if (error.isTransient && attempts < MAX_SEND_ATTEMPTS) {
                    db { store.outbox.requeue(item.id, attempts, error.kind.name, clock() + retryDelay(attempts)) }
                    return@withContext SendOutcome.Retry
                }
                return@withContext markFailed(entry, attempts, error)
            }
            SendOutcome.Sent(account, composed, recordSent(account, entry, sentCopy))
        } finally {
            sendingNow -= item.id
        }
    }

    /**
     * After a successful SMTP send: removes the message from the outbox and, in the same
     * transaction, records what must still reach the server (the Sent copy saved in [copy],
     * \Answered or $Forwarded on the original, the deletion of the server draft), so neither a
     * failure nor the death of the process can lose it. Returns whether the Sent copy was queued.
     */
    private suspend fun recordSent(account: Account, entry: OutboxEntry, copy: SentCopy?): Boolean {
        try {
            db {
                store.tx {
                    store.outbox.delete(entry.item.id)
                    if (copy != null) {
                        store.pendingOps.insert(
                            PendingOp(
                                accountId = account.id,
                                type = PendingOpType.APPEND_SENT,
                                folder = copy.folder.fullName,
                                payload = copy.file.absolutePath,
                                notBefore = clock(),
                            ),
                        )
                    }
                    recordSourceFlag(entry.item.draft)
                    recordServerDraftDeletion(account.id, entry)
                }
            }
            return copy != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Whatever else fails, a delivered message must leave the outbox.
            db { store.outbox.delete(entry.item.id) }
            copy?.let { withContext(io) { it.file.delete() } }
            return false
        }
    }

    /** A message saved for its copy in the account's Sent [folder]. */
    private class SentCopy(val folder: Folder, val file: File)

    /** Saves the message for its Sent copy; null without a Sent folder or if it cannot be written. */
    private suspend fun saveSentCopy(account: Account, message: ComposedMessage): SentCopy? {
        val sent = db { store.folders.findByRole(account.id, FolderRole.SENT) } ?: return null
        return withContext(io) {
            val file = File(sentCopiesDir, UUID.randomUUID().toString() + ".eml")
            try {
                sentCopiesDir.mkdirs()
                file.outputStream().buffered().use { message.mime.writeTo(it) }
                SentCopy(sent, file)
            } catch (_: Exception) {
                file.delete()
                null
            }
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
                bestEffort { db { deleteOrphanSentCopies() } }
                recovered = true
            }
        }
    }

    /**
     * Saved sent messages no operation refers to any more (their account was removed). Young
     * files are left alone: a send may be about to record its operation.
     */
    private fun deleteOrphanSentCopies() {
        val saved = sentCopiesDir.listFiles() ?: return
        val referenced = store.pendingOps.payloads(PendingOpType.APPEND_SENT).toSet()
        val cutoff = clock() - SENT_COPY_MIN_AGE
        saved.filter { it.absolutePath !in referenced && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    /** Where sent messages wait for their Sent copy ([PendingOpType.APPEND_SENT]). */
    private val sentCopiesDir: File get() = File(files.filesDir, SENT_COPIES_DIR)

    private fun isSentCopy(file: File): Boolean = file.parentFile?.absolutePath == sentCopiesDir.absolutePath

    /**
     * Bookkeeping after a successful send, after [recordSent]: contacts, compose files, and the
     * pending operations (the Sent copy among them) pushed right away. A failure or cancellation
     * here loses nothing: the operations wait for the next flush. Returns the Sent folder to
     * refresh, if the copy was stored there and the folder is being synchronised.
     */
    private suspend fun afterSend(entry: OutboxEntry, outcome: SendOutcome.Sent): Long? {
        val account = outcome.account
        val draft = entry.item.draft
        withContext(NonCancellable) {
            bestEffort { db { store.contacts.recordContacted(draft.to + draft.cc + draft.bcc, clock()) } }
        }
        var sent: Folder? = null
        val touched = HashSet<Long>()
        try {
            val folder = db { store.folders.findByRole(account.id, FolderRole.SENT) }
            sent = folder
            if (!outcome.sentCopyQueued && folder != null) {
                // Not saved for later (it could not be written): store the copy now, best effort.
                bestEffort {
                    clients.imap(account.id).append(folder.fullName, outcome.message.mime, true, false)
                    touched += folder.id
                    indexQuietly { store.headerIndex.markDirty(listOf(folder.id)) }
                }
            }
        } finally {
            // Only after the copy above: building it again reads the compose files.
            withContext(NonCancellable) {
                bestEffort {
                    db {
                        val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
                        files.deleteComposeFiles(draft.attachments.map { it.uri }, referenced)
                    }
                }
            }
        }
        bestEffort { touched += flushPendingOps(account.id).touchedFolders }
        return sent?.takeIf { it.id in touched && it.lastSyncedAt != null }?.id
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
        val failed = db { store.outbox.failStaleSending(clock() - STALE_SENDING, sendingNow.toSet(), MailErrorKind.SEND_UNCERTAIN.name) }
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

    // ------------------------------------------------------------------ header index

    /**
     * Makes the message of header index row [indexId] (a search result known only by its
     * headers) openable: it is downloaded as a detached row, which does not change the folder's
     * list. If the message is no longer at that place (moved, deleted, the mailbox renumbered),
     * the row is dropped and up to [MAX_COPY_ATTEMPTS] other indexed copies with the same
     * Message-ID are tried. Uses the main connection and no account lock.
     */
    suspend fun materializeIndexed(indexId: Long): HitOpenResult = withContext(io) { materializeIndexedOnIo(indexId) }

    private suspend fun materializeIndexedOnIo(indexId: Long): HitOpenResult {
        val first = db { store.headerIndex.get(indexId) } ?: return HitOpenResult.Gone
        materializeRow(first)?.let { return it }
        val messageId = first.messageIdHeader?.takeIf { it.isNotBlank() } ?: return HitOpenResult.Gone
        val copies = db { store.headerIndex.findByMessageId(first.accountId, messageId) }
            .filter { it.indexId != first.indexId }
            .take(MAX_COPY_ATTEMPTS)
        for (copy in copies) materializeRow(copy)?.let { return it }
        return HitOpenResult.Gone
    }

    /** Opens [row]'s message, downloading it if needed; null if it is not (or no longer) there. */
    private suspend fun materializeRow(row: IndexedMessage): HitOpenResult? {
        val folder = db { store.folders.get(row.folderId) } ?: return null
        val indexValidity = db { store.headerIndex.state(row.folderId) }?.uidValidity
        val cacheValidity = db { store.folders.syncState(row.folderId) }?.uidValidity ?: 0L
        // A cached row of another UIDVALIDITY than the index row is another message.
        val sameEpoch = cacheValidity == 0L || cacheValidity == indexValidity
        if (sameEpoch) db { store.messages.findByUid(row.folderId, row.uid) }?.let { return opened(it) }
        val fetched = clients.imap(folder.accountId).fetchByUids(folder.fullName, listOf(row.uid))
        if (cacheValidity != 0L && cacheValidity != fetched.uidValidity) {
            // The mailbox was renumbered: rebuild its cache (and index) first.
            syncFolder(folder.id)
        }
        val message = fetched.messages.firstOrNull { it.uid == row.uid }
        val same = message != null && !message.flags.deleted && indexValidity == fetched.uidValidity &&
            sameMessageId(row.messageIdHeader, message.messageId)
        if (message == null || !same) {
            db {
                store.headerIndex.deleteUids(row.folderId, listOf(row.uid))
                store.headerIndex.markDirty(listOf(row.folderId))
            }
            return null
        }
        if (!db { store.storeFetchedOutsideSync(folder, fetched.uidValidity, listOf(message), clock()) }) return null
        indexQuietly { store.headerIndex.updateFlags(row.folderId, mapOf(row.uid to message.flags)) }
        val ref = db { store.messages.findByUid(row.folderId, row.uid) } ?: return null
        return opened(ref)
    }

    /** A cached message ready to open, its detached rows marked as just used; or a pending move or delete. */
    private suspend fun opened(ref: MessageRef): HitOpenResult {
        if (ref.hidden) return HitOpenResult.PendingChange
        db { store.messages.touchDetachedThread(ref.id, clock()) }
        return HitOpenResult.Opened(ref.id)
    }

    private fun sameMessageId(indexed: String?, server: String?): Boolean {
        val a = indexed?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val b = server?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)
    }

    /** Keeps the header index in step; never fails the mail work it follows (the indexer repairs the index). */
    private suspend fun indexQuietly(block: () -> Unit) {
        try {
            db(block)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The index is a search aid: the next indexer run puts it right.
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun <T> withAccountLock(accountId: Long, block: suspend () -> T): T =
        accountLocks.getOrPut(accountId) { Mutex() }.withLock {
            try {
                block()
            } finally {
                // An account removed meanwhile may have got a new connection from this work.
                clients.releaseIfRemoved(accountId)
            }
        }

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

    /** Reads a local file: its failures are not network problems, so retrying would not help. */
    private suspend fun <T> readLocal(block: () -> T): T =
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MailException(MailErrorKind.UNKNOWN, e.message, e)
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
        private const val MAX_INLINE_IMAGES = 20
        private const val MAX_INLINE_IMAGE = 2L * 1024 * 1024
        private const val SNIPPET_FALLBACK = 160
        private const val FLAG_CHUNK = 500
        private const val MAX_UID = 4_294_967_295L
        private const val MAX_OP_ATTEMPTS = 50
        private const val MAX_OP_AGE = 7 * 24 * 60 * 60_000L
        private const val MAX_UPLOAD_ATTEMPTS = 10
        private const val SENT_COPIES_DIR = "sent"
        private const val SENT_COPY_MIN_AGE = 60 * 60_000L
        private const val IN_FLIGHT_WAIT = 15_000L
        private const val COMPLETED_MOVE_TTL = 5 * 60_000L
        private const val STALE_SENDING = 10 * 60_000L
        private const val FIRST_RETRY_DELAY = 30_000L
        private const val MAX_RETRY_DELAY = 15 * 60_000L

        /** Other indexed copies (same Message-ID) tried when a search result has moved. */
        private const val MAX_COPY_ATTEMPTS = 2

        /** Failures that concern the whole account (its login), never a single operation. */
        private val ACCOUNT_KINDS = setOf(MailErrorKind.AUTHENTICATION, MailErrorKind.CERTIFICATE)

        /** Operations that store a whole message: retried apart from the rest (see [flushPendingOps]). */
        private val UPLOADS = setOf(PendingOpType.APPEND_SENT, PendingOpType.APPEND_DRAFT)

        /** Operations that take messages out of their folder. */
        private val MOVES_OUT = setOf(PendingOpType.MOVE, PendingOpType.MOVE_BY_MESSAGE_ID)

        /** Marker: the draft had no previous server copy. */
        private val NO_COPY = ServerCopy("", null, null)
    }
}
