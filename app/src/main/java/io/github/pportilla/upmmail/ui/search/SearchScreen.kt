package io.github.pportilla.upmmail.ui.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.components.errorMessage
import io.github.pportilla.upmmail.ui.components.folderDisplayName
import io.github.pportilla.upmmail.ui.components.folderIcon
import io.github.pportilla.upmmail.ui.components.formatFileSize
import io.github.pportilla.upmmail.ui.mailbox.MailboxEmptyState
import io.github.pportilla.upmmail.ui.mailbox.MessageListItem
import io.github.pportilla.upmmail.ui.mailbox.showsRecipients
import io.github.pportilla.upmmail.ui.navigation.AppNavigator

/**
 * Mail search: instant results from this device while typing (the cache and the header index),
 * flag filters, a folder scope and a progressive search on the server below them, with its
 * progress and a Stop button. With an empty query it shows recent searches.
 *
 * @param accountId account to search, null for every account.
 * @param folderId folder the search was opened from, offered as the "This folder" scope.
 */
@Composable
fun SearchScreen(accountId: Long?, folderId: Long?, navigator: AppNavigator) {
    val container = appContainer()
    val viewModel = viewModel { SearchViewModel(container, accountId, folderId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val currentNavigator by rememberUpdatedState(navigator)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val resultsState = rememberLazyListState()
    val recentsState = rememberLazyListState()

    // Focus the field (and show the keyboard) only the first time, not when coming back from a result.
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoFocused) {
            autoFocused = true
            if (focusRequester.requestFocusSafely()) keyboard?.show()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchEvent.OpenMessage -> currentNavigator.openMessage(event.messageId)
                is SearchEvent.Info -> snackbar.show(context.getString(event.message))
                is SearchEvent.Error -> snackbar.show(context.errorMessage(event.error))
            }
        }
    }

    // "Index now" on mobile data asks first.
    var confirmIndexBytes by rememberSaveable { mutableStateOf<Long?>(null) }
    confirmIndexBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = { confirmIndexBytes = null },
            title = { Text(stringResource(R.string.search_index_confirm_title)) },
            text = { Text(stringResource(R.string.search_index_confirm_body, formatFileSize(bytes))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmIndexBytes = null
                        viewModel.indexNow(allowMetered = true)
                    },
                ) { Text(stringResource(R.string.search_index_now)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmIndexBytes = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Results of a different query start at the top; coming back from a result keeps the position.
    var shownResultsQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.resultsQuery) {
        val previous = shownResultsQuery
        shownResultsQuery = state.resultsQuery
        if (previous.isNotEmpty() && previous != state.resultsQuery) resultsState.scrollToItem(0)
    }

    // Scrolling a list means the user is reading: get the keyboard out of the way.
    val scrolling = resultsState.isScrollInProgress || recentsState.isScrollInProgress
    LaunchedEffect(scrolling) {
        if (scrolling) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    val hideKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                field = viewModel.queryField,
                state = state,
                focusRequester = focusRequester,
                onFieldChange = viewModel::onQueryFieldChange,
                onClear = { viewModel.setQuery("") },
                onSubmit = {
                    viewModel.onSubmit()
                    hideKeyboard()
                },
                onBack = navigator::back,
                onUnreadChange = viewModel::setUnreadOnly,
                onStarredChange = viewModel::setStarredOnly,
                onAttachmentsChange = viewModel::setWithAttachments,
                onScopeChange = viewModel::setThisFolderOnly,
            )
        },
        snackbarHost = { AppSnackbarHost() },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        val query = viewModel.query
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (query.isBlank()) {
                RecentSearches(
                    recents = state.recentSearches,
                    listState = recentsState,
                    onSearch = { text ->
                        viewModel.onRecentSearch(text)
                        hideKeyboard()
                    },
                    onFill = { text ->
                        viewModel.setQuery("$text ")
                        focusRequester.requestFocusSafely()
                    },
                    onClear = viewModel::clearRecentSearches,
                )
            } else {
                SearchResults(
                    state = state,
                    listState = resultsState,
                    onOpen = { hit ->
                        when (hit) {
                            is SearchHit.Cached -> {
                                viewModel.onCachedResultOpened()
                                navigator.openMessage(hit.message.id)
                            }
                            is SearchHit.Indexed -> viewModel.openIndexedHit(hit)
                        }
                    },
                    actions = ServerActions(
                        searchHeaders = {
                            hideKeyboard()
                            viewModel.searchServerHeaders()
                        },
                        searchText = {
                            hideKeyboard()
                            viewModel.searchServerText()
                        },
                        searchBins = viewModel::searchServerBins,
                        stop = viewModel::stopServerSearch,
                        retry = viewModel::retryServerSearch,
                        indexNow = {
                            val remaining = state.index?.remainingBytes ?: 0L
                            if (context.isOnMeteredNetwork()) confirmIndexBytes = remaining else viewModel.indexNow(allowMetered = false)
                        },
                    ),
                )
                if (state.searching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    field: TextFieldValue,
    state: SearchUiState,
    focusRequester: FocusRequester,
    onFieldChange: (TextFieldValue) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onUnreadChange: (Boolean) -> Unit,
    onStarredChange: (Boolean) -> Unit,
    onAttachmentsChange: (Boolean) -> Unit,
    onScopeChange: (Boolean) -> Unit,
) {
    val scope = state.scopeFolder
    val placeholder = if (scope != null && state.filters.thisFolderOnly) {
        stringResource(R.string.search_hint_folder, folderDisplayName(scope))
    } else {
        stringResource(R.string.search_hint)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                TextField(
                    value = field,
                    onValueChange = onFieldChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (field.text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onClear()
                            focusRequester.requestFocusSafely()
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            }
            FilterChips(
                filters = state.filters,
                scopeFolderName = scope?.let { folderDisplayName(it) },
                scopeFolderIcon = scope?.let { folderIcon(it.role) },
                onUnreadChange = onUnreadChange,
                onStarredChange = onStarredChange,
                onAttachmentsChange = onAttachmentsChange,
                onScopeChange = onScopeChange,
            )
            if (state.approximateFlags) {
                Text(
                    text = stringResource(R.string.search_flags_approximate),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FilterChips(
    filters: SearchFilterState,
    scopeFolderName: String?,
    scopeFolderIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onUnreadChange: (Boolean) -> Unit,
    onStarredChange: (Boolean) -> Unit,
    onAttachmentsChange: (Boolean) -> Unit,
    onScopeChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scopeFolderName != null) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menuOpen = true },
                    label = {
                        Text(
                            if (filters.thisFolderOnly) {
                                stringResource(R.string.search_scope_this_folder)
                            } else {
                                stringResource(R.string.search_scope_all_folders)
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (filters.thisFolderOnly && scopeFolderIcon != null) scopeFolderIcon else Icons.Outlined.FolderCopy,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_scope_this_folder_named, scopeFolderName)) },
                        leadingIcon = { if (filters.thisFolderOnly) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onScopeChange(true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.search_scope_all_folders)) },
                        leadingIcon = { if (!filters.thisFolderOnly) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onScopeChange(false)
                        },
                    )
                }
            }
        }
        ToggleChip(
            selected = filters.unreadOnly,
            label = stringResource(R.string.search_filter_unread),
            icon = Icons.Outlined.MarkEmailUnread,
            onChange = onUnreadChange,
        )
        ToggleChip(
            selected = filters.starredOnly,
            label = stringResource(R.string.search_filter_starred),
            icon = Icons.Outlined.StarOutline,
            onChange = onStarredChange,
        )
        ToggleChip(
            selected = filters.withAttachments,
            label = stringResource(R.string.search_filter_attachments),
            icon = Icons.Outlined.AttachFile,
            onChange = onAttachmentsChange,
        )
    }
}

