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
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
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
