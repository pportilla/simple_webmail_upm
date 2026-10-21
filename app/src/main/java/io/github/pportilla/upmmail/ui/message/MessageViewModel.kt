package io.github.pportilla.upmmail.ui.message

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.repository.UndoHandle
import io.github.pportilla.upmmail.mail.HtmlSanitizer
import io.github.pportilla.upmmail.mail.HtmlText
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.ui.components.formatFullDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Base64
import java.util.Locale

/** One-off things the screen must do (navigate, start an activity, show a snackbar). */
sealed interface MessageEvent {
    /** Leave the screen: the conversation disappeared or the user marked it unread. */
    data object Close : MessageEvent

    data class OpenAttachment(val file: File, val attachment: Attachment) : MessageEvent

    data class ShareAttachment(val file: File, val attachment: Attachment) : MessageEvent

    data class AttachmentSaved(val fileName: String) : MessageEvent

    data object AttachmentSaveFailed : MessageEvent

    data class Failure(val error: Throwable) : MessageEvent
}

/** Labels of the printed header, resolved by the screen in the user's language. */
data class PrintLabels(
    val from: String,
    val to: String,
    val cc: String,
    val date: String,
    val noSubject: String,
)

/** Everything needed to print a message. */
data class PrintRequest(
    val html: String,
    val blockNetworkLoads: Boolean,
    val jobName: String,
)

