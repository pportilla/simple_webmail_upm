package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.IndexFolderState
import io.github.pportilla.upmmail.mail.FolderProbe
import io.github.pportilla.upmmail.mail.SearchRange
import io.github.pportilla.upmmail.mail.ServerQuery
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchScope

/*
 * How a server search is cut into steps: pure functions, used by [ServerSearch].
 *
 * A step searches one window of a folder: a range of message sequence numbers (exact counts, no
 * uid list to download, stable while the folder stays selected) or the uids from a given one on.
 * Windows are planned newest first, so the most recent matches arrive first.
 */

/** Messages per HEADERS step (subject, from, to, cc): servers answer these quickly. */
internal const val HEADER_WINDOW = 10_000

/** Messages per TEXT step: a server without a full-text index reads every message body. */
internal const val TEXT_WINDOW = 2_000

/** A window that timed out is split in halves, but never below this size. */
internal const val MIN_WINDOW = 500

/** How many times a window may be halved. */
internal const val MAX_SPLIT_DEPTH = 2

/** TEXT tier 1: this many newest windows of every folder before anything older. */
internal const val TIER1_WINDOWS = 1

/** TEXT tier 2: the next windows of each folder, one folder after another. */
internal const val TIER2_WINDOWS = 4

/** One server command of a search plan: [range] of [folder], in [phase]. */
internal data class PlannedStep(
    val folder: Folder,
    val phase: SearchPhase,
    val range: SearchRange,
    /** How many times this window was halved. */
    val depth: Int = 0,
)

/**
 * The phases of [requested] that a server search of [query] runs: HEADERS as asked, TEXT (and
 * SNIPPETS after it) only for a query with words. A query of senders, recipients, subject, dates
 * and flags only, with or without exclusions, is answered by the HEADERS phase: the message text
 * cannot add a match to it, and exclusions are looked for in the text in every phase
 * ([ServerQuery.excludeInText]). Empty when there is nothing to search for ([query] is null).
 */
internal fun phasesToRun(query: ServerQuery?, requested: Set<SearchPhase>): Set<SearchPhase> {
    if (query == null) return emptySet()
    val result = HashSet<SearchPhase>(2)
    if (SearchPhase.HEADERS in requested) result += SearchPhase.HEADERS
    if (SearchPhase.TEXT in requested && query.hasWords) result += SearchPhase.TEXT
    return result
}

/**
 * Whether searching the message text can find more for the search box text [query] than the
 * headers: only when it has words (see [phasesToRun]). For any other query, the TEXT phase of a
 * request is skipped: the run finishes after its HEADERS phase (at once if it asked for TEXT
 * only), with no TEXT or SNIPPETS progress, so there is no "Search message text" to offer.
 */
internal fun searchesMessageText(query: String): Boolean = ServerQuery.parse(query)?.hasWords == true

/** Folder roles in the order a search (and the indexer) visits them; other folders keep drawer order. */
internal val SEARCH_ROLE_ORDER = listOf(
    FolderRole.INBOX,
    FolderRole.SENT,
    FolderRole.ARCHIVE,
    FolderRole.OTHER,
    FolderRole.TRASH,
    FolderRole.JUNK,
    FolderRole.DRAFTS,
)

/**
 * The folders of [folders] (one account, drawer order) that a search of [scope] looks in, in
 * search order: Inbox, Sent, Archive, then the other folders in drawer order. Folders that
 * cannot be selected are never searched. Folders the header index leaves out (not in
 * [indexable]: Drafts, virtual and shared folders) are only searched when the scope names them.
 * Without a folder in the scope, Trash and Junk are searched only when [ServerSearchScope.trashAndJunkOnly],
 * and then nothing else.
 */
internal fun folderOrder(folders: List<Folder>, indexable: Set<Long>, scope: ServerSearchScope): List<Folder> {
    val scopedId = scope.folderId
    val chosen = folders.filter { folder ->
        when {
            !folder.selectable -> false
            scopedId != null -> folder.id == scopedId
            folder.id !in indexable -> false
            scope.trashAndJunkOnly -> folder.role == FolderRole.TRASH || folder.role == FolderRole.JUNK
            else -> folder.role != FolderRole.TRASH && folder.role != FolderRole.JUNK
        }
    }
    // A stable sort: folders of one role keep the drawer order they came in.
    return chosen.sortedBy { SEARCH_ROLE_ORDER.indexOf(it.role) }
}

