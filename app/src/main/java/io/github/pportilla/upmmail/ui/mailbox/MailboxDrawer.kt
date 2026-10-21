package io.github.pportilla.upmmail.ui.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.ui.components.avatarColorFor
import io.github.pportilla.upmmail.ui.components.avatarInitial
import io.github.pportilla.upmmail.ui.components.folderDisplayName
import io.github.pportilla.upmmail.ui.components.folderIcon

/** Callbacks of the navigation drawer and the account switcher. */
internal class DrawerActions(
    val onSelectFolder: (Folder) -> Unit,
    val onSelectOutbox: () -> Unit,
    val onSelectAccount: (Account) -> Unit,
    val onAddAccount: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenWebmail: (String) -> Unit,
)

/** Drawer content: current account with switcher, folders, outbox, settings and webmail. */
@Composable
internal fun MailboxDrawerContent(
    state: MailboxUiState,
    drawerState: DrawerState,
    actions: DrawerActions,
) {
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }
    val arranged = remember(state.folders) { arrangeDrawerFolders(state.folders) }
    val email = state.account?.email
    val webmailUrl = remember(email) { email?.let { UpmPresets.defaultsFor(it).webmailUrl } }
    val currentFolderId = state.folder?.id
    val itemPadding = NavigationDrawerItemDefaults.ItemPadding

    ModalDrawerSheet(drawerState = drawerState) {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 16.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item(key = "account") {
                AccountHeader(
                    account = state.account,
                    expanded = accountsExpanded,
                    onToggle = { accountsExpanded = !accountsExpanded },
                )
            }
            if (accountsExpanded) {
                items(state.accounts, key = { "account-${it.id}" }) { account ->
                    NavigationDrawerItem(
                        icon = { AccountAvatar(account, size = 32.dp) },
                        label = { AccountLabel(account) },
                        selected = false,
                        badge = {
                            if (account.id == state.account?.id) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.mailbox_current_account))
                            }
                        },
                        onClick = {
                            accountsExpanded = false
                            actions.onSelectAccount(account)
                        },
                        modifier = Modifier.padding(itemPadding),
                    )
                }
                item(key = "add-account") {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                        label = { Text(stringResource(R.string.mailbox_add_account)) },
                        selected = false,
                        onClick = actions.onAddAccount,
                        modifier = Modifier.padding(itemPadding),
                    )
                }
            }
            item(key = "divider-top") { DrawerDivider() }
            if (!state.foldersLoaded || state.folders.isEmpty()) {
                item(key = "folders-loading") {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.mailbox_loading_folders),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(arranged.roles, key = { "folder-${it.folder.id}" }) { entry ->
                FolderDrawerItem(entry, selected = entry.folder.id == currentFolderId, onClick = actions.onSelectFolder)
            }
            if (state.outbox.isNotEmpty()) {
                item(key = "outbox") {
                    val failed = state.outbox.any { it.state == OutboxState.FAILED }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Outbox, contentDescription = null) },
                        label = { Text(stringResource(R.string.folder_outbox)) },
                        selected = state.isOutbox,
                        badge = {
                            Text(
                                text = badgeText(state.outbox.size),
                                color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                                fontWeight = if (failed) FontWeight.Bold else null,
                            )
                        },
                        onClick = actions.onSelectOutbox,
                        modifier = Modifier.padding(itemPadding),
                    )
                }
            }
            if (arranged.others.isNotEmpty()) {
                item(key = "others-header") {
                    Text(
                        text = stringResource(R.string.mailbox_drawer_folders),
                        modifier = Modifier
                            .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(arranged.others, key = { "folder-${it.folder.id}" }) { entry ->
                    FolderDrawerItem(entry, selected = entry.folder.id == currentFolderId, onClick = actions.onSelectFolder)
                }
            }
            item(key = "divider-bottom") { DrawerDivider() }
            item(key = "settings") {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.action_settings)) },
                    selected = false,
                    onClick = actions.onOpenSettings,
                    modifier = Modifier.padding(itemPadding),
                )
            }
            if (webmailUrl != null) {
                item(key = "webmail") {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        label = { Text(stringResource(R.string.action_open_webmail)) },
                        selected = false,
                        onClick = { actions.onOpenWebmail(webmailUrl) },
                        modifier = Modifier.padding(itemPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
}

@Composable
private fun FolderDrawerItem(entry: DrawerFolderEntry, selected: Boolean, onClick: (Folder) -> Unit) {
    val folder = entry.folder
    val name = entry.pathLabel ?: folderDisplayName(folder)
    val indent = Modifier.padding(start = (entry.indent * INDENT_DP).dp)
    if (!folder.selectable) {
        // A folder that only groups sub-folders (\Noselect): shown for structure, not clickable.
        Row(
            modifier = Modifier
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .then(indent)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(folderIcon(folder.role), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val count = drawerBadgeCount(folder)
    NavigationDrawerItem(
        icon = { Icon(folderIcon(folder.role), contentDescription = null) },
        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        badge = if (count > 0) {
            { Text(badgeText(count), fontWeight = if (folder.unreadCount > 0) FontWeight.SemiBold else null) }
        } else {
            null
        },
        onClick = { onClick(folder) },
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .then(indent),
    )
}

private const val INDENT_DP = 16

/** Alpha bits forced onto stored account colours, so a colour saved without alpha is still visible. */
private const val OPAQUE = 0xFF000000.toInt()

private fun badgeText(count: Int): String = if (count > 999) "999+" else count.toString()

@Composable
private fun AccountHeader(account: Account?, expanded: Boolean, onToggle: () -> Unit) {
    val toggleLabel = stringResource(if (expanded) R.string.mailbox_hide_accounts else R.string.mailbox_show_accounts)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClickLabel = toggleLabel, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(account, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (account != null) {
                Text(
                    text = accountName(account),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountLabel(account: Account) {
    Column {
        Text(accountName(account), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = account.email,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The name to show for an account: its display name, else the local part of the address. */
internal fun accountName(account: Account): String =
    account.displayName.trim().ifEmpty { account.email.substringBefore('@') }

/** Round avatar in the account's own colour. */
@Composable
internal fun AccountAvatar(account: Account?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val background = account?.color?.takeIf { it != 0 }?.let { Color(it or OPAQUE) } ?: avatarColorFor(account?.email.orEmpty())
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(account?.displayName, account?.email),
            // Same contrast rule as the shared SenderAvatar: white initial on a saturated colour.
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Bottom sheet opened from the avatar in the search bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSwitcherSheet(
    accounts: List<Account>,
    currentAccountId: Long?,
    onDismiss: () -> Unit,
    onSelectAccount: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.mailbox_accounts_title),
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            accounts.forEach { account ->
                val current = account.id == currentAccountId
                ListItem(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSelectAccount(account) },
                    colors = itemColors,
                    leadingContent = { AccountAvatar(account) },
                    headlineContent = { Text(accountName(account), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(account.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = if (current) {
                        { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.mailbox_current_account)) }
                    } else {
                        null
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ListItem(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onAddAccount),
                colors = itemColors,
                leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.mailbox_add_account)) },
            )
            ListItem(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onManageAccounts),
                colors = itemColors,
                leadingContent = { Icon(Icons.Outlined.ManageAccounts, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.mailbox_manage_accounts)) },
            )
        }
    }
}
