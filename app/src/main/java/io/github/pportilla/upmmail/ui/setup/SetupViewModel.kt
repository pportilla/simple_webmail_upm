package io.github.pportilla.upmmail.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the last sign-in attempt failed. */
sealed interface SetupError {
    /** The address is already configured on this device. */
    data object Duplicate : SetupError

    /** The data layer refused the login ([error] is normally a MailException). */
    data class Failed(val error: Throwable) : SetupError
}

data class SetupUiState(
    val signingIn: Boolean = false,
    val error: SetupError? = null,
    /** Set once the account has been added; the screen then opens the mailbox. */
    val signedInAccountId: Long? = null,
)

/** Signs in (adds) an account; the form fields themselves live in [SetupFormState]. */
class SetupViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * Kept here rather than in the saved instance state, so the password survives configuration
     * changes but is never written to a Bundle.
     */
    var password by mutableStateOf("")

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /**
     * Verifies the login and stores the account. [imap] / [smtp] are null to use the UPM
     * presets with automatic login-name detection.
     */
    fun signIn(email: String, displayName: String, imap: ServerSettings?, smtp: ServerSettings?) {
        if (_uiState.value.signingIn || _uiState.value.signedInAccountId != null) return
        val address = email.trim()
        val secret = password
        _uiState.update { it.copy(signingIn = true, error = null) }
        viewModelScope.launch {
            if (isAlreadyAdded(address)) {
                _uiState.update { it.copy(signingIn = false, error = SetupError.Duplicate) }
                return@launch
            }
            container.accountRepository.addAccount(address, secret, displayName.trim(), imap, smtp)
                .onSuccess { account ->
                    // Show the new account (its INBOX) in the mailbox.
                    container.settingsRepository.update { it.copy(lastAccountId = account.id, lastFolderId = -1) }
                    password = ""
                    _uiState.update { it.copy(signingIn = false, signedInAccountId = account.id) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(signingIn = false, error = SetupError.Failed(error)) }
                }
        }
    }

    fun dismissError() {
        if (_uiState.value.error != null) _uiState.update { it.copy(error = null) }
    }

    private suspend fun isAlreadyAdded(address: String): Boolean =
        try {
            container.accountRepository.accounts.first().any { it.email.equals(address, ignoreCase = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // addAccount checks for duplicates again and reports its own error.
            false
        }
}
