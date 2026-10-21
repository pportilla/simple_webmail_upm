package io.github.pportilla.upmmail.data.repository

import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.PendingOp
import io.github.pportilla.upmmail.data.db.PendingOpType
import io.github.pportilla.upmmail.data.db.ServerCopy
import io.github.pportilla.upmmail.data.files.LocalFiles
import io.github.pportilla.upmmail.mail.HtmlText
import io.github.pportilla.upmmail.mail.Quoting
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.model.OutgoingAttachment
import io.github.pportilla.upmmail.sync.FlushScheduler
import io.github.pportilla.upmmail.sync.SyncEngine
import io.github.pportilla.upmmail.sync.mailResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Localised texts placed in replies and forwards. */
interface ComposeTexts {
    /** "On <date>, <sender> wrote:" */
    fun replyAttribution(date: Long, sender: String): String

    /** The block above a forwarded message: a separator line, From, Date, Subject, To and Cc. */
    fun forwardHeader(from: String, date: Long, subject: String, to: String, cc: String): String
}

/** Copies picked or shared content into app storage so it can be attached (and sent later). */
fun interface AttachmentImporter {
    /** A file:// attachment for [uri], or null if it cannot be read. */
    suspend fun import(uri: String): OutgoingAttachment?
}

/**
 * [ComposeRepository]: drafts are saved locally (and optionally uploaded to the server Drafts
 * folder through a pending operation); sending goes through the outbox, handled by the
 * [SyncEngine] when the [SyncController] says so.
 */
