package io.github.pportilla.upmmail.ui.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.ContactSuggestion
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.OutgoingAttachment
import io.github.pportilla.upmmail.ui.navigation.ComposeArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why the compose screen could not open its draft. */
enum class LoadProblem {
    /** createDraft / draftFromServerMessage failed (e.g. offline reply needing the body). */
    ERROR,

    /** The local draft to reopen does not exist any more. */
    DRAFT_MISSING,

    /** There is no account to send from. */
    NO_ACCOUNT,
}

sealed interface ComposeStatus {
    data object Loading : ComposeStatus

    data object Ready : ComposeStatus

    data class Failed(val problem: LoadProblem, val error: Throwable? = null) : ComposeStatus

    /** Sent, saved or discarded: the screen is leaving and ignores further input. */
    data object Finished : ComposeStatus
}

/** The app bar title. */
enum class ComposeTitle { NEW, REPLY, REPLY_ALL, FORWARD, EDIT_DRAFT }

/** A chip selected with Backspace (the next Backspace removes it). */
data class ChipRef(val kind: RecipientKind, val index: Int)

/** Autocomplete results for what is typed in the [kind] field. */
data class SuggestionList(val kind: RecipientKind, val query: String, val items: List<ContactSuggestion>)

enum class ComposeDialog { CONFIRM_DISCARD, CONFIRM_NO_SUBJECT }

/** Ask the screen to move the focus to [target]; [id] makes repeated requests distinct. */
data class FocusRequest(val target: FocusTarget, val id: Int)

/** Everything the compose screen draws, except the text fields (see [ComposeViewModel.subject]). */
data class ComposeUiState(
    val status: ComposeStatus = ComposeStatus.Loading,
    val title: ComposeTitle = ComposeTitle.NEW,
    val accounts: List<Account> = emptyList(),
    val accountId: Long = -1,
    val to: List<Recipient> = emptyList(),
    val cc: List<Recipient> = emptyList(),
    val bcc: List<Recipient> = emptyList(),
    val ccBccExpanded: Boolean = false,
    val attachments: List<OutgoingAttachment> = emptyList(),
    /** Picked files still being copied into the app. */
    val importingAttachments: Int = 0,
    val suggestions: SuggestionList? = null,
    /** Recipient field that has the focus, if any. */
    val focusedField: RecipientKind? = null,
    val selectedChip: ChipRef? = null,
    /** Invalid chips are emphasised after a failed send attempt. */
    val highlightInvalid: Boolean = false,
    val dialog: ComposeDialog? = null,
    val focusRequest: FocusRequest? = null,
    /** Id of the local draft row once saved (0 before), kept by the screen across process death. */
    val savedDraftId: Long = 0,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == accountId }

    val isReady: Boolean get() = status == ComposeStatus.Ready

    fun recipients(kind: RecipientKind): List<Recipient> = when (kind) {
        RecipientKind.TO -> to
        RecipientKind.CC -> cc
        RecipientKind.BCC -> bcc
    }

    fun withRecipients(kind: RecipientKind, list: List<Recipient>): ComposeUiState = when (kind) {
        RecipientKind.TO -> copy(to = list)
        RecipientKind.CC -> copy(cc = list)
        RecipientKind.BCC -> copy(bcc = list)
    }

    /** Lower-case addresses of every chip, to skip duplicates and exclude them from suggestions. */
    val recipientKeys: Set<String> get() = (to + cc + bcc).mapTo(HashSet()) { it.key }

    val hasValidRecipient: Boolean get() = to.any { it.valid } || cc.any { it.valid } || bcc.any { it.valid }

    val attachmentsSize: Long get() = totalAttachmentSize(attachments)

    val attachmentsTooLarge: Boolean get() = attachmentsSize > LARGE_MESSAGE_BYTES

    /** Cc / Bcc can be folded away only while they are empty. */
    val canCollapseCcBcc: Boolean get() = cc.isEmpty() && bcc.isEmpty()
}

/** The outcome of queueing a message. */
sealed interface SendResult {
    data class Queued(val outboxId: Long) : SendResult

    /** Queueing failed; the message is kept as local draft [draftId] when that worked. */
    data class Failed(val error: Throwable, val draftId: Long?) : SendResult
}

/** How the screen was left, so it can report it after navigating back. */
sealed interface ComposeExit {
    data object Closed : ComposeExit

    /** Saved (and uploaded to the server Drafts folder in the background). */
    data class DraftSaved(val draft: Deferred<Draft>) : ComposeExit

