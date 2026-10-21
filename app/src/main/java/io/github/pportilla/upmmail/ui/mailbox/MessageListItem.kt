package io.github.pportilla.upmmail.ui.mailbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.ui.components.SenderAvatar
import io.github.pportilla.upmmail.ui.components.formatListDate

/**
 * One row of a message list (mailbox and search results): avatar, people, date, subject and
 * snippet, with unread / starred / attachment / draft markers.
 *
 * @param showRecipients show "To: …" instead of the sender (Sent and Drafts folders).
 * @param ownAddress the account address, shown as "me".
 * @param highlightQuery search text whose terms are highlighted.
 * @param folderLabel small folder tag (search results across folders).
 * @param onAvatarClick toggles selection from the avatar; null makes the avatar decorative.
 * @param onToggleStar star button; null shows the star only as an indicator.
 */
@Composable
fun MessageListItem(
    message: MessageSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showRecipients: Boolean = false,
    showSnippet: Boolean = true,
    ownAddress: String? = null,
    highlightQuery: String? = null,
    folderLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onAvatarClick: (() -> Unit)? = null,
    onToggleStar: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val unread = !message.isRead
    val me = stringResource(R.string.me)
    val people = when {
        showRecipients && message.to.isEmpty() -> stringResource(R.string.mailbox_no_recipients)
        showRecipients -> stringResource(R.string.mailbox_to_recipients, recipientNames(message.to, ownAddress, me))
        message.from == null -> stringResource(R.string.unknown_sender)
        ownAddress != null && message.from.address.equals(ownAddress, ignoreCase = true) -> me
        else -> message.from.displayName
    }
    val avatarPerson: EmailAddress? = if (showRecipients) message.to.firstOrNull() ?: message.from else message.from
    val subject = message.subject.ifBlank { stringResource(R.string.no_subject) }
    val date = remember(message.date) { formatListDate(message.date) }
    val terms = remember(highlightQuery) { highlightTerms(highlightQuery) }
    val highlight = SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)
    val unreadState = stringResource(R.string.mailbox_state_unread)
    val selectedState = stringResource(R.string.mailbox_state_selected)
    val isSelected = selected

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) stringResource(R.string.mailbox_action_select) else null,
            )
            .semantics {
                this.selected = isSelected
                val states = listOfNotNull(selectedState.takeIf { isSelected }, unreadState.takeIf { unread })
                if (states.isNotEmpty()) stateDescription = states.joinToString(", ")
            }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectableAvatar(
            person = avatarPerson,
            selected = selected,
            onClick = onAvatarClick,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = highlighted(people, terms, highlight),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.threadCount > 1) {
                        Text(
                            text = message.threadCount.toString(),
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (message.isAnswered) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = stringResource(R.string.mailbox_state_replied),
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
                if (unread) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                }
                Text(
                    text = date,
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (unread) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.isDraft) {
                            Text(
                                text = stringResource(R.string.mailbox_draft_label),
                                modifier = Modifier.padding(end = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.error,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = highlighted(subject, terms, highlight),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (folderLabel != null) FolderTag(folderLabel)
                        if (message.hasAttachments) {
                            Icon(
                                imageVector = Icons.Outlined.AttachFile,
                                contentDescription = stringResource(R.string.mailbox_has_attachments),
                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    }
                    if (showSnippet && message.snippet.isNotBlank()) {
                        Text(
                            text = highlighted(message.snippet, terms, highlight),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StarToggle(starred = message.isStarred, onToggle = onToggleStar)
            }
        }
    }
}

/** The sender avatar, replaced by a check mark while the row is selected. */
@Composable
internal fun SelectableAvatar(
    person: EmailAddress?,
    selected: Boolean,
    onClick: (() -> Unit)?,
    size: Dp = 40.dp,
) {
    val label = stringResource(if (selected) R.string.mailbox_action_deselect else R.string.mailbox_action_select)
    val clickModifier = if (onClick != null) {
        Modifier.clip(CircleShape).clickable(onClickLabel = label, role = Role.Checkbox, onClick = onClick)
    } else {
        Modifier
    }
    AnimatedContent(
        targetState = selected,
        modifier = clickModifier,
        transitionSpec = {
            (scaleIn(tween(150), initialScale = 0.6f) + fadeIn(tween(150)))
                .togetherWith(scaleOut(tween(100), targetScale = 0.6f) + fadeOut(tween(100)))
        },
        label = "avatar",
    ) { isSelected ->
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            SenderAvatar(name = person?.name, email = person?.address, size = size)
        }
    }
}

@Composable
private fun StarToggle(starred: Boolean, onToggle: (() -> Unit)?) {
    val description = stringResource(if (starred) R.string.action_unstar else R.string.action_star)
    if (onToggle == null) {
        if (starred) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.mailbox_state_starred),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        return
    }
    // A slightly smaller touch target than the 48 dp default keeps three-line rows compact.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = description,
                tint = if (starred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderTag(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [text] with the parts matching [terms] drawn in [style]. */
internal fun highlighted(text: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    val ranges = highlightRanges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}
