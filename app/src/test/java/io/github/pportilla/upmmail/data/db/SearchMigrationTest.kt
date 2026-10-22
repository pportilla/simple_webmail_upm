package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Version 3 splits the people of both full-text tables into sender and recipients. */
class SearchMigrationTest {

    @Test
    fun upgradeFromVersion2KeepsEveryRowAndSplitsThePeople() {
        val db = JdbcSqlDatabase.inMemoryVersion1()
        db.transaction { MailSchema.migrate(db, 1, 2) }
        val seeded = Version2Fixture.seed(db)
        val counts = Version2Fixture.rowCounts(db)

        db.transaction { MailSchema.migrate(db, 2, MailSchema.VERSION) }

        assertEquals("no row of any table is lost", counts, Version2Fixture.rowCounts(db))
        Version2Fixture.assertMigrated(db, seeded)
    }

    @Test
    fun theMigratedDatabaseIsTheFreshSchema() {
        val fresh = JdbcSqlDatabase.inMemory()
        val migrated = JdbcSqlDatabase.inMemoryVersion1()
        migrated.transaction { MailSchema.migrate(migrated, 1, 2) }
        Version2Fixture.seed(migrated)
        migrated.transaction { MailSchema.migrate(migrated, 2, MailSchema.VERSION) }
        val schema = "SELECT type, name, sql FROM sqlite_master ORDER BY type, name"
        val rows = { db: SqlDatabase -> db.query(schema, emptyList()) { "${it.string("type")} ${it.string("name")} ${it.string("sql")}" } }
        assertEquals(rows(fresh), rows(migrated))
    }

    /**
     * About the size of a big UPM mailbox. The upgrade runs once, when the app first opens the
     * database after the update; a device takes a few times longer than this JVM.
     */
    @Test
    fun upgradeOfFiftyThousandIndexRowsIsQuick() {
        val db = JdbcSqlDatabase.inMemoryVersion1()
        db.transaction { MailSchema.migrate(db, 1, 2) }
        val seeded = Version2Fixture.seed(db, extraIndexRows = 50_000)
        val start = System.nanoTime()
        db.transaction { MailSchema.migrate(db, 2, MailSchema.VERSION) }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("Upgrade of ${50_000 + Version2Fixture.INDEX_ROWS} index rows to version 3: $millis ms")
        assertTrue("took $millis ms", millis < 8_000)
        assertEquals(50_000L + Version2Fixture.INDEX_ROWS, db.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        Version2Fixture.assertMigrated(db, seeded)
    }
}

/**
 * A database of schema version 2 as the installed app has it: cached messages and header index
 * rows with their full-text entries, written with raw SQL exactly as version 2 wrote them (the
 * DAOs write version 3). Shared with [Sqlite318CompatTest].
 */
internal object Version2Fixture {
    class Seeded(val accountId: Long, val inboxId: Long, val archiveId: Long, val sentId: Long, val indexIds: Map<String, Long>)

    /** Rows [seed] writes to `header_index` besides the extra ones. */
    const val INDEX_ROWS = 6

    private val TABLES = listOf(
        "accounts", "folders", "messages", "attachments", "messages_fts", "header_index", "header_index_fts", "header_index_state",
    )

    private val ana = EmailAddress("ana.garcia@upm.es", "Ana García")
    private val luis = EmailAddress("luis.perez@upm.es", "Luis Pérez")
    private val marta = EmailAddress("marta@alumnos.upm.es", "Marta López")
    private val carlos = EmailAddress("carlos.nunez@upm.es", "Carlos Núñez")
    private val me = EmailAddress("yo@upm.es", "Yo")

    /** The "people" text of version 2: sender, To and Cc (at most 200 each), folded, at most 8,000 characters. */
    private fun people(from: EmailAddress?, to: List<EmailAddress>, cc: List<EmailAddress> = emptyList()): String =
        SearchText.fold(AddressCodec.people(listOfNotNull(from), to.take(200), cc.take(200))).take(8_000)

