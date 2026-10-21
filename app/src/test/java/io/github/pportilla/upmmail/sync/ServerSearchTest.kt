package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.db.FolderListEntry
import io.github.pportilla.upmmail.data.db.addTestAccount
import io.github.pportilla.upmmail.mail.FakeImapServer
import io.github.pportilla.upmmail.mail.ImapClient
import io.github.pportilla.upmmail.mail.ImapOptions
import io.github.pportilla.upmmail.mail.IndexedHeader
import io.github.pportilla.upmmail.mail.RemoteFlags
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.SearchFilters
import io.github.pportilla.upmmail.model.SearchHit
import io.github.pportilla.upmmail.model.SearchPhase
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSearchProgress
import io.github.pportilla.upmmail.model.ServerSearchRequest
import io.github.pportilla.upmmail.model.ServerSearchScope
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.model.StepOutcome
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ServerSearchTest : SyncTestBase() {
    private val servers = ArrayList<FakeImapServer>()
    private val searches = ArrayList<ServerSearch>()

    @After
    fun closeSearches() {
        searches.forEach { it.closeAll() }
        servers.forEach { it.close() }
    }

    private fun request(
        query: String,
        accountId: Long = account.id,
        phases: Set<SearchPhase> = setOf(SearchPhase.HEADERS),
        trashAndJunkOnly: Boolean = false,
        limit: Int = 200,
        snippets: Boolean = true,
    ) = ServerSearchRequest(query, ServerSearchScope(listOf(accountId), trashAndJunkOnly = trashAndJunkOnly), phases, limit, snippets)

    private suspend fun run(request: ServerSearchRequest, search: ServerSearch = engine.serverSearch): List<ServerSearchProgress> =
        withTimeout(60_000) { search.search(request).toList() }

    private fun ownSearch(
        clients: MailClientProvider = this.clients,
        limits: ServerSearch.Limits = ServerSearch.Limits(headerStepDeadline = 2_000, textStepDeadline = 300),
    ) = ServerSearch(store, clients, Dispatchers.IO, clock::now, ImapOptions.SEARCH, engine::storeParsedBody, {}, {}, limits)
        .also { searches += it }

    private fun subjects(progress: ServerSearchProgress) = progress.hits.map { it.display.subject }.toSet()

    /** An account on a scripted [server], with just an INBOX. */
    private fun fakeAccount(server: FakeImapServer): Pair<Account, Folder> {
        servers += server
        val fake = store.addTestAccount(
            email = "fake@upm.es",
            imap = ServerSettings("127.0.0.1", server.port, SecurityMode.NONE, "ana"),
            smtp = account.smtp,
        )
        passwords.set(fake.id, password)
        store.folders.applyServerList(fake.id, listOf(FolderListEntry("INBOX", "INBOX", '/', emptySet(), true, 0, FolderRole.INBOX)))
        return fake to store.folders.findByRole(fake.id, FolderRole.INBOX)!!
    }

    private fun header(uid: Long, subject: String) =
        IndexedHeader(uid, "<$uid@test>", subject, EmailAddress("bea@upm.es"), emptyList(), emptyList(), 1_700_000_000_000L, null, 100, RemoteFlags(), false)

    @Test
    fun headersFindUncachedMatchesAndTextFindsBodiesWithPreviews() = runBlocking {
        // Archivo: 3,000 messages never synced, the subject match at sequence 1,500 and a match
        // in the body only at 2,600.
        val fillers = (1..4).map { message("Relleno $it", body = "Nada que ver", messageId = "<relleno-$it@test>") }
        val archive = (1..3_000).map { seq ->
            when (seq) {
                1_500 -> message(
                    "Elecciones a Junta de Escuela",
                    body = "Calendario adjunto",
                    from = "Secretaría <secretaria@upm.es>",
                    messageId = "<junta@test>",
                    date = Date(1_600_000_000_000L),
                )
                2_600 -> message("Aviso", body = "Se convocan elecciones a delegados", messageId = "<aviso@test>", date = Date(1_650_000_000_000L))
                else -> fillers[seq % fillers.size]
            }
        }
        storeOnServer("Archivo", archive)
        // INBOX: 200 messages; the newest matches and gets cached by the sync, an old one does not.
        val inboxMail = numbered(200).toMutableList<MimeMessage>()
        inboxMail[4] = message("Elecciones 2019", messageId = "<e2019@test>", date = Date(1_700_000_000_000L + 5 * 60_000L))
        inboxMail[199] = message("Elecciones: resultados", messageId = "<resultados@test>", date = Date(1_700_000_000_000L + 200 * 60_000L))
        storeOnServer("INBOX", inboxMail)
        engine.syncFolderList(account.id)
        val inbox = engine.inboxOf(account.id).id
        val archivo = store.folders.findByFullName(account.id, "Archivo")!!.id
        engine.syncFolder(inbox)
        val inboxList = store.messages.list(inbox, 500, false).map { it.id }
        assertEquals(50, inboxList.size)

        val headers = run(request("elecciones"))
        val done = headers.last()
        assertTrue(done.finished)
        assertNull(done.error)
        assertFalse(done.truncated)
        assertTrue("progress is streamed", headers.count { !it.finished } >= 2)
        assertTrue(headers.dropLast(1).none { it.finished })
        assertEquals(setOf("Elecciones: resultados", "Elecciones 2019", "Elecciones a Junta de Escuela"), subjects(done))
        val cached = done.hits.single { it.display.subject == "Elecciones: resultados" }
        assertTrue(cached is SearchHit.Cached)
        val junta = done.hits.single { it.display.subject == "Elecciones a Junta de Escuela" } as SearchHit.Indexed
        assertEquals(archivo, junta.entry.folderId)
        assertEquals(FolderRole.ARCHIVE, junta.entry.folderRole)
        assertEquals(1_500L, junta.entry.uid)
        assertEquals("secretaria@upm.es", junta.entry.from?.address)
        assertEquals(1_600_000_000_000L, junta.entry.date)
        assertEquals("<junta@test>", junta.entry.messageIdHeader)
        assertEquals(0L, junta.display.id)
        assertTrue(done.hits.single { it.display.subject == "Elecciones 2019" } is SearchHit.Indexed)
        assertEquals("newest first", "Elecciones: resultados", done.hits.first().display.subject)
        assertTrue(done.problems.isEmpty())
        // Nothing reached `messages`; the matches are in the header index now.
        assertEquals(50, store.messages.count(inbox))
        assertEquals(0, store.messages.count(archivo))
        assertEquals(listOf(junta.entry), store.headerIndex.findByUids(archivo, listOf(1_500L)))
        assertTrue(engine.serverSearch.activeAccounts.value.isEmpty())

        val text = run(request("elecciones", phases = setOf(SearchPhase.TEXT)))
        val textDone = text.last()
        assertNull(textDone.error)
        assertTrue(text.any { it.phase == SearchPhase.TEXT && !it.finished })
        assertTrue(text.any { it.phase == SearchPhase.SNIPPETS })
        val aviso = textDone.hits.single { it.display.subject == "Aviso" }
        assertTrue("a preview was downloaded", aviso is SearchHit.Cached)
        assertTrue(aviso.display.snippet.contains("convocan"))
        assertFalse("subject-only match", "Elecciones a Junta de Escuela" in subjects(textDone))
        // Previews are detached rows: the lists stay as they were.
        assertEquals(inboxList, store.messages.list(inbox, 500, false).map { it.id })
        assertTrue(store.messages.list(archivo, 500, false).isEmpty())
        assertEquals(1, store.messages.count(archivo))
        assertTrue(store.messages.extras(archivo).isNotEmpty())
    }

    @Test
    fun everyWordMustMatchInAnyOfItsSpellings() = runBlocking {
        appendMessages(
            "INBOX",
            listOf(message("Revisión de notas"), message("Revisión del horario"), message("Notas finales"), message("Otra cosa")),
        )
        engine.syncFolderList(account.id)

        assertEquals(setOf("Revisión de notas", "Revisión del horario"), subjects(run(request("revision")).last()))
        assertEquals(setOf("Revisión de notas"), subjects(run(request("revision notas")).last()))
        assertEquals(setOf("Revisión de notas"), subjects(run(request("NOTAS revisión")).last()))
        val nothing = run(request("a"))
        assertEquals("no searchable word", 1, nothing.size)
        assertTrue(nothing.single().finished)
    }

    @Test
    fun theLimitTruncatesAndTheBinsAreSearchedOnRequestOnly() = runBlocking {
        appendMessages("INBOX", (1..5).map { message("Elecciones $it") })
        appendMessages("Trash", listOf(message("Elecciones en la papelera")))
        engine.syncFolderList(account.id)

        val limited = run(request("elecciones", limit = 3)).last()
        assertEquals(3, limited.hits.size)
        assertTrue(limited.truncated)
        assertTrue(limited.finished)

        val all = run(request("elecciones")).last()
        assertEquals(5, all.hits.size)
        assertFalse("Trash is left out", "Elecciones en la papelera" in subjects(all))

        val bins = run(request("elecciones", trashAndJunkOnly = true)).last()
        assertEquals(setOf("Elecciones en la papelera"), subjects(bins))
    }

    @Test
    fun searchesShareOnePooledConnection() = runBlocking {
        appendMessages("INBOX", listOf(message("Elecciones")))
        engine.syncFolderList(account.id)
        val logins = AtomicInteger()
        val counting = MailClientProvider(
            { store.accounts.get(it) },
            passwords,
            imapFactory = { settings, password -> ImapClient(settings) { logins.incrementAndGet(); password() } },
        )
        val search = ownSearch(counting)
        try {
            assertEquals(1, run(request("elecciones"), search).last().hits.size)
            assertEquals(1, run(request("elecciones", phases = setOf(SearchPhase.HEADERS, SearchPhase.TEXT)), search).last().hits.size)
            assertEquals(1, logins.get())
        } finally {
            counting.closeAll()
        }
    }

    @Test
    fun windowsThatTakeTooLongAreSplitAndTheMatchIsStillFound() = runBlocking {
        val searched = CopyOnWriteArrayList<String>()
        val server = FakeImapServer {
            when (FakeImapServer.verbOf(command)) {
                "UID SEARCH" -> {
                    val window = Regex("""(\d+):(\d+)""").find(command)!!.groupValues
                    val first = window[1].toInt()
                    val last = window[2].toInt()
                    searched += "$first:$last"
                    // Big windows, and the oldest one, never get an answer.
                    if (last - first + 1 <= 500 && first != 1) {
                        reply("* SEARCH" + if (1_500 in first..last) " 1500" else "", "$tag OK Search done")
                    }
                }
                "UID FETCH", "FETCH" -> {
                    val uid = Regex("""FETCH (\d+)""").find(command)!!.groupValues[1]
                    reply("* $uid FETCH (UID $uid FLAGS (\\Seen))", "$tag OK Fetch done")
                }
                else -> reply("$tag BAD Unexpected command")
            }
        }.apply {
            exists = 3_000
            uidNext = 3_001
        }
        val (fake, inbox) = fakeAccount(server)
        store.headerIndex.ensureState(inbox.id, 7, 3_001, 3_000)
        store.headerIndex.insertHeaders(inbox.id, 7, listOf(header(1_500, "Elecciones")))

        val progress = run(request("elecciones", fake.id, phases = setOf(SearchPhase.TEXT), snippets = false), ownSearch())
        val done = progress.last()
        assertNull(done.error)
        val hit = done.hits.single() as SearchHit.Indexed
        assertEquals(1_500L, hit.entry.uid)
        assertTrue("flags were refreshed", hit.isRead)
        assertEquals(
            listOf(StepOutcome.TIMED_OUT to (1 to 500)),
            done.problems.map { it.outcome to (it.firstSeq to it.lastSeq) },
        )
        assertTrue(searched.containsAll(listOf("1001:3000", "2001:3000", "2501:3000", "2001:2500", "1501:2000", "1001:1500", "501:1000", "1:500")))
        assertFalse("later windows are planned at the smaller size", "1:1000" in searched)
        assertTrue(progress.filter { it.phase == SearchPhase.TEXT }.maxOf { it.totalSteps } >= 7)
    }

    @Test
    fun cancellingTheSearchClosesItsSocket() = runBlocking {
        val server = FakeImapServer {
            // UID SEARCH is never answered.
            if (FakeImapServer.verbOf(command) != "UID SEARCH") reply("$tag BAD Unexpected command")
        }.apply {
            exists = 10
            uidNext = 11
        }
        val (fake, _) = fakeAccount(server)
        val search = ownSearch(limits = ServerSearch.Limits())
        val job = launch(Dispatchers.IO) { search.search(request("elecciones", fake.id)).collect {} }
        withTimeout(5_000) { while (server.count("UID SEARCH") == 0) delay(20) }
        assertEquals(setOf(fake.id), search.activeAccounts.value)

        job.cancelAndJoin()
        withTimeout(2_000) { while (server.open.get() > 0) delay(20) }
        assertTrue(search.activeAccounts.value.isEmpty())
        assertEquals(0, server.count("LOGOUT"))
    }

    @Test
    fun aCompleteIndexOnlyLeavesNewMailToAsk() = runBlocking {
        val server = FakeImapServer {
            if (FakeImapServer.verbOf(command) == "UID SEARCH") reply("* SEARCH", "$tag OK Search done") else reply("$tag BAD Unexpected command")
        }.apply {
            exists = 3_000
            uidNext = 3_001
        }
        val (fake, inbox) = fakeAccount(server)
        store.headerIndex.ensureState(inbox.id, 7, 3_001, 3_000)
        store.headerIndex.updateState(inbox.id, complete = true, updatedAt = clock.now())
        val search = ownSearch()

        assertNull(run(request("elecciones", fake.id), search).last().error)
        assertEquals("checked a moment ago: no connection at all", 0, server.accepted.get())

        store.headerIndex.updateState(inbox.id, updatedAt = clock.now() - 20 * 60_000L)
        assertNull(run(request("elecciones", fake.id), search).last().error)
        assertEquals(1, server.count("STATUS"))
        assertEquals("no new mail: STATUS is enough", 0, server.count("EXAMINE"))
        assertEquals(clock.now(), store.headerIndex.state(inbox.id)!!.updatedAt)

        clock.advance(20 * 60_000L)
        server.uidNext = 3_004
        server.exists = 3_003
        assertNull(run(request("elecciones", fake.id), search).last().error)
        assertEquals(1, server.count("EXAMINE"))
        val searches = server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" }
        assertEquals(1, searches.size)
        assertTrue(searches.single(), searches.single().startsWith("UID SEARCH UID 3001:*"))
    }

    @Test
    fun shortWordsAreAskedOfTheServerEvenWhenTheIndexIsComplete() = runBlocking {
        val mail = numbered(40, "Relleno").toMutableList<MimeMessage>()
        mail[3] = message("Envío de CVs", messageId = "<cv@test>")
        mail[7] = message("Examen de SO", messageId = "<so@test>")
        storeOnServer("Archivo", mail)
        engine.syncFolderList(account.id)
        val archivo = store.folders.findByFullName(account.id, "Archivo")!!.id
        assertNull(engine.headerIndexer.run(IndexBudget.manual(clock.now(), allowMetered = false)).error)
        assertTrue(store.headerIndex.state(archivo)!!.complete)
        assertEquals("the index finds acronyms", listOf("Examen de SO"), store.headerIndex.search("so", SearchFilters(), 10).map { it.subject })
        assertTrue("but as whole words only", store.headerIndex.search("cv", SearchFilters(), 10).isEmpty())

        val done = run(request("cv")).last()
        assertNull(done.error)
        assertTrue(done.problems.isEmpty())
        assertEquals("the server finds it inside a word", setOf("Envío de CVs"), subjects(done))
        assertTrue("Examen de SO" in subjects(run(request("SO")).last()))
    }

    @Test
    fun foldersNotListedSinceTheUpgradeAreListedFirst() = runBlocking {
        appendMessages("Archivo", listOf(message("Elecciones de delegados")))
        engine.syncFolderList(account.id)
        // As after the upgrade to schema version 2: whether a folder is virtual or shared is unknown.
        store.sql.execSQL("UPDATE folders SET attributes = NULL")

        val done = run(request("elecciones")).last()
        assertNull(done.error)
        assertEquals(setOf("Elecciones de delegados"), subjects(done))
        assertFalse(store.folders.hasUnlistedFolders(account.id))
    }

    @Test
    fun aWordInTheSubjectAndAnotherInTheTextAreFoundTogether() = runBlocking {
        storeOnServer(
            "Archivo",
            listOf(message("Convocatoria de elecciones", body = "Se eligen delegados de curso", messageId = "<x@test>")) + numbered(3, "Relleno"),
        )
        engine.syncFolderList(account.id)

        val done = run(request("elecciones delegados", phases = setOf(SearchPhase.HEADERS, SearchPhase.TEXT), snippets = false)).last()
        assertNull(done.error)
        assertEquals(setOf("Convocatoria de elecciones"), subjects(done))
        assertTrue("one word: the text only", run(request("curso", phases = setOf(SearchPhase.TEXT), snippets = false)).last().hits.size == 1)
    }

    @Test
    fun aServerThatNeverAnswersEndsTheSearchWithAConnectionError() = runBlocking {
        // Accepts the connection but never greets, like a network that swallows port 993.
        val server = FakeImapServer(greet = false)
        val (fake, _) = fakeAccount(server)
        store.folders.applyServerList(
            fake.id,
            listOf("INBOX", "Enviados", "Archivo", "A", "B", "C", "D").map { name ->
                FolderListEntry(name, name, '/', emptySet(), true, 0, if (name == "INBOX") FolderRole.INBOX else FolderRole.OTHER)
            },
        )
        val search = ownSearch(limits = ServerSearch.Limits(headerStepDeadline = 500, textStepDeadline = 500))

        val done = run(request("elecciones", fake.id, phases = setOf(SearchPhase.HEADERS, SearchPhase.TEXT)), search).last()
        assertTrue(done.finished)
        assertEquals(MailErrorKind.NETWORK, done.error?.kind)
        assertTrue("gives up after two tries, not one per folder", server.accepted.get() <= 2)
    }

    @Test
    fun theResyncOfARenumberedFolderDoesNotHoldUpTheSearch() = runBlocking {
        val server = FakeImapServer {
            if (FakeImapServer.verbOf(command) == "UID SEARCH") reply("* SEARCH", "$tag OK Search done") else reply("$tag BAD Unexpected command")
        }.apply {
            exists = 10
            uidNext = 11
        }
        val (fake, inbox) = fakeAccount(server)
        // The cache belongs to an older UIDVALIDITY than the server's 7.
        store.folders.updateAfterSync(inbox.id, 5, 0, 10)
        val resynced = CopyOnWriteArrayList<Long>()
        val syncDone = CompletableDeferred<Unit>()
        val search = ServerSearch(
            store, clients, Dispatchers.IO, clock::now, ImapOptions.SEARCH, engine::storeParsedBody,
            { id -> resynced += id; syncDone.await() }, {}, ServerSearch.Limits(headerStepDeadline = 2_000),
        ).also { searches += it }
        try {
            val done = withTimeout(10_000) { search.search(request("elecciones", fake.id)).toList() }.last()
            assertTrue("finished while the sync still runs", done.finished)
            assertNull(done.error)
            assertEquals(listOf(inbox.id), resynced)
        } finally {
            syncDone.complete(Unit)
        }
    }

    @Test
    fun wordsTheServerCannotSearchAreReported() = runBlocking {
        val server = FakeImapServer(capabilities = "IMAP4rev1") {
            when {
                FakeImapServer.verbOf(command) != "UID SEARCH" -> reply("$tag BAD Unexpected command")
                "CHARSET" in command -> reply("$tag NO [BADCHARSET (US-ASCII)] Unsupported charset")
                else -> reply("* SEARCH", "$tag OK Search done")
            }
        }.apply {
            exists = 10
            uidNext = 11
        }
        val (fake, _) = fakeAccount(server)
        val done = run(request("año", fake.id), ownSearch()).last()
        assertNull(done.error)
        val problem = done.problems.single()
        assertEquals(StepOutcome.FAILED, problem.outcome)
        assertEquals(MailErrorKind.SERVER, problem.error?.kind)
        assertEquals(ServerSearch.CHARSET_REJECTED, problem.error?.message)
    }

    @Test
    fun aLostConnectionEndsTheSearchWithTheError() = runBlocking {
        val server = FakeImapServer {
            // The connection drops in the middle of the search.
            if (FakeImapServer.verbOf(command) == "UID SEARCH") throw java.io.IOException("gone") else reply("$tag BAD Unexpected command")
        }.apply {
            exists = 10
            uidNext = 11
        }
        val (fake, _) = fakeAccount(server)
        val done = run(request("elecciones", fake.id), ownSearch()).last()
        assertTrue(done.finished)
        assertEquals(MailErrorKind.NETWORK, done.error?.kind)
    }
}
