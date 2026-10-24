package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.FolderListEntry
import io.github.pportilla.upmmail.data.db.HeaderIndexDao
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.ImapOptions
import io.github.pportilla.upmmail.mail.IndexedHeader
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.IndexedMessage
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HeaderIndexerTest : SyncTestBase() {

    private val indexer get() = engine.headerIndexer

    private suspend fun folderId(name: String): Long {
        engine.syncFolderList(account.id)
        return store.folders.findByFullName(account.id, name)!!.id
    }

    private fun bulk() = IndexBudget.bulk(clock.now(), charging = true)

    /** A bulk-like budget with small chunks, for runs that must stop part way. */
    private fun small(maxMessages: Int = 100_000, chunkSize: Int = 10, pause: Long = 0, allowMetered: Boolean = true) = IndexBudget(
        deadline = clock.now() + 60_000,
        maxMessages = maxMessages,
        chunkSize = chunkSize,
        backfill = true,
        diffs = DiffPolicy.ALL,
        dedicatedConnection = true,
        onlyDirty = false,
        pause = pause,
        allowMetered = allowMetered,
    )

    private fun rows(folderId: Long): List<IndexedMessage> =
        store.headerIndex.findByUids(folderId, store.headerIndex.indexedUids(folderId).toList())

    private fun subjects(folderId: Long): Set<String> = rows(folderId).map { it.subject }.toSet()

    private fun dated(prefix: String, count: Int, year: Int): List<MimeMessage> =
        (1..count).map { i ->
            val time = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + i * 60_000L
            message("$prefix $i", date = Date(time), messageId = "<$prefix-$i@test>")
        }

    /** Sets \Deleted on the server message with [subject] without expunging it. */
    private fun markDeletedOnServer(folderName: String, subject: String) = withServer { server ->
        val folder = server.getFolder(folderName)
        folder.open(Folder.READ_WRITE)
        folder.messages.filter { it.subject == subject }.forEach { it.setFlag(Flags.Flag.DELETED, true) }
        folder.close(false)
    }

    @Test
    fun aBulkRunIndexesEveryIndexableFolderAndCapsTheBins() = runBlocking {
        appendMessages("INBOX", numbered(30, "Entrada"))
        appendMessages("Sent", numbered(5, "Enviado"))
        appendMessages("Drafts", numbered(3, "Borrador"))
        appendMessages("Proyectos", numbered(4, "Proyecto"))
        storeOnServer("Trash", numbered(HEADER_BIN_CAP + 10, "Borrado"))
        val inbox = folderId("INBOX")
        val sent = folderId("Sent")
        val drafts = folderId("Drafts")
        val projects = folderId("Proyectos")
        val trash = folderId("Trash")

        val result = indexer.run(bulk())
        assertNull(result.error)
        assertFalse(result.skipped)
        assertEquals(30 + 5 + 4 + HEADER_BIN_CAP, result.fetched)
        assertFalse(result.moreWork)
        assertTrue(indexer.running.value.isEmpty())

        for ((folder, count) in listOf(inbox to 30, sent to 5, projects to 4, trash to HEADER_BIN_CAP)) {
            val state = store.headerIndex.state(folder)!!
            assertEquals(count, state.indexedCount)
            assertTrue(state.complete)
            assertFalse(state.dirty)
            assertEquals(count, rows(folder).size)
        }
        assertNull("Drafts are never indexed", store.headerIndex.state(drafts))
        val bin = store.headerIndex.state(trash)!!
        assertTrue("older messages exist beyond the cap", bin.capped)
        assertEquals("the newest are kept", 11L, store.headerIndex.indexedUids(trash).first())
        assertFalse(store.headerIndex.state(inbox)!!.capped)

        val newest = rows(inbox).first()
        assertEquals("Entrada 30", newest.subject)
        assertEquals("bea@alumnos.upm.es", newest.from?.address)
        assertTrue("recipients are kept for Sent only", newest.to.isEmpty())
        assertEquals(anaEmail, rows(sent).first().to.single().address)
        assertTrue("messages are not cached by indexing", store.messages.list(inbox, 10, false).isEmpty())

        val again = indexer.run(bulk())
        assertEquals(0, again.fetched)
        assertFalse(again.moreWork)
    }

    @Test
    fun headersAreFetchedNewestFirstAcrossFoldersAndANextRunResumes() = runBlocking {
        appendMessages("INBOX", dated("Viejo", 30, 2020))
        appendMessages("Archivo", dated("Reciente", 30, 2024))
        val inbox = folderId("INBOX")
        val archive = folderId("Archivo")

        val first = indexer.run(small(maxMessages = 30))
        assertEquals(30, first.fetched)
        assertTrue(first.moreWork)
        // Each folder starts with its newest chunk; then the folder whose mail is newer goes on.
        assertEquals(10, rows(inbox).size)
        assertEquals(20, rows(archive).size)
        assertTrue(subjects(archive).containsAll(listOf("Reciente 30", "Reciente 11")))
        assertFalse(store.headerIndex.state(archive)!!.complete)

        val second = indexer.run(bulk())
        assertNull(second.error)
        assertEquals(30, second.fetched)
        assertFalse(second.moreWork)
        assertEquals(30, rows(inbox).size)
        assertEquals(30, rows(archive).size)
        assertTrue(store.headerIndex.state(inbox)!!.complete)
        assertTrue(store.headerIndex.state(archive)!!.complete)
    }

    @Test
    fun theDeadlineStopsARunWithMoreWork() = runBlocking {
        appendMessages("INBOX", numbered(40))
        val inbox = folderId("INBOX")
        // Time runs out once the first chunk is stored.
        onUpdate = { sql, _ -> if ("indexed_count" in sql) clock.advance(30 * 60_000L) }
        val cut = indexer.run(small())
        onUpdate = null
        assertTrue(cut.moreWork)
        assertEquals(10, cut.fetched)
        assertFalse(store.headerIndex.state(inbox)!!.complete)

        val rest = indexer.run(small())
        assertEquals(30, rest.fetched)
        assertFalse(rest.moreWork)
        assertEquals(40, store.headerIndex.state(inbox)!!.indexedCount)
        assertTrue(store.headerIndex.state(inbox)!!.complete)
    }

    @Test
    fun deletedMessagesDoNotBlockCompleteAndExpungesAreFoundByTheCounts() = runBlocking {
        appendMessages("INBOX", numbered(5))
        markDeletedOnServer("INBOX", "Message 2")
        val inbox = folderId("INBOX")

        indexer.run(bulk())
        val state = store.headerIndex.state(inbox)!!
        assertTrue(state.complete)
        assertEquals(4, state.indexedCount)
        assertFalse("Message 2" in subjects(inbox))

        // Expunges Message 2 and Message 4: the count drops by two with no new mail.
        changeOnServer("INBOX", "Message 4") { it.setFlag(Flags.Flag.DELETED, true) }
        val light = indexer.run(IndexBudget.light(clock.now()))
        assertNull(light.error)
        assertEquals(1, light.removed)
        assertEquals(setOf("Message 1", "Message 3", "Message 5"), subjects(inbox))
        assertTrue(store.headerIndex.state(inbox)!!.complete)
        assertEquals(3, store.headerIndex.state(inbox)!!.serverCount)
    }

    @Test
    fun aLightRunDoesNotBackfillAndStartsFromTheServersUidNext() = runBlocking {
        appendMessages("INBOX", numbered(10))
        val inbox = folderId("INBOX")

        val first = indexer.run(IndexBudget.light(clock.now()))
        assertNull(first.error)
        assertEquals(0, first.fetched)
        assertTrue("older mail waits for a bulk run", first.moreWork)
        val state = store.headerIndex.state(inbox)!!
        assertEquals("UIDNEXT from STATUS", 11L, state.uidNext)
        assertEquals(10, state.serverCount)
        assertFalse(state.complete)
        assertEquals(0, state.indexedCount)
        assertNull("an incomplete folder is not compared when nothing older is fetched", state.checkedAt)

        appendMessages("INBOX", listOf(message("Nuevo 1"), message("Nuevo 2")))
        val second = indexer.run(IndexBudget.light(clock.now()))
        assertEquals(2, second.fetched)
        assertEquals(setOf("Nuevo 1", "Nuevo 2"), subjects(inbox))
        assertEquals(13L, store.headerIndex.state(inbox)!!.uidNext)
        assertFalse(store.headerIndex.state(inbox)!!.complete)
    }

    @Test
    fun aRunNotAllowedOnMobileDataStopsWhenTheNetworkBecomesMetered() = runBlocking {
        appendMessages("INBOX", numbered(40))
        val inbox = folderId("INBOX")
        val metered = AtomicBoolean(false)
        val own = HeaderIndexer(store, clients, Dispatchers.IO, clock::now, ImapOptions.INDEX, MutableStateFlow(emptySet())) {}
        own.meteredNetwork = metered::get
        // The phone leaves the Wi-Fi once the first chunk is stored.
        onUpdate = { sql, _ -> if ("indexed_count" in sql) metered.set(true) }
        val cut = own.run(small(allowMetered = false))
        onUpdate = null
        assertNull(cut.error)
        assertTrue(cut.moreWork)
        assertEquals(10, cut.fetched)

        assertEquals("nothing at all while still metered", 0, own.run(small(allowMetered = false)).fetched)
        val agreed = own.run(small(allowMetered = true))
        assertEquals(30, agreed.fetched)
        assertTrue(store.headerIndex.state(inbox)!!.complete)
    }

    @Test
    fun aFolderTheServerRefusesIsNotWorkLeft() = runBlocking {
        appendMessages("INBOX", numbered(3))
        folderId("INBOX")
        // Still listed here but gone from the server: its STATUS fails on every run.
        val ghost = store.folders.insert(account.id, FolderListEntry("Fantasma", "Fantasma", '/', emptySet(), true, 0, FolderRole.OTHER))

        val result = indexer.run(bulk())
        assertNull(result.error)
        assertEquals(3, result.fetched)
        assertFalse("the bulk job does not come back every few minutes for it", result.moreWork)
        assertNull(store.headerIndex.state(ghost))
        val next = IndexJobPlan.bulk(store.headerIndex.status(), result.moreWork, IndexJobPlan.idleRunsAfter(result, 0))!!
        assertEquals(IndexJobPlan.MAINTENANCE_DELAY, next.latencyMillis)
        assertTrue(next.requiresCharging)
    }

    @Test
    fun foldersNotListedSinceTheUpgradeAreListedBeforeIndexing() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = folderId("INBOX")
        // As after the upgrade to schema version 2: whether a folder is virtual or shared is unknown.
        store.sql.execSQL("UPDATE folders SET attributes = NULL")
        assertTrue(store.folders.indexableFolders(account.id).isEmpty())

        val result = indexer.run(bulk())
        assertNull(result.error)
        assertEquals(3, result.fetched)
        assertFalse(store.folders.hasUnlistedFolders(account.id))
        assertTrue(store.headerIndex.state(inbox)!!.complete)
    }

    @Test
    fun aRenumberedFolderIsIndexedAgainFromScratch() = runBlocking {
        appendMessages("INBOX", numbered(3))
        val inbox = folderId("INBOX")
        // An index of an older incarnation of the folder.
        store.headerIndex.ensureState(inbox, uidValidity = 1, uidNext = 50, messageCount = 1)
        val old = IndexedHeader(40, "<viejo@test>", "Viejo", null, emptyList(), emptyList(), 1L, 1L, 10, RemoteFlags(), false)
        assertEquals(1, store.headerIndex.insertHeaders(inbox, 1, listOf(old)))

        val result = indexer.run(bulk())
        assertNull(result.error)
        val state = store.headerIndex.state(inbox)!!
        assertNotEquals(1L, state.uidValidity)
        assertEquals(setOf("Message 1", "Message 2", "Message 3"), subjects(inbox))
        assertTrue(state.complete)
    }

    @Test
    fun aDirtyRunChecksOnlyDirtyFoldersAndDoesNotLogInWithoutThem() = runBlocking {
        val logins = AtomicInteger()
        val counting = MailClientProvider(
            { store.accounts.get(it) },
            passwords,
            imapFactory = { settings, password -> ImapClient(settings) { logins.incrementAndGet(); password() } },
            transportPolicy = ::allowLoopbackTransport,
        )
        val own = HeaderIndexer(store, counting, Dispatchers.IO, clock::now, ImapOptions.INDEX, MutableStateFlow(emptySet())) {}
        try {
            appendMessages("INBOX", numbered(3))
            val inbox = folderId("INBOX")

            val idle = own.run(IndexBudget.dirty(clock.now()))
            assertFalse(idle.skipped)
            assertNull(idle.error)
            assertEquals("nothing dirty: no connection at all", 0, logins.get())

            own.run(bulk())
            assertEquals(3, rows(inbox).size)
            // Our own move or upload added mail.
            appendMessages("INBOX", listOf(message("Movido")))
            store.headerIndex.markDirty(listOf(inbox))
            assertEquals(listOf(inbox), store.headerIndex.dirtyFolders(account.id))

            val dirty = own.run(IndexBudget.dirty(clock.now()))
            assertNull(dirty.error)
            assertEquals(1, dirty.fetched)
            assertTrue("Movido" in subjects(inbox))
            assertTrue(store.headerIndex.dirtyFolders().isEmpty())
            assertTrue(logins.get() > 0)
        } finally {
            counting.closeAll()
        }
    }

    @Test
    fun anActiveSearchPausesTheCrawl() = runBlocking {
        val searching = MutableStateFlow(setOf(account.id))
        val own = HeaderIndexer(store, clients, Dispatchers.IO, clock::now, ImapOptions.INDEX, searching) {}
        appendMessages("INBOX", numbered(20))
        val inbox = folderId("INBOX")

        val run = async(Dispatchers.IO) { own.run(bulk()) }
        delay(700)
        assertTrue(run.isActive)
        assertEquals(setOf(account.id), own.running.value)
        assertTrue("no headers while the search runs", rows(inbox).isEmpty())

        searching.value = emptySet()
        val result = withTimeout(10_000) { run.await() }
        assertEquals(20, result.fetched)
        assertTrue(own.running.value.isEmpty())
    }

    @Test
    fun removingTheAccountMidRunStopsCleanly() = runBlocking {
        appendMessages("INBOX", numbered(40))
        folderId("INBOX")
        val firstChunk = CompletableDeferred<Unit>()
        onUpdate = { sql, _ -> if ("indexed_count" in sql) firstChunk.complete(Unit) }
        val run = async(Dispatchers.IO) { indexer.run(small(pause = 500)) }
        launch(Dispatchers.IO) {
            firstChunk.await()
            store.accounts.delete(account.id)
        }
        val result = withTimeout(10_000) { run.await() }
        onUpdate = null
        assertNull(result.error)
        assertTrue(result.fetched < 40)
        assertTrue(store.headerIndex.statesOf(account.id).isEmpty())
        assertEquals(0, store.headerIndex.accountRowCount(account.id))
        assertTrue(indexer.running.value.isEmpty())
    }

    @Test
    fun invalidatingTheAccountAbortsItsRun() = runBlocking {
        appendMessages("INBOX", numbered(40))
        val inbox = folderId("INBOX")
        val firstChunk = CompletableDeferred<Unit>()
        onUpdate = { sql, _ -> if ("indexed_count" in sql) firstChunk.complete(Unit) }
        val run = async(Dispatchers.IO) { indexer.run(small(pause = 5_000)) }
        firstChunk.await()
        onUpdate = null
        val started = System.nanoTime()
        clients.invalidate(account.id)
        val result = withTimeout(10_000) { run.await() }
        assertTrue("does not wait for the pause", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(result.moreWork)
        assertEquals(10, rows(inbox).size)
        assertTrue(indexer.running.value.isEmpty())
    }

    @Test
    fun aRunIsSkippedOrWaitsWhileAnotherRuns() = runBlocking {
        appendMessages("INBOX", numbered(20))
        folderId("INBOX")
        val searching = MutableStateFlow(setOf(account.id))
        val own = HeaderIndexer(store, clients, Dispatchers.IO, clock::now, ImapOptions.INDEX, searching) {}
        val bulkRun = async(Dispatchers.IO) { own.run(bulk()) }
        withTimeout(10_000) { while (own.running.value.isEmpty()) delay(20) }

        assertTrue("a light run gives way", own.run(IndexBudget.light(clock.now())).skipped)
        val manual = async(Dispatchers.IO) { own.run(IndexBudget.manual(clock.now(), allowMetered = false)) }
        delay(200)
        assertTrue("a manual run waits its turn", manual.isActive)

        searching.value = emptySet()
        assertEquals(20, withTimeout(10_000) { bulkRun.await() }.fetched)
        val after = withTimeout(10_000) { manual.await() }
        assertFalse(after.skipped)
        assertEquals(0, after.fetched)
    }

    private companion object {
        const val HEADER_BIN_CAP = HeaderIndexDao.BIN_CAP
    }
}
