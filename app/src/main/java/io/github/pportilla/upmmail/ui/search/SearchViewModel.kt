package io.github.pportilla.upmmail.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the "Search on server" part. */
sealed interface ServerSearchState {
    data object Idle : ServerSearchState

    data class Running(val query: String) : ServerSearchState

    /** Finished for [query]; [results] are all server matches, before merging. */
    data class Done(val query: String, val results: List<MessageSummary>, val localIdsBefore: Set<Long>) : ServerSearchState

    data class Failed(val query: String, val error: Throwable) : ServerSearchState
}

/** Everything the search screen draws (the query text itself lives in [SearchViewModel.query]). */
data class SearchUiState(
    val filters: SearchFilterState = SearchFilterState(),
    /** The folder the screen was opened from, offered as the "This folder" scope. */
    val scopeFolder: Folder? = null,
    /** Query the shown local results belong to (blank: show recent searches). */
    val resultsQuery: String = "",
    /** A local search for the current text is pending (typing or running). */
    val searching: Boolean = false,
    val localResults: List<MessageSummary> = emptyList(),
    val serverResults: List<MessageSummary> = emptyList(),
    val server: ServerSearchState = ServerSearchState.Idle,
    val recentSearches: List<String> = emptyList(),
    /** Folders of the results, for row details (recipients in Sent, folder tags). */
    val folders: Map<Long, Folder> = emptyMap(),
    /** Account address per account id, shown as "me". */
    val accountAddresses: Map<Long, String> = emptyMap(),
    val showSnippets: Boolean = true,
    /** Folder the server search looks in; null when the account's INBOX is searched. */
    val serverFolder: Folder? = null,
)

