package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.HeaderIndexDao
import io.github.pportilla.upmmail.data.db.IndexFolderState
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.mail.FolderProbe
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.ImapOptions
import io.github.pportilla.upmmail.mail.IndexedHeader
import io.github.pportilla.upmmail.mail.MailErrors
import io.github.pportilla.upmmail.mail.ParsedBody
import io.github.pportilla.upmmail.mail.RemoteMessage
import io.github.pportilla.upmmail.mail.SearchRange
import io.github.pportilla.upmmail.mail.ServerQuery
import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.IndexedMessage
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchKey
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.model.StepOutcome
import io.github.pportilla.upmmail.model.StepProblem
import io.github.pportilla.upmmail.model.mergeHits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Progressive server search over every folder in scope (see [ServerSearchRequest]).
 *
 * The search box text is read by [ServerQuery.parse]: an AND over its words, each matching any
 * spelling variant in any searched field, and over its other criteria (senders, recipients,
 * subject, exclusions, dates, unread, starred). The HEADERS phase searches subject, from, to and
 * cc of every folder; a folder whose header index is complete is only asked about mail newer
 * than the index (or not at all when the index was checked a moment ago), unless the index cannot
 * answer the query ([HeaderIndexDao.answersFully]). Exclusions are checked in the headers and the
 * text in both phases; the HEADERS phase also notes the messages of the device that only their
 * text rules out ([ServerSearchProgress.refuted]), which the local search cannot know about.
 * Dates are asked for with a day more on each side and the matches kept by the device's days
 * ([ServerQuery.datesMatch]).
 * The TEXT phase, only for a query with words ([phasesToRun]), searches message bodies (and the
 * headers too when there are several words, which may be in different parts) in windows of the
 * newest messages first, splitting a window that takes too long and skipping one where the
 * criteria of the headers alone match nothing (a date range, a sender). Attachments have no IMAP
 * search key: matches are kept by their BODYSTRUCTURE, a page at a time, until enough are found.
 * A server that cannot be reached ends the search with a NETWORK error rather than a timeout per
 * folder. Results are emitted after every step;
 * matches not cached in `messages` are added to the header index, never to the mailbox lists
 * (only the few previews of the SNIPPETS phase are downloaded, as detached rows).
 *
 * Each account has one pooled search connection that can be aborted: cancelling the collector
 * closes its socket at once. It is closed after [CLIENT_IDLE_CLOSE] unused.
 */
