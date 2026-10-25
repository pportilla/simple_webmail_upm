package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.OutboxItem
import io.github.pportilla.upmmail.model.OutboxState
import kotlinx.coroutines.flow.Flow

/** An outbox row with the server copy of the draft it came from (deleted once sent). */
data class OutboxEntry(
    val item: OutboxItem,
    val serverCopy: ServerCopy?,
    val stateChangedAt: Long,
)

/**
 * Messages waiting to be sent. State machine: QUEUED -> SENDING -> (deleted | QUEUED | FAILED);
 * FAILED -> QUEUED on retry. The QUEUED -> SENDING step is a conditional UPDATE so that only one
 * sender (in-app timer or background job) can ever claim a message.
 */
class OutboxDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    fun observe(accountId: Long?): Flow<List<OutboxItem>> =
        store.observe(Tables.OUTBOX, Tables.OUTBOX_ATTACHMENTS) { list(accountId) }

    fun list(accountId: Long?): List<OutboxItem> {
        val rows = if (accountId == null) {
            sql.query("SELECT $COLUMNS FROM outbox ORDER BY created_at, id", emptyList(), ::mapEntry)
        } else {
            sql.query("SELECT $COLUMNS FROM outbox WHERE account_id = ? ORDER BY created_at, id", listOf(accountId), ::mapEntry)
        }
        return rows.map { withAttachments(it).item }
    }

    fun get(id: Long): OutboxEntry? =
        sql.query("SELECT $COLUMNS FROM outbox WHERE id = ?", listOf(id), ::mapEntry).firstOrNull()?.let(::withAttachments)

    /** Queues [draft] (its attachments included) to be sent at [sendAt]; returns the outbox id. */
    fun enqueue(draft: Draft, serverCopy: ServerCopy?, sendAt: Long): Long = store.tx {
        val now = store.now()
        val id = sql.insert(
            Tables.OUTBOX,
            DraftColumns.contentValues(draft) + mapOf(
                "server_folder" to serverCopy?.folder,
                "server_uid" to serverCopy?.uid,
                "server_msgid" to serverCopy?.messageIdHeader,
                "state" to OutboxState.QUEUED,
                "attempts" to 0,
                "last_error" to null,
                "send_at" to sendAt,
                "created_at" to now,
                "state_changed_at" to now,
            ),
        )
        store.drafts.replaceAttachments(Tables.OUTBOX_ATTACHMENTS, "outbox_id", id, draft.attachments)
        store.changed(Tables.OUTBOX, Tables.OUTBOX_ATTACHMENTS)
        id
    }

    /** QUEUED messages due at [now], oldest send time first. */
    fun due(accountId: Long?, now: Long): List<OutboxEntry> {
        val rows = if (accountId == null) {
            sql.query(
                "SELECT $COLUMNS FROM outbox WHERE state = ? AND send_at <= ? ORDER BY send_at, id",
                listOf(OutboxState.QUEUED, now),
                ::mapEntry,
            )
        } else {
            sql.query(
                "SELECT $COLUMNS FROM outbox WHERE account_id = ? AND state = ? AND send_at <= ? ORDER BY send_at, id",
                listOf(accountId, OutboxState.QUEUED, now),
                ::mapEntry,
            )
        }
        return rows.map(::withAttachments)
    }

    /** Earliest send time among QUEUED messages, or null if none is queued. */
    fun nextSendAt(): Long? =
        sql.queryLong("SELECT MIN(send_at) AS v FROM outbox WHERE state = ?", listOf(OutboxState.QUEUED))

    fun countQueued(): Int =
        sql.queryLong("SELECT COUNT(*) AS v FROM outbox WHERE state = ?", listOf(OutboxState.QUEUED))?.toInt() ?: 0

    /** QUEUED -> SENDING; true only for the single caller that won the race. */
    fun claim(id: Long): Boolean = store.tx {
        val claimed = sql.executeUpdateDelete(
            "UPDATE outbox SET state = ?, state_changed_at = ? WHERE id = ? AND state = ?",
            listOf(OutboxState.SENDING, store.now(), id, OutboxState.QUEUED),
        ) == 1
        if (claimed) store.changed(Tables.OUTBOX)
        claimed
    }

    /** SENDING -> QUEUED after a transient failure, to be retried at [retryAt]. */
    fun requeue(id: Long, attempts: Int, error: String?, retryAt: Long) = store.tx {
        sql.update(
            Tables.OUTBOX,
            mapOf(
                "state" to OutboxState.QUEUED,
                "attempts" to attempts,
                "last_error" to error,
                "send_at" to retryAt,
                "state_changed_at" to store.now(),
            ),
            "id = ?",
            listOf(id),
        )
        store.changed(Tables.OUTBOX)
    }

    fun fail(id: Long, attempts: Int, error: String?) = store.tx {
        sql.update(
            Tables.OUTBOX,
            mapOf(
                "state" to OutboxState.FAILED,
                "attempts" to attempts,
                "last_error" to error,
                "state_changed_at" to store.now(),
            ),
            "id = ?",
            listOf(id),
        )
        store.changed(Tables.OUTBOX)
    }

    /** FAILED -> QUEUED, due now. */
    fun retry(id: Long): Boolean = store.tx {
        val now = store.now()
        val changed = sql.executeUpdateDelete(
            "UPDATE outbox SET state = ?, attempts = 0, last_error = NULL, send_at = ?, state_changed_at = ? WHERE id = ? AND state = ?",
            listOf(OutboxState.QUEUED, now, now, id, OutboxState.FAILED),
        ) == 1
        if (changed) store.changed(Tables.OUTBOX)
        changed
    }

    fun delete(id: Long) = store.tx {
        sql.delete(Tables.OUTBOX, "id = ?", listOf(id))
        store.changed(Tables.OUTBOX, Tables.OUTBOX_ATTACHMENTS)
    }

    /** Deletes the row only if it is in one of [states]; returns it when deleted. */
    fun deleteIfIn(id: Long, vararg states: OutboxState): OutboxEntry? = store.tx {
        val entry = get(id) ?: return@tx null
        if (entry.item.state !in states) return@tx null
        val deleted = sql.delete(
            Tables.OUTBOX,
            "id = ? AND state IN (${placeholders(states.size)})",
            listOf<Any?>(id) + states.toList(),
        )
        if (deleted != 1) return@tx null
        store.changed(Tables.OUTBOX, Tables.OUTBOX_ATTACHMENTS)
        entry
    }

    /**
     * Rows left in SENDING by a process that died mid-send, older than [olderThan] and not in
     * [inProgress], become FAILED: they may or may not have been sent, so they are never resent
     * automatically. Returns the affected items.
     */
    fun failStaleSending(olderThan: Long, inProgress: Set<Long>, error: String): List<OutboxItem> = store.tx {
        val stale = sql.query(
            "SELECT $COLUMNS FROM outbox WHERE state = ? AND state_changed_at < ?",
            listOf(OutboxState.SENDING, olderThan),
            ::mapEntry,
        ).filter { it.item.id !in inProgress }
        for (entry in stale) fail(entry.item.id, entry.item.attempts, error)
        stale.map { withAttachments(it).item.copy(state = OutboxState.FAILED, lastError = error) }
    }

    /** Preserve all old supervised queued mail; only an explicit Retry can resume delivery. */
    fun holdForAudienceMigration() = store.tx {
        val changed = sql.executeUpdateDelete(
            "UPDATE outbox SET last_error = CASE WHEN state = ? THEN ? ELSE ? END, state = ?, state_changed_at = ? WHERE state IN (?, ?)",
            listOf(OutboxState.SENDING, io.github.pportilla.upmmail.model.MailErrorKind.SEND_UNCERTAIN.name,
                io.github.pportilla.upmmail.model.MailErrorKind.REVIEW_REQUIRED.name, OutboxState.FAILED,
                store.now(), OutboxState.QUEUED, OutboxState.SENDING),
        )
        if (changed > 0) store.changed(Tables.OUTBOX)
    }

    /** Every attachment URI referenced by the outbox. */
    fun attachmentUris(): Set<String> =
        sql.query("SELECT uri FROM outbox_attachments", emptyList()) { it.stringOrEmpty("uri") }.toSet()

    /** Attachment URIs of the account's outbox messages. */
    fun attachmentUrisOfAccount(accountId: Long): Set<String> =
        sql.query(
            "SELECT a.uri AS uri FROM outbox_attachments a JOIN outbox o ON o.id = a.outbox_id WHERE o.account_id = ?",
            listOf(accountId),
        ) { it.stringOrEmpty("uri") }.toSet()

    private fun withAttachments(entry: OutboxEntry): OutboxEntry {
        val attachments = store.drafts.attachments(Tables.OUTBOX_ATTACHMENTS, "outbox_id", entry.item.id)
        return entry.copy(item = entry.item.copy(draft = entry.item.draft.copy(attachments = attachments)))
    }

    private fun mapEntry(row: SqlRow): OutboxEntry {
        // The draft id of an outbox item is meaningless (the local draft is gone); keep it 0.
        val draft = DraftColumns.mapDraft(row, sql, Tables.OUTBOX, updatedColumn = "created_at").copy(id = 0)
        val item = OutboxItem(
            id = row.longOr("id"),
            accountId = row.longOr("account_id"),
            draft = draft,
            state = OutboxState.entries.firstOrNull { it.name == row.string("state") } ?: OutboxState.FAILED,
            attempts = row.intOr("attempts"),
            lastError = row.string("last_error"),
            sendAt = row.longOr("send_at"),
            createdAt = row.longOr("created_at"),
        )
        return OutboxEntry(item, DraftColumns.mapServerCopy(row), row.longOr("state_changed_at"))
    }

    private companion object {
        const val COLUMNS = "${DraftColumns.CONTENT}, state, attempts, last_error, send_at, created_at, state_changed_at"
    }
}
