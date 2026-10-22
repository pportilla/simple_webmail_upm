package io.github.pportilla.upmmail.ui.search

import io.github.pportilla.upmmail.mail.ServerQuery
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchKey
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.ServerSearchScope
import io.github.pportilla.upmmail.model.StepOutcome
import io.github.pportilla.upmmail.model.StepProblem
import io.github.pportilla.upmmail.model.mergeHits
import io.github.pportilla.upmmail.sync.ServerSearch
import java.text.NumberFormat
import java.util.Locale

/*
 * Pure (Android-free) rules of the search screen, unit-tested on the JVM.
 */

/** Wait this long after the last keystroke before searching the local cache. */
internal const val SEARCH_DEBOUNCE_MILLIS = 250L

/** With fewer results on the device than this, the server's headers are searched without asking. */
internal const val AUTO_SERVER_MAX_LOCAL = 5

/** ... once the local results have been on screen this long (typing on cancels it). */
internal const val AUTO_SERVER_DELAY_MILLIS = 800L

/** About what indexing one message downloads (its headers), for the mobile-data warning. */
internal const val INDEX_BYTES_PER_MESSAGE = 900L

/** Wait this long after the last keystroke in a person field before suggesting contacts. */
internal const val SUGGEST_DEBOUNCE_MILLIS = 150L

/** Contacts suggested under a person field of the advanced search panel. */
internal const val SUGGESTION_LIMIT = 5

/** Contacts looked at to find the name of an address in the search text. */
internal const val NAME_LOOKUP_LIMIT = 5

/** Row key prefixes of the two result sections; see [hitKey]. */
internal const val SECTION_LOCAL = "local"
internal const val SECTION_SERVER = "server"

/** The chips above the results. */
data class SearchFilterState(
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val withAttachments: Boolean = false,
    /** Search only the folder the screen was opened from (when there is one). */
    val thisFolderOnly: Boolean = false,
)

/** Local-search filters for [filters] given the screen's account and folder arguments. */
internal fun toSearchFilters(filters: SearchFilterState, accountId: Long?, folderId: Long?): SearchFilters =
    SearchFilters(
        accountId = accountId,
        folderId = if (filters.thisFolderOnly) folderId else null,
        unreadOnly = filters.unreadOnly,
        starredOnly = filters.starredOnly,
        withAttachments = filters.withAttachments,
    )

/** True if [hit] passes the flag filters (server results are not filtered by the server). */
internal fun matchesFilters(hit: SearchHit, filters: SearchFilterState): Boolean =
    (!filters.unreadOnly || !hit.isRead) &&
        (!filters.starredOnly || hit.isStarred) &&
        (!filters.withAttachments || hit.hasAttachments)

/**
 * The results on the device and the extra ones the server found, each shown in its own section.
 * [serverOnly] counts the server's extra matches before the flag filters.
 */
internal data class HitSections(
    val local: List<SearchHit>,
    val fromServer: List<SearchHit>,
    val serverOnly: Int = fromServer.size,
)

/**
 * Splits [local] and [server] results into the two sections.
 *
 * The server section holds the server matches that were not already shown on the device when
 * the server search started (neither the same message, [localKeysAtStart], nor a copy of it,
 * [localMidsAtStart]). When the local search runs again later (opened matches are cached by
 * then) those messages stay in the server section, with the fresher local copy, instead of
 * jumping between sections. The local section is the rest, but for the device results the
 * server ruled out ([refuted]: an excluded word in a text the device does not have). Flag
 * filters apply to both.
 */
internal fun mergeSections(
    local: List<SearchHit>,
    server: List<SearchHit>,
    localKeysAtStart: Set<SearchKey>,
    localMidsAtStart: Set<String>,
    filters: SearchFilterState,
    refuted: Set<SearchKey> = emptySet(),
): HitSections {
    val freshByKey = local.associateBy { it.key }
    val serverKeys = HashSet<SearchKey>()
    val serverMids = HashSet<String>()
    val fromServer = ArrayList<SearchHit>()
    for (hit in server) {
        if (hit.key in localKeysAtStart) continue
        if (messageIdOf(hit)?.let { it in localMidsAtStart } == true) continue
        // A cached copy is never replaced by an index-only one.
        val fresh = freshByKey[hit.key]?.takeUnless { it is SearchHit.Indexed && hit is SearchHit.Cached } ?: hit
        val mid = messageIdOf(fresh)
        if (!serverKeys.add(fresh.key) || (mid != null && !serverMids.add(mid))) continue
        fromServer += fresh
    }
    val localSection = local.filter { hit ->
        hit.key !in serverKeys && hit.key !in refuted && messageIdOf(hit)?.let { it in serverMids } != true &&
            matchesFilters(hit, filters)
    }
    return HitSections(localSection, fromServer.filter { matchesFilters(it, filters) }, fromServer.size)
}