class ServerSearch internal constructor(
    private val store: MailStore,
    private val clients: MailClientProvider,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val options: ImapOptions,
    private val storeParsedBody: (Long, ParsedBody) -> Unit,
    private val onValidityChanged: suspend (Long) -> Unit,
    private val ensureFolders: suspend (Long) -> Unit,
    private val limits: Limits = Limits(),
) {
    /** Time limits, shortened by tests. */
    internal data class Limits(
        val headerStepDeadline: Long = HEADER_STEP_DEADLINE,
        val textStepDeadline: Long = TEXT_STEP_DEADLINE,
        val clientIdleClose: Long = CLIENT_IDLE_CLOSE,
    )

    private val activeCounts = HashMap<Long, Int>()

    /**
     * Runs the syncs of folders found renumbered: they outlive the search that found them, which
     * neither waits for them nor cancels them.
     */
    private val background = CoroutineScope(SupervisorJob() + io)
    private val resyncing: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val active = MutableStateFlow<Set<Long>>(emptySet())

    /** Accounts a search is running on: the header indexer leaves them alone meanwhile. */
    val activeAccounts: StateFlow<Set<Long>> = active.asStateFlow()

    /**
     * Searches the server and emits the progress after every step, ending with a progress whose
     * [ServerSearchProgress.finished] is set. Cold: each collection runs its own search, and
     * cancelling the collection stops it, socket included. Failures are emitted, never thrown.
     */
    fun search(request: ServerSearchRequest): Flow<ServerSearchProgress> =
        channelFlow { store.safety.requireAttachments(); store.safety.requireNetwork(); SearchRun(request, this).execute() }.flowOn(io)

    // ------------------------------------------------------------------ connection pool

    private class Pooled(val settings: ServerSettings, val client: ImapClient) {
        var users = 0
        var releasedAt = 0L
    }

    private val pool = HashMap<Long, Pooled>()

    private val idleCloser: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "search-idle-close").apply { isDaemon = true } }
    }

    /**
     * The account's search connection: a client derived from its main one, kept between
     * searches and replaced when the account's server settings change. A call still running on
     * it (from a search being cancelled) is aborted, so the new search does not wait behind it.
     */
    private fun acquire(accountId: Long): ImapClient {
        val settings = clients.account(accountId).imap
        val replaced: ImapClient?
        val client: ImapClient
        synchronized(pool) {
            val pooled = pool[accountId]
            if (pooled != null && pooled.settings == settings) {
                pooled.users++
                if (pooled.client.isBusy) pooled.client.abort()
                return pooled.client
            }
            replaced = pooled?.client
            client = clients.imap(accountId).derive(options)
            pool[accountId] = Pooled(settings, client).also { it.users = 1 }
        }
        replaced?.close()
        return client
    }

    private fun release(accountId: Long, client: ImapClient) {
        synchronized(pool) {
            val pooled = pool[accountId]?.takeIf { it.client === client } ?: return
            pooled.users = (pooled.users - 1).coerceAtLeast(0)
            pooled.releasedAt = System.nanoTime()
            if (pooled.users > 0) return
        }
        idleCloser.schedule({ closeIfIdle(accountId) }, limits.clientIdleClose, TimeUnit.MILLISECONDS)
    }

    private fun closeIfIdle(accountId: Long) {
        val idle = synchronized(pool) {
            val pooled = pool[accountId] ?: return
            val unusedFor = (System.nanoTime() - pooled.releasedAt) / 1_000_000
            if (pooled.users > 0 || unusedFor < limits.clientIdleClose) return
            pool.remove(accountId)
            pooled.client
        }
        idle.close()
    }

    /** Stops and forgets the account's search connection (its login changed or it was removed). */
    private fun drop(accountId: Long) {
        synchronized(pool) { pool.remove(accountId) }?.client?.abort()
    }

    /** Closes every search connection. */
    internal fun closeAll() {
        val all = synchronized(pool) { pool.values.toList().also { pool.clear() } }
        all.forEach { it.client.close() }
    }

    private fun markActive(accountIds: Collection<Long>, running: Boolean) {
        synchronized(activeCounts) {
            for (id in accountIds) {
                val count = (activeCounts[id] ?: 0) + if (running) 1 else -1
                if (count > 0) activeCounts[id] = count else activeCounts.remove(id)
            }
            active.value = activeCounts.keys.toSet()
        }
    }

    // ------------------------------------------------------------------ one search

    /** A folder opened for this search, with what EXAMINE said. */
    private class Probed(
        val folder: Folder,
        val probe: FolderProbe,
        /** The folder has a state row in the header index, of [probe]'s UIDVALIDITY. */
        val indexed: Boolean,
        /** The cached `messages` rows belong to another UIDVALIDITY and are ignored. */
        val stale: Boolean,
    )

    /** What a call to the server came to. */
    private sealed interface Call<out T> {
        class Done<T>(val value: T) : Call<T>
        data object TimedOut : Call<Nothing>
        class Failed(val error: MailException) : Call<Nothing>
    }

    private inner class SearchRun(
        private val request: ServerSearchRequest,
        private val out: ProducerScope<ServerSearchProgress>,
    ) {
        private val limit = request.limit.coerceAtLeast(1)
        private var hits: List<SearchHit> = emptyList()
        private val problems = ArrayList<StepProblem>()
        private var phase: SearchPhase? = null
        private var folder: Folder? = null
        private var step = 0
        private var totalSteps = 0
        private var truncated = false

        private val connections = HashMap<Long, ImapClient>()
        private val probed = HashMap<Long, Probed>()

        /** Folders skipped for the rest of the search (gone from the server). */
        private val skipped = HashSet<Long>()

        /** Unsplittable steps that timed out, per folder and phase. */
        private val timeouts = HashMap<Pair<Long, SearchPhase>, Int>()

        /** Hits found by TEXT steps: candidates for previews. */
        private val textKeys = HashSet<SearchKey>()

        /** Messages of the device ruled out by an excluded word in their text ([refute]); [refutedNow] as emitted. */
        private val refuted = HashSet<SearchKey>()
        private var refutedNow: Set<SearchKey> = emptySet()

        /** Accounts whose login changed or that were removed during the search: no longer searched. */
        private val invalidated: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        /** Calls in a row per account that timed out before the server even let us log in. */
        private val unanswered = HashMap<Long, Int>()

        /** The error that ends the search, once one has. */
        private var stopError: MailException? = null
        private var stopped = false

        suspend fun execute() {
            val query = ServerQuery.parse(request.query, ServerQuery.HEADER_FIELDS)
            val phases = phasesToRun(query, request.phases)
            if (query == null || phases.isEmpty()) {
                emit(finished = true)
                return
            }
            val accounts = request.scope.accountIds.distinct()
            markActive(accounts, true)
            val watcher = out.launch(start = CoroutineStart.UNDISPATCHED) {
                clients.invalidated.collect { accountId ->
                    if (accountId in accounts) {
                        invalidated += accountId
                        drop(accountId)
                    }
                }
            }
            try {
                run(query, accounts, phases)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A database failure: report it like any other.
                stopError = MailException(MailErrorKind.UNKNOWN, e.message, e)
            } finally {
                watcher.cancel()
                markActive(accounts, false)
                connections.forEach { (accountId, client) -> release(accountId, client) }
            }
            phase = null
            folder = null
            emit(finished = true, error = stopError)
        }

        private suspend fun run(query: ServerQuery, accounts: List<Long>, phases: Set<SearchPhase>) {
            val plan = plan(accounts)
            if (stopped) return
            if (SearchPhase.HEADERS in phases) {
                headersPhase(plan, query.withFields(ServerQuery.HEADER_FIELDS))
                if (stopped) return
            }
            if (SearchPhase.TEXT in phases) {
                // One word is found in the headers by HEADERS; of several, each may be in another
                // part ("elecciones" in the subject, "delegados" in the text).
                val fields = if (query.words.size > 1) ServerQuery.HEADER_FIELDS + ServerQuery.TEXT_FIELDS else ServerQuery.TEXT_FIELDS
                textPhase(plan, query.withFields(fields))
                if (stopped) return
                if (request.snippets) snippetsPhase()
            }
        }

        /** The folders to search, account by account; folder lists are fetched where unknown. */
        private suspend fun plan(accounts: List<Long>): List<Pair<Folder, Boolean>> {
            val result = ArrayList<Pair<Folder, Boolean>>()
            for (accountId in accounts) {
                if (store.accounts.get(accountId) == null) continue
                when (val listed = call(limits.headerStepDeadline) { ensureFolders(accountId) }) {
                    is Call.Done -> Unit
                    Call.TimedOut -> continue
                    is Call.Failed -> {
                        if (accountId !in invalidated && stopsSearch(listed.error)) return result
                        continue
                    }
                }
                val folders = store.folders.getByAccount(accountId)
                val indexable = store.folders.indexableFolders(accountId).mapTo(HashSet()) { it.id }
                folderOrder(folders, indexable, request.scope).forEach { result += it to (it.id in indexable) }
            }
            return result
        }

        // -------------------------------------------------------------- HEADERS

        private suspend fun headersPhase(plan: List<Pair<Folder, Boolean>>, query: ServerQuery) {
            phase = SearchPhase.HEADERS
            totalSteps = plan.size
            // The index matches short words ("CV", "SO") as whole words only: for them a complete
            // index does not stand in for the folder's older mail, the server is asked about all of it.
            val indexAnswers = HeaderIndexDao.answersFully(request.query)
            for ((index, entry) in plan.withIndex()) {
                val (target, indexable) = entry
                step = index + 1
                folder = target
                emit()
                if (target.accountId in invalidated) continue
                val state = if (indexable && indexAnswers) store.headerIndex.state(target.id) else null
                if (state != null && state.complete && upToDate(target, state)) continue
                val opened = open(target, indexable, SearchPhase.HEADERS)
                if (stopped) return
                if (opened == null) continue
                val trusted = if (indexAnswers) store.headerIndex.state(target.id) else null
                val steps = headerSteps(target, opened.probe, trusted, HeaderIndexDao.capOf(target.role))
                runSteps(ArrayDeque(steps), query, stepProgress = false, precheck = null)
                if (stopped) return
            }
        }

        /**
         * Whether the complete index of [target] needs no server search: it was checked a moment
         * ago and our own changes added nothing since, or STATUS shows no mail newer than it.
         */
        private suspend fun upToDate(target: Folder, state: IndexFolderState): Boolean {
            val now = clock()
            val updatedAt = state.updatedAt
            if (!state.dirty && updatedAt != null && updatedAt > now - INDEX_FRESH) return true
            val status = when (val asked = call(limits.headerStepDeadline, target.accountId) { connection(target.accountId).status(target.fullName) }) {
                is Call.Done -> asked.value
                // EXAMINE is tried next and reports the problem, if it persists.
                else -> return false
            }
            if (status.uidValidity != state.uidValidity || status.uidNext != state.uidNext) return false
            store.headerIndex.updateState(target.id, updatedAt = now)
            return true
        }

        /** EXAMINEs [target] (it stays selected for the steps that follow), or records why not. */
        private suspend fun open(target: Folder, indexable: Boolean, phase: SearchPhase): Probed? {
            val probe = when (val examined = call(limits.headerStepDeadline, target.accountId) { connection(target.accountId).examine(target.fullName) }) {
                is Call.Done -> examined.value
                Call.TimedOut -> {
                    problems += StepProblem(target, phase, StepOutcome.TIMED_OUT, null, null, null)
                    return null
                }
                is Call.Failed -> {
                    folderFailed(target, phase, examined.error)
                    return null
                }
            }
            val cachedValidity = store.folders.syncState(target.id)?.uidValidity ?: 0L
            val stale = cachedValidity != 0L && cachedValidity != probe.uidValidity
            // The mailbox was renumbered: its cache is rebuilt by a sync, apart from this search.
            if (stale) resync(target.id)
            val state = if (indexable) store.headerIndex.ensureState(target.id, probe.uidValidity, probe.uidNext, probe.messageCount) else null
            return Probed(target, probe, indexed = state != null, stale = stale).also { probed[target.id] = it }
        }

        // -------------------------------------------------------------- TEXT and SNIPPETS

        private suspend fun textPhase(plan: List<Pair<Folder, Boolean>>, query: ServerQuery) {
            phase = SearchPhase.TEXT
            step = 0
            totalSteps = 0
            for ((target, indexable) in plan) {
                if (target.id in probed || target.id in skipped || target.accountId in invalidated) continue
                folder = target
                emit()
                open(target, indexable, SearchPhase.TEXT)
                if (stopped) return
            }
            val windows = plan.mapNotNull { (target, _) -> probed[target.id]?.let { target to it.probe } }
            runSteps(ArrayDeque(textSteps(windows)), query, stepProgress = true, precheck = query.headerCriteria())
        }

        /** Downloads the newest few text matches that are not cached, so their rows show a preview. */
        private suspend fun snippetsPhase() {
            val candidates = hits.asSequence()
                .filterIsInstance<SearchHit.Indexed>()
                .filter { it.key in textKeys && it.entry.size < SNIPPET_MAX_SIZE }
                .take(SNIPPET_PREFETCH)
                .toList()
            if (candidates.isEmpty()) return
            phase = SearchPhase.SNIPPETS
            folder = null
            step = 0
            totalSteps = candidates.size
            emit()
            for (candidate in candidates) {
                step++
                val target = probed[candidate.key.folderId] ?: continue
                if (target.stale || target.folder.accountId in invalidated) continue
                when (val loaded = call(limits.textStepDeadline, target.folder.accountId) { preview(target, candidate.entry) }) {
                    is Call.Done -> {
                        if (loaded.value.isEmpty()) continue
                        hits = mergeHits(hits, loaded.value, limit = limit)
                        emit()
                    }
                    // Previews are optional: give up on them when the server is slow or unreachable.
                    Call.TimedOut -> return
                    is Call.Failed -> if (loaded.error.kind != MailErrorKind.NOT_FOUND) return
                }
            }
        }

        /** Stores [entry] as a detached row with its body; its hit, or nothing if it is gone. */
        private suspend fun preview(target: Probed, entry: IndexedMessage): List<SearchHit.Cached> {
            val mailbox = target.folder
            val client = connection(mailbox.accountId)
            val fetched = client.fetchByUids(mailbox.fullName, listOf(entry.uid))
            if (fetched.uidValidity != target.probe.uidValidity) return emptyList()
            val message = fetched.messages.firstOrNull { it.uid == entry.uid && !it.flags.deleted } ?: return emptyList()
            if (!store.storeFetchedOutsideSync(mailbox, fetched.uidValidity, listOf(message), clock())) return emptyList()
            val ref = store.messages.findByUid(mailbox.id, entry.uid)?.takeIf { !it.hidden } ?: return emptyList()
            if (!ref.bodyLoaded) {
                val body = client.fetchBody(mailbox.fullName, entry.uid)
                storeParsedBody(ref.id, body)
            }
            return store.messages.cachedHits(mailbox.id, listOf(entry.uid))
        }

        // -------------------------------------------------------------- steps

        /**
         * Runs [steps] in order. A step that takes too long is split in halves (and the folder's
         * later windows in the phase are planned at that size); one that cannot be split is
         * recorded, and after [MAX_TIMEOUTS_PER_FOLDER_PHASE] of those the rest of the folder in
         * the phase is skipped. A step whose window has no message matching [precheck] (the
         * criteria of the headers, cheap to search) is skipped.
         */
        private suspend fun runSteps(steps: ArrayDeque<PlannedStep>, query: ServerQuery, stepProgress: Boolean, precheck: ServerQuery?) {
            var done = 0
            while (steps.isNotEmpty()) {
                val current = steps.removeFirst()
                val target = probed[current.folder.id] ?: continue
                if (current.folder.id in skipped || current.folder.accountId in invalidated) continue
                if (stepProgress) {
                    done++
                    step = done
                    totalSteps = done + steps.size
                    folder = current.folder
                    emit()
                }
                if (precheck != null && nothingMatches(current, target, precheck)) continue
                val deadline = if (current.phase == SearchPhase.TEXT) limits.textStepDeadline else limits.headerStepDeadline
                val refutes = current.phase == SearchPhase.HEADERS && query.excluded.isNotEmpty() && refuted.size < MAX_REFUTED
                // Without an IMAP key for attachments, every match of a step is checked; [refute]
                // needs every match too.
                val perStep = if (query.hasAttachment || refutes) Int.MAX_VALUE else PER_STEP_LIMIT
                val searched = call(deadline, current.folder.accountId) {
                    connection(current.folder.accountId).searchUids(current.folder.fullName, query, current.range, perStep)
                }
                val outcome = when (searched) {
                    is Call.Done -> searched.value
                    Call.TimedOut -> {
                        timedOut(current, steps)
                        continue
                    }
                    is Call.Failed -> {
                        stepFailed(current, searched.error, steps)
                        if (stopped) return
                        continue
                    }
                }
                if (outcome.uidValidity != target.probe.uidValidity) {
                    // Renumbered since EXAMINE: these uids mean nothing to the cache or the index.
                    folderGone(current, steps, MailException(MailErrorKind.SERVER, "UIDVALIDITY changed during the search"))
                    resync(target.folder.id)
                    continue
                }
                if (outcome.charsetRejected && outcome.uids.isEmpty()) {
                    problems += problem(current, StepOutcome.FAILED, MailException(MailErrorKind.SERVER, CHARSET_REJECTED))
                    continue
                }
                if (refutes) refute(current, target, query, outcome.uids)
                val uids = if (query.hasAttachment) outcome.uids else outcome.uids.take(PER_STEP_LIMIT)
                if (outcome.totalMatches > uids.size) truncated = true
                if (uids.isEmpty()) continue
                // Matches filtered here (attachments) are looked at a page at a time, newest
                // first, so the ones without attachments never use up the limit.
                val pages = if (query.hasAttachment) uids.chunked(ATTACHMENT_PAGE) else listOf(uids)
                for (page in pages) {
                    val resolved = call(limits.headerStepDeadline, current.folder.accountId) { resolve(target, page, query.hasAttachment) }
                    val found = when (resolved) {
                        // The server was asked for a day more on each side: the device's days decide.
                        is Call.Done -> resolved.value.filter { query.datesMatch(it.date) }
                        Call.TimedOut -> {
                            problems += problem(current, StepOutcome.TIMED_OUT, null)
                            break
                        }
                        is Call.Failed -> {
                            stepFailed(current, resolved.error, steps)
                            if (stopped) return
                            break
                        }
                    }
                    if (found.isEmpty()) continue
                    if (current.phase == SearchPhase.TEXT) found.mapTo(textKeys) { it.key }
                    hits = mergeHits(hits, found, limit = limit)
                    if (hits.size >= limit) {
                        truncated = true
                        stopped = true
                        return
                    }
                    emit()
                }
            }
        }

        /**
         * Notes the messages of the device in the window of [step] that an excluded word of
         * [query] rules out by their text alone: those matching the query with its exclusions
         * checked in the headers only ([ServerQuery.headerExclusionsOnly]) but not in [matched],
         * every match of the query in the window. The local search cannot see that for a header
         * index row or a cached message whose text was not downloaded. One header search more per
         * step; when it fails or takes too long nothing is ruled out. At most [MAX_REFUTED] are
         * kept, the newest first, as the local results are.
         */
        private suspend fun refute(step: PlannedStep, target: Probed, query: ServerQuery, matched: List<Long>) {
            val checked = call(limits.headerStepDeadline, step.folder.accountId) {
                connection(step.folder.accountId).searchUids(step.folder.fullName, query.headerExclusionsOnly(), step.range, Int.MAX_VALUE)
            }
            val outcome = (checked as? Call.Done)?.value ?: return
            if (outcome.uidValidity != target.probe.uidValidity) return
            val kept = matched.toHashSet()
            val candidates = outcome.uids.filter { it !in kept }
            if (candidates.isEmpty()) return
            val folderId = step.folder.id
            val known = HashSet<Long>()
            // Cached rows of another UIDVALIDITY are not these messages.
            if (!target.stale) known += store.messages.existingUids(folderId, candidates)
            if (target.indexed) known += store.headerIndex.existingUids(folderId, candidates)
            val before = refuted.size
            for (uid in candidates) {
                if (refuted.size >= MAX_REFUTED) break
                if (uid in known) refuted += SearchKey(folderId, uid)
            }
            if (refuted.size != before) refutedNow = refuted.toSet()
        }

        /**
         * True when [criteria] (of the headers and flags only) match no message in the window of
         * [step], so its message text need not be searched. Any doubt (no answer in time, a
         * failure, values the server cannot search for) leaves the step to run.
         */
        private suspend fun nothingMatches(step: PlannedStep, target: Probed, criteria: ServerQuery): Boolean {
            val checked = call(limits.headerStepDeadline, step.folder.accountId) {
                connection(step.folder.accountId).searchUids(step.folder.fullName, criteria, step.range, 0)
            }
            val outcome = (checked as? Call.Done)?.value ?: return false
            return outcome.uidValidity == target.probe.uidValidity && !outcome.charsetRejected && outcome.totalMatches == 0
        }

        private fun timedOut(current: PlannedStep, steps: ArrayDeque<PlannedStep>) {
            val halves = split(current)
            if (halves != null) {
                val depth = current.depth + 1
                val later = steps.flatMap { pending ->
                    if (pending.folder.id == current.folder.id && pending.phase == current.phase) refine(pending, depth) else listOf(pending)
                }
                steps.clear()
                steps.addAll(halves)
                steps.addAll(later)
                return
            }
            problems += problem(current, StepOutcome.TIMED_OUT, null)
            val key = current.folder.id to current.phase
            val count = (timeouts[key] ?: 0) + 1
            timeouts[key] = count
            if (count >= MAX_TIMEOUTS_PER_FOLDER_PHASE) skipRest(current, steps, StepOutcome.SKIPPED, null)
        }

        private fun stepFailed(current: PlannedStep, error: MailException, steps: ArrayDeque<PlannedStep>) {
            when {
                // Its login changed or it was removed: the account is just no longer searched.
                current.folder.accountId in invalidated -> Unit
                stopsSearch(error) -> Unit
                error.kind == MailErrorKind.NOT_FOUND -> folderGone(current, steps, error)
                else -> problems += problem(current, StepOutcome.FAILED, error)
            }
        }

        /** The folder vanished from the server: nothing more is asked of it. */
        private fun folderGone(current: PlannedStep, steps: ArrayDeque<PlannedStep>, error: MailException) {
            skipped += current.folder.id
            steps.removeAll { it.folder.id == current.folder.id }
            problems += problem(current, StepOutcome.SKIPPED, error)
        }

        /** Records the remaining steps of [current]'s folder in its phase as one skipped part, and drops them. */
        private fun skipRest(current: PlannedStep, steps: ArrayDeque<PlannedStep>, outcome: StepOutcome, error: MailException?) {
            val rest = steps.filter { it.folder.id == current.folder.id && it.phase == current.phase }
            if (rest.isEmpty()) return
            steps.removeAll { it.folder.id == current.folder.id && it.phase == current.phase }
            val windows = rest.mapNotNull { it.range as? SearchRange.Seq }
            problems += StepProblem(
                current.folder,
                current.phase,
                outcome,
                windows.minOfOrNull { it.first },
                windows.maxOfOrNull { it.last },
                error,
            )
        }

        /** EXAMINE of [target] failed: the folder is skipped, or the whole search ends. */
        private fun folderFailed(target: Folder, phase: SearchPhase, error: MailException) {
            if (target.accountId in invalidated || stopsSearch(error)) return
            val outcome = if (error.kind == MailErrorKind.NOT_FOUND) StepOutcome.SKIPPED else StepOutcome.FAILED
            if (error.kind == MailErrorKind.NOT_FOUND) skipped += target.id
            problems += StepProblem(target, phase, outcome, null, null, error)
        }

        /**
         * True (and the search ends) for a failure that concerns the whole account: no
         * connection (not even one could be opened), login refused, certificate rejected.
         */
        private fun stopsSearch(error: MailException): Boolean {
            val accountWide = error.kind == MailErrorKind.AUTHENTICATION ||
                error.kind == MailErrorKind.CERTIFICATE ||
                (error.kind == MailErrorKind.NETWORK && (!MailErrors.isTimeout(error) || MailErrors.isConnectFailure(error)))
            if (accountWide) {
                stopError = error
                stopped = true
            }
            return accountWide
        }

        private fun problem(step: PlannedStep, outcome: StepOutcome, error: MailException?): StepProblem {
            val window = step.range as? SearchRange.Seq
            return StepProblem(step.folder, step.phase, outcome, window?.first, window?.last, error)
        }

        /**
         * Turns the matching [uids] of a folder (newest first) into hits: cached rows as they
         * are, the others from the header index, whose missing rows are fetched first. Flags of
         * rows the index already had are refreshed, and rows of messages gone meanwhile dropped.
         * With [attachments], only messages with attachments are kept (see [withAttachments]).
         */
        private suspend fun resolve(target: Probed, uids: List<Long>, attachments: Boolean): List<SearchHit> {
            val mailbox = target.folder
            val existing = if (target.stale) emptySet() else store.messages.existingUids(mailbox.id, uids)
            val cached = store.messages.cachedHits(mailbox.id, uids.filter { it in existing })
            // Cached rows know their attachments from the BODYSTRUCTURE.
            val found = ArrayList<SearchHit>(if (attachments) cached.filter { it.hasAttachments } else cached)
            val rest = uids.filter { it !in existing }
            if (rest.isEmpty()) return found
            if (attachments) {
                found += withAttachments(target, rest)
                return found
            }
            if (!target.indexed) {
                // A folder the index leaves out (Drafts, a virtual folder): its matches become
                // detached rows, unless the cache is of another UIDVALIDITY.
                if (target.stale) return found
                val fetched = connection(mailbox.accountId).fetchByUids(mailbox.fullName, rest)
                if (fetched.uidValidity != target.probe.uidValidity) return found
                val present = fetched.messages.filter { !it.flags.deleted }
                if (store.storeFetchedOutsideSync(mailbox, fetched.uidValidity, present, clock())) {
                    found += store.messages.cachedHits(mailbox.id, present.map { it.uid })
                }
                return found
            }
            val known = store.headerIndex.findByUids(mailbox.id, rest)
            val knownUids = known.mapTo(HashSet()) { it.uid }
            val missing = rest.filter { it !in knownUids }
            if (missing.isNotEmpty()) {
                val batch = connection(mailbox.accountId).fetchIndexHeaders(mailbox.fullName, missing)
                if (batch.uidValidity == target.probe.uidValidity) index(target, batch.headers)
            }
            var stillThere = knownUids.toList()
            if (stillThere.isNotEmpty()) {
                val flags = connection(mailbox.accountId).fetchFlagsFor(mailbox.fullName, stillThere)
                // Rows marked \Deleted are removed by updateFlags; expunged ones here.
                store.headerIndex.updateFlags(mailbox.id, flags)
                val gone = stillThere.filter { it !in flags }
                if (gone.isNotEmpty()) store.headerIndex.deleteUids(mailbox.id, gone)
                stillThere = stillThere.filter { flags[it]?.deleted == false }
            }
            val indexed = store.headerIndex.findByUids(mailbox.id, stillThere + missing)
            indexed.mapTo(found) { SearchHit.Indexed(it) }
            return found
        }

        /**
         * The messages of [uids] (not cached) that have attachments, by their BODYSTRUCTURE: the
         * header index only guesses from the top-level type, so these are fetched. As in
         * [resolve], the matches the index lacks are added to it (with the BODYSTRUCTURE answer),
         * the flags of the rows it has are refreshed, and in a folder the index leaves out the
         * matches become detached rows.
         */
        private suspend fun withAttachments(target: Probed, uids: List<Long>): List<SearchHit> {
            val mailbox = target.folder
            if (!target.indexed && target.stale) return emptyList()
            val fetched = connection(mailbox.accountId).fetchByUids(mailbox.fullName, uids)
            if (fetched.uidValidity != target.probe.uidValidity) return emptyList()
            val matching = fetched.messages.filter { !it.flags.deleted && it.hasAttachments }
            if (!target.indexed) {
                if (matching.isEmpty()) return emptyList()
                if (!store.storeFetchedOutsideSync(mailbox, fetched.uidValidity, matching, clock())) return emptyList()
                return store.messages.cachedHits(mailbox.id, matching.map { it.uid })
            }
            val known = store.headerIndex.findByUids(mailbox.id, uids).mapTo(HashSet()) { it.uid }
            if (known.isNotEmpty()) {
                // Rows marked \Deleted are removed by updateFlags; expunged ones here.
                store.headerIndex.updateFlags(mailbox.id, fetched.messages.filter { it.uid in known }.associate { it.uid to it.flags })
                val present = fetched.messages.mapTo(HashSet()) { it.uid }
                val gone = known.filter { it !in present }
                if (gone.isNotEmpty()) store.headerIndex.deleteUids(mailbox.id, gone)
            }
            val missing = matching.filter { it.uid !in known }
            if (missing.isNotEmpty()) index(target, missing.map { it.toIndexedHeader() })
            // A row the index had keeps its guess; the hit says what the BODYSTRUCTURE says.
            return store.headerIndex.findByUids(mailbox.id, matching.map { it.uid })
                .map { SearchHit.Indexed(it.copy(hasAttachments = true)) }
        }

        /** Adds [headers] to the index; the state row is created again if a sync reset it meanwhile. */
        private fun index(target: Probed, headers: List<IndexedHeader>) {
            val validity = target.probe.uidValidity
            if (store.headerIndex.insertHeaders(target.folder.id, validity, headers) >= 0) return
            val probe = target.probe
            if (store.headerIndex.ensureState(target.folder.id, validity, probe.uidNext, probe.messageCount) != null) {
                store.headerIndex.insertHeaders(target.folder.id, validity, headers)
            }
        }

        // -------------------------------------------------------------- helpers

        private fun connection(accountId: Long): ImapClient = connections.getOrPut(accountId) { acquire(accountId) }

        /**
         * Runs [block] (a few server calls on the search connection of [accountId], if given)
         * within [deadline]. A cancelled caller is never reported as a failure; a read timeout
         * counts as running out of time, but not reaching the server does not: a connection that
         * could not be opened fails, and so does the second call in a row that timed out before
         * even logging in (a server or network that swallows the connection), so the search ends
         * with a connection error instead of waiting out every folder.
         */
        private suspend fun <T : Any> call(deadline: Long, accountId: Long? = null, block: suspend () -> T): Call<T> {
            val client = try {
                accountId?.let(::connection)
            } catch (e: MailException) {
                currentCoroutineContext().ensureActive()
                return Call.Failed(e)
            }
            val connected = client?.isConnected ?: true
            val logins = client?.logins ?: 0
            val result: Call<T> = try {
                val value = withTimeoutOrNull(deadline) { block() }
                if (value == null) Call.TimedOut else Call.Done(value)
            } catch (e: MailException) {
                currentCoroutineContext().ensureActive()
                if (MailErrors.isTimeout(e) && !MailErrors.isConnectFailure(e)) Call.TimedOut else Call.Failed(e)
            }
            if (accountId == null || client == null) return result
            if (result !is Call.TimedOut) {
                if (result is Call.Done) unanswered.remove(accountId)
                return result
            }
            // Timed out while connecting or logging in: the server did not answer at all.
            if (connected || client.logins != logins) return result
            val count = (unanswered[accountId] ?: 0) + 1
            unanswered[accountId] = count
            if (count < MAX_UNANSWERED_CONNECTS) return result
            return Call.Failed(MailException(MailErrorKind.NETWORK, SERVER_NOT_ANSWERING))
        }

        /** Rebuilds the cache of a renumbered folder with a sync that does not hold up this search. */
        private fun resync(folderId: Long) {
            if (!resyncing.add(folderId)) return
            background.launch {
                try {
                    onValidityChanged(folderId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next sync of the folder tries again.
                } finally {
                    resyncing.remove(folderId)
                }
            }
        }

        private suspend fun emit(finished: Boolean = false, error: MailException? = null) {
            store.safety.requireAttachments()
            out.send(
                ServerSearchProgress(
                    request = request,
                    phase = phase,
                    folder = folder,
                    step = step,
                    totalSteps = totalSteps,
                    hits = hits.filterNot { store.safety.isSenderBlocked(it.display.from?.address) },
                    problems = problems.toList(),
                    finished = finished,
                    truncated = truncated,
                    error = error,
                    refuted = refutedNow,
                ),
            )
        }
    }

    companion object {
        /** Matches kept per step; the rest of a step's matches are dropped (the result is truncated). */
        const val PER_STEP_LIMIT = 200

        /** Matches whose attachments are checked per server call. */
        const val ATTACHMENT_PAGE = 100

        /** Messages of the device a search rules out at most ([ServerSearchProgress.refuted]). */
        const val MAX_REFUTED = 10_000

        /** Longest wait for one HEADERS step (or EXAMINE, STATUS, a header fetch). */
        const val HEADER_STEP_DEADLINE = 45_000L

        /** Longest wait for one TEXT window or one preview. */
        const val TEXT_STEP_DEADLINE = 60_000L

        /** Unsplittable steps of a folder and phase that may time out before the rest of it is skipped. */
        const val MAX_TIMEOUTS_PER_FOLDER_PHASE = 3

        /** A complete index checked this recently is trusted without asking the server. */
        const val INDEX_FRESH = 15 * 60_000L

        /** Previews downloaded after a TEXT search. */
        const val SNIPPET_PREFETCH = 5

        /** Messages this large get no preview. */
        const val SNIPPET_MAX_SIZE = 150L * 1024

        /** An unused search connection is closed after this long. */
        const val CLIENT_IDLE_CLOSE = 2 * 60_000L

        /** Calls in a row that time out before logging in after which the server counts as unreachable. */
        const val MAX_UNANSWERED_CONNECTS = 2

        /** Message of the [MailErrorKind.NETWORK] error of a search stopped because the server never answered. */
        const val SERVER_NOT_ANSWERING = "The server does not answer"

        /**
         * Message of the [MailErrorKind.SERVER] error of a [StepOutcome.FAILED] step whose words
         * the server cannot search for (it refused `CHARSET UTF-8` and they have no plain-ASCII
         * spelling, or that spelling found nothing).
         */
        const val CHARSET_REJECTED = "BADCHARSET"
    }
}

