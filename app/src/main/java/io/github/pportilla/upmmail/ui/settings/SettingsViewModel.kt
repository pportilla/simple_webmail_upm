package io.github.pportilla.upmmail.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.SwipeAction
import io.github.pportilla.upmmail.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val settings: AppSettings,
    val accounts: List<Account> = emptyList(),
    /** False until the account list has been read once. */
    val accountsLoaded: Boolean = false,
)

/** Choices offered by the settings screen. */
object SettingsOptions {
    val themes: List<ThemeMode> = ThemeMode.entries

    /** Seconds; 0 turns undo-send off. */
    val undoSendSeconds: List<Int> = listOf(0, 5, 10, 20, 30)

    /** Instant push first, then the background check intervals (0 = only when the app is opened). */
    val mailChecks: List<MailCheck> = listOf(
        MailCheck.Push,
        MailCheck.Every(15),
        MailCheck.Every(30),
        MailCheck.Every(60),
        MailCheck.Every(0),
    )

    val swipeActions: List<SwipeAction> = listOf(
        SwipeAction.ARCHIVE,
        SwipeAction.DELETE,
        SwipeAction.TOGGLE_READ,
        SwipeAction.TOGGLE_STAR,
        SwipeAction.NONE,
    )

    /** [options] plus [current] if it is not one of them (e.g. set by an older version). */
    fun <T> withCurrent(options: List<T>, current: T): List<T> =
        if (current in options) options else options + current
}

/** How new mail is looked for: one choice of the "Check for new mail" setting. */
sealed interface MailCheck {
    /** Instant push over IMAP IDLE, with the periodic check kept as a safety net. */
    data object Push : MailCheck

    /** A background check every [minutes] (0: only when the app is opened). */
    data class Every(val minutes: Int) : MailCheck

    companion object {
        /** The periodic check that stays on behind push. */
        const val PUSH_SAFETY_NET_MINUTES = 15

        fun of(settings: AppSettings): MailCheck = if (settings.pushEnabled) Push else Every(settings.syncIntervalMinutes)
    }
}

/** The app settings and the account list; every change is saved immediately. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsRepository = container.settingsRepository

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        container.accountRepository.accounts
            .map<List<Account>, List<Account>?> { it }
            .onStart { emit(null) }
            .catch { emit(emptyList()) },
    ) { settings, accounts ->
        SettingsUiState(settings = settings, accounts = accounts.orEmpty(), accountsLoaded = accounts != null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(settingsRepository.settings.value),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        settingsRepository.update(transform)
    }

    fun setMailCheck(check: MailCheck) {
        settingsRepository.update {
            when (check) {
                MailCheck.Push -> it.copy(pushEnabled = true, syncIntervalMinutes = MailCheck.PUSH_SAFETY_NET_MINUTES)
                is MailCheck.Every -> it.copy(pushEnabled = false, syncIntervalMinutes = check.minutes)
            }
        }
        container.syncController.schedulePeriodicSync()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        settingsRepository.update { it.copy(notificationsEnabled = enabled) }
        container.syncController.schedulePeriodicSync()
    }

    fun forgetTrustedImageSenders() {
        settingsRepository.update { it.copy(trustedImageSenders = emptySet()) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
