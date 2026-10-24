package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.HeaderIndexDao
import io.github.pportilla.upmmail.data.db.IndexFolderState
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.MessageDao
import io.github.pportilla.upmmail.mail.ImapTransport
import io.github.pportilla.upmmail.mail.ImapOptions
import io.github.pportilla.upmmail.mail.RemoteFolderStatus
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** When a run of the [HeaderIndexer] compares a folder with the server ("diff"). */
enum class DiffPolicy {
    /** Whenever needed. */
    ALL,

    /** Only folders of up to [HeaderIndexer.SMALL_FOLDER] messages; the uid list of bigger ones is too long. */
    SMALL_ONLY,

    /** Never: only new mail is indexed. */
    NONE,
}

/**
 * What one run of the [HeaderIndexer] may do: until when ([deadline]), how many headers
 * ([maxMessages], fetched [chunkSize] at a time with a [pause] between chunks), whether it fills
 * in older mail ([backfill]) or only indexes new mail, which folders it compares with the server
 * ([diffs]), whether it uses a connection of its own instead of the account's main one
 * ([dedicatedConnection]), whether it only checks folders our own changes added mail to
 * ([onlyDirty]) and whether it may go on when the device is on a metered network
 * ([allowMetered]; see [HeaderIndexer.meteredNetwork]).
 */
data class IndexBudget(
    val deadline: Long,
    val maxMessages: Int,
    val chunkSize: Int,
    val backfill: Boolean,
    val diffs: DiffPolicy,
    val dedicatedConnection: Boolean,
    val onlyDirty: Boolean,
    val pause: Long,
    val allowMetered: Boolean = true,
) {
    companion object {
        /**
         * A background job on an unmetered network: the bulk of the work, a few minutes at a
         * time. JobScheduler stops it when the network becomes metered.
         */
        fun bulk(now: Long, charging: Boolean) = IndexBudget(
            deadline = now + if (charging) 8 * 60_000L else 4 * 60_000L,
            maxMessages = 60_000,
            chunkSize = 250,
            backfill = true,
            diffs = DiffPolicy.ALL,
            dedicatedConnection = true,
            onlyDirty = false,
            pause = 100,
        )

        /**
         * "Index now": everything, for up to ten minutes. Unless the user agreed to use mobile
         * data ([allowMetered]), it stops when the device moves to a metered network.
         */
        fun manual(now: Long, allowMetered: Boolean) = IndexBudget(
            deadline = now + 10 * 60_000L,
            maxMessages = 100_000,
            chunkSize = 250,
            backfill = true,
            diffs = DiffPolicy.ALL,
            dedicatedConnection = true,
            onlyDirty = false,
            pause = 100,
            allowMetered = allowMetered,
        )

        /** A periodic check on any network: new mail, and comparisons of small folders only. */
        fun light(now: Long) = IndexBudget(
            deadline = now + 60_000L,
            maxMessages = 500,
            chunkSize = 100,
            backfill = false,
            diffs = DiffPolicy.SMALL_ONLY,
            dedicatedConnection = false,
            onlyDirty = false,
            pause = 0,
        )

        /** Shortly after our own move or upload added mail to a folder. */
        fun dirty(now: Long) = IndexBudget(
            deadline = now + 20_000L,
            maxMessages = 200,
            chunkSize = 100,
            backfill = false,
            diffs = DiffPolicy.NONE,
            dedicatedConnection = false,
            onlyDirty = true,
            pause = 0,
        )
    }
}

/**
 * Outcome of [HeaderIndexer.run]: headers [fetched] and index rows [removed]; [moreWork] when
 * some indexable folder is still incomplete, dirty or has headers left to fetch (folders the
 * server refused in this run aside: they are tried again by later runs); [error] when an
 * account (network, login, certificate) or the whole run (storage) had to stop; [skipped] when
 * the run did not start because another one was running (then nothing else is known).
 */
data class IndexRunResult(
    val fetched: Int,
    val removed: Int,
    val moreWork: Boolean,
    val error: MailException?,
    val skipped: Boolean = false,
)

