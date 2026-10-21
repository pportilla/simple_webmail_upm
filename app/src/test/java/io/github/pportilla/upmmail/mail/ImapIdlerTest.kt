package io.github.pportilla.upmmail.mail

import com.icegreen.greenmail.util.GreenMailUtil
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.angus.mail.imap.IMAPFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ImapIdlerTest : GreenMailTestBase() {

    private fun idler(pass: String = password, refreshMillis: Long = ImapIdler.REFRESH_INTERVAL_MS) =
        ImapIdler(imapSettings(), { pass }, refreshIntervalMillis = refreshMillis)

    private fun deliver(subject: String) =
        GreenMailUtil.sendTextEmail(email, "bea@alumnos.upm.es", subject, "Hola", greenMail.smtp.serverSetup)

    /** Threads currently inside IMAPFolder.idle(). */
    private fun idlingThreads(): List<Thread> =
        Thread.getAllStackTraces()
            .filter { (_, stack) -> stack.any { it.className == IMAPFolder::class.java.name && it.methodName == "idle" } }
            .keys
            .toList()

    private suspend fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(20)
        }
    }

    @Test
    fun deliveredMessageIsReportedWithinSeconds() = runBlocking {
        val idler = idler()
        val idling = CompletableDeferred<Unit>()
        val changes = Channel<ImapIdler.Change>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            idler.run(onIdle = { idling.complete(Unit) }) { changes.trySend(it) }
        }
        try {
            withTimeout(10_000) { idling.await() }
            assertEquals(true, idler.idleSupported)
            deliver("Nuevo")
            assertEquals(ImapIdler.Change.NEW_MESSAGES, withTimeout(5_000) { changes.receive() })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun cancellingClosesTheConnectionAtOnce() = runBlocking {
        val idler = idler()
        val idling = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { idler.run(onIdle = { idling.complete(Unit) }) {} }
        withTimeout(10_000) { idling.await() }
        waitUntil { idlingThreads().isNotEmpty() }

        val started = System.nanoTime()
        withTimeout(3_000) { job.cancelAndJoin() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancellation took $elapsedMillis ms", elapsedMillis < 2_000)
        assertTrue(job.isCancelled)
        // The thread that was blocked reading the IDLE responses is free again: the socket is closed.
        waitUntil(2_000) { idlingThreads().isEmpty() }
        assertFalse(idler.refresh())
    }

    @Test
    fun wrongPasswordIsAuthenticationError() = runBlocking {
        try {
            withTimeout(10_000) { idler(pass = "wrong").run {} }
            fail("login should fail")
        } catch (e: MailException) {
            assertEquals(MailErrorKind.AUTHENTICATION, e.kind)
        }
    }

    @Test
    fun serverWithoutIdleMakesRunReturnForPolling() = runBlocking {
        val idler = idler().apply { disabledCapabilities = setOf("IDLE") }
        withTimeout(10_000) { idler.run(onIdle = { fail("must not idle") }) {} }
        assertEquals(false, idler.idleSupported)
    }

    @Test
    fun refreshChecksTheConnectionAndKeepsIdling() = runBlocking {
        val idler = idler()
        val idling = CompletableDeferred<Unit>()
        val changes = Channel<ImapIdler.Change>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            idler.run(onIdle = { idling.complete(Unit) }) { changes.trySend(it) }
        }
        try {
            withTimeout(10_000) { idling.await() }
            waitUntil { idlingThreads().isNotEmpty() }
            assertTrue(withTimeout(5_000) { idler.refresh() })
            assertTrue(withTimeout(5_000) { idler.refresh() })
            deliver("Después de comprobar")
            assertEquals(ImapIdler.Change.NEW_MESSAGES, withTimeout(5_000) { changes.receive() })
            assertTrue(job.isActive)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun idleIsRenewedPeriodically() = runBlocking {
        val idler = idler(refreshMillis = 200)
        val idles = Channel<Unit>(Channel.UNLIMITED)
        val changes = Channel<ImapIdler.Change>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            idler.run(onIdle = { idles.trySend(Unit) }) { changes.trySend(it) }
        }
        try {
            // A new IDLE command after every check.
            withTimeout(10_000) { repeat(4) { idles.receive() } }
            deliver("Entre renovaciones")
            assertEquals(ImapIdler.Change.NEW_MESSAGES, withTimeout(5_000) { changes.receive() })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun lostConnectionIsNetworkError() = runBlocking {
        val idler = idler()
        val idling = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable>()
        val job = launch(Dispatchers.IO) {
            try {
                idler.run(onIdle = { idling.complete(Unit) }) {}
                failure.complete(AssertionError("run returned"))
            } catch (e: MailException) {
                failure.complete(e)
            }
        }
        try {
            withTimeout(10_000) { idling.await() }
            waitUntil { idlingThreads().isNotEmpty() }
            greenMail.stop()
            val error = withTimeout(10_000) { failure.await() }
            assertEquals(MailErrorKind.NETWORK, (error as MailException).kind)
        } finally {
            job.cancelAndJoin()
            greenMail.start()
        }
    }

    @Test
    fun connectionThatStopsAnsweringIsClosedByTheCheck() = runBlocking {
        SilentImapServer().use { server ->
            val settings = ServerSettings("127.0.0.1", server.port, SecurityMode.NONE, login)
            val idler = ImapIdler(settings, { password }, checkTimeoutMillis = 500)
            val idling = CompletableDeferred<Unit>()
            val failure = CompletableDeferred<MailException>()
            val job: Job = launch(Dispatchers.IO) {
                try {
                    idler.run(onIdle = { idling.complete(Unit) }) {}
                } catch (e: MailException) {
                    failure.complete(e)
                }
            }
            try {
                withTimeout(10_000) { idling.await() }
                waitUntil { idlingThreads().isNotEmpty() }
                assertFalse(withTimeout(5_000) { idler.refresh() })
                assertEquals(MailErrorKind.NETWORK, withTimeout(5_000) { failure.await() }.kind)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    /** Speaks just enough IMAP to log in, open the INBOX and start IDLE, then never answers again. */
    private class SilentImapServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort

        init {
            thread(isDaemon = true, name = "silent-imap") { serve() }
        }

        private fun serve() {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                    fun send(vararg lines: String) {
                        lines.forEach { writer.write(it + "\r\n") }
                        writer.flush()
                    }
                    send("* OK [CAPABILITY IMAP4rev1 IDLE] Ready")
                    var idling = false
                    while (true) {
                        val line = reader.readLine() ?: return
                        if (idling) continue
                        val tag = line.substringBefore(' ')
                        when (line.substringAfter(' ').substringBefore(' ').uppercase()) {
                            "CAPABILITY" -> send("* CAPABILITY IMAP4rev1 IDLE", "$tag OK Done")
                            "LOGIN" -> send("$tag OK [CAPABILITY IMAP4rev1 IDLE] Logged in")
                            "EXAMINE", "SELECT" -> send(
                                "* 0 EXISTS",
                                "* 0 RECENT",
                                "* OK [UIDVALIDITY 1] UIDs valid",
                                "$tag OK [READ-ONLY] Done",
                            )
                            "IDLE" -> {
                                send("+ idling")
                                idling = true
                            }
                            else -> send("$tag OK Done")
                        }
                    }
                }
            } catch (_: IOException) {
                // Closed by the test.
            }
        }

        override fun close() = server.close()
    }
}
