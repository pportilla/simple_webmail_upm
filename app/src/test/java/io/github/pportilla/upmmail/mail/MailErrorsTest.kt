package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderClosedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessageRemovedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.StoreClosedException
import jakarta.mail.internet.InternetAddress
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException
import org.eclipse.angus.mail.util.MailConnectException
import org.eclipse.angus.mail.util.SocketConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class MailErrorsTest {
    private fun kind(t: Throwable) = MailErrors.map(t).kind

    @Test
    fun mapsEachFamily() {
        assertEquals(MailErrorKind.AUTHENTICATION, kind(AuthenticationFailedException("[AUTHENTICATIONFAILED] no")))
        assertEquals(
            MailErrorKind.CERTIFICATE,
            kind(MessagingException("connect", SSLHandshakeException("PKIX").apply { initCause(CertPathValidatorException("bad")) })),
        )
        assertEquals(MailErrorKind.CERTIFICATE, kind(MessagingException("x", CertificateException("expired"))))
        assertEquals(MailErrorKind.CERTIFICATE, kind(SSLHandshakeException("no cipher suites in common")))
        assertEquals(
            "a handshake cut by the network is a network error",
            MailErrorKind.NETWORK,
            kind(SSLHandshakeException("Remote host terminated the handshake").apply { initCause(EOFException()) }),
        )
        assertEquals(MailErrorKind.NETWORK, kind(MailConnectException(SocketConnectException("connect", ConnectException("refused"), "correo.upm.es", 993, 20_000))))
        assertEquals(MailErrorKind.NETWORK, kind(MessagingException("x", UnknownHostException("correo.upm.es"))))
        assertEquals(MailErrorKind.NETWORK, kind(MessagingException("x", ConnectException("refused"))))
        assertEquals(MailErrorKind.NETWORK, kind(MessagingException("x", SocketTimeoutException("Read timed out"))))
        assertEquals(MailErrorKind.NETWORK, kind(MessagingException("x", SocketException("Connection reset"))))
        assertEquals(MailErrorKind.NETWORK, kind(StoreClosedException(null, "closed")))
        assertEquals(MailErrorKind.NETWORK, kind(FolderClosedException(null, "closed")))
        assertEquals(MailErrorKind.NETWORK, kind(IOException("broken pipe")))
        assertEquals(MailErrorKind.NOT_FOUND, kind(FolderNotFoundException()))
        assertEquals(MailErrorKind.NOT_FOUND, kind(MessageRemovedException("gone")))
        assertEquals(MailErrorKind.SERVER, kind(MessagingException("A3 NO [SERVERBUG] oops")))
        assertEquals(MailErrorKind.UNKNOWN, kind(IllegalArgumentException("x")))
        assertEquals(MailErrorKind.UNKNOWN, kind(MessagingException("x", FileNotFoundException("content://gone"))))
    }

    @Test
    fun mapsSmtpFailures() {
        val invalid = SendFailedException("Invalid Addresses", null, emptyArray(), emptyArray(), arrayOf(InternetAddress("nadie@upm.es")))
        assertEquals(MailErrorKind.INVALID_RECIPIENT, kind(invalid))
        val tooLarge = SMTPSendFailedException("DATA", 552, "552 5.3.4 Message size exceeds fixed maximum message size", null, null, null, null)
        assertEquals(MailErrorKind.TOO_LARGE, kind(tooLarge))
        assertEquals(MailErrorKind.TOO_LARGE, kind(SendFailedException("554 5.3.4 Message too big for system")))
        assertEquals(MailErrorKind.INVALID_RECIPIENT, kind(SendFailedException("No recipient addresses")))
    }

    /** What Angus throws when RCPT fails: the refused addresses chained under "Invalid Addresses". */
    private fun rcptFailure(vararg replies: Pair<String, String>): SendFailedException {
        var first: SMTPAddressFailedException? = null
        val invalid = ArrayList<InternetAddress>()
        val unsent = ArrayList<InternetAddress>()
        for ((address, reply) in replies) {
            val code = reply.take(3).toInt()
            val failure = SMTPAddressFailedException(InternetAddress(address), "RCPT TO:<$address>", code, reply)
            if (first == null) first = failure else first.setNextException(failure)
            if (code in 400..499 || code == 552) unsent += InternetAddress(address) else invalid += InternetAddress(address)
        }
        return SendFailedException("Invalid Addresses", first, emptyArray(), unsent.toTypedArray(), invalid.toTypedArray())
    }

    private fun commandFailure(command: String, reply: String) =
        SMTPSendFailedException(command, reply.take(3).toInt(), reply, null, null, emptyArray(), null)

    @Test
    fun temporarySmtpRepliesAreRetryableServerErrors() {
        val temporary = listOf(
            rcptFailure("bea@upm.es" to "450 4.2.0 Greylisted, try again later"),
            rcptFailure("bea@upm.es" to "451 4.7.1 Rate limited", "carlos@upm.es" to "451 4.7.1 Rate limited"),
            rcptFailure("bea@upm.es" to "552 5.2.2 Mailbox full"),
            commandFailure("MAIL FROM:<ana@upm.es>", "421 4.3.2 Service shutting down"),
            commandFailure("DATA", "451 4.3.0 Try again later"),
            commandFailure(".", "452 4.3.1 Insufficient system storage"),
            AuthenticationFailedException("454 4.7.0 Temporary authentication failure\n"),
        )
        for (failure in temporary) {
            val mapped = MailErrors.map(failure)
            assertEquals(failure.message, MailErrorKind.SERVER, mapped.kind)
            assertTrue(failure.message, mapped.temporary)
            assertTrue(failure.message, mapped.isTransient)
        }
    }

    @Test
    fun permanentSmtpRepliesAreNotRetried() {
        fun assertPermanent(expected: MailErrorKind, failure: Exception) {
            val mapped = MailErrors.map(failure)
            assertEquals(failure.message, expected, mapped.kind)
            assertFalse(failure.message, mapped.isTransient)
        }
        assertPermanent(MailErrorKind.INVALID_RECIPIENT, rcptFailure("nadie@upm.es" to "550 5.1.1 User unknown"))
        assertPermanent(
            MailErrorKind.INVALID_RECIPIENT,
            rcptFailure("bea@upm.es" to "451 4.7.1 Try later", "nadie@upm.es" to "550 5.1.1 User unknown"),
        )
        // The same transaction would be refused again: the user has to split the recipients.
        assertPermanent(MailErrorKind.SERVER, rcptFailure("bea@upm.es" to "452 4.5.3 Too many recipients"))
        assertPermanent(MailErrorKind.TOO_LARGE, rcptFailure("bea@upm.es" to "552 5.3.4 Message too big for system"))
        assertPermanent(MailErrorKind.TOO_LARGE, commandFailure(".", "552 5.3.4 Message size exceeds fixed limit"))
        assertPermanent(MailErrorKind.SERVER, commandFailure(".", "554 5.7.1 Message rejected as spam"))
        assertPermanent(
            MailErrorKind.SERVER,
            commandFailure("MAIL FROM:<ana@upm.es>", "553 5.7.1 Sender not allowed").apply {
                setNextException(SMTPSenderFailedException(InternetAddress("ana@upm.es"), "MAIL", 553, "553 5.7.1 Sender not allowed"))
            },
        )
        assertPermanent(MailErrorKind.AUTHENTICATION, AuthenticationFailedException("535 5.7.8 Authentication credentials invalid\n"))
        // IMAP refusals keep their kind; PushWatcher tells passing ones apart itself.
        assertPermanent(MailErrorKind.AUTHENTICATION, AuthenticationFailedException("[UNAVAILABLE] Temporary authentication failure."))
        // An address like 10.4.5.3 is not the "too many recipients" status code.
        assertTrue(MailErrors.map(rcptFailure("bea@upm.es" to "451 4.7.1 Client host [10.4.5.3] rate limited")).isTransient)
    }

    @Test
    fun connectionLostDuringSmtpAuthIsANetworkError() {
        val lost = AuthenticationFailedException(
            "",
            MessagingException("Exception reading response", SocketException("Connection reset")),
        )
        assertEquals(MailErrorKind.NETWORK, kind(lost))
        assertTrue(MailErrors.map(lost).isTransient)
    }

    @Test
    fun onlyNetworkFailuresAndTemporaryRefusalsAreTransient() {
        assertTrue(MailException(MailErrorKind.NETWORK).isTransient)
        assertFalse(MailException(MailErrorKind.SERVER).isTransient)
        assertTrue(MailException(MailErrorKind.SERVER, temporary = true).isTransient)
        assertFalse(MailException(MailErrorKind.UNKNOWN, "x", null).temporary)
    }

    @Test
    fun keepsMailExceptionsAndCauses() {
        val existing = MailException(MailErrorKind.NOT_FOUND, "x")
        assertSame(existing, MailErrors.map(existing))
        val cause = MessagingException("A1 NO Mailbox is full")
        val mapped = MailErrors.map(cause)
        assertSame(cause, mapped.cause)
        assertTrue(mapped.message!!.contains("Mailbox is full"))
        assertTrue(MailErrors.map(MessagingException("connect failed", ConnectException("Connection refused"))).message!!.contains("Connection refused"))
    }

    @Test
    fun recognisesDroppedConnections() {
        assertTrue(MailErrors.isConnectionDrop(StoreClosedException(null, "x")))
        assertTrue(MailErrors.isConnectionDrop(MessagingException("x", SocketException("Connection reset"))))
        assertTrue(MailErrors.isConnectionDrop(IllegalStateException("This operation is not allowed on a closed folder")))
        assertFalse(MailErrors.isConnectionDrop(MessagingException("x", SocketTimeoutException("Read timed out"))))
        assertFalse(MailErrors.isConnectionDrop(AuthenticationFailedException("no")))
        assertFalse(MailErrors.isConnectionDrop(MessagingException("A1 NO nope")))
    }

    @Test
    fun recognisesTemporaryLoginRefusals() {
        val temporary = AuthenticationFailedException("[UNAVAILABLE] Temporary authentication failure. [host:2026-10-21]")
        assertTrue(MailErrors.isTemporaryLoginRefusal(temporary))
        assertTrue("as mapped", MailErrors.isTemporaryLoginRefusal(MailErrors.map(temporary)))
        assertTrue(MailErrors.isTemporaryLoginRefusal(AuthenticationFailedException("[SERVERBUG] Internal error")))
        assertTrue(MailErrors.isTemporaryLoginRefusal(AuthenticationFailedException("454 4.7.0 Temporary authentication failure")))
        assertFalse(MailErrors.isTemporaryLoginRefusal(AuthenticationFailedException("[AUTHENTICATIONFAILED] Authentication failed.")))
        assertFalse(MailErrors.isTemporaryLoginRefusal(AuthenticationFailedException("LOGIN failed")))
        assertFalse(MailErrors.isTemporaryLoginRefusal(AuthenticationFailedException(null)))
        assertFalse("not a login", MailErrors.isTemporaryLoginRefusal(MessagingException("[UNAVAILABLE] try later")))
    }
}
