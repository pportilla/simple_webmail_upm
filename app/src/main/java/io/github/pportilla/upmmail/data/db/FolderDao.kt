package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import kotlinx.coroutines.flow.Flow

/** A folder as reported by the server, with its detected role. */
data class FolderListEntry(
    val fullName: String,
    val displayName: String,
    val separator: Char,
    val attributes: Set<String>,
    val selectable: Boolean,
    val depth: Int,
    val role: FolderRole,
)

/** Synchronisation bookkeeping of a folder that the UI model does not carry. */
data class FolderSyncState(
    val folder: Folder,
    /** UIDVALIDITY seen by the last successful sync; 0 = never synchronised. */
    val uidValidity: Long,
    /**
     * The cache holds every message of the server with uid in windowStartUid..windowEndUid.
     * Messages outside (e.g. added by a server search) are extras checked separately.
     */
    val windowStartUid: Long,
    val windowEndUid: Long,
    /** Whether messages older than the window may exist on the server. */
    val moreAvailable: Boolean,
)

class FolderDao internal constructor(private val store: MailStore) {
    private val sql get() = store.sql

    /** INBOX first, then the other roles, then the rest in tree order (case-insensitive). */
    fun observeByAccount(accountId: Long): Flow<List<Folder>> =
        store.observe(Tables.FOLDERS) { getByAccount(accountId) }

    fun getByAccount(accountId: Long): List<Folder> =
        sql.query("SELECT * FROM folders WHERE account_id = ?", listOf(accountId)) { row -> map(row) to separatorOf(row) }
            .sortedWith(folderOrder)
            .map { it.first }

    fun get(id: Long): Folder? =
        sql.query("SELECT * FROM folders WHERE id = ?", listOf(id), ::map).firstOrNull()

    fun syncState(id: Long): FolderSyncState? =
        sql.query("SELECT * FROM folders WHERE id = ?", listOf(id)) { row ->
            FolderSyncState(
                folder = map(row),
                uidValidity = row.longOr("uid_validity"),
                windowStartUid = row.longOr("window_start_uid"),
                windowEndUid = row.longOr("window_end_uid"),
                moreAvailable = row.bool("more_available"),
            )
        }.firstOrNull()

    fun findByRole(accountId: Long, role: FolderRole): Folder? =
        sql.query(
            "SELECT * FROM folders WHERE account_id = ? AND role = ? ORDER BY selectable DESC, id LIMIT 1",
            listOf(accountId, role),
            ::map,
        ).firstOrNull()

    fun findByFullName(accountId: Long, fullName: String): Folder? =
        sql.query("SELECT * FROM folders WHERE account_id = ? AND full_name = ?", listOf(accountId, fullName), ::map)
            .firstOrNull()

