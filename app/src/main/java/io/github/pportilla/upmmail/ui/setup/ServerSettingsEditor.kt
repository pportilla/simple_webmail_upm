package io.github.pportilla.upmmail.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.SecurityMode

/** Above this font scale the security and port fields are stacked instead of side by side. */
private const val STACK_FONT_SCALE = 1.3f

private const val MAX_PORT_DIGITS = 5

/**
 * Editor for one server (host, security, port, login name), used by the sign-in screen's
 * advanced section and by the account settings.
 *
 * @param onEdited called after every change the user makes.
 * @param showErrors highlight invalid fields.
 * @param imeAction keyboard action of the last field; [onImeAction] runs for Done / Go.
 */
@Composable
fun ServerSettingsEditor(
    title: String,
    state: ServerFormState,
    onEdited: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showErrors: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    val errors = if (showErrors) state.errors() else ServerFieldErrors()
    val focusManager = LocalFocusManager.current
    val stacked = LocalDensity.current.fontScale > STACK_FONT_SCALE
    val nextField = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { heading() },
        )
        OutlinedTextField(
            value = state.host,
            onValueChange = { value ->
                state.host = value.filterNot { it.isWhitespace() }
                onEdited()
            },
            label = { Text(stringResource(R.string.setup_server_host)) },
            singleLine = true,
            enabled = enabled,
            isError = errors.host,
            supportingText = if (errors.host) {
                { Text(stringResource(R.string.setup_server_host_error)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = nextField,
            modifier = Modifier.fillMaxWidth(),
        )

        val security: @Composable (Modifier) -> Unit = { fieldModifier ->
            SecurityField(
                value = state.security,
                enabled = enabled,
                onChange = { mode ->
                    state.changeSecurity(mode)
                    onEdited()
                },
                modifier = fieldModifier,
            )
        }
        val port: @Composable (Modifier) -> Unit = { fieldModifier ->
            OutlinedTextField(
                value = state.port,
                onValueChange = { value ->
                    state.port = value.filter { it.isDigit() }.take(MAX_PORT_DIGITS)
                    onEdited()
                },
                label = { Text(stringResource(R.string.setup_server_port)) },
                singleLine = true,
                enabled = enabled,
                isError = errors.port,
                supportingText = if (errors.port) {
                    { Text(stringResource(R.string.setup_server_port_error)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = nextField,
                modifier = fieldModifier,
            )
        }
        if (stacked) {
            security(Modifier.fillMaxWidth())
            port(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                security(Modifier.weight(1.4f))
                port(Modifier.weight(1f))
            }
        }
        if (state.security == SecurityMode.NONE) {
            InsecureWarning()
        }

        OutlinedTextField(
            value = state.username,
            onValueChange = { value ->
                state.username = value.trim()
                onEdited()
            },
            label = { Text(stringResource(R.string.setup_server_username)) },
            singleLine = true,
            enabled = enabled,
            isError = errors.username,
            supportingText = {
                Text(
                    stringResource(
                        when {
                            errors.username -> R.string.setup_server_username_error
                            state.kind == ServerKind.IMAP -> R.string.setup_server_username_hint_imap
                            else -> R.string.setup_server_username_hint_smtp
                        },
                    ),
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { onImeAction() },
                onGo = { onImeAction() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityField(
    value: SecurityMode,
    enabled: Boolean,
    onChange: (SecurityMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = securityLabel(value),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.setup_server_security)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            SecurityMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(securityLabel(mode)) },
                    onClick = {
                        expanded = false
                        onChange(mode)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun securityLabel(mode: SecurityMode): String = stringResource(
    when (mode) {
        SecurityMode.SSL_TLS -> R.string.setup_security_ssl
        SecurityMode.STARTTLS -> R.string.setup_security_starttls
        SecurityMode.NONE -> R.string.setup_security_none
    },
)

@Composable
private fun InsecureWarning() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Text(
            text = stringResource(R.string.setup_security_none_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
