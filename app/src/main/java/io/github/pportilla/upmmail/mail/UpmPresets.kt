package io.github.pportilla.upmmail.mail

import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings

/** The kinds of UPM mailboxes, each served by a different IMAP host. */
enum class UpmAccountKind { STAFF, STUDENT, EXTERNAL, OTHER }

/** Server defaults for one address, plus the alternative login names worth trying. */
data class AccountServerDefaults(
    val kind: UpmAccountKind,
    val imap: ServerSettings,
    val smtp: ServerSettings,
    /** Login names to try in order when the user did not set one explicitly. */
    val imapUsernameCandidates: List<String>,
    val smtpUsernameCandidates: List<String>,
    /** UPM web gateway for this kind of account, if known. */
    val webmailUrl: String?,
)

/**
 * Connection settings published by the UPM IT service (Servicio de Planificación Informática y
 * Comunicaciones):
 *
 * - Staff (PDI/PAS), `@upm.es`: IMAP `correo.upm.es` 993 SSL/TLS.
 * - Students, `@alumnos.upm.es`: IMAP `correo.alumnos.upm.es` 993 SSL/TLS.
 * - External collaborators, `@externos.upm.es`: IMAP `correo.externos.upm.es` 993 SSL/TLS.
 * - Outgoing mail for all of them: SMTP `smtp.upm.es` 587 with STARTTLS and authentication
 *   (port 25 is not accepted).
 *
 * UPM's guides ask for the login *without* the domain on IMAP and the full address on SMTP, but
 * third-party guides report the opposite working too, so both are tried in that order.
 */
object UpmPresets {
    const val SMTP_HOST = "smtp.upm.es"
    const val SMTP_PORT = 587
    const val IMAPS_PORT = 993

    const val STAFF_DOMAIN = "upm.es"
    const val STUDENT_DOMAIN = "alumnos.upm.es"
    const val EXTERNAL_DOMAIN = "externos.upm.es"

    const val STAFF_WEBMAIL_URL = "https://www.upm.es/webmail_personal"
    const val STUDENT_WEBMAIL_URL = "https://www.upm.es/webmail_alumnos"

    /** Suggested domains for the e-mail field autocompletion on the sign-in screen. */
    val knownDomains = listOf(STAFF_DOMAIN, STUDENT_DOMAIN, EXTERNAL_DOMAIN)

    fun kindOf(email: String): UpmAccountKind =
        when (domainOf(email)) {
            STAFF_DOMAIN -> UpmAccountKind.STAFF
            STUDENT_DOMAIN -> UpmAccountKind.STUDENT
            EXTERNAL_DOMAIN -> UpmAccountKind.EXTERNAL
            else -> UpmAccountKind.OTHER
        }

    fun isUpmAddress(email: String): Boolean = kindOf(email) != UpmAccountKind.OTHER

    fun defaultsFor(email: String): AccountServerDefaults {
        val address = email.trim()
        val local = address.substringBefore('@')
        val kind = kindOf(address)
        val imapHost = when (kind) {
            UpmAccountKind.STAFF -> "correo.upm.es"
            UpmAccountKind.STUDENT -> "correo.alumnos.upm.es"
            UpmAccountKind.EXTERNAL -> "correo.externos.upm.es"
            // Not a UPM address: a reasonable guess the user can edit in the advanced settings.
            UpmAccountKind.OTHER -> "imap." + domainOf(address)
        }
        val smtpHost = if (kind == UpmAccountKind.OTHER) "smtp." + domainOf(address) else SMTP_HOST
        val imapCandidates = listOf(local, address).distinct()
        val smtpCandidates = listOf(address, local).distinct()
        return AccountServerDefaults(
            kind = kind,
            imap = ServerSettings(imapHost, IMAPS_PORT, SecurityMode.SSL_TLS, imapCandidates.first()),
            smtp = ServerSettings(smtpHost, SMTP_PORT, SecurityMode.STARTTLS, smtpCandidates.first()),
            imapUsernameCandidates = imapCandidates,
            smtpUsernameCandidates = smtpCandidates,
            webmailUrl = when (kind) {
                UpmAccountKind.STAFF, UpmAccountKind.EXTERNAL -> STAFF_WEBMAIL_URL
                UpmAccountKind.STUDENT -> STUDENT_WEBMAIL_URL
                UpmAccountKind.OTHER -> null
            },
        )
    }

    private fun domainOf(email: String): String = email.trim().substringAfter('@', "").lowercase()
}
