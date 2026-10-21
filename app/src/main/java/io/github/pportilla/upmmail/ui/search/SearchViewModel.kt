package io.github.pportilla.upmmail.ui.search

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.HitOpenResult
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchKey
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the search screen draws (the query text itself lives in [SearchViewModel.query]). */
data class SearchUiState(
    val filters: SearchFilterState = SearchFilterState(),
    /** The folder the screen was opened from, offered as the "This folder" scope. */
    val scopeFolder: Folder? = null,
    /** Query the shown local results belong to (blank: show recent searches). */
    val resultsQuery: String = "",
    /** A local search for the current text is pending (typing or running). */
    val searching: Boolean = false,
    /** Matches on this device: the cache and the header index. */
    val localResults: List<SearchHit> = emptyList(),
    /** Matches only the server search found. */
    val serverResults: List<SearchHit> = emptyList(),
    /** The server search of the current text and scope; null before one starts. */
    val server: ServerSession? = null,
    /** [serverResults] before the flag filters. */
    val serverOnlyCount: Int = 0,
    val recentSearches: List<String> = emptyList(),
    /** Folders of the results, for row details (recipients in Sent, folder tags). */
    val folders: Map<Long, Folder> = emptyMap(),
    /** Account address per account id, shown as "me". */
    val accountAddresses: Map<Long, String> = emptyMap(),
    val showSnippets: Boolean = true,
    /** The folder the search is limited to ("This folder"); null when every folder is searched. */
    val serverFolder: Folder? = null,
    /** Header index coverage of the searched accounts, while it is incomplete. */
    val index: IndexSummary? = null,
    /** The index-only match being downloaded to open it. */
    val openingKey: SearchKey? = null,
    /** Flag filters are on and some shown matches have flags from the header index, possibly out of date. */
    val approximateFlags: Boolean = false,
)

/** One-shot events for the screen. */
sealed interface SearchEvent {
    data class OpenMessage(val messageId: Long) : SearchEvent

    data class Info(@param:StringRes val message: Int) : SearchEvent

    data class Error(val error: Throwable) : SearchEvent
}

