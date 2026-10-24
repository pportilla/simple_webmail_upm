package io.github.pportilla.upmmail.ui.safety

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.safety.AgeBand
import io.github.pportilla.upmmail.data.safety.Audience
import io.github.pportilla.upmmail.ui.components.PrivacyPolicyButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The normal navigation graph is not composed for a child. A guardian session and navigation
 * state exist only in memory, and are revoked by MainActivity on background or process death.
 */
@Composable
fun SafetyRoot(
    container: AppContainer,
    onDiscardExternalRequest: () -> Unit,
    onProtectSurface: (Boolean) -> Unit,
    normalContent: @Composable () -> Unit,
) {
    val safety = container.safetyRepository
    // Policy changes must reach the root while backgrounded, before a resumed frame can draw.
    val state by safety.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    var dashboard by remember { mutableStateOf(false) }
    var accountManagement by remember { mutableStateOf(false) }
    var unlock by remember { mutableStateOf(false) }
    var changeAudience by remember { mutableStateOf(false) }
    var newBand by remember { mutableStateOf<AgeBand?>(null) }

    SideEffect {
        onProtectSurface(
            state.audience == Audience.UNCONFIGURED || !state.termsAccepted ||
                (state.audience == Audience.SUPERVISED && (!state.guardianConfigured || state.guardianUnlocked)) ||
                dashboard || accountManagement || changeAudience || unlock,
        )
    }

    LaunchedEffect(state.audience, state.guardianUnlocked) {
        if (state.audience != Audience.ADULT) onDiscardExternalRequest()
        if (!state.guardianUnlocked && state.audience == Audience.SUPERVISED) {
            dashboard = false
            accountManagement = false
        }
    }
    fun finishGuardian() {
        accountManagement = false
        dashboard = false
        safety.lockGuardian()
        onDiscardExternalRequest()
    }

    when {
        state.audience == Audience.UNCONFIGURED -> AgeGateScreen { band ->
            if (band == AgeBand.ADULT) safety.acceptTerms()
            safety.chooseAgeBand(band)
        }
        state.audience == Audience.SUPERVISED && !state.guardianConfigured -> GuardianSetupScreen(
            onBack = null,
            onConfigure = { pin, date ->
                safety.acceptTerms()
                safety.setupGuardian(pin, date)
                safety.lockGuardian()
            },
        )
        !state.termsAccepted -> TermsRenewalScreen(container)
        changeAudience -> {
            val selected = newBand
            if (selected == null) {
                AgeGateScreen(onBack = { changeAudience = false }, requireChild = true) { newBand = it }
            } else {
                GuardianSetupScreen(onBack = { newBand = null }) { pin, date ->
                    safety.acceptTerms()
                    safety.enableSupervised(selected, pin, date)
                    changeAudience = false
                    newBand = null
                    dashboard = false
                    safety.lockGuardian()
                }
            }
        }
        state.audience == Audience.ADULT && !dashboard -> Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { dashboard = true }, modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Text(stringResource(R.string.family_controls))
            }
            Box(Modifier.weight(1f)) { normalContent() }
        }
        accountManagement && state.guardianUnlocked -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { accountManagement = false }) { Text(stringResource(R.string.family_dashboard)) }
                TextButton(onClick = ::finishGuardian) { Text(stringResource(R.string.family_lock)) }
            }
            Text(stringResource(R.string.family_guardian_session_notice), modifier = Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f)) { normalContent() }
        }
        dashboard && (state.audience == Audience.ADULT || state.guardianUnlocked) -> GuardianDashboard(
            container = container,
            onDone = { if (state.audience == Audience.ADULT) dashboard = false else finishGuardian() },
            onManageAccounts = {
                onDiscardExternalRequest()
                if (state.audience == Audience.ADULT) dashboard = false else accountManagement = true
            },
            onSetUpChild = { newBand = null; changeAudience = true },
        )
        else -> ChildHome(container = container, onGuardian = { unlock = true })
    }
    if (unlock) {
        GuardianUnlockDialog(container, onDismiss = { unlock = false }) {
            unlock = false
            dashboard = true
        }
    }
    // Registered after the normal graph: Back ends guardian access, rather than restoring a
    // previously open unrestricted screen. It also clears pending requests before child use.
    BackHandler(enabled = state.audience == Audience.SUPERVISED && state.guardianUnlocked) { finishGuardian() }
    BackHandler(enabled = state.audience == Audience.ADULT && dashboard) { dashboard = false; changeAudience = false; newBand = null }
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
private fun AgeGateScreen(
    onBack: (() -> Unit)? = null,
    requireChild: Boolean = false,
    onSelected: (AgeBand) -> Unit,
) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var accepted by remember { mutableStateOf(false) }
    val band = date?.let { ageBandForBirthDate(it, LocalDate.now()) }
    SafetyPage(stringResource(R.string.family_age_title), onBack) {
        Text(stringResource(R.string.family_age_notice))
        BirthDateFields(stringResource(R.string.family_user_birth), onDate = { date = it })
        if (band == AgeBand.ADULT && !requireChild) TermsAcceptance(accepted) { accepted = it }
        if (requireChild && band == AgeBand.ADULT) Text(stringResource(R.string.family_child_date_required))
        SafetyButton(
            stringResource(R.string.family_continue),
            enabled = band != null && (!requireChild || band != AgeBand.ADULT) && (band != AgeBand.ADULT || accepted),
        ) { band?.let(onSelected) }
        PrivacyPolicyButton()
    }
}