/**
 * Sequence windows of [window] messages covering 1..[messageCount] exactly, newest first; the
 * oldest window may be smaller. Empty for an empty folder.
 */
internal fun seqWindows(messageCount: Int, window: Int): List<SearchRange.Seq> {
    if (messageCount <= 0) return emptyList()
    val size = window.coerceAtLeast(1)
    val result = ArrayList<SearchRange.Seq>(messageCount / size + 1)
    var last = messageCount
    while (last >= 1) {
        val first = maxOf(1, last - size + 1)
        result += SearchRange.Seq(first, last)
        last = first - 1
    }
    return result
}

/**
 * The HEADERS steps of [folder], just examined as [probe]. When the header index of the folder
 * is complete (and of the same UIDVALIDITY), it already answers for everything up to its
 * UIDNEXT, so only newer mail is searched, plus the messages older than the index keeps
 * ([cap]) when it is capped. Otherwise the whole folder, in windows of [HEADER_WINDOW].
 */
internal fun headerSteps(folder: Folder, probe: FolderProbe, state: IndexFolderState?, cap: Int): List<PlannedStep> {
    val ranges: List<SearchRange> = if (state != null && state.uidValidity == probe.uidValidity && state.complete) {
        val newer = listOf(SearchRange.UidsFrom(maxOf(1L, state.uidNext)))
        if (state.capped) newer + seqWindows(probe.messageCount - cap, HEADER_WINDOW) else newer
    } else {
        seqWindows(probe.messageCount, HEADER_WINDOW)
    }
    return ranges.map { PlannedStep(folder, SearchPhase.HEADERS, it) }
}

/**
 * The TEXT steps of the [probed] folders (in search order), in tiers so that recent mail of
 * every folder is searched before old mail of any: tier 1 is the newest window of each folder,
 * tier 2 the next [TIER2_WINDOWS] windows of each folder in turn, tier 3 the rest of each
 * folder in turn. A folder's windows within a tier run back to back, on one selected folder.
 */
internal fun textSteps(probed: List<Pair<Folder, FolderProbe>>): List<PlannedStep> {
    val windows = probed.map { (folder, probe) ->
        folder to seqWindows(probe.messageCount, TEXT_WINDOW)
    }
    val result = ArrayList<PlannedStep>()
    fun addTier(from: Int, to: Int) {
        for ((folder, ranges) in windows) {
            for (index in from until minOf(to, ranges.size)) result += PlannedStep(folder, SearchPhase.TEXT, ranges[index])
        }
    }
    addTier(0, TIER1_WINDOWS)
    addTier(TIER1_WINDOWS, TIER1_WINDOWS + TIER2_WINDOWS)
    addTier(TIER1_WINDOWS + TIER2_WINDOWS, Int.MAX_VALUE)
    return result
}

/**
 * The two halves of a sequence window that timed out, the newer half first; null when it
 * cannot be split: not a sequence window, halves smaller than [MIN_WINDOW], or already halved
 * [MAX_SPLIT_DEPTH] times.
 */
internal fun split(step: PlannedStep): List<PlannedStep>? {
    val range = step.range as? SearchRange.Seq ?: return null
    val size = range.last - range.first + 1
    if (size < 2 * MIN_WINDOW || step.depth >= MAX_SPLIT_DEPTH) return null
    val middle = range.first + size / 2
    return listOf(
        step.copy(range = SearchRange.Seq(middle, range.last), depth = step.depth + 1),
        step.copy(range = SearchRange.Seq(range.first, middle - 1), depth = step.depth + 1),
    )
}

/**
 * [step] split until its windows have been halved [depth] times (or cannot be split further),
 * newest first: once a window of a folder had to be halved, its later windows are planned at
 * that size straight away instead of timing out first.
 */
internal fun refine(step: PlannedStep, depth: Int): List<PlannedStep> {
    if (step.depth >= depth) return listOf(step)
    val halves = split(step) ?: return listOf(step)
    return halves.flatMap { refine(it, depth) }
}
