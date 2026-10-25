package io.github.pportilla.upmmail.ui.safety

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.BuildConfig
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.safety.AgeBand
import io.github.pportilla.upmmail.data.safety.PlatformRestriction
import io.github.pportilla.upmmail.ui.components.PrivacyPolicyButton

/** One local acknowledgement, then the ordinary mailbox for every eligible user aged 13+. */
@Composable
fun SafetyRoot(
    container: AppContainer,
    onDiscardExternalRequest: () -> Unit,
    onProtectSurface: (Boolean) -> Unit,
    onRetryPlatformCheck: () -> Unit,
    normalContent: @Composable () -> Unit,
) {
    val safety = container.safetyRepository
    val state by safety.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready = state.mailboxAccessAllowed
    SideEffect { onProtectSurface(!ready) }
    LaunchedEffect(ready) { if (!ready) onDiscardExternalRequest() }

    when {
        state.ageBand == AgeBand.UNDER_13 -> UnsupportedAgeScreen()
        state.migrationPending -> SafetyPage(stringResource(R.string.mail_setup_updating)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.mail_setup_updating_notice))
        }
        state.platformCheckPending && !ready -> SafetyPage(stringResource(R.string.mail_play_check_title)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.mail_play_check_notice))
            PrivacyPolicyButton()
        }
        state.platformRestriction != PlatformRestriction.NONE -> PlatformRestrictionScreen(
            restriction = state.platformRestriction,
            onRetry = onRetryPlatformCheck,
        )
        !ready -> GeneralMailWelcome(onContinue = safety::completeGeneralSetup)
        else -> normalContent()
    }
}

@Composable
internal fun SafetyPage(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable
internal fun SafetyButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun GeneralMailWelcome(onContinue: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SafetyPage(stringResource(R.string.mail_welcome_title)) {
        Text(stringResource(R.string.mail_welcome_notice))
        Text(stringResource(R.string.mail_acceptable_use))
        Row(
            Modifier.fillMaxWidth().toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = accepted, onCheckedChange = null)
            Text(stringResource(R.string.mail_accept_terms), Modifier.weight(1f).padding(top = 12.dp))
        }
        if (failed) Text(stringResource(R.string.mail_action_failed), color = MaterialTheme.colorScheme.error)
        SafetyButton(stringResource(R.string.mail_continue), enabled = accepted) {
            failed = runCatching(onContinue).isFailure
        }
        PrivacyPolicyButton()
    }
}

@Composable
private fun UnsupportedAgeScreen() {
    SafetyPage(stringResource(R.string.mail_unsupported_title)) {
        Text(stringResource(R.string.mail_unsupported_notice))
        Text(stringResource(R.string.mail_unsupported_help))
        PrivacyPolicyButton()
    }
}

/** Fixed Google Play destinations remain available while mailbox access is restricted. */
@Composable
private fun PlatformRestrictionScreen(restriction: PlatformRestriction, onRetry: () -> Unit) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    val notice = when (restriction) {
        PlatformRestriction.UNDER_13 -> R.string.mail_play_under13_notice
        PlatformRestriction.VERIFICATION_REQUIRED -> R.string.mail_play_verification_notice
        PlatformRestriction.APPROVAL_PENDING -> R.string.mail_play_pending_notice
        PlatformRestriction.APPROVAL_DECLINED -> R.string.mail_play_declined_notice
        PlatformRestriction.NONE -> return
    }
    SafetyPage(stringResource(if (restriction == PlatformRestriction.UNDER_13) R.string.mail_unsupported_title else R.string.mail_play_requirement_title)) {
        Text(stringResource(notice))
        SafetyButton(stringResource(R.string.mail_open_google_play)) {
            openFailed = true
            val destinations = listOf(
                "market://details?id=${BuildConfig.APPLICATION_ID}",
                "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}",
            )
            for (destination in destinations) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    openFailed = false
                    break
                } catch (_: ActivityNotFoundException) {
                    // A device without the Play app can use the official listing in its browser.
                } catch (_: SecurityException) {
                    // Device policy can also prevent a particular handler from opening.
                }
            }
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mail_retry_play_check))
        }
        if (openFailed) Text(stringResource(R.string.mail_play_open_failed), color = MaterialTheme.colorScheme.error)
        PrivacyPolicyButton()
    }
}
