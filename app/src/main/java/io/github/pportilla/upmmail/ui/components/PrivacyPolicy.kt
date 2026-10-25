package io.github.pportilla.upmmail.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.safety.hasAdultExternalAccess

private const val PRIVACY_CONTACT_URL = "mailto:p.portilla89@gmail.com?subject=UPM%20Mail%20privacy"

/** Available offline and before sign-in; opening the policy never sends credentials anywhere. */
@Composable
fun PrivacyPolicyButton(onOpenUrl: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
    var show by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = modifier) {
        Text(stringResource(R.string.privacy_title))
    }
    if (show) PrivacyPolicyDialog(onDismiss = { show = false }, onOpenUrl = onOpenUrl)
}

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit, onOpenUrl: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val policyText: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.privacy_updated))
                        Text(stringResource(R.string.privacy_intro))
                        PrivacySection(R.string.privacy_families_title, R.string.privacy_families_body)
                        PrivacySection(R.string.privacy_play_age_title, R.string.privacy_play_age_body)
                        PrivacySection(R.string.privacy_demo_title, R.string.privacy_demo_body)
                        PrivacySection(R.string.privacy_servers_title, R.string.privacy_servers_body)
                        PrivacySection(R.string.privacy_storage_title, R.string.privacy_storage_body)
                        PrivacySection(R.string.privacy_contacts_title, R.string.privacy_contacts_body)
                        PrivacySection(R.string.privacy_remote_title, R.string.privacy_remote_body)
                        PrivacySection(R.string.privacy_sharing_title, R.string.privacy_sharing_body)
                        PrivacySection(R.string.privacy_background_title, R.string.privacy_background_body)
                        PrivacySection(R.string.privacy_deletion_title, R.string.privacy_deletion_body)
                        PrivacySection(R.string.privacy_contact_title, R.string.privacy_contact_body)
                    }
                }
                if (onOpenUrl == null || !context.hasAdultExternalAccess()) {
                    // Selection can expose Android's external share/process-text actions.
                    policyText()
                } else {
                    SelectionContainer { policyText() }
                    TextButton(onClick = {
                        if (context.hasAdultExternalAccess()) onOpenUrl(PRIVACY_CONTACT_URL)
                    }) {
                        Text(stringResource(R.string.privacy_contact_action))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun PrivacySection(title: Int, body: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(body))
}
