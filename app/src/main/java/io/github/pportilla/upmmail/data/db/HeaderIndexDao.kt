package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.IndexedHeader
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.mail.RemoteMessage
import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.IndexStatus
import io.github.pportilla.upmmail.model.IndexedMessage
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchKey
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** Bookkeeping of one folder of the header index (a `header_index_state` row). */
data class IndexFolderState(
    val folderId: Long,
    val accountId: Long,
    /** UIDVALIDITY the folder's index rows belong to. */
    val uidValidity: Long,
    /** Server UIDNEXT at the last check for new mail: every message below it has been seen. */
    val uidNext: Long,
    /** STATUS MESSAGES at that check. */
    val serverCount: Int,
    /** Index rows of the folder. */
    val indexedCount: Int,
    /** The last full comparison with the server found nothing missing within the cap. */
    val complete: Boolean,
    /** The folder holds older messages beyond what the index keeps of it. */
    val capped: Boolean,
    /** Our own APPEND or MOVE added mail here since the last check for new mail. */
    val dirty: Boolean,
    /** Time of the last full comparison with the server. */
    val checkedAt: Long?,
    /** Time of the last check of any kind. */
    val updatedAt: Long?,
)

/** Result of [HeaderIndexDao.applyDiff]. Not a data class: it holds an array. */
class DiffResult(
    /** Index rows deleted. */
    val removed: Int,
    /** Uids of the folder still indexed, ascending. */
    val remaining: LongArray,
)

/**
 * The header index: a few headers of the messages of every indexable folder (see
 * [FolderDao.isIndexable]), cached or not, so search finds mail beyond the sync window without
 * asking the server. It lives apart from `messages`: its writes notify only the index tables,
 * so mailbox lists never re-run because of it.
 *
 * Rows are keyed by folder and uid and belong to the UIDVALIDITY of the folder's state row; a
 * folder without a state row has never been indexed and gets no rows. Every delete goes through
 * `header_index` and its trigger, which keeps `header_index_fts` in step.
 */
class HeaderIndexDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    // ---------------------------------------------------------------- folder state

    fun state(folderId: Long): IndexFolderState? =
        sql.query("SELECT * FROM header_index_state WHERE folder_id = ?", listOf(folderId), ::mapState).firstOrNull()

    fun statesOf(accountId: Long): List<IndexFolderState> =
        sql.query("SELECT * FROM header_index_state WHERE account_id = ? ORDER BY folder_id", listOf(accountId), ::mapState)

    /**
     * The folder's state row, created if missing. Callers must hold a fresh server answer
     * (STATUS or EXAMINE): a new row starts with [uidNext] and [messageCount] from it. If the
     * stored UIDVALIDITY differs, the folder's rows are deleted and the row is created again.
     * Null if the folder is gone.
     */
    fun ensureState(folderId: Long, uidValidity: Long, uidNext: Long, messageCount: Int): IndexFolderState? = store.tx {
        val accountId = sql.queryLong("SELECT account_id AS v FROM folders WHERE id = ?", listOf(folderId)) ?: return@tx null
        val existing = state(folderId)
        if (existing != null && existing.uidValidity == uidValidity) return@tx existing
        if (existing != null) {
            sql.delete(Tables.HEADER_INDEX, "folder_id = ?", listOf(folderId))
            sql.delete(Tables.HEADER_INDEX_STATE, "folder_id = ?", listOf(folderId))
            store.changed(Tables.HEADER_INDEX, Tables.HEADER_INDEX_FTS)
        }
        sql.insert(
            Tables.HEADER_INDEX_STATE,
            mapOf(
                "folder_id" to folderId,
                "account_id" to accountId,
                "uid_validity" to uidValidity,
                "uid_next" to uidNext.coerceAtLeast(1),
                "server_count" to messageCount.coerceAtLeast(0),
            ),
        )
        store.changed(Tables.HEADER_INDEX_STATE)
        state(folderId)
    }

    /** Forgets the folder: its rows and its state row (the cache was dropped, UIDVALIDITY changed). */
    fun resetFolder(folderId: Long) = store.tx {
        sql.delete(Tables.HEADER_INDEX, "folder_id = ?", listOf(folderId))
        sql.delete(Tables.HEADER_INDEX_STATE, "folder_id = ?", listOf(folderId))
        store.changed(*INDEX_TABLES)
    }

    /** The folder was emptied on the server: no rows, and the empty index is complete. */
    fun clearRows(folderId: Long) = store.tx {
        sql.delete(Tables.HEADER_INDEX, "folder_id = ?", listOf(folderId))
        sql.update(
            Tables.HEADER_INDEX_STATE,
            mapOf("complete" to true, "capped" to false, "server_count" to 0, "indexed_count" to 0),
            "folder_id = ?",
            listOf(folderId),
        )
        store.changed(*INDEX_TABLES)
    }

    /** Changes the given fields of the folder's state row; null leaves a field as it is. */
    fun updateState(
        folderId: Long,
        uidNext: Long? = null,
        serverCount: Int? = null,
        complete: Boolean? = null,
        capped: Boolean? = null,
        dirty: Boolean? = null,
        checkedAt: Long? = null,
        updatedAt: Long? = null,
    ) {
        val values = LinkedHashMap<String, Any?>()
        uidNext?.let { values["uid_next"] = it.coerceAtLeast(1) }
        serverCount?.let { values["server_count"] = it.coerceAtLeast(0) }
        complete?.let { values["complete"] = it }
        capped?.let { values["capped"] = it }
        dirty?.let { values["dirty"] = it }
        checkedAt?.let { values["checked_at"] = it }
        updatedAt?.let { values["updated_at"] = it }
        if (values.isEmpty()) return
        store.tx {
            if (sql.update(Tables.HEADER_INDEX_STATE, values, "folder_id = ?", listOf(folderId)) > 0) {
                store.changed(Tables.HEADER_INDEX_STATE)
            }
        }
    }

    /** Our own APPEND or MOVE added mail to [folderIds]. Folders never indexed have no row and stay so. */
    fun markDirty(folderIds: Collection<Long>) = store.tx {
        var changed = 0
        for (chunk in folderIds.distinct().chunked(IN_CHUNK)) {
            changed += sql.executeUpdateDelete(
                "UPDATE header_index_state SET dirty = 1 WHERE dirty = 0 AND folder_id IN (${placeholders(chunk.size)})",
                chunk,
            )
        }
        if (changed > 0) store.changed(Tables.HEADER_INDEX_STATE)
    }

    /**
     * Folders waiting for a check for the mail our own changes added, of [accountId] or of every
     * account. Only folders the index still covers: no run checks the others.
     */
    fun dirtyFolders(accountId: Long? = null): List<Long> {
        val where = if (accountId == null) "" else " AND s.account_id = ?"
        return sql.query(
            """
            SELECT s.folder_id, f.role, f.selectable, f.attributes FROM header_index_state s JOIN folders f ON f.id = s.folder_id
            WHERE s.dirty = 1$where ORDER BY s.folder_id
            """.trimIndent(),
            listOfNotNull(accountId),
        ) { row -> row.longOr("folder_id").takeIf { FolderDao.isIndexable(row) } }.filterNotNull()
    }

    fun observeDirty(): Flow<Set<Long>> =
        store.observe(Tables.HEADER_INDEX_STATE, Tables.FOLDERS) { dirtyFolders().toSet() }

    // ---------------------------------------------------------------- rows

    /**
     * Stores a batch of headers fetched for the folder, in one transaction. Messages marked
     * \Deleted lose their row; new ones are inserted with their full-text entry; known ones get
     * the read, starred and answered flags. The recipients are kept for the Sent folder only.
     *
     * Returns how many rows were inserted, or -1 when nothing was stored: the folder is gone, was
     * never indexed, or the batch belongs to another UIDVALIDITY (a stale batch never resets the
     * folder; only a fresh server answer may, through [ensureState]).
     */
    fun insertHeaders(folderId: Long, uidValidity: Long, headers: List<IndexedHeader>): Int = store.tx {
        val folder = sql.query("SELECT account_id, role FROM folders WHERE id = ?", listOf(folderId)) { row ->
            row.longOr("account_id") to FolderDao.roleOf(row.string("role"))
        }.firstOrNull() ?: return@tx -1
        val state = state(folderId)
        if (state == null || state.uidValidity != uidValidity) return@tx -1
        val (accountId, role) = folder
        val now = store.now()
        var inserted = 0
        for (header in headers) {
            if (header.flags.deleted) {
                sql.delete(Tables.HEADER_INDEX, "folder_id = ? AND uid = ?", listOf(folderId, header.uid))
                continue
            }
            val id = sql.insert(Tables.HEADER_INDEX, rowValues(accountId, folderId, role, header, now), Conflict.IGNORE)
            if (id > 0) {
                inserted++
                val people = AddressCodec.people(listOfNotNull(header.from), header.to.take(MAX_FTS_RECIPIENTS), header.cc.take(MAX_FTS_RECIPIENTS))
                sql.execSQL(
                    "INSERT INTO header_index_fts (docid, subject, people) VALUES (?, ?, ?)",
                    listOf(id, SearchText.fold(header.subject), SearchText.fold(people).take(MAX_FTS_PEOPLE)),
                )
            } else {
                writeFlags(folderId, header.uid, header.flags)
            }
        }
        if (headers.isNotEmpty()) {
            recount(folderId)
            store.changed(*INDEX_TABLES)
        }
        inserted
    }

    /** [insertHeaders] for messages fetched by the mailbox sync. */
    fun upsertFromSync(folderId: Long, uidValidity: Long, messages: List<RemoteMessage>): Int =
        insertHeaders(folderId, uidValidity, messages.map { it.toIndexedHeader() })

    fun deleteUids(folderId: Long, uids: Collection<Long>): Int = store.tx {
        var count = 0
        for (chunk in uids.distinct().chunked(IN_CHUNK)) {
            count += sql.delete(Tables.HEADER_INDEX, "folder_id = ? AND uid IN (${placeholders(chunk.size)})", listOf<Any?>(folderId) + chunk)
        }
        if (count > 0) rowsRemoved(folderId)
        count
    }

    /** Deletes the rows with uid in [range] that are not in [present] (expunged on the server). */
    fun deleteMissingInRange(folderId: Long, range: LongRange, present: Set<Long>): Int = store.tx {
        if (range.isEmpty()) return@tx 0
        val gone = sql.query(
            "SELECT uid FROM header_index WHERE folder_id = ? AND uid BETWEEN ? AND ?",
            listOf(folderId, range.first, range.last),
        ) { it.longOr("uid") }.filter { it !in present }
        deleteUids(folderId, gone)
    }

    /** Deletes the folder's rows with one of [messageIds] (case-insensitive). Scans the folder; rare. */
    fun deleteByMessageIds(folderId: Long, messageIds: Collection<String>): Int = store.tx {
        val wanted = messageIds.mapNotNullTo(HashSet()) { normalizeMessageId(it) }
        if (wanted.isEmpty()) return@tx 0
        val uids = sql.query(
            "SELECT uid, message_id_header FROM header_index WHERE folder_id = ? AND message_id_header IS NOT NULL",
            listOf(folderId),
        ) { row -> row.longOr("uid").takeIf { normalizeMessageId(row.string("message_id_header")) in wanted } }
            .filterNotNull()
        deleteUids(folderId, uids)
    }

    /**
     * Compares the folder with [present], every undeleted uid on the server (ascending), in one
     * transaction: indexed uids below [below] (the UIDNEXT seen before the server was asked)
     * that are not present are deleted, and so are rows older than the newest [keepNewest] of
     * [present] (the cap shrank). Uids from [below] on are kept: they may have arrived after the
     * server answered.
     */
    fun applyDiff(folderId: Long, present: LongArray, below: Long, keepNewest: Int): DiffResult = store.tx {
        val sorted = if (isAscending(present)) present else present.sortedArray()
        val cutoff = if (keepNewest in 1 until sorted.size) sorted[sorted.size - keepNewest] else Long.MIN_VALUE
        val indexed = indexedUids(folderId)
        val remaining = LongArray(indexed.size)
        var kept = 0
        val gone = ArrayList<Long>()
        for (uid in indexed) {
            val missing = uid < below && sorted.binarySearch(uid) < 0
            if (missing || uid < cutoff) gone += uid else remaining[kept++] = uid
        }
        DiffResult(deleteUids(folderId, gone), remaining.copyOf(kept))
    }

    /** Indexed uids of the folder from [fromUid] on, ascending. */
    fun indexedUids(folderId: Long, fromUid: Long = 1): LongArray =
        sql.query("SELECT uid FROM header_index WHERE folder_id = ? AND uid >= ? ORDER BY uid", listOf(folderId, fromUid)) {
            it.longOr("uid")
        }.toLongArray()

    fun maxUid(folderId: Long): Long? = sql.queryLong("SELECT MAX(uid) AS v FROM header_index WHERE folder_id = ?", listOf(folderId))

    fun oldestDate(folderId: Long): Long? = sql.queryLong("SELECT MIN(date) AS v FROM header_index WHERE folder_id = ?", listOf(folderId))

    /** Index rows of the account (the sum kept in the state rows, so no table scan). */
    fun accountRowCount(accountId: Long): Int =
        sql.queryLong("SELECT COALESCE(SUM(indexed_count), 0) AS v FROM header_index_state WHERE account_id = ?", listOf(accountId))
            ?.toInt() ?: 0

    // ---------------------------------------------------------------- flags

    /** Server flags of known rows; rows marked \Deleted are removed, like in [insertHeaders]. */
    fun updateFlags(folderId: Long, flags: Map<Long, RemoteFlags>) {
        if (flags.isEmpty()) return
        store.tx {
            var changed = 0
            val deleted = ArrayList<Long>()
            for ((uid, value) in flags) {
                if (value.deleted) deleted += uid else changed += writeFlags(folderId, uid, value)
            }
            if (changed > 0) store.changed(Tables.HEADER_INDEX)
            deleteUids(folderId, deleted)
        }
    }

    /** A flag change the server accepted. The index has no column for [LocalFlag.FORWARDED]. */
    fun setFlag(folderId: Long, uids: Collection<Long>, flag: LocalFlag, value: Boolean) {
        if (flag == LocalFlag.FORWARDED) return
        store.tx {
            var changed = 0
            for (chunk in uids.distinct().chunked(IN_CHUNK)) {
                changed += sql.executeUpdateDelete(
                    "UPDATE header_index SET ${flag.column} = ? WHERE folder_id = ? AND ${flag.column} != ? AND uid IN (${placeholders(chunk.size)})",
                    listOf<Any?>(value, folderId, value) + chunk,
                )
            }
            if (changed > 0) store.changed(Tables.HEADER_INDEX)
        }
    }

    fun markFolderRead(folderId: Long) = store.tx {
        if (sql.executeUpdateDelete("UPDATE header_index SET is_read = 1 WHERE folder_id = ? AND is_read = 0", listOf(folderId)) > 0) {
            store.changed(Tables.HEADER_INDEX)
        }
    }

    private fun writeFlags(folderId: Long, uid: Long, flags: RemoteFlags): Int =
        sql.executeUpdateDelete(
            """
            UPDATE header_index SET is_read = ?, is_starred = ?, is_answered = ?
            WHERE folder_id = ? AND uid = ? AND (is_read != ? OR is_starred != ? OR is_answered != ?)
            """.trimIndent(),
            listOf(flags.seen, flags.flagged, flags.answered, folderId, uid, flags.seen, flags.flagged, flags.answered),
        )

    // ---------------------------------------------------------------- search

    /**
     * Full-text search over subject and people of the indexed headers; [query] is free user
     * text. Words of at least [MIN_PREFIX] characters match as prefixes, shorter ones only as
     * whole words ("SO", "CV": a one- or two-letter prefix would match most of the index).
     * Filters work as in [MessageDao.search], and without a folder filter Trash and Junk are left
     * out.
     *
     * A short last word that matches nothing may still be being typed ("elecciones j"): then the
     * other words are searched without it, so the matches do not vanish until its third letter.
     *
     * Messages with a row in `messages` (hidden ones included) are left out: the cached copy is
     * the one to show, and a hidden one is being moved or deleted. Newest first.
     */
    fun search(query: String, filters: SearchFilters, limit: Int): List<IndexedMessage> {
        for (match in matchExpressions(query)) {
            val candidates = sql.queryLong(
                "SELECT COUNT(*) AS v FROM (SELECT docid FROM header_index_fts WHERE header_index_fts MATCH ? LIMIT ${MANY_CANDIDATES + 1})",
                listOf(match),
            ) ?: 0L
            if (candidates > 0L) return matching(match, candidates, filters, limit)
        }
        return emptyList()
    }

    /**
     * The MATCH expressions [search] tries in turn: [query] as typed, then, when its last word is
     * shorter than [MIN_PREFIX], may still be being typed (no space after it) and a longer word
     * comes before it, [query] without that word.
     */
    private fun matchExpressions(query: String): List<String> {
        val exact = SearchText.matchExpression(query, MIN_PREFIX) ?: return emptyList()
        val words = SearchText.words(query)
        val typing = query.lastOrNull()?.isWhitespace() == false
        val last = words.lastOrNull()
        val earlier = words.dropLast(1)
        if (!typing || last == null || last.length >= MIN_PREFIX || last in earlier || earlier.none { it.length >= MIN_PREFIX }) {
            return listOf(exact)
        }
        return listOfNotNull(exact, SearchText.matchExpression(earlier.joinToString(" "), MIN_PREFIX))
    }

    /** The rows of [match] (which has [candidates] full-text matches, counted up to a point) for [search]. */
    private fun matching(match: String, candidates: Long, filters: SearchFilters, limit: Int): List<IndexedMessage> {
        // Few matches: fetch them by id and sort. Many (a common word): walk the date index from
        // the newest message down and stop at the limit instead of sorting them all.
        val access = if (candidates > MANY_CANDIDATES) "INDEXED BY header_index_date" else "NOT INDEXED"
        val where = StringBuilder("h.id IN (SELECT docid FROM header_index_fts WHERE header_index_fts MATCH ?)")
        val args = mutableListOf<Any?>(match)
        filters.accountId?.let { where.append(" AND h.account_id = ?"); args += it }
        if (filters.folderId != null) {
            where.append(" AND h.folder_id = ?")
            args += filters.folderId
        } else {
            where.append(" AND f.role NOT IN ('TRASH', 'JUNK')")
        }
        if (filters.unreadOnly) where.append(" AND h.is_read = 0")
        if (filters.starredOnly) where.append(" AND h.is_starred = 1")
        if (filters.withAttachments) where.append(" AND h.has_attachments = 1")
        val safeLimit = limit.coerceAtLeast(1)
        // Rows cached in `messages` are dropped below, so ask for more.
        args += 2 * safeLimit + 50
        val rows = sql.query(
            """
            SELECT $INDEXED_COLUMNS FROM header_index h $access CROSS JOIN folders f ON f.id = h.folder_id
            WHERE $where
            ORDER BY h.date DESC, h.id DESC LIMIT ?
            """.trimIndent(),
            args,
            ::mapIndexed,
        )
        val cached = store.messages.existingKeys(rows.map { SearchKey(it.folderId, it.uid) })
        return rows.filter { SearchKey(it.folderId, it.uid) !in cached }.take(safeLimit)
    }

    fun get(indexId: Long): IndexedMessage? =
        sql.query(
            "SELECT $INDEXED_COLUMNS FROM header_index h JOIN folders f ON f.id = h.folder_id WHERE h.id = ?",
            listOf(indexId),
            ::mapIndexed,
        ).firstOrNull()

    /** Rows of [uids] in the folder, newest first. */
    fun findByUids(folderId: Long, uids: Collection<Long>): List<IndexedMessage> =
        uids.distinct().chunked(IN_CHUNK).flatMap { chunk ->
            sql.query(
                """
                SELECT $INDEXED_COLUMNS FROM header_index h JOIN folders f ON f.id = h.folder_id
                WHERE h.folder_id = ? AND h.uid IN (${placeholders(chunk.size)})
                """.trimIndent(),
                listOf<Any?>(folderId) + chunk,
                ::mapIndexed,
            )
        }.sortedWith(compareByDescending<IndexedMessage> { it.date }.thenByDescending { it.indexId })

    /** Rows of the account with Message-ID [messageId] (case-insensitive), newest first. Scans the account; rare. */
    fun findByMessageId(accountId: Long, messageId: String): List<IndexedMessage> =
        sql.query(
            """
            SELECT $INDEXED_COLUMNS FROM header_index h JOIN folders f ON f.id = h.folder_id
            WHERE h.account_id = ? AND h.message_id_header = ? COLLATE NOCASE
            ORDER BY h.date DESC, h.id DESC
            """.trimIndent(),
            listOf(accountId, messageId.trim()),
            ::mapIndexed,
        )

    // ---------------------------------------------------------------- status

    fun observeStatus(): Flow<List<IndexStatus>> =
        store.observe(Tables.HEADER_INDEX_STATE, Tables.FOLDERS, Tables.ACCOUNTS) { status() }

    /**
     * Coverage of every account, in account order. [IndexStatus.total] counts the indexable
     * folders' messages (the server count of the last check, else the synced count), each folder
     * capped by [capOf] and the account by [ACCOUNT_CAP]; [IndexStatus.indexed] is at most that.
     * An account is complete once each of its indexable folders is; one whose folders are not
     * known yet is not.
     */
    fun status(): List<IndexStatus> {
        val rows = sql.query(
            """
            SELECT a.id AS account_id, f.id AS folder_id, f.role, f.selectable, f.attributes, f.total_count,
                s.folder_id AS has_state, s.server_count, s.indexed_count, s.complete
            FROM accounts a
            LEFT JOIN folders f ON f.account_id = a.id
            LEFT JOIN header_index_state s ON s.folder_id = f.id
            ORDER BY a.sort_order, a.id
            """.trimIndent(),
            emptyList(),
        ) { row ->
            StatusRow(
                accountId = row.longOr("account_id"),
                indexable = row.long("folder_id") != null && FolderDao.isIndexable(row),
                role = FolderDao.roleOf(row.string("role")),
                hasState = row.long("has_state") != null,
                serverCount = row.intOr("server_count"),
                totalCount = row.intOr("total_count"),
                indexedCount = row.intOr("indexed_count"),
                complete = row.bool("complete"),
            )
        }
        return rows.groupBy { it.accountId }.map { (accountId, folders) ->
            val indexable = folders.filter { it.indexable }
            val total = indexable.sumOf { folder ->
                minOf(if (folder.hasState) folder.serverCount else folder.totalCount, capOf(folder.role)).toLong()
            }.coerceAtMost(ACCOUNT_CAP.toLong()).toInt()
            IndexStatus(
                accountId = accountId,
                indexed = indexable.sumOf { it.indexedCount.toLong() }.coerceAtMost(total.toLong()).toInt(),
                total = total,
                complete = indexable.isNotEmpty() && indexable.all { it.hasState && it.complete },
            )
        }
    }

    /**
     * Merges the full-text segments left by many small batches, after a bulk run. Each round is
     * a short transaction; it stops when a round finds nothing left to merge.
     */
    fun mergeSegments() {
        repeat(MAX_MERGE_ROUNDS) {
            val merged = store.tx {
                val before = sql.queryLong("SELECT total_changes() AS v") ?: 0L
                sql.execSQL("INSERT INTO header_index_fts(header_index_fts) VALUES('merge=200,8')")
                (sql.queryLong("SELECT total_changes() AS v") ?: 0L) - before >= 2
            }
            if (!merged) return
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun rowValues(accountId: Long, folderId: Long, role: FolderRole, header: IndexedHeader, now: Long): Map<String, Any?> =
        mapOf(
            "account_id" to accountId,
            "folder_id" to folderId,
            "uid" to header.uid,
            "message_id_header" to header.messageId,
            "subject" to header.subject,
            "from_addr" to AddressCodec.encode(header.from),
            "to_addr" to if (role == FolderRole.SENT) AddressCodec.encode(header.to.take(MAX_STORED_RECIPIENTS)) else "",
            "date" to MessageDao.effectiveDate(header.sentDate, header.internalDate, now),
            "internal_date" to header.internalDate,
            "size" to header.size,
            "is_read" to header.flags.seen,
            "is_starred" to header.flags.flagged,
            "is_answered" to header.flags.answered,
            "has_attachments" to header.likelyHasAttachments,
        )

    private fun recount(folderId: Long) {
        sql.executeUpdateDelete(
            "UPDATE header_index_state SET indexed_count = (SELECT COUNT(*) FROM header_index WHERE folder_id = ?) WHERE folder_id = ?",
            listOf(folderId, folderId),
        )
    }

    private fun rowsRemoved(folderId: Long) {
        recount(folderId)
        store.changed(*INDEX_TABLES)
    }

    private fun mapState(row: SqlRow): IndexFolderState = IndexFolderState(
        folderId = row.longOr("folder_id"),
        accountId = row.longOr("account_id"),
        uidValidity = row.longOr("uid_validity"),
        uidNext = row.longOr("uid_next"),
        serverCount = row.intOr("server_count"),
        indexedCount = row.intOr("indexed_count"),
        complete = row.bool("complete"),
        capped = row.bool("capped"),
        dirty = row.bool("dirty"),
        checkedAt = row.long("checked_at"),
        updatedAt = row.long("updated_at"),
    )

    private fun mapIndexed(row: SqlRow): IndexedMessage = IndexedMessage(
        indexId = row.longOr("id"),
        accountId = row.longOr("account_id"),
        folderId = row.longOr("folder_id"),
        folderRole = FolderDao.roleOf(row.string("folder_role")),
        uid = row.longOr("uid"),
        messageIdHeader = row.string("message_id_header"),
        subject = row.stringOrEmpty("subject"),
        from = AddressCodec.decode(row.string("from_addr")).firstOrNull(),
        to = AddressCodec.decode(row.string("to_addr")),
        date = row.longOr("date"),
        size = row.longOr("size"),
        isRead = row.bool("is_read"),
        isStarred = row.bool("is_starred"),
        isAnswered = row.bool("is_answered"),
        hasAttachments = row.bool("has_attachments"),
    )

    private class StatusRow(
        val accountId: Long,
        val indexable: Boolean,
        val role: FolderRole,
        val hasState: Boolean,
        val serverCount: Int,
        val totalCount: Int,
        val indexedCount: Int,
        val complete: Boolean,
    )

    companion object {
        /** Newest messages indexed per folder. */
        const val FOLDER_CAP = 50_000

        /** Newest messages indexed in Trash and Junk. */
        const val BIN_CAP = 2_000

        /** Rows per account; past it only new mail is indexed. */
        const val ACCOUNT_CAP = 100_000

        /** Shortest word that matches as a prefix in [search]. */
        const val MIN_PREFIX = 3

        fun capOf(role: FolderRole): Int = if (role == FolderRole.TRASH || role == FolderRole.JUNK) BIN_CAP else FOLDER_CAP

        /**
         * Whether a complete index answers [query] for its folder, so the server need not be
         * asked about the mail it holds: every word is at least [MIN_PREFIX] long and matched as
         * a prefix. A shorter word only matches whole words here, while the server's header
         * search also finds it inside longer ones ("cv" in "CVs").
         */
        fun answersFully(query: String): Boolean = SearchText.allTermsHaveLength(query, MIN_PREFIX)

        /** Matches above which [search] walks the date index instead of sorting the matches. */
        private const val MANY_CANDIDATES = 2_000

        private const val MAX_STORED_RECIPIENTS = 5

        /**
         * To and Cc recipients each made searchable. A complete index stands in for the server's
         * header search, so long distribution lists are covered too; only the full-text entries
         * of the rare messages that have them grow.
         */
        private const val MAX_FTS_RECIPIENTS = 200
        private const val MAX_FTS_PEOPLE = 8_000
        private const val MAX_MERGE_ROUNDS = 16

        private const val INDEXED_COLUMNS =
            "h.id, h.account_id, h.folder_id, h.uid, h.message_id_header, h.subject, h.from_addr, h.to_addr, h.date, " +
                "h.size, h.is_read, h.is_starred, h.is_answered, h.has_attachments, f.role AS folder_role"

        private val INDEX_TABLES = arrayOf(Tables.HEADER_INDEX, Tables.HEADER_INDEX_FTS, Tables.HEADER_INDEX_STATE)

        private fun normalizeMessageId(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

        private fun isAscending(values: LongArray): Boolean {
            for (i in 1 until values.size) if (values[i - 1] > values[i]) return false
            return true
        }
    }
}
