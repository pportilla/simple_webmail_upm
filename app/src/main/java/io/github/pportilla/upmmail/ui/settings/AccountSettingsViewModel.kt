package io.github.pportilla.upmmail.ui.settings

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.ui.setup.ServerFormState
import io.github.pportilla.upmmail.ui.setup.ServerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DemoArrivalStatus { IDLE, SCHEDULING, QUEUED, FAILED }

data class AccountSettingsUiState(
    val loading: Boolean = true,
    /** The account as stored. */
    val account: Account? = null,
    /** The account does not exist (any more). */
    val missing: Boolean = false,
    val saving: Boolean = false,
    val removing: Boolean = false,
    /** Why the last save or removal failed. */
    val error: Throwable? = null,
    /** True when [error] comes from removing the account rather than saving it. */
    val errorFromRemoval: Boolean = false,
    /** The app-wide notification switch, which overrides the per-account one. */
    val notificationsEnabledGlobally: Boolean = true,
    val demoArrivalStatus: DemoArrivalStatus = DemoArrivalStatus.IDLE,
)

sealed interface AccountSettingsEvent {
    /** The changes were stored ([changed] is false when there was nothing to save). */
    data class Saved(val changed: Boolean) : AccountSettingsEvent

    /** The account was removed; [remainingAccounts] tells whether others are left. */
    data class Removed(val remainingAccounts: Int) : AccountSettingsEvent
}

/**
 * Edits one account. The form fields are Compose state held here (they survive configuration
 * changes; after process death they are reloaded from the database).
 */
class AccountSettingsViewModel(
    private val container: AppContainer,
    private val accountId: Long,
) : ViewModel() {

    private val repository = container.accountRepository

    private val _uiState = MutableStateFlow(AccountSettingsUiState())
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<AccountSettingsEvent>(Channel.BUFFERED)
    val events: Flow<AccountSettingsEvent> = _events.receiveAsFlow()

    var displayName by mutableStateOf("")
    var signature by mutableStateOf("")
    var notificationsEnabled by mutableStateOf(true)

    /** Empty keeps the stored password. Never saved to instance state. */
    var newPassword by mutableStateOf("")
    val imap = ServerFormState(ServerKind.IMAP)
    val smtp = ServerFormState(ServerKind.SMTP)
    var serversExpanded by mutableStateOf(false)

    /** Field errors are shown after the first save attempt. */
    var showErrors by mutableStateOf(false)
        private set

    private var original by mutableStateOf<Account?>(null)

    /** True when the form differs from the stored account. */
    val isDirty: Boolean by derivedStateOf {
        val stored = original ?: return@derivedStateOf false
        (!stored.isDemo && newPassword.isNotEmpty()) ||
            displayName.trim() != stored.displayName ||
            signature.trimEnd() != stored.signature.trimEnd() ||
            notificationsEnabled != stored.notificationsEnabled ||
            (!stored.isDemo && (serverChanged(imap, stored.imap) || serverChanged(smtp, stored.smtp)))
    }

    init {
        viewModelScope.launch {
            repository.accounts
                .map { accounts -> accounts.firstOrNull { it.id == accountId } }
                .catch { error -> _uiState.update { it.copy(loading = false, error = error) } }
                .collect(::onAccount)
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(notificationsEnabledGlobally = settings.notificationsEnabled) }
            }
        }
    }

    private fun onAccount(account: Account?) {
        if (account == null) {
            // Gone because we removed it: the Removed event takes care of navigation.
            if (!_uiState.value.removing) _uiState.update { it.copy(loading = false, missing = true, account = null) }
            return
        }
        if (original == null) loadForm(account)
        original = account
        _uiState.update { it.copy(loading = false, missing = false, account = account) }
    }

    private fun loadForm(account: Account) {
        displayName = account.displayName
        signature = account.signature
        notificationsEnabled = account.notificationsEnabled
        imap.load(account.imap)
        smtp.load(account.smtp)
    }

    /**
     * Saves the form. Server or password changes are verified with the servers first; nothing is
     * stored if the login fails.
     */
    fun save() {
        val stored = original ?: return
        val state = _uiState.value
        if (state.saving || state.removing) return
        showErrors = true
        val imapSettings = if (stored.isDemo) stored.imap else imap.toSettings()
        val smtpSettings = if (stored.isDemo) stored.smtp else smtp.toSettings()
        if (imapSettings == null || smtpSettings == null) {
            serversExpanded = true
            return
        }
        val updated = stored.copy(
            displayName = displayName.trim(),
            signature = signature.trimEnd(),
            notificationsEnabled = notificationsEnabled,
            imap = imapSettings,
            smtp = smtpSettings,
        )
        val password = newPassword.takeIf { !stored.isDemo && it.isNotEmpty() }
        if (updated == stored && password == null) {
            _events.trySend(AccountSettingsEvent.Saved(changed = false))
            return
        }
        val verify = password != null || imapSettings != stored.imap || smtpSettings != stored.smtp
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // Finish the write even if the user leaves meanwhile, so the account is never left
            // half-updated (new servers stored but not the new password).
            val result = withContext(NonCancellable) { repository.updateAccount(updated, password, verify) }
            result
                .onSuccess { saved ->
                    original = saved
                    newPassword = ""
                    _uiState.update { it.copy(saving = false, account = saved) }
                    _events.send(AccountSettingsEvent.Saved(changed = true))
                }
                .onFailure { error ->
                    _uiState.update { it.copy(saving = false, error = error, errorFromRemoval = false) }
                }
        }
    }

    /** Deletes the account with its cached mail and stored password. */
    fun remove() {
        val state = _uiState.value
        if (state.removing || state.saving) return
        _uiState.update { it.copy(removing = true, error = null) }
        viewModelScope.launch {
            val remaining = try {
                withContext(NonCancellable) {
                    repository.removeAccount(accountId)
                    repository.accounts.first().size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(removing = false, error = e, errorFromRemoval = true) }
                return@launch
            }
            _events.send(AccountSettingsEvent.Removed(remaining))
        }
    }

    fun dismissError() {
        if (_uiState.value.error != null) _uiState.update { it.copy(error = null) }
    }

    fun scheduleSampleArrival() {
        val state = _uiState.value
        if (state.account?.isDemo != true || state.saving || state.removing || state.demoArrivalStatus == DemoArrivalStatus.SCHEDULING) return
        _uiState.update { it.copy(demoArrivalStatus = DemoArrivalStatus.SCHEDULING) }
        viewModelScope.launch {
            val result = try {
                repository.scheduleDemoArrival(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _uiState.update { it.copy(demoArrivalStatus = if (result.isSuccess) DemoArrivalStatus.QUEUED else DemoArrivalStatus.FAILED) }
        }
    }

    private fun serverChanged(form: ServerFormState, stored: ServerSettings): Boolean =
        form.toSettings()?.let { it != stored } ?: true
}
