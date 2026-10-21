package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.OutgoingAttachment
import kotlinx.coroutines.flow.Flow

/** Where the server copy of a draft lives: by uid when known, else by Message-ID. */
data class ServerCopy(
    val folder: String,
    val uid: Long?,
    val messageIdHeader: String?,
)

/** A local draft whose newest version is only on this device, as listed in the Drafts folder. */
data class UnsyncedDraft(
    val id: Long,
    val accountId: Long,
    val subject: String,
    val to: List<EmailAddress>,
    /** The first characters of the body. */
    val bodyStart: String,
    val hasAttachments: Boolean,
    val updatedAt: Long,
)

/** The unsynced drafts of a Drafts folder's account, and the cached server copies they replace. */
data class UnsyncedDrafts(val drafts: List<UnsyncedDraft>, val replacedMessageIds: Set<Long>) {
    companion object {
        val NONE = UnsyncedDrafts(emptyList(), emptySet())
    }
}

/**
 * Locally saved drafts (the compose screen's state, independent of the server Drafts folder).
 *
 * A draft is *unsynced* while its newest version is only on this device: it was never uploaded
 * (no server copy), or an upload of it is waiting or has failed (an APPEND_DRAFT operation
 * exists). Otherwise its server copy in the Drafts folder stands for it.
 */
class DraftDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    fun get(id: Long): Draft? {
        val draft = sql.query("SELECT ${DraftColumns.CONTENT}, updated_at FROM drafts WHERE id = ?", listOf(id)) {
            DraftColumns.mapDraft(it, sql, Tables.DRAFTS)
        }.firstOrNull() ?: return null
        return draft.copy(attachments = attachments(Tables.DRAFT_ATTACHMENTS, "draft_id", id))
    }

    /** The server message (local id) the draft edits, if any. */
    fun serverDraftMessageId(id: Long): Long? =
        sql.queryLong("SELECT server_draft_message_id AS v FROM drafts WHERE id = ?", listOf(id))

    /**
     * The local draft that continues the cached server draft [messageId]: the newest one that
     * edits it or whose server copy it is. Null if there is none.
     */
    fun findForServerMessage(messageId: Long): Long? =
        sql.queryLong(
            """
            SELECT d.id AS v FROM messages m JOIN folders f ON f.id = m.folder_id
            JOIN drafts d ON d.account_id = m.account_id
            WHERE m.id = ? AND (d.server_draft_message_id = m.id OR (d.server_folder = f.full_name AND $SAME_MESSAGE))
            ORDER BY d.updated_at DESC, d.id DESC LIMIT 1
            """.trimIndent(),
            listOf(messageId),
        )

    /** [unsynced] now and after every change. */
    fun observeUnsynced(draftsFolder: Folder): Flow<UnsyncedDrafts> =
        store.observe(Tables.DRAFTS, Tables.DRAFT_ATTACHMENTS, Tables.PENDING_OPS, Tables.MESSAGES) { unsynced(draftsFolder) }

    /**
     * The unsynced drafts of the account of [draftsFolder], newest first, with the cached rows of
     * that folder that are older server copies of them.
     */
    fun unsynced(draftsFolder: Folder): UnsyncedDrafts {
        val drafts = sql.query(
            """
            SELECT d.id, d.account_id, d.subject, d.to_addr, d.updated_at, substr(d.body, 1, $BODY_START) AS body_start,
                EXISTS (SELECT 1 FROM draft_attachments a WHERE a.draft_id = d.id) AS has_attachments
            FROM drafts d WHERE d.account_id = ? AND $UNSYNCED
            ORDER BY d.updated_at DESC, d.id DESC
            """.trimIndent(),
            listOf(draftsFolder.accountId, PendingOpType.APPEND_DRAFT),
        ) { row ->
            UnsyncedDraft(
                id = row.longOr("id"),
                accountId = row.longOr("account_id"),
                subject = row.stringOrEmpty("subject"),
                to = AddressCodec.decode(row.string("to_addr")),
                bodyStart = row.stringOrEmpty("body_start"),
                hasAttachments = row.bool("has_attachments"),
                updatedAt = row.longOr("updated_at"),
            )
        }
        if (drafts.isEmpty()) return UnsyncedDrafts.NONE
        val replaced = sql.query(
            """
            SELECT m.id AS id FROM drafts d JOIN messages m ON m.folder_id = ? AND $SAME_MESSAGE
            WHERE d.account_id = ? AND d.server_folder = ? AND $UNSYNCED
            """.trimIndent(),
            listOf(draftsFolder.id, draftsFolder.accountId, draftsFolder.fullName, PendingOpType.APPEND_DRAFT),
        ) { it.longOr("id") }
        return UnsyncedDrafts(drafts, replaced.toSet())
    }

    fun serverCopy(id: Long): ServerCopy? =
        sql.query("SELECT server_folder, server_uid, server_msgid FROM drafts WHERE id = ?", listOf(id)) {
            DraftColumns.mapServerCopy(it)
        }.firstOrNull()

    fun exists(id: Long): Boolean = sql.queryLong("SELECT COUNT(*) AS v FROM drafts WHERE id = ?", listOf(id))?.let { it > 0 } ?: false

    /**
     * Inserts or updates [draft] with its attachments and returns its id. A draft that edits a
     * server message adopts that message as its server copy the first time it is saved.
     */
    fun save(draft: Draft): Long = store.tx {
        val now = store.now()
        val values = DraftColumns.contentValues(draft) + mapOf("updated_at" to now)
        val id = if (draft.id != 0L && exists(draft.id)) {
            sql.update(Tables.DRAFTS, values, "id = ?", listOf(draft.id))
            draft.id
        } else {
            sql.insert(Tables.DRAFTS, values)
        }
        if (serverCopy(id) == null) {
            draft.serverDraftMessageId?.let { messageId -> serverCopyOfMessage(messageId)?.let { setServerCopy(id, it) } }
        }
        replaceAttachments(Tables.DRAFT_ATTACHMENTS, "draft_id", id, draft.attachments)
        store.changed(Tables.DRAFTS, Tables.DRAFT_ATTACHMENTS)
        id
    }

    fun setServerCopy(id: Long, copy: ServerCopy?) = store.tx {
        sql.update(
            Tables.DRAFTS,
            mapOf("server_folder" to copy?.folder, "server_uid" to copy?.uid, "server_msgid" to copy?.messageIdHeader),
            "id = ?",
            listOf(id),
        )
        store.changed(Tables.DRAFTS)
    }

    /** Deletes the draft and returns it with its server copy (null if it did not exist). */
    fun delete(id: Long): Pair<Draft, ServerCopy?>? = store.tx {
        val draft = get(id) ?: return@tx null
        val copy = serverCopy(id)
        sql.delete(Tables.DRAFTS, "id = ?", listOf(id))
        store.changed(Tables.DRAFTS, Tables.DRAFT_ATTACHMENTS)
        draft to copy
    }

    /**
     * Hides the cached rows of a draft's server [copy] and records the deletion of the copy on
     * the server. Returns the operation's id.
     */
    fun queueServerCopyDeletion(accountId: Long, copy: ServerCopy, notBefore: Long): Long = store.tx {
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
                notBefore = notBefore,
            ),
        )
    }

    /** Every attachment URI referenced by a local draft. */
    fun attachmentUris(): Set<String> =
        sql.query("SELECT uri FROM draft_attachments", emptyList()) { it.stringOrEmpty("uri") }.toSet()

    /** Attachment URIs of the account's drafts. */
    fun attachmentUrisOfAccount(accountId: Long): Set<String> =
        sql.query(
            "SELECT a.uri AS uri FROM draft_attachments a JOIN drafts d ON d.id = a.draft_id WHERE d.account_id = ?",
            listOf(accountId),
        ) { it.stringOrEmpty("uri") }.toSet()

    /** The server location of a cached message, used as a draft's server copy. */
    fun serverCopyOfMessage(messageId: Long): ServerCopy? =
        sql.query(
            """
            SELECT f.full_name AS folder, m.uid AS uid, m.message_id_header AS mid
            FROM messages m JOIN folders f ON f.id = m.folder_id WHERE m.id = ?
            """.trimIndent(),
            listOf(messageId),
        ) { ServerCopy(it.stringOrEmpty("folder"), it.long("uid"), it.string("mid")) }.firstOrNull()

    internal fun attachments(table: String, ownerColumn: String, ownerId: Long): List<OutgoingAttachment> =
        sql.query("SELECT * FROM $table WHERE $ownerColumn = ? ORDER BY position, id", listOf(ownerId)) { row ->
            OutgoingAttachment(
                uri = row.stringOrEmpty("uri"),
                fileName = row.stringOrEmpty("file_name"),
                mimeType = row.stringOrEmpty("mime_type"),
                size = row.longOr("size", -1),
            )
        }

    internal fun replaceAttachments(table: String, ownerColumn: String, ownerId: Long, attachments: List<OutgoingAttachment>) {
        sql.delete(table, "$ownerColumn = ?", listOf(ownerId))
        attachments.forEachIndexed { index, attachment ->
            sql.insert(
                table,
                mapOf(
                    ownerColumn to ownerId,
                    "position" to index,
                    "uri" to attachment.uri,
                    "file_name" to attachment.fileName,
                    "mime_type" to attachment.mimeType,
                    "size" to attachment.size,
                ),
            )
        }
    }
}

