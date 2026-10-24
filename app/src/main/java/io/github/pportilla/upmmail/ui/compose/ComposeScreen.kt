package io.github.pportilla.upmmail.ui.compose

import io.github.pportilla.upmmail.ui.components.SampleMailNotice
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NoAccounts
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.safety.hasAdultExternalAccess
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.ui.components.AppSnackbarController
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.components.errorMessage
import io.github.pportilla.upmmail.ui.navigation.AppNavigator
import io.github.pportilla.upmmail.ui.navigation.ComposeArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * New message, reply, forward or draft editing: recipient chips with autocomplete, Cc/Bcc,
 * attachments, local autosave and undo-send. Sending, saving and discarding finish in the
 * application scope; their outcome is reported with app-wide snackbars after leaving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(args: ComposeArgs, navigator: AppNavigator) {
    val container = appContainer()
    // The local draft id survives process death, so a restored screen reopens what was written.
    var restoredDraftId by rememberSaveable { mutableLongStateOf(-1L) }
    val viewModel = viewModel { ComposeViewModel(container, args, restoredDraftId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val keyboard = LocalSoftwareKeyboardController.current
    val currentNavigator by rememberUpdatedState(navigator)
    val currentIsDemo by rememberUpdatedState(state.account?.isDemo == true)

    val requesters = remember { FocusTarget.entries.associateWith { FocusRequester() } }

    LaunchedEffect(state.savedDraftId) {
        if (state.savedDraftId > 0) restoredDraftId = state.savedDraftId
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ComposeEvent.NoRecipients -> snackbar.show(context.getString(R.string.compose_error_no_recipients))
                is ComposeEvent.InvalidRecipients -> snackbar.show(
                    context.resources.getQuantityString(
                        R.plurals.compose_error_invalid_recipients,
                        event.addresses.size,
                        event.addresses.size,
                        event.addresses.joinToString(", "),
                    ),
                )
                ComposeEvent.AttachmentsPending -> snackbar.show(context.getString(R.string.compose_error_attachments_pending))
                is ComposeEvent.AttachmentsFailed -> snackbar.show(
                    context.resources.getQuantityString(R.plurals.compose_attachments_failed, event.count, event.count),
                )
                ComposeEvent.NothingToSave -> snackbar.show(context.getString(R.string.compose_nothing_to_save))
                is ComposeEvent.Exit -> {
                    keyboard?.hide()
                    val reporter = ExitReporter(context, container, snackbar, currentNavigator)
                    currentNavigator.back()
                    reporter.report(event.exit)
                }
            }
        }
    }

    // Moves the focus once the target field is on screen (after loading, "Add Cc/Bcc", editing a chip).
    val focusRequest = state.focusRequest
    LaunchedEffect(focusRequest?.id) {
        val request = focusRequest ?: return@LaunchedEffect
        withFrameNanos { }
        if (requesters.getValue(request.target).tryRequestFocus()) keyboard?.show()
        viewModel.focusRequestHandled(request.id)
    }

    BackHandler(enabled = state.status != ComposeStatus.Finished) { viewModel.close() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onStop()
        // The saved state is written right after this, before the UI state could deliver the id.
        viewModel.localDraftId.takeIf { it > 0 }?.let { restoredDraftId = it }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (context.hasAdultExternalAccess()) viewModel.addAttachments(uris.map { it.toString() })
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onContactsPermissionResult(granted && context.hasAdultExternalAccess())
    }
    val onRecipientFocused = remember(context) {
        {
            if (!currentIsDemo && shouldAskForContacts(context)) {
                markContactsAsked(context)
                try {
                    contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                } catch (_: ActivityNotFoundException) {
                    // No permission UI (unusual builds): suggestions keep using the mail history.
                }
            }
        }
    }
    val pickFiles = {
        try {
            if (context.hasAdultExternalAccess()) attachmentPicker.launch(arrayOf("*/*"))
            else snackbar.show(context.getString(R.string.family_action_blocked))
        } catch (_: ActivityNotFoundException) {
            snackbar.show(context.getString(R.string.compose_no_file_picker))
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val ready = state.status == ComposeStatus.Ready || state.status == ComposeStatus.Finished
    // Pending text that Send would commit counts too. Derived, so typing does not recompose the screen.
    val canSend by remember(viewModel) {
        derivedStateOf {
            state.isReady &&
                (state.hasValidRecipient || RecipientKind.entries.any { RecipientTokenizer.hasValidAddress(viewModel.input(it).text) })
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ComposeTopBar(
                title = stringResource(state.title.res),
                showActions = ready,
                canSend = canSend,
                isDemo = state.account?.isDemo == true,
                showAddCcBcc = !state.ccBccExpanded,
                scrollBehavior = scrollBehavior,
                onClose = viewModel::close,
                onAttach = pickFiles,
                onSend = viewModel::send,
                onSaveDraft = viewModel::saveDraftAndClose,
                onDiscard = viewModel::discard,
                onAddCcBcc = viewModel::showCcBcc,
            )
        },
        snackbarHost = { AppSnackbarHost() },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (val status = state.status) {
            ComposeStatus.Loading -> Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ComposeStatus.Failed -> ComposeErrorState(
                status = status,
                onRetry = viewModel::retry,
                onClose = viewModel::close,
                onAddAccount = navigator::openAddAccount,
                modifier = contentModifier,
            )
            ComposeStatus.Ready, ComposeStatus.Finished -> ComposeForm(
                state = state,
                viewModel = viewModel,
                requesters = requesters,
                onRecipientFocused = onRecipientFocused,
                modifier = contentModifier,
            )
        }
    }

    when (state.dialog) {
        ComposeDialog.CONFIRM_DISCARD -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.compose_discard_title)) },
            text = { Text(stringResource(R.string.compose_discard_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDiscard) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        ComposeDialog.CONFIRM_NO_SUBJECT -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.compose_no_subject_title)) },
            text = { Text(stringResource(R.string.compose_no_subject_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSendWithoutSubject) { Text(stringResource(if (state.account?.isDemo == true) R.string.demo_send else R.string.action_send)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        null -> Unit
    }
}

private val ComposeTitle.res: Int
    get() = when (this) {
        ComposeTitle.NEW -> R.string.compose_title_new
        ComposeTitle.REPLY -> R.string.compose_title_reply
        ComposeTitle.REPLY_ALL -> R.string.compose_title_reply_all
        ComposeTitle.FORWARD -> R.string.compose_title_forward
        ComposeTitle.EDIT_DRAFT -> R.string.compose_title_edit_draft
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeTopBar(
    title: String,
    showActions: Boolean,
    canSend: Boolean,
    isDemo: Boolean,
    showAddCcBcc: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onClose: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onSaveDraft: () -> Unit,
    onDiscard: () -> Unit,
    onAddCcBcc: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
            }
        },
        actions = {
            if (showActions) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.compose_attach))
                }
                if (isDemo) {
                    TextButton(onClick = onSend, enabled = canSend) { Text(stringResource(R.string.demo_send)) }
                } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (showAddCcBcc) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_add_cc_bcc)) },
                                leadingIcon = { Icon(Icons.Outlined.GroupAdd, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAddCcBcc()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.compose_save_draft)) },
                            leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSaveDraft()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_discard)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDiscard()
                            },
                        )
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/** The form: From, To (+ Cc / Bcc), Subject, attachments and a body that fills the rest. */
@Composable
private fun ComposeForm(
    state: ComposeUiState,
    viewModel: ComposeViewModel,
    requesters: Map<FocusTarget, FocusRequester>,
    onRecipientFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    var headerHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier) {
        val viewportHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Column(Modifier.onSizeChanged { headerHeight = it.height }) {
                SampleMailNotice(state.account)
                FromRow(
                    accounts = state.accounts,
                    selected = state.account,
                    onSelect = { viewModel.selectAccount(it.id) },
                )
                HorizontalDivider()
                RecipientRow(
                    kind = RecipientKind.TO,
                    label = stringResource(R.string.compose_to),
                    state = state,
                    viewModel = viewModel,
                    focusRequester = requesters.getValue(FocusTarget.TO),
                    onFocused = onRecipientFocused,
                    next = requesters.getValue(if (state.ccBccExpanded) FocusTarget.CC else FocusTarget.SUBJECT),
                    trailing = if (!state.ccBccExpanded || state.canCollapseCcBcc) {
                        { CcBccToggle(expanded = state.ccBccExpanded, onClick = viewModel::toggleCcBcc) }
                    } else {
                        null
                    },
                )
                HorizontalDivider()
                if (state.ccBccExpanded) {
                    RecipientRow(
                        kind = RecipientKind.CC,
                        label = stringResource(R.string.compose_cc),
                        state = state,
                        viewModel = viewModel,
                        focusRequester = requesters.getValue(FocusTarget.CC),
                        onFocused = onRecipientFocused,
                        next = requesters.getValue(FocusTarget.BCC),
                    )
                    HorizontalDivider()
                    RecipientRow(
                        kind = RecipientKind.BCC,
                        label = stringResource(R.string.compose_bcc),
                        state = state,
                        viewModel = viewModel,
                        focusRequester = requesters.getValue(FocusTarget.BCC),
                        onFocused = onRecipientFocused,
                        next = requesters.getValue(FocusTarget.SUBJECT),
                    )
                    HorizontalDivider()
                }
                SubjectInput(viewModel, requesters)
                HorizontalDivider()
                if (state.attachmentsTooLarge) LargeAttachmentsBanner(totalBytes = state.attachmentsSize, modifier = Modifier.padding(top = 4.dp))
                AttachmentList(
                    attachments = state.attachments,
                    importing = state.importingAttachments,
                    onRemove = viewModel::removeAttachment,
                )
            }
            // The body takes at least the rest of the screen, so tapping anywhere below starts typing.
            val remaining = with(density) { (viewportHeight.toPx() - headerHeight).coerceAtLeast(0f).toDp() }
            BodyInput(viewModel, requesters, Modifier.heightIn(min = maxOf(remaining, 160.dp)))
        }
    }
}