/**
 * Stores messages of [folder] fetched outside a sync (a search result being opened, a preview)
 * without changing what the folder lists: a message outside the folder's sync window, or of a
 * folder never synced, gets a detached row used at [now]; one inside the window becomes an
 * ordinary row. Local flag changes waiting to be pushed are kept. Nothing is stored, and false
 * returned, when the folder is gone or its cache belongs to another UIDVALIDITY.
 */
internal fun MailStore.storeFetchedOutsideSync(folder: Folder, uidValidity: Long, fetched: List<RemoteMessage>, now: Long): Boolean =
    tx {
        val state = folders.syncState(folder.id) ?: return@tx false
        if (state.uidValidity != 0L && state.uidValidity != uidValidity) return@tx false
        val synced = state.uidValidity != 0L
        val (inside, outside) = fetched.partition { synced && it.uid in state.windowStartUid..state.windowEndUid }
        val protectedUids = pendingOps.protectedUids(folder.accountId, folder.fullName)
        val keepFlags = fetched.mapNotNullTo(HashSet()) { message -> message.uid.takeIf { it in protectedUids } }
        if (inside.isNotEmpty()) messages.upsertHeaders(folder.accountId, folder.id, inside, keepFlags, feedContacts = false)
        if (outside.isNotEmpty()) {
            messages.upsertHeaders(folder.accountId, folder.id, outside, keepFlags, feedContacts = false, detachedAt = now)
        }
        true
    }
