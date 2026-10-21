package io.github.pportilla.upmmail.ui.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.ui.components.SenderAvatar
import io.github.pportilla.upmmail.ui.components.formatListDate

/** Callbacks of the outbox list. */
internal class OutboxActions(
    val onRetry: (OutboxItem) -> Unit,
    val onEdit: (OutboxItem) -> Unit,
    val onDelete: (OutboxItem) -> Unit,
    val onGoToInbox: () -> Unit,
)

/** Queued, sending and failed outgoing messages of the current account. */
@Composable
internal fun OutboxContent(
    items: List<OutboxItem>,
    ownAddress: String?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    actions: OutboxActions,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        ScrollableEmptyState(contentPadding, modifier) {
            MailboxEmptyState(
                icon = Icons.Outlined.Outbox,
                title = stringResource(R.string.mailbox_empty_outbox_title),
                body = stringResource(R.string.mailbox_empty_outbox_body),
                action = {
                    OutlinedButton(onClick = actions.onGoToInbox) {
                        Text(stringResource(R.string.mailbox_go_to_inbox))
                    }
                },
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            OutboxRow(item, ownAddress, actions, Modifier.animateItem())
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun OutboxRow(item: OutboxItem, ownAddress: String?, actions: OutboxActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val draft = item.draft
    val recipients = draft.to + draft.cc + draft.bcc
    val me = stringResource(R.string.me)
    val people = if (recipients.isEmpty()) {
        stringResource(R.string.mailbox_no_recipients)
    } else {
        stringResource(R.string.mailbox_to_recipients, recipientNames(recipients, ownAddress, me))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            val first = recipients.firstOrNull()
            SenderAvatar(name = first?.name, email = first?.address)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = people,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = draft.subject.ifBlank { stringResource(R.string.no_subject) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutboxStatus(item)
                if (item.attachmentsCount() > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.mailbox_outbox_attachments, item.attachmentsCount(), item.attachmentsCount()),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.state) {
                OutboxState.FAILED -> {
                    TextButton(onClick = { actions.onDelete(item) }) { Text(stringResource(R.string.action_delete)) }
                    TextButton(onClick = { actions.onEdit(item) }) { Text(stringResource(R.string.mailbox_outbox_edit)) }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(onClick = { actions.onRetry(item) }) { Text(stringResource(R.string.action_retry)) }
                }
                OutboxState.QUEUED -> {
                    TextButton(onClick = { actions.onDelete(item) }) { Text(stringResource(R.string.action_delete)) }
                }
                // Being handed to the SMTP server right now: nothing can be changed.
                OutboxState.SENDING -> Spacer(Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

private fun OutboxItem.attachmentsCount(): Int = draft.attachments.size

@Composable
private fun OutboxStatus(item: OutboxItem) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.state) {
            OutboxState.SENDING -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            OutboxState.QUEUED -> Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            OutboxState.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = colors.error)
        }
        Spacer(Modifier.width(6.dp))
        val text = when (item.state) {
            OutboxState.SENDING -> stringResource(R.string.mailbox_outbox_sending)
            OutboxState.QUEUED -> if (item.sendAt > System.currentTimeMillis()) {
                stringResource(R.string.mailbox_outbox_scheduled, formatListDate(item.sendAt))
            } else {
                stringResource(R.string.mailbox_outbox_queued)
            }
            OutboxState.FAILED -> stringResource(R.string.mailbox_outbox_failed)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (item.state == OutboxState.FAILED) colors.error else colors.onSurfaceVariant,
        )
    }
    val error = item.lastError?.takeIf { it.isNotBlank() }
    if (error != null && item.state != OutboxState.SENDING) {
        Text(
            text = error,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (item.state == OutboxState.FAILED) colors.error else colors.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (item.state == OutboxState.FAILED && item.attempts > 1) {
        Text(
            text = pluralStringResource(R.plurals.mailbox_outbox_attempts, item.attempts, item.attempts),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}