@Composable
internal fun BirthDateFields(label: String, onDate: (LocalDate?) -> Unit) {
    var day by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    val currentOnDate by rememberUpdatedState(onDate)
    LaunchedEffect(day, month, year) {
        currentOnDate(birthDateFromFields(day, month, year, LocalDate.now()))
    }
    Text(label, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.family_day)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(month, { month = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.family_month)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.family_year)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.weight(1.4f))
    }
}

@Composable
internal fun TermsAcceptance(accepted: Boolean, onAccepted: (Boolean) -> Unit) {
    Text(stringResource(R.string.family_terms), style = MaterialTheme.typography.bodyLarge)
    SafetyCheck(stringResource(R.string.family_accept_terms), accepted, onAccepted)
}

@Composable
internal fun SafetyCheck(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
private fun GuardianSetupScreen(
    onBack: (() -> Unit)?,
    onConfigure: suspend (String, LocalDate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    var pin by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var providerAccepted by remember { mutableStateOf(false) }
    var termsAccepted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pin = ""; repeated = "" }
    val adult = birthDate?.let { ageBandForBirthDate(it, LocalDate.now()) } == AgeBand.ADULT
    SafetyPage(stringResource(R.string.family_guardian_setup), onBack) {
        Text(stringResource(R.string.family_guardian_setup_notice))
        BirthDateFields(stringResource(R.string.family_guardian_birth), onDate = { birthDate = it })
        PinField(pin, { pin = it }, stringResource(R.string.family_new_pin), enabled = !busy)
        PinField(repeated, { repeated = it }, stringResource(R.string.family_repeat_pin), enabled = !busy)
        Text(stringResource(R.string.family_pin_notice))
        SafetyCheck(stringResource(R.string.family_provider_ack), providerAccepted) { providerAccepted = it }
        TermsAcceptance(termsAccepted) { termsAccepted = it }
        if (failed) Text(stringResource(R.string.family_action_failed), color = MaterialTheme.colorScheme.error)
        SafetyButton(stringResource(R.string.family_create_controls), !busy && adult && pin.length >= 6 && pin == repeated && providerAccepted && termsAccepted) {
            val date = birthDate ?: return@SafetyButton
            busy = true
            failed = false
            scope.launch {
                try {
                    onConfigure(pin, date)
                    pin = ""; repeated = ""
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }
        PrivacyPolicyButton()
    }
}

@Composable
internal fun PinField(value: String, onValue: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = { onValue(it.filter { char -> char in '0'..'9' }.take(20)) },
        label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun GuardianUnlockDialog(container: AppContainer, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pin = "" }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.family_guardian_unlock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.family_guardian_unlock_notice))
                PinField(pin, { pin = it }, stringResource(R.string.family_pin), enabled = !busy)
                if (failed) Text(stringResource(R.string.family_pin_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pin.length >= 6, onClick = {
                busy = true
                scope.launch {
                    try {
                        val unlocked = container.safetyRepository.unlockGuardian(pin)
                        pin = ""
                        if (unlocked && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onUnlocked()
                        else { container.safetyRepository.lockGuardian(); failed = true }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.family_unlock)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Future rule-version updates stay blocked until an adult accepts the new rules. */
@Composable
private fun TermsRenewalScreen(container: AppContainer) {
    val safety = container.safetyRepository
    val state by safety.state.collectAsStateWithLifecycle()
    var accepted by remember { mutableStateOf(false) }
    var unlock by remember { mutableStateOf(false) }
    SafetyPage(stringResource(R.string.family_controls)) {
        TermsAcceptance(accepted) { accepted = it }
        SafetyButton(stringResource(R.string.family_continue), enabled = accepted) {
            if (state.audience == Audience.ADULT || state.guardianUnlocked) {
                safety.acceptTerms()
                safety.lockGuardian()
            } else unlock = true
        }
        PrivacyPolicyButton()
    }
    if (unlock) GuardianUnlockDialog(container, onDismiss = { unlock = false }) {
        safety.acceptTerms()
        safety.lockGuardian()
        unlock = false
    }
}
