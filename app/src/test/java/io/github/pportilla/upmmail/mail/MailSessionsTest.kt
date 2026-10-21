package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import jakarta.activation.CommandMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ScheduledExecutorService

class MailSessionsTest {
    private val imap = ServerSettings("correo.upm.es", 993, SecurityMode.SSL_TLS, "ana")
    private val smtp = ServerSettings("smtp.upm.es", 587, SecurityMode.STARTTLS, "ana@upm.es")

    @Test
    fun imapOverSslUsesImapsWithPeekAndTimeouts() {
        val props = MailSessions.imapSession(imap).properties
        assertEquals("imaps", props.getProperty("mail.store.protocol"))
        assertEquals("true", props.getProperty("mail.imaps.ssl.enable"))
        assertEquals("true", props.getProperty("mail.imaps.ssl.checkserveridentity"))
        assertEquals("true", props.getProperty("mail.imaps.peek"))
        assertEquals("262144", props.getProperty("mail.imaps.fetchsize"))
        assertEquals("20000", props.getProperty("mail.imaps.connectiontimeout"))
        assertEquals("60000", props.getProperty("mail.imaps.timeout"))
        assertEquals("false", props.getProperty("mail.mime.address.strict"))
    }

    @Test
    fun smtpWithStartTlsRequiresIt() {
        val props = MailSessions.smtpSession(smtp).properties
        assertEquals("smtp", props.getProperty("mail.transport.protocol"))
        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", props.getProperty("mail.smtp.auth"))
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertNull(props.getProperty("mail.smtps.auth"))

        val ssl = MailSessions.smtpSession(smtp.copy(port = 465, security = SecurityMode.SSL_TLS)).properties
        assertEquals("smtps", ssl.getProperty("mail.transport.protocol"))
        assertEquals("true", ssl.getProperty("mail.smtps.auth"))

        val plain = MailSessions.imapSession(imap.copy(port = 143, security = SecurityMode.NONE)).properties
        assertEquals("imap", plain.getProperty("mail.store.protocol"))
        assertEquals("false", plain.getProperty("mail.imap.starttls.enable"))
    }

    @Test
    fun everyProtocolVerifiesTheHostNameAndTimesOutWrites() {
        val sessions = mapOf(
            "imaps" to MailSessions.imapSession(imap),
            "imap" to MailSessions.imapSession(imap.copy(port = 143, security = SecurityMode.STARTTLS)),
            "smtp" to MailSessions.smtpSession(smtp),
            "smtps" to MailSessions.smtpSession(smtp.copy(port = 465, security = SecurityMode.SSL_TLS)),
        )
        for ((protocol, session) in sessions) {
            val props = session.properties
            // Android ignores checkserveridentity: without the verifier any valid certificate would do.
            assertEquals(protocol, "MailHostnameVerifier", props.getProperty("mail.$protocol.ssl.hostnameverifier.class"))
            assertEquals(protocol, "true", props.getProperty("mail.$protocol.ssl.checkserveridentity"))
            assertEquals(protocol, "60000", props.getProperty("mail.$protocol.writetimeout"))
            assertTrue(protocol, props["mail.$protocol.executor.writetimeout"] is ScheduledExecutorService)
        }
        assertEquals("true", sessions.getValue("imap").properties.getProperty("mail.imap.starttls.required"))
    }

    @Test
    fun systemPropertiesAndContentHandlersAreInstalled() {
        MailSessions.ensureInitialized()
        assertEquals("true", System.getProperty("mail.mime.decodefilename"))
        assertEquals("false", System.getProperty("mail.mime.decodetext.strict"))
        assertEquals("true", System.getProperty("mail.mime.base64.ignoreerrors"))
        val map = CommandMap.getDefaultCommandMap()
        for (type in listOf("text/plain", "text/html; charset=utf-8", "text/xml", "multipart/mixed", "multipart/alternative", "message/rfc822")) {
            assertNotNull(type, map.createDataContentHandler(type))
        }
    }
}
