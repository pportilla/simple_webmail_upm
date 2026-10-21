package io.github.pportilla.upmmail.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.repository.AccountRepository
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.repository.ComposeRepository
import io.github.pportilla.upmmail.data.repository.ContactRepository
import io.github.pportilla.upmmail.data.repository.MailRepository
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.data.repository.SyncController
import io.github.pportilla.upmmail.data.repository.UndoHandle
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.HitOpenResult
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.IndexedMessage
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchKey
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.ServerSearchScope
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val imap = ServerSettings("correo.upm.es", 993, SecurityMode.SSL_TLS, "ana")
    private val staff = Account(1, "ana@upm.es", "Ana", imap, imap, "", 0, true, 0)
    private val student = Account(2, "ana@alumnos.upm.es", "Ana", imap, imap, "", 0, true, 1)
    private val inbox = Folder(10, 1, "INBOX", "INBOX", FolderRole.INBOX, 0, 0, 100, true, null)

    /** One collection of [MailRepository.searchServerProgress]: the test feeds its progress. */
    private class ServerRun(val request: ServerSearchRequest) {
        val progress = Channel<ServerSearchProgress>(Channel.UNLIMITED)
        var cancelled = false

        fun emit(hits: List<SearchHit>, finished: Boolean = false, phase: SearchPhase? = SearchPhase.HEADERS, error: MailException? = null) {
            progress.trySend(ServerSearchProgress(request, phase, null, 1, 3, hits, emptyList(), finished, false, error))
            if (finished) progress.close()
        }
    }

    private class FakeMail : MailRepository {
        val local = HashMap<String, List<SearchHit>>()
        val localQueries = ArrayList<String>()
        val runs = ArrayList<ServerRun>()
        val folders = HashMap<Long, Folder>()
        val index = MutableStateFlow<List<IndexStatus>>(emptyList())
        var indexStarts = 0
        var openResult: CompletableDeferred<Result<HitOpenResult>> = CompletableDeferred()
        val recents = ArrayList<String>()

        override suspend fun searchLocalHits(query: String, filters: SearchFilters, limit: Int): List<SearchHit> {
            localQueries += query
            return local[query].orEmpty()
        }

        override fun searchServerProgress(request: ServerSearchRequest): Flow<ServerSearchProgress> = flow {
            val run = ServerRun(request)
            runs += run
            try {
                for (progress in run.progress) emit(progress)
            } catch (e: CancellationException) {
                run.cancelled = true
                throw e
            }
        }

        override suspend fun openSearchHit(hit: SearchHit): Result<HitOpenResult> = openResult.await()

        override fun observeIndexStatus(): Flow<List<IndexStatus>> = index

        override fun startIndexingNow(allowMetered: Boolean) {
            indexStarts++
        }

        override suspend fun getFolder(folderId: Long): Folder? = folders[folderId]

        override suspend fun recentSearches(): List<String> = recents.toList()

        override suspend fun addRecentSearch(query: String) {
            recents.remove(query)
            recents.add(0, query)
        }

        override suspend fun clearRecentSearches() = recents.clear()

        override suspend fun searchLocal(query: String, filters: SearchFilters, limit: Int): List<MessageSummary> = error("unused")

        override fun observeFolders(accountId: Long): Flow<List<Folder>> = emptyFlow()

        override suspend fun findFolderByRole(accountId: Long, role: FolderRole): Folder? = null

        override fun observeMessages(folderId: Long, limit: Int, conversations: Boolean): Flow<List<MessageSummary>> = emptyFlow()

        override fun observeMessage(messageId: Long): Flow<MessageDetail?> = emptyFlow()

        override fun observeThread(messageId: Long): Flow<List<MessageDetail>> = emptyFlow()

        override val syncingFolders: StateFlow<Set<Long>> = MutableStateFlow(emptySet())

        override suspend fun refreshFolder(folderId: Long): Result<Unit> = error("unused")

        override suspend fun refreshAccount(accountId: Long): Result<Unit> = error("unused")

        override suspend fun loadOlder(folderId: Long, count: Int): Result<Boolean> = error("unused")

        override suspend fun ensureBodyLoaded(messageId: Long): Result<Unit> = error("unused")

        override suspend fun setRead(messageIds: List<Long>, read: Boolean) = error("unused")

        override suspend fun setStarred(messageIds: List<Long>, starred: Boolean) = error("unused")

        override suspend fun archive(messageIds: List<Long>): Result<UndoHandle> = error("unused")

        override suspend fun delete(messageIds: List<Long>): Result<UndoHandle?> = error("unused")

        override suspend fun moveTo(messageIds: List<Long>, folderId: Long): Result<UndoHandle> = error("unused")

        override suspend fun undo(handle: UndoHandle) = error("unused")

        override suspend fun markFolderRead(folderId: Long) = error("unused")

        override suspend fun emptyFolder(folderId: Long): Result<Unit> = error("unused")

        override suspend fun downloadAttachment(attachmentId: Long): Result<File> = error("unused")
    }

    private class FakeContainer(
        accounts: List<Account>,
        override val mailRepository: MailRepository,
        settings: AppSettings,
        override val applicationScope: CoroutineScope,
    ) : AppContainer {
        override val accountRepository: AccountRepository = object : AccountRepository {
            override val accounts: Flow<List<Account>> = MutableStateFlow(accounts)

            override suspend fun getAccount(id: Long): Account? = accounts.firstOrNull { it.id == id }

            override suspend fun addAccount(
                email: String,
                password: String,
                displayName: String,
                imap: ServerSettings?,
                smtp: ServerSettings?,
            ): Result<Account> = error("unused")

            override suspend fun updateAccount(account: Account, newPassword: String?, verify: Boolean): Result<Account> = error("unused")

            override suspend fun removeAccount(id: Long) = error("unused")
        }

        override val settingsRepository: SettingsRepository = object : SettingsRepository {
            override val settings: StateFlow<AppSettings> = MutableStateFlow(settings)

            override fun update(transform: (AppSettings) -> AppSettings) = Unit
        }

        override val composeRepository: ComposeRepository get() = error("unused")
        override val contactRepository: ContactRepository get() = error("unused")
        override val syncController: SyncController get() = error("unused")
    }

    private val mail = FakeMail().apply { folders[inbox.id] = inbox }

    private fun TestScope.viewModel(accountId: Long? = null, folderId: Long? = null): SearchViewModel {
        val container = FakeContainer(listOf(staff, student), mail, AppSettings(lastAccountId = 2), backgroundScope)
        val vm = SearchViewModel(container, accountId, folderId, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        runCurrent()
        return vm
    }

    private fun SearchViewModel.type(text: String) = onQueryFieldChange(TextFieldValue(text, TextRange(text.length)))

    private fun message(id: Long, uid: Long = id) = MessageSummary(
        id = id, accountId = 1, folderId = inbox.id, uid = uid, threadId = "t$id", subject = "Elecciones $id",
        from = EmailAddress("junta@upm.es"), to = emptyList(), date = id, snippet = "", isRead = true, isStarred = false,
        isAnswered = false, isForwarded = false, isDraft = false, hasAttachments = false,
    )

    private fun cached(id: Long) = SearchHit.Cached(message(id), "<c$id@upm.es>")

    private fun indexed(uid: Long) = SearchHit.Indexed(
        IndexedMessage(
            indexId = uid, accountId = 1, folderId = 20, folderRole = FolderRole.ARCHIVE, uid = uid,
            messageIdHeader = "<i$uid@upm.es>", subject = "Elecciones de junio", from = EmailAddress("junta@upm.es"),
            to = emptyList(), date = uid, size = 2_000, isRead = false, isStarred = false, isAnswered = false,
            hasAttachments = false,
        ),
    )

    @Test
    fun fewLocalResultsSearchTheServersHeadersByThemselves() = runTest {
        mail.local["elecciones"] = listOf(cached(1))
        val vm = viewModel()
        vm.type("elecciones")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        assertEquals(listOf("elecciones"), mail.localQueries)
        assertEquals(listOf(SearchKey(10, 1)), vm.uiState.value.localResults.map { it.key })
        assertTrue(mail.runs.isEmpty())

        advanceTimeBy(AUTO_SERVER_DELAY_MILLIS + 1)
        val run = mail.runs.single()
        assertEquals(setOf(SearchPhase.HEADERS), run.request.phases)
        assertEquals("every account, the last used first", ServerSearchScope(listOf(2L, 1L)), run.request.scope)
        assertTrue(vm.uiState.value.server!!.running)

        // Matches stream in while it runs; the local one stays in its section.
        run.emit(listOf(indexed(7), cached(1)))
        runCurrent()
        assertEquals(listOf(SearchKey(20, 7)), vm.uiState.value.serverResults.map { it.key })
        assertEquals(listOf(SearchKey(10, 1)), vm.uiState.value.localResults.map { it.key })

        run.emit(listOf(indexed(7), cached(1)), finished = true, phase = null)
        runCurrent()
        val server = vm.uiState.value.server!!
        assertFalse(server.running)
        assertEquals(setOf(SearchPhase.HEADERS), server.done)
        assertFalse(server.textRequested)
        assertTrue(server.binsOffered)

        // The keyboard's search key adds the text search only.
        vm.onSubmit()
        runCurrent()
        assertEquals(setOf(SearchPhase.TEXT), mail.runs.last().request.phases)
        assertEquals(listOf("elecciones"), mail.recents)
    }

    @Test
    fun manyLocalResultsWaitForTheUserAndSubmitSearchesTheTextToo() = runTest {
        mail.local["upm"] = (1L..6L).map(::cached)
        val vm = viewModel(accountId = 1)
        vm.type("upm")
        advanceTimeBy(5_000)
        assertTrue(mail.runs.isEmpty())
        assertNull(vm.uiState.value.server)

        vm.onSubmit()
        runCurrent()
        val run = mail.runs.single()
        assertEquals(setOf(SearchPhase.HEADERS, SearchPhase.TEXT), run.request.phases)
        assertEquals(ServerSearchScope(listOf(1L)), run.request.scope)
        assertTrue(vm.uiState.value.server!!.textRequested)
    }

    @Test
    fun typingOnCancelsThePendingAndTheRunningServerSearch() = runTest {
        val vm = viewModel()
        vm.type("elec")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 100)
        vm.type("elecc")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS - 100)
        assertTrue("the first one never started", mail.runs.isEmpty())
        advanceTimeBy(200)
        val run = mail.runs.single()
        assertEquals("elecc", run.request.query)

        vm.type("elecciones")
        runCurrent()
        assertTrue(run.cancelled)
        assertNull(vm.uiState.value.server)
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS + 10)
        assertEquals("elecciones", mail.runs.last().request.query)
    }

    @Test
    fun stopKeepsTheMatchesAndDoesNotStartAgainByItself() = runTest {
        val vm = viewModel()
        vm.type("elecciones")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS + 10)
        val run = mail.runs.single()
        run.emit(listOf(indexed(3)))
        runCurrent()

        vm.stopServerSearch()
        runCurrent()
        assertTrue(run.cancelled)
        val state = vm.uiState.value
        assertTrue(state.server!!.stopped)
        assertFalse(state.server!!.running)
        assertEquals(listOf(SearchKey(20, 3)), state.serverResults.map { it.key })

        vm.onResume()
        advanceTimeBy(5_000)
        assertEquals(1, mail.runs.size)
    }

    @Test
    fun anErrorIsShownAndRetryRunsTheSearchAgain() = runTest {
        val vm = viewModel()
        vm.type("elecciones")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS + 10)
        mail.runs.single().emit(emptyList(), finished = true, phase = null, error = MailException(MailErrorKind.NETWORK, "offline"))
        runCurrent()
        val server = vm.uiState.value.server!!
        assertEquals(MailErrorKind.NETWORK, (server.error as MailException).kind)
        assertFalse(server.running)
        assertTrue(server.canRetry)

        vm.retryServerSearch()
        runCurrent()
        assertEquals(2, mail.runs.size)
        assertEquals(setOf(SearchPhase.HEADERS), mail.runs.last().request.phases)
        assertNull(vm.uiState.value.server!!.error)
    }

    @Test
    fun thisFolderSearchesOnlyThatFolderAndSwitchingScopeCancels() = runTest {
        val vm = viewModel(accountId = 1, folderId = inbox.id)
        vm.type("elecciones")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS + 10)
        val run = mail.runs.single()
        assertEquals(ServerSearchScope(listOf(1L), folderId = inbox.id), run.request.scope)
        assertEquals(inbox, vm.uiState.value.serverFolder)

        vm.setThisFolderOnly(false)
        runCurrent()
        assertTrue(run.cancelled)
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS + 10)
        assertEquals(ServerSearchScope(listOf(1L)), mail.runs.last().request.scope)
        assertNull(vm.uiState.value.serverFolder)
    }

    @Test
    fun anIndexOnlyMatchIsDownloadedBeforeItOpens() = runTest {
        mail.local["elecciones"] = listOf(indexed(5), indexed(6))
        val vm = viewModel()
        val events = ArrayList<SearchEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.type("elecciones")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)

        vm.openIndexedHit(indexed(5))
        runCurrent()
        assertEquals(SearchKey(20, 5), vm.uiState.value.openingKey)
        vm.openIndexedHit(indexed(6))
        mail.openResult.complete(Result.success(HitOpenResult.Opened(42)))
        runCurrent()
        assertNull(vm.uiState.value.openingKey)
        assertEquals("one at a time", listOf<SearchEvent>(SearchEvent.OpenMessage(42)), events)

        mail.openResult = CompletableDeferred(Result.success(HitOpenResult.Gone))
        vm.openIndexedHit(indexed(6))
        runCurrent()
        assertEquals(SearchEvent.Info(R.string.search_hit_gone), events.last())

        mail.openResult = CompletableDeferred(Result.failure(MailException(MailErrorKind.NETWORK)))
        vm.openIndexedHit(indexed(6))
        runCurrent()
        assertTrue(events.last() is SearchEvent.Error)
    }

    @Test
    fun indexCoverageIsShownWhileIncomplete() = runTest {
        mail.index.value = listOf(IndexStatus(1, 1_000, 5_000, complete = false), IndexStatus(2, 10, 10, complete = true))
        val vm = viewModel(accountId = 2)
        assertNull("the searched account is complete", vm.uiState.value.index)
        val all = viewModel()
        assertEquals(IndexSummary(1_010, 5_010, running = false), all.uiState.value.index)
        mail.index.value = listOf(IndexStatus(1, 1_000, 5_000, complete = false, running = true))
        runCurrent()
        assertTrue(all.uiState.value.index!!.running)
        all.indexNow(allowMetered = false)
        assertEquals(1, mail.indexStarts)
    }
}
