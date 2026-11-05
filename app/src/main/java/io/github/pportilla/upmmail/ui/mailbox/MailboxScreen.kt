package io.github.pportilla.upmmail.ui.mailbox

import androidx.compose.foundation.layout.Column
import io.github.pportilla.upmmail.ui.components.SampleMailNotice
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.ui.components.AppSnackbarController
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.FolderPickerDialog
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.components.errorMessage
import io.github.pportilla.upmmail.ui.components.folderDisplayName
import io.github.pportilla.upmmail.ui.navigation.AppNavigator
import io.github.pportilla.upmmail.ui.navigation.ComposeArgs
import kotlinx.coroutines.launch

/** Room below the last row so the floating compose button never hides it. */
private val FabClearance = 88.dp

/**
 * The main screen: navigation drawer (accounts, folders, outbox), search bar, the message list
 * of the selected folder with swipe actions and multi-selection, and the compose button.
 */
@Composable
fun MailboxScreen(navigator: AppNavigator) {
    val container = appContainer()
    val viewModel = viewModel { MailboxViewModel(container) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showAccountSheet by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val outboxListState = rememberLazyListState()
    val currentNavigator by rememberUpdatedState(navigator)

    LaunchedEffect(viewModel, snackbar) {
        viewModel.events.collect { event ->
            handleEvent(event, context, snackbar, viewModel) { args -> currentNavigator.openCompose(args) }
        }
    }

    ScrollToTopOnFolderChange(state, listState)

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    val drawerActions = DrawerActions(
        onSelectFolder = { folder ->
            closeDrawerThen {
                if (folder.id == state.folder?.id) {
                    scope.launch { listState.animateScrollToItem(0) }
                } else {
                    viewModel.selectFolder(folder)
                }
            }
        },
        onSelectOutbox = { closeDrawerThen(viewModel::selectOutbox) },
        onSelectAccount = { account -> closeDrawerThen { viewModel.selectAccount(account) } },
        onAddAccount = { closeDrawerThen(navigator::openAddAccount) },
        onOpenSettings = { closeDrawerThen(navigator::openSettings) },
        onOpenWebmail = { url ->
            closeDrawerThen {
                if (!context.openUrl(url)) snackbar.show(context.getString(R.string.mailbox_no_browser))
            }
        },
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || !state.selectionMode,
        drawerContent = { MailboxDrawerContent(state, drawerState, drawerActions) },
    ) {
        Scaffold(
            topBar = {
                Column {
                MailboxTopBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSearch = { navigator.openSearch(state.account?.id, state.folder?.id) },
                    onAccountClick = { showAccountSheet = true },
                )
                SampleMailNotice(state.account)
                }
            },
            floatingActionButton = {
                val account = state.account
                if (!state.selectionMode && account != null) {
                    ComposeButton(
                        expanded = state.isOutbox || listState.isAtTop(),
                        onClick = { navigator.openCompose(ComposeArgs(accountId = account.id)) },
                    )
                }
            },
            snackbarHost = { AppSnackbarHost() },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val bodyModifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
                .consumeWindowInsets(innerPadding)
            val listPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + FabClearance)
            when (state.view) {
                MailboxView.Loading -> AccountLoadingContent(
                    state = state,
                    contentPadding = listPadding,
                    onRetry = viewModel::retryAccountSetup,
                    modifier = bodyModifier,
                )

                is MailboxView.Outbox -> OutboxContent(
                    items = state.outbox,
                    ownAddress = state.account?.email,
                    isDemo = state.account?.isDemo == true,
                    listState = outboxListState,
                    contentPadding = listPadding,
                    actions = OutboxActions(
                        onRetry = viewModel::retryOutbox,
                        onEdit = viewModel::editOutbox,
                        onDelete = viewModel::requestDeleteOutbox,
                        onGoToInbox = viewModel::showInbox,
                    ),
                    modifier = bodyModifier,
                )

                is MailboxView.FolderView -> MessageListContent(
                    state = state,
                    listState = listState,
                    contentPadding = listPadding,
                    actions = MessageListActions(
                        onOpen = { message -> openMessage(message, state.folder, navigator) },
                        onToggleSelection = viewModel::toggleSelection,
                        onToggleStar = viewModel::toggleStar,
                        onSwipe = viewModel::onSwipe,
                        onLoadMore = viewModel::loadMore,
                        onRetryLoadMore = viewModel::retryLoadMore,
                        onRefresh = viewModel::refresh,
                    ),
                    modifier = bodyModifier,
                )
            }
        }
    }

    if (showAccountSheet) {
        AccountSwitcherSheet(
            accounts = state.accounts,
            currentAccountId = state.account?.id,
            onDismiss = { showAccountSheet = false },
            onSelectAccount = { account ->
                showAccountSheet = false
                viewModel.selectAccount(account)
            },
            onAddAccount = {
                showAccountSheet = false
                navigator.openAddAccount()
            },
            onManageAccounts = {
                showAccountSheet = false
                navigator.openSettings()
            },
        )
    }

    MailboxDialogs(state, viewModel)
}

