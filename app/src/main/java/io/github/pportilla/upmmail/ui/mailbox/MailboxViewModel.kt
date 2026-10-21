package io.github.pportilla.upmmail.ui.mailbox

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.repository.UndoHandle
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.model.SwipeAction
import io.github.pportilla.upmmail.ui.navigation.ComposeArgs
import io.github.pportilla.upmmail.ui.navigation.DeepLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Dialogs the mailbox can show; kept in the ViewModel so they survive configuration changes. */
sealed interface MailboxDialog {
    /** Confirm deleting [ids]; [permanent] in Trash/Spam (or "confirm before deleting" is on). */
    data class ConfirmDelete(val ids: List<Long>, val permanent: Boolean, val fromSelection: Boolean) : MailboxDialog

    /** Confirm emptying the Trash or Spam [folder]. */
    data class ConfirmEmptyFolder(val folder: Folder) : MailboxDialog

    /** "Move to…" for [ids]. */
    data class MoveTo(val ids: List<Long>) : MailboxDialog

    /** Confirm deleting an unsent outbox message. */
    data class ConfirmDeleteOutbox(val item: OutboxItem) : MailboxDialog
}

/** One-shot events for the screen (snackbars and navigation). */
sealed interface MailboxEvent {
    /** A failure; [retry] is offered as the snackbar action when present. */
    data class Error(val error: Throwable?, val retry: (() -> Unit)? = null) : MailboxEvent

    /** A reversible move: [count] rows went to [kind]'s destination ([folder] for MOVED). */
    data class Undoable(val kind: UndoKind, val count: Int, val handle: UndoHandle, val folder: Folder? = null) :
        MailboxEvent

    data class DeletedForever(val count: Int) : MailboxEvent

    /** A plain message, e.g. "Marked all as read". */
    data class Info(@param:StringRes val message: Int) : MailboxEvent

    data class OpenCompose(val args: ComposeArgs) : MailboxEvent
}

enum class UndoKind { ARCHIVED, TRASHED, MOVED }

/** Everything the mailbox screen draws. */
data class MailboxUiState(
    /** False until the account list has been read once. */
    val accountsLoaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val account: Account? = null,
    /** Folders of [account] in repository order. */
    val folders: List<Folder> = emptyList(),
    val foldersLoaded: Boolean = false,
    val view: MailboxView = MailboxView.Loading,
    val messages: List<MessageSummary> = emptyList(),
    /** False until the first page of the current folder has been read from the cache. */
    val messagesLoaded: Boolean = false,
    /** Outbox of [account]. */
    val outbox: List<OutboxItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val selection: Set<Long> = emptySet(),
    /** A user-requested refresh (pull to refresh) is running. */
    val isRefreshing: Boolean = false,
    /** The current folder is being synchronised (by anyone). */
    val isSyncing: Boolean = false,
    val paging: PagingState = PagingState(),
    /** First sync of a new account running (no folders yet). */
    val accountSetupRunning: Boolean = false,
    /** First sync of the account failed; shown as a full-screen error with Retry. */
    val accountSetupError: Throwable? = null,
    val dialog: MailboxDialog? = null,
) {
    val folder: Folder? get() = (view as? MailboxView.FolderView)?.folder
    val isOutbox: Boolean get() = view is MailboxView.Outbox
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val hasArchiveFolder: Boolean get() = folders.any { it.role == FolderRole.ARCHIVE && it.selectable }
    val selectedMessages: List<MessageSummary> get() = messages.filter { it.id in selection }
}