    fun seed(db: SqlDatabase, extraIndexRows: Int = 0): Seeded = db.transaction {
        val accountId = db.insert(
            "accounts",
            mapOf(
                "email" to "yo@upm.es", "display_name" to "Yo", "imap_host" to "correo.upm.es", "imap_port" to 993,
                "imap_security" to "SSL_TLS", "imap_username" to "yo", "smtp_host" to "smtp.upm.es", "smtp_port" to 587,
                "smtp_security" to "STARTTLS", "smtp_username" to "yo@upm.es", "color" to 1, "created_at" to 1_000,
            ),
        )
        fun folder(name: String, role: String): Long {
            val id = db.insert(
                "folders",
                mapOf(
                    "account_id" to accountId, "full_name" to name, "display_name" to name, "role" to role, "uid_validity" to 7,
                    "window_start_uid" to 10, "window_end_uid" to 20, "total_count" to 20, "attributes" to "",
                ),
            )
            db.insert(
                "header_index_state",
                mapOf("folder_id" to id, "account_id" to accountId, "uid_validity" to 7, "uid_next" to 100_000, "complete" to 1),
            )
            return id
        }
        val inboxId = folder("INBOX", "INBOX")
        val archiveId = folder("Archive", "ARCHIVE")
        val sentId = folder("Sent", "SENT")

        fun message(folderId: Long, uid: Long, from: EmailAddress, to: List<EmailAddress>, subject: String, body: String, bcc: List<EmailAddress> = emptyList()) {
            val id = db.insert(
                "messages",
                mapOf(
                    "account_id" to accountId, "folder_id" to folderId, "uid" to uid, "message_id_header" to "<c$uid@upm.es>",
                    "thread_id" to "t$uid", "subject" to subject, "from_addr" to AddressCodec.encode(from),
                    "to_addr" to AddressCodec.encode(to), "bcc_addr" to AddressCodec.encode(bcc), "date" to 1_700_000_000_000L + uid,
                    "snippet" to body.take(20),
                ),
            )
            db.execSQL(
                "INSERT INTO messages_fts (docid, subject, people, body) VALUES (?, ?, ?, ?)",
                listOf(id, SearchText.fold(subject), people(from, to), SearchText.fold(body)),
            )
        }
        // Cached: its body was dropped from `messages`, only the full-text entry has it.
        message(inboxId, 10, ana, listOf(luis), "Acta de la reunión", "El cuerpo habla del presupuesto")
        message(inboxId, 11, luis, listOf(ana), "Re: Acta de la reunión", "Gracias")
        message(sentId, 12, me, listOf(luis), "Aviso", "Nos vemos", bcc = listOf(carlos))

        val indexIds = HashMap<String, Long>()
        fun indexRow(key: String, folderId: Long, uid: Long, from: EmailAddress?, to: List<EmailAddress>, subject: String, cc: List<EmailAddress> = emptyList(), sentFolder: Boolean = false) {
            val id = db.insert(
                "header_index",
                mapOf(
                    "account_id" to accountId, "folder_id" to folderId, "uid" to uid, "message_id_header" to "<h$uid@upm.es>",
                    "subject" to subject, "from_addr" to AddressCodec.encode(from),
                    "to_addr" to if (sentFolder) AddressCodec.encode(to.take(5)) else "", "date" to 1_600_000_000_000L + uid,
                ),
            )
            db.execSQL(
                "INSERT INTO header_index_fts (docid, subject, people) VALUES (?, ?, ?)",
                listOf(id, SearchText.fold(subject), people(from, to, cc)),
            )
            indexIds[key] = id
        }
        indexRow("ana", inboxId, 1, ana, listOf(luis), "Elecciones a la Junta", cc = listOf(marta))
        indexRow("luis", inboxId, 2, luis, listOf(ana), "Re: Elecciones")
        indexRow("daemon", archiveId, 3, EmailAddress("MAILER-DAEMON"), listOf(ana), "Undelivered Mail")
        indexRow("nobody", archiveId, 4, null, listOf(luis), "Sin remitente")
        val many = (1..250).map { EmailAddress("persona$it@upm.es", "Persona $it") }
        indexRow("sent", sentId, 5, me, listOf(carlos), "Convocatoria", cc = many, sentFolder = true)
        indexRow("comma", inboxId, 6, EmailAddress("ana.garcia@upm.es", "García, Ana"), listOf(luis), "Horario")
        check(indexIds.size == INDEX_ROWS)

        val words = listOf("reunión", "acta", "convocatoria", "examen", "horario", "proyecto", "beca", "tesis", "presupuesto", "plazo")
        val names = listOf("Elena Martín", "José Núñez", "Marta López", "Pablo Ruiz", "Lucía Gómez", "Javier Sanz")
        val random = Random(3)
        for (n in 1..extraIndexRows) {
            val folderId = if (n % 4 == 0) archiveId else inboxId
            val name = names[random.nextInt(names.size)]
            val from = EmailAddress(SearchText.fold(name).replace(' ', '.') + "@upm.es", name)
            val to = List(1 + random.nextInt(3)) { names[random.nextInt(names.size)].let { EmailAddress(SearchText.fold(it).replace(' ', '.') + "@upm.es", it) } }
            val subject = words[random.nextInt(words.size)] + " de " + words[random.nextInt(words.size)] + " " + n
            val id = db.insert(
                "header_index",
                mapOf(
                    "account_id" to accountId, "folder_id" to folderId, "uid" to 1_000L + n, "message_id_header" to "<x$n@upm.es>",
                    "subject" to subject, "from_addr" to AddressCodec.encode(from), "date" to 1_500_000_000_000L + n * 60_000L,
                ),
            )
            db.execSQL("INSERT INTO header_index_fts (docid, subject, people) VALUES (?, ?, ?)", listOf(id, SearchText.fold(subject), people(from, to)))
        }
        Seeded(accountId, inboxId, archiveId, sentId, indexIds)
    }

