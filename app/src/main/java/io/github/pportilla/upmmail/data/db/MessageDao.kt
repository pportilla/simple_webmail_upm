package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.ParsedAttachment
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.mail.RemoteMessage
import io.github.pportilla.upmmail.mail.Threading
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MessageDetail
import io.github.pportilla.upmmail.model.MessageSummary
import io.github.pportilla.upmmail.model.SearchFilters
import kotlinx.coroutines.flow.Flow

/** Identity and state of a cached message, as needed by actions and the sync engine. */
data class MessageRef(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val messageIdHeader: String?,
    val threadId: String,
    val isRead: Boolean,
    val isStarred: Boolean,
    val isAnswered: Boolean,
    val isForwarded: Boolean,
    val isDraft: Boolean,
    val hidden: Boolean,
    val size: Long,
    val bodyLoaded: Boolean,
)

/** Flags the user can change locally. */
enum class LocalFlag(internal val column: String) {
    READ("is_read"),
    STARRED("is_starred"),
    ANSWERED("is_answered"),
    FORWARDED("is_forwarded"),
}

/** An attachment together with where its message lives on the server. */
data class AttachmentLocation(
    val attachment: Attachment,
    val accountId: Long,
    val folderFullName: String,
    val uid: Long,
)

/** Result of [MessageDao.applyServerFlags]. */
data class FlagSyncResult(val updated: Int, val removed: Int)

class MessageDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    // ---------------------------------------------------------------- lists

    fun observeList(folderId: Long, limit: Int, conversations: Boolean): Flow<List<MessageSummary>> =
        store.observe(Tables.MESSAGES) { list(folderId, limit, conversations) }

    /**
     * Newest-first visible messages of a folder. With [conversations] one row per thread: its
     * newest visible message, the number of visible messages, "read" only if all of them are
     * read, starred / with attachments if any of them is. Correlated subqueries rather than
     * window functions, which Android 8's SQLite lacks.
     */
    fun list(folderId: Long, limit: Int, conversations: Boolean): List<MessageSummary> {
        val safeLimit = limit.coerceAtLeast(1)
        if (!conversations) {
            return sql.query(
                """
                SELECT $SUMMARY_COLUMNS, 1 AS thread_count FROM messages m
                WHERE m.folder_id = ? AND m.hidden = 0
                ORDER BY m.date DESC, m.id DESC LIMIT ?
                """.trimIndent(),
                listOf(folderId, safeLimit),
                ::mapSummary,
            )
        }
        val sameThread = "t.folder_id = m.folder_id AND t.thread_id = m.thread_id AND t.hidden = 0"
        return sql.query(
            """
            SELECT $SUMMARY_COLUMNS,
                (SELECT COUNT(*) FROM messages t WHERE $sameThread) AS thread_count,
                (SELECT MIN(t.is_read) FROM messages t WHERE $sameThread) AS thread_read,
                (SELECT MAX(t.is_starred) FROM messages t WHERE $sameThread) AS thread_starred,
                (SELECT MAX(t.has_attachments) FROM messages t WHERE $sameThread) AS thread_attachments
            FROM messages m
            WHERE m.folder_id = ? AND m.hidden = 0
                AND m.id = (
                    SELECT t.id FROM messages t WHERE $sameThread
                    ORDER BY t.date DESC, t.id DESC LIMIT 1
                )
            ORDER BY m.date DESC, m.id DESC LIMIT ?
            """.trimIndent(),
            listOf(folderId, safeLimit),
        ) { row ->
            mapSummary(row).copy(
                isRead = row.bool("thread_read"),
                isStarred = row.bool("thread_starred"),
                hasAttachments = row.bool("thread_attachments"),
            )
        }
    }

    /** Summaries of [ids] in the given order (missing ids are skipped). */
    fun summaries(ids: List<Long>): List<MessageSummary> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, MessageSummary>()
        for (chunk in ids.distinct().chunked(IN_CHUNK)) {
            sql.query(
                "SELECT $SUMMARY_COLUMNS, 1 AS thread_count FROM messages m WHERE m.id IN (${placeholders(chunk.size)})",
                chunk,
                ::mapSummary,
            ).forEach { byId[it.id] = it }
        }
        return ids.mapNotNull { byId[it] }
    }

    // ---------------------------------------------------------------- details

    fun observeDetail(messageId: Long): Flow<MessageDetail?> =
        store.observe(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.FOLDERS) { detail(messageId) }

    fun detail(messageId: Long): MessageDetail? {
        val rows = sql.query(
            "SELECT $DETAIL_COLUMNS FROM messages m JOIN folders f ON f.id = m.folder_id WHERE m.id = ?",
            listOf(messageId),
            ::mapDetail,
        )
        val detail = rows.firstOrNull() ?: return null
        return detail.copy(attachments = attachmentsFor(listOf(messageId))[messageId].orEmpty())
    }

    fun observeThread(messageId: Long): Flow<List<MessageDetail>> =
        store.observe(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.FOLDERS) { thread(messageId) }

    /**
     * Every visible message of [messageId]'s thread in its account, oldest first. Trash and Junk
     * copies are left out unless the message itself is there, and copies of the same message in
     * several folders (same Message-ID) appear once, preferring [messageId] itself.
     */
    fun thread(messageId: Long): List<MessageDetail> {
        val base = ref(messageId) ?: return emptyList()
        val baseRole = folderRoleOf(base.folderId)
        val includeBin = baseRole == FolderRole.TRASH || baseRole == FolderRole.JUNK
        val rows = sql.query(
            """
            SELECT $DETAIL_COLUMNS FROM messages m JOIN folders f ON f.id = m.folder_id
            WHERE m.account_id = ? AND m.thread_id = ? AND (m.hidden = 0 OR m.id = ?)
            ORDER BY m.date ASC, m.id ASC
            """.trimIndent(),
            listOf(base.accountId, base.threadId, messageId),
            ::mapDetail,
        )
            .filter { includeBin || it.summary.id == messageId || (it.folderRole != FolderRole.TRASH && it.folderRole != FolderRole.JUNK) }
        val chosen = rows.groupBy { it.messageIdHeader?.lowercase() ?: "#${it.summary.id}" }
            .values
            .map { copies ->
                copies.firstOrNull { it.summary.id == messageId }
                    ?: copies.firstOrNull { it.bodyLoaded }
                    ?: copies.first()
            }
            .mapTo(HashSet()) { it.summary.id }
        val ordered = rows.filter { it.summary.id in chosen }
        val attachments = attachmentsFor(ordered.map { it.summary.id })
        return ordered.map { it.copy(attachments = attachments[it.summary.id].orEmpty()) }
    }

    private fun folderRoleOf(folderId: Long): FolderRole =
        FolderDao.roleOf(sql.query("SELECT role FROM folders WHERE id = ?", listOf(folderId)) { it.string("role") }.firstOrNull())

    // ---------------------------------------------------------------- refs

    fun ref(id: Long): MessageRef? = refs(listOf(id)).firstOrNull()

    fun refs(ids: Collection<Long>): List<MessageRef> {
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().chunked(IN_CHUNK).flatMap { chunk ->
            sql.query("SELECT $REF_COLUMNS FROM messages WHERE id IN (${placeholders(chunk.size)})", chunk, ::mapRef)
        }
    }

    fun findByUid(folderId: Long, uid: Long): MessageRef? =
        sql.query("SELECT $REF_COLUMNS FROM messages WHERE folder_id = ? AND uid = ?", listOf(folderId, uid), ::mapRef).firstOrNull()

    fun findByMessageIdHeader(folderId: Long, messageIdHeader: String): MessageRef? =
        sql.query(
            "SELECT $REF_COLUMNS FROM messages WHERE folder_id = ? AND message_id_header = ? LIMIT 1",
            listOf(folderId, messageIdHeader),
            ::mapRef,
        ).firstOrNull()

    /** Every visible message sharing folder and thread with one of [ids] (the ids included). */
    fun expandToThreads(ids: Collection<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val result = LinkedHashSet<Long>(ids)
        for (chunk in ids.distinct().chunked(IN_CHUNK)) {
            sql.query(
                """
                SELECT t.id AS id FROM messages t JOIN messages m ON t.folder_id = m.folder_id AND t.thread_id = m.thread_id
                WHERE m.id IN (${placeholders(chunk.size)}) AND t.hidden = 0
                """.trimIndent(),
                chunk,
            ) { it.longOr("id") }.forEach { result += it }
        }
        return result.toList()
    }

    /** Lowest and highest cached uid of the folder, or null when nothing is cached. */
    fun uidBounds(folderId: Long): LongRange? =
        sql.query("SELECT MIN(uid) AS lo, MAX(uid) AS hi, COUNT(*) AS n FROM messages WHERE folder_id = ?", listOf(folderId)) { row ->
            if (row.longOr("n") == 0L) null else row.longOr("lo")..row.longOr("hi")
        }.firstOrNull()

    fun count(folderId: Long): Int =
        sql.queryLong("SELECT COUNT(*) AS v FROM messages WHERE folder_id = ?", listOf(folderId))?.toInt() ?: 0

    /** Cached uids below [windowStart] (e.g. added by a server search). */
    fun uidsBelow(folderId: Long, windowStart: Long): List<Long> =
        sql.query("SELECT uid FROM messages WHERE folder_id = ? AND uid < ? ORDER BY uid", listOf(folderId, windowStart)) {
            it.longOr("uid")
        }

    /** Newest messages of the folder whose body is not cached and that are smaller than [maxSize]. */
    fun needingBody(folderId: Long, limit: Int, maxSize: Long): List<MessageRef> =
        sql.query(
            """
            SELECT $REF_COLUMNS FROM messages
            WHERE folder_id = ? AND body_loaded = 0 AND hidden = 0 AND size < ?
            ORDER BY date DESC, id DESC LIMIT ?
            """.trimIndent(),
            listOf(folderId, maxSize, limit),
            ::mapRef,
        )

    // ---------------------------------------------------------------- sync writes

    /**
     * Inserts the messages that are not cached yet and refreshes the flags of the others (except
     * [protectedUids], which have local changes waiting to be pushed). Messages marked \Deleted
     * are skipped. New messages feed the contact history unless [feedContacts] is false.
     *
     * Returns the local ids of the newly inserted messages.
     */
    fun upsertHeaders(
        accountId: Long,
        folderId: Long,
        messages: List<RemoteMessage>,
        protectedUids: Set<Long> = emptySet(),
        feedContacts: Boolean = true,
        ownAddresses: Set<String> = emptySet(),
    ): List<Long> = store.tx {
        val inserted = ArrayList<Long>()
        for (message in messages) {
            if (message.flags.deleted) continue
            val values = headerValues(accountId, folderId, message)
            val id = sql.insert(Tables.MESSAGES, values, Conflict.IGNORE)
            if (id > 0) {
                inserted += id
                insertFts(id, message.subject, AddressCodec.people(listOfNotNull(message.from), message.to, message.cc), "")
                if (feedContacts) {
                    val people = listOfNotNull(message.from) + message.to + message.cc
                    store.contacts.recordSeen(people, values["date"] as Long, ownAddresses)
                }
            } else if (message.uid !in protectedUids) {
                writeFlags(folderId, message.uid, message.flags)
            }
        }
        store.changed(Tables.MESSAGES, Tables.MESSAGES_FTS)
        if (feedContacts && inserted.isNotEmpty()) store.changed(Tables.CONTACTS)
        inserted
    }

    private fun writeFlags(folderId: Long, uid: Long, flags: RemoteFlags) {
        sql.executeUpdateDelete(
            """
            UPDATE messages SET is_read = ?, is_starred = ?, is_answered = ?, is_forwarded = ?, is_draft = ?
            WHERE folder_id = ? AND uid = ?
            """.trimIndent(),
            listOf(flags.seen, flags.flagged, flags.answered, flags.forwarded, flags.draft, folderId, uid),
        )
    }

    /**
     * Reconciles the cached messages with uid in [range] against the server flags: rows missing
     * from [serverFlags] (expunged) or marked \Deleted are removed, the others get the server
     * flags unless their uid is in [protectedUids].
     */
    fun applyServerFlags(
        folderId: Long,
        range: LongRange,
        serverFlags: Map<Long, RemoteFlags>,
        protectedUids: Set<Long>,
    ): FlagSyncResult = store.tx {
        val cached = sql.query(
            "SELECT $REF_COLUMNS FROM messages WHERE folder_id = ? AND uid BETWEEN ? AND ?",
            listOf(folderId, range.first, range.last),
            ::mapRef,
        )
        var updated = 0
        val toDelete = ArrayList<Long>()
        for (row in cached) {
            val flags = serverFlags[row.uid]
            if (flags == null || flags.deleted) {
                toDelete += row.id
            } else if (row.uid !in protectedUids && row.differsFrom(flags)) {
                writeFlags(folderId, row.uid, flags)
                updated++
            }
        }
        deleteIdsInternal(toDelete)
        if (updated > 0 || toDelete.isNotEmpty()) store.changed(Tables.MESSAGES)
        if (toDelete.isNotEmpty()) store.changed(Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
        FlagSyncResult(updated, toDelete.size)
    }

    private fun MessageRef.differsFrom(flags: RemoteFlags): Boolean =
        isRead != flags.seen || isStarred != flags.flagged || isAnswered != flags.answered ||
            isForwarded != flags.forwarded || isDraft != flags.draft

    /** Drops the whole cache of a folder (UIDVALIDITY changed). */
    fun clearFolder(folderId: Long) = store.tx {
        sql.execSQL("DELETE FROM messages_fts WHERE docid IN (SELECT id FROM messages WHERE folder_id = ?)", listOf(folderId))
        sql.delete(Tables.MESSAGES, "folder_id = ?", listOf(folderId))
        store.changed(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
    }

    /** Deletes the visible rows of a folder except [keepUids] (window reset after a long gap). */
    fun deleteVisibleExcept(folderId: Long, keepUids: Set<Long>): Int = store.tx {
        val ids = sql.query("SELECT id, uid FROM messages WHERE folder_id = ? AND hidden = 0", listOf(folderId)) {
            it.longOr("id") to it.longOr("uid")
        }.filter { it.second !in keepUids }.map { it.first }
        deleteIdsInternal(ids)
        store.changed(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
        ids.size
    }

    fun deleteByUids(folderId: Long, uids: Collection<Long>): Int = store.tx {
        var count = 0
        for (chunk in uids.distinct().chunked(IN_CHUNK)) {
            val ids = sql.query(
                "SELECT id FROM messages WHERE folder_id = ? AND uid IN (${placeholders(chunk.size)})",
                listOf<Any?>(folderId) + chunk,
            ) { it.longOr("id") }
            deleteIdsInternal(ids)
            count += ids.size
        }
        store.changed(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
        count
    }

    fun deleteIds(ids: Collection<Long>) = store.tx {
        deleteIdsInternal(ids)
        store.changed(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
    }

    private fun deleteIdsInternal(ids: Collection<Long>) {
        for (chunk in ids.distinct().chunked(IN_CHUNK)) {
            sql.execSQL("DELETE FROM messages_fts WHERE docid IN (${placeholders(chunk.size)})", chunk)
            sql.delete(Tables.MESSAGES, "id IN (${placeholders(chunk.size)})", chunk)
        }
    }

    /**
     * Stores a downloaded body, its preview text and its attachment list. Returns false, storing
     * nothing, if the message is gone or its body is stored already (two downloads of the same
     * body raced; a message's body never changes). Attachment rows are matched by part path, so
     * their ids (held by open screens) and downloaded files survive a body stored again after
     * [dropOldBodies].
     */
    fun storeBody(
        messageId: Long,
        html: String?,
        text: String?,
        snippet: String,
        attachments: List<ParsedAttachment>,
        searchableText: String,
    ): Boolean = store.tx {
        val loaded = sql.query("SELECT body_loaded FROM messages WHERE id = ?", listOf(messageId)) { it.bool("body_loaded") }
            .firstOrNull()
        if (loaded != false) return@tx false
        sql.update(
            Tables.MESSAGES,
            mapOf(
                "body_html" to html,
                "body_text" to text,
                "snippet" to snippet,
                "body_loaded" to true,
                "has_attachments" to attachments.any { !it.isInline },
            ),
            "id = ?",
            listOf(messageId),
        )
        val existing = sql.query("SELECT id, part_path FROM attachments WHERE message_id = ?", listOf(messageId)) {
            it.stringOrEmpty("part_path") to it.longOr("id")
        }.toMap()
        val parts = attachments.distinctBy { it.partPath }
        for (attachment in parts) {
            val values = mapOf(
                "file_name" to attachment.fileName,
                "mime_type" to attachment.mimeType,
                "size" to attachment.size,
                "content_id" to attachment.contentId,
                "is_inline" to attachment.isInline,
            )
            val id = existing[attachment.partPath]
            if (id != null) {
                sql.update(Tables.ATTACHMENTS, values, "id = ?", listOf(id))
            } else {
                sql.insert(Tables.ATTACHMENTS, values + mapOf("message_id" to messageId, "part_path" to attachment.partPath))
            }
        }
        val gone = existing.filterKeys { path -> parts.none { it.partPath == path } }.values
        for (chunk in gone.chunked(IN_CHUNK)) sql.delete(Tables.ATTACHMENTS, "id IN (${placeholders(chunk.size)})", chunk)
        sql.execSQL(
            "UPDATE messages_fts SET body = ? WHERE docid = ?",
            listOf(SearchText.fold(searchableText.take(MAX_INDEXED_BODY)), messageId),
        )
        store.changed(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
        true
    }

    /**
     * Drops the downloaded bodies of each folder's messages beyond its newest [keepPerFolder]:
     * they are downloaded again when opened. The snippet, the attachment list and the search
     * index entry stay. Returns how many bodies were dropped.
     */
    fun dropOldBodies(keepPerFolder: Int): Int = store.tx {
        val folderIds = sql.query("SELECT id FROM folders", emptyList()) { it.longOr("id") }
        var dropped = 0
        for (folderId in folderIds) {
            dropped += sql.executeUpdateDelete(
                """
                UPDATE messages SET body_html = NULL, body_text = NULL, body_loaded = 0
                WHERE folder_id = ? AND body_loaded = 1 AND id NOT IN (
                    SELECT id FROM messages WHERE folder_id = ? ORDER BY date DESC, id DESC LIMIT ?
                )
                """.trimIndent(),
                listOf(folderId, folderId, keepPerFolder.coerceAtLeast(0)),
            )
        }
        if (dropped > 0) store.changed(Tables.MESSAGES)
        dropped
    }

    private fun insertFts(id: Long, subject: String, people: String, body: String) {
        sql.execSQL(
            "INSERT INTO messages_fts (docid, subject, people, body) VALUES (?, ?, ?, ?)",
            listOf(id, SearchText.fold(subject), SearchText.fold(people), SearchText.fold(body)),
        )
    }

    // ---------------------------------------------------------------- local actions

    /** Sets [flag] on [ids] and returns the messages whose value actually changed. */
    fun setFlag(ids: Collection<Long>, flag: LocalFlag, value: Boolean): List<MessageRef> = store.tx {
        val changed = refs(ids).filter { it.flag(flag) != value }
        for (chunk in changed.map { it.id }.chunked(IN_CHUNK)) {
            sql.executeUpdateDelete(
                "UPDATE messages SET ${flag.column} = ? WHERE id IN (${placeholders(chunk.size)})",
                listOf<Any?>(value) + chunk,
            )
        }
        if (changed.isNotEmpty()) store.changed(Tables.MESSAGES)
        changed
    }

    fun setHidden(ids: Collection<Long>, hidden: Boolean) = store.tx {
        for (chunk in ids.distinct().chunked(IN_CHUNK)) {
            sql.executeUpdateDelete(
                "UPDATE messages SET hidden = ? WHERE id IN (${placeholders(chunk.size)})",
                listOf<Any?>(hidden) + chunk,
            )
        }
        store.changed(Tables.MESSAGES)
    }

    /** Marks every cached message of the folder read; returns the ids that were unread. */
    fun markFolderRead(folderId: Long): List<Long> = store.tx {
        val ids = sql.query("SELECT id FROM messages WHERE folder_id = ? AND is_read = 0", listOf(folderId)) { it.longOr("id") }
        sql.executeUpdateDelete("UPDATE messages SET is_read = 1 WHERE folder_id = ? AND is_read = 0", listOf(folderId))
        if (ids.isNotEmpty()) store.changed(Tables.MESSAGES)
        ids
    }

    // ---------------------------------------------------------------- attachments

    fun attachment(id: Long): AttachmentLocation? =
        sql.query(
            """
            SELECT a.*, m.account_id AS account_id, m.uid AS uid, f.full_name AS folder_full_name
            FROM attachments a JOIN messages m ON m.id = a.message_id JOIN folders f ON f.id = m.folder_id
            WHERE a.id = ?
            """.trimIndent(),
            listOf(id),
        ) { row ->
            AttachmentLocation(
                attachment = mapAttachment(row),
                accountId = row.longOr("account_id"),
                folderFullName = row.stringOrEmpty("folder_full_name"),
                uid = row.longOr("uid"),
            )
        }.firstOrNull()

    fun attachmentsFor(messageIds: List<Long>): Map<Long, List<Attachment>> {
        if (messageIds.isEmpty()) return emptyMap()
        return messageIds.distinct().chunked(IN_CHUNK).flatMap { chunk ->
            sql.query(
                "SELECT * FROM attachments WHERE message_id IN (${placeholders(chunk.size)}) ORDER BY id",
                chunk,
                ::mapAttachment,
            )
        }.groupBy { it.messageId }
    }

    fun setAttachmentLocalPath(id: Long, path: String?) = store.tx {
        sql.update(Tables.ATTACHMENTS, mapOf("local_path" to path), "id = ?", listOf(id))
        store.changed(Tables.ATTACHMENTS)
    }

    /** Messages of the account with downloaded attachment files. */
    fun idsWithDownloads(accountId: Long): List<Long> =
        sql.query(
            """
            SELECT DISTINCT m.id AS id FROM messages m JOIN attachments a ON a.message_id = m.id
            WHERE m.account_id = ? AND a.local_path IS NOT NULL
            """.trimIndent(),
            listOf(accountId),
        ) { it.longOr("id") }

    /** Ids of messages that still exist, among those having downloaded attachment files. */
    fun existingIds(ids: Collection<Long>): Set<Long> {
        val result = HashSet<Long>()
        for (chunk in ids.distinct().chunked(IN_CHUNK)) {
            sql.query("SELECT id FROM messages WHERE id IN (${placeholders(chunk.size)})", chunk) { it.longOr("id") }
                .forEach { result += it }
        }
        return result
    }

    // ---------------------------------------------------------------- search

    /**
     * Full-text search over subject, people and body (or snippet). [query] is free user text;
     * see [SearchText.matchExpression]. Without a folder filter, Trash and Junk are left out and
     * copies of one message in several folders are returned once.
     */
    fun search(query: String, filters: SearchFilters, limit: Int): List<MessageSummary> {
        val match = SearchText.matchExpression(query) ?: return emptyList()
        val where = StringBuilder("m.id IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH ?) AND m.hidden = 0")
        val args = mutableListOf<Any?>(match)
        filters.accountId?.let { where.append(" AND m.account_id = ?"); args += it }
        if (filters.folderId != null) {
            where.append(" AND m.folder_id = ?")
            args += filters.folderId
        } else {
            where.append(" AND f.role NOT IN ('TRASH', 'JUNK')")
        }
        if (filters.unreadOnly) where.append(" AND m.is_read = 0")
        if (filters.starredOnly) where.append(" AND m.is_starred = 1")
        if (filters.withAttachments) where.append(" AND m.has_attachments = 1")
        args += limit.coerceAtLeast(1) * 2
        val rows = sql.query(
            """
            SELECT $SUMMARY_COLUMNS, m.message_id_header AS mid, 1 AS thread_count
            FROM messages m JOIN folders f ON f.id = m.folder_id
            WHERE $where
            ORDER BY m.date DESC, m.id DESC LIMIT ?
            """.trimIndent(),
            args,
        ) { row -> row.string("mid")?.lowercase() to mapSummary(row) }
        val seen = HashSet<String>()
        return rows.filter { (mid, _) -> mid == null || seen.add(mid) }
            .map { it.second }
            .take(limit.coerceAtLeast(1))
    }

    // ---------------------------------------------------------------- mapping

    internal fun headerValues(accountId: Long, folderId: Long, message: RemoteMessage): Map<String, Any?> =
        mapOf(
            "account_id" to accountId,
            "folder_id" to folderId,
            "uid" to message.uid,
            "message_id_header" to message.messageId,
            "thread_id" to Threading.threadId(message.messageId, message.inReplyTo, message.references, "local-$folderId-${message.uid}"),
            "subject" to message.subject,
            "from_addr" to AddressCodec.encode(message.from),
            "to_addr" to AddressCodec.encode(message.to),
            "cc_addr" to AddressCodec.encode(message.cc),
            "bcc_addr" to AddressCodec.encode(message.bcc),
            "reply_to_addr" to AddressCodec.encode(message.replyTo),
            "date" to effectiveDate(message.sentDate, message.internalDate, store.now()),
            "internal_date" to message.internalDate,
            "size" to message.size,
            "is_read" to message.flags.seen,
            "is_starred" to message.flags.flagged,
            "is_answered" to message.flags.answered,
            "is_forwarded" to message.flags.forwarded,
            "is_draft" to message.flags.draft,
            "has_attachments" to message.hasAttachments,
            "in_reply_to" to message.inReplyTo,
            "refs" to AddressCodec.encodeIds(message.references),
            "list_unsubscribe" to message.listUnsubscribe,
        )

    private fun mapSummary(row: SqlRow): MessageSummary = MessageSummary(
        id = row.longOr("id"),
        accountId = row.longOr("account_id"),
        folderId = row.longOr("folder_id"),
        uid = row.longOr("uid"),
        threadId = row.stringOrEmpty("thread_id"),
        subject = row.stringOrEmpty("subject"),
        from = AddressCodec.decode(row.string("from_addr")).firstOrNull(),
        to = AddressCodec.decode(row.string("to_addr")),
        date = row.longOr("date"),
        snippet = row.stringOrEmpty("snippet"),
        isRead = row.bool("is_read"),
        isStarred = row.bool("is_starred"),
        isAnswered = row.bool("is_answered"),
        isForwarded = row.bool("is_forwarded"),
        isDraft = row.bool("is_draft"),
        hasAttachments = row.bool("has_attachments"),
        threadCount = row.intOr("thread_count", 1),
    )

    /**
     * Bodies are read in slices: Android's CursorWindow (2 MB) cannot hold a single row with a
     * huge HTML body (newsletters with embedded images), so only the first [BODY_CHUNK]
     * characters come with the row and the rest is fetched separately.
     */
    private fun fullBody(row: SqlRow, column: String): String? {
        val head = row.string("${column}_head") ?: return null
        // SQLite counts characters (code points); Kotlin strings count UTF-16 units.
        val length = row.longOr("${column}_len")
        var read = head.codePointCount(0, head.length).toLong()
        if (read >= length) return head
        val id = row.longOr("id")
        val builder = StringBuilder(head)
        while (read < length) {
            val slice = sql.query(
                "SELECT substr($column, ?, ?) AS v FROM messages WHERE id = ?",
                listOf(read + 1, BODY_CHUNK, id),
            ) { it.string("v") }.firstOrNull()
            if (slice.isNullOrEmpty()) break
            builder.append(slice)
            read += slice.codePointCount(0, slice.length)
        }
        return builder.toString()
    }

    private fun mapDetail(row: SqlRow): MessageDetail = MessageDetail(
        summary = mapSummary(row),
        cc = AddressCodec.decode(row.string("cc_addr")),
        bcc = AddressCodec.decode(row.string("bcc_addr")),
        replyTo = AddressCodec.decode(row.string("reply_to_addr")),
        messageIdHeader = row.string("message_id_header"),
        inReplyTo = row.string("in_reply_to"),
        references = AddressCodec.decodeIds(row.string("refs")),
        bodyHtml = fullBody(row, "body_html"),
        bodyText = fullBody(row, "body_text"),
        bodyLoaded = row.bool("body_loaded"),
        attachments = emptyList(),
        listUnsubscribe = row.string("list_unsubscribe"),
        folderRole = FolderDao.roleOf(row.string("folder_role")),
    )

    private fun mapRef(row: SqlRow): MessageRef = MessageRef(
        id = row.longOr("id"),
        accountId = row.longOr("account_id"),
        folderId = row.longOr("folder_id"),
        uid = row.longOr("uid"),
        messageIdHeader = row.string("message_id_header"),
        threadId = row.stringOrEmpty("thread_id"),
        isRead = row.bool("is_read"),
        isStarred = row.bool("is_starred"),
        isAnswered = row.bool("is_answered"),
        isForwarded = row.bool("is_forwarded"),
        isDraft = row.bool("is_draft"),
        hidden = row.bool("hidden"),
        size = row.longOr("size"),
        bodyLoaded = row.bool("body_loaded"),
    )

    private fun mapAttachment(row: SqlRow): Attachment = Attachment(
        id = row.longOr("id"),
        messageId = row.longOr("message_id"),
        partPath = row.stringOrEmpty("part_path"),
        fileName = row.stringOrEmpty("file_name"),
        mimeType = row.stringOrEmpty("mime_type"),
        size = row.longOr("size", -1),
        contentId = row.string("content_id"),
        isInline = row.bool("is_inline"),
        localPath = row.string("local_path"),
    )

    companion object {
        private const val MAX_INDEXED_BODY = 64_000
        private const val ONE_DAY = 86_400_000L

        private const val SUMMARY_COLUMNS =
            "m.id, m.account_id, m.folder_id, m.uid, m.thread_id, m.subject, m.from_addr, m.to_addr, m.date, m.snippet, " +
                "m.is_read, m.is_starred, m.is_answered, m.is_forwarded, m.is_draft, m.has_attachments"

        /** Characters of a body read with its row (at most 4 bytes each in UTF-8). */
        private const val BODY_CHUNK = 150_000

        private const val DETAIL_COLUMNS =
            "$SUMMARY_COLUMNS, 1 AS thread_count, f.role AS folder_role, m.cc_addr, m.bcc_addr, m.reply_to_addr, " +
                "m.message_id_header, m.in_reply_to, m.refs, m.body_loaded, m.list_unsubscribe, " +
                "substr(m.body_html, 1, $BODY_CHUNK) AS body_html_head, length(m.body_html) AS body_html_len, " +
                "substr(m.body_text, 1, $BODY_CHUNK) AS body_text_head, length(m.body_text) AS body_text_len"

        private const val REF_COLUMNS =
            "id, account_id, folder_id, uid, message_id_header, thread_id, is_read, is_starred, is_answered, " +
                "is_forwarded, is_draft, hidden, size, body_loaded"

        /**
         * The Date header, unless missing or more than a day after the arrival time (spam with
         * future dates would otherwise stick to the top of the list).
         */
        fun effectiveDate(sentDate: Long?, internalDate: Long?, now: Long): Long = when {
            sentDate == null || sentDate <= 0 -> internalDate?.takeIf { it > 0 } ?: now
            internalDate != null && internalDate > 0 && sentDate > internalDate + ONE_DAY -> internalDate
            else -> sentDate
        }
    }
}

internal fun MessageRef.flag(flag: LocalFlag): Boolean = when (flag) {
    LocalFlag.READ -> isRead
    LocalFlag.STARRED -> isStarred
    LocalFlag.ANSWERED -> isAnswered
    LocalFlag.FORWARDED -> isForwarded
}