// Separate composables so typing only recomposes the field being edited.
@Composable
private fun SubjectInput(viewModel: ComposeViewModel, requesters: Map<FocusTarget, FocusRequester>) {
    SubjectField(
        value = viewModel.subject,
        onValueChange = viewModel::onSubjectChange,
        focusRequester = requesters.getValue(FocusTarget.SUBJECT),
        onNext = { requesters.getValue(FocusTarget.BODY).tryRequestFocus() },
    )
}

@Composable
private fun BodyInput(viewModel: ComposeViewModel, requesters: Map<FocusTarget, FocusRequester>, modifier: Modifier) {
    BodyField(
        value = viewModel.body,
        onValueChange = viewModel::onBodyChange,
        focusRequester = requesters.getValue(FocusTarget.BODY),
        modifier = modifier,
    )
}

@Composable
private fun RecipientRow(
    kind: RecipientKind,
    label: String,
    state: ComposeUiState,
    viewModel: ComposeViewModel,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    next: FocusRequester,
    trailing: (@Composable () -> Unit)? = null,
) {
    val suggestions = state.suggestions?.takeIf { it.kind == kind && state.focusedField == kind && viewModel.input(kind).text.isNotBlank() }
    RecipientField(
        label = label,
        recipients = state.recipients(kind),
        input = viewModel.input(kind),
        onInputChange = { viewModel.onInputChange(kind, it) },
        suggestions = suggestions?.items.orEmpty(),
        onSuggestionClick = { viewModel.selectSuggestion(kind, it) },
        onRemove = { viewModel.removeRecipient(kind, it) },
        onEdit = { viewModel.editRecipient(kind, it) },
        onBackspaceOnEmpty = { viewModel.onBackspaceOnEmpty(kind) },
        onImeAction = { viewModel.onRecipientImeAction(kind) },
        onFocusChanged = { focused ->
            viewModel.onRecipientFocusChanged(kind, focused)
            if (focused) onFocused()
        },
        focusRequester = focusRequester,
        selectedIndex = state.selectedChip?.takeIf { it.kind == kind }?.index,
        highlightInvalid = state.highlightInvalid,
        imeAction = ImeAction.Next,
        onMoveNext = { next.tryRequestFocus() },
        trailing = trailing,
    )
}

