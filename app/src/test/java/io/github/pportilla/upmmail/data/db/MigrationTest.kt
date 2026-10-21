package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.toIndexedHeader
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {

    @Test
    fun upgradeFromVersion1KeepsEveryRowAndDetachesRowsOutsideTheWindow() {
        val db = JdbcSqlDatabase.inMemoryVersion1()
        val seeded = Version1Fixture.seed(db)
        val countsBefore = Version1Fixture.rowCounts(db)

        db.transaction { MailSchema.migrate(db, 1, MailSchema.VERSION) }

        assertEquals("no row of any table is lost", countsBefore, Version1Fixture.rowCounts(db))
        Version1Fixture.assertMigrated(db, seeded)
    }

    @Test
    fun migratedDatabaseWorksWithTheCurrentDaos() {
        val db = JdbcSqlDatabase.inMemoryVersion1()
        val seeded = Version1Fixture.seed(db)
        db.transaction { MailSchema.migrate(db, 1, MailSchema.VERSION) }
        val store = MailStore(db, TableObserver()) { 1_800_000_000_000L }

        assertEquals("only the window is listed", listOf(200L, 150L, 100L), store.messages.list(seeded.inboxId, 10, conversations = false).map { it.uid })
        assertEquals(listOf(200L, 150L, 100L), store.messages.list(seeded.inboxId, 10, conversations = true).map { it.uid })
        assertEquals("local search still finds every cached message", 5, store.messages.search("asunto", SearchFilters(folderId = seeded.inboxId), 10).size)
        assertEquals("Ana Pérez", store.accounts.get(seeded.accountId)!!.displayName)
        assertEquals("Cuerpo del borrador", store.drafts.get(seeded.draftId)!!.body)

        // The next sync of the window attaches a detached row again when the window grows over it.
        store.messages.upsertHeaders(seeded.accountId, seeded.inboxId, listOf(remoteMessage(250, subject = "asunto 250")))
        assertEquals(listOf(250L, 200L, 150L, 100L), store.messages.list(seeded.inboxId, 10, conversations = false).map { it.uid })

        // Until the server lists them again, the folders' attributes are unknown and none is
        // indexed: one could be a virtual or shared folder.
        assertNull(store.folders.attributesOf(seeded.inboxId))
        assertTrue(store.folders.hasUnlistedFolders(seeded.accountId))
        assertTrue(store.folders.indexableFolders(seeded.accountId).isEmpty())
        assertFalse(store.headerIndex.status().single().complete)

        // The index tables, their trigger and the folder attributes work.
        store.folders.applyServerList(
            seeded.accountId,
            listOf(
                FolderListEntry("INBOX", "INBOX", '/', emptySet(), true, 0, FolderRole.INBOX),
                FolderListEntry("Old", "Old", '/', setOf("\\HasNoChildren"), true, 0, FolderRole.OTHER),
                FolderListEntry("Drafts", "Drafts", '/', setOf("\\Drafts"), true, 0, FolderRole.DRAFTS),
            ),
        )
        assertEquals(setOf("\\hasnochildren"), store.folders.attributesOf(seeded.oldId))
        assertFalse(store.folders.hasUnlistedFolders(seeded.accountId))
        assertEquals("listed: indexable now", listOf("INBOX", "Old"), store.folders.indexableFolders(seeded.accountId).map { it.fullName })
        assertTrue(store.headerIndex.ensureState(seeded.inboxId, 7, 300, 6) != null)
        assertEquals(1, store.headerIndex.insertHeaders(seeded.inboxId, 7, listOf(remoteMessage(40, subject = "Elecciones").toIndexedHeader())))
        assertEquals(listOf(40L), store.headerIndex.search("elecciones", SearchFilters(), 10).map { it.uid })
        store.folders.delete(seeded.inboxId)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
    }

    @Test
    fun thereIsAMigrationForEveryOlderVersion() {
        for (version in 1 until MailSchema.VERSION) assertTrue("missing migration from $version", version in MailSchema.MIGRATIONS)
    }

    @Test
    fun freshAndMigratedSchemasAreIdentical() {
        val fresh = JdbcSqlDatabase.inMemory()
        val migrated = JdbcSqlDatabase.inMemoryVersion1()
        migrated.transaction { MailSchema.migrate(migrated, 1, MailSchema.VERSION) }
        assertEquals(schemaOf(fresh), schemaOf(migrated))
        for (table in tablesOf(fresh)) assertEquals("columns of $table", columnsOf(fresh, table), columnsOf(migrated, table))
    }

    @Test
    fun recreateDropsTheIndexTablesToo() {
        val db = JdbcSqlDatabase.inMemory()
        val store = MailStore(db, TableObserver())
        val account = store.addTestAccount()
        val inbox = store.addTestFolder(account.id, "INBOX", FolderRole.INBOX)
        store.headerIndex.ensureState(inbox.id, 1, 10, 1)
        store.headerIndex.insertHeaders(inbox.id, 1, listOf(remoteMessage(3).toIndexedHeader()))
        val schema = schemaOf(db)

        db.transaction { MailSchema.recreate(db) }

        assertEquals(schema, schemaOf(db))
        assertEquals(0L, db.queryLong("SELECT COUNT(*) AS v FROM header_index"))
        assertEquals(0L, db.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        assertEquals(0L, db.queryLong("SELECT COUNT(*) AS v FROM accounts"))
    }

    private fun schemaOf(db: SqlDatabase): List<String> =
        db.query("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", emptyList()) { row ->
            listOf(row.string("type"), row.string("name"), row.string("tbl_name"), row.string("sql")).joinToString(" | ")
        }

    private fun tablesOf(db: SqlDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", emptyList()) { it.stringOrEmpty("name") }

    private fun columnsOf(db: SqlDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)", emptyList()) { row ->
            listOf(row.string("cid"), row.string("name"), row.string("type"), row.string("notnull"), row.string("dflt_value"), row.string("pk"))
                .joinToString(" | ")
        }
}

/**
 * A database of schema version 1 as the installed app has it, filled with raw SQL (the DAOs write
 * version 2 columns). Shared with [Sqlite318CompatTest].
 */
internal object Version1Fixture {
    class Seeded(val accountId: Long, val inboxId: Long, val oldId: Long, val draftsId: Long, val draftId: Long)

    private val TABLES = listOf(
        "accounts", "folders", "messages", "attachments", "contacts", "pending_ops", "outbox", "outbox_attachments",
        "drafts", "draft_attachments", "recent_searches", "messages_fts",
    )

    fun seed(db: SqlDatabase): Seeded = db.transaction {
        val accountId = db.insert(
            "accounts",
            mapOf(
                "email" to "ana@upm.es", "display_name" to "Ana Pérez", "imap_host" to "correo.upm.es", "imap_port" to 993,
                "imap_security" to "SSL_TLS", "imap_username" to "ana", "smtp_host" to "smtp.upm.es", "smtp_port" to 587,
                "smtp_security" to "STARTTLS", "smtp_username" to "ana@upm.es", "color" to 1, "created_at" to 1_000,
            ),
        )
        fun folder(name: String, role: String, validity: Long, start: Long, end: Long): Long = db.insert(
            "folders",
            mapOf(
                "account_id" to accountId, "full_name" to name, "display_name" to name, "role" to role,
                "uid_validity" to validity, "window_start_uid" to start, "window_end_uid" to end, "total_count" to 5,
            ),
        )
        val inboxId = folder("INBOX", "INBOX", 7, 100, 200)
        val oldId = folder("Old", "OTHER", 0, 0, 0)
        val draftsId = folder("Drafts", "DRAFTS", 9, 1, 50)
        var nextMessage = 0L
        fun message(folderId: Long, uid: Long): Long {
            nextMessage++
            val id = db.insert(
                "messages",
                mapOf(
                    "account_id" to accountId, "folder_id" to folderId, "uid" to uid, "message_id_header" to "<m$nextMessage@x>",
                    "thread_id" to "t$nextMessage", "subject" to "asunto $uid", "from_addr" to "Bea <bea@upm.es>",
                    "date" to 1_700_000_000_000L + uid, "snippet" to "hola",
                ),
            )
            db.execSQL("INSERT INTO messages_fts (docid, subject, people, body) VALUES (?, ?, ?, ?)", listOf(id, "asunto $uid", "bea bea@upm.es", "hola"))
            return id
        }
        // Below, inside (both ends) and above the INBOX window 100..200.
        val below = message(inboxId, 50)
        listOf(100L, 150L, 200L).forEach { message(inboxId, it) }
        message(inboxId, 250)
        message(oldId, 5)
        message(oldId, 6)
        message(draftsId, 10)
        db.insert("attachments", mapOf("message_id" to below, "part_path" to "2", "file_name" to "acta.pdf", "mime_type" to "application/pdf"))
        db.insert("contacts", mapOf("email" to "bea@upm.es", "name" to "Bea", "search_key" to "bea"))
        db.insert("pending_ops", mapOf("account_id" to accountId, "type" to "FLAG", "folder" to "INBOX", "uids" to "150", "created_at" to 1_000))
        val outboxId = db.insert(
            "outbox",
            mapOf(
                "account_id" to accountId, "to_addr" to "bea@upm.es", "subject" to "Pendiente", "mode" to "NEW", "state" to "QUEUED",
                "send_at" to 2_000, "created_at" to 1_000, "state_changed_at" to 1_000,
            ),
        )
        db.insert("outbox_attachments", mapOf("outbox_id" to outboxId, "position" to 0, "uri" to "file:///a", "file_name" to "a", "mime_type" to "text/plain"))
        val draftId = db.insert(
            "drafts",
            mapOf("account_id" to accountId, "subject" to "Borrador", "body" to "Cuerpo del borrador", "mode" to "NEW", "updated_at" to 1_000),
        )
        db.insert("draft_attachments", mapOf("draft_id" to draftId, "position" to 0, "uri" to "file:///b", "file_name" to "b", "mime_type" to "text/plain"))
        db.insert("recent_searches", mapOf("query" to "acta", "used_at" to 1_000))
        Seeded(accountId, inboxId, oldId, draftsId, draftId)
    }

    fun rowCounts(db: SqlDatabase): Map<String, Long> = TABLES.associateWith { db.queryLong("SELECT COUNT(*) AS v FROM $it") ?: -1 }

    /** Checks the version 2 columns of a database made by [seed] and then migrated. */
    fun assertMigrated(db: SqlDatabase, seeded: Seeded) {
        val detached = db.query("SELECT folder_id, uid, detached_at FROM messages ORDER BY folder_id, uid", emptyList()) { row ->
            Triple(row.longOr("folder_id"), row.longOr("uid"), row.long("detached_at"))
        }
        val expected = listOf(
            Triple(seeded.inboxId, 50L, 0L),
            Triple(seeded.inboxId, 100L, null),
            Triple(seeded.inboxId, 150L, null),
            Triple(seeded.inboxId, 200L, null),
            Triple(seeded.inboxId, 250L, 0L),
            Triple(seeded.oldId, 5L, 0L),
            Triple(seeded.oldId, 6L, 0L),
            Triple(seeded.draftsId, 10L, null),
        )
        assertEquals("rows outside the window and of never synced folders are detached", expected, detached)
        assertEquals("not listed yet", listOf(null), db.query("SELECT DISTINCT attributes FROM folders", emptyList()) { it.string("attributes") })
        for (table in listOf("header_index", "header_index_fts", "header_index_state")) {
            assertEquals("$table starts empty", 0L, db.queryLong("SELECT COUNT(*) AS v FROM $table"))
        }
        assertEquals(
            listOf("header_index_fts_delete", "messages_fts_delete"),
            db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name", emptyList()) { it.stringOrEmpty("name") },
        )
        assertEquals(
            "the other columns are untouched",
            listOf("asunto 150", "Bea <bea@upm.es>", "hola"),
            db.query("SELECT subject, from_addr, snippet FROM messages WHERE uid = 150", emptyList()) { row ->
                listOf(row.string("subject"), row.string("from_addr"), row.string("snippet"))
            }.single(),
        )
        assertEquals("ana@upm.es", db.query("SELECT email FROM accounts WHERE id = ?", listOf(seeded.accountId)) { it.string("email") }.single())
    }
}
