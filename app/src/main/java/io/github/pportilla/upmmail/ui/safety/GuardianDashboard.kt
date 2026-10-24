package io.github.pportilla.upmmail.ui.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.safety.Audience
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.ui.components.PrivacyPolicyButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuardianDashboard(
    container: AppContainer,
    onDone: () -> Unit,
    onManageAccounts: () -> Unit,
    onSetUpChild: () -> Unit,
) {
    val safety = container.safetyRepository
    val state by safety.state.collectAsStateWithLifecycle()
    val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle(emptyList())
    val inbox = remember(container) { safetyInbox(container) }
    val messages by inbox.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var newContact by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reportProvider by remember { mutableStateOf(false) }
    var reportsRevision by remember { mutableIntStateOf(0) }
    val reports = remember(state, reportsRevision) { runCatching { safety.reports() }.getOrDefault(emptyList()) }
    val message = messages.firstOrNull { it.summary.id == selected }
    fun act(action: () -> Unit) {
        failed = false
        try { action() } catch (_: Exception) { failed = true }
    }
    fun refresh() {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { accounts.forEach { container.mailRepository.refreshAccount(it.id).getOrThrow() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(selected) {
        val id = selected ?: return@LaunchedEffect
        try { container.mailRepository.ensureBodyLoaded(id).getOrThrow() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    BackHandler(enabled = message != null || reportProvider) { selected = null; reportProvider = false }

    when {
        reportProvider -> ProviderReportScreen(container, onBack = { reportProvider = false })
        message != null -> SafetyPage(stringResource(R.string.family_review_message), onBack = { selected = null }) {
            Text(stringResource(R.string.family_review_notice))
            MessageText(message)
            Text(stringResource(R.string.family_plaintext_notice))
            val sender = message.summary.from?.address.orEmpty().lowercase()
            if (sender !in state.approvedContacts && state.audience == Audience.SUPERVISED) {
                Text(stringResource(R.string.family_contact_not_approved))
                SafetyButton(stringResource(R.string.family_approve_contact), enabled = isSingleSafetyAddress(sender) && sender !in state.blockedSenders) {
                    act { safety.approveContact(sender) }
                }
            }
            if (state.audience == Audience.SUPERVISED) {
                SafetyButton(stringResource(R.string.family_approve_message), enabled = message.bodyLoaded && sender in state.approvedContacts && sender !in state.blockedSenders) {
                    act { safety.approveMessage(message); selected = null }
                }
                OutlinedButton(onClick = { act { safety.revokeMessage(message.summary.id); selected = null } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.family_revoke_message))
                }
            }
            SafetyButton(stringResource(R.string.family_block_sender), enabled = isSingleSafetyAddress(sender)) {
                act { safety.blockSender(sender); selected = null }
            }
            SafetyButton(stringResource(R.string.family_report_provider)) { reportProvider = true }
            if (failed) Text(stringResource(R.string.family_action_failed), color = MaterialTheme.colorScheme.error)
        }
        else -> SafetyPage(stringResource(R.string.family_dashboard), onBack = onDone) {
            Text(stringResource(R.string.family_guardian_session_notice))
            if (state.audience == Audience.ADULT) {
                SafetyButton(stringResource(R.string.family_set_up_child), onClick = onSetUpChild)
            } else {
                SafetyCheck(stringResource(R.string.family_allow_communication), state.communicationEnabled) {
                    act { safety.setCommunicationEnabled(it) }
                }
                Text(stringResource(R.string.family_communication_notice))
                SafetyButton(stringResource(R.string.family_revoke_all)) { act { safety.revokeAllApprovals() } }
                Text(stringResource(R.string.family_revoke_notice))
            }
            SafetyButton(stringResource(R.string.family_manage_accounts), onClick = onManageAccounts)
            SafetyButton(stringResource(R.string.family_refresh), enabled = !busy && accounts.isNotEmpty(), onClick = ::refresh)
            if (failed) Text(stringResource(R.string.family_action_failed), color = MaterialTheme.colorScheme.error)

            if (state.audience == Audience.SUPERVISED) {
                Text(stringResource(R.string.family_approved_contacts), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.family_contact_notice))
                OutlinedTextField(newContact, { newContact = it.take(254) }, label = { Text(stringResource(R.string.family_contact_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SafetyButton(stringResource(R.string.family_approve_contact), enabled = isSingleSafetyAddress(newContact.trim())) {
                    act { safety.approveContact(newContact.trim()); newContact = "" }
                }
                state.approvedContacts.sorted().forEach { address ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(address, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { act { safety.removeContact(address) } }) { Text(stringResource(R.string.family_remove)) }
                    }
                }
                Text(stringResource(R.string.family_concerns), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.family_concerns_notice))
                if (reports.isEmpty()) Text(stringResource(R.string.family_no_concerns))
                reports.forEach { report ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(report.reason)
                        if (messages.any { it.summary.id == report.messageId }) {
                            TextButton(onClick = { selected = report.messageId }) { Text(stringResource(R.string.family_review_message)) }
                        }
                        TextButton(onClick = { act { safety.clearReport(report.id); reportsRevision++ } }) { Text(stringResource(R.string.family_resolve_concern)) }
                    } }
                }
            }
            Text(stringResource(R.string.family_blocked_senders), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.family_block_notice))
            if (state.blockedSenders.isEmpty()) Text(stringResource(R.string.family_no_blocked_senders))
            state.blockedSenders.sorted().forEach { address ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(address, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { act { safety.unblockSender(address) } }) { Text(stringResource(R.string.family_unblock)) }
                }
            }
            SafetyButton(stringResource(R.string.family_report_provider)) { reportProvider = true }
            Text(stringResource(R.string.family_incoming_review), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.family_incoming_notice))
            if (messages.isEmpty()) Text(stringResource(R.string.family_no_messages))
            messages.forEach { item ->
                OutlinedButton(onClick = { selected = item.summary.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.summary.from?.address.orEmpty())
                        Text(item.summary.subject.ifBlank { stringResource(R.string.family_no_subject) }, style = MaterialTheme.typography.titleMedium)
                        if (state.audience == Audience.SUPERVISED && safety.isMessageApproved(item)) Text(stringResource(R.string.family_already_reviewed))
                    }
                }
            }
            PrivacyPolicyButton()
        }
    }
}

