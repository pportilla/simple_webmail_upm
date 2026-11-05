package io.github.pportilla.upmmail.ui.settings

import io.github.pportilla.upmmail.ui.components.SampleMailNotice
import io.github.pportilla.upmmail.ui.components.accountAddressLabel
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.navigation.AppNavigator
import io.github.pportilla.upmmail.ui.setup.PasswordTextField
import io.github.pportilla.upmmail.ui.setup.ServerSettingsEditor
import io.github.pportilla.upmmail.ui.setup.SignInErrorCard

private val MaxFormWidth = 640.dp

/**
 * Settings of one account: name, signature, notifications, password, server settings, and
 * removal. If the last account is removed, the navigation host takes the user to the sign-in
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(accountId: Long, navigator: AppNavigator) {
    val container = appContainer()
    val viewModel = viewModel { AccountSettingsViewModel(container, accountId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val currentNavigator by rememberUpdatedState(navigator)
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AccountSettingsEvent.Saved -> {
                    if (event.changed) snackbar.show(context.getString(R.string.settings_account_saved))
                    currentNavigator.back()
                }
                is AccountSettingsEvent.Removed -> {
                    snackbar.show(context.getString(R.string.settings_account_removed))
                    // With no accounts left the navigation host shows the sign-in screen.
                    if (event.remainingAccounts > 0) currentNavigator.back()
                }
            }
        }
    }

    val busy = state.saving || state.removing
    val leave = {
        if (viewModel.isDirty && !busy) confirmDiscard = true else navigator.back()
    }
    BackHandler(enabled = viewModel.isDirty && !busy) { confirmDiscard = true }

    // Also the keyboard's Done action: without changes it only closes the keyboard.
    val save = {
        keyboard?.hide()
        focusManager.clearFocus()
        if (viewModel.isDirty) viewModel.save()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_account_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.account?.let { account ->
                            Text(
                                text = accountAddressLabel(account),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.saving) {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    } else if (state.account != null) {
                        TextButton(onClick = save, enabled = viewModel.isDirty && !busy) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
        val account = state.account
        when {
            state.loading -> Box(bodyModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.missing || account == null -> MissingAccount(onBack = navigator::back, modifier = bodyModifier)
            else -> AccountForm(
                account = account,
                state = state,
                viewModel = viewModel,
                onSave = save,
                onRemove = { confirmRemove = true },
                modifier = bodyModifier,
            )
        }
    }

    if (confirmRemove) {
        RemoveAccountDialog(
            email = state.account?.email.orEmpty(),
            onConfirm = {
                confirmRemove = false
                viewModel.remove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.settings_discard_title)) },
            text = { Text(stringResource(R.string.settings_discard_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        navigator.back()
                    },
                ) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.settings_keep_editing)) }
            },
        )
    }
}

@Composable
private fun AccountForm(
    account: Account,
    state: AccountSettingsUiState,
    viewModel: AccountSettingsViewModel,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.saving || state.removing
    val fieldModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxFormWidth)
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            ListItem(
                headlineContent = { Text(account.displayName.ifBlank { account.email }) },
                supportingContent = { Text(accountAddressLabel(account)) },
                leadingContent = { AccountAvatar(account, size = 48.dp) },
            )

            SampleMailNotice(account)
            PreferenceSectionHeader(stringResource(R.string.settings_account_identity))
            OutlinedTextField(
                value = viewModel.displayName,
                onValueChange = { viewModel.displayName = it },
                label = { Text(stringResource(R.string.settings_account_name)) },
                supportingText = { Text(stringResource(R.string.settings_account_name_hint)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = fieldModifier,
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = viewModel.signature,
                onValueChange = { viewModel.signature = it },
                label = { Text(stringResource(R.string.settings_account_signature)) },
                supportingText = { Text(stringResource(R.string.settings_account_signature_hint)) },
                minLines = 3,
                maxLines = 10,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = fieldModifier,
            )

            PreferenceSectionHeader(stringResource(R.string.settings_section_notifications))
            SwitchPreference(
                title = stringResource(R.string.settings_account_notifications),
                summary = stringResource(
                    if (state.notificationsEnabledGlobally) {
                        R.string.settings_account_notifications_summary
                    } else {
                        R.string.settings_account_notifications_global_off
                    },
                ),
                icon = Icons.Outlined.NotificationsActive,
                checked = viewModel.notificationsEnabled,
                enabled = !busy,
                onCheckedChange = { viewModel.notificationsEnabled = it },
            )

            if (account.isDemo) {
                Text(stringResource(R.string.demo_push_notice), modifier = fieldModifier)
                OutlinedButton(
                    onClick = viewModel::scheduleSampleArrival,
                    enabled = !busy && state.demoArrivalStatus != DemoArrivalStatus.SCHEDULING,
                    modifier = fieldModifier.padding(vertical = 8.dp),
                ) { Text(stringResource(R.string.demo_receive)) }
                when (state.demoArrivalStatus) {
                    DemoArrivalStatus.QUEUED -> Text(stringResource(R.string.demo_receive_queued), modifier = fieldModifier)
                    DemoArrivalStatus.FAILED -> Text(stringResource(R.string.demo_receive_error), color = MaterialTheme.colorScheme.error, modifier = fieldModifier)
                    else -> Unit
                }
                PreferenceSectionHeader(stringResource(R.string.settings_account_servers))
                Text(stringResource(R.string.demo_no_connection), modifier = fieldModifier)
            } else {
            PreferenceSectionHeader(stringResource(R.string.settings_account_password))
            PasswordTextField(
                value = viewModel.newPassword,
                onValueChange = {
                    viewModel.newPassword = it
                    viewModel.dismissError()
                },
                label = stringResource(R.string.settings_account_new_password),
                supportingText = stringResource(R.string.settings_account_new_password_hint),
                enabled = !busy,
                newPassword = false,
                imeAction = ImeAction.Done,
                onImeAction = onSave,
                modifier = fieldModifier,
            )

            PreferenceSectionHeader(stringResource(R.string.settings_account_servers))
            ServersSection(viewModel = viewModel, enabled = !busy, onSave = onSave)

            }

            state.error?.let { error ->
                SignInErrorCard(
                    error = error,
                    title = stringResource(
                        if (state.errorFromRemoval) R.string.settings_account_remove_failed else R.string.settings_account_save_failed,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            PreferenceSectionHeader(stringResource(R.string.settings_account_remove_section))
            Text(
                text = stringResource(if (account.isDemo) R.string.demo_remove_notice else R.string.settings_account_remove_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = fieldModifier,
            )
            Spacer(Modifier.size(12.dp))
            OutlinedButton(
                onClick = onRemove,
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                if (state.removing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_account_removing))
                } else {
                    Icon(Icons.Outlined.PersonRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_account_remove))
                }
            }
        }
    }
}

@Composable
private fun ServersSection(viewModel: AccountSettingsViewModel, enabled: Boolean, onSave: () -> Unit) {
    val expanded = viewModel.serversExpanded
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    val stateText = stringResource(if (expanded) R.string.setup_expanded else R.string.setup_collapsed)
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_account_servers_edit)) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.settings_account_servers_summary,
                    viewModel.imap.host.ifBlank { "?" },
                    viewModel.smtp.host.ifBlank { "?" },
                ),
            )
        },
        leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
        trailingContent = { Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) },
        modifier = Modifier
            .clickable(role = Role.Button) { viewModel.serversExpanded = !expanded }
            .semantics { stateDescription = stateText },
    )
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_account_servers_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ServerSettingsEditor(
                title = stringResource(R.string.setup_incoming_title),
                state = viewModel.imap,
                onEdited = viewModel::dismissError,
                enabled = enabled,
                showErrors = viewModel.showErrors,
            )
            ServerSettingsEditor(
                title = stringResource(R.string.setup_outgoing_title),
                state = viewModel.smtp,
                onEdited = viewModel::dismissError,
                enabled = enabled,
                showErrors = viewModel.showErrors,
                imeAction = ImeAction.Done,
                onImeAction = onSave,
            )
        }
    }
}

@Composable
private fun MissingAccount(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = modifier.padding(24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PersonOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.settings_account_missing),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

@Composable
private fun RemoveAccountDialog(email: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_account_remove_title, email)) },
        text = { Text(stringResource(R.string.settings_account_remove_text)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_account_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