class ComposeRepositoryImpl(
    private val store: MailStore,
    private val engine: SyncEngine,
    private val flushScheduler: FlushScheduler,
    private val syncController: SyncController,
    private val files: LocalFiles,
    private val texts: ComposeTexts,
    private val importer: AttachmentImporter,
    /** An outbox message was retried, cancelled or deleted (to dismiss its failure notice). */
    private val onOutboxItemHandled: (Long) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ComposeRepository {

    override suspend fun createDraft(accountId: Long, mode: ComposeMode, sourceMessageId: Long?): Result<Draft> = mailResult {
        val account = db { store.accounts.get(accountId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Account $accountId")
        when (mode) {
            ComposeMode.NEW -> Draft(accountId = accountId, body = bodyWith(account, null), updatedAt = clock())
            ComposeMode.EDIT_DRAFT -> draftFromServerMessageInternal(
                sourceMessageId ?: throw MailException(MailErrorKind.NOT_FOUND, "No draft to edit"),
            )
            ComposeMode.REPLY, ComposeMode.REPLY_ALL -> reply(account, mode, sourceOf(sourceMessageId))
            ComposeMode.FORWARD -> forward(account, sourceOf(sourceMessageId))
        }
    }

    private suspend fun sourceOf(messageId: Long?): MessageDetail {
        val id = messageId ?: throw MailException(MailErrorKind.NOT_FOUND, "No message to reply to")
        loadBodyIfPossible(id)
        return db { store.messages.detail(id) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Message $id")
    }

    /** Quoting works without the body too (offline), so a failed download is not fatal here. */
    private suspend fun loadBodyIfPossible(messageId: Long) {
        try {
            engine.loadBody(messageId, inlineImages = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Reply without the quoted text rather than not at all.
        }
    }

    private suspend fun reply(account: Account, mode: ComposeMode, source: MessageDetail): Draft {
        val own = db { store.accounts.ownAddresses() } + account.email.lowercase()
        val recipients = Quoting.replyRecipients(
            from = source.summary.from,
            to = source.summary.to,
            cc = source.cc,
            replyTo = source.replyTo,
            selfAddresses = own,
            replyAll = mode == ComposeMode.REPLY_ALL,
        )
        val quoted = textOf(source)?.let { text ->
            val sender = source.summary.from?.toString() ?: ""
            texts.replyAttribution(source.summary.date, sender) + "\n" + Quoting.quote(text)
        }
        return Draft(
            accountId = account.id,
            to = recipients.to,
            cc = recipients.cc,
            subject = Quoting.replySubject(source.summary.subject),
            body = bodyWith(account, quoted),
            inReplyTo = source.messageIdHeader,
            references = Quoting.references(source.messageIdHeader, source.references),
            mode = mode,
            sourceMessageId = source.summary.id,
            updatedAt = clock(),
        )
    }

    private suspend fun forward(account: Account, source: MessageDetail): Draft {
        val header = texts.forwardHeader(
            from = source.summary.from?.toString() ?: "",
            date = source.summary.date,
            subject = source.summary.subject,
            to = source.summary.to.joinToString(", "),
            cc = source.cc.joinToString(", "),
        )
        val forwarded = header + "\n\n" + (textOf(source) ?: source.summary.snippet)
        return Draft(
            accountId = account.id,
            subject = Quoting.forwardSubject(source.summary.subject),
            body = bodyWith(account, forwarded),
            attachments = downloadAttachments(source),
            mode = ComposeMode.FORWARD,
            sourceMessageId = source.summary.id,
            updatedAt = clock(),
        )
    }

    /** The original's regular attachments, downloaded and referenced as file:// URIs. */
    private suspend fun downloadAttachments(source: MessageDetail): List<OutgoingAttachment> =
        source.attachments.filter { !it.isInline }.map { attachment ->
            val file = engine.downloadAttachment(attachment.id)
            OutgoingAttachment(
                uri = LocalFiles.fileUri(file),
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                size = file.length(),
            )
        }

    /** Empty space to type in, then the signature, then the quoted or forwarded text. */
    private fun bodyWith(account: Account, quoted: String?): String {
        val parts = mutableListOf("")
        if (account.signature.isNotBlank()) parts += SIGNATURE_SEPARATOR + account.signature.trimEnd()
        if (!quoted.isNullOrEmpty()) parts += quoted
        return if (parts.size == 1) "" else parts.joinToString("\n\n")
    }

    private fun textOf(source: MessageDetail): String? {
        source.bodyText?.takeIf { it.isNotBlank() }?.let { return it.trimEnd() }
        val html = source.bodyHtml?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { HtmlText.toPlainText(html) }.getOrNull()?.trimEnd()
    }

    override suspend fun loadDraft(draftId: Long): Draft? = db { store.drafts.get(draftId) }

    override suspend fun draftFromServerMessage(messageId: Long): Result<Draft> = mailResult {
        draftFromServerMessageInternal(messageId)
    }

    private suspend fun draftFromServerMessageInternal(messageId: Long): Draft {
        engine.loadBody(messageId, inlineImages = false)
        val source = db { store.messages.detail(messageId) } ?: throw MailException(MailErrorKind.NOT_FOUND, "Message $messageId")
        return Draft(
            accountId = source.summary.accountId,
            to = source.summary.to,
            cc = source.cc,
            bcc = source.bcc,
            subject = source.summary.subject,
            body = textOf(source).orEmpty(),
            attachments = downloadAttachments(source),
            inReplyTo = source.inReplyTo,
            references = source.references,
            mode = ComposeMode.EDIT_DRAFT,
            serverDraftMessageId = messageId,
            updatedAt = clock(),
        )
    }

    override suspend fun saveDraft(draft: Draft, upload: Boolean): Long {
        val id = db {
            store.tx {
                val id = store.drafts.save(draft.copy(updatedAt = clock()))
                if (upload) {
                    // Only the newest version needs uploading.
                    store.pendingOps.deleteDraftUploads(id)
                    store.pendingOps.insert(
                        PendingOp(
                            accountId = draft.accountId,
                            type = PendingOpType.APPEND_DRAFT,
                            target = id.toString(),
                            notBefore = clock(),
                        ),
                    )
                }
                id
            }
        }
        if (upload) flushScheduler.schedule(draft.accountId)
        return id
    }

    override suspend fun discardDraft(draft: Draft) {
        val serverCopy = db {
            store.tx {
                val deleted = if (draft.id != 0L) store.drafts.delete(draft.id) else null
                if (draft.id != 0L) store.pendingOps.deleteDraftUploads(draft.id)
                val copy = deleted?.second ?: draft.serverDraftMessageId?.let { store.drafts.serverCopyOfMessage(it) }
                if (copy != null) queueServerCopyDeletion(draft.accountId, copy)
                val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
                files.deleteComposeFiles((deleted?.first?.attachments ?: draft.attachments).map { it.uri }, referenced)
                copy
            }
        }
        if (serverCopy != null) flushScheduler.schedule(draft.accountId)
    }

    /** Hides the cached copy and records its deletion on the server. Inside a transaction. */
    private fun queueServerCopyDeletion(accountId: Long, copy: ServerCopy) {
        val folder = store.folders.findByFullName(accountId, copy.folder)
        val local = folder?.let { f ->
            listOfNotNull(
                copy.uid?.let { store.messages.findByUid(f.id, it) },
                copy.messageIdHeader?.let { store.messages.findByMessageIdHeader(f.id, it) },
            ).distinctBy { it.id }
        }.orEmpty()
        store.messages.setHidden(local.map { it.id }, true)
        store.pendingOps.insert(
            PendingOp(
                accountId = accountId,
                type = PendingOpType.DELETE_SERVER_COPY,
                folder = copy.folder,
                uids = listOfNotNull(copy.uid),
                messageIds = local.map { it.id },
                payload = copy.messageIdHeader,
                notBefore = clock(),
            ),
        )
    }

    override suspend fun send(draft: Draft, delaySeconds: Int): Long {
        val outboxId = db {
            store.tx {
                val copy = (if (draft.id != 0L) store.drafts.serverCopy(draft.id) else null)
                    ?: draft.serverDraftMessageId?.let { store.drafts.serverCopyOfMessage(it) }
                val id = store.outbox.enqueue(draft, copy, clock() + delaySeconds.coerceAtLeast(0) * 1000L)
                if (draft.id != 0L) {
                    store.pendingOps.deleteDraftUploads(draft.id)
                    store.drafts.delete(draft.id)
                }
                id
            }
        }
        syncController.requestOutboxSend()
        return outboxId
    }

    override suspend fun cancelSend(outboxId: Long): Draft? {
        val draft = db {
            store.tx {
                val entry = store.outbox.deleteIfIn(outboxId, OutboxState.QUEUED) ?: return@tx null
                val restored = entry.item.draft.copy(id = 0, updatedAt = clock())
                val id = store.drafts.save(restored)
                entry.serverCopy?.let { store.drafts.setServerCopy(id, it) }
                restored.copy(id = id)
            }
        }
        if (draft != null) outboxItemHandled(outboxId)
        syncController.requestOutboxSend()
        return draft
    }

    override fun observeOutbox(accountId: Long?): Flow<List<OutboxItem>> = store.outbox.observe(accountId)

    override suspend fun retrySend(outboxId: Long) {
        if (db { store.outbox.retry(outboxId) }) {
            outboxItemHandled(outboxId)
            syncController.requestOutboxSend()
        }
    }

    override suspend fun deleteOutboxItem(outboxId: Long) {
        db {
            store.tx {
                val entry = store.outbox.deleteIfIn(outboxId, OutboxState.QUEUED, OutboxState.FAILED) ?: return@tx
                val referenced = store.drafts.attachmentUris() + store.outbox.attachmentUris()
                files.deleteComposeFiles(entry.item.draft.attachments.map { it.uri }, referenced)
            }
        }
        outboxItemHandled(outboxId)
        syncController.requestOutboxSend()
    }

    private fun outboxItemHandled(outboxId: Long) {
        try {
            onOutboxItemHandled(outboxId)
        } catch (_: Exception) {
            // Notification housekeeping is best effort.
        }
    }

    override suspend fun describeAttachment(uri: String): OutgoingAttachment? =
        try {
            importer.import(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun <T> db(block: () -> T): T = withContext(io) { block() }

    private companion object {
        /** The standard signature delimiter: dash, dash, space. */
        const val SIGNATURE_SEPARATOR = "-- \n"
    }
}
