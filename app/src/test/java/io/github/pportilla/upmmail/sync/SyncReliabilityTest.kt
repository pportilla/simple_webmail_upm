package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.LocalFlag
import io.github.pportilla.upmmail.data.db.PendingOp
import io.github.pportilla.upmmail.data.db.PendingOpType
import io.github.pportilla.upmmail.model.ComposeMode
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.OutboxState
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pending operations, the outbox and folder state under failures, cancellation and races. */
class SyncReliabilityTest : SyncTestBase() {

    private suspend fun inboxId(): Long {
        engine.syncFolderList(account.id)
        return engine.inboxOf(account.id).id
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun sentCopies(): List<File> = File(files.filesDir, "sent").listFiles().orEmpty().toList()

    // ------------------------------------------------------------------ emptying a folder

    @Test
    fun emptyingTrashFirstMovesOutAMessageStillInItsUndoWindow() = runBlocking {
        createFolder("Trash")
        appendMessages("Trash", listOf(message("Importante"), message("Basura")))
        val inbox = inboxId()
        val trash = store.folders.findByRole(account.id, FolderRole.TRASH)!!.id
        engine.syncFolder(inbox)
        engine.syncFolder(trash)
        val keep = store.messages.list(trash, 10, false).first { it.subject == "Importante" }
        mailRepository().moveTo(listOf(keep.id), inbox).getOrThrow()

        engine.emptyFolder(trash)

        assertEquals(listOf("Importante"), serverMessages("INBOX").map { it.first })
        assertTrue(serverMessages("Trash").isEmpty())
        assertTrue(store.messages.list(trash, 10, false).isEmpty())
        assertEquals(0, store.folders.get(trash)!!.totalCount)
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
        engine.syncFolder(inbox)
        assertEquals(listOf("Importante"), store.messages.list(inbox, 10, false).map { it.subject })
    }

    @Test
    fun emptyingTrashIsRefusedWhileARestoreCannotReachTheServer() = runBlocking {
        createFolder("Trash")
        appendMessages("Trash", listOf(message("Importante"), message("Basura")))
        val inbox = inboxId()
        val trash = store.folders.findByRole(account.id, FolderRole.TRASH)!!.id
        engine.syncFolder(trash)
        val keep = store.messages.list(trash, 10, false).first { it.subject == "Importante" }
        mailRepository().moveTo(listOf(keep.id), inbox).getOrThrow()
        passwords.set(account.id, "expired")
        clients.invalidate(account.id)

        val error = runCatching { engine.emptyFolder(trash) }.exceptionOrNull()
        assertEquals(MailErrorKind.AUTHENTICATION, (error as MailException).kind)
        assertEquals("nothing was deleted", 2, serverMessages("Trash").size)
        assertEquals(listOf(PendingOpType.MOVE), store.pendingOps.forAccount(account.id).map { it.type })

        passwords.set(account.id, password)
        clients.invalidate(account.id)
        engine.emptyFolder(trash)
        assertEquals(listOf("Importante"), serverMessages("INBOX").map { it.first })
        assertTrue(serverMessages("Trash").isEmpty())
    }

    // ------------------------------------------------------------------ pending operations

    @Test
    fun markAllReadLeavesMailArrivingBeforeItIsPushedUnread() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        mailRepository().markFolderRead(inbox)
        appendMessages("INBOX", listOf(message("Nuevo")))

        // Offline until now: this sync pushes the operation, then fetches the new message.
        val fresh = engine.syncFolder(inbox)

        assertEquals(listOf("Nuevo"), fresh.map { it.subject })
        assertEquals(listOf("Nuevo"), listener.newMessages.map { it.subject })
        val server = serverMessages("INBOX").toMap()
        assertTrue((1..3).all { server.getValue("Message $it").contains(Flags.Flag.SEEN) })
        assertFalse(server.getValue("Nuevo").contains(Flags.Flag.SEEN))
        assertFalse(store.messages.list(inbox, 10, false).first { it.subject == "Nuevo" }.isRead)
        assertEquals(1, store.folders.get(inbox)!!.unreadCount)
    }

