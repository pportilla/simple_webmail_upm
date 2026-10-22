package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Timeouts, aborts and the wire format of server search, against a scripted server. */
class ImapAbortTest {
    private val servers = ArrayList<FakeImapServer>()
    private val clients = ArrayList<ImapClient>()

    @Before
    fun init() = MailSessions.ensureInitialized()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.close() }
    }

    private fun server(
        greet: Boolean = true,
        capabilities: String = "IMAP4rev1 UIDPLUS",
        handler: FakeImapServer.Exchange.() -> Unit = { reply("$tag BAD Unexpected command") },
    ): FakeImapServer = FakeImapServer(greet, capabilities, handler).also { servers += it }

    private fun client(server: FakeImapServer, options: ImapOptions = ImapOptions.SEARCH): ImapClient =
        ImapClient(ServerSettings("127.0.0.1", server.port, SecurityMode.NONE, "ana"), { "secret" }, options)
            .also { clients += it }

    private fun query(text: String, fields: Set<SearchField> = ServerQuery.HEADER_FIELDS) =
        ServerQuery.parse(text, fields)!!

    private suspend fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(20)
        }
    }

    private fun elapsedMillis(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** Answers every UID SEARCH with [uids], except the first [silent] ones, which are never answered. */
    private fun searchAnswering(vararg uids: Long, silent: Int = 0): FakeImapServer.Exchange.() -> Unit {
        val seen = AtomicInteger()
        return {
            if (FakeImapServer.verbOf(command) == "UID SEARCH") {
                if (seen.incrementAndGet() > silent) {
                    reply("* SEARCH" + uids.joinToString("") { " $it" }, "$tag OK Search done")
                }
            } else {
                reply("$tag BAD Unexpected command")
            }
        }
    }

    @Test
    fun searchThatNeverAnswersIsAbortedByTheDeadlineWithoutLogout() = runBlocking {
        val server = server(handler = searchAnswering(3, silent = 1)).apply { exists = 3; uidNext = 4 }
        val client = client(server)

        val started = System.nanoTime()
        val result = withTimeoutOrNull(500) { client.searchUids("INBOX", query("elecciones"), null, 10) }
        assertNull(result)
        assertTrue("took ${elapsedMillis(started)} ms", elapsedMillis(started) < 1_500)
        waitUntil { server.open.get() == 0 }
        delay(200)
        assertEquals("nothing is sent after the abort", 0, server.count("LOGOUT"))
        assertEquals(1, server.count("UID SEARCH"))

        // The next call logs in again and works.
        assertEquals(listOf(3L), client.searchUids("INBOX", query("elecciones"), null, 10).uids)
        assertEquals(2, server.count("LOGIN"))
    }

    @Test
    fun readTimeoutOfTheMainClientIsNotRetried() = runBlocking {
        val server = server(handler = searchAnswering(1, silent = Int.MAX_VALUE)).apply { exists = 1; uidNext = 2 }
        val client = client(server, ImapOptions(readTimeoutMillis = 1_000))
        client.examine("INBOX")

        val started = System.nanoTime()
        try {
            client.searchUids("INBOX", query("elecciones"), null, 10)
            fail("expected a timeout")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.NETWORK, e.kind)
        }
        assertTrue("took ${elapsedMillis(started)} ms", elapsedMillis(started) < 3_000)
        assertEquals("a timeout is never retried", 1, server.count("UID SEARCH"))
        assertEquals(1, server.count("LOGIN"))
    }

    @Test
    fun readTimeoutOfAnAbortableClientClosesTheSocket() = runBlocking {
        val server = server(handler = searchAnswering(1, silent = Int.MAX_VALUE)).apply { exists = 1; uidNext = 2 }
        val client = client(server, ImapOptions.SEARCH.copy(readTimeoutMillis = 1_000))
        try {
            client.searchUids("INBOX", query("elecciones"), null, 10)
            fail("expected a timeout")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.NETWORK, e.kind)
        }
        waitUntil { server.open.get() == 0 }
        delay(200)
        assertEquals(1, server.count("UID SEARCH"))
        assertEquals(0, server.count("LOGOUT"))
    }

    @Test
    fun abortWhileConnectingLeavesNoSocketOpen() = runBlocking {
        val server = server(greet = false)
        val client = client(server)

        val started = System.nanoTime()
        assertNull(withTimeoutOrNull(500) { client.examine("INBOX") })
        assertTrue("took ${elapsedMillis(started)} ms", elapsedMillis(started) < 1_500)
        assertEquals(1, server.accepted.get())
        waitUntil(2_000) { server.open.get() == 0 }
    }

    @Test
    fun abortedIdleClientWorksAgainOnTheNextCall() = runBlocking {
        val server = server(handler = searchAnswering(2)).apply { exists = 2; uidNext = 3 }
        val client = client(server)
        assertEquals(2, client.examine("INBOX").messageCount)
        client.abort()
        waitUntil { server.open.get() == 0 }
        assertEquals(listOf(2L), client.searchUids("INBOX", query("acta"), null, 10).uids)
        assertEquals(2, server.count("LOGIN"))
    }

    @Test
    fun cancellationSurfacesAsCancellation() = runBlocking {
        val server = server(handler = searchAnswering(1, silent = Int.MAX_VALUE)).apply { exists = 1; uidNext = 2 }
        val client = client(server)
        val failure = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.IO) {
            try {
                client.searchUids("INBOX", query("elecciones"), null, 10)
                failure.complete(null)
            } catch (e: Throwable) {
                failure.complete(e)
                throw e
            }
        }
        waitUntil { server.count("UID SEARCH") == 1 }
        assertTrue(client.isBusy)
        withTimeout(2_000) { job.cancelAndJoin() }
        val error = withTimeout(2_000) { failure.await() }
        assertTrue("got $error", error is CancellationException)
        assertFalse(client.isBusy)
        waitUntil { server.open.get() == 0 }
    }

    @Test
    fun closeWhileBusyAbortsTheRunningCall() = runBlocking {
        val server = server(handler = searchAnswering(1, silent = Int.MAX_VALUE)).apply { exists = 1; uidNext = 2 }
        val client = client(server)
        val failure = CompletableDeferred<Throwable?>()
        launch(Dispatchers.IO) {
            try {
                client.searchUids("INBOX", query("elecciones"), null, 10)
                failure.complete(null)
            } catch (e: Exception) {
                failure.complete(e)
            }
        }
        waitUntil { server.count("UID SEARCH") == 1 }
        client.close()
        val error = withTimeout(2_000) { failure.await() }
        assertEquals(MailErrorKind.NETWORK, (error as MailException).kind)
        waitUntil { server.open.get() == 0 }
    }

    @Test
    fun badCharsetIsRetriedWithTheAsciiSpellings() = runBlocking {
        val server = server {
            if (command.contains("CHARSET UTF-8")) {
                reply("$tag NO [BADCHARSET (US-ASCII)] Charset not supported")
            } else {
                reply("* SEARCH 4", "$tag OK Search done")
            }
        }.apply { exists = 5; uidNext = 6 }
        val client = client(server)

        val outcome = client.searchUids("INBOX", query("revisión", ServerQuery.TEXT_FIELDS), null, 10)
        assertEquals(listOf(4L), outcome.uids)
        assertTrue(outcome.charsetRejected)
        assertEquals(
            listOf(
                "UID SEARCH CHARSET UTF-8 OR BODY revisión BODY revision ALL",
                "UID SEARCH BODY revision ALL",
            ),
            server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" },
        )

        // "año" has no ASCII spelling: nothing is searched rather than "ano".
        val enye = client.searchUids("INBOX", query("año", ServerQuery.TEXT_FIELDS), null, 10)
        assertEquals(emptyList<Long>(), enye.uids)
        assertTrue(enye.charsetRejected)
        assertEquals(3, server.count("UID SEARCH"))
    }

    @Test
    fun plainRefusalIsAServerErrorAndNotRetried() = runBlocking {
        val server = server { reply("$tag NO [CANNOT] Search failed") }.apply { exists = 5; uidNext = 6 }
        val client = client(server)
        for (text in listOf("elecciones", "revisión")) {
            try {
                client.searchUids("INBOX", query(text), null, 10)
                fail("expected a refusal")
            } catch (e: MailException) {
                assertEquals(MailErrorKind.SERVER, e.kind)
            }
        }
        assertEquals(2, server.count("UID SEARCH"))
        assertEquals(1, server.count("LOGIN"))
    }

    @Test
    fun uidRangeDropsTheNewestMessageBelowItsStart() = runBlocking {
        // "UID 9:*" with every uid below 9 still matches the last message.
        val server = server(handler = searchAnswering(5)).apply { exists = 5; uidNext = 9 }
        val client = client(server)
        val outcome = client.searchUids("INBOX", query("acta"), SearchRange.UidsFrom(9), 10)
        assertEquals(emptyList<Long>(), outcome.uids)
        assertEquals(0, outcome.totalMatches)
        assertEquals(0, client.searchAllUids("INBOX", fromUid = 9).uids.size)
        assertEquals(
            listOf(
                "UID SEARCH UID 9:* OR OR OR SUBJECT acta FROM acta TO acta CC acta ALL",
                "UID SEARCH UID 9:* ALL",
            ),
            server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" },
        )
    }

    @Test
    fun keptFolderNeedsOneExamineForSeveralSearches() = runBlocking {
        val server = server(handler = searchAnswering(1)).apply { exists = 3; uidNext = 4 }
        val kept = client(server)
        kept.searchUids("INBOX", query("acta"), null, 10)
        kept.searchUids("INBOX", query("junta"), null, 10)
        assertEquals(1, server.count("EXAMINE"))
        assertEquals(0, server.count("CLOSE"))

        // Another folder's status is asked on the open connection, without a second login.
        kept.status("Archivo")
        assertEquals(1, server.count("LOGIN"))
        assertEquals(1, server.count("STATUS"))

        // Moving to another folder closes the first one.
        kept.searchUids("Archivo", query("acta"), null, 10)
        assertEquals(2, server.count("EXAMINE"))
        assertEquals(1, server.count("CLOSE"))
        assertEquals(1, server.count("LOGIN"))

        val plain = client(server, ImapOptions.DEFAULT)
        plain.searchUids("INBOX", query("acta"), null, 10)
        plain.searchUids("INBOX", query("junta"), null, 10)
        assertEquals(4, server.count("EXAMINE"))
        assertEquals(3, server.count("CLOSE"))
    }

    @Test
    fun examineAlwaysAsksTheServerAgain() = runBlocking {
        val server = server(handler = searchAnswering(1)).apply { exists = 3; uidNext = 4; uidValidity = 11 }
        val client = client(server)
        assertEquals(FolderProbe(11, 4, 3), client.examine("INBOX"))
        server.exists = 4
        server.uidNext = 5
        assertEquals(FolderProbe(11, 5, 4), client.examine("INBOX"))
        assertEquals(2, server.count("EXAMINE"))
    }

    @Test
    fun progressLinesAreIgnoredAndDoNotStopTheDeadline() = runBlocking {
        val calls = AtomicInteger()
        val server = server {
            if (calls.incrementAndGet() == 1) {
                reply("* OK Searched 50%", "* OK Searched 90%", "* SEARCH 2 4", "$tag OK Search done")
            } else {
                // A slow server reporting progress forever: the socket never goes quiet.
                while (true) {
                    reply("* OK Searched 10%")
                    Thread.sleep(100)
                }
            }
        }.apply { exists = 4; uidNext = 5 }
        val client = client(server)
        assertEquals(listOf(4L, 2L), client.searchUids("INBOX", query("acta"), null, 10).uids)

        val started = System.nanoTime()
        assertNull(withTimeoutOrNull(800) { client.searchUids("INBOX", query("junta"), null, 10) })
        assertTrue("took ${elapsedMillis(started)} ms", elapsedMillis(started) < 2_000)
        waitUntil { server.open.get() == 0 }
    }

    @Test
    fun sequenceWindowsAreClampedToTheFolder() = runBlocking {
        val server = server(handler = searchAnswering(28, 25)).apply { exists = 30; uidNext = 31 }
        val client = client(server)

        val clamped = client.searchUids("INBOX", query("acta"), SearchRange.Seq(21, 40), 1)
        assertEquals(listOf(28L), clamped.uids)
        assertEquals(2, clamped.totalMatches)
        assertEquals(30, clamped.messageCount)

        val past = client.searchUids("INBOX", query("acta"), SearchRange.Seq(31, 40), 10)
        assertEquals(emptyList<Long>(), past.uids)
        assertEquals(
            "nothing is sent for a window past the last message",
            listOf("UID SEARCH 21:30 OR OR OR SUBJECT acta FROM acta TO acta CC acta ALL"),
            server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" },
        )
    }

    @Test
    fun wireFormatOfHeaderAndTextSearches() = runBlocking {
        val server = server(handler = searchAnswering()).apply { exists = 50_000; uidNext = 60_000 }
        val client = client(server)
        client.searchUids("INBOX", query("elecciones"), SearchRange.Seq(40_001, 50_000), 200)
        client.searchUids("INBOX", query("elecciones", ServerQuery.TEXT_FIELDS), SearchRange.Seq(48_001, 50_000), 200)
        client.searchUids("INBOX", query("revisión", ServerQuery.TEXT_FIELDS), SearchRange.Seq(48_001, 50_000), 200)
        client.searchUids("INBOX", query("\"junta electoral\" 2024"), null, 200)
        assertEquals(
            listOf(
                "UID SEARCH 40001:50000 OR OR OR SUBJECT elecciones FROM elecciones TO elecciones CC elecciones ALL",
                "UID SEARCH 48001:50000 BODY elecciones ALL",
                "UID SEARCH CHARSET UTF-8 48001:50000 OR BODY revisión BODY revision ALL",
                "UID SEARCH OR OR OR SUBJECT \"junta electoral\" FROM \"junta electoral\" TO \"junta electoral\" " +
                    "CC \"junta electoral\" OR OR OR SUBJECT 2024 FROM 2024 TO 2024 CC 2024 ALL",
            ),
            server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" },
        )
    }

    @Test
    fun wireFormatOfAdvancedCriteria() = runBlocking {
        val server = server(handler = searchAnswering()).apply { exists = 50; uidNext = 51 }
        val client = client(server)
        val text = "informe from:ana from:luis to:bea subject:acta -borrador after:2026-06-01 before:2026-06-30 is:unread is:starred"
        client.searchUids("INBOX", query(text), SearchRange.Seq(1, 50), 200)
        client.searchUids("INBOX", query(text, ServerQuery.TEXT_FIELDS), null, 200)
        // An attachment filter alone has no search key: every message of the window matches.
        client.searchUids("INBOX", query("has:attachment"), SearchRange.Seq(1, 50), 200)
        assertEquals(
            listOf(
                "UID SEARCH 1:50 UNSEEN FLAGGED SENTSINCE 31-May-2026 SENTBEFORE 2-Jul-2026 OR FROM ana FROM luis OR TO bea CC bea " +
                    "SUBJECT acta OR OR OR SUBJECT informe FROM informe TO informe CC informe " +
                    "NOT OR OR OR OR SUBJECT borrador FROM borrador TO borrador CC borrador BODY borrador ALL",
                "UID SEARCH UNSEEN FLAGGED SENTSINCE 31-May-2026 SENTBEFORE 2-Jul-2026 OR FROM ana FROM luis OR TO bea CC bea " +
                    "SUBJECT acta BODY informe NOT OR OR OR OR SUBJECT borrador FROM borrador TO borrador CC borrador BODY borrador ALL",
                "UID SEARCH 1:50 ALL",
            ),
            server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" },
        )
    }

    @Test
    fun badCharsetFallbackCoversPeopleSubjectAndExclusions() = runBlocking {
        val server = server {
            if (command.contains("CHARSET UTF-8")) {
                reply("$tag NO [BADCHARSET (US-ASCII)] Charset not supported")
            } else {
                reply("* SEARCH 4", "$tag OK Search done")
            }
        }.apply { exists = 5; uidNext = 6 }
        val client = client(server)

        val outcome = client.searchUids("INBOX", query("from:García to:Muñoz subject:revisión -canción"), null, 10)
        assertEquals(listOf(4L), outcome.uids)
        assertTrue(outcome.charsetRejected)
        assertEquals(
            "UID SEARCH OR FROM garcía FROM garcia OR OR OR TO muñoz CC muñoz TO munoz CC munoz OR SUBJECT revisión SUBJECT revision " +
                "NOT OR OR OR OR OR OR OR OR OR SUBJECT canción FROM canción TO canción CC canción BODY canción " +
                "SUBJECT cancion FROM cancion TO cancion CC cancion BODY cancion ALL",
            server.commands.first { FakeImapServer.verbOf(it) == "UID SEARCH" }.replace("UID SEARCH CHARSET UTF-8", "UID SEARCH"),
        )
        assertEquals(
            "UID SEARCH FROM garcia OR TO munoz CC munoz SUBJECT revision NOT OR OR OR OR SUBJECT cancion FROM cancion TO cancion CC cancion BODY cancion ALL",
            server.commands.filter { FakeImapServer.verbOf(it) == "UID SEARCH" }[1],
        )

        // A subject word without an ASCII spelling cannot be left out.
        val enye = client.searchUids("INBOX", query("from:ana subject:año"), null, 10)
        assertEquals(emptyList<Long>(), enye.uids)
        assertTrue(enye.charsetRejected)
        assertEquals(3, server.count("UID SEARCH"))
    }

    @Test
    fun foldersOutsideThePersonalNamespaceAreMarked() = runBlocking {
        val server = server(capabilities = "IMAP4rev1 NAMESPACE").apply {
            namespace = "* NAMESPACE ((\"\" \"/\")) ((\"Other Users/\" \"/\")) ((\"Shared/\" \"/\"))"
            listing = listOf(
                "* LIST (\\HasNoChildren) \"/\" INBOX",
                "* LIST (\\HasNoChildren \\Sent) \"/\" Sent",
                "* LIST (\\HasNoChildren) \"/\" \"Shared/Proyectos\"",
                "* LIST (\\HasNoChildren) \"/\" \"Other Users/bea/INBOX\"",
                "* LIST (\\HasNoChildren) \"/\" \"Shared stuff\"",
            )
        }
        val folders = client(server, ImapOptions.DEFAULT).listFolders().associateBy { it.fullName }
        assertFalse(NOT_PERSONAL_ATTRIBUTE in folders.getValue("INBOX").attributes)
        assertEquals(setOf("\\hasnochildren", "\\sent"), folders.getValue("Sent").attributes)
        assertTrue(NOT_PERSONAL_ATTRIBUTE in folders.getValue("Shared/Proyectos").attributes)
        assertTrue(NOT_PERSONAL_ATTRIBUTE in folders.getValue("Other Users/bea/INBOX").attributes)
        assertFalse("a name that only starts like the namespace", NOT_PERSONAL_ATTRIBUTE in folders.getValue("Shared stuff").attributes)
    }
}