@Composable
private fun ProviderReportScreen(container: AppContainer, onBack: () -> Unit) {
    val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle(emptyList())
    val safetyState by container.safetyRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var accountId by remember { mutableStateOf<Long?>(null) }
    var recipient by remember { mutableStateOf("") }
    var concern by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Draft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    val subject = stringResource(R.string.family_report_subject)
    SafetyPage(stringResource(R.string.family_report_provider), onBack) {
        Text(stringResource(R.string.family_provider_report_notice))
        if (accounts.isEmpty()) Text(stringResource(R.string.family_report_needs_account))
        accounts.forEach {
            FilterChip(selected = it.id == account?.id, onClick = { accountId = it.id }, label = { Text(it.email) })
        }
        OutlinedTextField(recipient, { recipient = it.take(254) }, label = { Text(stringResource(R.string.family_provider_report_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(concern, { concern = it.take(5000) }, label = { Text(stringResource(R.string.family_concern_reason)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
        SafetyButton(stringResource(R.string.family_preview_report), enabled = account != null && isSingleSafetyAddress(recipient.trim()) && concern.isNotBlank() && !busy) {
            preview = Draft(accountId = account!!.id, to = listOf(EmailAddress(recipient.trim())), subject = subject, body = concern.trim())
        }
        if (queued) Text(stringResource(if (safetyState.audience == Audience.ADULT) R.string.family_report_queued_adult else R.string.family_report_queued))
        if (failed) Text(stringResource(R.string.family_action_failed), color = MaterialTheme.colorScheme.error)
    }
    preview?.let { draft ->
        AlertDialog(
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = { if (!busy) preview = null },
            title = { Text(stringResource(R.string.family_preview_report)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.family_report_exact_notice))
                Text(accounts.firstOrNull { it.id == draft.accountId }?.email.orEmpty())
                Text(draft.to.single().address, style = MaterialTheme.typography.titleMedium)
                Text(draft.subject)
                Text(draft.body)
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; failed = false
                scope.launch {
                    try {
                        if (container.safetyRepository.state.value.audience == Audience.SUPERVISED) {
                            container.safetyRepository.approveGuardianReport(draft)
                        }
                        container.composeRepository.send(draft, delaySeconds = 0)
                        queued = true; preview = null; concern = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true; preview = null }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.family_approve_and_send)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