/**
 * Instant local search (debounced) with flag filters and a scope, plus an explicit IMAP search on
 * the server whose extra matches are appended in their own section.
 *
 * @param accountId account to search, null for all accounts.
 * @param folderId folder the screen was opened from (offered as scope), null for none.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val container: AppContainer,
    accountId: Long?,
    folderId: Long?,
) : ViewModel() {

    private val mail = container.mailRepository
    private val accountId: Long? = accountId?.takeIf { it > 0 }

    // The outbox (a negative virtual id) and "no folder" both mean no folder scope.
    private val folderId: Long? = folderId?.takeIf { it > 0 }

    private val queryFlow = MutableStateFlow("")
    private val filters = MutableStateFlow(SearchFilterState(thisFolderOnly = this.folderId != null))
    private val refreshTick = MutableStateFlow(0)
    private val local = MutableStateFlow(LocalResults("", emptyList()))
    private val server = MutableStateFlow<ServerSearchState>(ServerSearchState.Idle)
    private val recents = MutableStateFlow<List<String>>(emptyList())
    private val scopeFolder = MutableStateFlow<Folder?>(null)
    private val folders = MutableStateFlow<Map<Long, Folder>>(emptyMap())
    private var serverJob: Job? = null

    private data class LocalResults(val query: String, val items: List<MessageSummary>)

    /** The search field. Plain Compose state so the text field never lags behind typing. */
    var queryField: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** The text in the search field. */
    val query: String get() = queryField.text

    private val accounts: StateFlow<List<Account>> = container.accountRepository.accounts
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val searchInputs = combine(
        queryFlow.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MILLIS }.map(::normalizeQuery).distinctUntilChanged(),
        filters.map { it.copy(thisFolderOnly = it.thisFolderOnly && this.folderId != null) }.distinctUntilChanged(),
        refreshTick,
    ) { q, f, _ -> q to f }

    val uiState: StateFlow<SearchUiState> = combine(
        combine(local, server, filters, queryFlow, ::Quad),
        combine(recents, scopeFolder, folders, accounts, container.settingsRepository.settings, ::Quint),
    ) { (localResults, serverState, filterState, rawQuery), (recentList, scope, folderMap, accountList, settings) ->
        val current = normalizeQuery(rawQuery)
        val serverDone = (serverState as? ServerSearchState.Done)?.takeIf { it.query == current }
        val merged = mergeResults(
            local = localResults.items,
            server = serverDone?.results.orEmpty(),
            localIdsAtServerSearch = serverDone?.localIdsBefore.orEmpty(),
            filters = filterState,
        )
        SearchUiState(
            filters = filterState,
            scopeFolder = scope,
            resultsQuery = localResults.query,
            searching = current.isNotEmpty() && current != localResults.query,
            localResults = if (current.isEmpty()) emptyList() else merged.local,
            serverResults = if (current.isEmpty()) emptyList() else merged.fromServer,
            server = serverState,
            recentSearches = recentList,
            folders = folderMap,
            accountAddresses = accountList.associate { it.id to it.email },
            showSnippets = settings.showSnippets,
            serverFolder = if (filterState.thisFolderOnly) scope else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    init {
        viewModelScope.launch {
            searchInputs
                .mapLatest { (q, f) ->
                    if (q.isEmpty()) {
                        LocalResults("", emptyList())
                    } else {
                        val items = try {
                            mail.searchLocal(q, toSearchFilters(f, accountId, folderId))
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
                }
        }
        viewModelScope.launch {
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

    /** Local results follow after a short pause; server results of another query are dropped. */
    private fun onTextChanged(value: String) {
        queryFlow.value = value
        val current = normalizeQuery(value)
        val serverState = server.value
        val serverQuery = when (serverState) {
            is ServerSearchState.Running -> serverState.query
            is ServerSearchState.Done -> serverState.query
            is ServerSearchState.Failed -> serverState.query
            ServerSearchState.Idle -> null
        }
        if (serverQuery != null && serverQuery != current) {
            serverJob?.cancel()
            server.value = ServerSearchState.Idle
        }
        if (current.isEmpty()) reloadRecentSearches()
    }

    /** IME search action: remember the query. */
    fun onSubmit() {
        val current = normalizeQuery(query)
        if (current.isEmpty()) return
        rememberQuery(current)
    }

    /** A recent search was tapped: search it again. */
    fun onRecentSearch(text: String) {
        setQuery(text)
        onSubmit()
    }

    fun clearRecentSearches() {
        recents.value = emptyList()
        viewModelScope.launch { runCatchingNonCancel { mail.clearRecentSearches() } }
    }

    fun setUnreadOnly(value: Boolean) = filters.update { it.copy(unreadOnly = value) }

    fun setStarredOnly(value: Boolean) = filters.update { it.copy(starredOnly = value) }

    fun setWithAttachments(value: Boolean) = filters.update { it.copy(withAttachments = value) }

    /** Switches between "this folder" and "all folders"; server results belong to one scope. */
    fun setThisFolderOnly(value: Boolean) {
        if (folderId == null || filters.value.thisFolderOnly == value) return
        filters.update { it.copy(thisFolderOnly = value) }
        serverJob?.cancel()
        server.value = ServerSearchState.Idle
    }

    /** A result is being opened: remember the query, and refresh results when coming back. */
    fun onResultOpened() {
        val current = normalizeQuery(query)
        if (current.isNotEmpty()) rememberQuery(current)
    }

    /** The screen is visible again (e.g. back from a message): refresh read / star state. */
    fun onResume() {
        if (normalizeQuery(query).isNotEmpty()) refreshTick.update { it + 1 }
    }

    /** Runs an IMAP search for the current text in the selected folder (or the INBOX). */
    fun searchOnServer() {
        val current = normalizeQuery(query)
        if (current.isEmpty()) return
        serverJob?.cancel()
        val localIds = local.value.takeIf { it.query == current }?.items?.mapTo(HashSet()) { it.id } ?: emptySet()
        server.value = ServerSearchState.Running(current)
        rememberQuery(current)
        serverJob = viewModelScope.launch {
            val scope = if (filters.value.thisFolderOnly) folderId else null
            val account = serverAccountId()
            val result = if (account == null) {
                Result.failure(MailException(MailErrorKind.NOT_FOUND, "No account to search"))
            } else {
                mail.searchServer(current, account, scope)
            }
            server.value = result.fold(
                onSuccess = { found ->
                    loadFolders(found)
                    ServerSearchState.Done(current, found, localIds)
                },
                onFailure = { ServerSearchState.Failed(current, it) },
            )
        }
    }

    private suspend fun serverAccountId(): Long? {
        accountId?.let { return it }
        scopeFolder.value?.let { return it.accountId }
        folderId?.let { id -> runCatchingNonCancel { mail.getFolder(id) }?.let { return it.accountId } }
        val list = accounts.value.ifEmpty { runCatchingNonCancel { container.accountRepository.accounts.first() }.orEmpty() }
        val last = container.settingsRepository.settings.value.lastAccountId
        return (list.firstOrNull { it.id == last } ?: list.firstOrNull())?.id
    }

    private fun rememberQuery(text: String) {
        viewModelScope.launch {
            runCatchingNonCancel { mail.addRecentSearch(text) }
            reloadRecentSearchesNow()
        }
    }

    private fun reloadRecentSearches() {
        viewModelScope.launch { reloadRecentSearchesNow() }
    }

    private suspend fun reloadRecentSearchesNow() {
        runCatchingNonCancel { mail.recentSearches() }?.let { list -> recents.value = list.filter { it.isNotBlank() }.distinct() }
    }

    /** Looks up the folders of [messages] not known yet. */
    private suspend fun loadFolders(messages: List<MessageSummary>) {
        val missing = messages.asSequence().map { it.folderId }.distinct().filter { it !in folders.value }.toList()
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
