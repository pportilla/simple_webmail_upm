package io.github.pportilla.upmmail.ui.message

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.ui.components.SenderAvatar
import io.github.pportilla.upmmail.ui.components.errorMessage
import io.github.pportilla.upmmail.ui.components.formatFullDate
import io.github.pportilla.upmmail.ui.components.formatListDate

/** Callbacks of the message cards, grouped so the cards take one stable parameter. */
@Immutable
internal class MessageCardActions(
    val onToggleExpanded: (Long) -> Unit,
    val onToggleDetails: (Long) -> Unit,
    val onStar: (id: Long, starred: Boolean) -> Unit,
    val onReply: (Long) -> Unit,
    val onReplyAll: (Long) -> Unit,
    val onForward: (Long) -> Unit,
    val onMarkUnreadFrom: (Long) -> Unit,
    val onDelete: (MessageItemUi) -> Unit,
    val onEditDraft: (Long) -> Unit,
    val onRetry: (Long) -> Unit,
    val onShowImages: (Long) -> Unit,
    val onTrustSender: (String) -> Unit,
    val onAddressClick: (EmailAddress) -> Unit,
    val onAddressLongClick: (EmailAddress) -> Unit,
    /** Searches the messages sent by the address. */
    val onSearchFrom: (EmailAddress) -> Unit,
    /** Searches the messages sent to the address (To or Cc). */
    val onSearchTo: (EmailAddress) -> Unit,
    val onLinkClick: (Uri) -> Unit,
    val onOpenAttachment: (Attachment) -> Unit,
    val onShareAttachment: (Attachment) -> Unit,
    val onSaveAttachment: (Attachment) -> Unit,
)

/** Gold used for stars, readable on light and dark surfaces. */
@Composable
private fun starColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFDD663) else Color(0xFFF4B400)

/** One message of the conversation: a one-line summary when collapsed, everything when expanded. */
@Composable
internal fun MessageCard(
    item: MessageItemUi,
    selfAddresses: Set<String>,
    actions: MessageCardActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (item.expanded) {
            ExpandedMessage(item, selfAddresses, actions)
        } else {
            CollapsedMessage(item, actions)
        }
    }
}

@Composable
private fun senderName(item: MessageItemUi): String {
    val from = item.detail.summary.from
    return when {
        item.fromSelf -> stringResource(R.string.me)
        from == null -> stringResource(R.string.unknown_sender)
        else -> from.displayName
    }
}

@Composable
private fun CollapsedMessage(item: MessageItemUi, actions: MessageCardActions) {
    val summary = item.detail.summary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.message_expand)) { actions.onToggleExpanded(item.id) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SenderAvatar(name = summary.from?.name, email = summary.from?.address)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = senderName(item),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (item.wasUnread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isDraft) DraftLabel()
                Spacer(Modifier.weight(1f))
                if (summary.hasAttachments) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.message_has_attachments),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (summary.isStarred) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.message_starred),
                        modifier = Modifier.size(16.dp),
                        tint = starColor(),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = formatListDate(summary.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = summary.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DraftLabel() {
    Text(
        text = stringResource(R.string.message_draft_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 6.dp),
        maxLines = 1,
    )
}

@Composable
private fun ExpandedMessage(item: MessageItemUi, selfAddresses: Set<String>, actions: MessageCardActions) {
    val detail = item.detail
    Column(modifier = Modifier.fillMaxWidth()) {
        MessageHeader(item, selfAddresses, actions)
        AnimatedVisibility(visible = item.showDetails) {
            MessageDetailsBlock(item, actions)
        }
        if (item.isDraft) {
            FilledTonalButton(
                onClick = { actions.onEditDraft(item.id) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.message_edit_draft))
            }
        }
        if (item.showRemoteImagesBanner) {
            RemoteImagesBanner(
                sender = detail.summary.from?.address?.takeIf { it.isNotBlank() },
                onShowImages = { actions.onShowImages(item.id) },
                onTrustSender = actions.onTrustSender,
            )
        }
        MessageBodySection(item, actions)
        if (item.attachments.isNotEmpty()) {
            AttachmentList(
                attachments = item.attachments,
                onOpen = actions.onOpenAttachment,
                onShare = actions.onShareAttachment,
                onSave = actions.onSaveAttachment,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun MessageHeader(item: MessageItemUi, selfAddresses: Set<String>, actions: MessageCardActions) {
    val summary = item.detail.summary
    val headerModifier = if (item.collapsible) {
        Modifier.clickable(onClickLabel = stringResource(R.string.message_collapse)) { actions.onToggleExpanded(item.id) }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(headerModifier)
            .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SenderAvatar(name = summary.from?.name, email = summary.from?.address)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = senderName(item),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (item.wasUnread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isDraft) DraftLabel()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatListDate(summary.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            RecipientLine(item, selfAddresses, onClick = { actions.onToggleDetails(item.id) })
        }
        IconButton(onClick = { actions.onStar(item.id, !summary.isStarred) }) {
            Icon(
                imageVector = if (summary.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(if (summary.isStarred) R.string.action_unstar else R.string.action_star),
                tint = if (summary.isStarred) starColor() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MessageMenu(item, actions)
    }
}

@Composable
private fun RecipientLine(item: MessageItemUi, selfAddresses: Set<String>, onClick: () -> Unit) {
    val detail = item.detail
    val me = stringResource(R.string.me)
    val summary = remember(detail.summary.to, detail.cc, detail.bcc, selfAddresses, me) {
        recipientSummary(detail.summary.to, detail.cc, detail.bcc, selfAddresses, me)
    }
    val names = summary.names.joinToString(", ")
    val text = when {
        summary.isEmpty -> stringResource(R.string.message_to_undisclosed)
        summary.more > 0 -> pluralStringResource(R.plurals.message_to_summary_more, summary.more, names, summary.more)
        else -> stringResource(R.string.message_to_summary, names)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                onClickLabel = stringResource(if (item.showDetails) R.string.message_hide_details else R.string.message_show_details),
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = if (item.showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageMenu(item: MessageItemUi, actions: MessageCardActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.message_more_options_message))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun select(action: () -> Unit) {
                expanded = false
                action()
            }
            if (item.isDraft) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_edit_draft)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { select { actions.onEditDraft(item.id) } },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_reply)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null) },
                    onClick = { select { actions.onReply(item.id) } },
                )
                if (item.canReplyAll) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reply_all)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ReplyAll, contentDescription = null) },
                        onClick = { select { actions.onReplyAll(item.id) } },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_forward)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Forward, contentDescription = null) },
                    onClick = { select { actions.onForward(item.id) } },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_mark_unread_from_here)) },
                    leadingIcon = { Icon(Icons.Outlined.MarkEmailUnread, contentDescription = null) },
                    onClick = { select { actions.onMarkUnreadFrom(item.id) } },
                )
            }
            if (item.canDeleteSingly) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_delete_this)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { select { actions.onDelete(item) } },
                )
            }
        }
    }
}

