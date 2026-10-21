package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.OutgoingAttachment

/** Where the server copy of a draft lives: by uid when known, else by Message-ID. */
data class ServerCopy(
    val folder: String,
    val uid: Long?,
    val messageIdHeader: String?,
)

/** Locally saved drafts (the compose screen's state, independent of the server Drafts folder). */
class DraftDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    fun get(id: Long): Draft? {
        val draft = sql.query("SELECT * FROM drafts WHERE id = ?", listOf(id)) { DraftColumns.mapDraft(it) }.firstOrNull()
            ?: return null
        return draft.copy(attachments = attachments(Tables.DRAFT_ATTACHMENTS, "draft_id", id))
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

/** Column mapping shared by the drafts and outbox tables, which store the same content. */
internal object DraftColumns {
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

    fun mapDraft(row: SqlRow, idColumn: String = "id", updatedColumn: String = "updated_at"): Draft = Draft(
        id = row.longOr(idColumn),
        accountId = row.longOr("account_id"),
        to = AddressCodec.decode(row.string("to_addr")),
        cc = AddressCodec.decode(row.string("cc_addr")),
        bcc = AddressCodec.decode(row.string("bcc_addr")),
        subject = row.stringOrEmpty("subject"),
        body = row.stringOrEmpty("body"),
        attachments = emptyList(),
        inReplyTo = row.string("in_reply_to"),
        references = AddressCodec.decodeIds(row.string("refs")),
        mode = ComposeMode.entries.firstOrNull { it.name == row.string("mode") } ?: ComposeMode.NEW,
        sourceMessageId = row.long("source_message_id"),
        serverDraftMessageId = row.long("server_draft_message_id"),
        updatedAt = row.longOr(updatedColumn),
    )

    fun mapServerCopy(row: SqlRow): ServerCopy? {
        val folder = row.string("server_folder") ?: return null
        val uid = row.long("server_uid")
        val mid = row.string("server_msgid")
        if (uid == null && mid == null) return null
        return ServerCopy(folder, uid, mid)
    }
}
