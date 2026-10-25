package io.github.pportilla.upmmail.mail.demo

import io.github.pportilla.upmmail.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DemoBackendRegistryTest {
    @get:Rule val temp = TemporaryFolder()
    private val settings = ServerSettings("sample.invalid", 993, SecurityMode.SSL_TLS, "sample")
    private val account = Account(1, "review@example.invalid", "Sample", settings, settings, "", 0, false, 0, true)

    @Test fun deletionSerializesWithInFlightConstructionAndCannotResurrectFiles() = runBlocking {
        val stored = AtomicReference<Account?>(account)
        val captured = CountDownLatch(1)
        val continueLookup = CountDownLatch(1)
        val registry = DemoBackendRegistry(temp.root) {
            val snapshot = stored.get()
            captured.countDown()
            check(continueLookup.await(3, TimeUnit.SECONDS))
            snapshot
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val constructing = executor.submit<DemoMailBackend> { registry.get(1) }
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            stored.set(null) // Database deletion occurs before its serialized cleanup callback.
            val removing = executor.submit { registry.remove(1) }
            continueLookup.countDown()
            val retained = constructing.get(3, TimeUnit.SECONDS)
            removing.get(3, TimeUnit.SECONDS)
            assertTrue(runCatching { retained.imap().listFolders() }.isFailure)
            assertTrue(runCatching { registry.get(1) }.isFailure)
            assertFalse(File(temp.root, "1").exists())
        } finally { continueLookup.countDown(); executor.shutdownNow() }
    }

    @Test fun removalWithoutCachedBackendDeletesOnlyThatAccountsPersistedFiles() {
        val first = File(temp.root, "1").apply { mkdirs() }
        File(first, "mailbox-v1.bin").writeText("sample")
        val unrelated = File(temp.root, "2").apply { mkdirs() }
        val keep = File(unrelated, "mailbox-v1.bin").apply { writeText("other sample") }
        val registry = DemoBackendRegistry(temp.root) { null }
        registry.remove(1)
        assertFalse(first.exists())
        assertTrue(keep.exists())
        assertTrue(runCatching { registry.get(1) }.isFailure)
    }

    @Test fun scheduledArrivalRechecksAuthorizationBeforeCreatingOrWritingSampleData() = runBlocking {
        val backend = DemoMailBackend(temp.root, account)
        val error = runCatching {
            backend.simulateIncoming { throw MailException(MailErrorKind.SAFETY_BLOCKED) }
        }.exceptionOrNull() as MailException
        assertEquals(MailErrorKind.SAFETY_BLOCKED, error.kind)
        assertFalse(File(temp.root, "1").exists())
        val count = backend.imap().status("INBOX").messageCount
        var checks = 0
        assertTrue(runCatching {
            backend.simulateIncoming { if (++checks > 1) throw MailException(MailErrorKind.SAFETY_BLOCKED) }
        }.isFailure)
        assertEquals(count, backend.imap().status("INBOX").messageCount)
        assertEquals(count, DemoMailBackend(temp.root, account).imap().status("INBOX").messageCount)
    }
}