    data object Discarded : ComposeExit

    /** Queued for sending after [undoSeconds]; the result arrives in [result]. */
    data class Sending(val result: Deferred<SendResult>, val undoSeconds: Int, val isDemo: Boolean = false) : ComposeExit
}

/** One-shot messages for the screen. */
sealed interface ComposeEvent {
    data object NoRecipients : ComposeEvent

    data class InvalidRecipients(val addresses: List<String>) : ComposeEvent

    data object AttachmentsPending : ComposeEvent

    data class AttachmentsFailed(val count: Int) : ComposeEvent

    data object NothingToSave : ComposeEvent

    data class Exit(val exit: ComposeExit) : ComposeEvent
}

/**
 * State and actions of the compose screen: loads or creates the draft once, keeps recipients as
 * chips with autocomplete, autosaves locally, and sends / saves / discards through work started
 * in the application scope so it completes after the screen is gone.
 *
 * @param restoredDraftId local draft saved by this screen before the process was killed; it is
 *   reopened instead of creating the draft again.
 * @param coroutineScope scope for the screen's own work (tests); defaults to the viewModelScope.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ComposeViewModel(
    private val container: AppContainer,
    private val args: ComposeArgs,
    private val restoredDraftId: Long = -1,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope
    private val compose = container.composeRepository

    private val state = MutableStateFlow(ComposeUiState(title = titleFor(args)))
    val uiState: StateFlow<ComposeUiState> = state.asStateFlow()

    private val eventChannel = Channel<ComposeEvent>(Channel.BUFFERED)
    val events: Flow<ComposeEvent> = eventChannel.receiveAsFlow()

    /** The subject field. Plain Compose state so typing never lags. */
    var subject: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** The body field (signature and quoted text included). */
    var body: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    private val inputs: Map<RecipientKind, MutableState<TextFieldValue>> =
        RecipientKind.entries.associateWith { mutableStateOf(TextFieldValue()) }

    /** Text typed in a recipient field and not yet turned into chips. */
    fun input(kind: RecipientKind): TextFieldValue = inputs.getValue(kind).value

    // Draft bookkeeping. Written on the main thread, except the @Volatile fields, which the save
    // jobs in the application scope update.
    private var baseDraft: Draft? = null
    private var origin = DraftOrigin.CREATED
    private var mode = ComposeMode.NEW
    private var generated: ComposeContent? = null
    private var initial: ComposeContent? = null
    private var generatedSignature: String? = null
    private var quoteAnchor: String? = null
    private val saveMutex = Mutex()

    @Volatile private var savedDraftId = 0L

    @Volatile private var lastSavedLocal: ComposeContent? = null

    @Volatile private var lastUploaded: ComposeContent? = null

    private var loadJob: Job? = null
    private var autosaveJob: Job? = null
    private var firstSaveStarted = false
    private var focusCounter = 0

    /** Files picked while the draft was still loading (a picker result after process death). */
    private val pendingPicked = mutableListOf<String>()

    private data class SuggestionRequest(val kind: RecipientKind, val query: String, val exclude: Set<String>, val generation: Int, val accountId: Long)

    private val suggestionRequests = MutableStateFlow<SuggestionRequest?>(null)
    private var suggestionGeneration = 0

    init {
        scope.launch {
            container.accountRepository.accounts
                .catch { emit(emptyList()) }
                .collect { list ->
                    state.update { it.copy(accounts = list) }
                    // An account was added while the screen waited for one.
                    val status = state.value.status
                    if (list.isNotEmpty() && status is ComposeStatus.Failed && status.problem == LoadProblem.NO_ACCOUNT) load()
                }
        }
        scope.launch {
            suggestionRequests
                .debounce { if (it == null) 0L else SUGGESTION_DEBOUNCE_MILLIS }
                .mapLatest { request -> request?.let { it to SuggestionList(it.kind, it.query, fetchSuggestions(it)) } }
                .collect { result ->
                    state.update { s ->
                        val current = result?.takeIf { (request, _) ->
                            request == suggestionRequests.value && request.accountId == s.accountId
                        }?.second?.takeIf { it.items.isNotEmpty() && s.focusedField == it.kind }
                        s.copy(suggestions = current)
                    }
                }
        }
        load()
    }

    // ------------------------------------------------------------------ loading

    /** Tries again after a failed load. */
    fun retry() {
        if (state.value.status is ComposeStatus.Failed) load()
    }

    private class OpenFailure(val problem: LoadProblem, cause: Throwable? = null) : Exception(cause)

    private class Opened(val draft: Draft, val origin: DraftOrigin, val applyPrefills: Boolean)

    private fun load() {
        loadJob?.cancel()
        state.update { it.copy(status = ComposeStatus.Loading) }
        loadJob = scope.launch {
            try {
                val accounts = container.accountRepository.accounts.first()
                if (accounts.isEmpty()) throw OpenFailure(LoadProblem.NO_ACCOUNT)
                val fallback = defaultAccount(accounts)
                val opened = open(fallback)
                show(opened, accounts, fallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenFailure) {
                state.update { it.copy(status = ComposeStatus.Failed(e.problem, e.cause)) }
            } catch (e: Exception) {
                state.update { it.copy(status = ComposeStatus.Failed(LoadProblem.ERROR, e)) }
            }
        }
    }

    /** The account asked for, else the last used one, else the first. */
    private fun defaultAccount(accounts: List<Account>): Account {
        val lastUsed = container.settingsRepository.settings.value.lastAccountId
        return accounts.firstOrNull { args.accountId >= 0 && it.id == args.accountId }
            ?: accounts.firstOrNull { it.id == lastUsed }
            ?: accounts.first()
    }

    private suspend fun open(fallback: Account): Opened {
        if (restoredDraftId > 0) {
            // Back after the process was killed: continue with what this screen saved.
            compose.loadDraft(restoredDraftId)?.let { return Opened(it, DraftOrigin.LOCAL_DRAFT, applyPrefills = false) }
        }
        return when {
            args.draftId >= 0 -> {
                val draft = compose.loadDraft(args.draftId) ?: throw OpenFailure(LoadProblem.DRAFT_MISSING)
                Opened(draft, DraftOrigin.LOCAL_DRAFT, applyPrefills = true)
            }
            args.serverDraftMessageId >= 0 -> {
                val draft = compose.draftFromServerMessage(args.serverDraftMessageId).getOrElse { throw OpenFailure(LoadProblem.ERROR, it) }
                Opened(draft, DraftOrigin.SERVER_DRAFT, applyPrefills = true)
            }
            args.sourceMessageId >= 0 && args.mode != ComposeMode.NEW -> {
                val draft = compose.createDraft(fallback.id, args.mode, args.sourceMessageId)
                    .getOrElse { throw OpenFailure(LoadProblem.ERROR, it) }
                val from = if (args.mode == ComposeMode.EDIT_DRAFT) DraftOrigin.SERVER_DRAFT else DraftOrigin.CREATED
                Opened(draft, from, applyPrefills = true)
            }
            else -> {
                val draft = compose.createDraft(fallback.id, ComposeMode.NEW, null).getOrElse { throw OpenFailure(LoadProblem.ERROR, it) }
                Opened(draft, DraftOrigin.CREATED, applyPrefills = true)
            }
        }
    }

    private fun show(opened: Opened, accounts: List<Account>, fallback: Account) {
        var draft = opened.draft
        if (accounts.none { it.id == draft.accountId }) draft = draft.copy(accountId = fallback.id)
        val account = accounts.first { it.id == draft.accountId }

        baseDraft = draft
        origin = opened.origin
        mode = draft.mode
        generatedSignature = account.signature
        quoteAnchor = if (origin == DraftOrigin.CREATED) Signatures.quotedPart(draft.body, account.signature) else null
        val generatedContent = contentOf(draft)
        generated = generatedContent

        val taken = HashSet<String>()
        fun recipients(base: List<EmailAddress>, prefill: String?): List<Recipient> {
            val own = base.map(Recipient::of)
            own.mapTo(taken) { it.key }
            if (!opened.applyPrefills || prefill.isNullOrBlank()) return own
            val merged = mergeRecipients(own, RecipientTokenizer.commitAll(prefill), taken)
            merged.mapTo(taken) { it.key }
            return merged
        }
        val to = recipients(draft.to, args.to)
        val cc = recipients(draft.cc, args.cc)
        val bcc = recipients(draft.bcc, args.bcc)
        var subjectText = draft.subject
        var bodyText = draft.body
        if (opened.applyPrefills) {
            args.subject?.let(::singleLine)?.takeIf { it.isNotBlank() }?.let { subjectText = it }
            args.body?.replace("\r\n", "\n")?.replace('\r', '\n')?.takeIf { it.isNotBlank() }?.let { bodyText = prependBody(it, bodyText) }
        }

        savedDraftId = if (origin == DraftOrigin.LOCAL_DRAFT) draft.id else 0L
        lastSavedLocal = if (origin == DraftOrigin.LOCAL_DRAFT) generatedContent else null
        lastUploaded = if (origin == DraftOrigin.SERVER_DRAFT) generatedContent else null

        subject = TextFieldValue(subjectText, TextRange(subjectText.length))
        body = TextFieldValue(bodyText, TextRange(initialBodyCursor(bodyText, quoteAnchor)))
        RecipientKind.entries.forEach { inputs.getValue(it).value = TextFieldValue() }

        val content = ComposeContent(
            accountId = draft.accountId,
            to = to.map { it.address },
            cc = cc.map { it.address },
            bcc = bcc.map { it.address },
            subject = subjectText,
            body = bodyText,
            attachments = draft.attachments,
        )
        initial = content
        val focus = initialFocus(mode, origin, content)
        state.update {
            it.copy(
                status = ComposeStatus.Ready,
                accounts = accounts,
                accountId = draft.accountId,
                to = to,
                cc = cc,
                bcc = bcc,
                ccBccExpanded = cc.isNotEmpty() || bcc.isNotEmpty(),
                attachments = draft.attachments,
                importingAttachments = 0,
                focusRequest = FocusRequest(focus, ++focusCounter),
                savedDraftId = savedDraftId,
            )
        }
        if (opened.applyPrefills) importAttachments(args.attachmentUris, fromPrefill = true)
        if (pendingPicked.isNotEmpty()) {
            val picked = pendingPicked.toList()
            pendingPicked.clear()
            importAttachments(picked, fromPrefill = false)
        }
    }

    // ------------------------------------------------------------------ recipients

    /** The user edited a recipient input: separators and pasted lists become chips. */
    fun onInputChange(kind: RecipientKind, value: TextFieldValue) {
        if (!state.value.isReady) return
        val holder = inputs.getValue(kind)
        val old = holder.value
        if (value.text == old.text) {
            holder.value = value
            return
        }
        val update = RecipientTokenizer.onTextChanged(old.text, value.text)
        holder.value = if (update.remaining == value.text) value else TextFieldValue(update.remaining, TextRange(update.remaining.length))
        if (state.value.selectedChip != null) state.update { it.copy(selectedChip = null) }
        if (update.committed.isNotEmpty()) addRecipients(kind, update.committed)
        requestSuggestions(kind)
    }

    /** A suggestion was picked: add it and clear the input. */
    fun selectSuggestion(kind: RecipientKind, suggestion: ContactSuggestion) {
        if (!state.value.isReady) return
        inputs.getValue(kind).value = TextFieldValue()
        addRecipients(kind, listOf(Recipient.of(suggestion.address)))
        clearSuggestions()
    }

    fun removeRecipient(kind: RecipientKind, index: Int) {
        if (!state.value.isReady) return
        val list = state.value.recipients(kind)
        if (index !in list.indices) return
        state.update { s -> s.withRecipients(kind, list.filterIndexed { i, _ -> i != index }).copy(selectedChip = null) }
        onContentChanged()
        requestSuggestions(kind)
    }

    /** Turns a chip back into editable text in its field. */
    fun editRecipient(kind: RecipientKind, index: Int) {
        if (!state.value.isReady) return
        val chip = state.value.recipients(kind).getOrNull(index) ?: return
        commitInput(kind)
        val list = state.value.recipients(kind)
        val position = list.indexOf(chip).takeIf { it >= 0 } ?: return
        val text = chip.toEditableText()
        state.update { s ->
            s.withRecipients(kind, list.filterIndexed { i, _ -> i != position })
                .copy(selectedChip = null, focusRequest = FocusRequest(kind.focusTarget, ++focusCounter))
        }
        inputs.getValue(kind).value = TextFieldValue(text, TextRange(text.length))
        onContentChanged()
    }

    /**
     * Backspace in an empty input: the first press selects the last chip, the second removes it.
     * Returns false when there is no chip (the key event is not consumed).
     */
    fun onBackspaceOnEmpty(kind: RecipientKind): Boolean {
        if (!state.value.isReady) return false
        val list = state.value.recipients(kind)
        if (list.isEmpty()) return false
        val last = ChipRef(kind, list.lastIndex)
        if (state.value.selectedChip == last) removeRecipient(kind, list.lastIndex) else state.update { it.copy(selectedChip = last) }
        return true
    }

    /**
     * IME Next / Done or Enter in a recipient input. Picks the first suggestion when the text is
     * a search rather than an address, else commits the text. Returns false when the input was
     * empty, so the caller moves the focus on.
     */
    fun onRecipientImeAction(kind: RecipientKind): Boolean {
        if (!state.value.isReady) return false
        val text = input(kind).text
        if (text.isBlank()) {
            if (text.isNotEmpty()) inputs.getValue(kind).value = TextFieldValue()
            return false
        }
        val shown = state.value.suggestions
        if (shown != null && shown.kind == kind && shown.items.isNotEmpty() && !RecipientTokenizer.isCompleteAddressList(text)) {
            selectSuggestion(kind, shown.items.first())
        } else {
            commitInput(kind)
        }
        return true
    }

    /** Focus moved into or out of a recipient field; leaving it commits the pending text. */
    fun onRecipientFocusChanged(kind: RecipientKind, focused: Boolean) {
        if (focused) {
            state.update { it.copy(focusedField = kind) }
            requestSuggestions(kind)
            return
        }
        if (state.value.focusedField == kind) {
            state.update { s ->
                s.copy(
                    focusedField = null,
                    suggestions = null,
                    selectedChip = s.selectedChip?.takeIf { it.kind != kind },
                )
            }
            suggestionRequests.value = null
        }
        if (state.value.isReady) commitInput(kind)
    }

    /** The READ_CONTACTS answer arrived; device contacts may now be suggested. */
    fun onContactsPermissionResult(granted: Boolean) {
        if (!granted) return
        suggestionGeneration++
        state.value.focusedField?.let(::requestSuggestions)
    }

    fun toggleCcBcc() {
        if (!state.value.isReady) return
        state.update { s ->
            if (s.ccBccExpanded && s.canCollapseCcBcc && input(RecipientKind.CC).text.isBlank() && input(RecipientKind.BCC).text.isBlank()) {
                s.copy(ccBccExpanded = false)
            } else {
                s.copy(ccBccExpanded = true)
            }
        }
    }

    /** "Add Cc/Bcc" from the menu: shows the fields and focuses Cc. */
    fun showCcBcc() {
        if (!state.value.isReady) return
        state.update { it.copy(ccBccExpanded = true, focusRequest = FocusRequest(FocusTarget.CC, ++focusCounter)) }
    }

    private fun addRecipients(kind: RecipientKind, incoming: List<Recipient>) {
        if (incoming.isEmpty()) return
        state.update { s ->
            val own = s.recipients(kind)
            val others = s.recipientKeys - own.mapTo(HashSet()) { it.key }
            s.withRecipients(kind, mergeRecipients(own, incoming, others)).copy(highlightInvalid = false)
        }
        onContentChanged()
    }

    private fun commitInput(kind: RecipientKind) {
        val holder = inputs.getValue(kind)
        val text = holder.value.text
        if (text.isEmpty()) return
        holder.value = TextFieldValue()
        addRecipients(kind, RecipientTokenizer.commitAll(text))
        if (state.value.suggestions?.kind == kind) clearSuggestions()
    }

    private fun commitAllInputs() = RecipientKind.entries.forEach(::commitInput)

    private fun requestSuggestions(kind: RecipientKind) {
        val s = state.value
        val query = input(kind).text.trim()
        if (query.isEmpty() || s.focusedField != kind) {
            if (s.suggestions?.kind == kind || s.focusedField == kind) clearSuggestions()
            return
        }
        suggestionRequests.value = SuggestionRequest(kind, query, s.recipientKeys, suggestionGeneration, s.accountId)
    }

    private fun clearSuggestions() {
        suggestionRequests.value = null
        if (state.value.suggestions != null) state.update { it.copy(suggestions = null) }
    }

    private suspend fun fetchSuggestions(request: SuggestionRequest): List<ContactSuggestion> = try {
        if (request.accountId > 0) {
            container.contactRepository.suggestForAccount(request.accountId, request.query, request.exclude, MAX_SUGGESTIONS)
        } else emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ other fields

    fun onSubjectChange(value: TextFieldValue) {
        if (!state.value.isReady) return
        val clean = if (value.text.any { it == '\n' || it == '\r' }) value.copy(text = singleLine(value.text)) else value
        val changed = clean.text != subject.text
        subject = clean
        if (changed) onContentChanged()
    }

    fun onBodyChange(value: TextFieldValue) {
        if (!state.value.isReady) return
        val changed = value.text != body.text
        body = value
        if (changed) onContentChanged()
    }

    /** Sends from another account; its signature replaces the old one if that is still untouched. */
    fun selectAccount(accountId: Long) {
        val s = state.value
        if (!s.isReady || accountId == s.accountId) return
        val newAccount = s.accounts.firstOrNull { it.id == accountId } ?: return
        val oldSignature = s.account?.signature
        val text = body.text
        val swapped = Signatures.swap(text, oldSignature, newAccount.signature, quoteAnchor)
        if (swapped != text) {
            val shift = swapped.length - text.length
            val selection = body.selection
            // Keep the cursor where it was when it sits before the signature (the usual case).
            val signatureStart = Signatures.block(oldSignature)?.let { Signatures.indexOfBlock(text, it) } ?: text.length
            val newSelection = if (selection.max <= signatureStart) {
                selection
            } else {
                TextRange((selection.start + shift).coerceIn(0, swapped.length), (selection.end + shift).coerceIn(0, swapped.length))
            }
            body = TextFieldValue(swapped, newSelection)
        }
        state.update { it.copy(accountId = accountId) }
        clearSuggestions()
        state.value.focusedField?.let(::requestSuggestions)
        onContentChanged()
    }

    // ------------------------------------------------------------------ attachments

    /**
     * Files picked by the user: each one is copied into the app (a spinner chip shows meanwhile).
     * Files picked before the draft is ready are added once it is.
     */
    fun addAttachments(uris: List<String>) {
        when (state.value.status) {
            ComposeStatus.Ready -> importAttachments(uris, fromPrefill = false)
            ComposeStatus.Loading, is ComposeStatus.Failed -> pendingPicked += uris
            ComposeStatus.Finished -> Unit
        }
    }

    fun removeAttachment(attachment: OutgoingAttachment) {
        if (!state.value.isReady) return
        state.update { s -> s.copy(attachments = s.attachments.filterNot { it.uri == attachment.uri }) }
        onContentChanged()
    }

    private fun importAttachments(uris: List<String>, fromPrefill: Boolean) {
        val list = uris.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) return
        state.update { it.copy(importingAttachments = it.importingAttachments + list.size) }
        scope.launch {
            var failed = 0
            for (uri in list) {
                val attachment = try {
                    compose.describeAttachment(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                state.update { s ->
                    val added = attachment != null && s.attachments.none { it.uri == attachment.uri }
                    s.copy(
                        importingAttachments = (s.importingAttachments - 1).coerceAtLeast(0),
                        attachments = if (added) s.attachments + attachment!! else s.attachments,
                    )
                }
                if (attachment == null) {
                    failed++
                } else {
                    // Shared files are part of the message as it was opened, not an edit.
                    if (fromPrefill) initial = initial?.let { it.copy(attachments = it.attachments + attachment) }
                    onContentChanged()
                }
            }
            if (failed > 0) eventChannel.send(ComposeEvent.AttachmentsFailed(failed))
        }
    }

    // ------------------------------------------------------------------ send / save / discard

    /** Validates and sends; asks first when the subject is empty. */
    fun send() = send(confirmedNoSubject = false)

    fun confirmSendWithoutSubject() {
        if (state.value.dialog != ComposeDialog.CONFIRM_NO_SUBJECT) return
        state.update { it.copy(dialog = null) }
        send(confirmedNoSubject = true)
    }

    private fun send(confirmedNoSubject: Boolean) {
        if (!state.value.isReady) return
        commitAllInputs()
        val s = state.value
        val all = s.to + s.cc + s.bcc
        val invalid = all.filter { !it.valid }
        when {
            all.isEmpty() -> {
                state.update { it.copy(focusRequest = FocusRequest(FocusTarget.TO, ++focusCounter)) }
                eventChannel.trySend(ComposeEvent.NoRecipients)
            }
            invalid.isNotEmpty() -> {
                state.update { it.copy(highlightInvalid = true, ccBccExpanded = it.ccBccExpanded || !it.canCollapseCcBcc) }
                eventChannel.trySend(ComposeEvent.InvalidRecipients(invalid.map { it.address.address }))
            }
            s.importingAttachments > 0 -> eventChannel.trySend(ComposeEvent.AttachmentsPending)
            !confirmedNoSubject && subject.text.isBlank() -> state.update { it.copy(dialog = ComposeDialog.CONFIRM_NO_SUBJECT) }
            else -> queueForSending()
        }
    }

    private fun queueForSending() {
        autosaveJob?.cancel()
        val content = currentContent()
        val isDemo = state.value.account?.isDemo == true
        val delaySeconds = container.settingsRepository.settings.value.undoSendSeconds.coerceAtLeast(0)
        val result = container.applicationScope.async {
            saveMutex.withLock {
                var draft = draftFor(content).copy(id = savedDraftId)
                try {
                    // Saved first, so the message survives as a draft if queueing fails.
                    val id = compose.saveDraft(draft, upload = false)
                    savedDraftId = id
                    draft = draft.copy(id = id)
                    SendResult.Queued(compose.send(draft, delaySeconds))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SendResult.Failed(e, draft.id.takeIf { it != 0L })
                }
            }
        }
        finish(ComposeExit.Sending(result, delaySeconds, isDemo))
    }

    /** Close button / Back: keeps what the user wrote as a draft, drops untouched messages. */
    fun close() {
        when (state.value.status) {
            ComposeStatus.Finished -> return
            ComposeStatus.Ready -> Unit
            else -> {
                loadJob?.cancel()
                finish(ComposeExit.Closed)
                return
            }
        }
        commitAllInputs()
        autosaveJob?.cancel()
        val content = currentContent()
        when (decideClose(content)) {
            CloseAction.NOTHING -> finish(ComposeExit.Closed)
            CloseAction.DISCARD -> {
                discardAsync(content)
                finish(ComposeExit.Closed)
            }
            CloseAction.SAVE -> finish(ComposeExit.DraftSaved(persistAsync(content, upload = true)))
        }
    }

    /** "Save draft" from the menu: saves (and uploads) and leaves. */
    fun saveDraftAndClose() {
        if (!state.value.isReady) return
        commitAllInputs()
        val content = currentContent()
        if (isEmpty(content)) {
            eventChannel.trySend(ComposeEvent.NothingToSave)
            return
        }
        autosaveJob?.cancel()
        val saved = if (content == lastUploaded) {
            CompletableDeferred(draftFor(content).copy(id = savedDraftId))
        } else {
            persistAsync(content, upload = true)
        }
        finish(ComposeExit.DraftSaved(saved))
    }

    /** "Discard" from the menu: asks first unless there is nothing to lose. */
    fun discard() {
        when (state.value.status) {
            ComposeStatus.Ready -> Unit
            ComposeStatus.Finished -> return
            else -> {
                close()
                return
            }
        }
        val content = currentContent()
        if (origin == DraftOrigin.CREATED && decideClose(content) != CloseAction.SAVE) {
            autosaveJob?.cancel()
            discardAsync(content)
            finish(ComposeExit.Closed)
        } else {
            state.update { it.copy(dialog = ComposeDialog.CONFIRM_DISCARD) }
        }
    }

    fun confirmDiscard() {
        if (state.value.dialog != ComposeDialog.CONFIRM_DISCARD || !state.value.isReady) return
        autosaveJob?.cancel()
        discardAsync(currentContent())
        finish(ComposeExit.Discarded)
    }

    fun dismissDialog() {
        state.update { it.copy(dialog = null) }
    }

    /** The focus request was carried out (or is obsolete). */
    fun focusRequestHandled(id: Int) {
        if (state.value.focusRequest?.id == id) state.update { it.copy(focusRequest = null) }
    }

    /**
     * Id of the local draft row saved by this screen, 0 before the first save. Read by the screen
     * when it stops, as its saved state is written before the UI state could deliver a new id.
     */
    val localDraftId: Long get() = savedDraftId

    /**
     * The app goes to the background: store the draft on the server too, so it is not lost if
     * the process is killed and the user never comes back to this screen.
     */
    fun onStop() {
        if (!state.value.isReady) return
        val content = currentContent()
        if (decideClose(content) != CloseAction.SAVE) return
        autosaveJob?.cancel()
        persistAsync(content, upload = true)
    }

    // ------------------------------------------------------------------ persistence

    private fun onContentChanged() {
        if (!state.value.isReady) return
        if (state.value.highlightInvalid && state.value.let { s -> (s.to + s.cc + s.bcc).all { it.valid } }) {
            state.update { it.copy(highlightInvalid = false) }
        }
        autosaveJob?.cancel()
        // The first local copy is made at once: the screen keeps its id in its saved state, so
        // that a screen restored after the process was killed reopens it. That id must be known
        // before the user can leave the app, which may be well within the autosave delay.
        val firstSave = savedDraftId == 0L && !firstSaveStarted
        autosaveJob = scope.launch {
            if (!firstSave) delay(AUTOSAVE_DELAY_MILLIS)
            val content = currentContent()
            if (content == lastSavedLocal || decideClose(content) != CloseAction.SAVE) return@launch
            if (firstSave) firstSaveStarted = true
            try {
                persistAsync(content, upload = false).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort; the next change or closing the screen saves again.
            }
        }
    }

    /** Saves in the application scope, one save at a time, so it completes after the screen is gone. */
    private fun persistAsync(content: ComposeContent, upload: Boolean): Deferred<Draft> =
        container.applicationScope.async {
            saveMutex.withLock {
                val draft = draftFor(content).copy(id = savedDraftId)
                val id = compose.saveDraft(draft, upload)
                savedDraftId = id
                lastSavedLocal = content
                if (upload) lastUploaded = content
                state.update { it.copy(savedDraftId = id) }
                draft.copy(id = id)
            }
        }

    private fun discardAsync(content: ComposeContent): Deferred<Unit> =
        container.applicationScope.async {
            saveMutex.withLock {
                compose.discardDraft(draftFor(content).copy(id = savedDraftId))
                savedDraftId = 0L
                lastSavedLocal = null
                lastUploaded = null
            }
        }

    private fun finish(exit: ComposeExit) {
        state.update { it.copy(status = ComposeStatus.Finished, dialog = null, suggestions = null) }
        eventChannel.trySend(ComposeEvent.Exit(exit))
    }

    private fun decideClose(content: ComposeContent): CloseAction {
        val start = initial ?: return CloseAction.NOTHING
        return closeAction(
            content = content,
            initial = start,
            generated = generated ?: start,
            lastUploaded = lastUploaded,
            origin = origin,
            hasLocalCopy = savedDraftId != 0L,
            empty = isEmpty(content),
        )
    }

    private fun isEmpty(content: ComposeContent): Boolean {
        val signature = state.value.accounts.firstOrNull { it.id == content.accountId }?.signature
        return isEffectivelyEmpty(content, generated?.body.orEmpty(), signature, generatedSignature)
    }

    private fun currentContent(): ComposeContent {
        val s = state.value
        return ComposeContent(
            accountId = s.accountId,
            to = s.to.map { it.address },
            cc = s.cc.map { it.address },
            bcc = s.bcc.map { it.address },
            subject = subject.text,
            body = body.text,
            attachments = s.attachments,
        )
    }

    private fun draftFor(content: ComposeContent): Draft {
        val base = baseDraft ?: Draft(accountId = content.accountId)
        return base.copy(
            accountId = content.accountId,
            to = content.to,
            cc = content.cc,
            bcc = content.bcc,
            subject = content.subject,
            body = content.body,
            attachments = content.attachments,
        )
    }

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 2_000L
        const val SUGGESTION_DEBOUNCE_MILLIS = 150L
        const val MAX_SUGGESTIONS = 6

        fun titleFor(args: ComposeArgs): ComposeTitle = when {
            args.draftId >= 0 || args.serverDraftMessageId >= 0 || args.mode == ComposeMode.EDIT_DRAFT -> ComposeTitle.EDIT_DRAFT
            args.sourceMessageId < 0 -> ComposeTitle.NEW
            args.mode == ComposeMode.REPLY -> ComposeTitle.REPLY
            args.mode == ComposeMode.REPLY_ALL -> ComposeTitle.REPLY_ALL
            args.mode == ComposeMode.FORWARD -> ComposeTitle.FORWARD
            else -> ComposeTitle.NEW
        }

        /** The draft as the screen holds it (addresses normalised like chips, so equality holds). */
        fun contentOf(draft: Draft) = ComposeContent(
            accountId = draft.accountId,
            to = draft.to.map { Recipient.of(it).address },
            cc = draft.cc.map { Recipient.of(it).address },
            bcc = draft.bcc.map { Recipient.of(it).address },
            subject = draft.subject,
            body = draft.body,
            attachments = draft.attachments,
        )

        fun singleLine(text: String): String = text.replace(Regex("[\\r\\n]+"), " ")
    }
}

/** The focus target of a recipient field. */
val RecipientKind.focusTarget: FocusTarget
    get() = when (this) {
        RecipientKind.TO -> FocusTarget.TO
        RecipientKind.CC -> FocusTarget.CC
        RecipientKind.BCC -> FocusTarget.BCC
    }
