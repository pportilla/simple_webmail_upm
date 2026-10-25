package io.github.pportilla.upmmail.data.repository

import kotlinx.coroutines.flow.flowOn
import io.github.pportilla.upmmail.data.db.LocalFlag
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.MessageRef
import io.github.pportilla.upmmail.data.db.PendingOp
import io.github.pportilla.upmmail.data.db.PendingOpType
import io.github.pportilla.upmmail.mail.MessageFlag
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.HitOpenResult
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.mergeHits
import io.github.pportilla.upmmail.sync.FlushScheduler
import io.github.pportilla.upmmail.sync.IndexBudget
import io.github.pportilla.upmmail.sync.SyncEngine
import io.github.pportilla.upmmail.sync.mailResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [MailRepository] on top of the SQLite cache and the [SyncEngine].
 *
 * Actions are optimistic: the cache changes at once and a pending operation is recorded for the
 * server. Flag changes are pushed about a second later; moves and deletes hide the messages and
 * wait for the undo window ([FlushScheduler.UNDO_WINDOW]) first. With conversation view on,
 * list actions apply to the whole conversation in that folder (see [expand]).
 *
 * The Drafts folder also lists the drafts that are only on this device ([LocalDraftRows]).
 */
class MailRepositoryImpl(
    private val store: MailStore,
    private val engine: SyncEngine,
    private val flushScheduler: FlushScheduler,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** Messages became read locally (to dismiss their notifications). */
    private val onMessagesRead: (Collection<Long>) -> Unit = {},
    /** Discards a local draft deleted from the Drafts folder list (ComposeRepositoryImpl.discardLocalDraft). */
    private val discardLocalDraft: suspend (draftId: Long) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How often an open conversation marks its rows downloaded from search results as used. */
    private val detachedKeepAlive: Long = DETACHED_KEEP_ALIVE,
) : MailRepository {

    /** The "Index now" run, while it lasts. */
    private var indexJob: Job? = null

    /** Whether [indexJob] may go on over mobile data. */
    private var indexJobMetered = false

    override fun observeFolders(accountId: Long): Flow<List<Folder>> = combine(store.folders.observeByAccount(accountId), store.safety.state) { folders, safety ->
        if (!safety.mailboxAccessAllowed) emptyList() else folders
    }

    override suspend fun getFolder(folderId: Long): Folder? = db { store.safety.requireAccountManagement(); store.folders.get(folderId) }

    override suspend fun findFolderByRole(accountId: Long, role: FolderRole): Folder? =
        db { store.safety.requireAccountManagement(); store.folders.findByRole(accountId, role) }

    override fun observeMessages(folderId: Long, limit: Int, conversations: Boolean): Flow<List<MessageSummary>> = flow {
        val cached = combine(store.messages.observeList(folderId, limit, conversations), store.safety.state) { rows, _ -> visible(rows) }.flowOn(io)
        val folder = db { store.folders.get(folderId) }
        if (folder?.role != FolderRole.DRAFTS) {
            emitAll(cached)
            return@flow
        }
        val from = db { store.accounts.get(folder.accountId) }?.let { EmailAddress(it.email, it.displayName.takeIf(String::isNotBlank)) }
        emitAll(
            combine(cached, store.drafts.observeUnsynced(folder)) { rows, local ->
                if (store.safety.state.value.mailboxAccessAllowed) LocalDraftRows.merge(rows, local, folder, from, limit) else rows
            },
        )
    }

    override fun observeMessage(messageId: Long): Flow<MessageDetail?> = combine(store.messages.observeDetail(messageId), store.safety.state) { detail, _ ->
        detail?.let(store.safety::projectMessage)
    }.flowOn(io)

    override fun observeThread(messageId: Long): Flow<List<MessageDetail>> = channelFlow {
        // Rows downloaded from search results are evicted some time after their last use: keep
        // marking the thread's as used while it is on screen.
        launch(io) {
            while (true) {
                try {
                    store.messages.touchDetachedThread(messageId, clock())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort: at worst the rows are downloaded again when opened again.
                }
                delay(detachedKeepAlive)
            }
        }
        combine(store.messages.observeThread(messageId), store.safety.state) { rows, _ -> rows.mapNotNull(store.safety::projectMessage) }.collect { send(it) }
    }

    override val syncingFolders: StateFlow<Set<Long>> get() = engine.syncingFolders

    override suspend fun refreshFolder(folderId: Long): Result<Unit> = mailResult {
        engine.syncFolder(folderId)
        Unit
    }

    override suspend fun refreshAccount(accountId: Long): Result<Unit> = mailResult {
        engine.syncAccount(accountId)
        Unit
    }

    override suspend fun loadOlder(folderId: Long, count: Int): Result<Boolean> = mailResult {
        engine.loadOlder(folderId, count)
    }

    override suspend fun ensureBodyLoaded(messageId: Long): Result<Unit> = mailResult {
        engine.loadBody(messageId, inlineImages = store.safety.state.value.mailboxAccessAllowed)
    }

    // ------------------------------------------------------------------ flags

    override suspend fun setRead(messageIds: List<Long>, read: Boolean) {
        store.safety.requireAccountManagement()
        // Reading a conversation reads all of it; "unread" only needs its newest message.
        val ids = if (read) expand(messageIds) else messageIds
        setFlag(ids, LocalFlag.READ, MessageFlag.SEEN, read)
        if (read) notifyRead(ids)
    }

    override suspend fun setStarred(messageIds: List<Long>, starred: Boolean) {
        store.safety.requireAccountManagement()
        // A conversation shows a star if any message has one, so unstarring clears them all.
        val ids = if (starred) messageIds else expand(messageIds)
        setFlag(ids, LocalFlag.STARRED, MessageFlag.FLAGGED, starred)
    }

    private suspend fun setFlag(ids: List<Long>, local: LocalFlag, server: MessageFlag, value: Boolean) {
        if (ids.isEmpty()) return
        val changed = db {
            store.tx {
                val changed = store.messages.setFlag(visibleIds(ids), local, value)
                recordFlagOps(changed, server, value)
                if (local == LocalFlag.READ) {
                    changed.groupBy { it.folderId }.forEach { (folderId, rows) ->
                        store.folders.adjustCounts(folderId, if (value) -rows.size else rows.size, 0)
                    }
                }
                changed
            }
        }
        changed.map { it.accountId }.distinct().forEach { flushScheduler.scheduleSoon(it) }
    }

    private fun recordFlagOps(rows: List<MessageRef>, flag: MessageFlag, value: Boolean) {
        val now = clock()
        for ((folderId, inFolder) in rows.groupBy { it.folderId }) {
            val folder = store.folders.get(folderId) ?: continue
            store.pendingOps.insert(
                PendingOp(
                    accountId = folder.accountId,
                    type = PendingOpType.FLAG,
                    folder = folder.fullName,
                    uids = inFolder.map { it.uid },
                    messageIds = inFolder.map { it.id },
                    flag = flag.name,
                    value = value,
                    notBefore = now,
                ),
            )
        }
    }

    override suspend fun markFolderRead(folderId: Long) {
        store.safety.requireAccountManagement()
        val folder = getFolder(folderId) ?: return
        val ids = db {
            store.tx {
                val ids = store.messages.markFolderRead(folderId)
                store.folders.setUnreadCount(folderId, 0)
                store.pendingOps.insert(
                    PendingOp(
                        accountId = folder.accountId,
                        type = PendingOpType.MARK_FOLDER_READ,
                        folder = folder.fullName,
                        notBefore = clock(),
                    ),
                )
                ids
            }
        }
        flushScheduler.scheduleSoon(folder.accountId)
        notifyRead(ids)
    }

    // ------------------------------------------------------------------ moves

    override suspend fun archive(messageIds: List<Long>): Result<UndoHandle> = mailResult {
        store.safety.requireAccountManagement()
        val ids = expand(messageIds)
        val record = db {
            store.tx {
                val refs = store.messages.refs(visibleIds(ids)).filter { !it.hidden }
                val archives = refs.map { it.accountId }.distinct().associateWith { accountId ->
                    store.folders.findByRole(accountId, FolderRole.ARCHIVE)
                        ?: throw MailException(MailErrorKind.NOT_FOUND, "The account has no Archive folder")
                }
                recordMoves(refs, undoable = true) { archives[it] }
            }
        }
        scheduleMoves(record)
        record.handle()
    }

    override suspend fun delete(messageIds: List<Long>): Result<UndoHandle?> = mailResult {
        store.safety.requireAccountManagement()
        // Drafts only on this device are discarded (there is no server copy to move to Trash).
        val (listed, cached) = messageIds.partition { LocalDraftRows.draftId(it) != null }
        listed.forEach { discardLocalDraft(LocalDraftRows.draftId(it) ?: return@forEach) }
        val ids = expand(cached)
        val (moves, deletes) = db {
            store.tx {
                val refs = store.messages.refs(visibleIds(ids)).filter { !it.hidden }
                val folders = refs.map { it.folderId }.distinct().associateWith { store.folders.get(it) }
                val (inBin, elsewhere) = refs.partition { folders[it.folderId]?.role in BIN_ROLES }
                val trash = elsewhere.map { it.accountId }.distinct().associateWith { store.folders.findByRole(it, FolderRole.TRASH) }
                val (movable, noTrash) = elsewhere.partition { trash[it.accountId] != null }
                val moves = recordMoves(movable, undoable = true) { trash[it] }
                val deletes = recordPermanentDeletes(inBin + noTrash)
                moves to deletes
            }
        }
        scheduleMoves(moves)
        scheduleMoves(deletes)
        if (moves.count > 0) moves.handle() else null
    }

    override suspend fun moveTo(messageIds: List<Long>, folderId: Long): Result<UndoHandle> = mailResult {
        store.safety.requireAccountManagement()
        val ids = expand(messageIds)
        val record = db {
            store.tx {
                val target = store.folders.get(folderId) ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
                if (!target.selectable) throw MailException(MailErrorKind.NOT_FOUND, "Folder ${target.fullName} cannot hold messages")
                val refs = store.messages.refs(visibleIds(ids)).filter { !it.hidden && it.accountId == target.accountId }
                recordMoves(refs, undoable = true) { target }
            }
        }
        scheduleMoves(record)
        record.handle()
    }

    /** Pending moves recorded by one action. */
    private class MoveRecord(val opIds: List<Long>, val count: Int, val accounts: Set<Long>, val notBefore: Long) {
        fun handle(): UndoHandle = UndoHandle(opIds.firstOrNull() ?: NO_UNDO, count)
    }

    /** Hides [refs], adjusts counters and records one MOVE per source folder. Inside a transaction. */
    private fun recordMoves(refs: List<MessageRef>, undoable: Boolean, target: (accountId: Long) -> Folder?): MoveRecord {
        val notBefore = clock() + if (undoable) FlushScheduler.UNDO_WINDOW else 0
        val opIds = ArrayList<Long>()
        val accounts = HashSet<Long>()
        var count = 0
        for ((folderId, rows) in refs.groupBy { it.folderId }) {
            val source = store.folders.get(folderId) ?: continue
            val destination = target(source.accountId) ?: continue
            if (destination.id == source.id) continue
            val unread = rows.count { !it.isRead }
            store.messages.setHidden(rows.map { it.id }, true)
            store.folders.adjustCounts(source.id, -unread, -rows.size)
            store.folders.adjustCounts(destination.id, unread, rows.size)
            opIds += store.pendingOps.insert(
                PendingOp(
                    accountId = source.accountId,
                    type = PendingOpType.MOVE,
                    folder = source.fullName,
                    uids = rows.map { it.uid },
                    messageIds = rows.map { it.id },
                    target = destination.fullName,
                    notBefore = notBefore,
                ),
            )
            accounts += source.accountId
            count += rows.size
        }
        if (undoable && opIds.isNotEmpty()) store.pendingOps.setUndoGroup(opIds, opIds.first())
        return MoveRecord(opIds, count, accounts, notBefore)
    }

    /** Hides [refs] and records permanent deletions, due at once. Inside a transaction. */
    private fun recordPermanentDeletes(refs: List<MessageRef>): MoveRecord {
        val now = clock()
        val opIds = ArrayList<Long>()
        val accounts = HashSet<Long>()
        for ((folderId, rows) in refs.groupBy { it.folderId }) {
            val folder = store.folders.get(folderId) ?: continue
            store.messages.setHidden(rows.map { it.id }, true)
            store.folders.adjustCounts(folder.id, -rows.count { !it.isRead }, -rows.size)
            opIds += store.pendingOps.insert(
                PendingOp(
                    accountId = folder.accountId,
                    type = PendingOpType.DELETE,
                    folder = folder.fullName,
                    uids = rows.map { it.uid },
                    messageIds = rows.map { it.id },
                    notBefore = now,
                ),
            )
            accounts += folder.accountId
        }
        return MoveRecord(opIds, refs.size, accounts, now)
    }

    private fun scheduleMoves(record: MoveRecord) {
        record.accounts.forEach { flushScheduler.schedule(it, record.notBefore) }
    }

    override suspend fun undo(handle: UndoHandle) {
        store.safety.requireAccountManagement()
        if (handle.id <= 0 || handle.count == 0) return
        withContext(io) {
            for (round in 1..UNDO_ROUNDS) {
                val inFlight = store.tx {
                    val (cancelled, executing) = store.pendingOps.cancelGroup(handle.id)
                    cancelled.forEach(::restore)
                    executing
                }
                if (inFlight.isEmpty()) break
                // Already being executed: wait for the outcome. A failed attempt goes back to the
                // queue and is cancelled in the next round; a successful one is reversed below.
                engine.awaitInFlight(inFlight.map { it.id })
            }
            val completed = engine.takeCompletedMoves(handle.id)
            if (completed.isEmpty()) return@withContext
            // The server already has the move: move the messages back by Message-ID.
            val now = clock()
            store.tx {
                for (move in completed) {
                    if (move.messageIdHeaders.isEmpty()) continue
                    store.pendingOps.insert(
                        PendingOp(
                            accountId = move.accountId,
                            type = PendingOpType.MOVE_BY_MESSAGE_ID,
                            folder = move.toFolder,
                            target = move.fromFolder,
                            payload = move.messageIdHeaders.joinToString("\n"),
                            notBefore = now,
                        ),
                    )
                }
            }
            for (move in completed) {
                scope.launch(io) {
                    try {
                        engine.flushPendingOps(move.accountId)
                        store.folders.findByFullName(move.accountId, move.fromFolder)?.let { engine.syncFolder(it.id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Retried by the next sync of the account.
                    }
                }
            }
        }
    }

    /** Reverts the local effect of a cancelled move or delete. Inside a transaction. */
    private fun restore(op: PendingOp) {
        if (op.type != PendingOpType.MOVE && op.type != PendingOpType.DELETE) return
        val rows = store.messages.refs(op.messageIds).filter { it.hidden }
        if (rows.isEmpty()) return
        store.messages.setHidden(rows.map { it.id }, false)
        val unread = rows.count { !it.isRead }
        store.folders.findByFullName(op.accountId, op.folder)?.let { store.folders.adjustCounts(it.id, unread, rows.size) }
        op.target?.let { target ->
            store.folders.findByFullName(op.accountId, target)?.let { store.folders.adjustCounts(it.id, -unread, -rows.size) }
        }
    }

    override suspend fun emptyFolder(folderId: Long): Result<Unit> = mailResult {
        store.safety.requireAccountManagement()
        val folder = getFolder(folderId) ?: throw MailException(MailErrorKind.NOT_FOUND, "Folder $folderId")
        if (folder.role !in BIN_ROLES) throw MailException(MailErrorKind.UNKNOWN, "Only Trash and Junk can be emptied")
        engine.emptyFolder(folderId)
    }

    override suspend fun downloadAttachment(attachmentId: Long): Result<File> = mailResult {
        engine.downloadAttachment(attachmentId)
    }

    // ------------------------------------------------------------------ search

    override suspend fun searchLocal(query: String, filters: SearchFilters, limit: Int): List<MessageSummary> =
        db { visible(store.messages.search(query, filters, limit)) }

    override suspend fun searchLocalHits(query: String, filters: SearchFilters, limit: Int): List<SearchHit> = db {
        val cached = store.messages.searchHits(query, filters, limit).filter { store.messages.detail(it.message.id)?.let(store.safety::projectMessage) != null }
        val indexed = if (store.safety.state.value.mailboxAccessAllowed) store.headerIndex.search(query, filters, limit).filterNot { store.safety.isSenderBlocked(it.from?.address) }.map(SearchHit::Indexed) else emptyList()
        mergeHits(cached, indexed, limit = limit)
    }

    override fun searchServerProgress(request: ServerSearchRequest): Flow<ServerSearchProgress> = channelFlow {
        store.safety.requireAttachments()
        var latest: ServerSearchProgress? = null
        fun project(progress: ServerSearchProgress): ServerSearchProgress {
            val state = store.safety.state.value
            return if (!state.mailboxAccessAllowed) {
                progress.copy(hits = emptyList(), finished = true, error = MailException(MailErrorKind.SAFETY_BLOCKED))
            } else progress.copy(hits = progress.hits.filterNot { store.safety.isSenderBlocked(it.display.from?.address) })
        }
        val policyObserver = launch {
            store.safety.state.drop(1).collect { latest?.let { send(project(it)) } }
        }
        try {
            engine.serverSearch.search(request).collect { latest = it; send(project(it)) }
        } finally {
            // A search is finite. The policy observer must not keep its collector alive forever.
            policyObserver.cancel()
        }
    }

    override suspend fun openSearchHit(hit: SearchHit): Result<HitOpenResult> = mailResult {
        if (hit is SearchHit.Cached) db { store.messages.detail(hit.message.id)?.let(store.safety::requireMessageAccess) }
        else store.safety.requireAttachments()
        when (hit) {
            is SearchHit.Cached -> db {
                // The row may have gone since the search (evicted, expunged) or be being moved.
                val ref = store.messages.ref(hit.message.id)
                when {
                    ref == null -> HitOpenResult.Gone
                    ref.hidden -> HitOpenResult.PendingChange
                    else -> {
                        store.messages.touchDetachedThread(ref.id, clock())
                        HitOpenResult.Opened(ref.id)
                    }
                }
            }
            is SearchHit.Indexed -> engine.materializeIndexed(hit.entry.indexId)
        }
    }

    override fun observeIndexStatus(): Flow<List<IndexStatus>> =
        combine(store.headerIndex.observeStatus(), engine.headerIndexer.running) { statuses, running ->
            statuses.map { it.copy(running = it.accountId in running) }
        }

    override fun startIndexingNow(allowMetered: Boolean) {
        store.safety.requireAttachments()
        synchronized(this) {
            // A run already going does the work, unless this one may use mobile data and it may
            // not: then this one follows it (runs take turns).
            if (indexJob?.isActive == true && (indexJobMetered || !allowMetered)) return
            indexJobMetered = allowMetered
            indexJob = scope.launch(io) {
                // Never fails but for cancellation; problems show up in the index status.
                mailResult { engine.headerIndexer.run(IndexBudget.manual(clock(), allowMetered)) }
            }
        }
    }

    override suspend fun recentSearches(): List<String> = db { store.safety.requireAccountManagement(); store.recentSearches.list() }

    override suspend fun addRecentSearch(query: String) = db { store.safety.requireAccountManagement(); store.recentSearches.add(query) }

    override suspend fun clearRecentSearches() = db { store.recentSearches.clear() }

    // ------------------------------------------------------------------ notification actions

    /** "Mark as read" from a notification: applied at once and pushed without delay. */
    suspend fun markReadNow(messageId: Long) {
        store.safety.requireAccountManagement()
        val ref = db { store.messages.ref(messageId) } ?: return
        setFlag(listOf(messageId), LocalFlag.READ, MessageFlag.SEEN, true)
        notifyRead(listOf(messageId))
        flushScheduler.schedule(ref.accountId)
    }

    /**
     * "Archive" (or "Delete" when the account has no Archive folder) from a notification: no
     * undo window, pushed without delay. Returns false if the message no longer exists.
     */
    suspend fun archiveOrDeleteNow(messageId: Long, archive: Boolean): Boolean {
        store.safety.requireAccountManagement()
        val record = db {
            store.tx {
                val ref = store.messages.ref(messageId)?.takeIf { !it.hidden } ?: return@tx null
                val folder = store.folders.get(ref.folderId) ?: return@tx null
                val targetRole = if (archive) FolderRole.ARCHIVE else FolderRole.TRASH
                val target = store.folders.findByRole(ref.accountId, targetRole)
                when {
                    target != null && target.id != folder.id -> recordMoves(listOf(ref), undoable = false) { target }
                    archive -> null
                    else -> recordPermanentDeletes(listOf(ref))
                }
            }
        } ?: return false
        notifyRead(listOf(messageId))
        scheduleMoves(record)
        return true
    }

    // ------------------------------------------------------------------ helpers

    /**
     * With conversation view on, the mailbox shows one row per thread, so an action on a row
     * means the whole conversation in that folder.
     */
    private suspend fun expand(ids: List<Long>): List<Long> {
        if (ids.isEmpty() || !settings.settings.value.conversationView) return ids
        return db { store.messages.expandToThreads(ids) }
    }

    private suspend fun notifyRead(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        try {
            withContext(io) { onMessagesRead(ids) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Notification housekeeping is best effort.
        }
    }

    private fun visible(rows: List<MessageSummary>): List<MessageSummary> {
        val state = store.safety.state.value
        if (!state.mailboxAccessAllowed) return emptyList()
        return rows.filterNot { store.safety.isSenderBlocked(it.from?.address) }
    }
    private fun visibleIds(ids: List<Long>): List<Long> = ids.filter { id -> store.messages.detail(id)?.let(store.safety::projectMessage) != null }

    private suspend fun <T> db(block: () -> T): T = withContext(io) { block() }

    private companion object {
        const val NO_UNDO = -1L
        const val UNDO_ROUNDS = 3
        val BIN_ROLES = setOf(FolderRole.TRASH, FolderRole.JUNK)

        /** Well below the 30 minutes after which eviction may delete an unused downloaded search result. */
        const val DETACHED_KEEP_ALIVE = 10 * 60_000L
    }
}
