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
}

/**
 * Database schema and migrations.
 *
 * Android 8.0 (minSdk 26) ships SQLite 3.18, so only features available there are used: no
 * window functions, UPSERT, RETURNING, RENAME COLUMN or generated columns, and FTS4 instead of
 * FTS5. The JDBC driver used in unit tests is much newer and would not catch violations.
 */
object MailSchema {
    const val NAME = "upmmail.db"
    const val VERSION = 1

    private val CREATE = listOf(
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

    private val DROP = listOf(
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
     * Step-by-step upgrades keyed by the version they start from. Version 1 is the first
     * release, so there are none yet; add `1 to { db -> ... }` when bumping [VERSION].
     */
    private val MIGRATIONS: Map<Int, (SqlDatabase) -> Unit> = emptyMap()

    fun create(db: SqlDatabase) {
        CREATE.forEach { db.execSQL(it.trimIndent()) }
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
