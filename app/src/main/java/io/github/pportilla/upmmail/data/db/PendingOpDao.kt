package io.github.pportilla.upmmail.data.db

/** Kinds of local changes waiting to be replayed on the server. */
enum class PendingOpType {
    /** Set or clear [PendingOp.flag] on [PendingOp.uids] of [PendingOp.folder]. */
    FLAG,

    /** Move [PendingOp.uids] from [PendingOp.folder] to [PendingOp.target]. */
    MOVE,

    /** Permanently delete [PendingOp.uids] of [PendingOp.folder]. */
    DELETE,

    /** Move the messages whose Message-IDs are in [PendingOp.payload] (late undo of a move). */
    MOVE_BY_MESSAGE_ID,

    /**
     * Set \Seen on the messages of [PendingOp.folder] up to the uid in [PendingOp.payload]: the
     * newest one known when the user asked (filled in by [PendingOpDao.insert]), so mail that
     * arrives before the operation runs stays unread. No bound means every message.
     */
    MARK_FOLDER_READ,

    /**
     * Upload local draft [PendingOp.target] to the Drafts folder, replacing its previous copy.
     * An upload that fails for good is kept as failed ([PendingOpDao.markFailed]): it marks the
     * draft as not on the server (see [DraftDao]) until the draft is saved again.
     */
    APPEND_DRAFT,

    /** Delete a server copy of a draft: uid in [PendingOp.uids] or Message-ID in [PendingOp.payload]. */
    DELETE_SERVER_COPY,

    /** Store the sent message saved in file [PendingOp.payload] (RFC 822) in Sent folder [PendingOp.folder]. */
    APPEND_SENT,
}

data class PendingOp(
    val id: Long = 0,
    val accountId: Long,
    val type: PendingOpType,
    /** IMAP full name of the source folder. */
    val folder: String = "",
    val uids: List<Long> = emptyList(),
    /** Local ids of the affected (usually hidden) messages. */
    val messageIds: List<Long> = emptyList(),
    val target: String? = null,
    val flag: String? = null,
    val value: Boolean = false,
    val payload: String? = null,
    val undoGroup: Long? = null,
    /** True while the sync engine is executing it. */
    val inFlight: Boolean = false,
    val notBefore: Long = 0,
    val attempts: Int = 0,
    val createdAt: Long = 0,
)

/**
 * Uids of a folder whose local state must not be overwritten by server flags yet: [uids], plus
 * every uid up to [wholeFolderUpTo] when [wholeFolder] (a pending "mark all as read").
 */
data class ProtectedUids(val uids: Set<Long>, val wholeFolder: Boolean, val wholeFolderUpTo: Long = Long.MAX_VALUE) {
    operator fun contains(uid: Long): Boolean = (wholeFolder && uid <= wholeFolderUpTo) || uid in uids
}

class PendingOpDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    /**
     * Records [op]. A [PendingOpType.MARK_FOLDER_READ] without a bound gets the newest uid the
     * cache knows for its folder, read in the same transaction as the local change.
     */
    fun insert(op: PendingOp): Long = store.tx {
        val payload = if (op.type == PendingOpType.MARK_FOLDER_READ && op.payload == null) {
            newestKnownUid(op.accountId, op.folder)?.takeIf { it > 0 }?.toString()
        } else {
            op.payload
        }
        val id = sql.insert(
            Tables.PENDING_OPS,
            mapOf(
                "account_id" to op.accountId,
                "type" to op.type,
                "folder" to op.folder,
                "uids" to op.uids.joinToString(","),
                "message_ids" to op.messageIds.joinToString(","),
                "target" to op.target,
                "flag" to op.flag,
                "value" to op.value,
                "payload" to payload,
                "undo_group" to op.undoGroup,
                "state" to 0,
                "not_before" to op.notBefore,
                "attempts" to op.attempts,
                "created_at" to store.now(),
            ),
        )
        store.changed(Tables.PENDING_OPS)
        id
    }

    fun setUndoGroup(ids: Collection<Long>, group: Long) = store.tx {
        for (chunk in ids.chunked(IN_CHUNK)) {
            sql.executeUpdateDelete(
                "UPDATE pending_ops SET undo_group = ? WHERE id IN (${placeholders(chunk.size)})",
                listOf<Any?>(group) + chunk,
            )
        }
        store.changed(Tables.PENDING_OPS)
    }

    /** Operations of the account still to run (waiting or in flight), in the order they were recorded. */
    fun forAccount(accountId: Long): List<PendingOp> =
        sql.query("SELECT * FROM pending_ops WHERE account_id = ? AND state != $FAILED ORDER BY id", listOf(accountId), ::map)

    fun get(id: Long): PendingOp? = sql.query("SELECT * FROM pending_ops WHERE id = ?", listOf(id), ::map).firstOrNull()

    fun byGroup(group: Long): List<PendingOp> =
        sql.query("SELECT * FROM pending_ops WHERE undo_group = ? ORDER BY id", listOf(group), ::map)

    fun accountsWithPending(): List<Long> =
        sql.query("SELECT DISTINCT account_id FROM pending_ops WHERE state != $FAILED ORDER BY account_id", emptyList()) {
            it.longOr("account_id")
        }

    fun count(accountId: Long? = null): Int =
        if (accountId == null) {
            sql.queryLong("SELECT COUNT(*) AS v FROM pending_ops")?.toInt() ?: 0
        } else {
            sql.queryLong("SELECT COUNT(*) AS v FROM pending_ops WHERE account_id = ?", listOf(accountId))?.toInt() ?: 0
        }

    /** Payloads of every operation of [type] (e.g. the files of [PendingOpType.APPEND_SENT]). */
    fun payloads(type: PendingOpType): List<String> =
        sql.query("SELECT payload FROM pending_ops WHERE type = ? AND payload IS NOT NULL", listOf(type)) { it.stringOrEmpty("payload") }

    /** Makes the waiting operations of [types] on [folder] due now (their undo window ends early). */
    fun makeDue(accountId: Long, folder: String, types: Collection<PendingOpType>) = store.tx {
        if (types.isEmpty()) return@tx
        val changed = sql.executeUpdateDelete(
            """
            UPDATE pending_ops SET not_before = 0
            WHERE account_id = ? AND folder = ? AND state = 0 AND type IN (${placeholders(types.size)})
            """.trimIndent(),
            listOf<Any?>(accountId, folder) + types,
        )
        if (changed > 0) store.changed(Tables.PENDING_OPS)
    }

    /** True if changes of the read state of [folder]'s messages are waiting to be pushed. */
    fun hasPendingReadChanges(accountId: Long, folder: String): Boolean {
        val count = sql.queryLong(
            """
            SELECT COUNT(*) AS v FROM pending_ops
            WHERE account_id = ? AND folder = ? AND (type = ? OR (type = ? AND flag = ?))
            """.trimIndent(),
            listOf(accountId, folder, PendingOpType.MARK_FOLDER_READ, PendingOpType.FLAG, "SEEN"),
        )
        return (count ?: 0L) > 0
    }

    /** Earliest not_before of the account's waiting operations, or null if there are none. */
    fun nextNotBefore(accountId: Long): Long? =
        sql.queryLong("SELECT MIN(not_before) AS v FROM pending_ops WHERE account_id = ? AND state = 0", listOf(accountId))

    /** Marks the operation as being executed; false if it was undone or claimed meanwhile. */
    fun claim(id: Long): Boolean = store.tx {
        val claimed = sql.executeUpdateDelete("UPDATE pending_ops SET state = 1 WHERE id = ? AND state = 0", listOf(id)) == 1
        if (claimed) store.changed(Tables.PENDING_OPS)
        claimed
    }

    /** Returns a claimed operation to the queue after a transient failure; due again at [notBefore] if given. */
    fun release(id: Long, attempts: Int, notBefore: Long? = null) = store.tx {
        val values = mutableMapOf<String, Any?>("state" to 0, "attempts" to attempts)
        if (notBefore != null) values["not_before"] = notBefore
        sql.update(Tables.PENDING_OPS, values, "id = ?", listOf(id))
        store.changed(Tables.PENDING_OPS)
    }

    fun delete(id: Long) = store.tx {
        sql.delete(Tables.PENDING_OPS, "id = ?", listOf(id))
        store.changed(Tables.PENDING_OPS)
    }

    /** Keeps a claimed operation that failed for good, without running it again (see [PendingOpType.APPEND_DRAFT]). */
    fun markFailed(id: Long) = store.tx {
        sql.executeUpdateDelete("UPDATE pending_ops SET state = $FAILED WHERE id = ?", listOf(id))
        store.changed(Tables.PENDING_OPS)
    }

    /** After a restart nothing can be in flight any more. */
    fun resetInFlight() = store.tx {
        sql.executeUpdateDelete("UPDATE pending_ops SET state = 0 WHERE state = 1")
        store.changed(Tables.PENDING_OPS)
    }

    /**
     * Undo: deletes the waiting operations of [group] and returns them; operations already in
     * flight are left alone and returned separately.
     */
    fun cancelGroup(group: Long): Pair<List<PendingOp>, List<PendingOp>> = store.tx {
        val ops = byGroup(group)
        val (inFlight, waiting) = ops.partition { it.inFlight }
        for (op in waiting) sql.delete(Tables.PENDING_OPS, "id = ? AND state = 0", listOf(op.id))
        if (waiting.isNotEmpty()) store.changed(Tables.PENDING_OPS)
        waiting to inFlight
    }

    /**
     * Uids of [folder] with local changes not yet on the server: their flags must not be
     * overwritten by a sync that runs before they are flushed.
     */
    fun protectedUids(accountId: Long, folder: String): ProtectedUids {
        val ops = sql.query(
            "SELECT * FROM pending_ops WHERE account_id = ? AND folder = ? AND type IN (?, ?, ?, ?)",
            listOf(accountId, folder, PendingOpType.FLAG, PendingOpType.MOVE, PendingOpType.DELETE, PendingOpType.MARK_FOLDER_READ),
            ::map,
        )
        val folderWide = ops.filter { it.type == PendingOpType.MARK_FOLDER_READ }
        return ProtectedUids(
            uids = ops.flatMapTo(HashSet()) { it.uids },
            wholeFolder = folderWide.isNotEmpty(),
            wholeFolderUpTo = folderWide.maxOfOrNull { op -> op.payload?.toLongOrNull()?.takeIf { it > 0 } ?: Long.MAX_VALUE }
                ?: Long.MAX_VALUE,
        )
    }

    /**
     * Drops the operations on a folder whose uids became meaningless (UIDVALIDITY change).
     * Deletions of a draft copy known by Message-ID are kept (without their uid), and a pending
     * "mark all as read" loses its uid bound.
     */
    fun deleteForFolder(accountId: Long, folder: String): List<PendingOp> = store.tx {
        val ops = sql.query(
            "SELECT * FROM pending_ops WHERE account_id = ? AND folder = ? AND type IN (?, ?, ?, ?, ?)",
            listOf(
                accountId,
                folder,
                PendingOpType.FLAG,
                PendingOpType.MOVE,
                PendingOpType.DELETE,
                PendingOpType.DELETE_SERVER_COPY,
                PendingOpType.MARK_FOLDER_READ,
            ),
            ::map,
        )
        val dropped = ArrayList<PendingOp>()
        for (op in ops) {
            when {
                op.type == PendingOpType.MARK_FOLDER_READ ->
                    sql.executeUpdateDelete("UPDATE pending_ops SET payload = NULL WHERE id = ?", listOf(op.id))
                op.type == PendingOpType.DELETE_SERVER_COPY && op.payload != null ->
                    sql.executeUpdateDelete("UPDATE pending_ops SET uids = '' WHERE id = ?", listOf(op.id))
                else -> {
                    sql.delete(Tables.PENDING_OPS, "id = ?", listOf(op.id))
                    dropped += op
                }
            }
        }
        if (ops.isNotEmpty()) store.changed(Tables.PENDING_OPS)
        dropped
    }

    /**
     * After every message of [folder] was deleted on the server: drops the flag changes and
     * deletions waiting for it (nothing is left to apply them to). Moves are kept.
     */
    fun deleteForEmptiedFolder(accountId: Long, folder: String) = store.tx {
        val deleted = sql.executeUpdateDelete(
            "DELETE FROM pending_ops WHERE account_id = ? AND folder = ? AND state = 0 AND type IN (?, ?)",
            listOf(accountId, folder, PendingOpType.FLAG, PendingOpType.DELETE),
        )
        if (deleted > 0) store.changed(Tables.PENDING_OPS)
    }

    /** Removes the waiting and failed uploads of a local draft (superseded by a newer save, or deleted). */
    fun deleteDraftUploads(draftId: Long) = store.tx {
        sql.delete(
            Tables.PENDING_OPS,
            "type = ? AND target = ? AND state != 1",
            listOf(PendingOpType.APPEND_DRAFT, draftId.toString()),
        )
        store.changed(Tables.PENDING_OPS)
    }

    /** The window end or highest cached uid of the account's folder [folder], whichever is newer. */
    private fun newestKnownUid(accountId: Long, folder: String): Long? =
        sql.queryLong(
            """
            SELECT MAX(f.window_end_uid, IFNULL((SELECT MAX(m.uid) FROM messages m WHERE m.folder_id = f.id), 0)) AS v
            FROM folders f WHERE f.account_id = ? AND f.full_name = ?
            """.trimIndent(),
            listOf(accountId, folder),
        )

    private fun map(row: SqlRow): PendingOp = PendingOp(
        id = row.longOr("id"),
        accountId = row.longOr("account_id"),
        type = PendingOpType.entries.firstOrNull { it.name == row.string("type") } ?: PendingOpType.FLAG,
        folder = row.stringOrEmpty("folder"),
        uids = parseIds(row.string("uids")),
        messageIds = parseIds(row.string("message_ids")),
        target = row.string("target"),
        flag = row.string("flag"),
        value = row.bool("value"),
        payload = row.string("payload"),
        undoGroup = row.long("undo_group"),
        inFlight = row.longOr("state") == 1L,
        notBefore = row.longOr("not_before"),
        attempts = row.intOr("attempts"),
        createdAt = row.longOr("created_at"),
    )

    private fun parseIds(value: String?): List<Long> =
        value?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    private companion object {
        /** State of an operation kept after failing for good (0 = waiting, 1 = in flight). */
        const val FAILED = 2
    }
}
