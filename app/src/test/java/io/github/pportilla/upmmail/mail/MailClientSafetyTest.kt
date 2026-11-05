package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.data.safety.SafetyPolicy
import io.github.pportilla.upmmail.data.safety.RevocableTestSafety
import io.github.pportilla.upmmail.data.safety.AgeBand
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.data.security.InMemoryPasswordStore
import io.github.pportilla.upmmail.model.Account
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.sync.MailClientProvider
import io.github.pportilla.upmmail.sync.allowLoopbackTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the production provider factory, not a hand-created abortable test client. */
class MailClientSafetyTest {
    private fun account(port: Int): Account {
        val server = ServerSettings("127.0.0.1", port, SecurityMode.NONE, "fixture")
        return Account(1, "fixture@example.invalid", "Fixture", server, server, "", 0, false, 0)
    }

    private fun provider(account: Account, safety: SafetyPolicy): MailClientProvider =
        MailClientProvider(
            { account.takeIf { id -> id.id == it } },
            InMemoryPasswordStore().apply { set(account.id, "fixture-password") },
            transportPolicy = ::allowLoopbackTransport,
            safety = safety,
        )

    private fun adult() = LocalSafetyRepository().apply { acceptTerms(); chooseAgeBand(AgeBand.ADULT) }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun unconfiguredProfileCannotCreateAnyNetworkClient() {
        val safety = LocalSafetyRepository()
        var factoryUsed = false
        val account = account(12345)
        val provider = MailClientProvider(
            { account }, InMemoryPasswordStore(), safety = safety,
            imapFactory = { s, p -> factoryUsed = true; ImapClient(s, p) },
            smtpFactory = { s, p -> factoryUsed = true; SmtpClient(s, p) },
            transportPolicy = ::allowLoopbackTransport,
        )
        listOf<() -> Any>({ provider.imap(1) }, { provider.smtp(1) }, { provider.idler(1) }).forEach { action ->
            val error = runCatching(action).exceptionOrNull()
            assertEquals(MailErrorKind.SAFETY_BLOCKED, (error as? MailException)?.kind)
        }
        assertFalse(factoryUsed)
    }

    @Test
    fun revokingCachedClientInterruptsStalledCommandAndPreventsReauthentication() = runBlocking {
        MailSessions.ensureInitialized()
        FakeImapServer(handler = { /* Deliberately leave the search command unanswered. */ }).use { server ->
            server.exists = 1
            server.uidNext = 2
            val safety = RevocableTestSafety()
            val provider = provider(account(server.port), safety)
            val client = provider.imap(1)
            try {
                val operation = async(Dispatchers.IO) {
                    runCatching { client.searchUids("INBOX", ServerQuery.parse("fixture", ServerQuery.HEADER_FIELDS)!!, null, 10) }
                }
                waitUntil { server.count("UID SEARCH") == 1 }
                safety.revoke()
                // DefaultAppContainer observes policy changes and performs this invalidation.
                provider.invalidate(1)
                assertTrue(withTimeout(2_000) { operation.await() }.isFailure)
                waitUntil { server.open.get() == 0 }
                assertEquals("Revocation closes the socket without waiting for LOGOUT", 0, server.count("LOGOUT"))
                assertTrue(runCatching { client.examine("INBOX") }.isFailure)
                assertEquals("A retained handle cannot authenticate after revocation", 1, server.count("LOGIN"))
            } finally {
                provider.closeAll()
            }
        }
    }

    @Test
    fun closingProviderInterruptsConnectionBeforeServerGreeting() = runBlocking {
        MailSessions.ensureInitialized()
        FakeImapServer(greet = false).use { server ->
            val provider = provider(account(server.port), adult())
            val client = provider.imap(1)
            val operation = async(Dispatchers.IO) { runCatching { client.connect() } }
            waitUntil { server.accepted.get() == 1 }
            provider.closeAll()
            assertTrue(withTimeout(2_000) { operation.await() }.isFailure)
            waitUntil { server.open.get() == 0 }
            assertEquals(0, server.count("LOGIN"))
        }
    }
}