@Composable
private fun ToggleChip(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onChange: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = if (selected) Icons.Filled.Check else icon,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun RecentSearches(
    recents: List<String>,
    listState: LazyListState,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (recents.isEmpty()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "hint") {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    MailboxEmptyState(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.search_empty_title),
                        body = stringResource(R.string.search_empty_body),
                    )
                }
            }
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_recent_title),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_history)) }
            }
        }
        items(recents, key = { "recent-$it" }) { text ->
            ListItem(
                modifier = Modifier.clickable { onSearch(text) },
                leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                headlineContent = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    IconButton(onClick = { onFill(text) }) {
                        Icon(Icons.Outlined.NorthWest, contentDescription = stringResource(R.string.search_edit_recent, text))
                    }
                },
            )
        }
    }
}

/** What the server part of the results can ask for. */
private class ServerActions(
    val searchHeaders: () -> Unit,
    val searchText: () -> Unit,
    val searchBins: () -> Unit,
    val stop: () -> Unit,
    val retry: () -> Unit,
    val indexNow: () -> Unit,
)

@Composable
private fun SearchResults(
    state: SearchUiState,
    listState: LazyListState,
    onOpen: (SearchHit) -> Unit,
    actions: ServerActions,
) {
    val noLocalMatches = !state.searching && state.localResults.isEmpty() && state.resultsQuery.isNotEmpty()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (noLocalMatches) {
            item(key = "no-local") {
                MailboxEmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_no_local_title),
                    body = stringResource(R.string.search_no_local_body),
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        items(state.localResults, key = { hitKey(SECTION_LOCAL, it.key) }, contentType = { "message" }) { hit ->
            SearchResultRow(hit, state, onOpen)
        }
        if (state.resultsQuery.isNotEmpty() || state.server != null) {
            item(key = "server", contentType = "server") {
                ServerSection(state = state, actions = actions)
            }
        }
        items(state.serverResults, key = { hitKey(SECTION_SERVER, it.key) }, contentType = { "message" }) { hit ->
            SearchResultRow(hit, state, onOpen)
        }
        state.server?.let { server ->
            item(key = "server-footer", contentType = "server-footer") {
                ServerFooter(server = server, state = state, actions = actions)
            }
        }
        state.index?.let { index ->
            item(key = "index", contentType = "index") {
                IndexStatusRow(index = index, onIndexNow = actions.indexNow)
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, state: SearchUiState, onOpen: (SearchHit) -> Unit) {
    val folder = state.folders[hit.key.folderId]
    val opening = hit.key == state.openingKey
    Column(Modifier.fillMaxWidth()) {
        MessageListItem(
            message = hit.display,
            // While it downloads, the row does nothing more.
            onClick = { if (!opening) onOpen(hit) },
            showRecipients = showsRecipients(folder?.role),
            showSnippet = state.showSnippets,
            ownAddress = state.accountAddresses[hit.accountId],
            highlightQuery = state.resultsQuery,
            folderLabel = if (!state.filters.thisFolderOnly && folder != null) folderDisplayName(folder) else null,
        )
        if (opening) {
            val label = stringResource(R.string.search_hit_opening)
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/** Header of the server part: the button that starts it, its progress, or how it ended. */
@Composable
private fun ServerSection(state: SearchUiState, actions: ServerActions) {
    val server = state.server
    Column(Modifier.fillMaxWidth()) {
        if (server == null) {
            ServerButton(
                label = stringResource(R.string.search_on_server),
                icon = Icons.Outlined.CloudQueue,
                hint = state.serverFolder?.let { stringResource(R.string.search_on_server_hint, folderDisplayName(it)) }
                    ?: stringResource(R.string.search_on_server_hint_all),
                enabled = state.resultsQuery.isNotEmpty(),
                onClick = actions.searchHeaders,
            )
            return@Column
        }
        LabeledDivider(stringResource(R.string.search_from_server))
        val error = server.error
        when {
            server.running -> ServerProgressRow(progress = server.progress, onStop = actions.stop)
            error != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = LocalContext.current.errorMessage(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (server.canRetry) TextButton(onClick = actions.retry) { Text(stringResource(R.string.action_retry)) }
            }
            server.stopped -> NoteText(stringResource(R.string.search_server_stopped))
        }
    }
}

/** Spinner, what is being searched now and a Stop button. */
@Composable
private fun ServerProgressRow(progress: ServerSearchProgress?, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = progressText(progress),
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onStop) { Text(stringResource(R.string.search_server_stop)) }
    }
}

@Composable
private fun progressText(progress: ServerSearchProgress?): String {
    val folderName = progress?.folder?.let { folderDisplayName(it) }
    return when {
        progress?.phase == SearchPhase.SNIPPETS -> stringResource(R.string.search_server_snippets_progress)
        progress == null || folderName == null -> stringResource(R.string.search_server_starting)
        progress.phase == SearchPhase.TEXT && progress.totalSteps <= 0 ->
            stringResource(R.string.search_server_text_preparing, folderName)
        progress.phase == SearchPhase.TEXT ->
            stringResource(R.string.search_server_text_progress, folderName, progress.step, progress.totalSteps)
        progress.totalSteps <= 0 -> stringResource(R.string.search_server_starting)
        else -> stringResource(R.string.search_server_headers_progress, folderName, progress.step, progress.totalSteps)
    }
}

/** After the server matches: notes on what the search could not cover, and what can be searched next. */
@Composable
private fun ServerFooter(server: ServerSession, state: SearchUiState, actions: ServerActions) {
    val problems = server.allProblems
    val finished = !server.running && server.error == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (finished) {
            val skipped = skippedParts(problems)
            val failed = failedParts(problems)
            if (skipped > 0) NoteText(pluralStringResource(R.plurals.search_server_skipped, skipped, skipped))
            if (failed > 0) NoteText(pluralStringResource(R.plurals.search_server_failed, failed, failed))
            if ((skipped > 0 || failed > 0) && server.canRetry) {
                TextButton(onClick = actions.retry) { Text(stringResource(R.string.action_retry)) }
            }
            if (problems.any(::charsetRejected)) NoteText(stringResource(R.string.search_server_charset_failed))
            if (server.truncated) NoteText(stringResource(R.string.search_server_truncated, server.allHits.size))
            val textDone = SearchPhase.TEXT in server.done
            when {
                textDone && state.serverOnlyCount == 0 && !server.stopped -> NoteText(
                    state.serverFolder?.let { stringResource(R.string.search_server_nothing_more, folderDisplayName(it)) }
                        ?: stringResource(R.string.search_server_nothing_more_all),
                )
                !textDone && SearchPhase.HEADERS in server.done -> NoteText(stringResource(R.string.search_server_headers_done))
            }
        }
        if (!server.textRequested) {
            ServerButton(
                label = stringResource(R.string.search_server_search_text),
                icon = Icons.AutoMirrored.Outlined.ManageSearch,
                hint = stringResource(R.string.search_server_search_text_hint),
                enabled = true,
                onClick = actions.searchText,
            )
        }
        if (finished && server.binsOffered) {
            TextButton(onClick = actions.searchBins, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.search_server_bins))
            }
        }
    }
}

/** How much of the mail is indexed on the device, with "Index now" (or the run's progress). */
@Composable
private fun IndexStatusRow(index: IndexSummary, onIndexNow: () -> Unit) {
    val indexed = formatCount(index.indexed)
    val total = formatCount(index.total)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index.running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = if (index.running) {
                    stringResource(R.string.search_index_running, indexed, total)
                } else {
                    stringResource(R.string.search_index_progress, indexed, total)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!index.running) TextButton(onClick = onIndexNow) { Text(stringResource(R.string.search_index_now)) }
        }
    }
}

@Composable
private fun ServerButton(label: String, icon: ImageVector, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun LabeledDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            text = label,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** Requests focus, returning false instead of throwing when the target is not attached yet. */
private fun FocusRequester.requestFocusSafely(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}

/** True on a network the user pays by use (mobile data); false when unknown or offline. */
private fun Context.isOnMeteredNetwork(): Boolean = try {
    val manager = getSystemService(ConnectivityManager::class.java)
    val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
    capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
} catch (_: SecurityException) {
    false
}
