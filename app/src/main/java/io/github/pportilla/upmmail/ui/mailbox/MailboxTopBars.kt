package io.github.pportilla.upmmail.ui.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.Account

/** Entries of the overflow menu next to the search bar; null hides an entry. */
internal class MailboxMenuActions(
    val onRefresh: (() -> Unit)?,
    val onMarkAllRead: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?,
    /** "Empty trash" / "Empty spam" with its label, or null outside those folders. */
    val emptyFolderLabel: String?,
    val onEmptyFolder: () -> Unit,
) {
    val isEmpty: Boolean get() = onRefresh == null && onMarkAllRead == null && onSelectAll == null && emptyFolderLabel == null
}

/**
 * The rounded "search pill" at the top of the mailbox: drawer button, search placeholder (opens
 * the search screen), overflow menu and the account avatar (opens the account switcher).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailboxSearchBar(
    placeholder: String,
    account: Account?,
    menu: MailboxMenuActions,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    onAccountClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            onClick = onSearch,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { role = Role.Button },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.mailbox_open_drawer))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = placeholder,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!menu.isEmpty) OverflowMenu(menu)
                val switchLabel = stringResource(R.string.mailbox_switch_account)
                IconButton(
                    onClick = onAccountClick,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                ) {
                    AccountAvatar(account, size = 32.dp)
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(menu: MailboxMenuActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu.onRefresh?.let { onRefresh ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_refresh)) },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRefresh()
                    },
                )
            }
            menu.onMarkAllRead?.let { onMarkAllRead ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mailbox_mark_all_read)) },
                    leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onMarkAllRead()
                    },
                )
            }
            menu.onSelectAll?.let { onSelectAll ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mailbox_select_all)) },
                    leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSelectAll()
                    },
                )
            }
            menu.emptyFolderLabel?.let { label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    onClick = {
                        expanded = false
                        menu.onEmptyFolder()
                    },
                )
            }
        }
    }
}

/** Callbacks of the contextual (selection mode) app bar. */
internal class SelectionBarActions(
    val onClose: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
    val onMarkRead: (read: Boolean) -> Unit,
    val onStar: (starred: Boolean) -> Unit,
    val onMove: () -> Unit,
    val onSelectAll: () -> Unit,
)

/** Contextual app bar shown while messages are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    available: SelectionActions,
    canArchive: Boolean,
    canMove: Boolean,
    permanentDelete: Boolean,
    allSelected: Boolean,
    actions: SelectionBarActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                text = pluralStringResource(R.plurals.mailbox_selected_count, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mailbox_exit_selection))
            }
        },
        actions = {
            if (canArchive) {
                IconButton(onClick = actions.onArchive) {
                    Icon(Icons.Outlined.Archive, contentDescription = stringResource(R.string.action_archive))
                }
            }
            IconButton(onClick = actions.onDelete) {
                Icon(
                    imageVector = if (permanentDelete) Icons.Outlined.DeleteForever else Icons.Outlined.Delete,
                    contentDescription = stringResource(if (permanentDelete) R.string.action_delete_forever else R.string.action_delete),
                )
            }
            IconButton(onClick = { actions.onMarkRead(available.markRead) }) {
                Icon(
                    imageVector = if (available.markRead) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread,
                    contentDescription = stringResource(if (available.markRead) R.string.action_mark_read else R.string.action_mark_unread),
                )
            }
            IconButton(onClick = { actions.onStar(available.star) }) {
                Icon(
                    imageVector = if (available.star) Icons.Outlined.StarOutline else Icons.Filled.Star,
                    contentDescription = stringResource(if (available.star) R.string.action_star else R.string.action_unstar),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (canMove) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_move)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                actions.onMove()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mailbox_select_all)) },
                        leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                        enabled = !allSelected,
                        onClick = {
                            menuExpanded = false
                            actions.onSelectAll()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}
