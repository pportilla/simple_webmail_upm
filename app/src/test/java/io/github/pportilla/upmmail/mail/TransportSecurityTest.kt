package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.data.db.addTestAccount
import io.github.pportilla.upmmail.data.db.newStore
import io.github.pportilla.upmmail.data.repository.EngineCredentialChecker
import io.github.pportilla.upmmail.data.security.InMemoryPasswordStore
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import io.github.pportilla.upmmail.sync.MailClientProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class TransportSecurityTest {
    private val insecure = ServerSettings("must-not-connect.invalid", 143, SecurityMode.NONE, "ana")

    @Test
    fun persistedPlaintextAccountsCannotOpenImapSmtpOrPushClients() {
        val store = newStore()
        val account = store.addTestAccount(imap = insecure, smtp = insecure)
        var factoryCalled = false
        val clients = MailClientProvider(
            { store.accounts.get(it) },
            InMemoryPasswordStore(),
            safety = store.safety,
            imapFactory = { settings, password -> factoryCalled = true; ImapClient(settings, password) },
            smtpFactory = { settings, password -> factoryCalled = true; SmtpClient(settings, password) },
        )
        listOf<() -> Any>(
            { clients.imap(account.id) },
            { clients.smtp(account.id) },
            { clients.idler(account.id) },
        ).forEach { action -> assertBlocked(action) }
        assertFalse("Validation happens before a client or password is used", factoryCalled)
    }

    @Test
    fun directCredentialVerificationRejectsPlaintextBeforeNetworking() = runBlocking {
        for (smtp in listOf(false, true)) {
            try {
                if (smtp) EngineCredentialChecker.checkSmtp(insecure, "secret")
                else EngineCredentialChecker.checkImap(insecure, "secret")
                fail("Plaintext verification must be rejected")
            } catch (error: MailException) {
                assertEquals(MailErrorKind.INSECURE_CONNECTION, error.kind)
            }
        }
    }

    @Test
    fun bothEncryptedModesPassThePolicy() {
        requireEncryptedTransport(insecure.copy(security = SecurityMode.SSL_TLS))
        requireEncryptedTransport(insecure.copy(security = SecurityMode.STARTTLS))
    }

    private fun assertBlocked(action: () -> Any) {
        try {
            action()
            fail("Plaintext account must be rejected")
        } catch (error: MailException) {
            assertEquals(MailErrorKind.INSECURE_CONNECTION, error.kind)
            assertFalse(error.isTransient)
        }
    }
}