private const val BODY_START = 300

/**
 * SQL condition: the server copy of `drafts d` is the cached message `m` (same folder checked
 * separately). By uid, unless the Message-IDs disagree (the uid was reused after a UIDVALIDITY
 * change); by Message-ID when the uid is unknown.
 */
private const val SAME_MESSAGE =
    "((d.server_uid IS NOT NULL AND m.uid = d.server_uid AND (d.server_msgid IS NULL OR m.message_id_header IS NULL " +
        "OR m.message_id_header = d.server_msgid)) OR (d.server_uid IS NULL AND m.message_id_header = d.server_msgid))"

/** SQL condition on `drafts d` (one argument: APPEND_DRAFT): its newest version is only on this device. */
private const val UNSYNCED =
    "(d.server_folder IS NULL OR (d.server_uid IS NULL AND d.server_msgid IS NULL) OR EXISTS (" +
        "SELECT 1 FROM pending_ops p WHERE p.account_id = d.account_id AND p.type = ? AND p.target = CAST(d.id AS TEXT)))"

/** Column mapping shared by the drafts and outbox tables, which store the same content. */
internal object DraftColumns {
    /**
     * Characters of a body read with its row. A longer body (a reply quoting a huge message) is
     * read in slices: Android's CursorWindow (2 MB) cannot hold the whole row.
     */
    private const val BODY_CHUNK = 150_000

    /** Content columns of a drafts or outbox row, with the body as `body_head` and `body_len`. */
    const val CONTENT =
        "id, account_id, to_addr, cc_addr, bcc_addr, subject, in_reply_to, refs, mode, source_message_id, " +
            "server_draft_message_id, server_folder, server_uid, server_msgid, " +
            "substr(body, 1, $BODY_CHUNK) AS body_head, length(body) AS body_len"

    fun contentValues(draft: Draft): Map<String, Any?> = mapOf(
        "account_id" to draft.accountId,
        "to_addr" to AddressCodec.encode(draft.to),
        "cc_addr" to AddressCodec.encode(draft.cc),
        "bcc_addr" to AddressCodec.encode(draft.bcc),
        "subject" to draft.subject,
        "body" to draft.body,
        "in_reply_to" to draft.inReplyTo,
        "refs" to AddressCodec.encodeIds(draft.references),
        "mode" to draft.mode,
        "source_message_id" to draft.sourceMessageId,
        "server_draft_message_id" to draft.serverDraftMessageId,
    )

    /** Maps a row selected with [CONTENT] of [table]; the rest of a long body is read from [sql]. */
    fun mapDraft(row: SqlRow, sql: SqlDatabase, table: String, updatedColumn: String = "updated_at"): Draft = Draft(
        id = row.longOr("id"),
        accountId = row.longOr("account_id"),
        to = AddressCodec.decode(row.string("to_addr")),
        cc = AddressCodec.decode(row.string("cc_addr")),
        bcc = AddressCodec.decode(row.string("bcc_addr")),
        subject = row.stringOrEmpty("subject"),
        body = body(row, sql, table),
        attachments = emptyList(),
        inReplyTo = row.string("in_reply_to"),
        references = AddressCodec.decodeIds(row.string("refs")),
        mode = ComposeMode.entries.firstOrNull { it.name == row.string("mode") } ?: ComposeMode.NEW,
        sourceMessageId = row.long("source_message_id"),
        serverDraftMessageId = row.long("server_draft_message_id"),
        updatedAt = row.longOr(updatedColumn),
    )

    private fun body(row: SqlRow, sql: SqlDatabase, table: String): String {
        val head = row.stringOrEmpty("body_head")
        // SQLite counts characters (code points); Kotlin strings count UTF-16 units.
        val length = row.longOr("body_len")
        var read = head.codePointCount(0, head.length).toLong()
        if (read >= length) return head
        val id = row.longOr("id")
        val builder = StringBuilder(head)
        while (read < length) {
            val slice = sql.query("SELECT substr(body, ?, ?) AS v FROM $table WHERE id = ?", listOf(read + 1, BODY_CHUNK, id)) {
                it.string("v")
            }.firstOrNull()
            if (slice.isNullOrEmpty()) break
            builder.append(slice)
            read += slice.codePointCount(0, slice.length)
        }
        return builder.toString()
    }

    fun mapServerCopy(row: SqlRow): ServerCopy? {
        val folder = row.string("server_folder") ?: return null
        val uid = row.long("server_uid")
        val mid = row.string("server_msgid")
        if (uid == null && mid == null) return null
        return ServerCopy(folder, uid, mid)
    }
}
