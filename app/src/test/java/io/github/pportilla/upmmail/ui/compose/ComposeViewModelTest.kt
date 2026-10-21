package io.github.pportilla.upmmail.ui.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.pportilla.upmmail.data.repository.AccountRepository
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.repository.ComposeRepository
import io.github.pportilla.upmmail.data.repository.ContactRepository
import io.github.pportilla.upmmail.data.repository.MailRepository
import io.github.pportilla.upmmail.data.repository.SettingsRepository
import io.github.pportilla.upmmail.data.repository.SyncController
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.AppSettings
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.ContactSource
import io.github.pportilla.upmmail.model.ContactSuggestion
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutgoingAttachment
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.ui.navigation.ComposeArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelTest {

    private val imap = ServerSettings("correo.upm.es", 993, SecurityMode.SSL_TLS, "ana")
    private val smtp = ServerSettings("smtp.upm.es", 587, SecurityMode.STARTTLS, "ana")
    private val staff = Account(1, "ana@upm.es", "Ana", imap, smtp, "Ana\nETSI", 0, true, 0)
    private val student = Account(2, "ana@alumnos.upm.es", "Ana", imap, smtp, "Ana (alumna)", 0, true, 1)

    private class FakeCompose : ComposeRepository {
        var created: Result<Draft>? = null
        val createCalls = mutableListOf<Triple<Long, ComposeMode, Long?>>()
        val saved = mutableListOf<Pair<Draft, Boolean>>()
        val discarded = mutableListOf<Draft>()
        val sent = mutableListOf<Pair<Draft, Int>>()
        val localDrafts = mutableMapOf<Long, Draft>()
        var serverDraft: Draft? = null
        var nextId = 100L
        var importable = mutableMapOf<String, OutgoingAttachment>()

        override suspend fun createDraft(accountId: Long, mode: ComposeMode, sourceMessageId: Long?): Result<Draft> {
            createCalls += Triple(accountId, mode, sourceMessageId)
            return created ?: Result.success(Draft(accountId = accountId, body = "\n\n-- \nAna\nETSI", mode = mode))
        }

        override suspend fun loadDraft(draftId: Long): Draft? = localDrafts[draftId]

        override suspend fun draftFromServerMessage(messageId: Long): Result<Draft> =
            serverDraft?.let { Result.success(it) } ?: Result.failure(MailException(MailErrorKind.NOT_FOUND))

        override suspend fun saveDraft(draft: Draft, upload: Boolean): Long {
            val id = if (draft.id != 0L) draft.id else nextId++
            saved += draft.copy(id = id) to upload
            localDrafts[id] = draft.copy(id = id)
            return id
        }

        override suspend fun discardDraft(draft: Draft) {
            discarded += draft
            localDrafts.remove(draft.id)
        }

        override suspend fun send(draft: Draft, delaySeconds: Int): Long {
            sent += draft to delaySeconds
            localDrafts.remove(draft.id)
            return 7L
        }

        override suspend fun cancelSend(outboxId: Long): Draft? = null

        override fun observeOutbox(accountId: Long?): Flow<List<OutboxItem>> = emptyFlow()

        override suspend fun retrySend(outboxId: Long) = Unit

        override suspend fun deleteOutboxItem(outboxId: Long) = Unit

        override suspend fun describeAttachment(uri: String): OutgoingAttachment? = importable[uri]
    }

    private class FakeContacts : ContactRepository {
        val queries = mutableListOf<Pair<String, Set<String>>>()

        override suspend fun suggest(query: String, exclude: Set<String>, limit: Int): List<ContactSuggestion> {
            queries += query to exclude
            return listOf(
                ContactSuggestion(EmailAddress("luis@upm.es", "Luis Gómez"), ContactSource.HISTORY),
                ContactSuggestion(EmailAddress("lucia@upm.es", "Lucía"), ContactSource.DEVICE),
            ).filter { it.address.address !in exclude }
        }
    }

    private class FakeContainer(
        accounts: List<Account>,
        override val composeRepository: ComposeRepository,
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
        override val mailRepository: MailRepository get() = error("unused")
        override val syncController: SyncController get() = error("unused")
    }

    private val compose = FakeCompose()
    private val contacts = FakeContacts()

    private fun TestScope.viewModel(
        args: ComposeArgs = ComposeArgs(),
        accounts: List<Account> = listOf(staff, student),
        settings: AppSettings = AppSettings(lastAccountId = 2, undoSendSeconds = 10),
        restoredDraftId: Long = -1,
    ): ComposeViewModel {
        val container = FakeContainer(accounts, compose, contacts, settings, backgroundScope)
        val vm = ComposeViewModel(container, args, restoredDraftId, backgroundScope)
        runCurrent()
        return vm
    }

    /** Runs pending work, including the background scope (advanceUntilIdle ignores it). */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun ComposeViewModel.type(kind: RecipientKind, text: String) {
        var current = input(kind).text
        for (c in text) {
            current = input(kind).text + c
            onInputChange(kind, TextFieldValue(current, TextRange(current.length)))
        }
    }

    private fun ComposeViewModel.setBody(text: String) = onBodyChange(TextFieldValue(text, TextRange(text.length)))

    private fun ComposeViewModel.collectEvents(scope: TestScope): MutableList<ComposeEvent> {
        val list = mutableListOf<ComposeEvent>()
        scope.backgroundScope.launchCollect(this, list)
        scope.runCurrent()
        return list
    }

    private fun CoroutineScope.launchCollect(vm: ComposeViewModel, into: MutableList<ComposeEvent>) {
        launch(Dispatchers.Unconfined) { vm.events.collect { into += it } }
    }

    // ------------------------------------------------------------------ loading

    @Test
    fun `new message uses the last account and applies prefills before the signature`() = runTest {
        val vm = viewModel(ComposeArgs(to = "a@upm.es, nonsense", subject = "Hi\nthere", body = "Shared text"))
        val state = vm.uiState.value
        assertEquals(ComposeStatus.Ready, state.status)
        assertEquals(listOf(Triple(2L, ComposeMode.NEW, null)), compose.createCalls)
        assertEquals(2L, state.accountId)
        assertEquals(listOf(true, false), state.to.map { it.valid })
        assertEquals("Hi there", vm.subject.text)
        assertEquals("Shared text\n\n-- \nAna\nETSI", vm.body.text)
        assertEquals(ComposeTitle.NEW, state.title)
        assertEquals(FocusTarget.BODY, state.focusRequest?.target)
    }

    @Test
    fun `explicit account wins and reply starts in the body at the top`() = runTest {
        compose.created = Result.success(
            Draft(
                accountId = 1,
                to = listOf(EmailAddress("luis@upm.es")),
                subject = "Re: Exam",
                body = "\n\n-- \nAna\nETSI\n\nOn Monday, Luis wrote:\n> hi",
                mode = ComposeMode.REPLY,
            ),
        )
        val vm = viewModel(ComposeArgs(accountId = 1, mode = ComposeMode.REPLY, sourceMessageId = 42))
        assertEquals(listOf(Triple(1L, ComposeMode.REPLY, 42L)), compose.createCalls)
        assertEquals(ComposeTitle.REPLY, vm.uiState.value.title)
        assertEquals(FocusTarget.BODY, vm.uiState.value.focusRequest?.target)
        assertEquals(TextRange(0), vm.body.selection)
    }

    @Test
    fun `failed creation shows an error and retry recovers`() = runTest {
        compose.created = Result.failure(MailException(MailErrorKind.NETWORK))
        val vm = viewModel(ComposeArgs(mode = ComposeMode.FORWARD, sourceMessageId = 3))
        val status = vm.uiState.value.status
        assertTrue(status is ComposeStatus.Failed && status.problem == LoadProblem.ERROR)
        compose.created = null
        vm.retry()
        runCurrent()
        assertEquals(ComposeStatus.Ready, vm.uiState.value.status)
        assertEquals(FocusTarget.TO, vm.uiState.value.focusRequest?.target)
    }

    @Test
    fun `missing local draft and no account are reported`() = runTest {
        val missing = viewModel(ComposeArgs(draftId = 5))
        assertEquals(ComposeStatus.Failed(LoadProblem.DRAFT_MISSING), missing.uiState.value.status)
        val noAccount = viewModel(accounts = emptyList())
        assertEquals(ComposeStatus.Failed(LoadProblem.NO_ACCOUNT), noAccount.uiState.value.status)
    }

    @Test
    fun `shared attachments are imported and do not count as an edit`() = runTest {
        val file = OutgoingAttachment("file:///compose/a.pdf", "a.pdf", "application/pdf", 1000)
        compose.importable["content://a"] = file
        val events = mutableListOf<ComposeEvent>()
        val vm = viewModel(ComposeArgs(attachmentUris = listOf("content://a", "content://broken")))
        backgroundScope.launchCollect(vm, events)
        settle()
        assertEquals(listOf(file), vm.uiState.value.attachments)
        assertEquals(0, vm.uiState.value.importingAttachments)
        assertTrue(events.contains(ComposeEvent.AttachmentsFailed(1)))
        vm.close()
        settle()
        assertTrue(compose.saved.isEmpty())
    }

    // ------------------------------------------------------------------ recipients

    @Test
    fun `typing a comma makes a chip and suggestions exclude added addresses`() = runTest {
        val vm = viewModel()
        vm.onRecipientFocusChanged(RecipientKind.TO, true)
        vm.type(RecipientKind.TO, "lucia@upm.es,")
        assertEquals(listOf("lucia@upm.es"), vm.uiState.value.to.map { it.address.address })
        assertEquals("", vm.input(RecipientKind.TO).text)

        vm.type(RecipientKind.TO, "lu")
        advanceTimeBy(100)
        runCurrent()
        assertNull(vm.uiState.value.suggestions)
        advanceTimeBy(100)
        runCurrent()
        val suggestions = vm.uiState.value.suggestions!!
        assertEquals(RecipientKind.TO, suggestions.kind)
        assertEquals(listOf("luis@upm.es"), suggestions.items.map { it.address.address })
        assertEquals("lu" to setOf("lucia@upm.es"), contacts.queries.last())

        vm.onRecipientImeAction(RecipientKind.TO)
        assertEquals(listOf("lucia@upm.es", "luis@upm.es"), vm.uiState.value.to.map { it.address.address })
        assertEquals("Luis Gómez", vm.uiState.value.to.last().address.name)
        assertNull(vm.uiState.value.suggestions)
    }

    @Test
    fun `backspace on empty input selects then removes the last chip`() = runTest {
        val vm = viewModel()
        vm.type(RecipientKind.CC, "a@upm.es;b@upm.es;")
        assertTrue(vm.onBackspaceOnEmpty(RecipientKind.CC))
        assertEquals(ChipRef(RecipientKind.CC, 1), vm.uiState.value.selectedChip)
        assertTrue(vm.onBackspaceOnEmpty(RecipientKind.CC))
        assertEquals(listOf("a@upm.es"), vm.uiState.value.cc.map { it.address.address })
        assertNull(vm.uiState.value.selectedChip)
        assertFalse(vm.onBackspaceOnEmpty(RecipientKind.BCC))
    }

    @Test
    fun `losing focus commits pending text and editing a chip brings it back`() = runTest {
        val vm = viewModel()
        vm.onRecipientFocusChanged(RecipientKind.TO, true)
        vm.type(RecipientKind.TO, "Ana Pérez <ana@upm.es>")
        vm.onRecipientFocusChanged(RecipientKind.TO, false)
        assertEquals(listOf(EmailAddress("ana@upm.es", "Ana Pérez")), vm.uiState.value.to.map { it.address })
        vm.editRecipient(RecipientKind.TO, 0)
        assertTrue(vm.uiState.value.to.isEmpty())
        assertEquals("Ana Pérez <ana@upm.es>", vm.input(RecipientKind.TO).text)
        assertEquals(FocusTarget.TO, vm.uiState.value.focusRequest?.target)
    }

    // ------------------------------------------------------------------ account / signature

    @Test
    fun `switching account swaps an untouched signature`() = runTest {
        val vm = viewModel(ComposeArgs(accountId = 1))
        vm.setBody("Hola\n\n-- \nAna\nETSI")
        vm.selectAccount(2)
        assertEquals(2L, vm.uiState.value.accountId)
        assertEquals("Hola\n\n-- \nAna (alumna)", vm.body.text)
    }

    // ------------------------------------------------------------------ saving

    @Test
    fun `untouched new message is closed without a draft`() = runTest {
        val vm = viewModel()
        val events = vm.collectEvents(this)
        vm.close()
        settle()
        assertEquals(listOf<ComposeEvent>(ComposeEvent.Exit(ComposeExit.Closed)), events)
        assertTrue(compose.saved.isEmpty())
        assertTrue(compose.discarded.isEmpty())
        assertEquals(ComposeStatus.Finished, vm.uiState.value.status)
    }

    @Test
    fun `autosave after two seconds, then closing uploads the same draft`() = runTest {
        val vm = viewModel()
        val events = vm.collectEvents(this)
        vm.setBody("Hello\n\n-- \nAna\nETSI")
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(compose.saved.isEmpty())
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, compose.saved.size)
        assertFalse(compose.saved[0].second)
        val id = compose.saved[0].first.id
        assertEquals(id, vm.uiState.value.savedDraftId)

        vm.close()
        settle()
        assertEquals(id to true, compose.saved.last().let { it.first.id to it.second })
        val exit = (events.single() as ComposeEvent.Exit).exit as ComposeExit.DraftSaved
        assertEquals("Hello\n\n-- \nAna\nETSI", exit.draft.await().body)
    }

    @Test
    fun `clearing an autosaved new message discards its copy on close`() = runTest {
        val vm = viewModel()
        vm.setBody("x\n\n-- \nAna\nETSI")
        settle()
        val id = compose.saved.single().first.id
        vm.setBody("\n\n-- \nAna\nETSI")
        vm.close()
        settle()
        assertEquals(id, compose.discarded.single().id)
    }

    @Test
    fun `unchanged server draft is left alone, discard asks first`() = runTest {
        compose.serverDraft = Draft(
            accountId = 1,
            to = listOf(EmailAddress("luis@upm.es")),
            subject = "Plan",
            body = "text",
            mode = ComposeMode.EDIT_DRAFT,
            serverDraftMessageId = 9,
        )
        val vm = viewModel(ComposeArgs(mode = ComposeMode.EDIT_DRAFT, serverDraftMessageId = 9))
        assertEquals(ComposeTitle.EDIT_DRAFT, vm.uiState.value.title)
        vm.onStop()
        settle()
        assertTrue(compose.saved.isEmpty())

        vm.discard()
        assertEquals(ComposeDialog.CONFIRM_DISCARD, vm.uiState.value.dialog)
        vm.confirmDiscard()
        settle()
        assertEquals(9L, compose.discarded.single().serverDraftMessageId)
    }

    @Test
    fun `going to the background uploads changed content`() = runTest {
        val vm = viewModel()
        vm.onSubjectChange(TextFieldValue("Meeting"))
        vm.onStop()
        settle()
        assertEquals(listOf(true), compose.saved.map { it.second })
        val events = vm.collectEvents(this)
        vm.close()
        settle()
        // Already on the server: nothing more to do.
        assertEquals(1, compose.saved.size)
        assertEquals(ComposeEvent.Exit(ComposeExit.Closed), events.single())
    }

    @Test
    fun `restored screen reopens the draft it saved`() = runTest {
        compose.localDrafts[55] = Draft(id = 55, accountId = 1, subject = "Half written", body = "text")
        val vm = viewModel(ComposeArgs(to = "x@upm.es"), restoredDraftId = 55)
        assertTrue(compose.createCalls.isEmpty())
        assertEquals("Half written", vm.subject.text)
        assertTrue(vm.uiState.value.to.isEmpty())
        assertEquals(55L, vm.uiState.value.savedDraftId)
    }

    // ------------------------------------------------------------------ sending

    @Test
    fun `send validates recipients`() = runTest {
        val vm = viewModel()
        val events = vm.collectEvents(this)
        vm.send()
        assertEquals(ComposeEvent.NoRecipients, events.last())
        vm.type(RecipientKind.BCC, "nonsense,")
        vm.send()
        assertEquals(ComposeEvent.InvalidRecipients(listOf("nonsense")), events.last())
        assertTrue(vm.uiState.value.highlightInvalid)
        assertTrue(vm.uiState.value.ccBccExpanded)
        assertTrue(compose.sent.isEmpty())
    }

    @Test
    fun `send without subject asks, then queues with the undo delay`() = runTest {
        val vm = viewModel()
        val events = vm.collectEvents(this)
        vm.type(RecipientKind.TO, "luis@upm.es")
        vm.send()
        assertEquals(ComposeDialog.CONFIRM_NO_SUBJECT, vm.uiState.value.dialog)
        assertEquals(listOf("luis@upm.es"), vm.uiState.value.to.map { it.address.address })

        vm.confirmSendWithoutSubject()
        settle()
        val exit = (events.single() as ComposeEvent.Exit).exit as ComposeExit.Sending
        assertEquals(10, exit.undoSeconds)
        assertEquals(SendResult.Queued(7), exit.result.await())
        val (draft, delay) = compose.sent.single()
        assertEquals(10, delay)
        assertTrue(draft.id != 0L)
        assertEquals(listOf(EmailAddress("luis@upm.es")), draft.to)
        assertEquals(ComposeStatus.Finished, vm.uiState.value.status)

        // Finished screens ignore further input.
        vm.close()
        settle()
        assertEquals(1, events.size)
    }
}
