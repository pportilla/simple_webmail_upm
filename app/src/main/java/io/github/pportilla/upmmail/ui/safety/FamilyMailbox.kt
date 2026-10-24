package io.github.pportilla.upmmail.ui.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.ui.components.PrivacyPolicyButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Keep complete snapshots so approval is checked against the text currently displayed. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun safetyInbox(container: AppContainer): Flow<List<MessageDetail>> =
    container.accountRepository.accounts.flatMapLatest { accounts ->
        if (accounts.isEmpty()) flowOf(emptyList()) else combine(accounts.map { account ->
            container.mailRepository.observeFolders(account.id).flatMapLatest { folders ->
                val inboxes = folders.filter { it.role == FolderRole.INBOX }
                if (inboxes.isEmpty()) flowOf(emptyList()) else combine(inboxes.map { folder ->
                    container.mailRepository.observeMessages(folder.id, 80, conversations = false).flatMapLatest { summaries ->
                        if (summaries.isEmpty()) flowOf(emptyList()) else combine(summaries.map {
                            container.mailRepository.observeMessage(it.id)
                        }) { details -> details.filterNotNull() }
                    }
                }) { lists -> lists.flatMap { it } }
            }
        }) { lists -> lists.flatMap { it }.sortedByDescending { it.summary.date } }
    }

@Composable
internal fun ChildHome(container: AppContainer, onGuardian: () -> Unit) {
    val safety = container.safetyRepository
    val state by safety.state.collectAsStateWithLifecycle()
    val inbox = remember(container) { safetyInbox(container) }
    val messages by inbox.collectAsStateWithLifecycle(emptyList())
    // The repository already filters. Recheck locally as well while a send-approval dialog
    // temporarily unlocks its guardian session; that must not reveal any other messages.
    val reviewed = remember(messages, state) {
        if (state.communicationEnabled) messages.filter(safety::isMessageApproved) else emptyList()
    }
    var selected by remember { mutableStateOf<Long?>(null) }
    var composing by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<MessageDetail?>(null) }
    var actionFailed by remember { mutableStateOf(false) }
    val message = reviewed.firstOrNull { it.summary.id == selected }
    BackHandler(enabled = selected != null || composing) { selected = null; composing = false }

    when {
        composing -> ChildComposer(container, onBack = { composing = false })
        message != null -> SafetyPage(stringResource(R.string.family_read_message), onBack = { selected = null }) {
            Text(stringResource(R.string.family_read_together))
            SafetyButton(stringResource(R.string.family_ask_guardian)) { report = message }
            val sender = message.summary.from?.address.orEmpty()
            if (isSingleSafetyAddress(sender)) {
                SafetyButton(stringResource(R.string.family_child_block)) {
                    try { safety.blockSender(sender); selected = null }
                    catch (_: Exception) { actionFailed = true }
                }
            }
            Text(stringResource(R.string.family_child_block_notice))
            if (actionFailed) Text(stringResource(R.string.family_action_failed), color = MaterialTheme.colorScheme.error)
            MessageText(message)
            Text(stringResource(R.string.family_ask_guardian_notice))
        }
        else -> SafetyPage(stringResource(R.string.family_home)) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.family_safety_reminder), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.family_read_together))
            if (!state.communicationEnabled) Text(stringResource(R.string.family_communication_paused))
            SafetyButton(stringResource(R.string.family_guardian_controls), onClick = onGuardian)
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(40.dp))
            SafetyButton(stringResource(R.string.family_write_message), enabled = state.communicationEnabled && state.approvedContacts.isNotEmpty()) {
                composing = true
            }
            Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.family_reviewed_inbox), style = MaterialTheme.typography.headlineSmall)
            if (reviewed.isEmpty()) Text(stringResource(R.string.family_no_reviewed_messages))
            reviewed.forEach { item ->
                OutlinedButton(onClick = { selected = item.summary.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.summary.subject.ifBlank { stringResource(R.string.family_no_subject) }, style = MaterialTheme.typography.titleLarge)
                        Text(item.summary.from?.address.orEmpty())
                    }
                }
            }
            PrivacyPolicyButton()
        }
    }
    report?.let { item ->
        ConcernDialog(container, item, onDismiss = { report = null })
    }
}

@Composable
internal fun MessageText(message: MessageDetail) {
    Text(message.summary.from?.address.orEmpty(), style = MaterialTheme.typography.titleMedium)
    Text(message.summary.subject.ifBlank { stringResource(R.string.family_no_subject) }, style = MaterialTheme.typography.headlineSmall)
    if (message.bodyLoaded) {
        // Supervised repository projections contain plain text only. Never add link annotations,
        // SelectionContainer, WebView, attachment controls or external intents to this surface.
        Text(message.bodyText.orEmpty(), style = MaterialTheme.typography.bodyLarge)
    } else Text(stringResource(R.string.family_message_not_loaded))
}