@Composable
private fun MailboxTopBar(
    state: MailboxUiState,
    viewModel: MailboxViewModel,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onAccountClick: () -> Unit,
) {
    val folder = state.folder
    if (state.selectionMode) {
        val selected = state.selectedMessages
        SelectionTopBar(
            count = state.selection.size,
            available = remember(selected) { selectionActions(selected) },
            canArchive = state.hasArchiveFolder && folder?.role != FolderRole.ARCHIVE,
            canMove = state.folders.any { it.selectable && it.id != folder?.id },
            permanentDelete = deletesPermanently(folder?.role),
            allSelected = state.messages.isNotEmpty() && state.selection.size >= state.messages.size,
            actions = SelectionBarActions(
                onClose = viewModel::clearSelection,
                onArchive = viewModel::archiveSelected,
                onDelete = viewModel::deleteSelected,
                onMarkRead = viewModel::markSelectedRead,
                onStar = viewModel::starSelected,
                onMove = viewModel::moveSelected,
                onSelectAll = viewModel::selectAll,
            ),
        )
        return
    }
    val placeholder = when {
        folder != null -> stringResource(R.string.mailbox_search_in, folderDisplayName(folder))
        else -> stringResource(R.string.mailbox_search_mail)
    }
    val hasUnread = folder != null && (folder.unreadCount > 0 || state.messages.any { !it.isRead })
    val emptyLabel = when (folder?.role) {
        FolderRole.TRASH -> stringResource(R.string.mailbox_empty_trash)
        FolderRole.JUNK -> stringResource(R.string.mailbox_empty_spam)
        else -> null
    }
    MailboxSearchBar(
        placeholder = placeholder,
        account = state.account,
        menu = MailboxMenuActions(
            onRefresh = if (folder != null) viewModel::refresh else null,
            onMarkAllRead = if (hasUnread) viewModel::markAllRead else null,
            onSelectAll = if (folder != null && state.messages.isNotEmpty()) viewModel::selectAll else null,
            emptyFolderLabel = emptyLabel?.takeIf { state.messages.isNotEmpty() || (folder?.totalCount ?: 0) > 0 },
            onEmptyFolder = viewModel::requestEmptyFolder,
        ),
        onOpenDrawer = onOpenDrawer,
        onSearch = onSearch,
        onAccountClick = onAccountClick,
    )
}

@Composable
private fun ComposeButton(expanded: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.action_compose)
    ExtendedFloatingActionButton(
        text = { Text(label) },
        icon = { Icon(Icons.Outlined.Edit, contentDescription = if (expanded) null else label) },
        onClick = onClick,
        expanded = expanded,
    )
}

/** True while the list shows its first row, i.e. the user has not scrolled down. */
@Composable
private fun LazyListState.isAtTop(): Boolean {
    val atTop by remember(this) { derivedStateOf { firstVisibleItemIndex == 0 } }
    return atTop
}

/** Opens a row: drafts go back to the composer, everything else to the conversation. */
private fun openMessage(message: MessageSummary, folder: Folder?, navigator: AppNavigator) {
    if (folder?.role == FolderRole.DRAFTS) {
        navigator.openCompose(
            ComposeArgs(
                accountId = message.accountId,
                mode = ComposeMode.EDIT_DRAFT,
                serverDraftMessageId = message.id,
            ),
        )
    } else {
        navigator.openMessage(message.id)
    }
}

/**
 * Jumps to the top when another folder is shown, but not when the screen merely comes back
 * (returning from a message keeps the scroll position).
 */
@Composable
private fun ScrollToTopOnFolderChange(state: MailboxUiState, listState: LazyListState) {
    val key = when (val view = state.view) {
        is MailboxView.FolderView -> "folder-${view.folder.id}"
        is MailboxView.Outbox -> "outbox-${view.accountId}"
        MailboxView.Loading -> null
    }
    var shownKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        if (shownKey != null && shownKey != key && scrolled) listState.scrollToItem(0)
        shownKey = key
    }
}

/** Shown while there is no folder to display yet: first sync of a new account, or its failure. */
@Composable
private fun AccountLoadingContent(
    state: MailboxUiState,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.accountSetupError
    when {
        error != null -> ScrollableEmptyState(contentPadding, modifier) {
            val context = LocalContext.current
            MailboxEmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.mailbox_setup_failed_title),
                body = context.errorMessage(error),
                action = { Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } },
            )
        }

        state.account != null && state.foldersLoaded -> MailboxLoadingState(
            caption = stringResource(R.string.mailbox_setting_up),
            contentPadding = contentPadding,
            modifier = modifier,
        )

        else -> MailboxLoadingState(
            caption = stringResource(R.string.loading),
            contentPadding = contentPadding,
            modifier = modifier,
        )
    }
}

