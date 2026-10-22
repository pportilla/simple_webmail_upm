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
import io.github.pportilla.upmmail.model.ContactSource
import io.github.pportilla.upmmail.model.ContactSuggestion
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
import io.github.pportilla.upmmail.model.SearchQuery
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
import java.time.LocalDate

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

        fun emit(
            hits: List<SearchHit>,
            finished: Boolean = false,
            phase: SearchPhase? = SearchPhase.HEADERS,
            error: MailException? = null,
            refuted: Set<SearchKey> = emptySet(),
        ) {
            progress.trySend(ServerSearchProgress(request, phase, null, 1, 3, hits, emptyList(), finished, false, error, refuted))
            if (finished) progress.close()
        }
    }

    private class FakeMail : MailRepository {
        val local = HashMap<String, List<SearchHit>>()
        val localQueries = ArrayList<String>()
        val localFilters = ArrayList<SearchFilters>()
        val runs = ArrayList<ServerRun>()
        val folders = HashMap<Long, Folder>()
        val index = MutableStateFlow<List<IndexStatus>>(emptyList())
        var indexStarts = 0
        var openResult: CompletableDeferred<Result<HitOpenResult>> = CompletableDeferred()
        val recents = ArrayList<String>()

        override suspend fun searchLocalHits(query: String, filters: SearchFilters, limit: Int): List<SearchHit> {
            localQueries += query
            localFilters += filters
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

    /** Contacts suggested by word prefix of the name or address, like the real repository. */
    private class FakeContacts : ContactRepository {
        val contacts = ArrayList<EmailAddress>()
        val queries = ArrayList<String>()

        override suspend fun suggest(query: String, exclude: Set<String>, limit: Int): List<ContactSuggestion> {
            queries += query
            val text = query.lowercase()
            return contacts
                .filter { it.address !in exclude }
                .filter { c -> listOfNotNull(c.name, c.address).any { it.lowercase().split(' ', '.', '@').any { w -> w.startsWith(text) } || it.lowercase() == text } }
                .take(limit)
                .map { ContactSuggestion(it, ContactSource.HISTORY) }
        }
    }

    private class FakeContainer(
        accounts: List<Account>,
        override val mailRepository: MailRepository,
        override val contactRepository: ContactRepository,
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
        override val syncController: SyncController get() = error("unused")
    }

    private val mail = FakeMail().apply { folders[inbox.id] = inbox }
    private val contacts = FakeContacts()
    private val today = LocalDate.of(2026, 10, 22)

    private fun TestScope.viewModel(accountId: Long? = null, folderId: Long? = null, initialQuery: String? = null): SearchViewModel {
        val container = FakeContainer(listOf(staff, student), mail, contacts, AppSettings(lastAccountId = 2), backgroundScope)
        val vm = SearchViewModel(container, accountId, folderId, initialQuery, coroutineScope = backgroundScope, today = { today })
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
    fun localResultsTheServerRuledOutLeaveTheList() = runTest {
        // The index cannot see the text of 5; the server finds "borrador" in it.
        mail.local["from:ana -borrador"] = listOf(indexed(5), cached(1))
        val vm = viewModel()
        vm.type("from:ana -borrador")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + AUTO_SERVER_DELAY_MILLIS + 10)
        assertEquals(listOf(SearchKey(20, 5), SearchKey(10, 1)), vm.uiState.value.localResults.map { it.key })
        val run = mail.runs.single()
        run.emit(listOf(cached(1)), refuted = setOf(SearchKey(20, 5)))
        runCurrent()
        assertEquals(listOf(SearchKey(10, 1)), vm.uiState.value.localResults.map { it.key })
        run.emit(listOf(cached(1)), finished = true, phase = null, refuted = setOf(SearchKey(20, 5)))
        runCurrent()
        assertEquals(listOf(SearchKey(10, 1)), vm.uiState.value.localResults.map { it.key })
        assertTrue(vm.uiState.value.serverResults.isEmpty())
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

    @Test
    fun anInitialQueryIsSearchedAtOnceAndItsAddressShowsAsAName() = runTest {
        val fromAna = SearchHit.Cached(message(3).copy(from = EmailAddress("ana.garcia@upm.es", "Ana García")), "<c3@upm.es>")
        mail.local["from:ana.garcia@upm.es"] = listOf(fromAna)
        val vm = viewModel(initialQuery = " from:ana.garcia@upm.es ")
        assertEquals("from:ana.garcia@upm.es", vm.query)
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        assertEquals(listOf("from:ana.garcia@upm.es"), mail.localQueries)
        val state = vm.uiState.value
        assertEquals(listOf(SearchKey(10, 3)), state.localResults.map { it.key })
        assertEquals(listOf<CriterionChip>(CriterionChip.Person(PersonField.FROM, "ana.garcia@upm.es")), state.chips)
        assertEquals("the sender of a match names the address", "Ana García", personLabel("ana.garcia@upm.es", state.names))
        assertTrue("found on a match, not looked up", contacts.queries.isEmpty())
        assertFalse("people only: the server has no message text to search", state.searchesText)

        // An address on no match is looked up in the contacts once.
        contacts.contacts += EmailAddress("carlos@upm.es", "Carlos Pérez")
        vm.type("to:carlos@upm.es")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        assertEquals("Carlos Pérez", vm.uiState.value.names["carlos@upm.es"])
        assertEquals(listOf("carlos@upm.es"), contacts.queries)
    }

    @Test
    fun theFlagChipsAreTheOperatorsOfTheText() = runTest {
        val vm = viewModel()
        vm.type("examen")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        vm.setUnreadOnly(true)
        assertEquals("examen is:unread", vm.query)
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        assertTrue(vm.uiState.value.filters.unreadOnly)
        assertEquals("examen is:unread", mail.localQueries.last())
        assertTrue("the local search gets the flag too", mail.localFilters.last().unreadOnly)

        // Typed by hand, in Spanish: the chip shows it, and turning the chip off removes it.
        vm.type("examen es:destacado")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        val state = vm.uiState.value
        assertTrue(state.filters.starredOnly)
        assertFalse(state.filters.unreadOnly)
        vm.setStarredOnly(false)
        assertEquals("examen", vm.query)
        runCurrent()
        assertFalse(vm.uiState.value.filters.starredOnly)

        vm.type("from:ana -spam examen")
        vm.removeChip(CriterionChip.Excluded("spam"))
        assertEquals("from:ana examen", vm.query)
        vm.removeChip(CriterionChip.Person(PersonField.FROM, "ana"))
        assertEquals("examen", vm.query)
        runCurrent()
        assertTrue(vm.uiState.value.chips.isEmpty())
    }

    @Test
    fun theAdvancedPanelEditsTheQueryAndSuggestsContacts() = runTest {
        contacts.contacts += EmailAddress("carlos@upm.es", "Carlos Pérez")
        contacts.contacts += EmailAddress("carmen@upm.es", "Carmen Ruiz")
        val vm = viewModel()
        vm.type("from:ana subject:examen notas")
        vm.openAdvancedSearch()
        val opened = vm.advanced!!
        assertEquals(listOf("ana"), opened.from)
        assertEquals("examen", opened.subject)
        assertEquals("notas", opened.words)

        vm.onPersonInput(PersonField.TO, "car")
        advanceTimeBy(SUGGEST_DEBOUNCE_MILLIS + 1)
        val suggestions = vm.suggestions.value!!
        assertEquals(PersonField.TO, suggestions.field)
        assertEquals(listOf("carlos@upm.es", "carmen@upm.es"), suggestions.items.map { it.address.address })
        vm.pickSuggestion(PersonField.TO, suggestions.items.first())
        assertNull(vm.suggestions.value)
        assertEquals(listOf("carlos@upm.es"), vm.advanced!!.to)
        assertEquals("", vm.advanced!!.toInput)

        vm.updateAdvancedSearch { it.copy(date = DateChoice.LAST_7_DAYS, hasAttachment = true, words = "") }
        vm.applyAdvancedSearch()
        runCurrent()
        assertNull(vm.advanced)
        val text = "from:ana to:carlos@upm.es subject:examen after:2026-10-16 has:attachment"
        assertEquals(text, vm.query)
        assertEquals("searched and remembered", listOf(text), mail.recents)
        val state = vm.uiState.value
        assertTrue(state.filters.withAttachments)
        assertEquals(
            listOf(
                CriterionChip.Person(PersonField.FROM, "ana"),
                CriterionChip.Person(PersonField.TO, "carlos@upm.es"),
                CriterionChip.Subject("examen"),
                CriterionChip.Dates(LocalDate.of(2026, 10, 16), null, DateChoice.LAST_7_DAYS),
            ),
            state.chips,
        )
        assertEquals("the picked contact names its chip", "Carlos Pérez", state.names["carlos@upm.es"])

        // Opened again, the panel shows the same search; Clear then Search empties it.
        vm.openAdvancedSearch()
        assertEquals(
            SearchQuery.parse(text),
            vm.advanced!!.toQuery(today),
        )
        assertEquals(DateChoice.LAST_7_DAYS, vm.advanced!!.date)
        vm.clearAdvancedSearch()
        assertTrue(vm.advanced!!.isEmpty)
        vm.applyAdvancedSearch()
        assertEquals("", vm.query)

        // Closing drops the edits.
        vm.type("examen")
        vm.openAdvancedSearch()
        vm.onPersonInput(PersonField.FROM, "ana,")
        assertEquals(listOf("ana"), vm.advanced!!.from)
        vm.closeAdvancedSearch()
        assertNull(vm.advanced)
        assertEquals("examen", vm.query)
    }

    @Test
    fun aQueryWithoutWordsSearchesOnlyTheServersHeaders() = runTest {
        mail.local["from:ana"] = (1L..6L).map(::cached)
        val vm = viewModel()
        vm.type("from:ana")
        advanceTimeBy(SEARCH_DEBOUNCE_MILLIS + 1)
        assertFalse(vm.uiState.value.searchesText)
        vm.onSubmit()
        runCurrent()
        assertEquals(setOf(SearchPhase.HEADERS), mail.runs.single().request.phases)
        assertFalse(vm.uiState.value.server!!.textRequested)
    }

    @Test
    fun anAttachmentFilterAloneWaitsForTheUser() = runTest {
        val vm = viewModel()
        vm.setWithAttachments(true)
        assertEquals("has:attachment", vm.query)
        advanceTimeBy(5_000)
        assertEquals(listOf("has:attachment"), mail.localQueries)
        assertTrue(mail.runs.isEmpty())
    }

    @Test
    fun theOperatorTipShowsUntilAnOperatorIsUsed() = runTest {
        mail.recents += "elecciones"
        val vm = viewModel()
        runCurrent()
        assertTrue(vm.uiState.value.operatorTip)
        vm.type("de:ana")
        vm.onSubmit()
        runCurrent()
        assertFalse(vm.uiState.value.operatorTip)
    }
}
