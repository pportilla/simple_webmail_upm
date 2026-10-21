package io.github.pportilla.upmmail.data.db

import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.model.OutgoingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutboxDaoTest {
    private val clock = TestClock()
    private lateinit var store: MailStore
    private var accountId = 0L

    @Before
    fun setUp() {
        store = newStore(clock::now)
        accountId = store.addTestAccount().id
    }

    private fun draft(subject: String = "Hola") = Draft(
        accountId = accountId,
        to = listOf(EmailAddress("bea@alumnos.upm.es", "Bea Gómez"), EmailAddress("c@upm.es")),
        cc = listOf(EmailAddress("d@upm.es")),
        subject = subject,
        body = "Cuerpo\n-- \nFirma",
        attachments = listOf(OutgoingAttachment("file:///tmp/a.pdf", "a.pdf", "application/pdf", 10)),
        inReplyTo = "<orig@x>",
        references = listOf("<root@x>", "<orig@x>"),
        mode = ComposeMode.REPLY,
        sourceMessageId = 42,
    )

    @Test
    fun hugeDraftAndOutboxBodiesAreReadBackCompletely() {
        // A reply quoting a huge message: more than Android's 2 MB CursorWindow holds in one row.
        val body = buildString { repeat(120_000) { append("> 引用されたテキスト $it\n") } }
        val id = store.drafts.save(draft().copy(body = body))
        assertEquals(body, store.drafts.get(id)!!.body)
        val outboxId = store.outbox.enqueue(draft().copy(body = body), null, clock.now())
        assertEquals(body, store.outbox.get(outboxId)!!.item.draft.body)
        assertEquals(body, store.outbox.due(null, clock.now()).single().item.draft.body)
        assertEquals(body, store.outbox.list(accountId).single().draft.body)
    }

    @Test
    fun draftsRoundTripWithAttachments() {
        val id = store.drafts.save(draft())
        val loaded = store.drafts.get(id)!!
        assertEquals(draft().copy(id = id, updatedAt = loaded.updatedAt), loaded)

        val updated = loaded.copy(subject = "Hola de nuevo", attachments = emptyList())
        assertEquals(id, store.drafts.save(updated))
        assertEquals("Hola de nuevo", store.drafts.get(id)!!.subject)
        assertTrue(store.drafts.get(id)!!.attachments.isEmpty())

        val (deleted, copy) = store.drafts.delete(id)!!
        assertEquals("Hola de nuevo", deleted.subject)
        assertNull(copy)
        assertNull(store.drafts.get(id))
    }

    @Test
    fun draftAdoptsTheServerMessageItEdits() {
        val drafts = store.addTestFolder(accountId, "Drafts", FolderRole.DRAFTS)
        val messageId = store.messages.upsertHeaders(accountId, drafts.id, listOf(remoteMessage(8, messageId = "<draft@x>"))).single()
        val id = store.drafts.save(draft().copy(mode = ComposeMode.EDIT_DRAFT, serverDraftMessageId = messageId))
        assertEquals(ServerCopy("Drafts", 8, "<draft@x>"), store.drafts.serverCopy(id))
    }

    @Test
    fun outboxStateMachine() {
        val id = store.outbox.enqueue(draft(), ServerCopy("Drafts", 5, "<d@x>"), sendAt = clock.now() + 5_000)
        val item = store.outbox.list(null).single()
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals(1, item.draft.attachments.size)
        assertEquals(clock.now() + 5_000, store.outbox.nextSendAt())

        assertTrue("not due yet", store.outbox.due(null, clock.now()).isEmpty())
        clock.advance(5_000)
        val due = store.outbox.due(accountId, clock.now()).single()
        assertEquals(ServerCopy("Drafts", 5, "<d@x>"), due.serverCopy)

        assertTrue(store.outbox.claim(id))
        assertFalse("only one sender can claim it", store.outbox.claim(id))
        assertNull("cannot cancel while sending", store.outbox.deleteIfIn(id, OutboxState.QUEUED))

        store.outbox.requeue(id, attempts = 1, error = "NETWORK", retryAt = clock.now() + 30_000)
        val requeued = store.outbox.get(id)!!.item
        assertEquals(OutboxState.QUEUED, requeued.state)
        assertEquals(1, requeued.attempts)
        assertEquals("NETWORK", requeued.lastError)

        assertTrue(store.outbox.claim(id))
        store.outbox.fail(id, attempts = 2, error = "INVALID_RECIPIENT")
        assertEquals(OutboxState.FAILED, store.outbox.get(id)!!.item.state)
        assertNull(store.outbox.nextSendAt())

        assertTrue(store.outbox.retry(id))
        val retried = store.outbox.get(id)!!.item
        assertEquals(OutboxState.QUEUED, retried.state)
        assertEquals(0, retried.attempts)
        assertNull(retried.lastError)

        assertNotNull(store.outbox.deleteIfIn(id, OutboxState.QUEUED, OutboxState.FAILED))
        assertTrue(store.outbox.list(null).isEmpty())
        assertTrue("attachments cascade", store.outbox.attachmentUris().isEmpty())
    }

    @Test
    fun staleSendingBecomesFailedButNeverQueued() {
        val stale = store.outbox.enqueue(draft("old"), null, clock.now())
        val fresh = store.outbox.enqueue(draft("new"), null, clock.now())
        store.outbox.claim(stale)
        clock.advance(11 * 60_000)
        store.outbox.claim(fresh)
        val failed = store.outbox.failStaleSending(clock.now() - 10 * 60_000, emptySet(), "UNKNOWN")
        assertEquals(listOf(stale), failed.map { it.id })
        assertEquals(OutboxState.FAILED, store.outbox.get(stale)!!.item.state)
        assertEquals(OutboxState.SENDING, store.outbox.get(fresh)!!.item.state)
    }
}
