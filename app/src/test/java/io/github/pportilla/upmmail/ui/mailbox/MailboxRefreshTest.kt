package io.github.pportilla.upmmail.ui.mailbox

import androidx.lifecycle.ViewModelStore
import io.github.pportilla.upmmail.data.db.FakeSettings
import io.github.pportilla.upmmail.data.repository.*
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MailboxRefreshTest {
    private val server = ServerSettings("mail.invalid", 993, SecurityMode.SSL_TLS, "test")
    private val account = Account(1, "test@example.invalid", "Test", server, server, "", 0, false, 0)
    private val inbox = Folder(1, 1, "INBOX", "INBOX", FolderRole.INBOX, depth = 0, unreadCount = 0, totalCount = 0, selectable = true, lastSyncedAt = System.currentTimeMillis())
    private val secondAccount = account.copy(id = 2, email = "second@example.invalid")
    private val secondInbox = inbox.copy(id = 3, accountId = 2)
    private val archive = inbox.copy(id = 2, fullName = "Archive", displayName = "Archive", role = FolderRole.ARCHIVE)

    private class Mail(private val folders: List<Folder>) : MailRepository {
        var refresh: suspend () -> Result<Unit> = { Result.success(Unit) }
        var calls = 0
        override val syncingFolders = MutableStateFlow<Set<Long>>(emptySet())
        override fun observeFolders(accountId: Long) = flowOf(folders.filter { it.accountId == accountId })
        override suspend fun getFolder(folderId: Long) = folders.find { it.id == folderId }
        override suspend fun findFolderByRole(accountId: Long, role: FolderRole) = folders.find { it.role == role }
        override fun observeMessages(folderId: Long, limit: Int, conversations: Boolean) = flowOf(emptyList<MessageSummary>())
        override fun observeMessage(messageId: Long) = flowOf<MessageDetail?>(null)
        override fun observeThread(messageId: Long) = flowOf(emptyList<MessageDetail>())
        override suspend fun refreshFolder(folderId: Long): Result<Unit> { calls++; return refresh() }
        override suspend fun refreshAccount(accountId: Long): Result<Unit> { calls++; return refresh() }
        override suspend fun loadOlder(folderId: Long, count: Int): Result<Boolean> = error("unused")
        override suspend fun ensureBodyLoaded(messageId: Long): Result<Unit> = error("unused")
        override suspend fun setRead(messageIds: List<Long>, read: Boolean) = Unit
        override suspend fun setStarred(messageIds: List<Long>, starred: Boolean) = Unit
        override suspend fun archive(messageIds: List<Long>): Result<UndoHandle> = error("unused")
        override suspend fun delete(messageIds: List<Long>): Result<UndoHandle?> = error("unused")
        override suspend fun moveTo(messageIds: List<Long>, folderId: Long): Result<UndoHandle> = error("unused")
        override suspend fun undo(handle: UndoHandle) = Unit
        override suspend fun markFolderRead(folderId: Long) = Unit
        override suspend fun emptyFolder(folderId: Long): Result<Unit> = error("unused")
        override suspend fun downloadAttachment(attachmentId: Long): Result<File> = error("unused")
        override suspend fun searchLocal(query: String, filters: SearchFilters, limit: Int): List<MessageSummary> = error("unused")
        override suspend fun searchLocalHits(query: String, filters: SearchFilters, limit: Int): List<SearchHit> = error("unused")
        override fun searchServerProgress(request: ServerSearchRequest): Flow<ServerSearchProgress> = emptyFlow()
        override suspend fun openSearchHit(hit: SearchHit): Result<HitOpenResult> = error("unused")
        override fun observeIndexStatus(): Flow<List<IndexStatus>> = emptyFlow()
        override fun startIndexingNow(allowMetered: Boolean) = Unit
        override suspend fun recentSearches(): List<String> = emptyList()
        override suspend fun addRecentSearch(query: String) = Unit
        override suspend fun clearRecentSearches() = Unit
    }

    private class Compose : ComposeRepository {
        override fun observeOutbox(accountId: Long?) = flowOf(emptyList<OutboxItem>())
        override suspend fun createDraft(accountId: Long, mode: ComposeMode, sourceMessageId: Long?): Result<Draft> = error("unused")
        override suspend fun loadDraft(draftId: Long): Draft? = error("unused")
        override suspend fun draftFromServerMessage(messageId: Long): Result<Draft> = error("unused")
        override suspend fun saveDraft(draft: Draft, upload: Boolean): Long = error("unused")
        override suspend fun discardDraft(draft: Draft) = Unit
        override suspend fun send(draft: Draft, delaySeconds: Int): Long = error("unused")
        override suspend fun cancelSend(outboxId: Long): Draft? = error("unused")
        override suspend fun retrySend(outboxId: Long) = Unit
        override suspend fun deleteOutboxItem(outboxId: Long) = Unit
        override suspend fun describeAttachment(uri: String): OutgoingAttachment? = error("unused")
    }

    private class Controller : SyncController {
        var outboxRequests = 0
        override fun schedulePeriodicSync() = Unit
        override fun onAppForeground() = Unit
        override fun onAppBackground() = Unit
        override fun requestOutboxSend() { outboxRequests++ }
    }

    private fun container(mail: Mail, scope: CoroutineScope, controller: Controller): AppContainer = object : AppContainer {
        override val safetyRepository = LocalSafetyRepository().apply { completeGeneralSetup() }
        override val applicationScope = scope
        override val mailRepository = mail
        override val settingsRepository = FakeSettings(AppSettings(lastAccountId = account.id, lastFolderId = inbox.id))
        override val composeRepository = Compose()
        override val contactRepository: ContactRepository get() = error("unused")
        override val syncController = controller
        override val accountRepository = object : AccountRepository {
            override val accounts = flowOf(listOf(account, secondAccount))
            override suspend fun getAccount(id: Long) = listOf(account, secondAccount).find { it.id == id }
            override suspend fun addAccount(email: String, password: String, displayName: String, imap: ServerSettings?, smtp: ServerSettings?): Result<Account> = error("unused")
            override suspend fun updateAccount(account: Account, newPassword: String?, verify: Boolean): Result<Account> = error("unused")
            override suspend fun removeAccount(id: Long) = Unit
        }
    }

    private suspend fun TestScope.withMailbox(block: suspend (MailboxViewModel, Mail, Controller) -> Unit) = run {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val mail = Mail(listOf(inbox, archive, secondInbox))
        val controller = Controller()
        val vm = MailboxViewModel(container(mail, backgroundScope, controller))
        owner.put("mailbox", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        try { block(vm, mail, controller) }
        finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun cancellationClearsSpinnerAndAnotherPullWorks() = runTest {
        withMailbox { vm, mail, controller ->
            mail.refresh = { throw CancellationException("Interrupted refresh") }
            vm.refresh(); runCurrent()
            assertFalse(vm.uiState.value.isRefreshing)
            mail.refresh = { Result.success(Unit) }
            vm.refresh(); runCurrent()
            assertEquals(2, mail.calls)
            assertEquals(2, controller.outboxRequests)
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }

    @Test fun folderChangeCancelsOldPullAndItsFinallyCannotClearNewPull() = runTest {
        withMailbox { vm, mail, _ ->
            var cancelled = false
            val oldCleanup = CompletableDeferred<Unit>()
            mail.refresh = {
                try { awaitCancellation() }
                finally { cancelled = true; withContext(NonCancellable) { oldCleanup.await() } }
            }
            vm.refresh(); runCurrent()
            assertTrue(vm.uiState.value.isRefreshing)
            vm.selectFolder(archive); runCurrent()
            assertTrue(cancelled)
            assertFalse(vm.uiState.value.isRefreshing)
            val finish = CompletableDeferred<Unit>()
            mail.refresh = { finish.await(); Result.success(Unit) }
            vm.refresh(); runCurrent()
            assertTrue(vm.uiState.value.isRefreshing)
            assertEquals(2, mail.calls)
            oldCleanup.complete(Unit); runCurrent()
            assertTrue("old request cleanup must not clear the new spinner", vm.uiState.value.isRefreshing)
            finish.complete(Unit); runCurrent()
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }

    @Test fun accountChangeCancelsPreviousAccountRefresh() = runTest {
        withMailbox { vm, mail, _ ->
            var cancelled = false
            mail.refresh = { try { awaitCancellation() } finally { cancelled = true } }
            vm.refresh(); runCurrent()
            assertTrue(vm.uiState.value.isRefreshing)
            vm.selectAccount(secondAccount); runCurrent()
            assertTrue(cancelled)
            assertEquals(secondAccount.id, vm.uiState.value.account!!.id)
            assertFalse(vm.uiState.value.isRefreshing)
            mail.refresh = { Result.success(Unit) }
            vm.refresh(); runCurrent()
            assertEquals(2, mail.calls)
        }
    }

    @Test fun networkFailureClearsSpinnerEmitsRetryAndDuplicatePullDoesNotDuplicateWork() = runTest {
        withMailbox { vm, mail, controller ->
            val finish = CompletableDeferred<Unit>()
            val errors = mutableListOf<MailboxEvent.Error>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.events.collect { if (it is MailboxEvent.Error) errors += it }
            }
            mail.refresh = { finish.await(); Result.failure(MailException(MailErrorKind.NETWORK)) }
            vm.refresh(); vm.refresh(); runCurrent()
            assertTrue(vm.uiState.value.isRefreshing)
            assertEquals(1, mail.calls)
            assertEquals(1, controller.outboxRequests)
            finish.complete(Unit); runCurrent()
            assertFalse(vm.uiState.value.isRefreshing)
            assertEquals(MailErrorKind.NETWORK, (errors.single().error as MailException).kind)
            mail.refresh = { Result.success(Unit) }
            errors.single().retry!!.invoke(); runCurrent()
            assertEquals(2, mail.calls)
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }
}
