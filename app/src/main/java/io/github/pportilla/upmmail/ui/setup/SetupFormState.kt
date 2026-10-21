package io.github.pportilla.upmmail.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.pportilla.upmmail.mail.AddressParser
import io.github.pportilla.upmmail.mail.UpmPresets

/**
 * Field values of the sign-in form. Saved across process death with [Saver], except the
 * password, which deliberately lives only in memory (in SetupViewModel).
 *
 * The server sections follow UpmPresets for the typed address until the user edits one of them;
 * an untouched section is sent as null so the data layer auto-detects the login name.
 */
@Stable
class SetupFormState internal constructor(
    email: String = "",
    displayName: String = "",
    displayNameEdited: Boolean = false,
    advancedExpanded: Boolean = false,
    imapEdited: Boolean = false,
    smtpEdited: Boolean = false,
    showErrors: Boolean = false,
    val imap: ServerFormState = ServerFormState(ServerKind.IMAP),
    val smtp: ServerFormState = ServerFormState(ServerKind.SMTP),
) {
    var email by mutableStateOf(email)
        private set

    private var displayNameText by mutableStateOf(displayName)

    /** True once the user typed a name; until then it follows the e-mail address. */
    var displayNameEdited by mutableStateOf(displayNameEdited)
        private set

    var advancedExpanded by mutableStateOf(advancedExpanded)

    var imapEdited by mutableStateOf(imapEdited)
        private set

    var smtpEdited by mutableStateOf(smtpEdited)
        private set

    /** Field errors are shown after the first sign-in attempt, then updated live. */
    var showErrors by mutableStateOf(showErrors)

    /** The name shown in the field: the user's own, or the one derived from the address. */
    val displayName: String
        get() = if (displayNameEdited) displayNameText else displayNameFromEmail(email)

    val serversEdited: Boolean get() = imapEdited || smtpEdited

    val isEmailValid: Boolean get() = AddressParser.isValidAddress(email.trim())

    fun onEmailChange(value: String) {
        // Addresses never contain whitespace; pasted text often ends with a space or newline.
        email = value.filterNot { it.isWhitespace() }
        applyPresets()
    }

    fun onDisplayNameChange(value: String) {
        displayNameText = value
        displayNameEdited = true
    }

    fun onImapEdited() {
        imapEdited = true
    }

    fun onSmtpEdited() {
        smtpEdited = true
    }

    /** Forgets the user's server changes and goes back to the presets for the address. */
    fun resetServers() {
        imapEdited = false
        smtpEdited = false
        applyPresets()
    }

    private fun applyPresets() {
        if (!hasDomain(email)) return
        val defaults = UpmPresets.defaultsFor(email)
        if (!imapEdited) imap.load(defaults.imap)
        if (!smtpEdited) smtp.load(defaults.smtp)
    }

    companion object {
        private const val FLAGS = 7

        val Saver: Saver<SetupFormState, Any> = Saver(
            save = { state ->
                arrayListOf<Any>(
                    state.email,
                    state.displayNameText,
                    state.displayNameEdited,
                    state.advancedExpanded,
                    state.imapEdited,
                    state.smtpEdited,
                    state.showErrors,
                ).apply {
                    addAll(state.imap.save())
                    addAll(state.smtp.save())
                }
            },
            restore = { saved ->
                val values = saved as List<*>
                val imapStart = FLAGS
                val smtpStart = FLAGS + ServerFormState.SAVED_SIZE
                SetupFormState(
                    email = values[0] as String,
                    displayName = values[1] as String,
                    displayNameEdited = values[2] as Boolean,
                    advancedExpanded = values[3] as Boolean,
                    imapEdited = values[4] as Boolean,
                    smtpEdited = values[5] as Boolean,
                    showErrors = values[6] as Boolean,
                    imap = ServerFormState.restore(values.subList(imapStart, smtpStart).map { it as String }),
                    smtp = ServerFormState.restore(
                        values.subList(smtpStart, smtpStart + ServerFormState.SAVED_SIZE).map { it as String },
                    ),
                )
            },
        )
    }
}

@Composable
fun rememberSetupFormState(): SetupFormState =
    rememberSaveable(saver = SetupFormState.Saver) { SetupFormState() }
