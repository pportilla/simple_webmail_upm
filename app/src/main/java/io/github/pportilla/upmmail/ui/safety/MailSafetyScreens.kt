package io.github.pportilla.upmmail.ui.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.ui.components.SampleMailNotice
import io.github.pportilla.upmmail.ui.components.accountAddressLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Local sender blocking is available to every user, without a separate profile or PIN. */
@Composable
fun BlockedSendersScreen(container: AppContainer, onBack: () -> Unit) {
    val safety = container.safetyRepository
    val state by safety.state.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var blockAddress by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    SafetyPage(stringResource(R.string.mail_blocked_senders), onBack) {
        Text(stringResource(R.string.mail_block_notice))
        OutlinedTextField(
            address, { address = it.take(254) },
            label = { Text(stringResource(R.string.mail_sender_address)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        SafetyButton(stringResource(R.string.mail_block_sender), enabled = isSingleSafetyAddress(address.trim())) {
            blockAddress = address.trim()
        }
        if (failed) Text(stringResource(R.string.mail_action_failed), color = MaterialTheme.colorScheme.error)
        if (state.blockedSenders.isEmpty()) Text(stringResource(R.string.mail_no_blocked_senders))
        state.blockedSenders.sorted().forEach { blocked ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(blocked, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { failed = runCatching { safety.unblockSender(blocked) }.isFailure }) {
                    Text(stringResource(R.string.mail_unblock))
                }
            }
        }
    }
    blockAddress?.let { selected ->
        BlockSenderDialog(selected, onDismiss = { blockAddress = null }) {
            failed = runCatching { safety.blockSender(selected) }.isFailure
            if (!failed) address = ""
            blockAddress = null
        }
    }
}

@Composable
fun BlockSenderDialog(address: String, onDismiss: () -> Unit, onBlock: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mail_block_sender)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(address, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.mail_block_notice))
        } },
        confirmButton = { TextButton(onClick = onBlock) { Text(stringResource(R.string.mail_block_sender)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A deliberate report through the user's own mailbox; original correspondence is never copied. */
@Composable
fun ProviderReportScreen(container: AppContainer, onBack: () -> Unit, initialAccountId: Long? = null) {
    val accounts by container.accountRepository.accounts.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var accountId by remember { mutableStateOf(initialAccountId) }
    var recipient by remember { mutableStateOf("") }
    var concern by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Draft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(false) }
    var queuedDemo by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    val subject = stringResource(R.string.mail_report_subject)
    BackHandler { if (!busy) onBack() }
    SafetyPage(stringResource(R.string.mail_report_provider), if (busy) null else onBack) {
        SampleMailNotice(account)
        Text(stringResource(if (account?.isDemo == true) R.string.demo_report_notice else R.string.mail_provider_report_notice))
        if (accounts.isEmpty()) Text(stringResource(R.string.mail_report_needs_account))
        accounts.forEach {
            FilterChip(
                selected = it.id == account?.id, onClick = { accountId = it.id }, enabled = !busy,
                label = { Text(accountAddressLabel(it)) },
            )
        }
        OutlinedTextField(
            recipient, { recipient = it.take(254) }, enabled = !busy,
            label = { Text(stringResource(R.string.mail_provider_report_address)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            concern, { concern = it.take(5000); queued = false }, enabled = !busy,
            label = { Text(stringResource(R.string.mail_report_description)) },
            minLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        SafetyButton(stringResource(R.string.mail_preview_report), enabled = account != null && isSingleSafetyAddress(recipient.trim()) && concern.isNotBlank() && !busy) {
            preview = Draft(accountId = account!!.id, to = listOf(EmailAddress(recipient.trim())), subject = subject, body = concern.trim())
        }
        if (queued) Text(stringResource(if (queuedDemo) R.string.demo_queued else R.string.mail_report_queued))
        if (failed) Text(stringResource(R.string.mail_action_failed), color = MaterialTheme.colorScheme.error)
    }
    preview?.let { draft ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text(stringResource(R.string.mail_preview_report)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mail_report_exact_notice))
                SampleMailNotice(accounts.firstOrNull { it.id == draft.accountId })
                Text(accountAddressLabel(accounts.firstOrNull { it.id == draft.accountId }))
                Text(draft.to.single().address, style = MaterialTheme.typography.titleMedium)
                Text(draft.subject)
                Text(draft.body)
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; failed = false
                scope.launch {
                    try {
                        container.composeRepository.send(draft, delaySeconds = 0)
                        queuedDemo = accounts.firstOrNull { it.id == draft.accountId }?.isDemo == true
                        queued = true; preview = null; concern = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true; preview = null }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (accounts.firstOrNull { it.id == draft.accountId }?.isDemo == true) R.string.demo_send else R.string.mail_send_report)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