@Composable
private fun CcBccToggle(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(if (expanded) R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc),
        )
    }
}

@Composable
private fun ComposeErrorState(
    status: ComposeStatus.Failed,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val (icon, title, body) = when (status.problem) {
        LoadProblem.ERROR -> Triple(Icons.Outlined.ErrorOutline, stringResource(R.string.compose_error_title), context.errorMessage(status.error))
        LoadProblem.DRAFT_MISSING -> Triple(
            Icons.Outlined.SearchOff,
            stringResource(R.string.compose_draft_missing_title),
            stringResource(R.string.compose_draft_missing_body),
        )
        LoadProblem.NO_ACCOUNT -> Triple(
            Icons.Outlined.NoAccounts,
            stringResource(R.string.compose_no_account_title),
            stringResource(R.string.compose_no_account_body),
        )
    }
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                when (status.problem) {
                    LoadProblem.ERROR -> Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    LoadProblem.NO_ACCOUNT -> Button(onClick = onAddAccount) { Text(stringResource(R.string.compose_add_account)) }
                    LoadProblem.DRAFT_MISSING -> Unit
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- after leaving the screen

/**
 * Reports how the screen was left once it is gone: "Sending… Undo", "Draft saved · Discard"...
 * Texts are resolved up front so no Activity is kept alive by the pending work.
 */
private class ExitReporter(
    context: Context,
    private val container: AppContainer,
    private val snackbar: AppSnackbarController,
    private val navigator: AppNavigator,
) {
    private val online = context.isOnline()
    private val sending = context.getString(if (online) R.string.compose_sending else R.string.compose_queued_offline)
    private val demoSending = context.getString(R.string.demo_queued)
    private val demoAlreadySent = context.getString(R.string.demo_already_sent)
    private val undo = context.getString(R.string.action_undo)
    private val discard = context.getString(R.string.action_discard)
    private val open = context.getString(R.string.compose_open_draft)
    private val alreadySent = context.getString(R.string.compose_already_sent)
    private val undoFailed = context.getString(R.string.compose_undo_failed)
    private val sendFailedSaved = context.getString(R.string.compose_send_failed_saved)
    private val sendFailed = context.getString(R.string.compose_send_failed)
    private val draftSaved = context.getString(R.string.compose_draft_saved)
    private val draftSaveFailed = context.getString(R.string.compose_draft_save_failed)
    private val draftDiscarded = context.getString(R.string.compose_draft_discarded)
    private val compose = container.composeRepository

    fun report(exit: ComposeExit) {
        when (exit) {
            ComposeExit.Closed -> Unit
            ComposeExit.Discarded -> snackbar.show(draftDiscarded)
            is ComposeExit.DraftSaved -> reportDraftSaved(exit)
            is ComposeExit.Sending -> reportSending(exit)
        }
    }

    private fun reportDraftSaved(exit: ComposeExit.DraftSaved) {
        container.applicationScope.launch {
            val draft = awaitOrNull { exit.draft.await() }
            onMain {
                if (draft == null) {
                    snackbar.show(draftSaveFailed)
                } else {
                    snackbar.show(draftSaved, actionLabel = discard, onAction = { discardSaved(draft) })
                }
            }
        }
    }

    private fun discardSaved(draft: Draft) {
        container.applicationScope.launch {
            val done = awaitOrNull { compose.discardDraft(draft) } != null
            if (done) onMain { snackbar.show(draftDiscarded) }
        }
    }

    private fun reportSending(exit: ComposeExit.Sending) {
        val sending = if (exit.isDemo) demoSending else sending
        if (exit.undoSeconds > 0) {
            // Undo stays available for the whole undo window, which may exceed SnackbarDuration.Long.
            snackbar.show(
                message = sending,
                actionLabel = undo,
                timeoutMillis = exit.undoSeconds * 1000L,
                onAction = { undoSend(exit) },
            )
        } else {
            snackbar.show(sending)
        }
        container.applicationScope.launch {
            val result = awaitOrNull { exit.result.await() }
            if (result is SendResult.Queued) return@launch
            val draftId = (result as? SendResult.Failed)?.draftId
            onMain {
                if (draftId != null) {
                    snackbar.show(sendFailedSaved, actionLabel = open, onAction = {
                        navigator.openCompose(ComposeArgs(draftId = draftId))
                    })
                } else {
                    snackbar.show(sendFailed)
                }
            }
        }
    }

    private sealed interface UndoOutcome {
        data class Restored(val accountId: Long, val draftId: Long) : UndoOutcome

        data object AlreadySent : UndoOutcome

        data object Failed : UndoOutcome
    }

    private fun undoSend(exit: ComposeExit.Sending) {
        container.applicationScope.launch {
            // A failure to queue is reported on its own.
            val queued = awaitOrNull { exit.result.await() } as? SendResult.Queued ?: return@launch
            val outcome = awaitOrNull {
                val draft = compose.cancelSend(queued.outboxId)
                if (draft == null) {
                    UndoOutcome.AlreadySent
                } else {
                    UndoOutcome.Restored(draft.accountId, compose.saveDraft(draft, upload = false))
                }
            } ?: UndoOutcome.Failed
            onMain {
                when (outcome) {
                    is UndoOutcome.Restored -> navigator.openCompose(ComposeArgs(accountId = outcome.accountId, draftId = outcome.draftId))
                    UndoOutcome.AlreadySent -> snackbar.show(if (exit.isDemo) demoAlreadySent else alreadySent)
                    UndoOutcome.Failed -> snackbar.show(undoFailed)
                }
            }
        }
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private suspend fun <T> awaitOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

private const val PREFS = "compose_ui"
private const val KEY_CONTACTS_ASKED = "contacts_permission_asked"

/** True the first time a recipient field is focused without the contacts permission. */
private fun shouldAskForContacts(context: Context): Boolean {
    if (!context.hasAdultExternalAccess()) return false
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) return false
    return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONTACTS_ASKED, false)
}

private fun markContactsAsked(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONTACTS_ASKED, true).apply()
}

/** Best guess at connectivity, only to word the "Sending…" snackbar. */
private fun Context.isOnline(): Boolean = try {
    val manager = getSystemService(ConnectivityManager::class.java)
    val network = manager?.activeNetwork
    val capabilities = network?.let { manager.getNetworkCapabilities(it) }
    manager == null || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
} catch (_: SecurityException) {
    true
}