@Composable
private fun MessageDetailsBlock(item: MessageItemUi, actions: MessageCardActions) {
    val detail = item.detail
    val summary = detail.summary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            summary.from?.let {
                DetailAddresses(stringResource(R.string.message_details_from), listOf(it), actions, actions.onSearchFrom, R.string.message_search_from)
            }
            DetailAddresses(stringResource(R.string.message_details_to), summary.to, actions, actions.onSearchTo, R.string.message_search_to)
            DetailAddresses(stringResource(R.string.message_details_cc), detail.cc, actions, actions.onSearchTo, R.string.message_search_to)
            // Searches match To and Cc only: no search for Bcc and Reply-To addresses.
            DetailAddresses(stringResource(R.string.message_details_bcc), detail.bcc, actions)
            DetailAddresses(stringResource(R.string.message_details_reply_to), detail.replyTo, actions)
            DetailRow(stringResource(R.string.message_details_date)) {
                Text(
                    text = formatFullDate(summary.date),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(84.dp)
                .padding(top = 2.dp, end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * The addresses of one header. Tapping one offers to write to it, to search its messages (with
 * [onSearch], labelled [searchLabel]) and to copy it; a long press copies it straight away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailAddresses(
    label: String,
    addresses: List<EmailAddress>,
    actions: MessageCardActions,
    onSearch: ((EmailAddress) -> Unit)? = null,
    @StringRes searchLabel: Int = 0,
) {
    if (addresses.isEmpty()) return
    DetailRow(label) {
        addresses.forEach { address ->
            val name = address.name?.takeIf { it.isNotBlank() }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .combinedClickable(
                            onClickLabel = stringResource(R.string.message_address_options),
                            onLongClickLabel = stringResource(R.string.message_copy_address),
                            onLongClick = { actions.onAddressLongClick(address) },
                            onClick = { menuOpen = true },
                        )
                        .padding(vertical = 2.dp),
                ) {
                    if (name != null) {
                        Text(text = name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = address.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(text = address.address, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_write_to, address.displayName)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onAddressClick(address)
                        },
                    )
                    if (onSearch != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(searchLabel, address.displayName)) },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSearch(address)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_copy_address)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onAddressLongClick(address)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteImagesBanner(sender: String?, onShowImages: () -> Unit, onTrustSender: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.HideImage, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.message_images_hidden), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.message_images_hidden_detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FlowRow {
                TextButton(onClick = onShowImages) { Text(stringResource(R.string.message_show_images)) }
                if (sender != null) {
                    TextButton(onClick = { onTrustSender(sender) }) {
                        Text(stringResource(R.string.message_always_show_images, sender))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBodySection(item: MessageItemUi, actions: MessageCardActions) {
    when (val body = item.body) {
        BodyState.Loading -> BodyPlaceholder {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.message_loading_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is BodyState.Error -> BodyPlaceholder {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.message_body_error),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = LocalContext.current.errorMessage(body.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { actions.onRetry(item.id) }) { Text(stringResource(R.string.action_retry)) }
        }
        is BodyState.Ready -> {
            val rendered = body.body
            if (!rendered.complete) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
            if (!rendered.isHtml && rendered.isEmpty) {
                Text(
                    text = stringResource(R.string.message_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            } else {
                MessageBodyView(
                    body = rendered,
                    remoteImagesAllowed = item.remoteImagesAllowed,
                    onLinkClick = actions.onLinkClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BodyPlaceholder(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

/** Stand-in for older messages folded together; tapping it shows them all. */
@Composable
internal fun HiddenMessagesRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.message_more_messages, count, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
