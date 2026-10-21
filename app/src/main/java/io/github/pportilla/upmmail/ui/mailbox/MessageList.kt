package io.github.pportilla.upmmail.ui.mailbox

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SwipeAction
import kotlinx.coroutines.delay

/** Callbacks of the message list, grouped to keep signatures short. */
internal class MessageListActions(
    val onOpen: (MessageSummary) -> Unit,
    val onToggleSelection: (Long) -> Unit,
    val onToggleStar: (MessageSummary) -> Unit,
    val onSwipe: (MessageSummary, SwipeAction) -> Boolean,
    val onLoadMore: () -> Unit,
    val onRetryLoadMore: () -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * The folder's message list with pull to refresh, sync progress, swipe actions, paging and the
 * loading / empty states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageListContent(
    state: MailboxUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    actions: MessageListActions,
    modifier: Modifier = Modifier,
) {
    val folder = state.folder
    val role = folder?.role
    val messages = state.messages
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        val neverSynced = folder != null && folder.lastSyncedAt == null
        when {
            !state.messagesLoaded || (messages.isEmpty() && neverSynced && (state.isSyncing || state.isRefreshing)) ->
                MailboxLoadingState(stringResource(R.string.mailbox_loading_messages), contentPadding)

            messages.isEmpty() && neverSynced ->
                ScrollableEmptyState(contentPadding) {
                    MailboxEmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = stringResource(R.string.mailbox_empty_not_synced_title),
                        body = stringResource(R.string.mailbox_empty_not_synced_body),
                        action = {
                            OutlinedButton(onClick = actions.onRefresh) { Text(stringResource(R.string.action_refresh)) }
                        },
                    )
                }

            messages.isEmpty() ->
                ScrollableEmptyState(contentPadding) {
                    val empty = emptyStateFor(role)
                    MailboxEmptyState(
                        icon = empty.icon,
                        title = stringResource(empty.title),
                        body = stringResource(empty.body),
                    )
                }

            else -> MessageRows(state, listState, contentPadding, actions)
        }
        if (state.isSyncing && !state.isRefreshing && state.messagesLoaded) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun MessageRows(
    state: MailboxUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    actions: MessageListActions,
) {
    val messages = state.messages
    val role = state.folder?.role
    val showRecipients = showsRecipients(role)
    val permanentDelete = deletesPermanently(role)
    val startToEnd = effectiveSwipeAction(state.settings.swipeStartToEnd, role)
    val endToStart = effectiveSwipeAction(state.settings.swipeEndToStart, role)
    val ownAddress = state.account?.email
    val selectionMode = state.selectionMode

    // Paging: ask for more when the last visible row is close to the end.
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            // The last item is the footer.
            lastVisible >= info.totalItemsCount - 1 - LOAD_AHEAD
        }
    }
    val currentLoadMore by rememberUpdatedState(actions.onLoadMore)
    LaunchedEffect(nearEnd, state.paging, messages.size) {
        if (nearEnd && messages.isNotEmpty()) currentLoadMore()
    }

    // New mail arriving while the list is at the top: keep the top visible instead of letting
    // the list hold on to the previously first row.
    val firstId = messages.firstOrNull()?.id
    var previousFirstId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(firstId) {
        val previous = previousFirstId
        previousFirstId = firstId
        if (previous == null || firstId == null || previous == firstId) return@LaunchedEffect
        val insertedAbove = messages.indexOfFirst { it.id == previous }
        if (insertedAbove > 0 &&
            listState.firstVisibleItemIndex == insertedAbove &&
            listState.firstVisibleItemScrollOffset == 0
        ) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(messages, key = { it.id }, contentType = { "message" }) { message ->
            val selected = message.id in state.selection
            SwipeableMessageRow(
                message = message,
                startToEnd = if (selectionMode) SwipeAction.NONE else startToEnd,
                endToStart = if (selectionMode) SwipeAction.NONE else endToStart,
                permanentDelete = permanentDelete,
                onSwipe = actions.onSwipe,
                modifier = Modifier.animateItem(),
            ) {
                MessageListItem(
                    message = message,
                    selected = selected,
                    showRecipients = showRecipients,
                    showSnippet = state.settings.showSnippets,
                    ownAddress = ownAddress,
                    onClick = {
                        if (selectionMode) actions.onToggleSelection(message.id) else actions.onOpen(message)
                    },
                    onLongClick = { actions.onToggleSelection(message.id) },
                    onAvatarClick = { actions.onToggleSelection(message.id) },
                    onToggleStar = { actions.onToggleStar(message) },
                )
            }
        }
        item(key = "footer", contentType = "footer") {
            ListFooter(
                loading = state.paging.loadingOlder,
                failed = state.paging.failed,
                onRetry = actions.onRetryLoadMore,
            )
        }
    }
}

@Composable
private fun ListFooter(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    when {
        loading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.mailbox_loading_older),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        failed -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.mailbox_load_older_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }

        else -> Spacer(Modifier.height(8.dp))
    }
}