/**
 * Builds and maintains the header index (see [HeaderIndexDao]) of every account: it finds the
 * messages each indexable folder holds on the server, fetches the headers of those not indexed
 * yet (globally newest first) and removes rows of messages that are gone.
 *
 * Per folder, a check compares the server's STATUS with the folder's state row: uids from the
 * stored UIDNEXT on are new mail; a message count lower than expected means expunges. A full
 * comparison ("diff", `UID SEARCH UNDELETED`) runs when the folder lost messages or was last
 * compared a week ago, and in runs that fill in older mail when it is not complete yet. Only one
 * run works at a time; it pauses while a server search runs on the same account, so the search
 * gets the server's attention. Folders whose attributes are unknown are listed first (see
 * [io.github.pportilla.upmmail.data.db.FolderDao.hasUnlistedFolders]).
 */
class HeaderIndexer internal constructor(
    private val store: MailStore,
    private val clients: MailClientProvider,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val options: ImapOptions,
    private val searchActive: StateFlow<Set<Long>>,
    /** Lists the account's folders when they are not known (see [io.github.pportilla.upmmail.data.db.FolderDao.hasUnlistedFolders]). */
    private val ensureFolders: suspend (Long) -> Unit = {},
    private val onValidityChanged: suspend (Long) -> Unit,
) {
    private val runMutex = Mutex()
    private val runningAccounts = MutableStateFlow<Set<Long>>(emptySet())

    /** Accounts being indexed right now. */
    val running: StateFlow<Set<Long>> = runningAccounts.asStateFlow()

    /** True when the device is short of storage: a run then stops (set by the scheduler). */
    @Volatile
    var storageLow: () -> Boolean = { false }

    /**
     * True on a network the user pays by use: a run whose budget does not
     * [allow it][IndexBudget.allowMetered] then stops (set by the scheduler).
     */
    @Volatile
    var meteredNetwork: () -> Boolean = { false }

    /**
     * Indexes the accounts [accountIds] (every account if null, in account order) within
     * [budget]. Runs with a dedicated connection (bulk, manual) wait for a run in progress; the
     * others are skipped then. Never throws but for cancellation: problems are in the result.
     */
    suspend fun run(budget: IndexBudget, accountIds: List<Long>? = null): IndexRunResult = withContext(io) {
        store.safety.requireAttachments()
        store.safety.requireNetwork()
        if (budget.dedicatedConnection) {
            runMutex.withLock { runLocked(budget, accountIds) }
        } else {
            if (!runMutex.tryLock()) return@withContext IndexRunResult(0, 0, moreWork = false, error = null, skipped = true)
            try {
                runLocked(budget, accountIds)
            } finally {
                runMutex.unlock()
            }
        }
    }

    /**
     * Runs a [IndexBudget.dirty] run shortly after our own changes add mail to an indexed folder
     * (see [HeaderIndexDao.markDirty]), while [isOnline]. Never returns; cancel it to stop.
     */
    suspend fun watchDirty(isOnline: () -> Boolean = { true }) {
        while (true) {
            val pause = try {
                store.headerIndex.observeDirty().first { it.isNotEmpty() }
                delay(DIRTY_DELAY)
                if (!isOnline()) {
                    DIRTY_RETRY
                } else {
                    val result = run(IndexBudget.dirty(clock()))
                    when {
                        result.error != null -> DIRTY_RETRY
                        // A bulk or manual run is busy: it checks the dirty folders as well.
                        result.skipped -> DIRTY_DELAY
                        else -> 0L
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The database failed: try again later rather than spin.
                DIRTY_RETRY
            }
            if (pause > 0) delay(pause)
        }
    }

    // ------------------------------------------------------------------ one run

    /** Stops the whole run (storage low, database failure). */
    private class StopRun(val error: MailException) : Exception(error)

    /** Totals of one run, shared by its accounts. */
    private class Totals {
        var fetched = 0
        var removed = 0
        var moreWork = false
        var error: MailException? = null

        /**
         * Folders the server refused or no longer has: they do not count as work left, so one
         * folder refused for good does not keep the bulk job coming back every few minutes. Later
         * runs (at the slower pace of a complete index) try them again.
         */
        val refused = HashSet<Long>()
    }

    private suspend fun runLocked(budget: IndexBudget, accountIds: List<Long>?): IndexRunResult {
        val totals = Totals()
        try {
            val accounts = store.accounts.getAll().map { it.id }.filter { accountIds == null || it in accountIds }
            for ((index, accountId) in accounts.withIndex()) {
                val now = clock()
                val share = (budget.deadline - now) / (accounts.size - index)
                val deadline = now + maxOf(MIN_ACCOUNT_TIME, share)
                try {
                    indexAccount(accountId, budget, deadline, totals)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StopRun) {
                    throw e
                } catch (e: MailException) {
                    currentCoroutineContext().ensureActive()
                    // The account was removed meanwhile: nothing to report.
                    if (e.kind != MailErrorKind.NOT_FOUND) {
                        totals.error = totals.error ?: e
                        totals.moreWork = true
                    }
                }
            }
            if (budget.dedicatedConnection && totals.fetched > 0) store.headerIndex.mergeSegments()
            for (accountId in accounts) {
                if (!totals.moreWork && hasWorkLeft(accountId, totals.refused)) totals.moreWork = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StopRun) {
            totals.error = e.error
            totals.moreWork = true
        } catch (e: Exception) {
            // A database failure (a full disk among them).
            totals.error = MailException(MailErrorKind.UNKNOWN, e.message, e)
            totals.moreWork = true
        }
        return IndexRunResult(totals.fetched, totals.removed, totals.moreWork, totals.error)
    }

    /**
     * Whether an indexable folder of the account is not complete or dirty. Folders whose older
     * mail is held back only by [HeaderIndexDao.ACCOUNT_CAP] do not count: no run could do more;
     * nor do the folders the server [refused] in this run.
     */
    private fun hasWorkLeft(accountId: Long, refused: Set<Long>): Boolean {
        if (store.accounts.get(accountId) == null) return false
        val states = store.headerIndex.statesOf(accountId).associateBy { it.folderId }
        val full = store.headerIndex.accountRowCount(accountId) >= HeaderIndexDao.ACCOUNT_CAP
        return store.folders.indexableFolders(accountId).any { folder ->
            val state = states[folder.id]
            folder.id !in refused && (state == null || state.dirty || (!state.complete && !full))
        }
    }

    private suspend fun indexAccount(accountId: Long, budget: IndexBudget, deadline: Long, totals: Totals) {
        if (budget.onlyDirty && store.headerIndex.dirtyFolders(accountId).isEmpty()) return
        // Which folders are indexed depends on their attributes: unknown ones are listed first.
        if (!budget.onlyDirty) ensureFolders(accountId)
        val main = clients.imap(accountId)
        val client = if (budget.dedicatedConnection) main.derive(options) else main
        runningAccounts.update { it + accountId }
        try {
            coroutineScope {
                val invalidated = AtomicBoolean(false)
                // Started once the watcher below listens, so no invalidation is missed.
                val work = async(start = CoroutineStart.LAZY) { AccountRun(accountId, client, budget, deadline, totals).execute() }
                // The login changed or the account was removed: stop at once, on its socket too.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    clients.invalidated.first { it == accountId }
                    invalidated.set(true)
                    work.cancel()
                    client.abort()
                }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    if (!invalidated.get()) throw e
                    currentCoroutineContext().ensureActive()
                    totals.moreWork = true
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            runningAccounts.update { it - accountId }
            if (budget.dedicatedConnection) withContext(NonCancellable) { client.close() }
        }
    }

    /** What the check of a folder left to fetch. */
    private class FolderWork(
        val folder: Folder,
        /** Position in [SEARCH_ROLE_ORDER] order: breaks ties between folders. */
        val priority: Int,
        /** The state row before this run: its UIDVALIDITY and UIDNEXT. */
        val state: IndexFolderState,
        val status: RemoteFolderStatus,
        /** Uids to fetch, newest first. */
        val queue: ArrayDeque<Long>,
        /** A diff ran: the folder is complete once [queue] is done, if [completeWhenDone]. */
        val diffDone: Boolean,
        val completeWhenDone: Boolean,
        /** UIDNEXT and the message count are stored once the new mail in [queue] is indexed. */
        val advanceWhenDone: Boolean,
        /** The highest indexed uid before this run. */
        val oldMaxUid: Long,
        /** The oldest date in the index before this run. */
        val oldestIndexed: Long?,
    ) {
        var dropped = false

        /** Older mail was taken out of [queue] because the account's index is full. */
        var trimmed = false
        var oldestFetched: Long? = null

        /**
         * How recent the next chunk is: new mail of an indexed folder first; otherwise the oldest
         * date the folder's index has reached, or first place for a folder with no date yet.
         */
        val frontier: Long
            get() {
                val next = queue.firstOrNull() ?: return Long.MIN_VALUE
                if (oldMaxUid > 0 && next > oldMaxUid) return Long.MAX_VALUE
                return listOfNotNull(oldestFetched, oldestIndexed).minOrNull() ?: Long.MAX_VALUE
            }
    }

    private inner class AccountRun(
        private val accountId: Long,
        private val imap: ImapTransport,
        private val budget: IndexBudget,
        private val deadline: Long,
        private val totals: Totals,
    ) {
        suspend fun execute() {
            if (budget.dedicatedConnection) evictDetachedOfUnsyncedFolders()
            val dirty = if (budget.onlyDirty) store.headerIndex.dirtyFolders(accountId).toSet() else null
            val folders = store.folders.indexableFolders(accountId)
                .filter { dirty == null || it.id in dirty }
                .sortedBy { SEARCH_ROLE_ORDER.indexOf(it.role) }
            val works = ArrayList<FolderWork>()
            for ((priority, folder) in folders.withIndex()) {
                if (clock() >= deadline || meteredStop()) {
                    totals.moreWork = true
                    break
                }
                val work = guarded(folder) { check(folder, priority) } ?: continue
                works += work
            }
            fetch(works)
            finish(works)
        }

        /**
         * Rows of folders never synced exist only for search results and opened messages; no
         * sync evicts them, so this does, least recently used first.
         */
        private fun evictDetachedOfUnsyncedFolders() {
            val spareSince = clock() - DETACHED_SPARE
            for (folder in store.folders.getByAccount(accountId)) {
                val state = store.folders.syncState(folder.id) ?: continue
                if (state.uidValidity != 0L) continue
                store.tx {
                    val protectedUids = store.pendingOps.protectedUids(accountId, folder.fullName)
                    store.messages.evictExtras(folder.id, DETACHED_KEEP, spareSince, protectedUids)
                }
            }
        }

        /** Compares [folder] with the server and returns what is left to fetch, or null to skip it. */
        private suspend fun check(folder: Folder, priority: Int): FolderWork? {
            val status = imap.status(folder.fullName)
            val cachedValidity = store.folders.syncState(folder.id)?.uidValidity ?: return null
            if (cachedValidity != 0L && cachedValidity != status.uidValidity) onValidityChanged(folder.id)
            val state = store.headerIndex.ensureState(folder.id, status.uidValidity, status.uidNext, status.messageCount)
                ?: return null
            val now = clock()
            val cap = HeaderIndexDao.capOf(folder.role)
            var newMissing: List<Long> = emptyList()
            var newFound = 0
            if (status.uidNext > state.uidNext) {
                val listed = imap.searchAllUids(folder.fullName, state.uidNext)
                // Renumbered between the two commands: the next run sees it in STATUS.
                if (listed.uidValidity != state.uidValidity) return null
                val indexed = store.headerIndex.indexedUids(folder.id, state.uidNext)
                newMissing = listed.uids.filter { indexed.binarySearch(it) < 0 }.sortedDescending()
                newFound = listed.uids.count { it < status.uidNext }
            }
            // Messages are only added above UIDNEXT, so a lower count than expected means expunges.
            val expunged = state.serverCount + newFound - status.messageCount
            val checkedAt = state.checkedAt
            // A run that does not fill in older mail never completes a folder: it only compares
            // one that lost messages or is due for its weekly check, never one still incomplete.
            val needsDiff = expunged > 0 ||
                (if (checkedAt == null) state.complete else now - checkedAt >= DIFF_EVERY) ||
                (!state.complete && budget.backfill)
            val allowDiff = when (budget.diffs) {
                DiffPolicy.ALL -> true
                DiffPolicy.SMALL_ONLY -> status.messageCount <= SMALL_FOLDER
                DiffPolicy.NONE -> false
            }
            val oldMaxUid = store.headerIndex.maxUid(folder.id) ?: 0L
            val oldestIndexed = store.headerIndex.oldestDate(folder.id)
            if (needsDiff && allowDiff) {
                val undeleted = imap.searchUndeletedUids(folder.fullName)
                if (undeleted.uidValidity != state.uidValidity) return null
                val present = undeleted.uids
                val diff = store.headerIndex.applyDiff(folder.id, present, below = status.uidNext, keepNewest = cap)
                totals.removed += diff.removed
                val newest = if (present.size > cap) present.copyOfRange(present.size - cap, present.size) else present
                val missing = newest.filter { diff.remaining.binarySearch(it) < 0 }.sortedDescending()
                val queued = if (budget.backfill) missing else missing.filter { it >= state.uidNext }
                // Everything below UIDNEXT is accounted for by the diff; the rest is in the queue.
                store.headerIndex.updateState(
                    folder.id,
                    uidNext = status.uidNext,
                    serverCount = status.messageCount,
                    complete = missing.isEmpty(),
                    capped = present.size > cap,
                    dirty = false,
                    checkedAt = now,
                    updatedAt = now,
                )
                return FolderWork(
                    folder, priority, state, status, ArrayDeque(queued),
                    diffDone = true,
                    completeWhenDone = queued.size == missing.size,
                    advanceWhenDone = false,
                    oldMaxUid = oldMaxUid,
                    oldestIndexed = oldestIndexed,
                )
            }
            // Not compared now: a later bulk run finds what the expunges removed.
            if (expunged > 0) store.headerIndex.updateState(folder.id, complete = false)
            // UIDNEXT moves on only once the new mail below it is indexed: a run cut short finds
            // it again next time, and a search does not take the folder for up to date meanwhile.
            if (newMissing.isEmpty()) {
                store.headerIndex.updateState(
                    folder.id,
                    uidNext = status.uidNext,
                    serverCount = status.messageCount,
                    dirty = false,
                    updatedAt = now,
                )
            }
            return FolderWork(
                folder, priority, state, status, ArrayDeque(newMissing),
                diffDone = false,
                completeWhenDone = false,
                advanceWhenDone = newMissing.isNotEmpty(),
                oldMaxUid = oldMaxUid,
                oldestIndexed = oldestIndexed,
            )
        }

        /** Fetches the queued headers, always from the folder whose next chunk is newest. */
        private suspend fun fetch(works: List<FolderWork>) {
            while (true) {
                if (works.none { !it.dropped && it.queue.isNotEmpty() }) return
                if (accountId in searchActive.value) {
                    // A server search has the account's attention. A long one is not waited for
                    // to the end: then one chunk goes in every few minutes.
                    withTimeoutOrNull(SEARCH_PAUSE_MAX) { searchActive.first { accountId !in it } }
                }
                if (clock() >= deadline || totals.fetched >= budget.maxMessages || meteredStop()) {
                    totals.moreWork = true
                    return
                }
                if (store.headerIndex.accountRowCount(accountId) >= HeaderIndexDao.ACCOUNT_CAP) {
                    // The account holds all the index keeps of it: only new mail is still indexed.
                    for (work in works) {
                        if (work.queue.removeAll { it < work.state.uidNext }) work.trimmed = true
                    }
                }
                currentCoroutineContext().ensureActive()
                if (store.accounts.get(accountId) == null) return
                if (storageLow()) throw StopRun(MailException(MailErrorKind.UNKNOWN, "Not enough free storage to index mail"))
                val work = works.filter { !it.dropped && it.queue.isNotEmpty() }
                    .maxWithOrNull(compareBy<FolderWork> { it.frontier }.thenByDescending { it.priority })
                    ?: return
                guarded(work.folder, onSkip = { work.dropped = true }) { fetchChunk(work) }
                if (budget.pause > 0) delay(budget.pause)
            }
        }

        private suspend fun fetchChunk(work: FolderWork) {
            val size = minOf(budget.chunkSize, budget.maxMessages - totals.fetched).coerceAtLeast(1)
            val chunk = work.queue.take(size)
            val batch = imap.fetchIndexHeaders(work.folder.fullName, chunk)
            repeat(chunk.size) { work.queue.removeFirst() }
            // Renumbered meanwhile: only a fresh STATUS may reset the folder, in the next run.
            if (batch.uidValidity != work.state.uidValidity) {
                work.dropped = true
                return
            }
            if (store.headerIndex.insertHeaders(work.folder.id, work.state.uidValidity, batch.headers) < 0) {
                // The folder is gone or was reset meanwhile.
                work.dropped = true
                return
            }
            if (batch.missingUids.isNotEmpty()) totals.removed += store.headerIndex.deleteUids(work.folder.id, batch.missingUids)
            totals.fetched += batch.headers.size
            val now = clock()
            val oldest = batch.headers.minOfOrNull { MessageDao.effectiveDate(it.sentDate, it.internalDate, now) }
            if (oldest != null) work.oldestFetched = minOf(oldest, work.oldestFetched ?: Long.MAX_VALUE)
        }

        private fun finish(works: List<FolderWork>) {
            val now = clock()
            for (work in works) {
                if (work.dropped) continue
                if (work.queue.isNotEmpty()) {
                    totals.moreWork = true
                    continue
                }
                if (work.advanceWhenDone) {
                    store.headerIndex.updateState(
                        work.folder.id,
                        uidNext = work.status.uidNext,
                        serverCount = work.status.messageCount,
                        dirty = false,
                        updatedAt = now,
                    )
                }
                if (work.diffDone && work.completeWhenDone && !work.trimmed) {
                    store.headerIndex.updateState(work.folder.id, complete = true)
                }
            }
        }

        /**
         * Runs [block] for [folder]. A folder that is gone or that the server refuses is skipped
         * (and not left dirty, so it does not trigger run after run, nor counted as work left);
         * a network, login or certificate failure stops the account and is reported.
         */
        private suspend fun <T> guarded(folder: Folder, onSkip: () -> Unit = {}, block: suspend () -> T): T? =
            try {
                block()
            } catch (e: MailException) {
                currentCoroutineContext().ensureActive()
                if (e.kind != MailErrorKind.NOT_FOUND && e.kind != MailErrorKind.SERVER) throw e
                onSkip()
                totals.refused += folder.id
                store.headerIndex.updateState(folder.id, dirty = false)
                null
            }

        /** The device moved to a metered network and the budget does not allow it. */
        private fun meteredStop(): Boolean = !budget.allowMetered && meteredNetwork()
    }

    companion object {
        /** A complete folder is compared with the server again after a week. */
        const val DIFF_EVERY = 7 * 24 * 60 * 60_000L

        /** Largest folder a [DiffPolicy.SMALL_ONLY] run compares. */
        const val SMALL_FOLDER = 2_000

        /** Longest a run waits for a server search of the same account to end. */
        const val SEARCH_PAUSE_MAX = 2 * 60_000L

        /** Wait after our own changes made a folder dirty, so that a series of moves is checked once. */
        const val DIRTY_DELAY = 30_000L

        /** Wait before checking dirty folders again after a failure (or offline). */
        const val DIRTY_RETRY = 5 * 60_000L

        /** Detached rows kept per folder, least recently used evicted first. */
        const val DETACHED_KEEP = 200

        /** A detached row used this recently (an open message, a search result) is never evicted. */
        const val DETACHED_SPARE = 30 * 60_000L

        /** Every account gets at least this much of a run's time. */
        private const val MIN_ACCOUNT_TIME = 20_000L
    }
}
