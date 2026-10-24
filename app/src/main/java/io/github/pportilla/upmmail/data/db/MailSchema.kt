package io.github.pportilla.upmmail.data.db

/** Table names, used both in SQL and as invalidation keys for [TableObserver]. */
object Tables {
    const val ACCOUNTS = "accounts"
    const val FOLDERS = "folders"
    const val MESSAGES = "messages"
    const val ATTACHMENTS = "attachments"
    const val CONTACTS = "contacts"
    const val PENDING_OPS = "pending_ops"
    const val OUTBOX = "outbox"
    const val OUTBOX_ATTACHMENTS = "outbox_attachments"
    const val DRAFTS = "drafts"
    const val DRAFT_ATTACHMENTS = "draft_attachments"
    const val RECENT_SEARCHES = "recent_searches"
    const val MESSAGES_FTS = "messages_fts"
    const val HEADER_INDEX = "header_index"
    const val HEADER_INDEX_FTS = "header_index_fts"
    const val HEADER_INDEX_STATE = "header_index_state"
}

/**
 * Database schema and migrations.
 *
 * Android 8.0 (minSdk 26) ships SQLite 3.18, so only features available there are used: no
 * window functions, UPSERT, RETURNING, RENAME COLUMN or generated columns, and FTS4 instead of
 * FTS5. Sqlite318CompatTest runs the schema and the DAO queries on SQLite 3.18 to catch violations
 * the much newer JDBC driver of the other unit tests would accept.
 *
 * A fresh database is built exactly like an upgraded one: version 1 followed by every migration.
 */
object MailSchema {
    const val NAME = "upmmail.db"
    const val VERSION = 4