/**
 * Instant search on the device (debounced; the cache and the header index) with flag filters
 * and a scope, plus a progressive search on the server whose extra matches are appended in their
 * own section: the headers of every folder are searched by themselves when the device has few
 * matches, the message text on request (or the keyboard's search key).
 *
 * @param accountId account to search, null for all accounts.
 * @param folderId folder the screen was opened from (offered as scope), null for none.
 * @param coroutineScope replaces viewModelScope in tests.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val container: AppContainer,
    accountId: Long?,
    folderId: Long?,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope
    private val mail = container.mailRepository
    private val accountId: Long? = accountId?.takeIf { it > 0 }

    // The outbox (a negative virtual id) and "no folder" both mean no folder scope.
    private val folderId: Long? = folderId?.takeIf { it > 0 }

    private val queryFlow = MutableStateFlow("")
    private val filters = MutableStateFlow(SearchFilterState(thisFolderOnly = this.folderId != null))
    private val refreshTick = MutableStateFlow(0)
    private val local = MutableStateFlow(LocalResults("", emptyList()))
    private val session = MutableStateFlow<ServerSession?>(null)
    private val opening = MutableStateFlow<SearchKey?>(null)
    private val recents = MutableStateFlow<List<String>>(emptyList())
    private val scopeFolder = MutableStateFlow<Folder?>(null)
    private val folders = MutableStateFlow<Map<Long, Folder>>(emptyMap())
    private val eventChannel = Channel<SearchEvent>(Channel.BUFFERED)

    private var serverJob: Job? = null
    private var autoJob: Job? = null
    private var autoQuery: String? = null
    private var openJob: Job? = null
    private var openingQuery: String? = null
    private var pendingStart: PendingStart? = null
    private var sessionCount = 0L

    private data class LocalResults(val query: String, val items: List<SearchHit>)

    /** A server search being set up (its scope is being looked up). */
    private class PendingStart(val query: String, var phases: Set<SearchPhase>)

    /** One-shot events; collect them while the screen is shown. */
    val events: Flow<SearchEvent> = eventChannel.receiveAsFlow()

    /** The search field. Plain Compose state so the text field never lags behind typing. */
    var queryField: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** The text in the search field. */
    val query: String get() = queryField.text

    private val accounts: StateFlow<List<Account>> = container.accountRepository.accounts
        .catch { emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Starts empty so that the screen never waits for it.
    private val indexStatuses: Flow<List<IndexStatus>> = mail.observeIndexStatus()
        .onStart { emit(emptyList()) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(emptyList())
        }

    private val searchInputs = combine(
        queryFlow.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MILLIS }.map(::normalizeQuery).distinctUntilChanged(),
        filters.map { it.copy(thisFolderOnly = it.thisFolderOnly && this.folderId != null) }.distinctUntilChanged(),
        refreshTick,
    ) { q, f, _ -> q to f }

    val uiState: StateFlow<SearchUiState> = combine(
        combine(local, session, filters, queryFlow, opening, ::Quint),
        combine(recents, scopeFolder, folders, accounts, container.settingsRepository.settings, ::Quint),
        indexStatuses,
    ) { (localResults, serverSession, filterState, rawQuery, openingKey), (recentList, openedFrom, folderMap, accountList, settings), statuses ->
        val current = normalizeQuery(rawQuery)
        val server = serverSession?.takeIf { it.query == current }
        val sections = mergeSections(
            local = localResults.items,
            server = server?.allHits.orEmpty(),
            localKeysAtStart = server?.localKeys.orEmpty(),
            localMidsAtStart = server?.localMids.orEmpty(),
            filters = filterState,
        )
        val shownLocal = if (current.isEmpty()) emptyList() else sections.local
        val shownServer = if (current.isEmpty()) emptyList() else sections.fromServer
        val scoped = filterState.thisFolderOnly && openedFrom != null
        SearchUiState(
            filters = filterState,
            scopeFolder = openedFrom,
            resultsQuery = localResults.query,
            searching = current.isNotEmpty() && current != localResults.query,
            localResults = shownLocal,
            serverResults = shownServer,
            server = server,
            serverOnlyCount = sections.serverOnly,
            recentSearches = recentList,
            folders = folderMap,
            accountAddresses = accountList.associate { it.id to it.email },
            showSnippets = settings.showSnippets,
            serverFolder = if (scoped) openedFrom else null,
            index = indexSummary(statuses, searchedAccounts(openedFrom, filterState)),
            openingKey = openingKey,
            approximateFlags = flagsApproximate(filterState, shownLocal + shownServer),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    init {
        scope.launch {
            searchInputs
                .mapLatest { (q, f) ->
                    if (q.isEmpty()) {
                        LocalResults("", emptyList())
                    } else {
                        val items = try {
                            mail.searchLocalHits(q, toSearchFilters(f, accountId, folderId))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A malformed full-text expression or a DB hiccup: show no local matches.
                            emptyList()
                        }
                        LocalResults(q, items)
                    }
                }
                .collect { results ->
                    local.value = results
                    loadFolders(results.items)
                    scheduleAutoSearch(results)
                }
        }
        scope.launch {
            folderId?.let { id -> scopeFolder.value = runCatchingNonCancel { mail.getFolder(id) } }
        }
        reloadRecentSearches()
    }

    /** The user edited the field (text, cursor or selection). */
    fun onQueryFieldChange(value: TextFieldValue) {
        val textChanged = value.text != queryField.text
        queryField = value
        if (textChanged) onTextChanged(value.text)
    }

    /** Replaces the text, with the cursor at its end. */
    fun setQuery(text: String) {
        queryField = TextFieldValue(text, selection = TextRange(text.length))
        onTextChanged(text)
    }

    /** Local results follow after a short pause; a server search of another text is dropped. */
    private fun onTextChanged(value: String) {
        queryFlow.value = value
        val current = normalizeQuery(value)
        if (autoQuery != current) cancelAutoSearch()
        val serverQuery = session.value?.query ?: pendingStart?.query
        if (serverQuery != null && serverQuery != current) cancelServerSearch()
        if (openingQuery != null && openingQuery != current) cancelOpening()
        if (current.isEmpty()) reloadRecentSearches()
    }

    /** Keyboard search key: remember the query and search the message text on the server too. */
    fun onSubmit() {
        val current = normalizeQuery(query)
        if (current.isEmpty()) return
        rememberQuery(current)
        startServerSearch(setOf(SearchPhase.TEXT))
    }

    /** A recent search was tapped: search it again. */
    fun onRecentSearch(text: String) {
        setQuery(text)
        normalizeQuery(text).takeIf { it.isNotEmpty() }?.let(::rememberQuery)
    }

    fun clearRecentSearches() {
        recents.value = emptyList()
        scope.launch { runCatchingNonCancel { mail.clearRecentSearches() } }
    }

    fun setUnreadOnly(value: Boolean) = filters.update { it.copy(unreadOnly = value) }

    fun setStarredOnly(value: Boolean) = filters.update { it.copy(starredOnly = value) }

    fun setWithAttachments(value: Boolean) = filters.update { it.copy(withAttachments = value) }

    /** Switches between "this folder" and "all folders"; a server search belongs to one scope. */
    fun setThisFolderOnly(value: Boolean) {
        if (folderId == null || filters.value.thisFolderOnly == value) return
        filters.update { it.copy(thisFolderOnly = value) }
        cancelAutoSearch()
        cancelServerSearch()
        cancelOpening()
    }

    /** A result is being opened: remember the query, and refresh results when coming back. */
    fun onResultOpened() {
        val current = normalizeQuery(query)
        if (current.isNotEmpty()) rememberQuery(current)
    }

    /** A cached result is being opened directly: a download for another one is no longer wanted. */
    fun onCachedResultOpened() {
        cancelOpening()
        onResultOpened()
    }

    /** The screen is visible again (e.g. back from a message): refresh read / star state. */
    fun onResume() {
        if (normalizeQuery(query).isNotEmpty()) refreshTick.update { it + 1 }
    }

    // ------------------------------------------------------------------ server search

    /** "Search on server": subjects, senders and recipients of every folder in scope. */
    fun searchServerHeaders() {
        normalizeQuery(query).takeIf { it.isNotEmpty() }?.let(::rememberQuery)
        startServerSearch(setOf(SearchPhase.HEADERS))
    }

    /** "Search message text": the slower search inside the messages (after the headers, if not done yet). */
    fun searchServerText() {
        normalizeQuery(query).takeIf { it.isNotEmpty() }?.let(::rememberQuery)
        startServerSearch(setOf(SearchPhase.TEXT))
    }

    /** "Also search Trash and Spam". */
    fun searchServerBins() {
        val current = session.value?.takeIf { it.query == normalizeQuery(query) } ?: return
        session.value = current.withBins()
        runQueue()
    }

    /** Stops the server search; what it found stays. */
    fun stopServerSearch() {
        serverJob?.cancel()
        serverJob = null
        if (pendingStart != null) {
            pendingStart = null
            return
        }
        session.update { it?.stop() }
    }

    /** Runs again the parts of the server search that failed or took too long. */
    fun retryServerSearch() {
        val current = session.value?.takeIf { it.query == normalizeQuery(query) } ?: return
        session.value = current.retry()
        runQueue()
    }

    /** "Index now"; [allowMetered] once the user agreed to use mobile data for it. */
    fun indexNow(allowMetered: Boolean) = mail.startIndexingNow(allowMetered)

    /** Runs [phases] for the current text: added to its server search, or a new one in the current scope. */
    private fun startServerSearch(phases: Set<SearchPhase>) {
        val current = normalizeQuery(query)
        if (current.isEmpty()) return
        cancelAutoSearch()
        session.value?.takeIf { it.query == current }?.let { existing ->
            session.value = existing.request(phases)
            runQueue()
            return
        }
        pendingStart?.takeIf { it.query == current }?.let { pending ->
            pending.phases += phases
            return
        }
        cancelServerSearch()
        val pending = PendingStart(current, phases)
        pendingStart = pending
        val shown = local.value.takeIf { it.query == current }?.items.orEmpty()
        scope.launch {
            val searchScope = serverScope()
            if (pendingStart !== pending) return@launch
            pendingStart = null
            val id = ++sessionCount
            session.value = if (searchScope == null) {
                ServerSession(id, current, ServerSearchScope(emptyList()), error = MailException(MailErrorKind.NOT_FOUND, "No account to search"))
            } else {
                ServerSession.start(id, current, searchScope, shown).request(pending.phases)
            }
            runQueue()
        }
    }

    /** Runs the session's queued searches one after another, unless that is going on already. */
    private fun runQueue() {
        if (serverJob?.isActive == true) return
        val id = session.value?.takeIf { it.running }?.id ?: return
        serverJob = scope.launch {
            while (true) {
                val request = session.value?.takeIf { it.id == id }?.queue?.firstOrNull() ?: break
                var last: ServerSearchProgress? = null
                var failure: Throwable? = null
                try {
                    mail.searchServerProgress(request).collect { progress ->
                        last = progress
                        session.update { s -> if (s?.id == id) s.onProgress(progress) else s }
                        progress.folder?.let { folder -> if (folder.id !in folders.value) folders.update { it + (folder.id to folder) } }
                        loadFolders(progress.hits)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The search reports its failures as progress; this is a bug or a DB failure.
                    failure = e as? MailException ?: MailException(MailErrorKind.UNKNOWN, e.message, e)
                }
                session.update { s -> if (s?.id == id) s.onFinished(request, last, failure) else s }
            }
        }
    }

    private fun cancelServerSearch() {
        serverJob?.cancel()
        serverJob = null
        pendingStart = null
        session.value = null
    }

    /** Searches the server's headers by themselves shortly after few local results arrive. */
    private fun scheduleAutoSearch(results: LocalResults) {
        val q = results.query
        if (q.isEmpty() || q == autoQuery) return
        cancelAutoSearch()
        val searched = session.value?.query == q || pendingStart?.query == q
        if (!shouldAutoSearch(q, results.items.size, searched)) return
        autoQuery = q
        autoJob = scope.launch {
            delay(AUTO_SERVER_DELAY_MILLIS)
            autoJob = null
            autoQuery = null
            val current = normalizeQuery(query)
            if (current == q && session.value?.query != q && pendingStart?.query != q) startServerSearch(setOf(SearchPhase.HEADERS))
        }
    }

    private fun cancelAutoSearch() {
        autoJob?.cancel()
        autoJob = null
        autoQuery = null
    }

    /**
     * Where the server search looks: the screen's folder ("This folder"), else its account, else
     * every account with the last one used first.
     */
    private suspend fun serverScope(): ServerSearchScope? {
        if (filters.value.thisFolderOnly && folderId != null) {
            val folder = scopeFolder.value ?: runCatchingNonCancel { mail.getFolder(folderId) } ?: return null
            return ServerSearchScope(listOf(folder.accountId), folderId = folder.id)
        }
        accountId?.let { return ServerSearchScope(listOf(it)) }
        val list = accounts.value.ifEmpty { runCatchingNonCancel { container.accountRepository.accounts.first() }.orEmpty() }
        if (list.isEmpty()) return null
        val last = container.settingsRepository.settings.value.lastAccountId
        return ServerSearchScope(list.sortedBy { if (it.id == last) 0 else 1 }.map { it.id })
    }

    /** Accounts whose index coverage is shown: those searched; null for every account. */
    private fun searchedAccounts(openedFrom: Folder?, filterState: SearchFilterState): Set<Long>? {
        accountId?.let { return setOf(it) }
        if (filterState.thisFolderOnly && openedFrom != null) return setOf(openedFrom.accountId)
        return null
    }

    // ------------------------------------------------------------------ opening results

    /**
     * A match known only from the header index was tapped: it is downloaded first (the row shows
     * progress meanwhile), then opened.
     */
    fun openIndexedHit(hit: SearchHit.Indexed) {
        if (opening.value != null) return
        onResultOpened()
        opening.value = hit.key
        openingQuery = normalizeQuery(query)
        openJob = scope.launch {
            val result = mail.openSearchHit(hit)
            opening.value = null
            openingQuery = null
            openJob = null
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is HitOpenResult.Opened -> eventChannel.send(SearchEvent.OpenMessage(outcome.messageId))
                        HitOpenResult.Gone -> {
                            session.update { it?.drop(hit.key) }
                            refreshTick.update { it + 1 }
                            eventChannel.send(SearchEvent.Info(R.string.search_hit_gone))
                        }
                        HitOpenResult.PendingChange -> eventChannel.send(SearchEvent.Info(R.string.search_hit_pending))
                    }
                },
                onFailure = { eventChannel.send(SearchEvent.Error(it)) },
            )
        }
    }

    /** The user moved on (another text or scope): the match being opened is not wanted any more. */
    private fun cancelOpening() {
        openJob?.cancel()
        openJob = null
        openingQuery = null
        opening.value = null
    }

    // ------------------------------------------------------------------ helpers

    private fun rememberQuery(text: String) {
        scope.launch {
            runCatchingNonCancel { mail.addRecentSearch(text) }
            reloadRecentSearchesNow()
        }
    }

    private fun reloadRecentSearches() {
        scope.launch { reloadRecentSearchesNow() }
    }

    private suspend fun reloadRecentSearchesNow() {
        runCatchingNonCancel { mail.recentSearches() }?.let { list -> recents.value = list.filter { it.isNotBlank() }.distinct() }
    }

    /** Looks up the folders of [hits] not known yet. */
    private suspend fun loadFolders(hits: List<SearchHit>) {
        val missing = hits.asSequence().map { it.key.folderId }.distinct().filter { it !in folders.value }.toList()
        if (missing.isEmpty()) return
        val found = missing.mapNotNull { id -> runCatchingNonCancel { mail.getFolder(id) } }
        if (found.isNotEmpty()) folders.update { current -> current + found.associateBy { it.id } }
    }
}

/** Runs [block], turning failures (but not cancellation) into null. */
private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