    /**
     * Makes the cached folder list match the server's: new folders are inserted, known ones
     * updated in place (ids stay stable, so selections and pending work survive) and vanished
     * ones deleted together with their messages.
     *
     * When several folders claim the same special role, the one flagged with the SPECIAL-USE
     * attribute (else the shallowest) keeps it and the others become [FolderRole.OTHER].
     */
    fun applyServerList(accountId: Long, entries: List<FolderListEntry>) = store.tx {
        val resolved = resolveRoles(normaliseDepth(entries.distinctBy { it.fullName }))
        val existing = sql.query("SELECT id, full_name FROM folders WHERE account_id = ?", listOf(accountId)) {
            it.stringOrEmpty("full_name") to it.longOr("id")
        }.toMap()
        for (entry in resolved) {
            val values = mapOf(
                "display_name" to entry.displayName,
                "separator" to entry.separator.toString(),
                "role" to entry.role,
                "depth" to entry.depth,
                "selectable" to entry.selectable,
            )
            val id = existing[entry.fullName]
            if (id != null) {
                sql.update(Tables.FOLDERS, values, "id = ?", listOf(id))
            } else {
                sql.insert(Tables.FOLDERS, values + mapOf("account_id" to accountId, "full_name" to entry.fullName))
            }
        }
        val remoteNames = resolved.mapTo(HashSet()) { it.fullName }
        val vanished = existing.filterKeys { it !in remoteNames }.values
        for (id in vanished) deleteInternal(id)
        store.changed(Tables.FOLDERS)
        if (vanished.isNotEmpty()) store.changed(Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
    }

    /** Inserts a folder unknown to the cache (e.g. created on demand); returns its id. */
    fun insert(accountId: Long, entry: FolderListEntry): Long = store.tx {
        val id = sql.insert(
            Tables.FOLDERS,
            mapOf(
                "account_id" to accountId,
                "full_name" to entry.fullName,
                "display_name" to entry.displayName,
                "separator" to entry.separator.toString(),
                "role" to entry.role,
                "depth" to entry.depth,
                "selectable" to entry.selectable,
            ),
            Conflict.IGNORE,
        )
        store.changed(Tables.FOLDERS)
        if (id > 0) id else findByFullName(accountId, entry.fullName)?.id ?: -1
    }

    fun delete(id: Long) = store.tx {
        deleteInternal(id)
        store.changed(Tables.FOLDERS, Tables.MESSAGES, Tables.ATTACHMENTS, Tables.MESSAGES_FTS)
    }

    private fun deleteInternal(id: Long) {
        sql.execSQL("DELETE FROM messages_fts WHERE docid IN (SELECT id FROM messages WHERE folder_id = ?)", listOf(id))
        sql.delete(Tables.FOLDERS, "id = ?", listOf(id))
    }

    /** Stores the server status after a successful sync. */
    fun updateAfterSync(id: Long, uidValidity: Long, unreadCount: Int, totalCount: Int) = store.tx {
        sql.update(
            Tables.FOLDERS,
            mapOf(
                "uid_validity" to uidValidity,
                "unread_count" to unreadCount.coerceAtLeast(0),
                "total_count" to totalCount.coerceAtLeast(0),
                "last_synced_at" to store.now(),
            ),
            "id = ?",
            listOf(id),
        )
        store.changed(Tables.FOLDERS)
    }

    /** Forgets the sync window (after the cache was dropped), so the next sync starts afresh. */
    fun resetSyncState(id: Long) = store.tx {
        sql.update(
            Tables.FOLDERS,
            mapOf("uid_validity" to 0, "window_start_uid" to 0, "window_end_uid" to 0, "more_available" to true),
            "id = ?",
            listOf(id),
        )
        store.changed(Tables.FOLDERS)
    }

    fun setWindow(id: Long, windowStartUid: Long, windowEndUid: Long, moreAvailable: Boolean) = store.tx {
        sql.update(
            Tables.FOLDERS,
            mapOf("window_start_uid" to windowStartUid, "window_end_uid" to windowEndUid, "more_available" to moreAvailable),
            "id = ?",
            listOf(id),
        )
        store.changed(Tables.FOLDERS)
    }

    /** Optimistic adjustment of the counters after a local change (clamped at zero). */
    fun adjustCounts(id: Long, unreadDelta: Int, totalDelta: Int) {
        if (unreadDelta == 0 && totalDelta == 0) return
        store.tx {
            sql.executeUpdateDelete(
                """
                UPDATE folders SET unread_count = MAX(0, unread_count + ?), total_count = MAX(0, total_count + ?)
                WHERE id = ?
                """.trimIndent(),
                listOf(unreadDelta, totalDelta, id),
            )
            store.changed(Tables.FOLDERS)
        }
    }

    fun setUnreadCount(id: Long, unread: Int) = store.tx {
        sql.update(Tables.FOLDERS, mapOf("unread_count" to unread.coerceAtLeast(0)), "id = ?", listOf(id))
        store.changed(Tables.FOLDERS)
    }

    private fun map(row: SqlRow): Folder = Folder(
        id = row.longOr("id"),
        accountId = row.longOr("account_id"),
        fullName = row.stringOrEmpty("full_name"),
        displayName = row.stringOrEmpty("display_name"),
        role = roleOf(row.string("role")),
        depth = row.intOr("depth"),
        unreadCount = row.intOr("unread_count"),
        totalCount = row.intOr("total_count"),
        selectable = row.bool("selectable"),
        lastSyncedAt = row.long("last_synced_at"),
    )

    private fun separatorOf(row: SqlRow): Char = row.string("separator")?.firstOrNull() ?: '/'

    companion object {
        fun roleOf(value: String?): FolderRole = FolderRole.entries.firstOrNull { it.name == value } ?: FolderRole.OTHER

        private val ROLE_ORDER = listOf(
            FolderRole.INBOX,
            FolderRole.DRAFTS,
            FolderRole.SENT,
            FolderRole.ARCHIVE,
            FolderRole.JUNK,
            FolderRole.TRASH,
            FolderRole.OTHER,
        )

        private val SPECIAL_USE = mapOf(
            FolderRole.DRAFTS to "\\drafts",
            FolderRole.SENT to "\\sent",
            FolderRole.ARCHIVE to "\\archive",
            FolderRole.JUNK to "\\junk",
            FolderRole.TRASH to "\\trash",
        )

        private val folderOrder = Comparator<Pair<Folder, Char>> { a, b ->
            val roleA = ROLE_ORDER.indexOf(a.first.role)
            val roleB = ROLE_ORDER.indexOf(b.first.role)
            if (roleA != roleB) return@Comparator roleA - roleB
            val pathA = a.first.fullName.split(a.second)
            val pathB = b.first.fullName.split(b.second)
            for (i in 0 until minOf(pathA.size, pathB.size)) {
                val c = pathA[i].compareTo(pathB[i], ignoreCase = true)
                if (c != 0) return@Comparator c
            }
            pathA.size - pathB.size
        }

        /**
         * Servers with an "INBOX." personal namespace (Courier, some Dovecot setups) report every
         * folder as a child of INBOX; flatten that level so the drawer is not indented throughout.
         */
        internal fun normaliseDepth(entries: List<FolderListEntry>): List<FolderListEntry> {
            val others = entries.filter { !it.fullName.equals("INBOX", ignoreCase = true) }
            if (others.isEmpty()) return entries
            val allUnderInbox = others.all { entry ->
                entry.fullName.length > 6 &&
                    entry.fullName.substring(0, 5).equals("INBOX", ignoreCase = true) &&
                    entry.fullName[5] == entry.separator
            }
            if (!allUnderInbox) return entries
            return entries.map { if (it in others) it.copy(depth = (it.depth - 1).coerceAtLeast(0)) else it }
        }

        internal fun resolveRoles(entries: List<FolderListEntry>): List<FolderListEntry> {
            val result = entries.toMutableList()
            val byRole = entries.withIndex()
                .filter { it.value.role != FolderRole.OTHER }
                .groupBy { it.value.role }
            for ((role, claimants) in byRole) {
                if (claimants.size < 2) continue
                val attribute = SPECIAL_USE[role]
                val winner = claimants.firstOrNull { attribute != null && attribute in it.value.attributes }
                    ?: claimants.firstOrNull { role == FolderRole.INBOX && it.value.fullName.equals("INBOX", ignoreCase = true) }
                    ?: claimants.minWith(compareBy({ it.value.depth }, { it.index }))
                for (claimant in claimants) {
                    if (claimant !== winner) result[claimant.index] = claimant.value.copy(role = FolderRole.OTHER)
                }
            }
            return result
        }
    }
}
