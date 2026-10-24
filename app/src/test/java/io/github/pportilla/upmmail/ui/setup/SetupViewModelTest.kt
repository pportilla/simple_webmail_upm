package io.github.pportilla.upmmail.ui.setup

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
class SetupViewModelTest {
    private val server = ServerSettings("sample.invalid", 993, SecurityMode.SSL_TLS, "sample")
    private val sample = Account(id = 8, email = "review@example.invalid", displayName = "Sample", imap = server, smtp = server, signature = "", color = 0, notificationsEnabled = false, sortOrder = 0, isDemo = true)

    private class Accounts(private val response: suspend () -> Result<Account>) : AccountRepository {
        var sampleCalls = 0
        override val accounts = MutableStateFlow<List<Account>>(emptyList())
        override suspend fun getAccount(id: Long) = accounts.value.firstOrNull { it.id == id }
        override suspend fun addDemoAccount(): Result<Account> { sampleCalls++; return response() }
        override suspend fun addAccount(email: String, password: String, displayName: String, imap: ServerSettings?, smtp: ServerSettings?): Result<Account> = error("Sample setup must not call credential setup")
        override suspend fun updateAccount(account: Account, newPassword: String?, verify: Boolean): Result<Account> = error("unused")
        override suspend fun removeAccount(id: Long) = error("unused")
    }

    private class Container(override val accountRepository: AccountRepository, override val applicationScope: CoroutineScope) : AppContainer {
        // Setup does not choose an adult band or accept terms on behalf of the user.
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
    fun `sample setup bypasses credential entry without changing age or consent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val completion = CompletableDeferred<Result<Account>>()
            val accounts = Accounts { completion.await() }
            val container = Container(accounts, backgroundScope)
            val before = container.safetyRepository.state.value
            val vm = SetupViewModel(container).also { store.put("setup", it) }
            vm.password = "typed-real-password-never-submitted"
            vm.trySampleMail(); vm.trySampleMail(); runCurrent()
            assertEquals(1, accounts.sampleCalls)
            assertTrue(vm.uiState.value.signingIn)
            completion.complete(Result.success(sample)); runCurrent()
            assertEquals(sample.id, vm.uiState.value.signedInAccountId)
            assertEquals(sample.id, container.settingsRepository.settings.value.lastAccountId)
            assertEquals("", vm.password)
            assertEquals(before, container.safetyRepository.state.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun `sample repository denial remains on setup and does not select an account`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val denied = IllegalStateException("Guardian session required")
            val accounts = Accounts { Result.failure(denied) }
            val container = Container(accounts, backgroundScope)
            val previous = container.settingsRepository.settings.value.lastAccountId
            val vm = SetupViewModel(container).also { store.put("setup", it) }
            vm.trySampleMail(); runCurrent()
            assertFalse(vm.uiState.value.signingIn)
            assertNull(vm.uiState.value.signedInAccountId)
            assertEquals(denied, (vm.uiState.value.error as SetupError.Failed).error)
            assertEquals(previous, container.settingsRepository.settings.value.lastAccountId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