/**
 * State holder of the conversation screen. It observes the cached thread of [messageId], marks
 * it read, downloads bodies of expanded messages, prepares their HTML (sanitised, inline images
 * embedded) and runs the actions of the screen.
 *
 * Work that must survive leaving the screen (moves, flag changes, saving a file) runs in the
 * application scope; results come back through callbacks or [events].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageViewModel(
    private val container: AppContainer,
    private val messageId: Long,
) : ViewModel() {

    private val mail = container.mailRepository
    private val settingsRepository = container.settingsRepository
    private val appScope = container.applicationScope

    private val thread = MutableStateFlow<ThreadLoad>(ThreadLoad.Loading)
    private val local = MutableStateFlow(LocalState())

    private val eventChannel = Channel<MessageEvent>(Channel.BUFFERED)

    /** Collected once by the screen. */
    val events: Flow<MessageEvent> = eventChannel.receiveAsFlow()

    // Bookkeeping below is only touched on the main thread.
    private val ensureJobs = HashMap<Long, Job>()
    private val renderJobs = HashMap<Long, Job>()
    private val renderKeys = HashMap<Long, RenderKey>()
    private val seenIds = HashSet<Long>()
    private val markedRead = HashSet<Long>()
    private val pendingRead = LinkedHashSet<Long>()
    private var markReadJob: Job? = null
    private var visible = false
    private var closed = false

    private val accountId: StateFlow<Long?> = thread
        .map { (it as? ThreadLoad.Loaded)?.messages?.firstOrNull()?.summary?.accountId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val folders: Flow<List<Folder>> = accountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else mail.observeFolders(id).catch { emit(emptyList()) }
    }

    private val selfAddresses: StateFlow<Set<String>> = accountId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptySet())
        } else {
            container.accountRepository.accounts
                .map { accounts ->
                    accounts.firstOrNull { it.id == id }?.email?.trim()?.lowercase(Locale.ROOT)
                        ?.let { setOf(it) }
                        .orEmpty()
                }
                .catch { emit(emptySet()) }
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val uiState: StateFlow<MessageUiState> = combine(
        thread,
        local,
        settingsRepository.settings,
        folders,
        selfAddresses,
    ) { load, localState, settings, folderList, self ->
        buildMessageUiState(messageId, load, localState, settings, folderList, self)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageUiState())

    init {
        viewModelScope.launch {
            mail.observeThread(messageId)
                .distinctUntilChanged()
                .catch { error ->
                    if (error is CancellationException) throw error
                    emit(emptyList())
                }
                .collect(::onThread)
        }
    }

    // ------------------------------------------------------------------ screen lifecycle

    /** Messages arriving while the screen is visible are marked read; the rest wait for it. */
    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible && pendingRead.isNotEmpty()) {
            markRead(pendingRead.toList())
            pendingRead.clear()
        }
    }

    private fun onThread(messages: List<MessageDetail>) {
        if (closed) return
        val previous = thread.value
        if (messages.isEmpty()) {
            if (previous is ThreadLoad.Loaded) close() else thread.value = ThreadLoad.NotFound
            return
        }
        val first = previous !is ThreadLoad.Loaded
        val arrived = messages.filter { seenIds.add(it.summary.id) }
        val unreadArrived = arrived.filter { !it.summary.isRead && !it.isDraftMessage() }.map { it.summary.id }
        local.update { state ->
            if (first) {
                state.copy(
                    expanded = initialExpandedIds(messages.map { it.summary }, messageId),
                    wasUnread = unreadArrived.toSet(),
                )
            } else {
                state.copy(expanded = state.expanded + unreadArrived, wasUnread = state.wasUnread + unreadArrived)
            }
        }
        thread.value = ThreadLoad.Loaded(messages)
        when {
            first -> markRead(unreadArrived)
            visible -> markRead(unreadArrived)
            else -> pendingRead += unreadArrived
        }
        if (messages.none { it.summary.id !in local.value.removed }) {
            close()
            return
        }
        startBodyWork()
    }

    private fun close() {
        if (closed) return
        closed = true
        sendEvent(MessageEvent.Close)
    }

    /** Each message is marked read at most once, so "mark unread" is never fought. */
    private fun markRead(ids: List<Long>) {
        val fresh = ids.filter { markedRead.add(it) }
        if (fresh.isEmpty()) return
        val previous = markReadJob
        markReadJob = launchInApp {
            previous?.join()
            mail.setRead(fresh, true)
        }
    }

    private fun loadedMessages(): List<MessageDetail> {
        val removed = local.value.removed
        return (thread.value as? ThreadLoad.Loaded)?.messages?.filter { it.summary.id !in removed }.orEmpty()
    }

    /** Messages of the conversation in the opened message's folder: what conversation actions move. */
    private fun conversationIds(): List<Long> {
        val messages = loadedMessages()
        val opened = messages.firstOrNull { it.summary.id == messageId } ?: messages.lastOrNull() ?: return emptyList()
        return messages.filter { it.summary.folderId == opened.summary.folderId }.map { it.summary.id }
    }

    // ------------------------------------------------------------------ expansion

    fun toggleExpanded(id: Long) {
        local.update { state ->
            state.copy(expanded = if (id in state.expanded) state.expanded - id else state.expanded + id)
        }
        startBodyWork()
    }

    fun toggleDetails(id: Long) {
        local.update { state ->
            state.copy(detailsShown = if (id in state.detailsShown) state.detailsShown - id else state.detailsShown + id)
        }
    }

    /** Shows the messages folded into a "N more messages" row. */
    fun revealAll() {
        local.update { it.copy(revealAll = true) }
    }

    // ------------------------------------------------------------------ bodies

    private data class RenderKey(val html: String?, val text: String?)

    private fun startBodyWork() {
        val state = local.value
        val messages = loadedMessages()
        for (detail in messages) {
            val id = detail.summary.id
            if (messages.size > 1 && id !in state.expanded) continue
            ensureBody(detail)
            if (detail.bodyLoaded) render(detail)
        }
    }

    /** Downloads the body (and inline images) once per message; failures wait for [retryBody]. */
    private fun ensureBody(detail: MessageDetail) {
        val id = detail.summary.id
        if (ensureJobs.containsKey(id) || id in local.value.bodyErrors) return
        val needsBody = !detail.bodyLoaded
        ensureJobs[id] = viewModelScope.launch {
            val result = try {
                mail.ensureBodyLoaded(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val error = result.exceptionOrNull()
            if (error != null && needsBody) {
                ensureJobs.remove(id)
                local.update { it.copy(bodyErrors = it.bodyErrors + (id to error)) }
            }
        }
    }

    fun retryBody(id: Long) {
        local.update { it.copy(bodyErrors = it.bodyErrors - id) }
        ensureJobs.remove(id)?.cancel()
        startBodyWork()
    }

    private fun render(detail: MessageDetail) {
        val id = detail.summary.id
        val key = RenderKey(detail.bodyHtml, detail.bodyText)
        if (renderKeys[id] == key) return
        renderKeys[id] = key
        renderJobs.remove(id)?.cancel()
        renderJobs[id] = viewModelScope.launch {
            val body = renderBody(detail) { interim ->
                if (local.value.rendered[id] == null) publish(id, interim)
            }
            publish(id, body)
        }
    }

    private fun publish(id: Long, body: RenderedBody) {
        local.update { it.copy(rendered = it.rendered + (id to body)) }
    }

    /**
     * Sanitises the body and embeds the inline images it references. When images take a while,
     * [onInterim] receives the body without them first.
     */
    private suspend fun renderBody(detail: MessageDetail, onInterim: ((RenderedBody) -> Unit)?): RenderedBody {
        val html = detail.bodyHtml
        try {
            if (html.isNullOrBlank()) return plainBody(detail.bodyText.orEmpty())
            val prepared = withContext(Dispatchers.Default) {
                val sanitized = fitWideContent(HtmlSanitizer.sanitize(html))
                Triple(sanitized, HtmlSanitizer.hasRemoteContent(sanitized), referencedContentIds(html))
            }
            val (sanitized, remote, referenced) = prepared
            val candidates = detail.attachments.filter { attachment ->
                attachment.contentId?.let { normalizeContentId(it) in referenced } == true
            }
            if (candidates.isEmpty()) return RenderedBody(sanitized, isHtml = true, hasRemoteContent = remote, hiddenAttachmentIds = emptySet())
            return coroutineScope {
                val interim = onInterim?.let { callback ->
                    launch {
                        delay(INTERIM_DELAY_MS)
                        callback(
                            RenderedBody(
                                sanitized,
                                isHtml = true,
                                hasRemoteContent = remote,
                                hiddenAttachmentIds = candidates.mapTo(HashSet()) { it.id },
                                complete = false,
                            ),
                        )
                    }
                }
                // ensureBodyLoaded fetches inline images itself; let it finish to avoid downloading twice.
                ensureJobs[detail.summary.id]?.let { job -> withTimeoutOrNull(ENSURE_WAIT_MS) { job.join() } }
                val images = loadInlineImages(candidates)
                interim?.cancel()
                val finalHtml = if (images.isEmpty()) {
                    sanitized
                } else {
                    withContext(Dispatchers.Default) { HtmlSanitizer.inlineImages(sanitized, images.mapKeys { it.key.contentId!! }) }
                }
                RenderedBody(
                    finalHtml,
                    isHtml = true,
                    hasRemoteContent = remote,
                    hiddenAttachmentIds = images.keys.mapTo(HashSet()) { it.id },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A body the sanitiser chokes on is still readable as text.
            val text = detail.bodyText ?: html?.let { runCatching { HtmlText.toPlainText(it) }.getOrNull() }.orEmpty()
            return plainBody(text)
        }
    }

    private suspend fun plainBody(text: String): RenderedBody {
        val fragment = withContext(Dispatchers.Default) { HtmlSanitizer.plainTextToHtml(text) }
        return RenderedBody(fragment, isHtml = false, hasRemoteContent = false, hiddenAttachmentIds = emptySet(), isEmpty = text.isBlank())
    }

    /** Inline images as data: URIs, within the per-image and total size limits. */
    private suspend fun loadInlineImages(candidates: List<Attachment>): Map<Attachment, String> {
        val result = LinkedHashMap<Attachment, String>()
        var total = 0L
        var offline = false
        for (attachment in candidates) {
            // The announced size is the encoded (base64) size, about 4/3 of the real one.
            if (attachment.size > MAX_INLINE_IMAGE_BYTES * 4 / 3 + 4096) continue
            val cached = withContext(Dispatchers.IO) { attachment.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 } }
            val file = cached ?: if (offline) {
                null
            } else {
                mail.downloadAttachment(attachment.id).getOrElse { error ->
                    if ((error as? MailException)?.isTransient == true) offline = true
                    null
                }
            }
            if (file == null) continue
            val dataUri = withContext(Dispatchers.IO) {
                val length = file.length()
                if (length <= 0 || length > MAX_INLINE_IMAGE_BYTES || total + length > MAX_INLINE_TOTAL_BYTES) {
                    null
                } else {
                    runCatching {
                        "data:" + inlineImageMime(attachment.mimeType, attachment.fileName) + ";base64," +
                            Base64.getEncoder().encodeToString(file.readBytes())
                    }.getOrNull()?.also { total += length }
                }
            } ?: continue
            result[attachment] = dataUri
        }
        return result
    }

    // ------------------------------------------------------------------ remote images

    fun showImages(id: Long) {
        local.update { it.copy(imagesShown = it.imagesShown + id) }
    }

    /** Always loads remote images from [address] from now on. */
    fun trustSender(address: String) {
        val key = address.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return
        settingsRepository.update { it.copy(trustedImageSenders = it.trustedImageSenders + key) }
    }

    // ------------------------------------------------------------------ flags

    fun setMessageStarred(id: Long, starred: Boolean) {
        launchInApp { mail.setStarred(listOf(id), starred) }
    }

    /** Stars the newest message of the conversation, or clears every star of it. */
    fun setConversationStarred(starred: Boolean) {
        val ids = conversationIds()
        val messages = loadedMessages().filter { it.summary.id in ids }
        val targets = if (starred) {
            listOfNotNull((messages.lastOrNull { !it.isDraftMessage() } ?: messages.lastOrNull())?.summary?.id)
        } else {
            messages.filter { it.summary.isStarred }.map { it.summary.id }
        }
        if (targets.isEmpty()) return
        launchInApp { mail.setStarred(targets, starred) }
    }

    /** Marks the newest message of the conversation unread, so the mailbox shows it as unread. */
    fun markConversationUnread() {
        val ids = conversationIds().toSet()
        val target = loadedMessages().lastOrNull { it.summary.id in ids && !it.isDraftMessage() } ?: return
        markUnread(listOf(target.summary.id))
    }

    /** Marks [id] and the messages after it unread (except the user's own and drafts). */
    fun markUnreadFrom(id: Long) {
        val messages = loadedMessages()
        val start = messages.indexOfFirst { it.summary.id == id }
        if (start < 0) return
        val ids = messages.drop(start)
            .filterIndexed { index, detail -> index == 0 || (!detail.isDraftMessage() && !isFromSelf(detail)) }
            .map { it.summary.id }
        markUnread(ids)
    }

    private fun markUnread(ids: List<Long>) {
        if (ids.isEmpty()) return
        pendingRead.clear()
        markedRead += ids
        val previous = markReadJob
        markReadJob = launchInApp {
            previous?.join()
            mail.setRead(ids, false)
        }
        close()
    }

    private fun isFromSelf(detail: MessageDetail): Boolean =
        detail.summary.from?.address?.trim()?.lowercase(Locale.ROOT)?.let { it in selfAddresses.value } == true

    // ------------------------------------------------------------------ moves

    /** Archives the conversation (its messages in the opened folder). */
    fun archiveConversation(onResult: (Result<UndoHandle>) -> Unit) {
        val ids = conversationIds()
        runAction(onResult) { mail.archive(ids) }
    }

    /** Moves the conversation to Trash, or deletes it for good in Trash / Junk. */
    fun deleteConversation(onResult: (Result<UndoHandle?>) -> Unit) {
        val ids = conversationIds()
        runAction(onResult) { mail.delete(ids) }
    }

    fun moveConversation(folder: Folder, onResult: (Result<UndoHandle>) -> Unit) {
        val ids = conversationIds()
        runAction(onResult) { mail.moveTo(ids, folder.id) }
    }

    /** Deletes one message; the screen stays open while other messages remain. */
    fun deleteMessage(id: Long, onResult: (Result<UndoHandle?>) -> Unit) {
        local.update { it.copy(removed = it.removed + id) }
        if (loadedMessages().isEmpty()) close()
        runAction({ result ->
            if (result.isFailure) local.update { it.copy(removed = it.removed - id) }
            onResult(result)
        }) { mail.delete(listOf(id)) }
    }

    /** Reverts a move; also brings back a message deleted from this screen. */
    fun undo(handle: UndoHandle, restoreId: Long? = null) {
        launchInApp {
            mail.undo(handle)
            if (restoreId != null) local.update { it.copy(removed = it.removed - restoreId) }
        }
    }

    private fun <T> runAction(onResult: (Result<T>) -> Unit, action: suspend () -> Result<T>) {
        appScope.launch {
            val result = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    // ------------------------------------------------------------------ attachments

    fun openAttachment(attachment: Attachment) {
        withDownloaded(attachment.id) { file -> sendEvent(MessageEvent.OpenAttachment(file, attachment)) }
    }

    fun shareAttachment(attachment: Attachment) {
        withDownloaded(attachment.id) { file -> sendEvent(MessageEvent.ShareAttachment(file, attachment)) }
    }

    /**
     * Copies attachment [attachmentId] to [target], the document the user created in the "save as"
     * picker (null if cancelled). It works from the id alone: after the process was killed behind
     * the picker, a new ViewModel gets the result before the conversation has loaded. When the
     * attachment cannot be written, the empty document is deleted again.
     */
    fun saveAttachmentTo(attachmentId: Long?, fileName: String, target: Uri?, resolver: ContentResolver) {
        if (target == null) return
        if (attachmentId == null) {
            appScope.launch { deleteDocument(resolver, target) }
            sendEvent(MessageEvent.AttachmentSaveFailed)
            return
        }
        withDownloaded(
            attachmentId,
            scope = Scope.APP,
            onFailure = { error ->
                deleteDocument(resolver, target)
                sendEvent(MessageEvent.Failure(error))
            },
        ) { file ->
            val saved = withContext(Dispatchers.IO) {
                try {
                    resolver.openOutputStream(target)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (!saved) deleteDocument(resolver, target)
            sendEvent(if (saved) MessageEvent.AttachmentSaved(fileName) else MessageEvent.AttachmentSaveFailed)
        }
    }

    /** Best effort: removes a document left empty by a failed save. */
    private suspend fun deleteDocument(resolver: ContentResolver, document: Uri) {
        withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(resolver, document)
            } catch (e: Exception) {
                // Not deletable (or already gone): an empty file stays behind.
            }
        }
    }

    private enum class Scope { SCREEN, APP }

    /**
     * Downloads attachment [id] if needed and runs [action] with the local file. Opening and
     * sharing ([Scope.SCREEN]) are skipped while the same attachment is already downloading.
     */
    private fun withDownloaded(
        id: Long,
        scope: Scope = Scope.SCREEN,
        onFailure: suspend (Throwable) -> Unit = { sendEvent(MessageEvent.Failure(it)) },
        action: suspend (File) -> Unit,
    ) {
        if (scope == Scope.SCREEN && id in local.value.downloading) return
        local.update { it.copy(downloading = it.downloading + id) }
        (if (scope == Scope.APP) appScope else viewModelScope).launch {
            val result = try {
                mail.downloadAttachment(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                local.update { it.copy(downloading = it.downloading - id) }
            }
            result.fold(
                onSuccess = { action(it) },
                onFailure = { onFailure(it) },
            )
        }
    }

    // ------------------------------------------------------------------ print

    /** Prepares the newest message for printing, downloading its body first if needed. */
    suspend fun preparePrint(labels: PrintLabels): Result<PrintRequest> =
        try {
            buildPrintRequest(labels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun buildPrintRequest(labels: PrintLabels): Result<PrintRequest> {
        val messages = loadedMessages()
        var target = messages.lastOrNull { !it.isDraftMessage() } ?: messages.lastOrNull()
            ?: return Result.failure(MailException(MailErrorKind.NOT_FOUND))
        val id = target.summary.id
        if (!target.bodyLoaded) {
            mail.ensureBodyLoaded(id).onFailure { return Result.failure(it) }
            target = mail.observeMessage(id).first() ?: return Result.failure(MailException(MailErrorKind.NOT_FOUND))
        }
        val current = local.value.rendered[id]
        val body = if (current != null && current.complete && renderKeys[id] == RenderKey(target.bodyHtml, target.bodyText)) {
            current
        } else {
            renderBody(target, onInterim = null)
        }
        val summary = target.summary
        val subject = summary.subject.ifBlank { labels.noSubject }
        val header = listOf(
            PrintDocument.HeaderLine(labels.from, summary.from?.toString().orEmpty()),
            PrintDocument.HeaderLine(labels.to, summary.to.joinToString(", ", transform = EmailAddress::toString)),
            PrintDocument.HeaderLine(labels.cc, target.cc.joinToString(", ", transform = EmailAddress::toString)),
            PrintDocument.HeaderLine(labels.date, formatFullDate(summary.date)),
        )
        val html = withContext(Dispatchers.Default) { PrintDocument.build(body.html, body.isHtml, subject, header) }
        val settings = settingsRepository.settings.value
        val sender = summary.from?.address?.trim()?.lowercase(Locale.ROOT)
        val allowed = settings.loadRemoteImages || id in local.value.imagesShown ||
            (sender != null && sender in settings.trustedImageSenders)
        return Result.success(PrintRequest(html, blockNetworkLoads = !allowed, jobName = subject))
    }

    /** Never suspends nor throws: events raised after the screen is gone are dropped. */
    private fun sendEvent(event: MessageEvent) {
        eventChannel.trySend(event)
    }

    /**
     * Work that must finish even if the user leaves the screen. An unexpected failure becomes a
     * snackbar (while the screen is open) instead of crashing the app.
     */
    private fun launchInApp(block: suspend () -> Unit): Job = appScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendEvent(MessageEvent.Failure(e))
        }
    }

    override fun onCleared() {
        eventChannel.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_INLINE_IMAGE_BYTES = 3L * 1024 * 1024
        const val MAX_INLINE_TOTAL_BYTES = 10L * 1024 * 1024
        const val INTERIM_DELAY_MS = 1_500L
        const val ENSURE_WAIT_MS = 4_000L
    }
}
