package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.Conflict
import io.github.pportilla.upmmail.data.db.ExtraRow
import io.github.pportilla.upmmail.data.db.JdbcSqlDatabase
import io.github.pportilla.upmmail.data.db.MailStore
import io.github.pportilla.upmmail.data.db.PendingOp
import io.github.pportilla.upmmail.data.db.PendingOpType
import io.github.pportilla.upmmail.data.db.SqlDatabase
import io.github.pportilla.upmmail.data.db.TableObserver
import io.github.pportilla.upmmail.data.db.Tables
import io.github.pportilla.upmmail.data.db.addTestAccount
import io.github.pportilla.upmmail.data.db.remoteMessage
import io.github.pportilla.upmmail.data.files.FileAttachmentOpener
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.HitOpenResult
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.OutboxState
import io.github.pportilla.upmmail.model.OutgoingAttachment
import io.github.pportilla.upmmail.model.SearchFilters
import jakarta.mail.Flags
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SyncEngineTest : SyncTestBase() {

    private suspend fun inboxId(): Long {
        engine.syncFolderList(account.id)
        return engine.inboxOf(account.id).id
    }

    @Test
    fun firstSyncFetchesTheNewestWindowAndLoadOlderPagesBack() = runBlocking {
        appendMessages("INBOX", numbered(60))
        val inbox = inboxId()

        val fresh = engine.syncFolder(inbox)
        assertTrue("nothing is notified on the first sync", fresh.isEmpty())
        assertTrue(listener.newMessages.isEmpty())
        assertEquals(50, store.messages.count(inbox))
        val list = store.messages.list(inbox, 100, conversations = false)
        assertEquals("Message 60", list.first().subject)
        assertEquals("Message 11", list.last().subject)
        assertTrue(store.folders.syncState(inbox)!!.moreAvailable)
        val folder = store.folders.get(inbox)!!
        assertEquals(60, folder.totalCount)
        assertEquals(60, folder.unreadCount)
        assertTrue("newest bodies are prefetched", list.first().snippet.contains("Hola"))

        assertFalse("no more after the last page", engine.loadOlder(inbox, 50))
        assertEquals(60, store.messages.count(inbox))
        assertEquals("Message 1", store.messages.list(inbox, 100, conversations = false).last().subject)
    }

    @Test
    fun loadOlderReportsWhenMoreRemain() = runBlocking {
        appendMessages("INBOX", numbered(70))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        assertTrue(engine.loadOlder(inbox, 10))
        assertEquals(60, store.messages.count(inbox))
        assertFalse(engine.loadOlder(inbox, 10))
        assertEquals(70, store.messages.count(inbox))
    }

    @Test
    fun newMailFlagChangesAndExpungesAreReflected() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)

        appendMessages("INBOX", listOf(message("Nuevo 1"), message("Nuevo 2")))
        changeOnServer("INBOX", "Message 1") { it.setFlag(Flags.Flag.SEEN, true) }
        changeOnServer("INBOX", "Message 2") { it.setFlag(Flags.Flag.FLAGGED, true) }
        changeOnServer("INBOX", "Message 3") { it.setFlag(Flags.Flag.DELETED, true) }

        val fresh = engine.syncFolder(inbox)
        assertEquals(setOf("Nuevo 1", "Nuevo 2"), fresh.map { it.subject }.toSet())
        assertEquals(2, listener.newMessages.size)
        val bySubject = store.messages.list(inbox, 100, conversations = false).associateBy { it.subject }
        assertTrue(bySubject.getValue("Message 1").isRead)
        assertTrue(bySubject.getValue("Message 2").isStarred)
        assertNull("expunged on the server", bySubject["Message 3"])
        assertEquals(4, bySubject.size)
        assertEquals(4, store.folders.get(inbox)!!.totalCount)
        assertTrue("contacts are fed by sync", store.contacts.get("bea@alumnos.upm.es")!!.timesSeen > 0)
    }

    @Test
    fun uidValidityChangeDropsTheFolderCache() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val oldIds = store.messages.list(inbox, 10, false).map { it.id }.toSet()
        store.pendingOps.insert(
            PendingOp(
                accountId = account.id,
                type = PendingOpType.FLAG,
                folder = "INBOX",
                uids = listOf(1),
                flag = "SEEN",
                value = true,
                notBefore = clock.now() + 60_000,
            ),
        )
        // The server now reports a different UIDVALIDITY than the cached one.
        store.folders.updateAfterSync(inbox, 1, 0, 0)

        engine.syncFolder(inbox)
        val newIds = store.messages.list(inbox, 10, false).map { it.id }.toSet()
        assertEquals(3, newIds.size)
        assertTrue("rows were rebuilt", newIds.intersect(oldIds).isEmpty())
        assertTrue("operations on the old uids are dropped", store.pendingOps.forAccount(account.id).isEmpty())
        assertNotEquals(1L, store.folders.syncState(inbox)!!.uidValidity)
    }

    @Test
    fun pendingFlagChangesAreFlushedToTheServer() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val repository = mailRepository()
        val bySubject = store.messages.list(inbox, 10, false).associateBy { it.subject }

        repository.setRead(listOf(bySubject.getValue("Message 1").id), true)
        repository.setStarred(listOf(bySubject.getValue("Message 2").id), true)
        assertEquals("optimistic counter", 2, store.folders.get(inbox)!!.unreadCount)
        // A sync before the flush must not undo the local change...
        engine.syncFolder(inbox)
        assertTrue(store.messages.ref(bySubject.getValue("Message 1").id)!!.isRead)

        val flags = serverMessages("INBOX").toMap()
        assertTrue("...because it flushes first", flags.getValue("Message 1").contains(Flags.Flag.SEEN))
        assertTrue(flags.getValue("Message 2").contains(Flags.Flag.FLAGGED))
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
    }

    @Test
    fun markFolderReadUpdatesEveryServerMessage() = runBlocking {
        appendMessages("INBOX", numbered(60))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        mailRepository().markFolderRead(inbox)
        assertEquals(0, store.folders.get(inbox)!!.unreadCount)
        engine.flushPendingOps(account.id)
        assertTrue("also messages outside the cached window", serverMessages("INBOX").all { it.second.contains(Flags.Flag.SEEN) })
    }

    @Test
    fun moveCanBeUndoneBeforeItsWindowEnds() = runBlocking {
        createFolder("Archive")
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val repository = mailRepository()
        val target = store.messages.list(inbox, 10, false).first { it.subject == "Message 2" }

        val handle = repository.archive(listOf(target.id)).getOrThrow()
        assertEquals(1, handle.count)
        assertFalse(store.messages.list(inbox, 10, false).any { it.id == target.id })
        assertEquals("optimistic counter", 2, store.folders.get(inbox)!!.totalCount)

        engine.flushPendingOps(account.id)
        assertEquals("still inside the undo window", 3, serverMessages("INBOX").size)

        repository.undo(handle)
        assertTrue(store.messages.list(inbox, 10, false).any { it.id == target.id })
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
        clock.advance(10_000)
        engine.flushPendingOps(account.id)
        assertEquals(3, serverMessages("INBOX").size)
        assertTrue(serverMessages("Archive").isEmpty())
    }

    @Test
    fun moveReachesTheServerAfterTheWindowAndCanStillBeUndone() = runBlocking {
        createFolder("Archive")
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val repository = mailRepository()
        val target = store.messages.list(inbox, 10, false).first { it.subject == "Message 2" }

        val handle = repository.archive(listOf(target.id)).getOrThrow()
        clock.advance(7_000)
        engine.flushPendingOps(account.id)
        assertEquals(listOf("Message 1", "Message 3"), serverMessages("INBOX").map { it.first })
        assertEquals(listOf("Message 2"), serverMessages("Archive").map { it.first })
        assertNull("local row removed once moved", store.messages.ref(target.id))

        repository.undo(handle)
        engine.flushPendingOps(account.id)
        assertEquals(3, serverMessages("INBOX").size)
        assertTrue(serverMessages("Archive").isEmpty())
        engine.syncFolder(inbox)
        assertTrue(store.messages.list(inbox, 10, false).any { it.subject == "Message 2" })
    }

    @Test
    fun deleteMovesToTrashAndIsPermanentInsideTrash() = runBlocking {
        createFolder("Trash")
        appendMessages("INBOX", numbered(2))
        appendMessages("Trash", listOf(message("Viejo")))
        val inbox = inboxId()
        val trash = store.folders.findByRole(account.id, FolderRole.TRASH)!!.id
        engine.syncFolder(inbox)
        engine.syncFolder(trash)
        val repository = mailRepository()

        val inTrash = store.messages.list(trash, 10, false).single()
        assertNull("no undo for permanent deletes", repository.delete(listOf(inTrash.id)).getOrThrow())
        engine.flushPendingOps(account.id)
        assertTrue(serverMessages("Trash").isEmpty())

        val inInbox = store.messages.list(inbox, 10, false).first()
        val handle = repository.delete(listOf(inInbox.id)).getOrThrow()
        assertEquals(1, handle!!.count)
        clock.advance(7_000)
        engine.flushPendingOps(account.id)
        assertEquals(1, serverMessages("Trash").size)
        assertEquals(1, serverMessages("INBOX").size)
    }

    @Test
    fun archiveWithoutArchiveFolderFailsWithNotFound() = runBlocking {
        appendMessages("INBOX", numbered(1))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val result = mailRepository().archive(listOf(store.messages.list(inbox, 10, false).single().id))
        assertEquals(MailErrorKind.NOT_FOUND, (result.exceptionOrNull() as MailException).kind)
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
    }

    @Test
    fun conversationActionsCoverTheWholeThread() = runBlocking {
        createFolder("Archive")
        appendMessages(
            "INBOX",
            listOf(
                message("Plan", messageId = "<root@test>", date = Date(1_700_000_000_000L)),
                reply(message("Re: Plan", messageId = "<reply@test>", date = Date(1_700_000_060_000L)), "<root@test>"),
                message("Otro", messageId = "<other@test>", date = Date(1_700_000_120_000L)),
            ),
        )
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val repository = mailRepository(conversations = true)
        val rows = store.messages.list(inbox, 10, conversations = true)
        val plan = rows.first { it.subject == "Re: Plan" }
        assertEquals(2, plan.threadCount)

        repository.setRead(listOf(plan.id), true)
        assertTrue(store.messages.list(inbox, 10, conversations = false).filter { it.subject.contains("Plan") }.all { it.isRead })
        val handle = repository.archive(listOf(plan.id)).getOrThrow()
        assertEquals(2, handle.count)
        assertEquals(listOf("Otro"), store.messages.list(inbox, 10, conversations = true).map { it.subject })
    }

    @Test
    fun bodiesAndAttachmentsAreDownloaded() = runBlocking {
        val bytes = ByteArray(5_000) { (it % 251).toByte() }
        appendMessages("INBOX", listOf(message("Acta", body = "Adjunto el acta", attachment = "acta.pdf" to bytes)))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val id = store.messages.list(inbox, 10, false).single().id
        engine.loadBody(id, inlineImages = true)
        val detail = store.messages.detail(id)!!
        assertTrue(detail.bodyLoaded)
        assertTrue(detail.bodyText.orEmpty().contains("Adjunto el acta"))
        val attachment = detail.attachments.single()
        assertEquals("acta.pdf", attachment.fileName)

        val file = engine.downloadAttachment(attachment.id)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertTrue(file.canonicalPath.startsWith(files.attachmentsDir.canonicalPath))
        assertEquals(file.absolutePath, store.messages.detail(id)!!.attachments.single().localPath)
        assertEquals("cached copy is reused", file, engine.downloadAttachment(attachment.id))
    }

    @Test
    fun serverSearchAddsOlderMatchesWithoutBreakingTheWindow() = runBlocking {
        val messages = numbered(60).toMutableList()
        messages[2] = message("Unicornio azul", messageId = "<unicorn@test>", date = Date(1_700_000_000_000L + 3 * 60_000L))
        appendMessages("INBOX", messages)
        val inbox = inboxId()
        engine.syncFolder(inbox)
        assertTrue(store.messages.search("unicornio", SearchFilters(), 10).isEmpty())

        val found = engine.searchServer(account.id, inbox, "Unicornio", 10)
        assertEquals(listOf("Unicornio azul"), found.map { it.subject })
        assertEquals(51, store.messages.count(inbox))
        assertEquals("searchable locally now", 1, store.messages.search("unicornio", SearchFilters(), 10).size)

        engine.syncFolder(inbox)
        assertEquals(51, store.messages.count(inbox))
        assertFalse(engine.loadOlder(inbox, 50))
        assertEquals(60, store.messages.count(inbox))
    }

    @Test
    fun sendingThroughTheOutboxDeliversAndKeepsACopyInSent() = runBlocking {
        createFolder("Sent")
        appendMessages("INBOX", listOf(message("Pregunta", from = "Bob <bob@upm.es>", messageId = "<question@test>")))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val source = store.messages.list(inbox, 10, false).single()
        val compose = composeRepository()

        val draft = compose.createDraft(account.id, ComposeMode.REPLY, source.id).getOrThrow()
        assertEquals(listOf("bob@upm.es"), draft.to.map { it.address })
        assertEquals("Re: Pregunta", draft.subject)
        assertTrue(draft.body.contains("wrote:"))
        assertTrue(draft.body.contains("> Hola, Pregunta"))

        val outboxId = compose.send(draft.copy(body = "Respuesta\n\n" + draft.body), delaySeconds = 0)
        assertTrue(outboxId > 0)
        val result = engine.sendOutbox()
        assertEquals(1, result.sent)
        assertNull(result.nextSendAt)
        assertTrue(store.outbox.list(null).isEmpty())

        assertEquals(listOf("Re: Pregunta"), serverMessages("INBOX", user = "bob").map { it.first })
        val sent = serverMessages("Sent")
        assertEquals(listOf("Re: Pregunta"), sent.map { it.first })
        assertTrue(sent.single().second.contains(Flags.Flag.SEEN))
        assertTrue("source marked answered", serverMessages("INBOX").single().second.contains(Flags.Flag.ANSWERED))
        assertTrue(store.messages.ref(source.id)!!.isAnswered)
        assertEquals(1, store.contacts.get("bob@upm.es")!!.timesContacted)
    }

    @Test
    fun undoSendRestoresTheDraft() = runBlocking {
        val compose = composeRepository()
        val draft = draftTo("bob@upm.es", "Ups")
        val saved = compose.saveDraft(draft, upload = false)
        val outboxId = compose.send(draft.copy(id = saved), delaySeconds = 30)
        assertNull("local draft moved to the outbox", compose.loadDraft(saved))
        assertTrue("not due yet", engine.sendOutbox().sent == 0)

        val restored = compose.cancelSend(outboxId)!!
        assertEquals("Ups", restored.subject)
        assertEquals(restored, compose.loadDraft(restored.id)?.copy(updatedAt = restored.updatedAt))
        assertTrue(store.outbox.list(null).isEmpty())
        assertTrue(serverMessages("INBOX", user = "bob").isEmpty())
    }

    @Test
    fun networkFailureRequeuesAndOtherFailuresMarkFailed() = runBlocking {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        store.accounts.update(account.copy(smtp = account.smtp.copy(port = closedPort)))
        val compose = composeRepository()
        val draft = draftTo("bob@upm.es", "Offline")
        val id = compose.send(draft, delaySeconds = 0)

        val result = engine.sendOutbox()
        assertEquals(1, result.retrying)
        val item = store.outbox.get(id)!!.item
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals(1, item.attempts)
        assertEquals("NETWORK", item.lastError)
        assertTrue("retried later, not immediately", item.sendAt > clock.now())

        // A missing attachment cannot be fixed by retrying: FAILED, and the listener is told.
        store.accounts.update(account)
        val missing = OutgoingAttachment("file:///nonexistent/x.pdf", "x.pdf", "application/pdf", 1)
        val broken = draft.copy(attachments = listOf(missing))
        val brokenId = compose.send(broken, delaySeconds = 0)
        engine.sendOutbox()
        assertEquals(OutboxState.FAILED, store.outbox.get(brokenId)!!.item.state)
        assertEquals(listOf(brokenId), listener.failures.map { it.id })
    }

    @Test
    fun draftsAreUploadedReplacedAndDiscarded() = runBlocking {
        createFolder("Drafts")
        engine.syncFolderList(account.id)
        val compose = composeRepository()
        val draft = draftTo("bob@upm.es", "Borrador 1")

        val id = compose.saveDraft(draft, upload = true)
        engine.flushPendingOps(account.id)
        val first = serverMessages("Drafts")
        assertEquals(listOf("Borrador 1"), first.map { it.first })
        assertTrue(first.single().second.contains(Flags.Flag.DRAFT))

        compose.saveDraft(draft.copy(id = id, subject = "Borrador 2"), upload = true)
        engine.flushPendingOps(account.id)
        assertEquals("the previous copy is replaced", listOf("Borrador 2"), serverMessages("Drafts").map { it.first })

        compose.discardDraft(compose.loadDraft(id)!!)
        engine.flushPendingOps(account.id)
        assertTrue(serverMessages("Drafts").isEmpty())
        assertNull(compose.loadDraft(id))
    }

    @Test
    fun newMailStoredByACancelledSyncIsStillNotified() = runBlocking {
        // Cancels the sync right after it stores a message, as a push restart on a new network can.
        var afterInsert: (() -> Unit)? = null
        val database = JdbcSqlDatabase.inMemory()
        val hooked = object : SqlDatabase by database {
            override fun insert(table: String, values: Map<String, Any?>, conflict: Conflict): Long =
                database.insert(table, values, conflict).also { if (table == Tables.MESSAGES && it > 0) afterInsert?.invoke() }
        }
        val hookedStore = MailStore(hooked, TableObserver(), io.github.pportilla.upmmail.data.db.adultSafety(), clock::now)
        val hookedAccount = hookedStore.addTestAccount(email = anaEmail, imap = account.imap, smtp = account.smtp)
        passwords.set(hookedAccount.id, password)
        val hookedClients = MailClientProvider({ hookedStore.accounts.get(it) }, passwords, transportPolicy = ::allowLoopbackTransport, safety = hookedStore.safety)
        val recorder = RecordingListener()
        val hookedEngine = SyncEngine(hookedStore, hookedClients, files, FileAttachmentOpener, recorder, clock = clock::now)
        try {
            appendMessages("INBOX", numbered(3))
            hookedEngine.syncFolderList(hookedAccount.id)
            val inbox = hookedEngine.inboxOf(hookedAccount.id).id
            hookedEngine.syncFolder(inbox)

            appendMessages("INBOX", listOf(message("Nuevo")))
            val sync = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { hookedEngine.syncFolder(inbox) }
            afterInsert = { sync.cancel() }
            sync.join()
            afterInsert = null
            assertTrue(sync.isCancelled)
            assertEquals(4, hookedStore.messages.count(inbox))
            assertEquals(listOf("Nuevo"), recorder.newMessages.map { it.subject })

            assertTrue("a stored message is not new again", hookedEngine.syncFolder(inbox).isEmpty())
            assertEquals(1, recorder.newMessages.size)
        } finally {
            hookedClients.closeAll()
        }
    }

    // ------------------------------------------------------------------ header index and search results

    private fun bulk() = IndexBudget.bulk(clock.now(), charging = true)

    @Test
    fun anIndexedSearchHitOpensAsADetachedRow() = runBlocking {
        appendMessages("INBOX", numbered(60))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        engine.headerIndexer.run(bulk())
        val listed = store.messages.list(inbox, 100, false).map { it.id }
        val row = store.headerIndex.findByUids(inbox, listOf(3L)).single()
        assertEquals("Message 3", row.subject)

        val opened = engine.materializeIndexed(row.indexId) as HitOpenResult.Opened
        assertEquals(3L, store.messages.ref(opened.messageId)!!.uid)
        assertEquals("the list is unchanged", listed, store.messages.list(inbox, 100, false).map { it.id })
        assertEquals("the conversation shows it", listOf("Message 3"), store.messages.thread(opened.messageId).map { it.summary.subject })
        assertEquals(listOf(ExtraRow(opened.messageId, 3, clock.now())), store.messages.extras(inbox))
        assertTrue(
            "the cached copy replaces the index row in results",
            store.headerIndex.search("Message", SearchFilters(), 100).none { it.uid == 3L },
        )

        clock.advance(60_000)
        assertEquals(opened, engine.materializeIndexed(row.indexId))
        assertEquals("opening it again marks it used", clock.now(), store.messages.extras(inbox).single().detachedAt)

        engine.syncFolder(inbox)
        assertEquals(listed, store.messages.list(inbox, 100, false).map { it.id })
        assertNotNull("kept by the sync", store.messages.ref(opened.messageId))

        // Once the window grows over it, it is an ordinary row again.
        assertFalse(engine.loadOlder(inbox, 50))
        assertTrue(store.messages.list(inbox, 100, false).any { it.id == opened.messageId })
        assertTrue(store.messages.extras(inbox).isEmpty())
    }

    @Test
    fun anIndexedHitThatMovedOpensAnotherCopyAndOneThatIsGoneSaysSo() = runBlocking {
        appendMessages("INBOX", numbered(5))
        appendMessages("Archive", listOf(message("Message 2", messageId = "<Message-2@test>")))
        val inbox = inboxId()
        val archive = store.folders.findByFullName(account.id, "Archive")!!.id
        engine.headerIndexer.run(bulk())
        val moved = store.headerIndex.findByUids(inbox, listOf(2L)).single()
        val deleted = store.headerIndex.findByUids(inbox, listOf(4L)).single()
        // On the server, Message 2 is now only in Archive and Message 4 is gone.
        changeOnServer("INBOX", "Message 2") { it.setFlag(Flags.Flag.DELETED, true) }
        changeOnServer("INBOX", "Message 4") { it.setFlag(Flags.Flag.DELETED, true) }

        val opened = engine.materializeIndexed(moved.indexId) as HitOpenResult.Opened
        assertEquals(archive, store.messages.ref(opened.messageId)!!.folderId)
        assertTrue("the stale row is dropped", store.headerIndex.findByUids(inbox, listOf(2L)).isEmpty())

        assertEquals(HitOpenResult.Gone, engine.materializeIndexed(deleted.indexId))
        assertTrue(store.headerIndex.findByUids(inbox, listOf(4L)).isEmpty())
        assertNull(store.messages.findByUid(inbox, 4))
        assertEquals("the folder is checked again soon", listOf(inbox), store.headerIndex.dirtyFolders(account.id))
        assertEquals(HitOpenResult.Gone, engine.materializeIndexed(deleted.indexId))
    }

    @Test
    fun anIndexedHitBeingMovedReportsAPendingChange() = runBlocking {
        createFolder("Archive")
        appendMessages("INBOX", numbered(60))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        engine.headerIndexer.run(bulk())
        val row = store.headerIndex.findByUids(inbox, listOf(3L)).single()
        val opened = engine.materializeIndexed(row.indexId) as HitOpenResult.Opened

        mailRepository().archive(listOf(opened.messageId)).getOrThrow()
        assertEquals(HitOpenResult.PendingChange, engine.materializeIndexed(row.indexId))
    }

    @Test
    fun detachedRowsAreEvictedLeastRecentlyUsedFirstButNotWhileInUse() = runBlocking {
        storeOnServer("INBOX", numbered(350))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val now = clock.now()
        // 300 rows found by searches or opened: 50 used two hours ago, 250 in the last half hour.
        store.messages.upsertHeaders(account.id, inbox, (1L..50L).map { remoteMessage(it) }, feedContacts = false, detachedAt = now - 2 * 60 * 60_000L)
        store.messages.upsertHeaders(account.id, inbox, (51L..300L).map { remoteMessage(it) }, feedContacts = false, detachedAt = now - 10 * 60_000L)
        assertEquals(300, store.messages.extras(inbox).size)

        engine.syncFolder(inbox)
        assertEquals("rows in use are spared", 250, store.messages.extras(inbox).size)
        assertEquals(51L, store.messages.extras(inbox).first().uid)

        clock.advance(60 * 60_000L)
        engine.syncFolder(inbox)
        val left = store.messages.extras(inbox)
        assertEquals(HeaderIndexer.DETACHED_KEEP, left.size)
        assertEquals(101L, left.first().uid)
        assertEquals(50, store.messages.list(inbox, 500, false).size)
    }

    @Test
    fun aWindowRestartDetachesTheRowsItKeeps() = runBlocking {
        appendMessages("INBOX", numbered(60))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val starred = store.messages.findByUid(inbox, 20)!!
        store.pendingOps.insert(
            PendingOp(
                accountId = account.id,
                type = PendingOpType.FLAG,
                folder = "INBOX",
                uids = listOf(20),
                messageIds = listOf(starred.id),
                flag = "FLAGGED",
                value = true,
                notBefore = clock.now() + 60_000,
            ),
        )
        // A search result opened a minute ago.
        store.messages.upsertHeaders(account.id, inbox, listOf(remoteMessage(5)), feedContacts = false, detachedAt = clock.now() - 60_000)
        storeOnServer("INBOX", numbered(SyncEngine.NEWER_LIMIT + 50, "Nuevo"))

        engine.syncFolder(inbox)
        val listed = store.messages.list(inbox, 500, false)
        assertEquals(SyncEngine.INITIAL_WINDOW, listed.size)
        assertTrue(listed.all { it.subject.startsWith("Nuevo") })
        assertTrue("the starred row waits for its change, detached", store.messages.extras(inbox).any { it.uid == 20L })
        assertNotNull("the row in use survives", store.messages.findByUid(inbox, 5))
        assertNull(store.messages.findByUid(inbox, 21))
    }

    @Test
    fun completedChangesUpdateTheIndexAndMarkWhereMailWasAdded() = runBlocking {
        createFolder("Archive")
        createFolder("Sent")
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        val archive = store.folders.findByFullName(account.id, "Archive")!!.id
        val sent = store.folders.findByFullName(account.id, "Sent")!!.id
        engine.syncFolder(inbox)
        engine.headerIndexer.run(bulk())
        assertEquals(3, store.headerIndex.state(inbox)!!.indexedCount)
        val repository = mailRepository()
        val byUid = store.messages.list(inbox, 10, false).associateBy { it.uid }

        repository.setStarred(listOf(byUid.getValue(2).id), true)
        repository.archive(listOf(byUid.getValue(1).id)).getOrThrow()
        clock.advance(7_000)
        engine.flushPendingOps(account.id)
        assertTrue("moved away", store.headerIndex.findByUids(inbox, listOf(1L)).isEmpty())
        assertTrue(store.headerIndex.findByUids(inbox, listOf(2L)).single().isStarred)
        assertEquals("the move target expects new mail", listOf(archive), store.headerIndex.dirtyFolders(account.id))

        repository.markFolderRead(inbox)
        engine.flushPendingOps(account.id)
        assertTrue(store.headerIndex.findByUids(inbox, listOf(2L, 3L)).all { it.isRead })

        val dirty = engine.headerIndexer.run(IndexBudget.dirty(clock.now()))
        assertEquals(1, dirty.fetched)
        assertEquals(listOf("Message 1"), store.headerIndex.findByUids(archive, store.headerIndex.indexedUids(archive).toList()).map { it.subject })
        assertTrue(store.headerIndex.dirtyFolders().isEmpty())

        composeRepository().send(draftTo("bob@upm.es", "Hola Bob"), delaySeconds = 0)
        assertEquals(1, engine.sendOutbox().sent)
        assertEquals("the Sent copy is new there", listOf(sent), store.headerIndex.dirtyFolders(account.id))
    }
}

/** Turns [message] into a reply to the message with Message-ID [parent]. */
private fun reply(message: MimeMessage, parent: String): MimeMessage {
    message.setHeader("In-Reply-To", parent)
    message.setHeader("References", parent)
    return message
}