@Composable
private fun MailboxDialogs(state: MailboxUiState, viewModel: MailboxViewModel) {
    when (val dialog = state.dialog) {
        is MailboxDialog.ConfirmDelete -> {
            val count = dialog.ids.size
            val conversations = state.settings.conversationView
            val body = when {
                dialog.permanent && conversations -> R.plurals.mailbox_confirm_delete_forever_conversations
                dialog.permanent -> R.plurals.mailbox_confirm_delete_forever_messages
                conversations -> R.plurals.mailbox_confirm_trash_conversations
                else -> R.plurals.mailbox_confirm_trash_messages
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                icon = {
                    Icon(if (dialog.permanent) Icons.Outlined.DeleteForever else Icons.Outlined.Delete, contentDescription = null)
                },
                title = {
                    Text(
                        stringResource(
                            if (dialog.permanent) R.string.mailbox_confirm_delete_forever_title else R.string.mailbox_confirm_trash_title,
                        ),
                    )
                },
                text = { Text(pluralStringResource(body, count, count)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmDelete) {
                        Text(stringResource(if (dialog.permanent) R.string.action_delete_forever else R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        is MailboxDialog.ConfirmEmptyFolder -> {
            val junk = dialog.folder.role == FolderRole.JUNK
            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                title = { Text(stringResource(if (junk) R.string.mailbox_confirm_empty_spam_title else R.string.mailbox_confirm_empty_trash_title)) },
                text = { Text(stringResource(if (junk) R.string.mailbox_confirm_empty_spam_body else R.string.mailbox_confirm_empty_trash_body)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmEmptyFolder) { Text(stringResource(R.string.mailbox_empty_action)) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        is MailboxDialog.MoveTo -> FolderPickerDialog(
            folders = state.folders,
            excludeFolderId = state.folder?.id,
            onDismiss = viewModel::dismissDialog,
            onFolderSelected = viewModel::moveTo,
        )

        is MailboxDialog.ConfirmDeleteOutbox -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.mailbox_confirm_delete_outbox_title)) },
            text = { Text(stringResource(R.string.mailbox_confirm_delete_outbox_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteOutbox) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )

        null -> Unit
    }
}

private fun handleEvent(
    event: MailboxEvent,
    context: Context,
    snackbar: AppSnackbarController,
    viewModel: MailboxViewModel,
    openCompose: (ComposeArgs) -> Unit,
) {
    val resources = context.resources
    when (event) {
        is MailboxEvent.Error -> snackbar.show(
            message = context.errorMessage(event.error),
            actionLabel = event.retry?.let { context.getString(R.string.action_retry) },
            onAction = event.retry,
        )

        is MailboxEvent.Undoable -> {
            val message = when (event.kind) {
                UndoKind.ARCHIVED -> resources.getQuantityString(R.plurals.mailbox_archived, event.count, event.count)
                UndoKind.TRASHED -> resources.getQuantityString(R.plurals.mailbox_trashed, event.count, event.count)
                UndoKind.MOVED -> resources.getQuantityString(
                    R.plurals.mailbox_moved,
                    event.count,
                    event.count,
                    event.folder?.let { folderLabel(context, it) }.orEmpty(),
                )
            }
            snackbar.show(
                message = message,
                actionLabel = context.getString(R.string.action_undo),
                onAction = { viewModel.undo(event.handle) },
            )
        }

        is MailboxEvent.DeletedForever ->
            snackbar.show(resources.getQuantityString(R.plurals.mailbox_deleted_forever, event.count, event.count))

        is MailboxEvent.Info -> snackbar.show(context.getString(event.message))

        is MailboxEvent.OpenCompose -> openCompose(event.args)
    }
}

/** [folderDisplayName] outside composition. */
private fun folderLabel(context: Context, folder: Folder): String = when (folder.role) {
    FolderRole.INBOX -> context.getString(R.string.folder_inbox)
    FolderRole.DRAFTS -> context.getString(R.string.folder_drafts)
    FolderRole.SENT -> context.getString(R.string.folder_sent)
    FolderRole.ARCHIVE -> context.getString(R.string.folder_archive)
    FolderRole.TRASH -> context.getString(R.string.folder_trash)
    FolderRole.JUNK -> context.getString(R.string.folder_junk)
    FolderRole.OTHER -> folder.displayName
}

/** Opens [url] in the browser; false if no app can show it. */
private fun Context.openUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
