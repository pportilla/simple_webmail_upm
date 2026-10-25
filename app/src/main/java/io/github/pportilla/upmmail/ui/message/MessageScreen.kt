package io.github.pportilla.upmmail.ui.message

import io.github.pportilla.upmmail.ui.components.SampleMailNotice
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.ReportOff
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.UndoHandle
import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchQuery
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.FolderPickerDialog
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.components.errorMessage
import io.github.pportilla.upmmail.ui.components.folderDisplayName
import io.github.pportilla.upmmail.ui.components.folderIcon
import io.github.pportilla.upmmail.ui.navigation.AppNavigator
import io.github.pportilla.upmmail.ui.navigation.ComposeArgs
import io.github.pportilla.upmmail.ui.safety.BlockSenderDialog
import io.github.pportilla.upmmail.ui.safety.ProviderReportScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** How long a restored scroll position waits for the message bodies to load again. */
private const val SCROLL_RESTORE_MILLIS = 5_000L

/**
 * Conversation view of the thread containing [messageId]: every message as a card (sanitised
 * HTML in a WebView, attachments, details), conversation actions in the top bar and reply
 * buttons at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(messageId: Long, navigator: AppNavigator) {
    val container = appContainer()
    val viewModel = viewModel(key = "message-$messageId") { MessageViewModel(container, messageId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    // navigator.back() must run once, whatever asks for it first (an action, the thread vanishing…).
    var closed by remember { mutableStateOf(false) }
    val close: () -> Unit = remember(navigator) {
        {
            if (!closed) {
                closed = true
                navigator.back()
            }
        }
    }

    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var confirmConversationDelete by rememberSaveable { mutableStateOf(false) }
    var confirmMessageDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }
    var senderToBlock by rememberSaveable { mutableStateOf<String?>(null) }
    var showProviderReport by rememberSaveable { mutableStateOf(false) }

    val printer = remember(context) { MessagePrinter(context) }
    DisposableEffect(printer) {
        onDispose { printer.release() }
    }

    var saveMimeType by rememberSaveable { mutableStateOf("application/octet-stream") }
    // The attachment the "save as" picker is for. Saved state: the app may be killed meanwhile.
    var pendingSaveId by rememberSaveable { mutableLongStateOf(-1L) }
    var pendingSaveName by rememberSaveable { mutableStateOf("") }
    var saveRequest by remember { mutableStateOf<Attachment?>(null) }
    val saveContract = remember(saveMimeType) { ActivityResultContracts.CreateDocument(saveMimeType) }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        viewModel.saveAttachmentTo(
            attachmentId = pendingSaveId.takeIf { it >= 0 },
            fileName = pendingSaveName,
            target = uri,
            resolver = context.applicationContext.contentResolver,
        )
        pendingSaveId = -1L
    }
    LaunchedEffect(saveRequest) {
        val request = saveRequest ?: return@LaunchedEffect
        saveRequest = null
        try {
            saveLauncher.launch(request.fileName)
        } catch (e: ActivityNotFoundException) {
            pendingSaveId = -1L
            snackbar.show(context.getString(R.string.message_attachment_save_failed))
        }
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MessageEvent.Close -> close()
                is MessageEvent.OpenAttachment -> {
                    if (!context.openAttachmentFile(event.file, event.attachment)) {
                        snackbar.show(context.getString(R.string.message_attachment_open_failed))
                    }
                }
                is MessageEvent.ShareAttachment -> {
                    val title = context.getString(R.string.message_attachment_share_title, event.attachment.fileName)
                    if (!context.shareAttachmentFile(event.file, event.attachment, title)) {
                        snackbar.show(context.getString(R.string.message_attachment_share_failed))
                    }
                }
                is MessageEvent.AttachmentSaved -> {
                    snackbar.show(context.getString(R.string.message_attachment_saved, event.fileName))
                }
                MessageEvent.AttachmentSaveFailed -> {
                    snackbar.show(context.getString(R.string.message_attachment_save_failed))
                }
                is MessageEvent.Failure -> snackbar.show(context.errorMessage(event.error))
            }
        }
    }

    /** Snackbar for a finished move, with Undo when it can still be reverted. */
    fun showMoveResult(@StringRes message: Int, handle: UndoHandle?, restoreId: Long? = null, vararg args: Any) {
        val text = context.getString(message, *args)
        if (handle != null && handle.count > 0) {
            snackbar.show(text, actionLabel = context.getString(R.string.action_undo), onAction = { viewModel.undo(handle, restoreId) })
        } else {
            snackbar.show(text)
        }
    }

    fun showError(error: Throwable) {
        snackbar.show(context.errorMessage(error))
    }

    val accountId = state.accountId

    fun compose(args: ComposeArgs) = navigator.openCompose(args)

    fun reply(id: Long, mode: ComposeMode) = compose(ComposeArgs(accountId = accountId, mode = mode, sourceMessageId = id))

    fun editDraft(id: Long) = compose(ComposeArgs(accountId = accountId, mode = ComposeMode.EDIT_DRAFT, serverDraftMessageId = id))

    fun deleteConversationNow() {
        viewModel.deleteConversation { result ->
            result.fold(
                onSuccess = { handle ->
                    showMoveResult(if (handle != null) R.string.message_trashed else R.string.message_deleted_forever, handle)
                },
                onFailure = ::showError,
            )
        }
        close()
    }

    fun deleteMessageNow(id: Long) {
        viewModel.deleteMessage(id) { result ->
            result.fold(
                onSuccess = { handle ->
                    showMoveResult(
                        if (handle != null) R.string.message_single_trashed else R.string.message_single_deleted_forever,
                        handle,
                        restoreId = id,
                    )
                },
                onFailure = ::showError,
            )
        }
    }

    fun moveNow(folder: Folder, @StringRes message: Int) {
        val name = folderName(context, folder)
        viewModel.moveConversation(folder) { result ->
            result.fold(onSuccess = { showMoveResult(message, it, null, name) }, onFailure = ::showError)
        }
        close()
    }

    fun handleLink(uri: Uri) {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "mailto" -> parseMailto(uri.toString())?.let { compose(it.toComposeArgs(accountId)) }
            "http", "https", "tel", "geo", "sms", "smsto" -> {
                if (!context.openExternalLink(uri)) snackbar.show(context.getString(R.string.message_no_app_for_link))
            }
            else -> Unit
        }
    }

    val cardActions = remember(viewModel, accountId) {
        MessageCardActions(
            onToggleExpanded = viewModel::toggleExpanded,
            onToggleDetails = viewModel::toggleDetails,
            onStar = viewModel::setMessageStarred,
            onReply = { reply(it, ComposeMode.REPLY) },
            onReplyAll = { reply(it, ComposeMode.REPLY_ALL) },
            onForward = { reply(it, ComposeMode.FORWARD) },
            onMarkUnreadFrom = viewModel::markUnreadFrom,
            onDelete = { item ->
                if (isPermanentDelete(item, state) || state.confirmDelete) {
                    confirmMessageDeleteId = item.id
                } else {
                    deleteMessageNow(item.id)
                }
            },
            onEditDraft = ::editDraft,
            onRetry = viewModel::retryBody,
            onShowImages = viewModel::showImages,
            onTrustSender = viewModel::trustSender,
            onAddressClick = { address -> compose(ComposeArgs(accountId = accountId, to = AddressParser.format(listOf(address)))) },
            onAddressLongClick = { address ->
                context.copyAddress(address)
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    snackbar.show(context.getString(R.string.message_address_copied))
                }
            },
            onSearchFrom = { address ->
                navigator.openSearch(accountId.takeIf { it > 0 }, null, SearchQuery(from = listOf(address.address)).format())
            },
            onSearchTo = { address ->
                navigator.openSearch(accountId.takeIf { it > 0 }, null, SearchQuery(to = listOf(address.address)).format())
            },
            onLinkClick = ::handleLink,
            onOpenAttachment = viewModel::openAttachment,
            onShareAttachment = viewModel::shareAttachment,
            onBlockSender = { senderToBlock = it },
            onReportToProvider = { showProviderReport = true },
            onSaveAttachment = { attachment ->
                pendingSaveId = attachment.id
                pendingSaveName = attachment.fileName
                saveMimeType = effectiveMimeType(attachment)
                saveRequest = attachment
            },
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollState = rememberScrollState()
    // Coming back to this screen recreates the message bodies empty, and the first layout clamps
    // the restored scroll position: return to it once they have grown back, unless the user scrolls.
    val restoredScroll = remember { scrollState.value }
    LaunchedEffect(Unit) {
        if (restoredScroll <= 0) return@LaunchedEffect
        val reachable = withTimeoutOrNull(SCROLL_RESTORE_MILLIS) {
            // maxValue is Int.MAX_VALUE until the scrolling column has been laid out.
            val (scrolling, _) = snapshotFlow { scrollState.isScrollInProgress to scrollState.maxValue }
                .first { (scrolling, max) -> scrolling || (max != Int.MAX_VALUE && max >= restoredScroll) }
            !scrolling
        } ?: false
        if (reachable && scrollState.value < restoredScroll) scrollState.scrollTo(restoredScroll)
    }
    var headerHeight by remember { mutableIntStateOf(0) }
    val showTitle by remember { derivedStateOf { headerHeight > 0 && scrollState.value > headerHeight } }
    val ready = state.status == ThreadStatus.READY

    if (showProviderReport) {
        ProviderReportScreen(container, onBack = { showProviderReport = false }, initialAccountId = accountId)
        return
    }
    senderToBlock?.let { address ->
        BlockSenderDialog(address, onDismiss = { senderToBlock = null }) {
            runCatching { container.safetyRepository.blockSender(address) }.fold(
                onSuccess = { snackbar.show(context.getString(R.string.mail_sender_blocked)); close() },
                onFailure = ::showError,
            )
            senderToBlock = null
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    if (ready && showTitle) {
                        Text(
                            text = state.subject.ifBlank { stringResource(R.string.no_subject) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (ready) {
                        ConversationActions(
                            state = state,
                            onArchive = {
                                viewModel.archiveConversation { result ->
                                    result.fold(onSuccess = { showMoveResult(R.string.message_archived, it) }, onFailure = ::showError)
                                }
                                close()
                            },
                            onDelete = {
                                if (state.deleteIsPermanent || state.confirmDelete) {
                                    confirmConversationDelete = true
                                } else {
                                    deleteConversationNow()
                                }
                            },
                            onMarkUnread = viewModel::markConversationUnread,
                            onMove = { showMoveDialog = true },
                            onStar = { viewModel.setConversationStarred(!state.isStarred) },
                            onSpam = { action ->
                                when (action) {
                                    is SpamAction.Report -> moveNow(action.target, R.string.message_reported_spam)
                                    is SpamAction.NotSpam -> moveNow(action.target, R.string.message_moved)
                                }
                            },
                            onPrint = {
                                val labels = PrintLabels(
                                    from = context.getString(R.string.message_details_from),
                                    to = context.getString(R.string.message_details_to),
                                    cc = context.getString(R.string.message_details_cc),
                                    date = context.getString(R.string.message_details_date),
                                    noSubject = context.getString(R.string.no_subject),
                                )
                                scope.launch {
                                    viewModel.preparePrint(labels).fold(
                                        onSuccess = { request ->
                                            printer.print(request) {
                                                snackbar.show(context.getString(R.string.message_print_failed))
                                            }
                                        },
                                        onFailure = ::showError,
                                    )
                                }
                            },
                            onUnsubscribe = { confirmUnsubscribe = true },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (ready) {
                ReplyBar(
                    state = state,
                    onReply = { reply(it, ComposeMode.REPLY) },
                    onReplyAll = { reply(it, ComposeMode.REPLY_ALL) },
                    onForward = { reply(it, ComposeMode.FORWARD) },
                    onEditDraft = ::editDraft,
                )
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        when (state.status) {
            ThreadStatus.LOADING -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            ThreadStatus.NOT_FOUND -> NotFoundContent(onBack = close, modifier = Modifier.padding(padding))
            ThreadStatus.CLOSED -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            ThreadStatus.READY -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(bottom = 16.dp),
            ) {
                SampleMailNotice(accounts.firstOrNull { it.id == state.accountId })
                SubjectHeader(
                    subject = state.subject,
                    folder = state.folder,
                    modifier = Modifier.onSizeChanged { headerHeight = it.height },
                )
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.rows.forEach { row ->
                        when (row) {
                            is ThreadRowUi.Message -> key(row.item.id) {
                                MessageCard(item = row.item, selfAddresses = state.selfAddresses, actions = cardActions)
                            }
                            is ThreadRowUi.Hidden -> key("hidden-${row.ids.first()}") {
                                HiddenMessagesRow(count = row.ids.size, onClick = viewModel::revealAll)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMoveDialog && ready) {
        FolderPickerDialog(
            folders = state.folders.filter { it.role != FolderRole.DRAFTS },
            excludeFolderId = state.folder?.id,
            onDismiss = { showMoveDialog = false },
            onFolderSelected = { folder ->
                showMoveDialog = false
                moveNow(folder, R.string.message_moved)
            },
        )
    }

    if (confirmConversationDelete && ready) {
        val permanent = state.deleteIsPermanent
        AlertDialog(
            onDismissRequest = { confirmConversationDelete = false },
            title = {
                Text(stringResource(if (permanent) R.string.message_delete_forever_title else R.string.message_delete_title))
            },
            text = {
                Text(
                    pluralStringResource(
                        if (permanent) R.plurals.message_delete_forever_body else R.plurals.message_delete_body,
                        state.actionCount,
                        state.actionCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmConversationDelete = false
                        deleteConversationNow()
                    },
                ) {
                    Text(stringResource(if (permanent) R.string.action_delete_forever else R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmConversationDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val messageToDelete = confirmMessageDeleteId?.let { id ->
        state.rows.firstNotNullOfOrNull { row -> (row as? ThreadRowUi.Message)?.item?.takeIf { it.id == id } }
    }
    if (messageToDelete != null) {
        val id = messageToDelete.id
        val permanent = isPermanentDelete(messageToDelete, state)
        AlertDialog(
            onDismissRequest = { confirmMessageDeleteId = null },
            title = { Text(stringResource(R.string.message_delete_single_title)) },
            text = {
                Text(
                    stringResource(
                        if (permanent) R.string.message_delete_single_forever_body else R.string.message_delete_single_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmMessageDeleteId = null
                        deleteMessageNow(id)
                    },
                ) {
                    Text(stringResource(if (permanent) R.string.action_delete_forever else R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMessageDeleteId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val unsubscribe = state.unsubscribe
    if (confirmUnsubscribe && ready && unsubscribe != null) {
        val description = when (unsubscribe) {
            is UnsubscribeTarget.Web -> stringResource(
                R.string.message_unsubscribe_web,
                Uri.parse(unsubscribe.url).host ?: unsubscribe.url,
            )
            is UnsubscribeTarget.Mail -> stringResource(R.string.message_unsubscribe_mail, unsubscribe.link.to.joinToString(", "))
        }
        val defaultSubject = stringResource(R.string.message_unsubscribe_default_subject)
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            icon = { Icon(Icons.Outlined.Unsubscribe, contentDescription = null) },
            title = {
                Text(
                    state.unsubscribeSender?.let { stringResource(R.string.message_unsubscribe_title_from, it) }
                        ?: stringResource(R.string.message_unsubscribe_title),
                )
            },
            text = { Text(description) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnsubscribe = false
                        when (unsubscribe) {
                            is UnsubscribeTarget.Web -> {
                                if (!context.openExternalLink(Uri.parse(unsubscribe.url))) {
                                    snackbar.show(context.getString(R.string.message_no_app_for_link))
                                }
                            }
                            is UnsubscribeTarget.Mail -> {
                                val link = unsubscribe.link
                                compose(
                                    link.copy(subject = link.subject?.takeIf { it.isNotBlank() } ?: defaultSubject)
                                        .toComposeArgs(accountId),
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.message_unsubscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnsubscribe = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Top bar actions acting on the whole conversation (its messages in the opened folder). */
@Composable
private fun ConversationActions(
    state: MessageUiState,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkUnread: () -> Unit,
    onMove: () -> Unit,
    onStar: () -> Unit,
    onSpam: (SpamAction) -> Unit,
    onPrint: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    if (state.canArchive) {
        IconButton(onClick = onArchive) {
            Icon(Icons.Outlined.Archive, contentDescription = stringResource(R.string.action_archive))
        }
    }
    IconButton(onClick = onDelete) {
        if (state.deleteIsPermanent) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever))
        } else {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
        }
    }
    if (state.canMarkUnread) {
        IconButton(onClick = onMarkUnread) {
            Icon(Icons.Outlined.MarkEmailUnread, contentDescription = stringResource(R.string.action_mark_unread))
        }
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val dismiss = { expanded = false }
            OverflowItem(stringResource(R.string.action_move), Icons.AutoMirrored.Outlined.DriveFileMove, dismiss, onMove)
            if (state.isStarred) {
                OverflowItem(stringResource(R.string.action_unstar), Icons.Outlined.StarBorder, dismiss, onStar)
            } else {
                OverflowItem(stringResource(R.string.action_star), Icons.Outlined.Star, dismiss, onStar)
            }
            when (val spam = state.spamAction) {
                is SpamAction.Report ->
                    OverflowItem(stringResource(R.string.action_report_spam), Icons.Outlined.Report, dismiss) { onSpam(spam) }
                is SpamAction.NotSpam ->
                    OverflowItem(stringResource(R.string.action_not_spam), Icons.Outlined.ReportOff, dismiss) { onSpam(spam) }
                null -> Unit
            }
            OverflowItem(stringResource(R.string.message_print), Icons.Outlined.Print, dismiss, onPrint)
            if (state.unsubscribe != null) {
                OverflowItem(stringResource(R.string.message_unsubscribe), Icons.Outlined.Unsubscribe, dismiss, onUnsubscribe)
            }
        }
    }
}

@Composable
private fun OverflowItem(text: String, icon: ImageVector, dismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            dismiss()
            onClick()
        },
    )
}

/** Reply / Reply all / Forward for the newest message, or "Edit draft" when a draft is open. */
@Composable
private fun ReplyBar(
    state: MessageUiState,
    onReply: (Long) -> Unit,
    onReplyAll: (Long) -> Unit,
    onForward: (Long) -> Unit,
    onEditDraft: (Long) -> Unit,
) {
    val draftId = state.openedDraftId
    val target = state.replyTarget
    if (draftId == null && target == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (draftId != null) {
                Button(onClick = { onEditDraft(draftId) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.message_edit_draft))
                }
            } else if (target != null) {
                val replyAllFirst = state.defaultReplyAll && target.canReplyAll
                ReplyButton(
                    label = stringResource(R.string.message_button_reply),
                    icon = Icons.AutoMirrored.Outlined.Reply,
                    emphasized = !replyAllFirst,
                    onClick = { onReply(target.id) },
                    modifier = Modifier.weight(1f),
                )
                if (target.canReplyAll) {
                    ReplyButton(
                        label = stringResource(R.string.message_button_reply_all),
                        icon = Icons.AutoMirrored.Outlined.ReplyAll,
                        emphasized = replyAllFirst,
                        onClick = { onReplyAll(target.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                ReplyButton(
                    label = stringResource(R.string.message_button_forward),
                    icon = Icons.AutoMirrored.Outlined.Forward,
                    emphasized = false,
                    onClick = { onForward(target.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReplyButton(
    label: String,
    icon: ImageVector,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (emphasized) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) { content() }
    }
}

@Composable
private fun SubjectHeader(subject: String, folder: Folder?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    ) {
        SelectionContainer {
            Text(
                text = subject.ifBlank { stringResource(R.string.no_subject) },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (folder != null) {
            Spacer(Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(folderIcon(folder.role), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(folderDisplayName(folder), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun NotFoundContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MailOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.message_not_found_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.message_not_found_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

/** Deleting [item] removes it for good: it is in Trash or Junk, or the account has no Trash. */
private fun isPermanentDelete(item: MessageItemUi, state: MessageUiState): Boolean =
    item.detail.folderRole == FolderRole.TRASH || item.detail.folderRole == FolderRole.JUNK ||
        state.folders.none { it.role == FolderRole.TRASH && it.selectable }

/** Compose-screen arguments prefilled from a mailto: link. */
private fun MailtoLink.toComposeArgs(accountId: Long): ComposeArgs =
    ComposeArgs(
        accountId = accountId,
        to = to.joinToString(", ").ifEmpty { null },
        cc = cc.joinToString(", ").ifEmpty { null },
        bcc = bcc.joinToString(", ").ifEmpty { null },
        subject = subject,
        body = body,
    )

/** Folder name for snackbars (outside composition), localised for well-known folders. */
private fun folderName(context: Context, folder: Folder): String = when (folder.role) {
    FolderRole.INBOX -> context.getString(R.string.folder_inbox)
    FolderRole.DRAFTS -> context.getString(R.string.folder_drafts)
    FolderRole.SENT -> context.getString(R.string.folder_sent)
    FolderRole.ARCHIVE -> context.getString(R.string.folder_archive)
    FolderRole.TRASH -> context.getString(R.string.folder_trash)
    FolderRole.JUNK -> context.getString(R.string.folder_junk)
    FolderRole.OTHER -> folder.displayName
}

private fun Context.copyAddress(address: EmailAddress) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(address.displayName, address.address))
}