/**
 * State and actions of the main mailbox screen.
 *
 * The selected account and folder live in AppSettings (lastAccountId / lastFolderId), so they are
 * restored on start and follow external changes (a tapped notification). The message list is
 * the cache's Flow for the folder, grown page by page; older mail is downloaded on demand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailboxViewModel(private val container: AppContainer) : ViewModel() {

    private val mail = container.mailRepository
    private val compose = container.composeRepository
    private val settingsRepository = container.settingsRepository

    private val eventChannel = Channel<MailboxEvent>(Channel.BUFFERED)

    /** One-shot events; collect them while the screen is shown. */
    val events: Flow<MailboxEvent> = eventChannel.receiveAsFlow()

    private data class LocalState(
        val selection: Set<Long> = emptySet(),
        val refreshing: Boolean = false,
        val setupRunningFor: Long? = null,
        val setupError: Pair<Long, Throwable>? = null,
        val dialog: MailboxDialog? = null,
    )

    private data class FolderData(val accountId: Long?, val folders: List<Folder>, val loaded: Boolean)

    private data class Core(
        val accounts: List<Account>?,
        val account: Account?,
        val folders: FolderData,
        val resolution: ViewResolution,
        val settings: AppSettings,
    )

    /** Rows of one folder as loaded with [limit]. */
    private data class ListSnapshot(val folderId: Long, val limit: Int, val items: List<MessageSummary>)

    private val local = MutableStateFlow(LocalState())
    private val paging = MutableStateFlow(PagingState())
    private val snapshot = MutableStateFlow<ListSnapshot?>(null)
    private val setupRequested = HashSet<Long>()

    private val accounts: StateFlow<List<Account>?> = container.accountRepository.accounts
        .catch { error ->
            eventChannel.trySend(MailboxEvent.Error(error))
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val accountId: Flow<Long?> =
        combine(accounts.filterNotNull(), settings) { list, s -> resolveAccount(list, s.lastAccountId)?.id }
            .distinctUntilChanged()

    private val folderData: StateFlow<FolderData> = accountId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(FolderData(null, emptyList(), loaded = true))
            } else {
                flow {
                    emit(FolderData(id, emptyList(), loaded = false))
                    emitAll(mail.observeFolders(id).map { FolderData(id, it, loaded = true) })
                }
            }
        }
        .catch { error ->
            eventChannel.trySend(MailboxEvent.Error(error))
            emit(FolderData(null, emptyList(), loaded = true))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FolderData(null, emptyList(), loaded = false))

    // Account, settings and folders are resolved in one place so the persisted selection is only
    // ever compared with folders of the same account.
    private val core: StateFlow<Core> = combine(accounts, settings, folderData) { list, s, data ->
        val account = list?.let { resolveAccount(it, s.lastAccountId) }
        val resolution = resolveView(
            accountId = account?.id,
            foldersAccountId = data.accountId,
            foldersLoaded = data.loaded,
            folders = data.folders,
            lastAccountId = s.lastAccountId,
            lastFolderId = s.lastFolderId,
        )
        Core(list, account, data, resolution, s)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        Core(null, null, FolderData(null, emptyList(), false), ViewResolution(MailboxView.Loading), settings.value),
    )

    private val outbox: StateFlow<List<OutboxItem>> = accountId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else compose.observeOutbox(id) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val dynamic = combine(snapshot, outbox, mail.syncingFolders, local, paging) { list, out, syncing, l, p ->
        DynamicParts(list, out, syncing, l, p)
    }

    private data class DynamicParts(
        val snapshot: ListSnapshot?,
        val outbox: List<OutboxItem>,
        val syncing: Set<Long>,
        val local: LocalState,
        val paging: PagingState,
    )

    val uiState: StateFlow<MailboxUiState> = combine(core, dynamic) { c, d ->
        val view = c.resolution.view
        val folder = (view as? MailboxView.FolderView)?.folder
        val list = d.snapshot?.takeIf { folder != null && it.folderId == folder.id }
        val accountId = c.account?.id
        MailboxUiState(
            accountsLoaded = c.accounts != null,
            accounts = c.accounts.orEmpty(),
            account = c.account,
            folders = if (c.folders.accountId == accountId) c.folders.folders else emptyList(),
            foldersLoaded = c.folders.accountId == accountId && c.folders.loaded,
            view = view,
            messages = list?.items.orEmpty(),
            messagesLoaded = list != null,
            outbox = d.outbox.filter { it.accountId == accountId },
            settings = c.settings,
            selection = d.local.selection,
            isRefreshing = d.local.refreshing,
            isSyncing = folder != null && folder.id in d.syncing,
            paging = d.paging,
            accountSetupRunning = accountId != null && d.local.setupRunningFor == accountId,
            accountSetupError = d.local.setupError?.takeIf { it.first == accountId }?.second,
            dialog = d.local.dialog,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailboxUiState())

    init {
        // Persist the fallback selection (first account / INBOX) so other screens agree with it.
        viewModelScope.launch {
            core.collect { c ->
                val folderId = c.resolution.persistFolderId ?: return@collect
                val account = c.account ?: return@collect
                settingsRepository.update { it.copy(lastAccountId = account.id, lastFolderId = folderId) }
            }
        }
        // A freshly added account has no folders until its first sync: run it once.
        viewModelScope.launch {
            folderData.collect { data ->
                val id = data.accountId ?: return@collect
                if (!data.loaded) return@collect
                if (data.folders.isEmpty()) {
                    if (setupRequested.add(id)) setUpAccount(id)
                } else if (local.value.setupError?.first == id) {
                    local.update { it.copy(setupError = null) }
                }
            }
        }
        // The message list of the selected folder.
        viewModelScope.launch {
            core.map { (it.resolution.view as? MailboxView.FolderView)?.folder?.id }
                .distinctUntilChanged()
                .collectLatestSafely { folderId -> observeFolder(folderId) }
        }
    }

    private suspend fun observeFolder(folderId: Long?) {
        paging.value = PagingState(folderId = folderId)
        snapshot.value = null
        // Selection and dialogs belong to the previous folder (it may change under them, e.g. when a
        // notification is tapped).
        local.update { it.copy(selection = emptySet(), dialog = null) }
        if (folderId == null) return
        coroutineScope {
            launch { refreshIfStale(folderId) }
            combine(
                paging.map { it.limit }.distinctUntilChanged(),
                settings.map { it.conversationView }.distinctUntilChanged(),
            ) { limit, conversations -> limit to conversations }
                .flatMapLatest { (limit, conversations) ->
                    mail.observeMessages(folderId, limit, conversations).map { ListSnapshot(folderId, limit, it) }
                }
                .catch { error -> eventChannel.trySend(MailboxEvent.Error(error)) }
                .collect { list ->
                    snapshot.value = list
                    paging.update { if (it.folderId == folderId) it.copy(shownLimit = list.limit) else it }
                    val ids = list.items.mapTo(HashSet()) { it.id }
                    local.update { state ->
                        if (state.selection.all { it in ids }) state else state.copy(selection = state.selection.filterTo(HashSet()) { it in ids })
                    }
                }
        }
    }

    private suspend fun refreshIfStale(folderId: Long) {
        val folder = mail.getFolder(folderId) ?: return
        if (!folder.selectable || folderId in mail.syncingFolders.value) return
        if (!isStale(folder, System.currentTimeMillis())) return
        val result = mail.refreshFolder(folderId)
        // A folder never synced before would otherwise stay empty without explanation.
        if (result.isFailure && folder.lastSyncedAt == null) {
            eventChannel.send(MailboxEvent.Error(result.exceptionOrNull(), retry = ::refresh))
        }
    }

    private fun setUpAccount(accountId: Long) {
        local.update { it.copy(setupRunningFor = accountId, setupError = null) }
        viewModelScope.launch {
            val result = mail.refreshAccount(accountId)
            var error = result.exceptionOrNull()
            if (error == null) {
                // The folder list reaches us through the cache observer a moment later.
                val folders = withTimeoutOrNull(FOLDER_WAIT_MILLIS) {
                    folderData.first { it.accountId != accountId || (it.loaded && it.folders.isNotEmpty()) }
                }
                if (folders == null) error = MailException(MailErrorKind.NOT_FOUND, "The account has no folders")
            }
            local.update { state ->
                state.copy(
                    setupRunningFor = state.setupRunningFor.takeIf { it != accountId },
                    setupError = error?.let { accountId to it },
                )
            }
        }
    }

    // ------------------------------------------------------------------ navigation in the mailbox

    /** Shows [folder] (of the current account). */
    fun selectFolder(folder: Folder) {
        if (!folder.selectable) return
        settingsRepository.update { it.copy(lastAccountId = folder.accountId, lastFolderId = folder.id) }
    }

    /** Shows the outbox of the current account. */
    fun selectOutbox() {
        val account = core.value.account ?: return
        settingsRepository.update { it.copy(lastAccountId = account.id, lastFolderId = DeepLinks.OUTBOX_FOLDER_ID) }
    }

    /** Shows the INBOX of the current account (e.g. leaving an empty outbox). */
    fun showInbox() {
        val account = core.value.account ?: return
        settingsRepository.update { it.copy(lastAccountId = account.id, lastFolderId = -1) }
    }

    /** Switches to [account], showing its INBOX. */
    fun selectAccount(account: Account) {
        if (core.value.account?.id == account.id) return
        settingsRepository.update { it.copy(lastAccountId = account.id, lastFolderId = -1) }
    }

    /** Pull to refresh: the INBOX refreshes the whole account (folder list, outbox, INBOX). */
    fun refresh() {
        when (val view = core.value.resolution.view) {
            is MailboxView.FolderView -> {
                if (local.value.refreshing) return
                local.update { it.copy(refreshing = true) }
                paging.update { if (it.folderId == view.folder.id) it.copy(failed = false) else it }
                viewModelScope.launch {
                    val folder = view.folder
                    val result = if (folder.role == FolderRole.INBOX) {
                        mail.refreshAccount(folder.accountId)
                    } else {
                        mail.refreshFolder(folder.id)
                    }
                    local.update { it.copy(refreshing = false) }
                    result.exceptionOrNull()?.let { eventChannel.send(MailboxEvent.Error(it, retry = ::refresh)) }
                }
            }
            is MailboxView.Outbox -> container.syncController.requestOutboxSend()
            MailboxView.Loading -> retryAccountSetup()
        }
    }

    /** Retries the first sync of the current account after a failure. */
    fun retryAccountSetup() {
        val id = core.value.account?.id ?: return
        if (local.value.setupRunningFor == id) return
        setupRequested += id
        setUpAccount(id)
    }

    // ------------------------------------------------------------------ paging

    /** The user scrolled near the end of the list: show more cached rows or download older mail. */
    fun loadMore() {
        val current = paging.value
        val folderId = current.folderId ?: return
        val shown = snapshot.value?.takeIf { it.folderId == folderId } ?: return
        when (nextPagingStep(current, shown.items.size)) {
            PagingStep.NOTHING -> Unit
            PagingStep.GROW_LIMIT -> paging.update {
                if (it.folderId == folderId && it.limit == current.limit) it.copy(limit = it.limit + PAGE_SIZE) else it
            }
            PagingStep.LOAD_OLDER -> {
                paging.update { if (it.folderId == folderId) it.copy(loadingOlder = true) else it }
                viewModelScope.launch {
                    val result = mail.loadOlder(folderId, PAGE_SIZE)
                    paging.update { state ->
                        if (state.folderId != folderId) {
                            state
                        } else {
                            state.copy(
                                loadingOlder = false,
                                endReached = result.getOrNull()?.not() ?: state.endReached,
                                failed = result.isFailure,
                            )
                        }
                    }
                    result.exceptionOrNull()?.let { eventChannel.send(MailboxEvent.Error(it, retry = ::retryLoadMore)) }
                }
            }
        }
    }

    /** Clears a "load older" failure and tries again. */
    fun retryLoadMore() {
        paging.update { it.copy(failed = false) }
        loadMore()
    }

    // ------------------------------------------------------------------ selection

    fun toggleSelection(id: Long) {
        local.update { it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id) }
    }

    fun clearSelection() {
        local.update { it.copy(selection = emptySet()) }
    }

    fun selectAll() {
        val ids = snapshot.value?.items?.mapTo(HashSet()) { it.id } ?: return
        local.update { it.copy(selection = ids) }
    }

    private fun takeSelection(): List<Long> {
        val ids = local.value.selection.toList()
        clearSelection()
        return ids
    }

    fun markSelectedRead(read: Boolean) {
        val ids = takeSelection()
        if (ids.isNotEmpty()) perform { mail.setRead(ids, read) }
    }

    fun starSelected(starred: Boolean) {
        val ids = takeSelection()
        if (ids.isNotEmpty()) perform { mail.setStarred(ids, starred) }
    }

    fun archiveSelected() {
        if (!uiState.value.hasArchiveFolder) {
            eventChannel.trySend(MailboxEvent.Info(R.string.mailbox_no_archive_folder))
            return
        }
        val ids = takeSelection()
        if (ids.isNotEmpty()) archive(ids)
    }

    fun deleteSelected() {
        val ids = local.value.selection.toList()
        if (ids.isEmpty()) return
        if (requestDelete(ids, fromSelection = true)) clearSelection()
    }

    fun moveSelected() {
        val ids = local.value.selection.toList()
        if (ids.isNotEmpty()) local.update { it.copy(dialog = MailboxDialog.MoveTo(ids)) }
    }

    /** Completes the "Move to…" dialog. */
    fun moveTo(folder: Folder) {
        val dialog = local.value.dialog as? MailboxDialog.MoveTo ?: return
        local.update { it.copy(dialog = null, selection = it.selection - dialog.ids.toSet()) }
        val count = dialog.ids.size
        performThen(
            action = { mail.moveTo(dialog.ids, folder.id) },
            onResult = { result ->
                result.fold(
                    onSuccess = { handle ->
                        if (handle.count > 0) eventChannel.send(MailboxEvent.Undoable(UndoKind.MOVED, count, handle, folder))
                    },
                    onFailure = { eventChannel.send(MailboxEvent.Error(it)) },
                )
            },
        )
    }

    // ------------------------------------------------------------------ row actions

    fun toggleStar(message: MessageSummary) {
        perform { mail.setStarred(listOf(message.id), !message.isStarred) }
    }

    /**
     * Runs a swipe [action] on [message]. Returns true if the row should stay dismissed (it is
     * leaving the list), false if it should snap back.
     */
    fun onSwipe(message: MessageSummary, action: SwipeAction): Boolean = when (action) {
        SwipeAction.NONE -> false
        SwipeAction.TOGGLE_READ -> {
            perform { mail.setRead(listOf(message.id), !message.isRead) }
            false
        }
        SwipeAction.TOGGLE_STAR -> {
            toggleStar(message)
            false
        }
        SwipeAction.ARCHIVE -> {
            val state = uiState.value
            when {
                state.folder?.role == FolderRole.ARCHIVE -> false
                !state.hasArchiveFolder -> {
                    eventChannel.trySend(MailboxEvent.Info(R.string.mailbox_no_archive_folder))
                    false
                }
                else -> {
                    archive(listOf(message.id))
                    true
                }
            }
        }
        SwipeAction.DELETE -> requestDelete(listOf(message.id), fromSelection = false)
    }

    private fun archive(ids: List<Long>) {
        performThen(
            action = { mail.archive(ids) },
            onResult = { result ->
                result.fold(
                    onSuccess = { handle ->
                        if (handle.count > 0) eventChannel.send(MailboxEvent.Undoable(UndoKind.ARCHIVED, ids.size, handle))
                    },
                    onFailure = { error ->
                        if (error is MailException && error.kind == MailErrorKind.NOT_FOUND) {
                            eventChannel.send(MailboxEvent.Info(R.string.mailbox_no_archive_folder))
                        } else {
                            eventChannel.send(MailboxEvent.Error(error))
                        }
                    },
                )
            },
        )
    }

    /**
     * Deletes [ids] now, or asks first (Trash/Spam and drafts saved only on this device always,
     * elsewhere when the setting says so). Returns true if the deletion started.
     */
    private fun requestDelete(ids: List<Long>, fromSelection: Boolean): Boolean {
        val state = uiState.value
        val permanent = deletesPermanently(state.folder?.role, ids)
        if (permanent || state.settings.confirmDelete) {
            local.update { it.copy(dialog = MailboxDialog.ConfirmDelete(ids, permanent, fromSelection)) }
            return false
        }
        delete(ids)
        return true
    }

    /** Confirms the pending delete dialog. */
    fun confirmDelete() {
        val dialog = local.value.dialog as? MailboxDialog.ConfirmDelete ?: return
        local.update { state ->
            state.copy(
                dialog = null,
                selection = if (dialog.fromSelection) emptySet() else state.selection - dialog.ids.toSet(),
            )
        }
        delete(dialog.ids)
    }

    private fun delete(ids: List<Long>) {
        performThen(
            action = { mail.delete(ids) },
            onResult = { result ->
                result.fold(
                    onSuccess = { handle ->
                        when {
                            handle == null -> eventChannel.send(MailboxEvent.DeletedForever(ids.size))
                            // Local drafts among them were discarded for good; Undo restores the rest.
                            handle.count > 0 -> eventChannel.send(
                                MailboxEvent.Undoable(UndoKind.TRASHED, ids.count { !isLocalDraftRow(it) }, handle),
                            )
                        }
                    },
                    onFailure = { eventChannel.send(MailboxEvent.Error(it)) },
                )
            },
        )
    }

    /** Reverts an archive / delete / move. Runs in the application scope: the screen may be gone. */
    fun undo(handle: UndoHandle) {
        container.applicationScope.launch {
            try {
                mail.undo(handle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing sensible to show: the snackbar is gone and the messages stay moved.
            }
        }
    }

    // ------------------------------------------------------------------ folder actions

    fun markAllRead() {
        val folder = uiState.value.folder ?: return
        performThen(
            action = { mail.markFolderRead(folder.id) },
            onResult = { eventChannel.send(MailboxEvent.Info(R.string.mailbox_marked_all_read)) },
        )
    }

    fun requestEmptyFolder() {
        val folder = uiState.value.folder?.takeIf { deletesPermanently(it.role) } ?: return
        local.update { it.copy(dialog = MailboxDialog.ConfirmEmptyFolder(folder)) }
    }

    fun confirmEmptyFolder() {
        val dialog = local.value.dialog as? MailboxDialog.ConfirmEmptyFolder ?: return
        local.update { it.copy(dialog = null, selection = emptySet()) }
        emptyFolder(dialog.folder)
    }

    private fun emptyFolder(folder: Folder) {
        performThen(
            action = { mail.emptyFolder(folder.id) },
            onResult = { result ->
                result.fold(
                    onSuccess = {
                        val message = if (folder.role == FolderRole.JUNK) R.string.mailbox_spam_emptied else R.string.mailbox_trash_emptied
                        eventChannel.send(MailboxEvent.Info(message))
                    },
                    onFailure = { eventChannel.send(MailboxEvent.Error(it, retry = { emptyFolder(folder) })) },
                )
            },
        )
    }

    fun dismissDialog() {
        local.update { it.copy(dialog = null) }
    }

    // ------------------------------------------------------------------ outbox

    fun retryOutbox(item: OutboxItem) {
        perform { compose.retrySend(item.id) }
    }

    fun requestDeleteOutbox(item: OutboxItem) {
        local.update { it.copy(dialog = MailboxDialog.ConfirmDeleteOutbox(item)) }
    }

    fun confirmDeleteOutbox() {
        val dialog = local.value.dialog as? MailboxDialog.ConfirmDeleteOutbox ?: return
        local.update { it.copy(dialog = null) }
        performThen(
            action = { compose.deleteOutboxItem(dialog.item.id) },
            onResult = { eventChannel.send(MailboxEvent.Info(R.string.mailbox_outbox_deleted)) },
        )
    }

    /**
     * Moves an unsent message back to the drafts and opens it in the composer. A failed message is
     * saved as a draft before its outbox entry is deleted, so its attachments are still referenced
     * and kept. A queued one is taken back like "Undo" after sending, which only works until the
     * sender picks it up.
     */
    fun editOutbox(item: OutboxItem) {
        val current = outbox.value.firstOrNull { it.id == item.id } ?: return
        val takeBack: suspend () -> Long? = when (current.state) {
            OutboxState.FAILED -> suspend {
                val draftId = compose.saveDraft(current.draft.copy(id = 0), upload = false)
                compose.deleteOutboxItem(current.id)
                draftId
            }
            OutboxState.QUEUED -> suspend {
                compose.cancelSend(current.id)?.let { draft -> compose.saveDraft(draft, upload = false) }
            }
            // Being handed to the SMTP server right now.
            OutboxState.SENDING -> return
        }
        performThen(
            action = takeBack,
            onResult = { draftId ->
                if (draftId == null) {
                    eventChannel.send(MailboxEvent.Info(R.string.mailbox_outbox_edit_too_late))
                } else {
                    eventChannel.send(MailboxEvent.OpenCompose(ComposeArgs(accountId = current.accountId, draftId = draftId)))
                }
            },
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs a repository [action] in the application scope (so it completes even if the screen
     * goes away) and reports its outcome from the ViewModel scope. Unexpected exceptions become
     * an error snackbar instead of crashing.
     */
    private fun <T> performThen(action: suspend () -> T, onResult: suspend (T) -> Unit) {
        val deferred = container.applicationScope.async {
            try {
                Result.success(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        viewModelScope.launch {
            deferred.await().fold(
                onSuccess = { onResult(it) },
                onFailure = { eventChannel.send(MailboxEvent.Error(it)) },
            )
        }
    }

    /** [performThen] without a follow-up. */
    private fun perform(action: suspend () -> Unit) = performThen(action) { }
}

private const val FOLDER_WAIT_MILLIS = 3_000L

/** collectLatest that survives a failing block (a DB error) instead of killing the collector. */
private suspend fun <T> Flow<T>.collectLatestSafely(block: suspend (T) -> Unit) {
    collectLatest { value ->
        try {
            block(value)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next emission starts over; errors inside are reported where they happen.
        }
    }
}
