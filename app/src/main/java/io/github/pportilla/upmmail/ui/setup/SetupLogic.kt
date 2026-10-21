package io.github.pportilla.upmmail.ui.setup

import io.github.pportilla.upmmail.mail.UpmAccountKind
import io.github.pportilla.upmmail.mail.UpmPresets
import io.github.pportilla.upmmail.model.SecurityMode
import io.github.pportilla.upmmail.model.ServerSettings

/*
 * Android-free helpers of the sign-in and account settings forms, unit-tested on the JVM.
 */

/** Which of the two servers of an account a form section edits. */
enum class ServerKind { IMAP, SMTP }

/**
 * Domains worth offering as completions for [email]: all UPM domains while only the user part
 * has been typed, the matching ones while the domain is being typed, none once it is complete
 * (or the text cannot become a UPM address).
 */
fun domainSuggestions(email: String, domains: List<String> = UpmPresets.knownDomains): List<String> {
    val text = email.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return emptyList()
    val at = text.indexOf('@')
    if (at < 0) return domains
    if (at == 0 || text.indexOf('@', at + 1) >= 0) return emptyList()
    val partial = text.substring(at + 1).lowercase()
    return domains.filter { it.startsWith(partial) && it != partial }
}

/** [email] with its domain part replaced by (or completed to) [domain]. */
fun completeEmail(email: String, domain: String): String =
    email.trim().substringBefore('@') + "@" + domain

/**
 * A readable name guessed from the user part of [email]: "ana.garcia@upm.es" becomes
 * "Ana Garcia". Separators (. _ -) become spaces, a "+tag" and digits are dropped, and each
 * word gets an initial capital. Empty when nothing usable is left.
 */
fun displayNameFromEmail(email: String): String {
    val local = email.trim().substringBefore('@').substringBefore('+')
    return local.split('.', '_', '-')
        .map { part -> part.filter { it.isLetter() || it == '\'' } }
        .filter { part -> part.any { it.isLetter() } }
        .joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { it.titlecaseChar() }
        }
}

/** The UPM webmail that matches the typed address (the staff one when unknown). */
fun webmailUrlFor(email: String): String =
    when (UpmPresets.kindOf(email)) {
        UpmAccountKind.STUDENT -> UpmPresets.STUDENT_WEBMAIL_URL
        else -> UpmPresets.STAFF_WEBMAIL_URL
    }

/** True once [email] has a domain the server presets can be derived from. */
fun hasDomain(email: String): Boolean {
    val domain = email.trim().substringAfter('@', "")
    return domain.contains('.') && !domain.endsWith('.') && !domain.startsWith('.')
}

/** The usual port for [security] on [kind]: 993 / 143 for IMAP, 465 / 587 for SMTP. */
fun defaultPort(kind: ServerKind, security: SecurityMode): Int = when (kind) {
    ServerKind.IMAP -> if (security == SecurityMode.SSL_TLS) 993 else 143
    ServerKind.SMTP -> if (security == SecurityMode.SSL_TLS) 465 else 587
}

/**
 * The port to show after the security mode changes from [old] to [new]: the default of the new
 * mode if the port was empty or the default of the old one, otherwise the user's own [port].
 */
fun portAfterSecurityChange(kind: ServerKind, port: String, old: SecurityMode, new: SecurityMode): String {
    val current = parsePort(port)
    return if (current == null || current == defaultPort(kind, old)) defaultPort(kind, new).toString() else port
}

/** The port number in [text], or null if it is not a valid TCP port. */
fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

/** A plausible host name or IP address: no spaces, no scheme, no path. */
fun isValidHost(host: String): Boolean {
    val value = host.trim()
    if (value.isEmpty() || value.length > 253) return false
    if (value.startsWith('[') && value.endsWith(']')) {
        // IPv6 literal.
        return value.length > 2 && value.substring(1, value.length - 1).all { it.isLetterOrDigit() || it == ':' || it == '.' }
    }
    if (value.startsWith('.') || value.endsWith('.') || value.contains("..")) return false
    return value.all { it.isLetterOrDigit() || it == '.' || it == '-' }
}

/** Which fields of a server section are invalid. */
data class ServerFieldErrors(
    val host: Boolean = false,
    val port: Boolean = false,
    val username: Boolean = false,
) {
    val any: Boolean get() = host || port || username
}

fun validateServerFields(host: String, port: String, username: String): ServerFieldErrors =
    ServerFieldErrors(
        host = !isValidHost(host),
        port = parsePort(port) == null,
        username = username.isBlank(),
    )

/** Builds the settings from form text, or null if any field is invalid. */
fun serverSettingsOf(host: String, port: String, security: SecurityMode, username: String): ServerSettings? {
    if (validateServerFields(host, port, username).any) return null
    return ServerSettings(
        host = host.trim().lowercase(),
        port = parsePort(port) ?: return null,
        security = security,
        username = username.trim(),
    )
}
