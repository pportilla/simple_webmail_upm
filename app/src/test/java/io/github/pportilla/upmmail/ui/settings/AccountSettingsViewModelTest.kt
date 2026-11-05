package io.github.pportilla.upmmail.ui.settings

import androidx.lifecycle.ViewModelStore
import io.github.pportilla.upmmail.data.repository.*
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsViewModelTest {
    private val server = ServerSettings("sample.invalid", 993, SecurityMode.SSL_TLS, "sample")
    private val sample = Account(8, "review@example.invalid", "Sample", server, server, "", 0, false, 0, isDemo = true)

    private class Accounts(account: Account) : AccountRepository {
        override val accounts = MutableStateFlow(listOf(account))
        var saved: Account? = null
        var savedPassword: String? = null
        var verified = false
        val arrivals = mutableListOf<Long>()
        var arrivalResult: suspend () -> Result<Unit> = { Result.success(Unit) }
        override suspend fun getAccount(id: Long) = accounts.value.firstOrNull { it.id == id }
        override suspend fun scheduleDemoArrival(accountId: Long): Result<Unit> { arrivals += accountId; return arrivalResult() }
        override suspend fun addAccount(email: String, password: String, displayName: String, imap: ServerSettings?, smtp: ServerSettings?): Result<Account> = error("unused")
        override suspend fun updateAccount(account: Account, newPassword: String?, verify: Boolean): Result<Account> {
            saved = account; savedPassword = newPassword; verified = verify
            accounts.value = listOf(account)
            return Result.success(account)
        }
        override suspend fun removeAccount(id: Long) = error("unused")
    }

    private class Container(override val accountRepository: AccountRepository, override val applicationScope: CoroutineScope) : AppContainer {
        override val safetyRepository = LocalSafetyRepository()
        override val settingsRepository = object : SettingsRepository {
            override val settings = MutableStateFlow(AppSettings())
            override fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
        }
        override val mailRepository: MailRepository get() = error("unused")
        override val composeRepository: ComposeRepository get() = error("unused")
        override val contactRepository: ContactRepository get() = error("unused")
        override val syncController: SyncController get() = error("unused")
    }

    @Test
    fun `sample account edits preserve connection and never submit typed password`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val accounts = Accounts(sample)
            val vm = AccountSettingsViewModel(Container(accounts, backgroundScope), sample.id).also { store.put("account", it) }
            runCurrent()
            vm.displayName = "My sample"
            vm.signature = "Local signature"
            vm.newPassword = "must-not-be-stored"
            vm.imap.load(server.copy(host = "real.example.test"))
            vm.save(); runCurrent()
            assertEquals(sample.copy(displayName = "My sample", signature = "Local signature"), accounts.saved)
            assertNull(accounts.savedPassword)
            assertFalse(accounts.verified)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun `sample arrival schedules selected account once while busy and reports failures`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val accounts = Accounts(sample)
            val result = CompletableDeferred<Result<Unit>>()
            accounts.arrivalResult = { result.await() }
            val vm = AccountSettingsViewModel(Container(accounts, backgroundScope), sample.id).also { store.put("account", it) }
            runCurrent()
            vm.scheduleSampleArrival(); vm.scheduleSampleArrival(); runCurrent()
            assertEquals(listOf(sample.id), accounts.arrivals)
            assertEquals(DemoArrivalStatus.SCHEDULING, vm.uiState.value.demoArrivalStatus)
            result.complete(Result.success(Unit)); runCurrent()
            assertEquals(DemoArrivalStatus.QUEUED, vm.uiState.value.demoArrivalStatus)
            accounts.arrivalResult = { Result.failure(IllegalStateException("Guardian locked")) }
            vm.scheduleSampleArrival(); runCurrent()
            assertEquals(DemoArrivalStatus.FAILED, vm.uiState.value.demoArrivalStatus)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun `real account cannot invoke sample arrival even with sample looking address`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val accounts = Accounts(sample.copy(isDemo = false))
            val vm = AccountSettingsViewModel(Container(accounts, backgroundScope), sample.id).also { store.put("account", it) }
            runCurrent(); vm.scheduleSampleArrival(); runCurrent()
            assertTrue(accounts.arrivals.isEmpty())
            assertEquals(DemoArrivalStatus.IDLE, vm.uiState.value.demoArrivalStatus)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
