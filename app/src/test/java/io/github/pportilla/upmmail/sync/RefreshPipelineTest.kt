package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.LocalFlag
import io.github.pportilla.upmmail.data.db.PendingOp
import io.github.pportilla.upmmail.data.db.PendingOpType
import io.github.pportilla.upmmail.data.db.addTestFolder
import io.github.pportilla.upmmail.data.db.remoteMessage
import io.github.pportilla.upmmail.data.files.FileAttachmentOpener
import io.github.pportilla.upmmail.data.repository.MailRepositoryImpl
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.data.safety.PlatformRestriction
import io.github.pportilla.upmmail.mail.FakeImapServer
import io.github.pportilla.upmmail.mail.SmtpClient
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.Flags
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real sockets and cache: optional startup work must not monopolise a user's refresh. */
class RefreshPipelineTest : SyncTestBase() {
    private fun scripted(server: FakeImapServer, prefetchBudget: Long = 500): Long {
        clients.closeAll()
        store.accounts.update(account.copy(imap = account.imap.copy(port = server.port)))
        clients = MailClientProvider({ store.accounts.get(it) }, passwords,
            transportPolicy = ::allowLoopbackTransport, safety = store.safety)
        engine = SyncEngine(store, clients, files, FileAttachmentOpener, listener,
            clock = clock::now, prefetchBudgetMillis = prefetchBudget)
        // Headers already arrived in the first sync; its optional body warming is still pending.
        val folder = store.addTestFolder(account.id, "INBOX", FolderRole.INBOX)
        store.messages.upsertHeaders(account.id, folder.id, listOf(remoteMessage(1)))
        store.folders.updateAfterSync(folder.id, 7, 1, 1)
        store.folders.setWindow(folder.id, 1, 1, false)
        server.exists = 1
        server.uidNext = 2
        return folder.id
    }

    private fun repository(timeout: Long = 3_000) = MailRepositoryImpl(
        store, engine, FlushScheduler(scope, engine::flushPendingOps), settings, scope,
        refreshTimeoutMillis = timeout,
    )

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    private fun FakeImapServer.Exchange.answerHeaders() {
        if (command.startsWith("UID FETCH") || command.startsWith("FETCH")) {
            reply("* 1 FETCH (UID 1 FLAGS ())", "$tag OK Done")
        } else reply("$tag BAD Unexpected command")
    }

    @Test(timeout = 10_000)
    fun startupPrefetchDeadlineClosesStalledSocketAndLetsManualRefreshComplete() = runBlocking {
        FakeImapServer(handler = {
            // The server answers headers, then never answers the body structure request.
            if (!command.contains("BODYSTRUCTURE")) answerHeaders()
        }).use { server ->
            val folder = scripted(server, prefetchBudget = 700)
            val startup = async(Dispatchers.IO) { engine.syncFolder(folder) }
            waitUntil { server.commands.any { it.contains("BODYSTRUCTURE") } }
            val refresh = async(Dispatchers.IO) { repository().refreshAccount(account.id) }
            assertTrue(withTimeout(3_000) { refresh.await() }.isSuccess)
            withTimeout(3_000) { startup.await() }
            assertTrue("the timed-out socket was replaced", server.count("LOGIN") >= 2)
            assertFalse("no command lock remains occupied", clients.imap(account.id).isBusy)
            assertTrue(engine.syncingFolders.value.isEmpty())
            val message = store.messages.list(folder, 10, false).single()
            assertFalse("optional bodies can be fetched later", store.messages.ref(message.id)!!.bodyLoaded)
            assertEquals("manual refresh must not start another body prefetch", 1, server.commands.count { it.contains("BODYSTRUCTURE") })
        }
    }

    @Test(timeout = 10_000)
    fun manualRefreshTimeoutClosesStalledSocketAndASecondPullSucceeds() = runBlocking {
        val answer = AtomicBoolean(false)
        FakeImapServer(handler = { if (answer.get()) answerHeaders() }).use { server ->
            val folder = scripted(server)
            val result = repository(timeout = 500).refreshFolder(folder)
            assertEquals(MailErrorKind.NETWORK, (result.exceptionOrNull() as MailException).kind)
            waitUntil { server.open.get() == 0 }
            assertFalse(clients.imap(account.id).isBusy)
            assertTrue(engine.syncingFolders.value.isEmpty())
            assertEquals("cached mail remains readable", 1, store.messages.list(folder, 10, false).size)
            answer.set(true)
            assertTrue(repository().refreshFolder(folder).isSuccess)
            assertEquals(2, server.count("LOGIN"))
        }
    }

    @Test(timeout = 10_000)
    fun manualRefreshDoesNotStartQueuedSmtpAndBackgroundDeliveryStillSendsOnce() = runBlocking {
        appendMessages("INBOX", listOf(message("Visible mail")))
        val smtpCalls = AtomicInteger()
        clients.closeAll()
        clients = MailClientProvider({ store.accounts.get(it) }, passwords,
            transportPolicy = ::allowLoopbackTransport, safety = store.safety,
            smtpFactory = { settings, password -> smtpCalls.incrementAndGet(); SmtpClient(settings, password) })
        engine = SyncEngine(store, clients, files, FileAttachmentOpener, listener)
        val id = composeRepository().send(draftTo("bob@upm.es", "Queued separately"), delaySeconds = 0)
        assertTrue(repository().refreshAccount(account.id).isSuccess)
        assertEquals(0, smtpCalls.get())
        assertNotNull(store.outbox.get(id))
        val folder = engine.inboxOf(account.id)
        val message = store.messages.list(folder.id, 10, false).single()
        assertEquals("Visible mail", message.subject)
        assertFalse(store.messages.ref(message.id)!!.bodyLoaded)
        engine.sendOutbox()
        engine.sendOutbox()
        assertEquals(1, smtpCalls.get())
        assertNull(store.outbox.get(id))
        assertEquals(1, greenMail.receivedMessages.count { it.subject == "Queued separately" })
    }