    @Test
    fun aReadChangeMadeWhileFlagsAreFetchedSurvivesTheSync() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val target = store.messages.ref(store.messages.list(inbox, 10, false).first { it.subject == "Message 2" }.id)!!
        var opened = false
        // The user opens the message while the sync asks the server for the window's flags
        // (what MailRepositoryImpl.setFlag records, without waiting for the account lock).
        onQuery = { sql ->
            if (!opened && sql.startsWith("SELECT MIN(uid) AS lo")) {
                opened = true
                store.tx {
                    store.messages.setFlag(listOf(target.id), LocalFlag.READ, true)
                    store.pendingOps.insert(
                        PendingOp(
                            accountId = account.id,
                            type = PendingOpType.FLAG,
                            folder = "INBOX",
                            uids = listOf(target.uid),
                            messageIds = listOf(target.id),
                            flag = "SEEN",
                            value = true,
                            notBefore = clock.now(),
                        ),
                    )
                    store.folders.adjustCounts(inbox, -1, 0)
                }
            }
        }
        engine.syncFolder(inbox)
        onQuery = null

        assertTrue(opened)
        assertTrue("not overwritten by the older server flags", store.messages.ref(target.id)!!.isRead)
        assertEquals(2, store.folders.get(inbox)!!.unreadCount)
        engine.syncFolder(inbox)
        assertTrue(serverMessages("INBOX").toMap().getValue("Message 2").contains(Flags.Flag.SEEN))
        assertTrue(store.messages.ref(target.id)!!.isRead)
        assertEquals(2, store.folders.get(inbox)!!.unreadCount)
    }

    @Test
    fun operationsBlockedByLoginOrNetworkProblemsAreKept() = runBlocking {
        createFolder("Archive")
        appendMessages("INBOX", numbered(2))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val target = store.messages.list(inbox, 10, false).first()
        mailRepository().archive(listOf(target.id)).getOrThrow()
        clock.advance(7_000)

        passwords.set(account.id, "expired")
        clients.invalidate(account.id)
        repeat(60) { assertEquals(MailErrorKind.AUTHENTICATION, engine.flushPendingOps(account.id).blockedBy?.kind) }
        store.accounts.update(account.copy(imap = account.imap.copy(port = closedPort())))
        repeat(60) { assertEquals(MailErrorKind.NETWORK, engine.flushPendingOps(account.id).blockedBy?.kind) }
        val op = store.pendingOps.forAccount(account.id).single()
        assertEquals("not the operation's fault", 0, op.attempts)
        assertTrue(store.messages.ref(target.id)!!.hidden)

        store.accounts.update(account)
        passwords.set(account.id, password)
        clients.invalidate(account.id)
        assertEquals(null, engine.flushPendingOps(account.id).blockedBy)
        assertEquals(listOf(target.subject), serverMessages("Archive").map { it.first })
    }

    @Test
    fun anUploadTheServerKeepsRefusingWaitsWithoutHoldingUpLaterOperations() = runBlocking {
        createFolder("Drafts")
        appendMessages("INBOX", numbered(1))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val draftId = store.drafts.save(draftTo("bob@upm.es", "Borrador"))
        store.pendingOps.insert(
            PendingOp(accountId = account.id, type = PendingOpType.APPEND_DRAFT, target = draftId.toString(), notBefore = clock.now()),
        )
        val starred = store.messages.list(inbox, 10, false).single()
        store.pendingOps.insert(
            PendingOp(
                accountId = account.id,
                type = PendingOpType.FLAG,
                folder = "INBOX",
                uids = listOf(starred.uid),
                messageIds = listOf(starred.id),
                flag = "FLAGGED",
                value = true,
                notBefore = clock.now(),
            ),
        )
        var uploads = 0
        onQuery = { sql ->
            if (sql.contains("updated_at FROM drafts WHERE id = ?")) {
                uploads++
                throw MailException(MailErrorKind.SERVER, "Try again later", temporary = true)
            }
        }

        val first = engine.flushPendingOps(account.id)
        assertEquals(null, first.blockedBy)
        assertTrue("the later operation ran", serverMessages("INBOX").single().second.contains(Flags.Flag.FLAGGED))
        assertEquals(PendingOpType.APPEND_DRAFT, store.pendingOps.forAccount(account.id).single().type)
        // Tried again by the next flush; from then on it waits.
        val second = engine.flushPendingOps(account.id)
        assertEquals(2, uploads)
        val waiting = store.pendingOps.forAccount(account.id).single()
        assertEquals(2, waiting.attempts)
        assertTrue(waiting.notBefore > clock.now())
        assertEquals(waiting.notBefore, second.nextDueAt)
        engine.flushPendingOps(account.id)
        assertEquals("not uploaded again at once", 2, uploads)

        repeat(20) {
            clock.advance(16 * 60_000L)
            engine.flushPendingOps(account.id)
        }
        onQuery = null
        assertEquals("given up after a bounded number of uploads", 10, uploads)
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
        assertNotNull("the draft stays on this device", store.drafts.get(draftId))
    }

    @Test
    fun aMoveTheServerRejectsGivesTheCountersBack() = runBlocking {
        createFolder("Proyectos")
        appendMessages("INBOX", numbered(3))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val target = store.folders.findByFullName(account.id, "Proyectos")!!
        // Deleted elsewhere (webmail) before this app knows.
        withServer { it.getFolder("Proyectos").delete(true) }
        val before = store.folders.get(inbox)!!
        val moved = store.messages.list(inbox, 10, false).first()
        mailRepository().moveTo(listOf(moved.id), target.id).getOrThrow()
        assertEquals(before.unreadCount - 1, store.folders.get(inbox)!!.unreadCount)

        clock.advance(7_000)
        engine.flushPendingOps(account.id)

        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
        assertFalse(store.messages.ref(moved.id)!!.hidden)
        val inboxAfter = store.folders.get(inbox)!!
        assertEquals(before.unreadCount, inboxAfter.unreadCount)
        assertEquals(before.totalCount, inboxAfter.totalCount)
        val targetAfter = store.folders.get(target.id)!!
        assertEquals(target.unreadCount, targetAfter.unreadCount)
        assertEquals(target.totalCount, targetAfter.totalCount)
    }

    @Test
    fun aDraftDeletionByMessageIdSurvivesAUidValidityChange() = runBlocking {
        createFolder("Drafts")
        appendMessages("Drafts", listOf(message("Borrador viejo", messageId = "<old-draft@test>")), Flags(Flags.Flag.DRAFT))
        engine.syncFolderList(account.id)
        val drafts = store.folders.findByRole(account.id, FolderRole.DRAFTS)!!.id
        engine.syncFolder(drafts)
        store.pendingOps.insert(
            PendingOp(
                accountId = account.id,
                type = PendingOpType.DELETE_SERVER_COPY,
                folder = "Drafts",
                uids = listOf(1),
                payload = "<old-draft@test>",
                notBefore = clock.now() + 60_000,
            ),
        )
        // The server now reports a different UIDVALIDITY than the cached one.
        store.folders.updateAfterSync(drafts, 1, 0, 0)

        engine.syncFolder(drafts)
        val kept = store.pendingOps.forAccount(account.id).single()
        assertEquals(PendingOpType.DELETE_SERVER_COPY, kept.type)
        assertTrue("its uid means nothing now", kept.uids.isEmpty())

        clock.advance(61_000)
        engine.flushPendingOps(account.id)
        assertTrue(serverMessages("Drafts").isEmpty())
        assertTrue(store.messages.list(drafts, 10, false).isEmpty())
    }

    // ------------------------------------------------------------------ outbox

    @Test
    fun theSentCopyAndAnsweredFlagWaitWhenImapFailsRightAfterTheSend() = runBlocking {
        createFolder("Sent")
        appendMessages("INBOX", listOf(message("Pregunta", from = "Bob <bob@upm.es>", messageId = "<question@test>")))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val source = store.messages.list(inbox, 10, false).single()
        val compose = composeRepository()
        compose.send(compose.createDraft(account.id, ComposeMode.REPLY, source.id).getOrThrow(), delaySeconds = 0)
        store.accounts.update(account.copy(imap = account.imap.copy(port = closedPort())))

        assertEquals(1, engine.sendOutbox().sent)
        assertEquals(listOf("Re: Pregunta"), serverMessages("INBOX", user = "bob").map { it.first })
        assertTrue(store.outbox.list(null).isEmpty())
        assertTrue(serverMessages("Sent").isEmpty())
        val waiting = store.pendingOps.forAccount(account.id)
        assertEquals(listOf(PendingOpType.APPEND_SENT, PendingOpType.FLAG), waiting.map { it.type })
        // A second copy of the same message, as left by an attempt whose answer was lost.
        val saved = sentCopies().single()
        val again = File(saved.parentFile, "again.eml").also { saved.copyTo(it) }
        store.pendingOps.insert(waiting.first().copy(id = 0, payload = again.absolutePath))

        store.accounts.update(account)
        assertEquals(null, engine.flushPendingOps(account.id).blockedBy)
        assertEquals("stored once", listOf("Re: Pregunta"), serverMessages("Sent").map { it.first })
        assertTrue(serverMessages("INBOX").single().second.contains(Flags.Flag.ANSWERED))
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
        assertTrue("saved copies are deleted", sentCopies().isEmpty())
    }

    @Test
    fun aSendCancelledMidwayStillGetsItsSentCopyAndAnsweredFlag() = runBlocking {
        createFolder("Sent")
        appendMessages("INBOX", listOf(message("Pregunta", from = "Bob <bob@upm.es>", messageId = "<question@test>")))
        val inbox = inboxId()
        engine.syncFolder(inbox)
        val source = store.messages.list(inbox, 10, false).single()
        val compose = composeRepository()
        compose.send(compose.createDraft(account.id, ComposeMode.REPLY, source.id).getOrThrow(), delaySeconds = 0)

        // As when JobScheduler stops the outbox job: cancelled once the message is claimed.
        val send = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { engine.sendOutbox() }
        onUpdate = { sql, args ->
            if (sql.startsWith("UPDATE outbox SET state") && args.firstOrNull() == OutboxState.SENDING) send.cancel()
        }
        send.join()
        onUpdate = null

        assertTrue(send.isCancelled)
        assertEquals("the send itself is never interrupted", listOf("Re: Pregunta"), serverMessages("INBOX", user = "bob").map { it.first })
        assertTrue(store.outbox.list(null).isEmpty())
        engine.flushPendingOps(account.id)
        assertEquals(listOf("Re: Pregunta"), serverMessages("Sent").map { it.first })
        assertTrue(serverMessages("INBOX").single().second.contains(Flags.Flag.ANSWERED))
    }

    @Test
    fun refreshingDoesNotWaitForASendInProgress() = runBlocking {
        appendMessages("INBOX", numbered(2))
        val inbox = inboxId()
        composeRepository().send(draftTo("bob@upm.es", "Adjunto grande"), delaySeconds = 0)
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        var claimed = false
        // Holds the sender right after it claimed the message, as a slow upload would.
        onUpdate = { sql, args ->
            if (sql.startsWith("UPDATE outbox SET state") && args.firstOrNull() == OutboxState.SENDING) claimed = true
        }
        onQuery = { sql ->
            if (claimed && sql.startsWith("SELECT * FROM accounts WHERE id")) {
                claimed = false
                sending.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        val send = launch(Dispatchers.IO) { engine.sendOutbox() }
        assertTrue(sending.await(20, TimeUnit.SECONDS))
        onUpdate = null
        onQuery = null

        val refreshed = withTimeoutOrNull(10_000) { engine.syncAccount(account.id) }
        release.countDown()
        send.join()

        assertNotNull("the refresh did not wait for the send", refreshed)
        assertEquals(2, store.messages.count(inbox))
        assertEquals(listOf("Adjunto grande"), serverMessages("INBOX", user = "bob").map { it.first })
    }
}