/**
 * Wraps a row with the configured swipe actions. Archive and delete leave the row dismissed
 * (the list drops it); read and star toggles snap back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMessageRow(
    message: MessageSummary,
    startToEnd: SwipeAction,
    endToStart: SwipeAction,
    permanentDelete: Boolean,
    onSwipe: (MessageSummary, SwipeAction) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The state below remembers its first confirmValueChange lambda: read everything through
    // updated state holders.
    val currentMessage by rememberUpdatedState(message)
    val currentStart by rememberUpdatedState(startToEnd)
    val currentEnd by rememberUpdatedState(endToStart)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val guard = remember { SwipeGuard() }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.Settled -> true
                SwipeToDismissBoxValue.StartToEnd -> guard.decide { currentOnSwipe(currentMessage, currentStart) }
                SwipeToDismissBoxValue.EndToStart -> guard.decide { currentOnSwipe(currentMessage, currentEnd) }
            }
        },
        positionalThreshold = { distance -> distance * SWIPE_THRESHOLD },
    )
    // A row still in the list a moment after being dismissed (the action failed, or the list has
    // not dropped it) slides back instead of staying blank.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            delay(DISMISSED_RESET_MILLIS)
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = startToEnd != SwipeAction.NONE,
        enableDismissFromEndToStart = endToStart != SwipeAction.NONE,
        gesturesEnabled = startToEnd != SwipeAction.NONE || endToStart != SwipeAction.NONE,
        backgroundContent = {
            SwipeBackground(dismissState, message, startToEnd, endToStart, permanentDelete)
        },
    ) {
        content()
    }
}

/** Ignores repeated confirmations of the same gesture so a toggle never runs twice. */
private class SwipeGuard {
    private var lastAt = 0L
    private var lastDecision = false

    fun decide(action: () -> Boolean): Boolean {
        val now = System.nanoTime()
        if (lastAt != 0L && now - lastAt < GUARD_NANOS) return lastDecision
        lastAt = now
        lastDecision = action()
        return lastDecision
    }
}

private const val SWIPE_THRESHOLD = 0.35f
private const val DISMISSED_RESET_MILLIS = 1_500L
private const val GUARD_NANOS = 600_000_000L

private class SwipeVisual(val color: Color, val icon: ImageVector, @param:StringRes val label: Int)

private fun swipeVisual(action: SwipeAction, message: MessageSummary, permanentDelete: Boolean): SwipeVisual? =
    when (action) {
        SwipeAction.NONE -> null
        SwipeAction.ARCHIVE -> SwipeVisual(Color(0xFF2E7D32), Icons.Outlined.Archive, R.string.action_archive)
        SwipeAction.DELETE -> if (permanentDelete) {
            SwipeVisual(Color(0xFFB3261E), Icons.Outlined.DeleteForever, R.string.action_delete_forever)
        } else {
            SwipeVisual(Color(0xFFC62828), Icons.Outlined.Delete, R.string.action_delete)
        }
        SwipeAction.TOGGLE_READ -> if (message.isRead) {
            SwipeVisual(Color(0xFF1565C0), Icons.Outlined.MarkEmailUnread, R.string.action_mark_unread)
        } else {
            SwipeVisual(Color(0xFF1565C0), Icons.Outlined.MarkEmailRead, R.string.action_mark_read)
        }
        SwipeAction.TOGGLE_STAR -> if (message.isStarred) {
            SwipeVisual(Color(0xFFB26A00), Icons.Outlined.StarOutline, R.string.action_unstar)
        } else {
            SwipeVisual(Color(0xFFB26A00), Icons.Filled.Star, R.string.action_star)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(
    state: SwipeToDismissBoxState,
    message: MessageSummary,
    startToEnd: SwipeAction,
    endToStart: SwipeAction,
    permanentDelete: Boolean,
) {
    val direction = state.dismissDirection
    val action = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> startToEnd
        SwipeToDismissBoxValue.EndToStart -> endToStart
        SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
    }
    val visual = swipeVisual(action, message, permanentDelete) ?: return
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    val iconScale by animateFloatAsState(if (armed) 1.2f else 0.9f, label = "swipeIcon")
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(visual.color)
            .padding(horizontal = 24.dp),
        horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(visual.label)
        if (!fromStart) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Spacer(Modifier.width(12.dp))
        }
        Icon(visual.icon, contentDescription = null, tint = Color.White, modifier = Modifier.scale(iconScale))
        if (fromStart) {
            Spacer(Modifier.width(12.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private class EmptyStateContent(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val body: Int)

private fun emptyStateFor(role: FolderRole?): EmptyStateContent = when (role) {
    FolderRole.INBOX -> EmptyStateContent(Icons.Outlined.Celebration, R.string.mailbox_empty_inbox_title, R.string.mailbox_empty_inbox_body)
    FolderRole.DRAFTS -> EmptyStateContent(Icons.Outlined.Drafts, R.string.mailbox_empty_drafts_title, R.string.mailbox_empty_drafts_body)
    FolderRole.SENT -> EmptyStateContent(Icons.AutoMirrored.Outlined.Send, R.string.mailbox_empty_sent_title, R.string.mailbox_empty_sent_body)
    FolderRole.ARCHIVE -> EmptyStateContent(Icons.Outlined.Inventory2, R.string.mailbox_empty_archive_title, R.string.mailbox_empty_archive_body)
    FolderRole.TRASH -> EmptyStateContent(Icons.Outlined.Delete, R.string.mailbox_empty_trash_title, R.string.mailbox_empty_trash_body)
    FolderRole.JUNK -> EmptyStateContent(Icons.Outlined.Report, R.string.mailbox_empty_junk_title, R.string.mailbox_empty_junk_body)
    FolderRole.OTHER, null -> EmptyStateContent(Icons.Outlined.FolderOpen, R.string.mailbox_empty_folder_title, R.string.mailbox_empty_folder_body)
}

/** An empty state inside a scrollable container, so pull to refresh still works. */
@Composable
internal fun ScrollableEmptyState(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "empty") {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** Centered illustration, title, explanation and an optional action. */
@Composable
fun MailboxEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Full-size progress with a caption. */
@Composable
internal fun MailboxLoadingState(caption: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