/** The Message-ID [mergeHits] and [mergeSections] compare, or null without one. */
internal fun messageIdOf(hit: SearchHit): String? =
    hit.messageIdHeader?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

/**
 * Whether the server's headers should be searched by themselves, for few local results. Not for
 * an attachment filter alone: with no IMAP criterion, that search fetches the structure of every
 * message of every folder.
 */
internal fun shouldAutoSearch(query: String, localCount: Int, alreadySearched: Boolean): Boolean =
    !alreadySearched && localCount < AUTO_SERVER_MAX_LOCAL && ServerQuery.parse(query)?.hasImapCriteria == true

/** Key of a result row: unique across both sections. */
internal fun hitKey(section: String, key: SearchKey): String = "$section-${key.folderId}-${key.uid}"

/** True when flag filters are on and a shown match comes from the header index, whose flags may lag. */
internal fun flagsApproximate(filters: SearchFilterState, shown: List<SearchHit>): Boolean =
    (filters.unreadOnly || filters.starredOnly) && shown.any { it is SearchHit.Indexed }

// ---------------------------------------------------------------------- problems

/** The step ran out of time (a window, or the rest of a folder after repeated timeouts). */
internal fun tookTooLong(problem: StepProblem): Boolean =
    problem.outcome == StepOutcome.TIMED_OUT || (problem.outcome == StepOutcome.SKIPPED && problem.error == null)

/** The server cannot search for the words as typed (no plain-ASCII spelling it accepts). */
internal fun charsetRejected(problem: StepProblem): Boolean =
    problem.outcome == StepOutcome.FAILED && problem.error?.message == ServerSearch.CHARSET_REJECTED

/** The server failed the step; the folder may answer when asked again. A folder gone from the server is not a failure. */
internal fun failedOnServer(problem: StepProblem): Boolean =
    problem.outcome != StepOutcome.DONE && !tookTooLong(problem) && !charsetRejected(problem) &&
        !(problem.outcome == StepOutcome.SKIPPED && problem.error?.kind == MailErrorKind.NOT_FOUND)

/** Parts of the search skipped because they took too long. */
internal fun skippedParts(problems: List<StepProblem>): Int = problems.count(::tookTooLong)

/** Parts of the search the server failed. */
internal fun failedParts(problems: List<StepProblem>): Int = problems.count(::failedOnServer)

/** A problem worth asking the server about again. */
internal fun retryable(problem: StepProblem): Boolean = tookTooLong(problem) || failedOnServer(problem)

// ---------------------------------------------------------------------- index status

/** Header index coverage of the searched accounts, shown under the results while it is incomplete. */
data class IndexSummary(val indexed: Int, val total: Int, val running: Boolean) {
    /** About what indexing the rest downloads. */
    val remainingBytes: Long get() = (total - indexed).coerceAtLeast(0).toLong() * INDEX_BYTES_PER_MESSAGE
}

/**
 * Coverage of [accountIds] (every account when null), or null when there is nothing to tell:
 * the index is complete or holds every message it aims to, and no run is working on it.
 */