    @Test(timeout = 10_000)
    fun foregroundCheckDefersRefreshButActualRestrictionFails() = runBlocking {
        val policy = store.safety as LocalSafetyRepository
        policy.setPlatformCheckPending(true)
        val refresh = async { repository().refreshAccount(account.id) }
        yield()
        assertFalse(refresh.isCompleted)
        policy.setPlatformCheckPending(false)
        assertTrue(refresh.await().isSuccess)
        policy.setPlatformCheckPending(true)
        val blocked = async { repository().refreshAccount(account.id) }
        yield()
        policy.updatePlatformRestriction(PlatformRestriction.APPROVAL_DECLINED)
        assertEquals(MailErrorKind.SAFETY_BLOCKED, (blocked.await().exceptionOrNull() as MailException).kind)
    }
    @Test(timeout = 15_000)
    fun manualAccountAndFolderRefreshLeaveDraftUploadForIndependentFlush() = runBlocking {
        createFolder("Drafts")
        appendMessages("INBOX", listOf(message("Mail to read")))
        engine.syncFolderList(account.id)
        val inbox = engine.inboxOf(account.id).id
        val draftId = store.drafts.save(draftTo("bob@upm.es", "Upload independently"))
        store.pendingOps.insert(PendingOp(accountId = account.id, type = PendingOpType.APPEND_DRAFT,
            target = draftId.toString(), notBefore = 0))
        val queued = store.pendingOps.forAccount(account.id)

        assertTrue(repository().refreshAccount(account.id).isSuccess)
        assertTrue(repository().refreshFolder(inbox).isSuccess)
        assertEquals("Refresh does not claim or change the upload", queued, store.pendingOps.forAccount(account.id))
        assertTrue(serverMessages("Drafts").isEmpty())
        assertNull(store.drafts.serverCopy(draftId))
        assertEquals("Mail to read", store.messages.list(inbox, 10, false).single().subject)

        engine.flushPendingOps(account.id)
        engine.flushPendingOps(account.id)
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
        assertEquals(listOf("Upload independently"), serverMessages("Drafts").map { it.first })
        assertNotNull(store.drafts.serverCopy(draftId))
    }

    @Test(timeout = 10_000)
    fun timedOutManualRefreshNeverClaimsQueuedDraftUpload() = runBlocking {
        FakeImapServer(handler = { /* Hold the header read until the refresh deadline. */ }).use { server ->
            val inbox = scripted(server)
            store.addTestFolder(account.id, "Drafts", FolderRole.DRAFTS)
            val draftId = store.drafts.save(draftTo("bob@upm.es", "Must not be interrupted"))
            store.pendingOps.insert(PendingOp(accountId = account.id, type = PendingOpType.APPEND_DRAFT,
                target = draftId.toString(), notBefore = 0))
            val queued = store.pendingOps.forAccount(account.id)
            val result = repository(timeout = 500).refreshFolder(inbox)
            assertEquals(MailErrorKind.NETWORK, (result.exceptionOrNull() as MailException).kind)
            assertEquals(queued, store.pendingOps.forAccount(account.id))
            assertNull(store.drafts.serverCopy(draftId))
            assertEquals("Must not be interrupted", store.drafts.get(draftId)!!.subject)
            assertEquals(0, server.count("APPEND"))
            waitUntil { server.open.get() == 0 }
        }
    }

    @Test(timeout = 15_000)
    fun manualHeadersPreserveQueuedReadAndStarChangesUntilBackgroundFlush() = runBlocking {
        appendMessages("INBOX", listOf(message("Local changes")))
        engine.syncAccount(account.id)
        val inbox = engine.inboxOf(account.id).id
        val original = store.messages.list(inbox, 10, false).single()
        store.tx {
            store.messages.setFlag(listOf(original.id), LocalFlag.READ, true)
            store.messages.setFlag(listOf(original.id), LocalFlag.STARRED, true)
            store.folders.adjustCounts(inbox, -1, 0)
            for (flag in listOf("SEEN", "FLAGGED")) {
                store.pendingOps.insert(PendingOp(accountId = account.id, type = PendingOpType.FLAG,
                    folder = "INBOX", uids = listOf(original.uid), messageIds = listOf(original.id),
                    flag = flag, value = true, notBefore = 0))
            }
        }
        val queued = store.pendingOps.forAccount(account.id)
        appendMessages("INBOX", listOf(message("New arrival")))
        assertTrue(repository().refreshAccount(account.id).isSuccess)
        assertTrue(repository().refreshFolder(inbox).isSuccess)
        val local = store.messages.list(inbox, 10, false)
        assertEquals(setOf("Local changes", "New arrival"), local.map { it.subject }.toSet())
        assertTrue(local.single { it.id == original.id }.isRead)
        assertTrue(local.single { it.id == original.id }.isStarred)
        assertEquals(1, store.folders.get(inbox)!!.unreadCount)
        assertEquals(queued, store.pendingOps.forAccount(account.id))
        val before = serverMessages("INBOX").single { it.first == "Local changes" }.second
        assertFalse(before.contains(Flags.Flag.SEEN)); assertFalse(before.contains(Flags.Flag.FLAGGED))

        engine.flushPendingOps(account.id)
        val after = serverMessages("INBOX").single { it.first == "Local changes" }.second
        assertTrue(after.contains(Flags.Flag.SEEN)); assertTrue(after.contains(Flags.Flag.FLAGGED))
        assertTrue(store.pendingOps.forAccount(account.id).isEmpty())
    }

}
