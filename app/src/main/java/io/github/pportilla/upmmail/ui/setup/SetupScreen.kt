package io.github.pportilla.upmmail.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.ui.components.AppSnackbarHost
import io.github.pportilla.upmmail.ui.components.LocalAppSnackbar
import io.github.pportilla.upmmail.ui.components.appContainer
import io.github.pportilla.upmmail.ui.navigation.AppNavigator
import io.github.pportilla.upmmail.ui.theme.UpmBlue

/** Widest the form gets on tablets and in landscape. */
private val MaxFormWidth = 560.dp

/**
 * Sign-in screen for UPM accounts: e-mail (with domain completion), password and name, optional
 * advanced server settings, and links to the UPM webmail.
 *
 * @param isAddingAccount true when another account is being added: shows a back button. As the
 *   first screen, back simply leaves the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(navigator: AppNavigator, isAddingAccount: Boolean) {
    val container = appContainer()
    val viewModel = viewModel { SetupViewModel(container) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = rememberSetupFormState()
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val currentNavigator by rememberUpdatedState(navigator)

    LaunchedEffect(state.signedInAccountId) {
        if (state.signedInAccountId != null) {
            snackbar.show(context.getString(R.string.setup_signed_in, form.email.trim()))
            currentNavigator.openMailbox(clearBackStack = true)
        }
    }

    val busy = state.signingIn || state.signedInAccountId != null
    val emailError = form.showErrors && !form.isEmailValid
    val passwordError = form.showErrors && viewModel.password.isEmpty()

    fun submit() {
        if (busy) return
        form.showErrors = true
        val imap = if (form.imapEdited) form.imap.toSettings() else null
        val smtp = if (form.smtpEdited) form.smtp.toSettings() else null
        val serversValid = (!form.imapEdited || imap != null) && (!form.smtpEdited || smtp != null)
        when {
            !form.isEmailValid -> emailFocus.requestFocus()
            viewModel.password.isEmpty() -> passwordFocus.requestFocus()
            !serversValid -> form.advancedExpanded = true
            else -> {
                keyboard?.hide()
                focusManager.clearFocus()
                viewModel.signIn(form.email, form.displayName, imap, smtp)
            }
        }
    }

    Scaffold(
        topBar = {
            if (isAddingAccount) {
                TopAppBar(
                    title = { Text(stringResource(R.string.setup_add_account_title)) },
                    navigationIcon = {
                        IconButton(onClick = navigator::back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            }
        },
        snackbarHost = { AppSnackbarHost() },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .widthIn(max = MaxFormWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                BrandHeader(isAddingAccount)
                Spacer(Modifier.size(4.dp))

                EmailField(
                    form = form,
                    enabled = !busy,
                    isError = emailError,
                    focusRequester = emailFocus,
                    onEdited = viewModel::dismissError,
                    onNext = { passwordFocus.requestFocus() },
                    onSuggestionPicked = { passwordFocus.requestFocus() },
                )
                PasswordTextField(
                    value = viewModel.password,
                    onValueChange = {
                        viewModel.password = it
                        viewModel.dismissError()
                    },
                    label = stringResource(R.string.setup_password_label),
                    enabled = !busy,
                    isError = passwordError,
                    supportingText = if (passwordError) stringResource(R.string.setup_password_error) else null,
                    imeAction = ImeAction.Done,
                    onImeAction = ::submit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocus),
                )
                OutlinedTextField(
                    value = form.displayName,
                    onValueChange = form::onDisplayNameChange,
                    label = { Text(stringResource(R.string.setup_name_label)) },
                    supportingText = { Text(stringResource(R.string.setup_name_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.PersonFullName },
                )

                if (hasDomain(form.email) && !UpmPresets.isUpmAddress(form.email)) {
                    NoticeRow(
                        icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                        text = stringResource(R.string.setup_not_upm_notice),
                    )
                }

                AdvancedSection(form = form, enabled = !busy, onSubmit = ::submit)

                state.error?.let { error ->
                    when (error) {
                        SetupError.Duplicate -> NoticeRow(
                            icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            text = stringResource(R.string.setup_error_duplicate),
                            color = MaterialTheme.colorScheme.error,
                        )
                        is SetupError.Failed -> SignInErrorCard(
                            error = error.error,
                            title = stringResource(R.string.setup_error_title),
                        )
                    }
                }

                Button(
                    onClick = ::submit,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.setup_signing_in))
                    } else {
                        Text(stringResource(R.string.setup_sign_in))
                    }
                }
                AnimatedVisibility(visible = state.signingIn) {
                    Text(
                        text = stringResource(R.string.setup_signing_in_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Footer(
                    email = form.email,
                    onOpenWebmail = { url ->
                        if (!context.openWebPage(url)) snackbar.show(context.getString(R.string.setup_no_browser))
                    },
                )
            }
        }
    }
}

@Composable
private fun BrandHeader(isAddingAccount: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(UpmBlue),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
            )
        }
        Text(
            text = stringResource(if (isAddingAccount) R.string.setup_add_account_heading else R.string.setup_welcome),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmailField(
    form: SetupFormState,
    enabled: Boolean,
    isError: Boolean,
    focusRequester: FocusRequester,
    onEdited: () -> Unit,
    onNext: () -> Unit,
    onSuggestionPicked: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = form.email,
            onValueChange = {
                form.onEmailChange(it)
                onEdited()
            },
            label = { Text(stringResource(R.string.setup_email_label)) },
            placeholder = { Text(stringResource(R.string.setup_email_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null) },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            supportingText = if (isError) {
                { Text(stringResource(R.string.setup_email_error)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .semantics { contentType = ContentType.EmailAddress },
        )

        val suggestions = domainSuggestions(form.email)
        // Keep the last chips while they animate out.
        var shown by remember { mutableStateOf(suggestions) }
        if (suggestions.isNotEmpty()) shown = suggestions
        AnimatedVisibility(
            visible = enabled && suggestions.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { domain ->
                    val description = stringResource(R.string.setup_complete_domain, domain)
                    SuggestionChip(
                        onClick = {
                            form.onEmailChange(completeEmail(form.email, domain))
                            onEdited()
                            onSuggestionPicked()
                        },
                        label = { Text("@$domain") },
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(form: SetupFormState, enabled: Boolean, onSubmit: () -> Unit) {
    val rotation by animateFloatAsState(if (form.advancedExpanded) 180f else 0f, label = "expand")
    val stateText = stringResource(if (form.advancedExpanded) R.string.setup_expanded else R.string.setup_collapsed)
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.setup_advanced_title)) },
            supportingContent = { Text(stringResource(R.string.setup_advanced_summary)) },
            leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
            trailingContent = {
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { form.advancedExpanded = !form.advancedExpanded }
                .semantics { stateDescription = stateText },
        )
        AnimatedVisibility(
            visible = form.advancedExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.setup_advanced_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ServerSettingsEditor(
                    title = stringResource(R.string.setup_incoming_title),
                    state = form.imap,
                    onEdited = form::onImapEdited,
                    enabled = enabled,
                    showErrors = form.showErrors && form.imapEdited,
                )
                ServerSettingsEditor(
                    title = stringResource(R.string.setup_outgoing_title),
                    state = form.smtp,
                    onEdited = form::onSmtpEdited,
                    enabled = enabled,
                    showErrors = form.showErrors && form.smtpEdited,
                    imeAction = ImeAction.Done,
                    onImeAction = onSubmit,
                )
                if (form.serversEdited) {
                    TextButton(onClick = form::resetServers, enabled = enabled) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.setup_advanced_reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(email: String, onOpenWebmail: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { onOpenWebmail(webmailUrlFor(email)) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_open_webmail))
        }
        NoticeRow(
            icon = { Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            text = stringResource(R.string.setup_privacy_note),
        )
        Text(
            text = stringResource(R.string.setup_unofficial_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NoticeRow(
    icon: @Composable () -> Unit,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}