    /** Version 1 exactly as shipped. Never edit: installed databases were created from it. */
    private val CREATE_V1 = listOf(
        """
        CREATE TABLE accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            email TEXT NOT NULL COLLATE NOCASE UNIQUE,
            display_name TEXT NOT NULL DEFAULT '',
            imap_host TEXT NOT NULL,
            imap_port INTEGER NOT NULL,
            imap_security TEXT NOT NULL,
            imap_username TEXT NOT NULL,
            smtp_host TEXT NOT NULL,
            smtp_port INTEGER NOT NULL,
            smtp_security TEXT NOT NULL,
            smtp_username TEXT NOT NULL,
            signature TEXT NOT NULL DEFAULT '',
            color INTEGER NOT NULL,
            notifications_enabled INTEGER NOT NULL DEFAULT 1,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE folders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            full_name TEXT NOT NULL,
            display_name TEXT NOT NULL,
            separator TEXT NOT NULL DEFAULT '/',
            role TEXT NOT NULL,
            depth INTEGER NOT NULL DEFAULT 0,
            selectable INTEGER NOT NULL DEFAULT 1,
            uid_validity INTEGER NOT NULL DEFAULT 0,
            unread_count INTEGER NOT NULL DEFAULT 0,
            total_count INTEGER NOT NULL DEFAULT 0,
            window_start_uid INTEGER NOT NULL DEFAULT 0,
            window_end_uid INTEGER NOT NULL DEFAULT 0,
            more_available INTEGER NOT NULL DEFAULT 1,
            last_synced_at INTEGER,
            UNIQUE (account_id, full_name)
        )
        """,
        """
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
            uid INTEGER NOT NULL,
            message_id_header TEXT,
            thread_id TEXT NOT NULL,
            subject TEXT NOT NULL DEFAULT '',
            from_addr TEXT NOT NULL DEFAULT '',
            to_addr TEXT NOT NULL DEFAULT '',
            cc_addr TEXT NOT NULL DEFAULT '',
            bcc_addr TEXT NOT NULL DEFAULT '',
            reply_to_addr TEXT NOT NULL DEFAULT '',
            date INTEGER NOT NULL,
            internal_date INTEGER,
            size INTEGER NOT NULL DEFAULT 0,
            is_read INTEGER NOT NULL DEFAULT 0,
            is_starred INTEGER NOT NULL DEFAULT 0,
            is_answered INTEGER NOT NULL DEFAULT 0,
            is_forwarded INTEGER NOT NULL DEFAULT 0,
            is_draft INTEGER NOT NULL DEFAULT 0,
            has_attachments INTEGER NOT NULL DEFAULT 0,
            snippet TEXT NOT NULL DEFAULT '',
            body_html TEXT,
            body_text TEXT,
            body_loaded INTEGER NOT NULL DEFAULT 0,
            in_reply_to TEXT,
            refs TEXT NOT NULL DEFAULT '',
            list_unsubscribe TEXT,
            hidden INTEGER NOT NULL DEFAULT 0,
            UNIQUE (folder_id, uid)
        )
        """,
        "CREATE INDEX messages_folder_date ON messages (folder_id, date)",
        "CREATE INDEX messages_folder_thread ON messages (folder_id, thread_id, date)",
        "CREATE INDEX messages_account_thread ON messages (account_id, thread_id)",
        "CREATE INDEX messages_message_id ON messages (message_id_header)",
        """
        CREATE TABLE attachments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            part_path TEXT NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size INTEGER NOT NULL DEFAULT -1,
            content_id TEXT,
            is_inline INTEGER NOT NULL DEFAULT 0,
            local_path TEXT,
            UNIQUE (message_id, part_path)
        )
        """,
        """
        CREATE TABLE contacts (
            email TEXT PRIMARY KEY NOT NULL,
            name TEXT,
            search_key TEXT NOT NULL DEFAULT '',
            times_contacted INTEGER NOT NULL DEFAULT 0,
            times_seen INTEGER NOT NULL DEFAULT 0,
            last_used INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE pending_ops (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            type TEXT NOT NULL,
            folder TEXT NOT NULL DEFAULT '',
            uids TEXT NOT NULL DEFAULT '',
            message_ids TEXT NOT NULL DEFAULT '',
            target TEXT,
            flag TEXT,
            value INTEGER NOT NULL DEFAULT 0,
            payload TEXT,
            undo_group INTEGER,
            state INTEGER NOT NULL DEFAULT 0,
            not_before INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX pending_ops_account ON pending_ops (account_id, id)",
        "CREATE INDEX pending_ops_group ON pending_ops (undo_group)",
        """
        CREATE TABLE outbox (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            to_addr TEXT NOT NULL DEFAULT '',
            cc_addr TEXT NOT NULL DEFAULT '',
            bcc_addr TEXT NOT NULL DEFAULT '',
            subject TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL DEFAULT '',
            in_reply_to TEXT,
            refs TEXT NOT NULL DEFAULT '',
            mode TEXT NOT NULL,
            source_message_id INTEGER,
            server_draft_message_id INTEGER,
            server_folder TEXT,
            server_uid INTEGER,
            server_msgid TEXT,
            state TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            send_at INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            state_changed_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX outbox_state ON outbox (state, send_at)",
        """
        CREATE TABLE outbox_attachments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            outbox_id INTEGER NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            uri TEXT NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size INTEGER NOT NULL DEFAULT -1
        )
        """,
        "CREATE INDEX outbox_attachments_outbox ON outbox_attachments (outbox_id)",
        """
        CREATE TABLE drafts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            to_addr TEXT NOT NULL DEFAULT '',
            cc_addr TEXT NOT NULL DEFAULT '',
            bcc_addr TEXT NOT NULL DEFAULT '',
            subject TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL DEFAULT '',
            in_reply_to TEXT,
            refs TEXT NOT NULL DEFAULT '',
            mode TEXT NOT NULL,
            source_message_id INTEGER,
            server_draft_message_id INTEGER,
            server_folder TEXT,
            server_uid INTEGER,
            server_msgid TEXT,
            updated_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE draft_attachments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            draft_id INTEGER NOT NULL REFERENCES drafts(id) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            uri TEXT NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size INTEGER NOT NULL DEFAULT -1
        )
        """,
        "CREATE INDEX draft_attachments_draft ON draft_attachments (draft_id)",
        """
        CREATE TABLE recent_searches (
            query TEXT PRIMARY KEY NOT NULL COLLATE NOCASE,
            used_at INTEGER NOT NULL
        )
        """,
        // Text is folded (lower case, no diacritics) in Kotlin before indexing; unicode61 then
        // only has to split words. docid = messages.id.
        "CREATE VIRTUAL TABLE messages_fts USING fts4(subject, people, body, tokenize=unicode61)",
        // Covers cascaded deletes (folder or account removed) that DAO code never sees.
        """
        CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN
            DELETE FROM messages_fts WHERE docid = old.id;
        END
        """,
    )

    /**
     * Version 2: the header index used by search, and detached messages (cached rows outside the
     * sync window, hidden from the mailbox lists). Each statement is one element: the framework
     * runs one statement per execSQL.
     */
    private val V1_TO_V2 = listOf(
        // NULL: inside the sync window, listed. Otherwise the time of the last use (0 = unknown,
        // evicted first) of a row kept for a search result or an opened message.
        "ALTER TABLE messages ADD COLUMN detached_at INTEGER",
        // Lower-case IMAP attributes of the folder, sorted and space separated. NULL: not listed
        // since this upgrade, so whether the folder is virtual or shared (and indexed) is unknown.
        "ALTER TABLE folders ADD COLUMN attributes TEXT",
        // Rows a server search added below the window, or cached for a folder never synced, are
        // the detached rows of version 2.
        """
        UPDATE messages SET detached_at = 0
        WHERE EXISTS (
            SELECT 1 FROM folders f WHERE f.id = messages.folder_id
                AND (f.uid_validity = 0 OR messages.uid < f.window_start_uid OR messages.uid > f.window_end_uid)
        )
        """,
        // Headers of messages that are not (necessarily) cached, so local search finds mail
        // outside the sync window. to_addr is kept for the Sent folder only.
        """
        CREATE TABLE header_index (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
            uid INTEGER NOT NULL,
            message_id_header TEXT,
            subject TEXT NOT NULL DEFAULT '',
            from_addr TEXT NOT NULL DEFAULT '',
            to_addr TEXT NOT NULL DEFAULT '',
            date INTEGER NOT NULL,
            internal_date INTEGER,
            size INTEGER NOT NULL DEFAULT 0,
            is_read INTEGER NOT NULL DEFAULT 0,
            is_starred INTEGER NOT NULL DEFAULT 0,
            is_answered INTEGER NOT NULL DEFAULT 0,
            has_attachments INTEGER NOT NULL DEFAULT 0,
            UNIQUE (folder_id, uid)
        )
        """,
        "CREATE INDEX header_index_date ON header_index (date)",
        // Folded text like messages_fts; docid = header_index.id.
        "CREATE VIRTUAL TABLE header_index_fts USING fts4(subject, people, tokenize=unicode61)",
        // Persistent: keeps the segment count low while the index is filled in many small batches.
        "INSERT INTO header_index_fts(header_index_fts) VALUES('automerge=8')",
        // Covers every delete, cascades included. Never insert into header_index with OR REPLACE:
        // the rows it deletes would not fire this trigger.
        """
        CREATE TRIGGER header_index_fts_delete AFTER DELETE ON header_index BEGIN
            DELETE FROM header_index_fts WHERE docid = old.id;
        END
        """,
        // Per indexed folder: how far the index is known to match the server.
        """
        CREATE TABLE header_index_state (
            folder_id INTEGER PRIMARY KEY REFERENCES folders(id) ON DELETE CASCADE,
            account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            uid_validity INTEGER NOT NULL,
            uid_next INTEGER NOT NULL,
            server_count INTEGER NOT NULL DEFAULT 0,
            indexed_count INTEGER NOT NULL DEFAULT 0,
            complete INTEGER NOT NULL DEFAULT 0,
            capped INTEGER NOT NULL DEFAULT 0,
            dirty INTEGER NOT NULL DEFAULT 0,
            checked_at INTEGER,
            updated_at INTEGER
        )
        """,
    )

    /**
     * Version 3: the people of both full-text tables split into a sender and a recipients column,
     * so `from:` and `to:` match only their own side. FTS4 tables cannot gain columns: both are
     * rebuilt (see [rebuildFullText]), without losing an entry or the indexed bodies. One pass
     * over the tables; about a second per 50,000 index rows on a JVM (SearchMigrationTest).
     */
    private fun upgradeToVersion3(db: SqlDatabase) {
        // Triggers go first: renaming a table that a trigger names fails on recent SQLite versions.
        db.execSQL("DROP TRIGGER messages_fts_delete")
        db.execSQL("DROP TRIGGER header_index_fts_delete")
        db.execSQL("ALTER TABLE messages_fts RENAME TO messages_fts_v2")
        db.execSQL("ALTER TABLE header_index_fts RENAME TO header_index_fts_v2")
        // Folded text like before; docid = messages.id. Recipients are To and Cc, as in the index
        // and the server's search: never Bcc, known for our own mail only.
        db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(subject, sender, recipients, body, tokenize=unicode61)")
        // docid = header_index.id. Recipients are To and Cc, of every folder.
        db.execSQL("CREATE VIRTUAL TABLE header_index_fts USING fts4(subject, sender, recipients, tokenize=unicode61)")
        rebuildFullText(db)
        db.execSQL("DROP TABLE messages_fts_v2")
        db.execSQL("DROP TABLE header_index_fts_v2")
        // Persistent, as in version 2; set after the bulk copy so that it is not slowed down.
        db.execSQL("INSERT INTO header_index_fts(header_index_fts) VALUES('automerge=8')")
        db.execSQL(
            """
            CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN
                DELETE FROM messages_fts WHERE docid = old.id;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER header_index_fts_delete AFTER DELETE ON header_index BEGIN
                DELETE FROM header_index_fts WHERE docid = old.id;
            END
            """.trimIndent(),
        )
    }

    /**
     * Fills the version 3 full-text tables from the renamed version 2 ones: one entry per row of
     * `messages` and of `header_index` (an orphan entry is dropped, a missing one is made from
     * the row).
     *
     * Messages keep their indexed body (it may be all that is left of a dropped body); their
     * people come from the address columns. Index rows keep their subject and people texts; the
     * people text has the sender first, and [FtsPeople.senderEnd] finds where it ends, so the
     * recipients of every folder survive although `to_addr` holds them for the Sent folder only.
     *
     * The index may hold 100,000 rows per account, so the work per row is kept small: Kotlin
     * only reads the people text and notes where to cut it, in a temporary table filled a page at
     * a time, and one INSERT ... SELECT per table copies the texts inside SQLite, in docid order
     * (out of order inserts make FTS4 flush its pending terms each time).
     */
    private fun rebuildFullText(db: SqlDatabase) {
        db.execSQL("CREATE TEMP TABLE fts_rebuild (docid INTEGER PRIMARY KEY, cut INTEGER, subject TEXT, sender TEXT, recipients TEXT)")
        var after = 0L
        while (true) {
            val rows = db.query(
                "SELECT id, subject, from_addr, to_addr, cc_addr FROM messages WHERE id > ? ORDER BY id LIMIT ?",
                listOf(after, REBUILD_PAGE),
            ) { row ->
                listOf<Any?>(
                    row.longOr("id"),
                    null,
                    SearchText.fold(row.stringOrEmpty("subject")),
                    FtsPeople.ofStored(row.string("from_addr")),
                    FtsPeople.ofStored(row.string("to_addr"), row.string("cc_addr")),
                )
            }
            insertRebuildRows(db, rows)
            if (rows.size < REBUILD_PAGE) break
            after = rows.last()[0] as Long
        }
        db.execSQL(
            """
            INSERT INTO messages_fts (docid, subject, sender, recipients, body)
            SELECT r.docid, r.subject, r.sender, r.recipients, COALESCE(o.body, '')
            FROM fts_rebuild r LEFT JOIN messages_fts_v2 o ON o.docid = r.docid
            ORDER BY r.docid
            """.trimIndent(),
        )
        db.execSQL("DELETE FROM fts_rebuild")

        // Many rows share a sender: its words are worked out once.
        val senderWords = HashMap<String, List<String>>()
        after = 0L
        while (true) {
            val rows = db.query(
                """
                SELECT h.id, h.subject, h.from_addr, h.to_addr, o.people FROM header_index h
                LEFT JOIN header_index_fts_v2 o ON o.docid = h.id
                WHERE h.id > ? ORDER BY h.id LIMIT ?
                """.trimIndent(),
                listOf(after, REBUILD_PAGE),
            ) { row ->
                val id = row.longOr("id")
                val fromAddr = row.stringOrEmpty("from_addr")
                val people = row.string("people")
                if (senderWords.size > SENDER_CACHE) senderWords.clear()
                val end = if (people == null) -1 else FtsPeople.senderEnd(people, senderWords.getOrPut(fromAddr) { SearchText.words(fromAddr) })
                when {
                    // The usual case: the texts are cut and copied by SQLite (substr counts code points).
                    end >= 0 -> listOf<Any?>(id, people!!.codePointCount(0, end), null, null, null)
                    people != null -> listOf<Any?>(id, null, null, FtsPeople.ofStored(fromAddr), people)
                    else -> listOf<Any?>(
                        id, null, SearchText.fold(row.stringOrEmpty("subject")), FtsPeople.ofStored(fromAddr), FtsPeople.ofStored(row.string("to_addr")),
                    )
                }
            }
            insertRebuildRows(db, rows)
            if (rows.size < REBUILD_PAGE) break
            after = rows.last()[0] as Long
        }
        db.execSQL(
            """
            INSERT INTO header_index_fts (docid, subject, sender, recipients)
            SELECT r.docid, COALESCE(r.subject, o.subject, ''),
                COALESCE(r.sender, trim(substr(o.people, 1, r.cut))),
                COALESCE(r.recipients, trim(substr(o.people, r.cut + 1)))
            FROM fts_rebuild r LEFT JOIN header_index_fts_v2 o ON o.docid = r.docid
            ORDER BY r.docid
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE fts_rebuild")
    }

    /** Inserts [rows] (docid, cut, subject, sender, recipients) into `fts_rebuild`, many per statement. */
    private fun insertRebuildRows(db: SqlDatabase, rows: List<List<Any?>>) {
        for (chunk in rows.chunked(REBUILD_ROWS_PER_INSERT)) {
            db.execSQL(
                "INSERT INTO fts_rebuild (docid, cut, subject, sender, recipients) VALUES " +
                    List(chunk.size) { "(?, ?, ?, ?, ?)" }.joinToString(", "),
                chunk.flatten(),
            )
        }
    }

    /** Rows read at a time by [rebuildFullText]. */
    private const val REBUILD_PAGE = 1_000

    /** Rows per INSERT into `fts_rebuild`: 5 arguments each, within SQLite's limit of 999. */
    private const val REBUILD_ROWS_PER_INSERT = 150

    /** Senders whose words [rebuildFullText] keeps at a time. */
    private const val SENDER_CACHE = 20_000

    private val DROP = listOf(
        "DROP TRIGGER IF EXISTS header_index_fts_delete",
        "DROP TABLE IF EXISTS header_index_fts",
        "DROP TABLE IF EXISTS header_index",
        "DROP TABLE IF EXISTS header_index_state",
        "DROP TRIGGER IF EXISTS messages_fts_delete",
        "DROP TABLE IF EXISTS messages_fts",
        "DROP TABLE IF EXISTS recent_searches",
        "DROP TABLE IF EXISTS draft_attachments",
        "DROP TABLE IF EXISTS drafts",
        "DROP TABLE IF EXISTS outbox_attachments",
        "DROP TABLE IF EXISTS outbox",
        "DROP TABLE IF EXISTS pending_ops",
        "DROP TABLE IF EXISTS contacts",
        "DROP TABLE IF EXISTS attachments",
        "DROP TABLE IF EXISTS messages",
        "DROP TABLE IF EXISTS folders",
        "DROP TABLE IF EXISTS accounts",
    )

    /**
     * Step-by-step upgrades keyed by the version they start from; there must be one for every
     * version below [VERSION]. They run inside the framework's upgrade transaction: if one throws,
     * the database stays at its old version, but the app cannot open it until a fixed build
     * ships, so every step must also pass Sqlite318CompatTest.
     */
    internal val MIGRATIONS: Map<Int, (SqlDatabase) -> Unit> = mapOf(
        1 to { db -> V1_TO_V2.forEach { db.execSQL(it.trimIndent()) } },
        2 to ::upgradeToVersion3,
        3 to { db -> db.execSQL("ALTER TABLE accounts ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0") },
    )

    fun create(db: SqlDatabase) {
        createVersion1(db)
        for (version in 1 until VERSION) MIGRATIONS.getValue(version)(db)
    }

    /** The schema of version 1, for [create] and for migration tests. */
    internal fun createVersion1(db: SqlDatabase) {
        CREATE_V1.forEach { db.execSQL(it.trimIndent()) }
    }

    /** Applies the migrations from [from] to [to]; recreates the schema if a step is missing. */
    fun migrate(db: SqlDatabase, from: Int, to: Int) {
        var version = from
        while (version < to) {
            val step = MIGRATIONS[version]
            if (step == null) {
                recreate(db)
                return
            }
            step(db)
            version++
        }
    }

    /** Drops everything and creates the current schema (downgrades, missing migrations). */
    fun recreate(db: SqlDatabase) {
        DROP.forEach { db.execSQL(it) }
        create(db)
    }
}