internal fun indexSummary(statuses: List<IndexStatus>, accountIds: Set<Long>?): IndexSummary? {
    val relevant = statuses.filter { accountIds == null || it.accountId in accountIds }
    if (relevant.isEmpty()) return null
    val total = relevant.sumOf { it.total.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val indexed = relevant.sumOf { it.indexed.toLong() }.coerceAtMost(total.toLong()).toInt()
    val running = relevant.any { it.running }
    if (total <= 0) return null
    if (!running && (relevant.all { it.complete } || indexed >= total)) return null
    return IndexSummary(indexed, total, running)
}

/** [count] with the locale's digit grouping, e.g. "12,345" or "12.345". */
internal fun formatCount(count: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(count)

// ---------------------------------------------------------------------- server search session

/** A phase run on the scope's folders, or on Trash and Junk ([bins]). */
data class SearchPart(val bins: Boolean, val phase: SearchPhase)

/**
 * The server search of one query in one scope, over every run it took: the headers (on their
 * own or as the start of the text search), the message text, Trash and Spam, retries. Pure: the
 * view model runs the [queue] front first and reports back with [onProgress] and [onFinished].
 *
 * @property id distinguishes sessions, so a late answer of a replaced one is ignored.
 * @property localKeys / [localMids] the device results when the first run started (see [mergeSections]).
 * @property hits everything finished runs found; see [allHits] for the running one too.
 * @property done / [binsDone] phases that finished on the scope's folders, on Trash and Junk.
 * @property includeBins the user asked for Trash and Spam too: later phases cover them as well.
 * @property problems of the finished runs, per part; replaced when the part runs again.
 * @property error that ended the last run; [failedRequests] is what Retry runs again then.
 * @property dropped matches found gone when opened.
 * @property refuted device results the finished runs ruled out (see [ServerSearchProgress.refuted]);
 *   [allRefuted] includes the running one's.
 */
data class ServerSession(
    val id: Long,
    val query: String,
    val scope: ServerSearchScope,
    val localKeys: Set<SearchKey> = emptySet(),
    val localMids: Set<String> = emptySet(),
    val hits: List<SearchHit> = emptyList(),
    val done: Set<SearchPhase> = emptySet(),
    val binsDone: Set<SearchPhase> = emptySet(),
    val includeBins: Boolean = false,
    val queue: List<ServerSearchRequest> = emptyList(),
    val progress: ServerSearchProgress? = null,
    val problems: Map<SearchPart, List<StepProblem>> = emptyMap(),
    val truncated: Boolean = false,
    val stopped: Boolean = false,
    val error: Throwable? = null,
    val failedRequests: List<ServerSearchRequest> = emptyList(),
    val dropped: Set<SearchKey> = emptySet(),
    val refuted: Set<SearchKey> = emptySet(),
) {
    val running: Boolean get() = queue.isNotEmpty()

    /** Device results ruled out so far, by the running search too: the local section leaves them out. */
    val allRefuted: Set<SearchKey>
        get() {
            val current = progress?.refuted.orEmpty()
            return if (current.isEmpty()) refuted else refuted + current
        }

    /** Every match so far, the running search's included, newest first. */
    val allHits: List<SearchHit>
        get() {
            val current = progress?.hits.orEmpty()
            val merged = if (current.isEmpty()) hits else mergeHits(hits, current)
            return if (dropped.isEmpty()) merged else merged.filter { it.key !in dropped }
        }

    /** Problems of the finished runs. */
    val allProblems: List<StepProblem> get() = problems.values.flatten()

    /** The message text of the scope's folders is searched, being searched or about to be. */
    val textRequested: Boolean get() = SearchPhase.TEXT in done || SearchPhase.TEXT in queued(bins = false)

    /** "Also search Trash and Spam" can be offered: a whole-account search whose headers were searched. */
    val binsOffered: Boolean get() = scope.folderId == null && !includeBins && SearchPhase.HEADERS in done

    /** Retry has something to run again. */
    val canRetry: Boolean get() = failedRequests.isNotEmpty() || retryRequests().isNotEmpty()

    /**
     * Queues [phases] (the text search includes the headers when they are not searched yet) for
     * the scope's folders, and for Trash and Junk when they are included, unless already done
     * or queued.
     */
    internal fun request(phases: Set<SearchPhase>): ServerSession {
        val wanted = (if (SearchPhase.TEXT in phases) phases + SearchPhase.HEADERS else phases) - SearchPhase.SNIPPETS
        return enqueue(main = wanted, bins = if (includeBins) wanted else emptySet())
    }

    /** Includes Trash and Junk: they get the phases the scope's folders got (at least the headers). */
    internal fun withBins(): ServerSession {
        if (scope.folderId != null || includeBins) return this
        val phases = done + queued(bins = false) + SearchPhase.HEADERS - SearchPhase.SNIPPETS
        return copy(includeBins = true).enqueue(main = emptySet(), bins = phases)
    }

    /** Runs again what failed: the runs an error ended, or else the parts that were skipped or failed. */
    internal fun retry(): ServerSession {
        if (failedRequests.isNotEmpty()) {
            return copy(queue = queue + failedRequests, failedRequests = emptyList(), error = null, stopped = false)
        }
        val retries = retryRequests().filter { it !in queue }
        if (retries.isEmpty()) return this
        return copy(queue = queue + retries, error = null, stopped = false)
    }

    internal fun onProgress(progress: ServerSearchProgress): ServerSession = copy(progress = progress)

    /**
     * [request] (the front of the queue) ended with [last] as its final progress, or with
     * [failure]. An error clears the queue (Retry runs it again); so does reaching the limit.
     */
    internal fun onFinished(request: ServerSearchRequest, last: ServerSearchProgress?, failure: Throwable? = null): ServerSession {
        val rest = if (queue.firstOrNull() == request) queue.drop(1) else queue - request
        val found = last?.hits.orEmpty()
        val merged = if (found.isEmpty()) hits else mergeHits(hits, found)
        val truncatedNow = truncated || last?.truncated == true
        val ruledOut = last?.refuted.orEmpty().let { if (it.isEmpty()) refuted else refuted + it }
        val error = failure ?: last?.error
        if (error != null) {
            return copy(
                hits = merged,
                refuted = ruledOut,
                queue = emptyList(),
                progress = null,
                truncated = truncatedNow,
                error = error,
                failedRequests = listOf(request) + rest,
            )
        }
        val bins = request.scope.trashAndJunkOnly
        val phases = request.phases - SearchPhase.SNIPPETS
        // A retry of a single folder only replaces that folder's problems.
        val retriedFolder = request.scope.folderId?.takeIf { it != scope.folderId }
        val updated = problems.toMutableMap()
        for (phase in phases) {
            val part = SearchPart(bins, phase)
            val kept = if (retriedFolder == null) emptyList() else problems[part].orEmpty().filter { it.folder.id != retriedFolder }
            val fresh = last?.problems.orEmpty().filter { it.phase == phase }
            if (kept.isEmpty() && fresh.isEmpty()) updated.remove(part) else updated[part] = kept + fresh
        }
        val full = found.size >= request.limit
        return copy(
            hits = merged,
            refuted = ruledOut,
            queue = if (full) emptyList() else rest,
            progress = null,
            done = if (bins) done else done + phases,
            binsDone = if (bins) binsDone + phases else binsDone,
            problems = updated,
            truncated = truncatedNow,
        )
    }

    /** The user stopped the search: what was found is kept, nothing more runs. */
    internal fun stop(): ServerSession {
        if (!running) return this
        val current = progress?.hits.orEmpty()
        return copy(
            hits = if (current.isEmpty()) hits else mergeHits(hits, current),
            refuted = allRefuted,
            queue = emptyList(),
            progress = null,
            stopped = true,
        )
    }

    /** [key] turned out to be gone from the server. */
    internal fun drop(key: SearchKey): ServerSession = copy(dropped = dropped + key)

    private fun binsScope(): ServerSearchScope = scope.copy(trashAndJunkOnly = true)

    /** Phases queued for the whole scope (or all of Trash and Junk); single-folder retries do not count. */
    private fun queued(bins: Boolean): Set<SearchPhase> =
        queue.filter { it.scope.trashAndJunkOnly == bins && it.scope.folderId == scope.folderId }
            .flatMapTo(HashSet()) { it.phases }

    private fun enqueue(main: Set<SearchPhase>, bins: Set<SearchPhase>): ServerSession {
        val added = ArrayList<ServerSearchRequest>(2)
        val mainPhases = main - done - queued(bins = false)
        if (mainPhases.isNotEmpty()) added += ServerSearchRequest(query, scope, mainPhases)
        val binPhases = if (scope.folderId == null) bins - binsDone - queued(bins = true) else emptySet()
        if (binPhases.isNotEmpty()) added += ServerSearchRequest(query, binsScope(), binPhases)
        if (added.isEmpty()) return this
        return copy(queue = queue + added, stopped = false, error = null, failedRequests = emptyList())
    }

    /** One run per scope (folders, bins) for its parts with retryable problems; a single folder's on its own. */
    private fun retryRequests(): List<ServerSearchRequest> = listOf(false, true).mapNotNull { bins ->
        val parts = problems.filterKeys { it.bins == bins }
            .mapValues { (_, list) -> list.filter(::retryable) }
            .filterValues { it.isNotEmpty() }
        if (parts.isEmpty()) return@mapNotNull null
        val folders = parts.values.flatten().map { it.folder }.distinctBy { it.id }
        val base = if (bins) binsScope() else scope
        val target = folders.singleOrNull()
            ?.takeIf { base.folderId == null }
            ?.let { base.copy(accountIds = listOf(it.accountId), folderId = it.id) }
            ?: base
        ServerSearchRequest(query, target, parts.keys.mapTo(HashSet()) { it.phase })
    }

    companion object {
        /** A new session; the device results [local] shown now keep their section. */
        internal fun start(id: Long, query: String, scope: ServerSearchScope, local: List<SearchHit>): ServerSession =
            ServerSession(
                id = id,
                query = query,
                scope = scope,
                localKeys = local.mapTo(HashSet()) { it.key },
                localMids = local.mapNotNullTo(HashSet(), ::messageIdOf),
            )
    }
}

/** Normalised form of a query for history and comparisons. */
internal fun normalizeQuery(query: String): String = query.trim().replace(Regex("\\s+"), " ")
