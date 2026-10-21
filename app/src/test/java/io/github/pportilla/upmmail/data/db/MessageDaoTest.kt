package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.mail.ParsedAttachment
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MessageDaoTest {
    private lateinit var store: MailStore
    private var accountId = 0L
    private var inboxId = 0L

    @Before
    fun setUp() {
        store = newStore()
        accountId = store.addTestAccount().id
        inboxId = store.addTestFolder(accountId, "INBOX", FolderRole.INBOX).id
    }

    @Test
    fun schemaCreatesEveryTable() {
        val tables = store.sql.query("SELECT name FROM sqlite_master WHERE type IN ('table', 'trigger')", emptyList()) {
            it.stringOrEmpty("name")
        }
        listOf(
            "accounts", "folders", "messages", "attachments", "contacts", "pending_ops", "outbox",
            "outbox_attachments", "drafts", "draft_attachments", "recent_searches", "messages_fts", "messages_fts_delete",
        ).forEach { assertTrue("missing $it", it in tables) }
    }

    @Test
    fun upsertInsertsNewMessagesAndUpdatesFlagsOfKnownOnes() {
        val first = store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(1), remoteMessage(2)))
        assertEquals(2, first.size)

        val again = store.messages.upsertHeaders(
            accountId,
            inboxId,
            listOf(remoteMessage(1, seen = true), remoteMessage(2, flagged = true), remoteMessage(3)),
            protectedUids = setOf(2L),
        )
        assertEquals("only uid 3 is new", 1, again.size)
        val byUid = store.messages.list(inboxId, 10, conversations = false).associateBy { it.uid }
        assertTrue(byUid.getValue(1).isRead)
        assertFalse("protected uid keeps its local flags", byUid.getValue(2).isStarred)
        assertEquals(3, byUid.size)
    }

    @Test
    fun deletedOnServerIsNeverInserted() {
        val message = remoteMessage(5).copy(flags = RemoteFlags(deleted = true))
        assertTrue(store.messages.upsertHeaders(accountId, inboxId, listOf(message)).isEmpty())
    }

    @Test
    fun listIsNewestFirstAndExcludesHiddenRows() {
        val ids = store.messages.upsertHeaders(accountId, inboxId, (1L..5L).map { remoteMessage(it) })
        store.messages.setHidden(listOf(ids[4]), true)
        val list = store.messages.list(inboxId, 10, conversations = false)
        assertEquals(listOf(4L, 3L, 2L, 1L), list.map { it.uid })
        assertEquals(EmailAddress("bea@alumnos.upm.es", "Bea Gómez"), list.first().from)
        assertEquals(2, store.messages.list(inboxId, 2, conversations = false).size)
    }

    @Test
    fun conversationsGroupThreadsWithCountAndReadState() {
        store.messages.upsertHeaders(
            accountId,
            inboxId,
            listOf(
                remoteMessage(1, subject = "Plan", messageId = "<root@x>", seen = true),
                remoteMessage(2, subject = "Re: Plan", messageId = "<r1@x>", inReplyTo = "<root@x>", references = listOf("<root@x>"), seen = true),
                remoteMessage(3, subject = "Other", messageId = "<other@x>", seen = true),
                remoteMessage(4, subject = "Re: Plan", messageId = "<r2@x>", references = listOf("<ROOT@x>", "<r1@x>"), seen = false),
            ),
        )
        val rows = store.messages.list(inboxId, 10, conversations = true)
        assertEquals(2, rows.size)
        val plan = rows.first()
        assertEquals("newest message represents the thread", 4L, plan.uid)
        assertEquals(3, plan.threadCount)
        assertFalse("unread while any message is unread", plan.isRead)
        assertEquals(1, rows[1].threadCount)
        assertTrue(rows[1].isRead)

        val all = store.messages.expandToThreads(listOf(plan.id))
        assertEquals(3, all.size)

        // Hiding the newest one: the thread is represented by the previous message.
        store.messages.setHidden(listOf(plan.id), true)
        val after = store.messages.list(inboxId, 10, conversations = true)
        assertEquals(2L, after.first { it.subject.contains("Plan") }.uid)
        assertEquals(2, after.first { it.subject.contains("Plan") }.threadCount)
        assertTrue(after.first { it.subject.contains("Plan") }.isRead)
    }

    @Test
    fun searchUsesPrefixesAndIgnoresAccentsAndCase() {
        store.messages.upsertHeaders(
            accountId,
            inboxId,
            listOf(
                remoteMessage(1, subject = "Reunión de departamento", from = EmailAddress("jose@upm.es", "José Núñez")),
                remoteMessage(2, subject = "Horario de exámenes"),
                remoteMessage(3, subject = "Menu del comedor", from = EmailAddress("cafeteria@upm.es")),
            ),
        )
        assertEquals(listOf(1L), store.messages.search("reunion", SearchFilters(), 10).map { it.uid })
        assertEquals(listOf(1L), store.messages.search("REUN depa", SearchFilters(), 10).map { it.uid })
        assertEquals(listOf(1L), store.messages.search("nunez", SearchFilters(), 10).map { it.uid })
        assertEquals(listOf(2L), store.messages.search("exám", SearchFilters(), 10).map { it.uid })
        assertEquals(listOf(3L), store.messages.search("cafeteria@upm", SearchFilters(), 10).map { it.uid })
        assertTrue(store.messages.search("reunion horario", SearchFilters(), 10).isEmpty())
        // FTS syntax in user input is neutralised instead of failing.
        assertEquals(listOf(1L), store.messages.search("\"reunión\" -(depa*", SearchFilters(), 10).map { it.uid })
        assertTrue(store.messages.search("*** \"", SearchFilters(), 10).isEmpty())
    }

    @Test
    fun searchFindsBodiesAndHonoursFilters() {
        val ids = store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(1), remoteMessage(2, seen = true)))
        store.messages.storeBody(ids[0], null, "La convocatoria del tribunal", "La convocatoria", emptyList(), "La convocatoria del tribunal")
        store.messages.storeBody(ids[1], null, "Otra convocatoria", "Otra", emptyList(), "Otra convocatoria")
        assertEquals(2, store.messages.search("convocat", SearchFilters(), 10).size)
        assertEquals(listOf(ids[0]), store.messages.search("convocat", SearchFilters(unreadOnly = true), 10).map { it.id })
        assertEquals(listOf(ids[0]), store.messages.search("tribunal", SearchFilters(accountId = accountId, folderId = inboxId), 10).map { it.id })

        val trash = store.addTestFolder(accountId, "Trash", FolderRole.TRASH)
        store.messages.upsertHeaders(accountId, trash.id, listOf(remoteMessage(9, subject = "convocatoria vieja", messageId = "<t@x>")))
        assertEquals("trash is left out of global searches", 2, store.messages.search("convocat", SearchFilters(), 10).size)
        assertEquals(1, store.messages.search("convocat", SearchFilters(folderId = trash.id), 10).size)

        store.messages.setHidden(listOf(ids[1]), true)
        assertEquals(1, store.messages.search("convocat", SearchFilters(), 10).size)

        store.messages.deleteIds(listOf(ids[0]))
        val ftsRows = store.sql.queryLong("SELECT COUNT(*) AS v FROM messages_fts WHERE docid = ?", listOf(ids[0]))
        assertEquals(0L, ftsRows)
    }

    @Test
    fun serverFlagsUpdateRowsAndRemoveExpungedOnes() {
        val ids = store.messages.upsertHeaders(accountId, inboxId, (1L..4L).map { remoteMessage(it) })
        val result = store.messages.applyServerFlags(
            inboxId,
            1L..4L,
            mapOf(1L to RemoteFlags(seen = true), 2L to RemoteFlags(flagged = true), 4L to RemoteFlags(seen = true)),
            protectedUids = setOf(4L),
        )
        assertEquals(2, result.updated)
        assertEquals(1, result.removed)
        val byUid = store.messages.list(inboxId, 10, false).associateBy { it.uid }
        assertTrue(byUid.getValue(1).isRead)
        assertTrue(byUid.getValue(2).isStarred)
        assertNull("uid 3 was expunged", byUid[3])
        assertFalse("protected uid 4 keeps local state", byUid.getValue(4).isRead)
        assertNull(store.messages.ref(ids[2]))
    }

    @Test
    fun bodyStorageKeepsDownloadedAttachmentPaths() {
        val id = store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(1))).single()
        val parts = listOf(ParsedAttachment("2", "acta.pdf", "application/pdf", 1234, null, false))
        store.messages.storeBody(id, "<p>Hola</p>", null, "Hola", parts, "Hola")
        val attachment = store.messages.attachmentsFor(listOf(id)).getValue(id).single()
        store.messages.setAttachmentLocalPath(attachment.id, "/tmp/acta.pdf")

        store.messages.storeBody(id, "<p>Hola</p>", null, "Hola", parts, "Hola")
        val detail = store.messages.detail(id)
        assertNotNull(detail)
        assertTrue(detail!!.bodyLoaded)
        assertTrue(detail.summary.hasAttachments)
        assertEquals("/tmp/acta.pdf", detail.attachments.single().localPath)
        assertEquals(FolderRole.INBOX, detail.folderRole)
        val location = store.messages.attachment(detail.attachments.single().id)
        assertEquals("INBOX", location!!.folderFullName)
        assertEquals(1L, location.uid)
    }

    @Test
    fun aBodyIsStoredOnceAndNeverForADeletedMessage() {
        val ids = store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(1), remoteMessage(2)))
        val parts = listOf(
            ParsedAttachment("2", "logo.png", "image/png", 100, "logo", true),
            ParsedAttachment("3", "acta.pdf", "application/pdf", 1234, null, false),
        )
        assertTrue(store.messages.storeBody(ids[0], "<p>Hola</p>", null, "Hola", parts, "Hola"))
        val first = store.messages.attachmentsFor(listOf(ids[0])).getValue(ids[0]).map { it.id }

        // A second download of the same body (prefetch and opening the message racing).
        assertFalse(store.messages.storeBody(ids[0], "<p>Hola</p>", null, "Hola", parts, "Hola"))
        assertEquals("attachment ids held by the screen stay valid", first, store.messages.attachmentsFor(listOf(ids[0])).getValue(ids[0]).map { it.id })

        // Expunged or moved while its body was downloading: nothing to store, and no failure.
        store.messages.deleteIds(listOf(ids[1]))
        assertFalse(store.messages.storeBody(ids[1], "<p>Adiós</p>", null, "Adiós", parts, "Adiós"))
        assertTrue(store.messages.attachmentsFor(listOf(ids[1])).isEmpty())
    }

    @Test
    fun oldBodiesAreDroppedAndStoredAgainWithTheSameAttachments() {
        val ids = store.messages.upsertHeaders(accountId, inboxId, (1L..5L).map { remoteMessage(it) })
        val parts = listOf(ParsedAttachment("2", "acta.pdf", "application/pdf", 1234, null, false))
        ids.forEach { store.messages.storeBody(it, "<p>Cuerpo $it</p>", "Cuerpo $it", "Cuerpo $it", parts, "convocatoria $it") }
        val oldest = ids.first()
        val attachment = store.messages.attachmentsFor(listOf(oldest)).getValue(oldest).single()
        store.messages.setAttachmentLocalPath(attachment.id, "/tmp/acta.pdf")

        assertEquals(2, store.messages.dropOldBodies(keepPerFolder = 3))
        assertEquals(0, store.messages.dropOldBodies(keepPerFolder = 3))
        val dropped = store.messages.detail(oldest)!!
        assertFalse(dropped.bodyLoaded)
        assertNull(dropped.bodyHtml)
        assertEquals("the list keeps its preview", "Cuerpo $oldest", dropped.summary.snippet)
        assertTrue(store.messages.detail(ids.last())!!.bodyLoaded)
        assertEquals("still found by local search", 1, store.messages.search("convocatoria $oldest", SearchFilters(), 10).count { it.id == oldest })
        assertEquals(listOf(ids[1], ids[0]), store.messages.needingBody(inboxId, 10, Long.MAX_VALUE).map { it.id })

        assertTrue(store.messages.storeBody(oldest, "<p>Cuerpo</p>", null, "Cuerpo", parts, "Cuerpo"))
        val again = store.messages.detail(oldest)!!
        assertEquals("<p>Cuerpo</p>", again.bodyHtml)
        assertEquals(listOf(attachment.id), again.attachments.map { it.id })
        assertEquals("/tmp/acta.pdf", again.attachments.single().localPath)
    }

    @Test
    fun hugeBodiesAreReadBackCompletely() {
        val id = store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(1))).single()
        val html = buildString { repeat(40_000) { append("<p>Línea número $it ✓ 😀</p>") } }
        store.messages.storeBody(id, html, "corto", "corto", emptyList(), "corto")
        val detail = store.messages.detail(id)!!
        assertEquals(html.length, detail.bodyHtml!!.length)
        assertEquals(html, detail.bodyHtml)
        assertEquals("corto", detail.bodyText)
        assertEquals(html, store.messages.thread(id).single().bodyHtml)
    }

    @Test
    fun threadSpansFoldersSkipsTrashAndDeduplicatesCopies() {
        val sent = store.addTestFolder(accountId, "Sent", FolderRole.SENT)
        val trash = store.addTestFolder(accountId, "Trash", FolderRole.TRASH)
        val inboxIds = store.messages.upsertHeaders(
            accountId,
            inboxId,
            listOf(
                remoteMessage(1, messageId = "<a@x>", date = 1_000),
                remoteMessage(2, messageId = "<c@x>", references = listOf("<a@x>"), date = 3_000),
            ),
        )
        store.messages.upsertHeaders(accountId, sent.id, listOf(remoteMessage(7, messageId = "<b@x>", references = listOf("<a@x>"), date = 2_000)))
        // A copy of <c@x> in Sent too (e.g. sent to oneself) and an old reply in the Trash.
        store.messages.upsertHeaders(accountId, sent.id, listOf(remoteMessage(8, messageId = "<c@x>", references = listOf("<a@x>"), date = 3_000)))
        store.messages.upsertHeaders(accountId, trash.id, listOf(remoteMessage(3, messageId = "<d@x>", references = listOf("<a@x>"), date = 4_000)))

        val thread = store.messages.thread(inboxIds[0])
        assertEquals(listOf("<a@x>", "<b@x>", "<c@x>"), thread.map { it.messageIdHeader })
        assertEquals("copy of the opened folder preferred", inboxId, store.messages.thread(inboxIds[1]).last().summary.folderId)
    }

    @Test
    fun observerEmitsAfterCommittedChanges() = runBlocking {
        val flow = store.messages.observeList(inboxId, 10, conversations = false)
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.IO) { flow.collect { seen += it.size } }
        waitFor { seen.isNotEmpty() }
        store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(1)))
        waitFor { seen.lastOrNull() == 1 }
        store.tx {
            store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(2)))
            store.messages.upsertHeaders(accountId, inboxId, listOf(remoteMessage(3)))
        }
        waitFor { seen.lastOrNull() == 3 }
        job.cancel()
        assertEquals(0, seen.first())
        assertFalse("no intermediate state of the transaction is emitted", 2 in seen)
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            delay(10)
        }
    }
}