@Composable
private fun ConcernDialog(container: AppContainer, message: MessageDetail, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family_ask_guardian)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.family_ask_guardian_notice))
            OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text(stringResource(R.string.family_concern_reason)) }, modifier = Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.family_action_failed))
        } },
        confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = {
            try {
                container.safetyRepository.reportMessage(message.summary.id, reason.trim())
                onDismiss()
            } catch (_: Exception) { failed = true }
        }) { Text(stringResource(R.string.family_save_concern)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChildComposer(container: AppContainer, onBack: () -> Unit) {
    val state by container.safetyRepository.state.collectAsStateWithLifecycle()
    val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle(emptyList())
    var accountId by remember { mutableStateOf<Long?>(null) }
    var recipient by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var reminded by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Draft?>(null) }
    var queued by remember { mutableStateOf(false) }
    val selectedAccount = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    val approved = state.approvedContacts.filterNot { it in state.blockedSenders }.sorted()
    SafetyPage(stringResource(R.string.family_write_message), onBack) {
        Text(stringResource(R.string.family_safety_reminder), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.family_read_together))
        SafetyCheck(stringResource(R.string.family_reminder_ack), reminded) { reminded = it }
        if (queued) Text(stringResource(R.string.family_message_queued))
        if (!state.communicationEnabled) Text(stringResource(R.string.family_communication_paused))
        if (accounts.size > 1) {
            Text(stringResource(R.string.family_from_account))
            accounts.forEach { account ->
                FilterChip(selected = selectedAccount?.id == account.id, onClick = { accountId = account.id }, label = { Text(account.email) })
            }
        }
        Text(stringResource(R.string.family_choose_contact), style = MaterialTheme.typography.titleMedium)
        approved.forEach { address ->
            FilterChip(selected = recipient == address, onClick = { recipient = address }, label = { Text(address) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        OutlinedTextField(subject, { subject = it.take(200) }, label = { Text(stringResource(R.string.family_subject)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it.take(10000) }, label = { Text(stringResource(R.string.family_message_text)) }, minLines = 6, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.family_plaintext_notice))
        SafetyButton(stringResource(R.string.family_request_send), enabled = state.communicationEnabled && reminded && selectedAccount != null && recipient in approved && body.isNotBlank()) {
            review = Draft(accountId = selectedAccount!!.id, to = listOf(EmailAddress(recipient)), subject = subject, body = body)
        }
    }
    review?.let { draft ->
        ChildSendApprovalDialog(container, draft, onDismiss = { review = null }) {
            review = null
            subject = ""; body = ""; reminded = false; queued = true
        }
    }
}

@Composable
private fun ChildSendApprovalDialog(container: AppContainer, draft: Draft, onDismiss: () -> Unit, onQueued: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle(emptyList())
    val state by container.safetyRepository.state.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var authenticated by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pin = ""; authenticated = false; reviewed = false }
    fun close() { container.safetyRepository.lockGuardian(); onDismiss() }
    LaunchedEffect(state.guardianUnlocked) { if (!state.guardianUnlocked) { authenticated = false; reviewed = false } }
    DisposableEffect(Unit) { onDispose { container.safetyRepository.lockGuardian() } }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.family_approve_send)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.family_send_review_notice))
            Text(accounts.firstOrNull { it.id == draft.accountId }?.email.orEmpty())
            Text(draft.to.single().address, style = MaterialTheme.typography.titleMedium)
            Text(draft.subject, style = MaterialTheme.typography.titleLarge)
            Text(draft.body)
            if (!authenticated) PinField(pin, { pin = it }, stringResource(R.string.family_pin), enabled = !busy)
            else SafetyCheck(stringResource(R.string.family_exact_send_ack), reviewed) { reviewed = it }
            if (failed) Text(stringResource(R.string.family_action_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !busy && (if (authenticated) reviewed && state.guardianUnlocked else pin.length >= 6), onClick = {
            busy = true; failed = false
            scope.launch {
                try {
                    if (!authenticated) {
                        authenticated = container.safetyRepository.unlockGuardian(pin)
                        pin = ""
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            container.safetyRepository.lockGuardian()
                            authenticated = false
                        }
                        failed = !authenticated
                    } else {
                        container.safetyRepository.approveOutgoing(draft)
                        container.composeRepository.send(draft, delaySeconds = 0)
                        container.safetyRepository.lockGuardian()
                        onQueued()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(if (authenticated) R.string.family_approve_and_send else R.string.family_unlock)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = ::close) { Text(stringResource(R.string.action_cancel)) } },
    )
}
