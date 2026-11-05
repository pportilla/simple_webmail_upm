package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.model.Draft
import io.github.pportilla.upmmail.model.EmailAddress
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Revocation must interrupt the live SMTP transaction, not merely block the next retry. */
class SmtpSafetyTest {
    @Test(timeout = 20_000)
    fun blockingARecipientWhileMailCommandWaitsSendsNoMessageData() = runBlocking {
        exercise(Pause.MAIL) { it.blockSender(RECIPIENT) }
    }

    @Test(timeout = 20_000)
    fun blockingARecipientWhileRcptWaitsSendsNoMessageData() = runBlocking {
        exercise(Pause.RCPT) { it.blockSender(RECIPIENT) }
    }

    @Test(timeout = 20_000)
    fun blockingAProviderReportRecipientInterruptsBeforeData() = runBlocking {
        exercise(Pause.RCPT, report = true) { it.blockSender("abuse@provider.invalid") }
    }

    @Test(timeout = 20_000)
    fun revokingAfterTheTerminatorPreservesUncertainDelivery() = runBlocking {
        exercise(Pause.ACCEPTED, expected = MailErrorKind.SEND_UNCERTAIN) { it.blockSender(RECIPIENT) }
    }

    @Test(timeout = 20_000)
    fun foregroundCheckDuringRcptIsTransientAndSendsNoData() = runBlocking {
        exercise(Pause.RCPT, expected = MailErrorKind.ACCESS_CHECK_PENDING, transient = true) { it.setPlatformCheckPending(true) }
    }

    @Test(timeout = 20_000)
    fun foregroundCheckAfterTerminatorRemainsUncertainAndMustNotAutoRetry() = runBlocking {
        exercise(Pause.ACCEPTED, expected = MailErrorKind.SEND_UNCERTAIN) { it.setPlatformCheckPending(true) }
    }

    private suspend fun exercise(
        pause: Pause,
        report: Boolean = false,
        expected: MailErrorKind = MailErrorKind.SAFETY_BLOCKED,
        transient: Boolean = false,
        revoke: (LocalSafetyRepository) -> Unit,
    ): Unit = coroutineScope {
        val policy = LocalSafetyRepository().apply { completeGeneralSetup() }
        val draft = Draft(
            accountId = 1,
            to = listOf(EmailAddress(if (report) "abuse@provider.invalid" else RECIPIENT)),
            subject = "Local transport test",
            body = "A fictional message for the local transport test.",
        )
        val outboxId = policy.enqueueApproved(draft) { 17L }
        val message = MessageComposer.build(EmailAddress("sender@example.invalid"), draft, emptyList()).mime
        PausedSmtpServer(pause).use { server ->
            val client = SmtpClient(ServerSettings("127.0.0.1", server.port, SecurityMode.NONE, "local-test")) { "local-test" }
            val sending = async(Dispatchers.IO) {
                runCatching {
                    client.send(message, policyChanges = policy.state) { policy.requireQueuedSend(outboxId, draft) }
                }.exceptionOrNull()
            }
            try {
                assertTrue("SMTP did not reach the paused command", server.paused.awaitBounded())
                revoke(policy)
                // The server still withholds its reply: only live socket cancellation can finish.
                val error = withTimeout(5_000) { sending.await() }
                assertTrue("Expected MailException, got $error", error is MailException)
                assertEquals(expected, (error as MailException).kind)
                assertEquals("Only a temporary check before possible delivery may retry", transient, error.isTransient)
                server.resume.countDown()
                assertTrue("SMTP connection did not finish", server.finished.awaitBounded())
                if (pause == Pause.ACCEPTED) {
                    assertEquals(1, server.accepted.get())
                    assertTrue(server.dataLines.get() > 0)
                } else {
                    assertEquals("No DATA payload may follow revocation", 0, server.dataLines.get())
                    assertEquals(0, server.accepted.get())
                }
            } finally {
                server.close()
            }
        }
    }

    private suspend fun CountDownLatch.awaitBounded(): Boolean = withContext(Dispatchers.IO) {
        await(5, TimeUnit.SECONDS)
    }

    private enum class Pause { MAIL, RCPT, ACCEPTED }

    /** One loopback connection; all waits are bounded and close() releases both ends. */
    private class PausedSmtpServer(private val pause: Pause) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val accepted = AtomicInteger()
        val dataLines = AtomicInteger()
        @Volatile private var client: Socket? = null

        init {
            thread(isDaemon = true, name = "smtp-safety-test") {
                try {
                    listener.accept().use { socket ->
                        client = socket
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val output = socket.getOutputStream()
                        fun reply(text: String) {
                            output.write("$text\r\n".toByteArray(Charsets.US_ASCII))
                            output.flush()
                        }
                        fun pauseAt(point: Pause) {
                            if (pause == point) {
                                paused.countDown()
                                if (!resume.await(15, TimeUnit.SECONDS)) throw IOException("Test did not release SMTP reply")
                            }
                        }
                        reply("220 local.invalid ESMTP")
                        while (true) {
                            val line = input.readLine() ?: break
                            when (line.substringBefore(' ').uppercase()) {
                                "EHLO" -> reply("250-local.invalid\r\n250-AUTH PLAIN LOGIN\r\n250 8BITMIME")
                                "AUTH" -> reply("235 2.7.0 Authenticated")
                                "MAIL" -> { pauseAt(Pause.MAIL); reply("250 2.1.0 Ok") }
                                "RCPT" -> { pauseAt(Pause.RCPT); reply("250 2.1.5 Ok") }
                                "DATA" -> {
                                    reply("354 End with a dot")
                                    while (true) {
                                        val data = input.readLine() ?: throw IOException("Connection closed during DATA")
                                        if (data == ".") break
                                        dataLines.incrementAndGet()
                                    }
                                    accepted.incrementAndGet()
                                    pauseAt(Pause.ACCEPTED)
                                    reply("250 2.0.0 Accepted")
                                }
                                "QUIT" -> { reply("221 Bye"); break }
                                else -> reply("250 Ok")
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Expected when revocation closes the in-flight transport.
                } finally {
                    finished.countDown()
                }
            }
        }

        override fun close() {
            resume.countDown()
            runCatching { client?.close() }
            runCatching { listener.close() }
        }
    }

    private companion object {
        const val RECIPIENT = "friend@example.invalid"
    }
}
