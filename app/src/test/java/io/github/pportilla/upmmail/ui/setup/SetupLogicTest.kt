package io.github.pportilla.upmmail.ui.setup

import androidx.compose.runtime.saveable.SaverScope
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLogicTest {

    private val allDomains = listOf("upm.es", "alumnos.upm.es", "externos.upm.es")

    @Test
    fun `domain suggestions follow what has been typed`() {
        assertEquals(emptyList<String>(), domainSuggestions(""))
        assertEquals(allDomains, domainSuggestions("ana.garcia"))
        assertEquals(allDomains, domainSuggestions("ana.garcia@"))
        assertEquals(listOf("alumnos.upm.es"), domainSuggestions("ana@al"))
        assertEquals(listOf("externos.upm.es"), domainSuggestions("ana@e"))
        assertEquals(listOf("upm.es"), domainSuggestions("ana@UPM"))
        assertEquals(emptyList<String>(), domainSuggestions("ana@upm.es"))
        assertEquals(emptyList<String>(), domainSuggestions("ana@gmail.com"))
        assertEquals(emptyList<String>(), domainSuggestions("@upm"))
        assertEquals(emptyList<String>(), domainSuggestions("ana@@"))
        assertEquals(emptyList<String>(), domainSuggestions("ana garcia"))
    }

    @Test
    fun `completing an address replaces the partial domain`() {
        assertEquals("ana@alumnos.upm.es", completeEmail("ana@al", "alumnos.upm.es"))
        assertEquals("ana@upm.es", completeEmail(" ana ", "upm.es"))
    }

    @Test
    fun `display name is derived from the user part`() {
        assertEquals("Ana Garcia", displayNameFromEmail("ana.garcia@upm.es"))
        assertEquals("Juan Perez Lopez", displayNameFromEmail("JUAN_PEREZ-LOPEZ@upm.es"))
        assertEquals("Ana", displayNameFromEmail("ana+news@upm.es"))
        assertEquals("Agarcia", displayNameFromEmail("agarcia23@alumnos.upm.es"))
        assertEquals("María Núñez", displayNameFromEmail("maría.núñez@upm.es"))
        assertEquals("", displayNameFromEmail("12345@upm.es"))
        assertEquals("", displayNameFromEmail(""))
    }

    @Test
    fun `webmail link depends on the account kind`() {
        assertEquals(UpmPresets.STUDENT_WEBMAIL_URL, webmailUrlFor("a@alumnos.upm.es"))
        assertEquals(UpmPresets.STAFF_WEBMAIL_URL, webmailUrlFor("a@upm.es"))
        assertEquals(UpmPresets.STAFF_WEBMAIL_URL, webmailUrlFor("a@externos.upm.es"))
        assertEquals(UpmPresets.STAFF_WEBMAIL_URL, webmailUrlFor("ana"))
    }

    @Test
    fun `hasDomain needs a dotted domain`() {
        assertTrue(hasDomain("ana@upm.es"))
        assertFalse(hasDomain("ana@upm"))
        assertFalse(hasDomain("ana@upm."))
        assertFalse(hasDomain("ana"))
    }

    @Test
    fun `ports follow the security mode unless customised`() {
        assertEquals(993, defaultPort(ServerKind.IMAP, SecurityMode.SSL_TLS))
        assertEquals(143, defaultPort(ServerKind.IMAP, SecurityMode.STARTTLS))
        assertEquals(465, defaultPort(ServerKind.SMTP, SecurityMode.SSL_TLS))
        assertEquals(587, defaultPort(ServerKind.SMTP, SecurityMode.STARTTLS))
        assertEquals("143", portAfterSecurityChange(ServerKind.IMAP, "993", SecurityMode.SSL_TLS, SecurityMode.STARTTLS))
        assertEquals("1993", portAfterSecurityChange(ServerKind.IMAP, "1993", SecurityMode.SSL_TLS, SecurityMode.STARTTLS))
        assertEquals("465", portAfterSecurityChange(ServerKind.SMTP, "", SecurityMode.STARTTLS, SecurityMode.SSL_TLS))
    }

    @Test
    fun `ports and hosts are validated`() {
        assertEquals(993, parsePort(" 993 "))
        assertNull(parsePort("0"))
        assertNull(parsePort("65536"))
        assertNull(parsePort("abc"))

        assertTrue(isValidHost("correo.upm.es"))
        assertTrue(isValidHost("192.168.1.10"))
        assertTrue(isValidHost("[::1]"))
        assertFalse(isValidHost(""))
        assertFalse(isValidHost("imap://correo.upm.es"))
        assertFalse(isValidHost("correo upm.es"))
        assertFalse(isValidHost(".upm.es"))
        assertFalse(isValidHost("correo..upm.es"))
    }

    @Test
    fun `server settings are built only from valid fields`() {
        assertEquals(
            ServerSettings("correo.upm.es", 993, SecurityMode.SSL_TLS, "ana.garcia"),
            serverSettingsOf(" Correo.UPM.es ", "993", SecurityMode.SSL_TLS, " ana.garcia "),
        )
        assertNull(serverSettingsOf("correo.upm.es", "", SecurityMode.SSL_TLS, "ana"))
        assertNull(serverSettingsOf("correo.upm.es", "993", SecurityMode.SSL_TLS, " "))
        assertEquals(ServerFieldErrors(host = true, port = true, username = true), validateServerFields("", "x", ""))
    }

    @Test
    fun `changing security moves a default port along`() {
        val form = ServerFormState(ServerKind.SMTP)
        form.load(ServerSettings("smtp.upm.es", 587, SecurityMode.STARTTLS, "ana@upm.es"))
        form.changeSecurity(SecurityMode.SSL_TLS)
        assertEquals("465", form.port)
        form.port = "2525"
        form.changeSecurity(SecurityMode.STARTTLS)
        assertEquals("2525", form.port)
    }

    @Test
    fun `form follows the presets until a server is edited`() {
        val form = SetupFormState()
        form.onEmailChange("ana.garcia@alumnos.upm.es ")
        assertEquals("ana.garcia@alumnos.upm.es", form.email)
        assertEquals("correo.alumnos.upm.es", form.imap.host)
        assertEquals("ana.garcia", form.imap.username)
        assertEquals("smtp.upm.es", form.smtp.host)
        assertEquals("ana.garcia@alumnos.upm.es", form.smtp.username)
        assertEquals("Ana Garcia", form.displayName)

        form.imap.host = "imap.example.org"
        form.onImapEdited()
        form.onEmailChange("luis@upm.es")
        assertEquals("imap.example.org", form.imap.host)
        assertEquals("luis@upm.es", form.smtp.username)
        assertTrue(form.serversEdited)

        form.resetServers()
        assertEquals("correo.upm.es", form.imap.host)
        assertFalse(form.serversEdited)
    }

    @Test
    fun `typed display name is kept when the address changes`() {
        val form = SetupFormState()
        form.onEmailChange("ana@upm.es")
        form.onDisplayNameChange("Ana G.")
        form.onEmailChange("ana.garcia@upm.es")
        assertEquals("Ana G.", form.displayName)
    }

    @Test
    fun `form state survives a save and restore round trip`() {
        val form = SetupFormState()
        form.onEmailChange("ana@upm.es")
        form.onDisplayNameChange("Ana")
        form.advancedExpanded = true
        form.smtp.changeSecurity(SecurityMode.SSL_TLS)
        form.onSmtpEdited()
        form.showErrors = true

        val scope = SaverScope { true }
        val saved = with(SetupFormState.Saver) { scope.save(form) }!!
        val restored = SetupFormState.Saver.restore(saved)!!

        assertEquals(form.email, restored.email)
        assertEquals("Ana", restored.displayName)
        assertTrue(restored.displayNameEdited)
        assertTrue(restored.advancedExpanded)
        assertTrue(restored.smtpEdited)
        assertFalse(restored.imapEdited)
        assertTrue(restored.showErrors)
        assertEquals(form.imap.toSettings(), restored.imap.toSettings())
        assertEquals(SecurityMode.SSL_TLS, restored.smtp.security)
        assertEquals("465", restored.smtp.port)
    }
}
