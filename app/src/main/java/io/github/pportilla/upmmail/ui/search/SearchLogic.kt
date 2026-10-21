package io.github.pportilla.upmmail.ui.search

import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchFilters

/*
 * Pure (Android-free) rules of the search screen, unit-tested on the JVM.
 */

/** Wait this long after the last keystroke before searching the local cache. */
internal const val SEARCH_DEBOUNCE_MILLIS = 250L

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

/** True if [message] passes the flag filters (server results are not filtered by the server). */
internal fun matchesFilters(message: MessageSummary, filters: SearchFilterState): Boolean =
    (!filters.unreadOnly || !message.isRead) &&
        (!filters.starredOnly || message.isStarred) &&
        (!filters.withAttachments || message.hasAttachments)

/** Local results and the extra ones the server found, each shown in its own section. */
internal data class MergedResults(
    val local: List<MessageSummary>,
    val fromServer: List<MessageSummary>,
)

/**
 * Merges [local] results with [server] results.
 *
 * Server results already shown locally when the server search ran ([localIdsAtServerSearch]) are
 * dropped, the rest form the "From server" section. When the local search is re-run later (the
 * server matches are cached by then) those messages stay in the server section, with the fresher
 * local copy, instead of jumping between sections. Flag filters apply to both sections.
 */
internal fun mergeResults(
    local: List<MessageSummary>,
    server: List<MessageSummary>,
    localIdsAtServerSearch: Set<Long>,
    filters: SearchFilterState,
): MergedResults {
    val freshById = local.associateBy { it.id }
    val seen = HashSet<Long>()
    val fromServer = server
        .asSequence()
        .filter { it.id !in localIdsAtServerSearch }
        .map { freshById[it.id] ?: it }
        .filter { seen.add(it.id) && matchesFilters(it, filters) }
        .toList()
    val serverIds = fromServer.mapTo(HashSet()) { it.id }
    return MergedResults(local = local.filter { it.id !in serverIds }, fromServer = fromServer)
}

/** Normalised form of a query for history and comparisons. */
internal fun normalizeQuery(query: String): String = query.trim().replace(Regex("\\s+"), " ")