    fun rowCounts(db: SqlDatabase): Map<String, Long> = TABLES.associateWith { db.queryLong("SELECT COUNT(*) AS v FROM $it") ?: -1 }

    /** Checks the version 3 full-text entries of a database made by [seed] and then upgraded. */
    fun assertMigrated(db: SqlDatabase, seeded: Seeded) {
        val split = db.query(
            "SELECT docid, sender, recipients FROM header_index_fts WHERE docid IN (${seeded.indexIds.values.joinToString(",")})",
            emptyList(),
        ) { row -> row.longOr("docid") to (row.stringOrEmpty("sender") to row.stringOrEmpty("recipients")) }.toMap()
        val ids = seeded.indexIds
        assertEquals("ana garcia ana.garcia@upm.es" to "luis perez luis.perez@upm.es marta lopez marta@alumnos.upm.es", split[ids["ana"]])
        assertEquals("mailer-daemon" to "ana garcia ana.garcia@upm.es", split[ids["daemon"]])
        assertEquals("" to "luis perez luis.perez@upm.es", split[ids["nobody"]])
        assertEquals("garcia, ana ana.garcia@upm.es" to "luis perez luis.perez@upm.es", split[ids["comma"]])
        assertTrue(split.getValue(ids.getValue("sent")).second.startsWith("carlos nunez carlos.nunez@upm.es persona 1 persona1@upm.es"))

        val store = MailStore(db, TableObserver())
        fun cached(query: String) = store.messages.searchHits(query, SearchFilters(), 50).map { it.key.uid }
        fun indexed(query: String) = store.headerIndex.search(query, SearchFilters(), 50).map { it.uid }
        assertEquals(listOf(6L, 1L), indexed("from:ana"))
        assertEquals("recipients outside Sent survive", listOf(3L, 2L), indexed("to:ana"))
        assertEquals(listOf(6L, 4L, 1L), indexed("to:luis"))
        assertEquals(listOf(3L), indexed("from:mailer-daemon"))
        assertEquals(listOf(1L), indexed("to:marta from:ana elecciones"))
        assertEquals(listOf(5L), indexed("to:persona150"))
        assertTrue("beyond the 200 Cc kept by version 2", indexed("to:persona250").isEmpty())
        assertEquals(listOf(10L), cached("from:ana"))
        assertEquals(listOf(11L), cached("to:ana"))
        assertEquals("the body of the full-text entry is kept", listOf(10L), cached("presupuesto"))
        assertTrue("Bcc is not a recipient", cached("to:carlos").isEmpty())
        assertEquals(listOf(12L), cached("to:luis aviso"))
        assertEquals(listOf(11L, 10L), cached("subject:acta"))

        val before = db.queryLong("SELECT COUNT(*) AS v FROM header_index_fts") ?: 0L
        db.delete("header_index", "id = ?", listOf(ids["luis"]))
        assertEquals("the delete trigger works", before - 1, db.queryLong("SELECT COUNT(*) AS v FROM header_index_fts"))
        val messages = db.queryLong("SELECT COUNT(*) AS v FROM messages_fts") ?: 0L
        db.delete("messages", "uid = 11", emptyList())
        assertEquals(messages - 1, db.queryLong("SELECT COUNT(*) AS v FROM messages_fts"))
    }
}
